# `:engine` — recitation tracking & feedback engine

Pure Kotlin/JVM (no Android dependencies). Package `com.qurancompanion.engine.*`.
Build/test: `./gradlew :engine:test`.

The engine never contains Quran text. It consumes an abstract `QuranReference` (filled from the
reference SQLite DB by the app) and recognizer output (`RecognitionEvent`). All tests use synthetic
random-letter "words" and synthetic surah/ayah structures.

## Design principles (from the product spec)

- **Silence is the default.** Feedback only above a high confidence threshold, outside waqf/breath
  context, with cooldowns and a per-ayah cap.
- **Only stable tokens commit position.** Provisional tokens only move the display `candidate`.
- **Never advance from Qari playback.** `PlaybackGuard` drops tokens during playback + tail; the
  controller ignores recognition while a hint plays.
- **No sequential assumption.** Repeats, previous ayah, skips, surah jumps and mid-ayah starts are
  handled by local repositioning and global re-search.
- **Ambiguity stays unresolved.** Repeated phrases are only located when the margin over the next
  candidate is large enough.
- **Mistakes are provisional** until the self-correction window passes; re-reciting is never an error.

## Public API (what the Android app calls)

### Reference — `reference`
```kotlin
data class WordRef(globalId: Int, surah: Int, ayah: Int, wordIndex: Int,
                   matchKey: String, phonetic: List<String> = emptyList(), waqfAfter: Boolean = false)
interface QuranReference {
    val wordCount: Int
    fun word(globalId: Int): WordRef
    fun ayahWords(surah: Int, ayah: Int): IntRange?   // global ids
    fun isAyahEnd(globalId: Int): Boolean; fun hasWaqfAfter(globalId: Int): Boolean
    fun isPausePoint(globalId: Int): Boolean; fun position(globalId: Int): WordPosition
}
class InMemoryQuranReference(words: List<WordRef>)   // validates globalId == 0-based index
```
The DB loader maps `word.id` to a contiguous 0-based `globalId`, `norm_skeleton` → `matchKey`
(must use the **same normalization** the word recognizer outputs) and `phonetic` → `phonetic`
(split into phoneme tokens in the recognizer's inventory). A custom `QuranReference` backed by
SQLite directly is fine; the tracker reads every word once at construction.

### Recognition — `recognition`
```kotlin
enum class TokenUnit { WORD, PHONEME }
data class Token(text: String, startMs: Long? = null, endMs: Long? = null, confidence: Float? = null)
data class RecognitionEvent(tokens: List<Token>, isStable: Boolean, timestampMs: Long,
                            unit: TokenUnit = WORD, suppressedWindow: LongRange? = null)
sealed interface RecognizerStatus { Idle; Loading(progress); Ready; Listening; Error(message) }
```
Contract for `QuranRecognitionEngine` implementations: stable events are **appended** (each stable
token delivered once, in order); a provisional event **replaces** the previous provisional
hypothesis. Times must be on the same clock as the controller's `Clock`. Timestamps and confidence
are optional but strongly improve playback isolation and false-alert suppression.

### Session — `session` (main entry point)
```kotlin
class RecitationSessionController(reference: QuranReference, unit: TokenUnit, clock: Clock,
                                  config: SessionConfig = SessionConfig(), newSessionId: () -> String = UUID)
    val uiState: StateFlow<SessionUiState>
    fun handle(input: SessionInput)            // single-threaded: call from one dispatcher
    fun snapshot(): SessionSnapshot?           // persist on every confirmed-position change, pause, stop
    val currentState: SessionState; val history: List<SessionState>; val tracker: QuranPositionTracker
```
`SessionInput`: `Initialize`, `PermissionsResult(granted)`, `ModelDownloadProgress(f)`, `ModelReady`,
`ReferenceReady`, `StartSession(mode)`, `RecoverSession(snapshot)`, `RecognizerStatusChanged(status)`,
`Recognition(event)`, `SpeechActivity(speaking)` (optional VAD), `Tick` (every 250–500 ms),
`RequestLukma(level?)`, `AcceptOffer(level?)`, `DeclineOffer`, `Pause`, `Resume`, `Stop`, `Reset`,
`SetManualPosition(wordId)` (manual recovery: "I have just recited this word" — while listening →
RECITING; while PAUSED/INTERRUPTED/RECOVERING only moves the position; ignored during playback;
withdraws a pending offer and clears held mistakes),
`PlaybackStarted`, `PlaybackEnded`, `Interrupted(reason)`, `InterruptionEnded`, `Failure(message)`.

`SessionUiState`: `state`, `mode`, `sessionId`, `confirmed` (last committed `WordPosition`),
`candidate` (highlight; includes provisional), `nextWordId`, `trackingConfidence`, `pauseClass`,
`pendingOffer: LukmaOffer?`, `activeHint: HintRequest?` (what to play — the app plays it and sends
`PlaybackStarted`/`PlaybackEnded`), `heldMistakes` (in self-correction window, not errors yet),
`feedback` (surfaced mistakes), `repetitions`, `downloadProgress`, `error`.

`SessionSnapshot(sessionId, startTimeMs, mode, lastWordId, lastSurah, lastAyah, lastWordIndex, status, durationMs)`
— `durationMs` excludes paused/interrupted time.

Typical app flow:
```
Initialize → PermissionsResult(true) → [ModelDownloadProgress…] ModelReady → ReferenceReady → READY
StartSession(mode) → start mic+recognizer → RecognizerStatusChanged(Listening) → LOCATING_POSITION
loop: Recognition(event) / Tick / user actions …
pendingOffer != null → show gentle offer; user taps → AcceptOffer() → play activeHint → PlaybackStarted … PlaybackEnded
Stop → persist snapshot()
```
After process death: `RecoverSession(snapshot)` from READY → RECOVERING_SESSION → (Listening) → RECITING.

### Lower-level components (usable on their own)
- `tracking.QuranPositionTracker(reference, unit, config)`: `onStable(tokens): List<TrackUpdate>`,
  `onProvisional(tokens)`, `lastConfirmed`, `candidate`, `confidence`, `isTracking`, `restore(wordId)`, `reset()`.
  `TrackUpdate.kind ∈ LOCATED, ADVANCE, REPEAT, SKIP, JUMP, LOST`.
- `tracking.GlobalPositionSearch.search(query): SearchResult` (ACCEPTED / AMBIGUOUS / INSUFFICIENT),
  `tracking.LocalTracker`.
- `align.Aligner` (`WordAligner()`, `PhonemeAligner()`): `global`, `prefix`, `local` (with margin).
- `align.MistakeClassifier.classify(ops, tokens, contextBefore, previousTokens): List<CandidateMistake>`.
- `session.SelfCorrectionHandler`, `PauseDetector`, `FeedbackPolicy`, `LukmaAssistanceController`,
  `PlaybackGuard`, `SessionTransitions.canTransition(from, to)`.

## State machine

`SessionState` has the 24 spec states; `SessionTransitions` holds the explicit table (ERROR is
reachable from every state, STOPPED from every in-session state). `go()` throws on an illegal
transition (a bug); inputs that make no sense in the current state are ignored. Transient states
(`INITIALIZING`, `PROCESSING`, `ASSISTANCE_OFFERED`, `RESUMING`) are passed through within one
`handle` call and recorded in `history`.

| From | Main transitions |
|---|---|
| IDLE → INITIALIZING → CHECKING_PERMISSIONS → DOWNLOADING_MODEL → FETCHING_REFERENCE → READY | lifecycle inputs |
| READY/STOPPED → STARTING_SESSION → LOCATING_POSITION | StartSession, Listening |
| LOCATING_POSITION → PROCESSING → RECITING | stable tokens located |
| RECITING → PROCESSING → SELF_CORRECTION_WINDOW → POSSIBLE_MISTAKE | mistake held → promoted & approved |
| RECITING → PAUSE_DETECTED → ASSISTANCE_OFFERED → WAITING_FOR_CONFIRMATION | silence ≥ LISTENING, ≥ EVALUATE |
| WAITING_FOR_CONFIRMATION → PLAYING_LUKMA / PLAYING_FULL_AYAH → WAITING_FOR_RETRY → PROCESSING → RESUMING → RECITING | accept, playback end, user recites |
| any listening state ↔ PAUSED / INTERRUPTED (→ RESUMING) | Pause/Resume, Interrupted/InterruptionEnded |

## Algorithms

**Unit abstraction.** `UnitSequence` flattens the reference into the recognizer's unit: one unit per
word (`matchKey`) or the concatenated phonemes, with unit↔word maps. Everything below works on units;
positions are reported as words.

**Global position search.** Inverted index of unit n-grams (sorted `LongArray` of hash|position —
~4 MB for 560k phonemes). Each query n-gram votes for a diagonal (refPos − queryPos); the top-K
buckets get a banded Smith–Waterman alignment whose end must explain the last query tokens
(only cells ending in a match count). Accept only if matches ≥ `minRepositionMatches`, score ≥
`minScore` (rejects scattered chance matches) **and** margin ≥ `minMargin` over the best candidate
ending elsewhere. Otherwise stay in LOCATING_POSITION. Measured: ~0.3 s index build and ~1.5–4.5 ms
per located start on a 75k-word / 560k-phoneme synthetic reference (JVM).

**Local tracking.** (1) *Sequential*: prefix alignment of pending tokens from the word after the last
confirmed one (≤ `maxSequentialSkip` deletions); commit only the prefix ending in a match, and only if
matches ≥ `minSequentialMatches` + errors in that prefix. So a single word lags one word behind until
the next arrives (the `candidate` shows it immediately). (2) *Reposition* inside
[confirmed − `backWindow`, confirmed + `forwardWindow`] with the global acceptance rule → REPEAT
(starts at/before the confirmed word) or SKIP. (3) After `lostAfter` uncommitted units → LOST; global
search runs on every new token and the last confirmed position is kept for UI/persistence.

**Alignment.** Weighted edit distance: match (sim ≥ threshold) scores +sim, substitution −(1−sim),
insertion/deletion −1. Words use normalized Levenshtein on skeleton strings; phonemes use identity
(pluggable similarity).

**Mistake classification** (only on in-sequence ADVANCE ops; repositions never produce mistakes).
Unit ops are grouped into word ops. Rules:
- Inserted token resembling an adjacent matched word → `REPETITION` (informational, never an error).
- Deletion + insertion of the same word within 2, or crossed adjacent substitutions → `ORDER`.
- A substitution whose token is the previous/next reference word → treated as `DELETION`.
- Else `SUBSTITUTION` / `DELETION` / `INSERTION`.

Confidence = base × context × recognizer confidence: base SUB = 1 − 0.6·similarity, DEL 0.9,
ORDER 0.85, INS 0.5; context = 0.6 + 0.1 × matched neighbours (2 before, 2 after). Deletions where a
word-sized silent gap sits where the word should be (also checked on the far side of an identical
neighbouring word) are multiplied by 0.3: the recognizer most likely dropped a word the user said.

**Self-correction.** Error candidates are held. Discarded if any of their words is matched again by a
later update, or a backward repeat starts within 2 words after them. Promoted after ≥ 3 s **and**
≥ 3 further words, or after 8 s regardless. The spec pattern "says X, then restarts and recites the
expected words" yields no feedback (scenario tests cover restart-later, immediate retry, restart-before-commit).

**Feedback policy.** Per mode (`RECITATION`: threshold 0.85, SUB/DEL/ORDER, cooldown 15 s, 1 per
ayah; `MEMORIZATION`: 0.75, + INSERTION, 6 s, 2 per ayah). Near an ayah end / waqf, or after a
breath-length gap: deletions/insertions suppressed, threshold +0.1. No feedback while tracking
confidence < 0.6.

**Pause detection.** Silence since last speech (tokens or VAD) → NORMAL < 3 s ≤ LISTENING < 6 s ≤
EVALUATE < 10 s ≤ LONG. ×2 at an ayah end / waqf. Scaled by pace factor = mean inter-word interval /
700 ms, clamped to [0.8, 1.6]. Lukma is offered at EVALUATE (mid-ayah ≈ 5–6 s for normal pace).

**Lukma.** Offer (never twice in a row for the same word; 20 s cooldown after decline/hint) → user
confirms → `HintRequest` (NEXT_WORD by default; PHRASE = up to 3 words stopping at waqf/ayah end;
FULL_AYAH). User-initiated `RequestLukma` skips the offer; repeated requests for the same word
escalate word → phrase → ayah. **Nothing is ever produced without an explicit user action.**

**Playback guard.** Opened at the hint request (not only at `PlaybackStarted`); while open all
tokens are dropped; afterwards tokens overlapping [start, end + 300 ms] or the recognizer's
`suppressedWindow` are dropped (tokens without timestamps use the event time).

## Tunable parameters

| Where | Parameter | Word default | Phoneme default |
|---|---|---|---|
| `TrackerConfig` | matchThreshold | 0.7 | 1.0 (exact) |
| | ngram | 2 | 4 |
| | minSequentialMatches / maxSequentialSkip | 2 / 2 | 4 / 8 |
| | minRepositionMatches / minScore / minMargin | 3 / 2.8 / 1.5 | 10 / 8 / 4 |
| | lostAfter / maxQuery | 6 / 12 | 25 / 40 |
| | backWindow / forwardWindow / tailSlack | 80 / 120 / 1 | 400 / 600 / 2 |
| | topCandidates / maxPostings | 16 / 2000 | same |
| `ClassifierConfig` | deletionBase, orderBase, insertionBase, substitutionSimilarityWeight | 0.9, 0.85, 0.5, 0.6 | |
| | missedWordGapFraction / missedWordFactor / breathGapMs | 0.6 / 0.3 / 800 | |
| `SelfCorrectionConfig` | windowMs / windowWords / maxHoldMs / repeatMarginWords | 3000 / 3 / 8000 / 2 | |
| `PauseConfig` | listening / evaluate / long ms | 3000 / 6000 / 10000 | |
| | pausePointMultiplier, referenceWordIntervalMs, pace clamp | 2.0, 700, [0.8, 1.6] | |
| `FeedbackConfig` | per-mode threshold / types / cooldown / maxPerAyah; pausePointPenalty; minTrackingConfidence | see above; 0.1; 0.6 | |
| `LukmaConfig` | phraseWords / offerCooldownMs | 3 / 20000 | |
| `SessionConfig` | playbackTailMs / offerAt | 300 / EVALUATE | |

## Validation (synthetic)

88 JVM tests (incl. `ManualPositionTest`): component tests for every class, the state table, and scenarios on a synthetic
reference (3 surahs, 237 ayahs, Zipf word frequencies, a refrain repeated 4×, a phrase shared by two
surahs, an ayah identical across surahs): breathing pause, waqf pause, forgotten word → offer → hint →
resume, repeat current ayah, previous ayah, skip, surah jump, mid-ayah start, ambiguous refrain,
self-correction, noise (300 random tokens), user Lukma escalation, playback isolation, interruption,
snapshot/recovery, phoneme recognizer.

False-alert evaluation (`FalseAlertTest`): 200 correctly recited ayahs (1964 words) × 10 seeds ×
2 modes with realistic noise (8 % one-letter errors, 3 % low-confidence whole-word misrecognitions,
2 % silent drops, 2 % stutters, 2 % junk tokens, breaths): **0 false alerts in 4000 ayahs**.
Harsh noise (misrecognitions at confidence 0.95, drops leaving no timing gap): **13 per 100 ayahs
(Recitation) / 25 per 100 (Memorization)** — such recognizer errors are indistinguishable from real
mistakes at word level, so the real-world false-alert rate is bounded by how often the chosen model
makes *confident* errors. Measure it on real recordings before enabling feedback by default.

## Known limitations

- Synthetic validation only; thresholds must be re-tuned on real recordings and real Quran text
  (very short skeletons, frequent function words, long repeated passages).
- Word matching threshold 0.7 makes 2–3 letter skeletons effectively exact-match.
- Position commits lag one word in word mode (by design); use `candidate` for highlighting.
- Repeated passages longer than `maxQuery` tokens cannot be disambiguated until the user leaves them.
- Insertions (extra words) are not alerted in Recitation mode and rarely in Memorization: at word
  level they look like false starts.
- Skips inside the local window are treated as repositioning, not as "missing words".
- Phoneme mode reports word-level SUBSTITUTION/DELETION only; no Harakah/Tajweed classification.
- Playback isolation without token timestamps relies on the event time; recognizers with long
  latency should supply timestamps or the app should stop feeding audio during playback.
- Not thread-safe; one controller per session, driven from a single dispatcher.
