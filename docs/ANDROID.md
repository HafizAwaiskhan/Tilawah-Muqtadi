# Android app (`:app`) — Phase 1 foundation

## SDK setup (this cloud machine)

```
mkdir -p /opt/android-sdk/cmdline-tools
# commandlinetools-linux-16111833_latest.zip from dl.google.com/android/repository/
unzip commandlinetools-linux-*_latest.zip && mv cmdline-tools /opt/android-sdk/cmdline-tools/latest
yes | /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/opt/android-sdk --licenses
/opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/opt/android-sdk \
  "platform-tools" "platforms;android-35" "platforms;android-36" "build-tools;35.0.0" "build-tools;36.0.0"
echo "sdk.dir=/opt/android-sdk" > local.properties   # gitignored
```

Installed: cmdline-tools (latest = 16111833), platform-tools, platforms 35 + 36, build-tools 35.0.0
(AGP 8.13 default) + 36.0.0. Elsewhere, point `local.properties` / `ANDROID_HOME` at any SDK
with platform 36.

## Toolchain versions (gradle/libs.versions.toml)

Constrained by the Gradle 8.14.3 wrapper (→ AGP 8.x, max 8.13) and Kotlin **2.1.0** (libraries
must be compiled with Kotlin metadata ≤ 2.2, so mid-2025 AndroidX releases are used):

| | version |
|---|---|
| AGP | 8.13.0 · compileSdk/targetSdk 36 · minSdk 26 · Java/JVM target 17 |
| Kotlin / compose compiler plugin / KSP | 2.1.0 / 2.1.0 / 2.1.0-1.0.29 |
| Compose BOM | 2025.08.00 (Material 3) |
| Navigation Compose | 2.9.3 · Lifecycle 2.9.2 · Activity 1.10.1 · Core 1.16.0 |
| Hilt | 2.56.2 (KSP) · hilt-navigation-compose 1.2.0 |
| Room | 2.7.2 (KSP, schema exported to `app/schemas/`) · DataStore 1.1.7 |
| Media3 ExoPlayer | 1.8.0 · ONNX Runtime Android **1.26.0** (dependency only; ≥ 1.25.1 required — 1.22–1.24 give wrong, thread-count-dependent output on the quran-asr-small INT8 model; ORT 1.26 AAR still has minSdk ≤ 26) |

Upgrading past these needs Gradle 9 + AGP 9 + Kotlin ≥ 2.2 together.

## Build / test / install

```
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The reference DB `data/build/quran_ref_v1.db` is **not duplicated** under `app/`: the
`copyReferenceDb` task copies it into `app/build/generated/referenceAssets/` (an extra assets
dir) before every build; it is stored uncompressed (`noCompress "db"`). If the file is missing
the build still succeeds and the app shows "Quran text not included in this build".

APK size: `abiFilters` = arm64-v8a + armeabi-v7a (debug also x86_64 for emulators), release
built with R8 (`isMinifyEnabled` + `isShrinkResources`, keep rule for `ai.onnxruntime.**`).
Release unsigned **66.7 MB** (was 146.6 MB: x86/x86_64 ORT libs and 50 MB of unshrunk dex removed);
debug 120.7 MB (was 153.8 MB). Remaining: ORT arm64 27.4 MB + armv7 19.5 MB, DB 18 MB. An App
Bundle with ABI splits would cut a per-device download to ≈ 45 MB. The R8 build has not been run
on a device yet.

## Package layout (`com.qurancompanion`)

```
QuranCompanionApp, MainActivity        Hilt app + single activity (Compose)
core/audio/        AudioCapture (AudioRecord 16 kHz mono PCM16, VOICE_RECOGNITION, AEC,
                   silenced-recording callback), AudioInterruptionMonitor (audio focus,
                   ACTION_AUDIO_BECOMING_NOISY, call detection via audio-mode listener API 31+)
core/audio/        + EnergyVad (RMS VAD, adaptive noise floor, 60 ms attack / 400 ms hangover)
core/recognition/  QuranRecognitionEngine (emits engine RecognitionEvent/Token; flush()), ModelReadiness,
                   AbdouRecognitionEngine (:asr AbdouRecognizer on one worker thread),
                   UnavailableRecognitionEngine (manual mode: drains audio, emits nothing),
                   SelectableRecognitionEngine (model if installed → manual → debug simulator)
core/model/        AsrModelSpec (pinned URLs, sizes, SHA-256, licence), ModelDownloadManager
core/playback/     QuranAudioPlaybackController (Media3, MP3 index seeking; emits Started/Ended),
                   QariAudioResolver → TestQariAudioResolver (file → stream when online → Offline)
core/qari/         AlafasyTestQari (URL pattern, ayah counts, sizes), QariDownloadManager,
                   QariTimingSource (debug-only timing asset)
core/cue/          SessionCues (gentle vibration, soft ToneGenerator beep)
core/net/          NetworkStatus (validated internet?)
core/time/         Clock (wall + monotonic) for testability
data/settings/     UserSettings + DataStore repository
data/db/           Room v2: sessions, bookmarks, flagged_words (no audio, no transcripts); MIGRATION_1_2
data/session/      Session/Bookmark repositories
data/reference/    QuranReferenceRepository (read-only SQLite asset), WordDisplayRule,
                   EngineReference (word table → engine QuranReference, loaded once, cached)
session/           RecitationSessionManager (adapter around the engine's
                   RecitationSessionController) + LiveSession (UI state)
src/debug/         SimulatedRecognitionEngine + DebugModule (debug builds only)
service/           RecitationService (microphone FGS + notification)
di/                Hilt modules (ApplicationScope = SupervisorJob + Main.immediate)
ui/                onboarding, home, recitation, quran, history, settings, components, theme
```

Data flow: `AudioCapture → (gate: listening && no hint) → EnergyVad + QuranRecognitionEngine →
RecitationSessionManager → RecitationSessionController.handle() → SessionUiState → LiveSession
StateFlow → ViewModels / notification`, with controller snapshots persisted to Room.

## Engine integration (`session/RecitationSessionManager`)

* **Reference.** `CachedEngineReference` streams `SELECT id, surah, ayah, word_index,
  norm_skeleton, phonetic, stop_marks FROM word ORDER BY id` into `EngineReferenceBuilder`:
  globalId = `word.id` (verified contiguous 0..77428), matchKey = `norm_skeleton`, phonetic =
  space-split `phonetic`, waqfAfter = `stop_marks` contains U+06D6..U+06DC except U+06D9 ("do not
  stop"). Strings are interned. Loaded once on `Dispatchers.Default`; JVM measurement
  (`EngineReferenceTest`, sqlite-jdbc on the real DB): **~350 ms, ~6 MB retained** (plus the
  controller's n-gram index). Home shows "Position tracking: Preparing… / Ready (loaded in N ms)".
* **One dispatcher.** All inputs go through one channel consumed on `@EngineDispatcher`
  (`Dispatchers.Default.limitedParallelism(1)`); the controller is only touched there.
* **Inputs.** `Initialize` once the reference is loaded; on Start: `PermissionsResult(true)`
  (the UI only starts after RECORD_AUDIO is granted), `ModelReady` (also when no model is
  installed — the session then runs in manual mode), `ReferenceReady`, `StartSession(mode from
  Settings)`; `RecoverSession` from the Room row (Home "Resume session"); `RecognizerStatusChanged
  (Listening)` when `runRecognizer` is bound; `Recognition` events; `SpeechActivity` edges from
  `EnergyVad` on the same gated frames; `Tick` every 250 ms while a session exists; Lukma
  request / accept (hint or full ayah) / decline; Pause/Resume/Stop; `PlaybackStarted/Ended` from
  `QuranAudioPlayback`; `Interrupted`/`InterruptionEnded` from focus/call/noisy/capture errors
  (auto-resume only after call/focus); `SetManualPosition` for word taps (new engine input).
* **Persistence.** A Room row is created at StartSession (start = requested ayah or 1:1, updated
  to the first located ayah); the snapshot is written whenever the confirmed position or the
  status changes and on stop (COMPLETED + endedAt; deleted if "keep history" is off). Room
  schema v2 adds `flagged_words` (migration tested on JVM against the exported schema). Recovery maps `lastSurah/lastAyah/lastWord` back
  to a global word id (`lastWord = 0` → locate again).
* **Hints.** When the controller exposes `activeHint`, the clip comes from `QariAudioResolver`
  (word timings for NEXT_WORD/PHRASE, the whole ayah for FULL_AYAH). With no Qari audio a
  gentle notice is shown ("not downloaded and you're offline" / "not available") and
  `PlaybackEnded` is sent at once, so recitation resumes. A mistake's "correct word" without word
  timings falls back to the whole ayah. `smartAssistance = off` declines automatic offers
  (pause and mistake) silently; mistakes are still flagged and cued; the Lukma button still works.
* **Mistakes → Lukma.** A surfaced mistake comes with a MISTAKE offer (engine, ENGINE.md). The card
  ("Check this ayah?") plays Settings → "On mistake, play" (Full ayah [default] / Correct word only)
  as its main button (`acceptOffer(null)` maps the setting), the other choice as secondary, or
  Continue. Afterwards the user re-recites the ayah from its first word.
* **Flagged words.** New entries of `feedback` (detected with `feedbackCount`) become
  `LiveSession.flagged` and Room `flagged_words` rows (session id, surah, ayah, word index, mistake
  type, time — no audio/transcript). "Mark flagged words in text" underlines them in red in the
  Recitation screen; tapping one shows the expected word (DB text) and "Play the ayah"
  (`playFlaggedAyah`: a user-initiated full-ayah hint while listening, else a plain preview with the
  gate closed). The Stop summary and History → session details list them; a tap opens the ayah in
  the Quran screen. Deleting a session deletes its flags (FK cascade + explicit delete).
* **Cues.** On a new flag or a new (non-declined) offer the manager — not the UI, so it works with
  the screen locked — calls `SessionCues`: gentle vibration [on] (waveform 35/90/35 ms at low
  amplitude, `USAGE_NOTIFICATION` attributes, skipped in silent mode) and soft tone [off]
  (`ToneGenerator` beep, 120 ms, volume 25 on STREAM_MUSIC → current output). For the tone the
  audio gate closes 60 ms before it and reopens 120 ms + 300 ms tail after it (same
  `recognitionSuppressed` window as hints; this also flushes the recognizer segment).
* **New session** (Home "Session in progress" card, Recitation controls; confirm dialog): flush →
  Stop (saved like Stop) → StartSession with automatic position detection, on the same engine
  queue. `LiveSession.restarting` keeps the microphone service alive across the STOPPED in between.
* **Recitation screen** renders the engine state label, pause class, current ayah with the
  candidate word highlighted (tap a word = manual position), the next ayah dimmed, the offer card
  (Play hint / Play full ayah / Continue), surfaced feedback phrased gently, and Previous/Next word
  buttons when no model is ready.

## Simulated recognizer (debug builds)

Settings → Developer (debug only) → "Simulated recognizer": replays the reference's `norm_skeleton`
keys for surah S, ayahs A..B at a chosen pace (provisional token mid-word, stable at the end),
optionally with one ~10 s mid-ayah pause (→ Lukma offer) and injected errors (one wrong word
followed by a restart two words back, one skipped word). It reads the DB, never literals.
Simulated time advances only with received audio frames, so it stalls while paused or while a
hint plays. Applies to the next session (it still needs the mic FGS; on an emulator the virtual
mic supplies frames). `SimulatedPipelineTest` runs the whole pipeline on the real DB with a virtual
clock: locate in 67:1, words advance, no offer at 3 s of silence, offer after ≥ 5 s, tokens during
hint playback ignored, resume to the end of the range, snapshot persisted / COMPLETED on stop.

## Quran text

* `SqliteQuranReferenceRepository` copies the asset to the databases dir on first run (and after
  each APK update, by comparing `lastUpdateTime`) and opens it `OPEN_READONLY`.
* Per-word display text is chosen in one place, `WordDisplayRule`: `text_indopak` when
  `indopak_check = 'match'`, else `text_uthmani` for that word.
* Each word is a separate composable (`AyahWords`, RTL `FlowRow`) so the current word is
  highlighted and tappable.
* **Font TODO:** no Indo-Pak/Nastaleeq font is bundled (no licence documented yet), the system
  Arabic font is used (`QuranTextStyle`). When a licensed font is added, switch
  `WordDisplayRule` to `text_indopak_nastaleeq` where `nastaleeq_map IN ('match','split_1to2',
  'pua_glyph')` and set the font family in `ui/theme/Theme.kt`.
* Tanzil CC BY 3.0 attribution (`meta 'attribution.tanzil'`) and `data_version` are shown in
  Settings → About.

## Permissions & foreground-service behaviour

Manifest: `RECORD_AUDIO`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`,
`POST_NOTIFICATIONS`, `VIBRATE`, `INTERNET` (model download on user action only); service
`foregroundServiceType="microphone"`, not exported. Backup/device transfer disabled.

* RECORD_AUDIO is requested in context: on the onboarding microphone step (skippable) and again
  when the user taps Start/Resume (`rememberRecitationStarter`). POST_NOTIFICATIONS is requested
  on the onboarding background step (API 33+); if denied the FGS still runs.
* **Android 14+ rule:** a microphone FGS may only be started while the app is visible. The
  service is only started from UI click handlers (Start, Resume, Home "Resume session"). It is
  `START_NOT_STICKY` — the system will not restart it in the background, where it would be
  refused. If `startForeground` throws (background start / missing permission) the session is
  marked INTERRUPTED (`PERMISSION_DENIED`) and the service stops.
* While running it continues with the screen off. The mic (`AudioRecord`) is opened only while
  LISTENING and no Qari audio plays; pausing releases it.
* Notification: ongoing, silent, Pause/Resume + Stop actions, tap opens the Recitation screen.
  Resume from the notification re-opens the mic from an FGS that is already running (allowed).
* Interruptions: call (audio mode / silenced recording) and audio-focus loss → INTERRUPTED,
  auto-resume on call end / focus regain; headset unplug → INTERRUPTED, waits for the user.
* Process death: every position/status change is written to Room in order. A non-COMPLETED
  session is offered on Home ("Resume session" / "Discard").
* Playback isolation: `recognitionSuppressed` is set before a hint starts and cleared 300 ms
  after playback ends; the audio gate closes meanwhile, and hints never move the position.
  ExoPlayer does not request audio focus itself (the session holds it).

## Recognition model (`AbdouRecognitionEngine`, `ModelDownloadManager`)

* **Download** (user action only: Onboarding "Download the model" step, Settings → Offline
  downloads; shows 36 MB, MIT licence text + attribution). Files: `model.int8.onnx` + `tokens.txt`
  from `huggingface.co/Abdou/quran-asr-small/resolve/<pinned revision>/`. They are written to
  `filesDir/models/.staging-abdou-v1/*.part`; an interrupted transfer resumes with HTTP Range (a 200
  answer restarts the file). Each file is checked for exact size + SHA-256 (constants in
  `AsrModelSpec`, verified against `models/` by `ModelDownloadManagerTest`); a mismatch deletes it.
  Then a `VERSION` file (spec id/version + hashes) is written and the staging dir is renamed to
  `filesDir/models/abdou-v1/` in one step; other `abdou-*` dirs are removed. On start the install
  counts only if `VERSION` matches the spec and the sizes are right, so a spec change forces a
  re-download. The download runs in the app scope: it stops if the process dies and can be resumed.
* **Engine.** One dedicated thread (`abdou-asr`) runs load (~0.3 s x86), `accept`, `flush` and
  `reset` in order. Frames from the gated `AudioCapture` flow are time-stamped on arrival
  (`elapsedRealtime − 20 ms`, kept contiguous while within 200 ms; a gate closure shows as a time
  jump → new recognizer segment). Up to 30 s of audio may queue; beyond that new frames are dropped.
  `flush()` returns after its events were handed to the controller's collector.
* **Selection** (`SelectableRecognitionEngine.initialize`, per session): debug simulator if chosen
  in Settings → Developer; else the model engine when installed and it loads; else manual mode.
  Home's "Recognition model" row shows Not installed / Downloading N % / Ready / Error.
* **Flush points** (`RecitationSessionManager.flushThen`): before Pause, Stop, Lukma request and
  accepting an offer, and whenever the audio gate closes (hint playback, interruption).

## Remaining integration TODOs

1. **Device validation of the recognizer**: CPU per audio second on ARM, RSS after load, battery,
   whether the worker keeps up; tune `AbdouConfig` (window/hop) and ORT threads per device class.
   Nothing has run on a device yet (docs/STATUS.md checklist).
2. **Qari audio** — a licensed source + permission is still needed. For the owner's testing only:
   Settings → Offline downloads → "Mishary Alafasy (for personal testing only — not licensed for
   distribution)": per-surah or whole-Quran (1.71 GB) download of
   `https://everyayah.com/data/Alafasy_128kbps/SSSAAA.mp3` into `filesDir/qari/alafasy-test/`
   (`.part` → rename, MP3 header check, `SSS.done` marker; continues with missing ayahs after an
   interruption; stops with the process — no WorkManager). Not-downloaded ayahs stream from the same
   URL when online. Word timings: `buildQariTestTimings` converts `data/raw/.../segments_recitation7`
   (these files are byte-identical to Quran.com recitation 7) into `assets/qari/alafasy_timings.tsv`
   of **debug builds only** (77,392 words; release builds have none → next-word hints show a notice).
3. **Font** — licensed Indo-Pak Nastaleeq font (see "Quran text").
4. Thresholds (pause, feedback, VAD) are tuned on synthetic data and professional recitations only;
   re-tune on learner recordings.
5. Download over metered networks: no Wi-Fi-only option or WorkManager (download stops with the
   process); consider WorkManager + a foreground notification for large/slow downloads.
6. App Bundle / ABI splits for distribution; release signing config.
7. Emulator screenshots were not captured: this cloud machine has no `/dev/kvm`.

## Must be tested on a real device

* FGS start from foreground on Android 14/15/16, continuation with screen locked for 30+ min,
  notification actions while locked, behaviour when POST_NOTIFICATIONS is denied.
* Background-start refusal path (e.g. try to resume via notification after the FGS was killed).
* Incoming/outgoing call, VoIP call, another media app taking focus, wired/BT headset unplug.
* `AudioRecord` with VOICE_RECOGNITION on several OEMs; AEC availability; BT HFP mic.
* Process death (`adb shell am kill com.qurancompanion` while recording) → Home resume.
* Hint playback isolation (no mic frames during playback + tail) with the Alafasy test audio, on the
  speaker and on earbuds; soft-tone isolation (the tone must never be recognised as a word).
* Vibration / tone cues with the screen locked; silent / vibrate ringer modes.
* Room migration 1 → 2 on a phone that has v1 history (install the previous APK, record a
  session, update): history must survive.
* Arabic word rendering/line breaking with the system font; RTL layout; TalkBack.
* First-run DB copy time (18 MB) on low-end devices; battery drain per hour.
* Model download on Wi-Fi/mobile, pause/resume, airplane mode mid-download, process kill mid-download.
* Release (R8) build end-to-end: ORT JNI, Room, Hilt keep rules.
