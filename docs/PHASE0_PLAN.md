# Quran Companion — Phase 0 Feasibility Audit & Implementation Plan

Status: **Draft plan (desk audit).** Date: 2026-11-21.

> **Verification caveat.** This cloud session's network policy blocked `sdk.quranlab.ai`,
> `huggingface.co`, `qul.tarteel.ai` and `tanzil.net`. Every statement below about a third‑party
> SDK, model, text source or license is marked **[UNVERIFIED]** and must be checked against the
> primary source before any decision is final. Nothing here was taken as fact from memory alone
> where a license or capability decides the outcome.

---

## 0. Repository inspection

- Working directory is **empty**: no existing Android project, Gradle config, min SDK or dependencies.
- Consequence: greenfield project. Nothing to preserve, so the spec's suggested structure can be
  adopted, simplified to avoid over‑engineering (see §6).
- Builds and real‑device tests need an Android SDK and a physical phone; this container can host
  JVM unit tests for the pure‑Kotlin logic (tracking, alignment, state machine), but microphone,
  foreground service, Bluetooth and lock‑screen behaviour must be tested on the user's devices.

---

## 1. Feasibility summary

| Area | Feasible? | Main condition |
|---|---|---|
| On‑device recognition | Likely | Depends on the chosen model meeting latency/accuracy on mid‑range phones |
| Automatic position detection | Yes | Phonetic index over the full Quran + local alignment; validated on a corpus |
| Continuous word tracking | Yes | Needs word boundaries/timestamps, or alignment‑derived boundaries |
| Word‑level mistakes (missing/extra/repeated/wrong/order) | Yes, conservatively | Alignment against the reference; tune for very low false alerts |
| Consonant / Harakah errors | **Uncertain** | Only with a phoneme‑level model **and** a teacher‑annotated test set |
| Tajweed (Ghunnah, Madd, Qalqalah…) | **Not in MVP** | Each rule needs its own validation |
| Locked‑screen listening | Yes | Microphone foreground service started while the app is visible (Android 14+ rules) |
| Bluetooth earbuds mic | Partly | HFP/SCO mic mode lowers audio quality and changes playback routing; must be tested |
| Offline | Yes | After model, text and Qari audio downloads |
| Commercial release | **Blocked** until licensing (§3) is resolved |

---

## 2. Recognition model recommendation

**Decision rule:** benchmark both candidates on the same small corpus before integrating either
(spec §9). Do not ship both.

### Candidate A — Quran‑Lab TajweedFormer V4 (`sdk.quranlab.ai`)
- Claimed: on‑device streaming **phoneme** recognition, word boundaries, timing, confidence,
  stable vs provisional tokens, some pronunciation signals. **[UNVERIFIED]**
- Strength: the only candidate that could support Harakah/consonant feedback and stable‑token
  self‑correction directly.
- **Blocker:** NPL‑1.2 reportedly restricts monetizing features powered by the model
  (subscriptions, ads). **[UNVERIFIED — read the license text]**

### Candidate B — Quran ASR Small (Abdou, Hugging Face)
- Claimed: MIT, ~33M params, ~36 MB INT8 ONNX, Quran Arabic **word** recognition, no detailed
  Harakah output. **[UNVERIFIED]**
- Strength: permissive license, small, runs with ONNX Runtime Mobile.
- Limits: word‑level only → supports tracking and word‑level mistakes, **not** vowel errors.
  Streaming/timestamps support must be checked; if it is non‑streaming, use a sliding window
  (e.g. 2–4 s chunks with overlap) and treat overlap‑agreed words as "stable".

### Recommendation
1. Build the app around a `QuranRecognitionEngine` interface (spec §9) so the model is swappable.
2. **If the app will be monetized** (ads/subscriptions/paid): plan the MVP on Candidate B and
   restrict mistake detection to word‑level categories, *unless* Quran‑Lab grants a commercial
   license in writing.
3. **If the app is free with no monetization**: Candidate A is likely the better technical fit,
   subject to its license actually permitting that use and redistribution in an app.
4. Fallbacks to evaluate if both disappoint: fine‑tuned Whisper‑tiny/base or a CTC
   (wav2vec2/Conformer) Quran model, quantized to INT8 for ONNX/TFLite — check each license.

Benchmark (Phase 2 gate): per‑device latency (word appears → stable), real‑time factor, RAM,
battery per 30 min, WER on clean / noisy / pocket / earbud audio, Indo‑Pak‑accent reciters,
Tarteel vs Hadr.

---

## 3. License risk assessment

| Item | Risk | Action |
|---|---|---|
| Quran‑Lab model (NPL‑1.2) | **High** for any monetized app | Read license; email Quran‑Lab for commercial terms; get written permission or use B |
| Abdou ASR (MIT) | Low | Confirm license file and training‑data terms on the model card |
| Quran text (Tanzil / QUL / Quran.com) | Low–medium | Tanzil historically requires verbatim text, attribution and a source link **[UNVERIFIED]**; check each source's terms |
| Indo‑Pak fonts (e.g. IndoPak Nastaleeq, KFGQPC) | Medium | Font licenses vary; confirm embedding/redistribution rights |
| Qari audio (EveryAyah, Quran.com CDN, QUL) | **Medium–high** | Per‑reciter rights are often unclear; get permission or use explicitly licensed recordings |
| Word‑timing segments (for word hints) | Medium | Check license of timing data used to cut word/phrase hints |

**Rule:** no public/commercial release until every row is "Low" with written evidence in
`docs/licenses/`.

---

## 4. Quran reference recommendation

Three **separate, versioned** data layers, all keyed by `(surah, ayah, wordIndex)`:

1. **Display text (Indo‑Pak)** — verified Indo‑Pak script, word‑by‑word, with waqf signs and
   symbols. Candidates: QUL (Tarteel's Quranic Universal Library) Indo‑Pak script; Quran.com API
   `text_indopak`. **[UNVERIFIED]** Pair with a matching Indo‑Pak font; render word by word so the
   current word can be highlighted.
2. **Canonical reference (Uthmani/Hafs)** — e.g. Tanzil Uthmani text, used as the indexing
   backbone and to cross‑check word counts against the Indo‑Pak layer. Word segmentation differs
   between scripts in places, so build and **test** an explicit word mapping table; never assume
   identity (spec §2).
3. **Phonetic reference** — generated **deterministically** by a rule‑based Hafs grapheme‑to‑
   phoneme converter from the canonical text (or supplied by the chosen SDK), in the model's
   token set. Never generated or edited by an LLM. Spot‑checked by a qualified teacher.

Packaging: build these offline with a script into a single read‑only SQLite asset
(`quran_ref_vX.db`) with a manifest (source, version, checksum). The app checks the manifest so the
model and reference versions match.

Mushaf pagination (Indo‑Pak 15/16‑line page layouts) is **post‑MVP** unless a verified,
licensed layout dataset is found.

---

## 5. Background audio feasibility (Android)

- **Foreground service** with `foregroundServiceType="microphone"`, permissions `RECORD_AUDIO`,
  `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS` (13+).
- Android 14+ will not let a mic foreground service start while the app is in the background.
  The session must be **started from the visible app** (or a notification action). After that it
  continues with the screen locked / phone in pocket. Acceptable for this product.
- Capture: `AudioRecord`, 16 kHz mono PCM16, source `VOICE_RECOGNITION` (less processing).
- **Bluetooth mic:** using the earbud mic requires HFP/SCO (`setCommunicationDevice` on 12+),
  which narrows bandwidth and switches playback to the call channel. Test whether the model
  tolerates it; otherwise default to phone mic + earbud playback (A2DP).
- **Playback isolation:** while a hint plays, pause feeding audio to the recognizer, drop tokens
  whose timestamps overlap playback (+ ~300 ms tail), and never advance position from playback.
  Add `AcousticEchoCanceler` where available as a second guard.
- Audio focus, headset unplug (`ACTION_AUDIO_BECOMING_NOISY`), phone calls (focus loss /
  `AudioRecord` silenced callbacks) → state `INTERRUPTED`, keep last confirmed position.
- Process death: persist last confirmed position on every commit (Room); offer resume.
- Battery: target < ~8–10 %/hour on a mid‑range device; measure in Phase 2. Run inference in
  batches on a background dispatcher; no wakelock beyond what the FGS needs.

---

## 6. Architecture proposal

Single Gradle **app module** to start (package‑level separation), plus one pure‑Kotlin
`:engine` module for the testable logic. Split further only if build time or reuse demands it.

```
:app
  ui/          onboarding, home, recitation, quran, history, settings (Compose + ViewModels)
  service/     RecitationService (mic FGS, notification controls)
  data/        Room (sessions, bookmarks), DataStore (settings), reference DB, downloads
  audio/       AudioCapture, QuranAudioPlaybackController, focus/route handling
  recognition/ QuranRecognitionEngine impls (one per model, behind the interface)
  di/          Hilt
:engine  (pure Kotlin, JVM‑testable, no Android deps)
  reference/   QuranReference model + phonetic index
  tracking/    QuranPositionTracker (global search + local tracking)
  align/       PhonemeAligner, MistakeClassifier
  session/     RecitationSessionController state machine, PauseDetector,
               SelfCorrectionHandler, FeedbackPolicy, LukmaAssistanceController
```

Stack: Kotlin, Compose, MVVM, Coroutines/Flow, Hilt, Room, DataStore, ONNX Runtime Android
(or the SDK's runtime), Media3 ExoPlayer for Qari audio. minSdk 26, target/compile SDK latest stable.

Key data flow:
`AudioCapture → RecognitionEngine (provisional/stable tokens) → PositionTracker → Aligner →
MistakeClassifier → FeedbackPolicy → SessionController (StateFlow) → ViewModel → UI / notification`

### Core algorithms
- **Global position search:** inverted index of phonetic n‑grams (e.g. word bigrams/trigrams or
  phoneme 4‑grams) over all ~77k words → top‑K candidate positions → banded local alignment
  (Smith‑Waterman) on stable tokens → accept only if score margin over 2nd best exceeds a
  threshold *and* ≥ N words match. Otherwise stay in `LOCATING_POSITION` (silent).
- **Local tracking:** align new stable tokens against a window around the last confirmed
  position (back‑window for repeats, forward‑window for skips). Loss of alignment for M words →
  trigger global re‑search, keep last confirmed position.
- **Self‑correction:** mismatches are held as *provisional* for a window (e.g. 2–3 s or until the
  next 2–3 words agree). If the user re‑recites correctly, discard; otherwise promote to a
  candidate mistake.
- **FeedbackPolicy:** silence by default; emit feedback only above a high confidence threshold
  and outside a waqf/breath context. Tuned for false alerts ≤ ~1 per 100 correct ayahs.
- **PauseDetector:** energy‑based VAD + context (end of ayah / waqf sign → longer allowed
  pause; mid‑ayah → shorter). Spec thresholds (3/6/10 s) are starting values, adapted to the
  user's measured pace.

Session state machine: implement the spec §12 states as a sealed class with an explicit
transition table, unit‑tested for every allowed/forbidden transition.

---

## 7. Risks and blockers

1. **Model license (Quran‑Lab)** — blocks a monetized release. *Need user decision.*
2. **Model accuracy on Indo‑Pak‑accented, non‑professional reciters** — unknown until benchmarked.
3. **Harakah/consonant detection may not be reliable enough** → ship word‑level only if so.
4. **False alerts** damage trust more than missed mistakes; heavy tuning needed.
5. **Indo‑Pak ↔ Uthmani word mapping** mismatches would corrupt highlighting.
6. **Qari audio rights** and word‑level timing data availability for word/phrase hints.
7. **Bluetooth HFP** quality/routing problems.
8. **Voice commands** ("Lukma", "Continue"…) risk false triggers and model conflict → defer to
   post‑MVP; use notification/headset buttons and UI first.
9. **Annotated test corpus** requires reciters' consent and Quran teachers' time.
10. **Network policy in this cloud session** blocks the sources above; verification needs those
    hosts allowed or must be done by the user.

---

## 8. Phased plan (with exit gates)

| Phase | Scope | Exit gate |
|---|---|---|
| **0 Audit** (this doc) | Verify licenses/sources online; collect 30–50 test recordings | Model license decision made; text sources confirmed |
| **1 Foundation** | Project skeleton, Hilt, navigation (Home/Recitation/Quran/History/Settings), onboarding + permissions, settings, resource download manager, reference DB build script + loader, Quran reader with Indo‑Pak text | App installs; reader shows verified text; reference checksums pass tests |
| **2 Recognition PoC** | AudioCapture, engine interface + chosen model, stable/provisional tokens, global position detection, on‑device benchmark harness | Position found within ~5–10 s on ≥ 90 % of corpus starts; latency/RAM/battery measured |
| **3 Tracking** | Local tracking, highlighting, repeats/back/skip/jump, re‑search, persistence of last confirmed position | Word tracking accuracy target met; no advance on silence/noise |
| **4 Mistakes** | Alignment, missing/extra/repeat/wrong/order, self‑correction window, FeedbackPolicy; vowel/consonant only if model + validation allow | False alerts ≤ target on correct recitations; precision per category reported |
| **5 Smart Lukma** | PauseDetector, gentle offer → confirm → hint (word/phrase/ayah) → resume; playback isolation | Hints never move position; no prompts on breath/waqf in corpus |
| **6 Background** | FGS, lock screen, notification controls, audio focus, calls, BT, unplug, process‑death recovery | Survives 30‑min locked pocket session on ≥ 3 devices |
| **7 History & Practice** | Session summaries, history, delete, bookmarks; Memorization Practice | — |
| **8 Validation & Release** | Teacher‑annotated corpus, metrics, privacy review, accessibility, licenses doc | All MVP criteria (spec §19) evidenced |

---

## 9. Realistic MVP scope

**In:** automatic position detection; continuous word tracking with highlighting; Indo‑Pak
display; repeats/back/skip handling; word‑level mistake detection (missing, extra, repeated,
wrong word) with self‑correction window and conservative policy; Smart Lukma via UI and
notification with confirmation; next‑word/phrase/ayah hint playback for **one** licensed Qari;
locked‑screen foreground service; interruption handling and session resume; offline after
download; session history; no raw audio stored; documented limitations.

**Out (later):** Harakah/consonant feedback unless validated in Phase 4; all Tajweed rules; voice
commands; Mushaf page layout; multiple Qaris; Memorization Practice mode; accuracy scores.

---

## 10. Decisions needed from the user

1. **Will the app be monetized** (ads, subscriptions, paid)? This decides the model.
2. Is there an existing Android repo to build in, or start fresh here?
3. Which Qari for hints, and do you have/plan permission for the audio?
4. Target test devices (models / Android versions) and earbuds.
5. Allow network access in this environment to `sdk.quranlab.ai`, `huggingface.co`,
   `qul.tarteel.ai`, `tanzil.net`, `api.quran.com` so the [UNVERIFIED] items can be checked.
