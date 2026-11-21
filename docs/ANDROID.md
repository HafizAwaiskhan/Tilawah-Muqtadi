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
| Media3 ExoPlayer | 1.8.0 · ONNX Runtime Android 1.22.0 (dependency only) |

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

APK size is ~115 MB, almost all ONNX Runtime native libs for 4 ABIs + the 18 MB DB. Add
`abiFilters` / App Bundle splits before release.

## Package layout (`com.qurancompanion`)

```
QuranCompanionApp, MainActivity        Hilt app + single activity (Compose)
core/audio/        AudioCapture (AudioRecord 16 kHz mono PCM16, VOICE_RECOGNITION, AEC,
                   silenced-recording callback), AudioInterruptionMonitor (audio focus,
                   ACTION_AUDIO_BECOMING_NOISY, call detection via audio-mode listener API 31+)
core/recognition/  QuranRecognitionEngine interface, ModelReadiness, local RecognitionEvent,
                   UnavailableRecognitionEngine (drains audio, emits nothing)
core/playback/     QuranAudioPlaybackController (Media3; emits Started/Ended), QariAudioResolver
core/time/         Clock (wall + monotonic) for testability
data/settings/     UserSettings + DataStore repository
data/db/           Room: sessions, bookmarks (no audio, no transcripts)
data/session/      Session/Bookmark repositories
data/reference/    QuranReferenceRepository (read-only SQLite asset), WordDisplayRule
session/           RecitationSessionManager (live state, persistence queue, Lukma, playback
                   isolation, interruption handling) + SessionState
service/           RecitationService (microphone FGS + notification)
di/                Hilt modules (ApplicationScope = SupervisorJob + Main.immediate)
ui/                onboarding, home, recitation, quran, history, settings, components, theme
```

Data flow today: `AudioCapture → (gate: LISTENING && !suppressed) → QuranRecognitionEngine →
RecitationSessionManager.onRecognitionEvent → StateFlow → ViewModels / notification`.
Position is advanced manually (Quran screen word tap / "Recite from here", Recitation screen
Next/Previous word) until the engine is wired.

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
`POST_NOTIFICATIONS`, `VIBRATE`, `INTERNET` (future downloads only); service
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

## Integration points (TODO)

1. **Engine (`:engine`)** — `RecitationSessionManager` is the adapter. Map engine
   `recognition.RecognitionEvent` ↔ `core/recognition/RecognitionEvent` (or replace the local
   type), feed stable tokens to the engine's `RecitationSessionController` /
   `QuranPositionTracker` and call `onConfirmedPosition()`; drive Lukma offers from its
   PauseDetector/FeedbackPolicy (respect `smartAssistance`). The engine's API was still
   uncommitted when this was written, so nothing imports it yet.
2. **Model** — implement `QuranRecognitionEngine` with ONNX Runtime and bind it in
   `di/AppModule.kt` instead of `UnavailableRecognitionEngine`; report `ModelReadiness`.
3. **Resources** — download manager for model + Qari audio; implement `QariAudioResolver`
   (ayah clips + word timings) and the Home "Qari audio" readiness row.
4. **Reference engine data** — build the engine's `QuranReference` from the `word` table
   (`norm_skeleton`, `phonetic`), reusing `SqliteQuranReferenceRepository`'s DB handle.
5. Memorization mode is a stored setting only.

## Must be tested on a real device

* FGS start from foreground on Android 14/15/16, continuation with screen locked for 30+ min,
  notification actions while locked, behaviour when POST_NOTIFICATIONS is denied.
* Background-start refusal path (e.g. try to resume via notification after the FGS was killed).
* Incoming/outgoing call, VoIP call, another media app taking focus, wired/BT headset unplug.
* `AudioRecord` with VOICE_RECOGNITION on several OEMs; AEC availability; BT HFP mic.
* Process death (`adb shell am kill com.qurancompanion` while recording) → Home resume.
* Hint playback isolation (no mic frames during playback + tail) once Qari audio exists.
* Arabic word rendering/line breaking with the system font; RTL layout; TalkBack.
* First-run DB copy time (18 MB) on low-end devices; battery drain per hour.
