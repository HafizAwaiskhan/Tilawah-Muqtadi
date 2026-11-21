# Project status — 2026-11-21

**Nothing has been run on a real Android device or an emulator yet** (this cloud machine has no
`/dev/kvm` and no device). Every "Done" below means: implemented and covered by JVM tests or a
measurement on x86 — not "verified on a phone". Spec phases: docs/PHASE0_PLAN.md §8; MVP scope §9.

Build/test gate (all green at this commit):
`./gradlew :engine:test :asr:test :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug`
→ engine 96, asr 15, app 61 tests, 0 failures, 0 skipped (with `models/`, `data/build/` present;
the real-audio/real-DB tests skip without them); lint 0 errors.

APK (release unsigned, R8 on): **67.3 MB** (was 146.6 MB); debug 121.3 MB (incl. the 1 MB debug-only
Alafasy timing asset). Content:
ORT native arm64 27.4 MB + armv7 19.5 MB, reference DB 18.0 MB, dex 2.8 MB. The 36 MB model is
downloaded, not bundled. An App Bundle with ABI splits would bring a per-device download to ≈ 45 MB.

## Phases

| Phase | Status | Evidence / what is missing |
|---|---|---|
| 0 Audit | **Done** | Licences/sources (LICENSES.md, SOURCES.md), model evaluation on 30 everyayah clips (MODEL_EVALUATION.md). Learner / Indo-Pak recordings not collected. |
| 1 Foundation | **Done (JVM) / Partial** | Hilt, navigation, onboarding, settings, Room (v2 + migration), reference DB loader, Quran reader, model download manager, **testing-only** Alafasy audio download (`QariAudioTest`). Missing: licensed Qari audio, licensed Nastaleeq font. |
| 2 Recognition PoC | **Partial** | `:asr` port verified against Python (30/30 identical transcripts) and bound in `:app` (`AbdouRecognitionEngine`). Missing: **on-device** latency / RAM / battery / CPU measurement (x86 numbers only: 50 ms CPU per audio second); position-found rate on learner audio. |
| 3 Tracking | **Partial** | Engine tracking/repeat/skip/jump/re-search tested on synthetic + real-DB streaming e2e (asr `EndToEndTest`: 20/30 clips exact position, rest correctly ambiguous). Not measured on real recitation through the app. |
| 4 Mistakes | **Partial** | Word-level missing/extra/repeat/wrong/order + self-correction + FeedbackPolicy, tested synthetically; 0 false feedback on 30 correct professional recitations. No teacher-annotated learner corpus → precision per category unknown. No harakat/tajweed (model has none). |
| 5 Smart Lukma | **Partial** | PauseDetector, offer → confirm → hint → resume, playback guard; **Lukma on a confirmed mistake** (full ayah by default, re-recite from the ayah start): engine `MistakeLukmaTest`, app `MistakeFlowManagerTest`, `SimulatedPipelineTest`. Real hints now possible with the **testing-only** Alafasy audio (download or stream) — never heard on a phone yet. Release still blocked on licensed audio. |
| 6 Background | **Partial (untested)** | FGS (microphone), notification controls, focus/call/unplug handling, Room recovery are implemented. Exit gate (30-min locked session on ≥ 3 devices) not attempted. |
| 7 History & Practice | **Partial** | History list/delete/keep-history toggle, **session details with flagged words** (tap → ayah), Stop summary with flagged words, "New session", bookmarks, Memorization mode setting. Memorization practice UX minimal. |
| 8 Validation & Release | **Not started** | No annotated corpus, no device matrix, no accessibility pass, licences not all cleared (see below). |

## MVP acceptance items

| Item | Status | How tested / blocker |
|---|---|---|
| Resources init (text, model, readiness) | **Done (JVM)** | Reference DB loaded once (`EngineReferenceTest`, ~350 ms JVM); model download verified by SHA-256 (`ModelDownloadManagerTest`: ok / mismatch / interrupted + Range resume / server ignoring Range / version mismatch; shipped hashes checked against `models/`). Home shows Quran text / tracking / model / Qari rows. Real network download from Hugging Face on a phone not tried. |
| Automatic position detection | **Partial** | Engine `GlobalPositionSearchTest`, `ScaleTest` (77,429 words); asr e2e on real audio: lock 3–12 s after start. Learner audio untested. |
| Continuous word tracking | **Partial** | `QuranPositionTrackerTest`, asr streaming e2e; stable words lag 1.5–4.4 s (full-attention model). |
| Indo-Pak display | **Partial / Blocked** | `WordDisplayRule` picks `text_indopak` where verified (`WordDisplayRuleTest`); rendered with the system font — **no licensed Nastaleeq font**; Quran.com Indo-Pak text licence UNVERIFIED. |
| Current-word highlight | **Partial** | Candidate/confirmed word highlighted in `AyahWords`; never seen on a screen. |
| Repeat / go back | **Done (JVM)** | `repeatingCurrentAyahIsNotAMistake`, `recitingPreviousAyahRepositions`, `repeatOfCurrentAyahIsRepeatNotMistake`. |
| Self-correction | **Done (JVM)** | `selfCorrectionRestartGivesNoFeedback`, `discardedWhenWordIsReRecited`. |
| Validated mistakes only | **Partial** | Conservative policy; `FalseAlertTest` (0 false alerts with realistic noise) + 0/30 on real correct recitations. No learner validation. |
| Smart assistance with confirmation | **Done (JVM)** | Offer → user confirms; `longSilenceNeverAutoPlays`, `breathingPauseGivesNoLukma`, `waqfPause…`; smart assistance off = no offers. |
| Hints + resume | **Partial (testing audio)** | Flow tested with fake audio (`SimulatedPipelineTest`, `MistakeFlowManagerTest`: full-ayah / word clip, offline notice, resume at ayah start). Alafasy test audio (everyayah, **not licensed**) downloadable per surah / whole Quran or streamed; word timings in debug builds only. Licensed audio still needed for release. |
| Locked-screen listening | **Not verified** | Microphone FGS implemented; needs devices (Android 14+ rules). |
| Interruptions (call, focus, unplug) | **Partial (untested)** | Implemented + manager JVM tests with fake signals; no real call/BT test. |
| Recovery (process death) | **Partial** | Snapshot persistence + Home "Resume session" (`RecitationSessionManagerTest`); `am kill` on device not tried. |
| Offline after download | **Done (JVM)** | Model loads from `filesDir/models/abdou-v1/`; recognition and tracking need no network. Not tried in airplane mode on a device. |
| No raw audio stored | **Done (by design, code review)** | `AudioCapture` → in-memory frames → recognizer ring buffer (6–7 s); Room stores positions/durations only; backup disabled. No automated test asserts the absence of files. |
| History | **Done (JVM)** | `HistoryAndSettingsViewModelTest`; delete all / keep-history off. |
| Limitations documented | **Done** | ASR.md "Limitations", ENGINE.md, this file. Not yet shown in-app. |
| Licences | **Partial** | Model MIT + attribution in Settings (Offline downloads + About); Tanzil attribution in About. Quran.com text, fonts, Qari audio, word timings UNVERIFIED/High (LICENSES.md); the Alafasy audio/timings are a labelled testing-only exception. |

## Real-device test checklist

1. Install debug APK on ≥ 3 devices (one low-end armv7/arm64, Android 10, 14, 15/16).
2. Onboarding → Download model on Wi-Fi: progress, pause/resume, airplane mode mid-download → "Resume
   download" continues (Range), kill app mid-download → restart resumes; verify Home shows "Ready".
3. Settings → Remove model → Home shows "Not installed", a session runs in manual mode (word tap).
4. Model load time and RSS after load (`adb shell dumpsys meminfo com.qurancompanion`), target ≤ 250 MB.
5. Recite 5–10 min from memory (several reciters incl. learners, Indo-Pak style): position lock
   time, word-highlight lag, false feedback count, dropped words; CPU per audio second (Perfetto /
   `top`), whether the `abdou-asr` worker keeps up (no queue growth), battery %/hour (target < 8–10).
6. Pause / Resume / Stop mid-word: the last words before the tap are committed.
7. Lukma button and auto-offer (≥ 5 s silence mid-ayah, none at waqf/breath).
7a. **Owner test of this round** (debug APK, Alafasy test audio):
    1. Settings → Offline downloads → Alafasy card: download surah 1 and 67; check progress, "On this
       phone" count, Home "Qari audio" row. Airplane mode mid-download → error; retry continues.
    2. Settings: "On mistake, play" = Full ayah; vibration on; soft tone off; mark flagged words on.
    3. Start a session in 67, recite, deliberately say a wrong word and carry on 3–4 words: after
       a few seconds a gentle vibration + "Check this ayah?" card; nothing plays until you tap.
       Tap "Play the ayah": whole ayah plays, the app does not move; then re-recite from the ayah
       start and check tracking follows.
    4. Same with "Continue" (offer goes, tracking continues) and with an immediate self-correction
       (no card). Switch to "Correct word only" and repeat (only the word plays).
    5. Flagged word is underlined red; tap it → expected word + "Play the ayah".
    6. Soft tone on, earbuds connected: the beep is in the earbuds and is not recognised as a word.
    7. Lock the screen while reciting: vibration still comes on a mistake/offer.
    8. Pause hint in a surah that is not downloaded: online → streams; airplane mode → gentle notice.
    9. Stop → summary lists the flagged words; tap → Quran screen at that ayah. History → session →
       same list; delete the session → it is gone.
   10. "New session" from Home and from the Recitation screen while reciting/paused: confirm dialog,
       old session in History, new one locates automatically, notification stays.
   11. Update over the previous APK with existing history → history still there (Room 1 → 2).
8. Screen locked 30+ min in a pocket; notification actions while locked; POST_NOTIFICATIONS denied.
9. Incoming/outgoing call, VoIP, another media app, wired + BT headset unplug, BT HFP mic.
10. `adb shell am kill com.qurancompanion` while reciting → Home "Resume session".
11. Arabic word rendering / line breaking (system font), RTL, TalkBack, large font scale.
12. First-run DB copy time (18 MB) on a low-end device.
13. Release build (R8): launch, download, recognize — confirms keep rules (ONNX Runtime JNI, Room, Hilt).

## Decisions needed from the owner

1. **Monetization** (ads / subscriptions / paid / free): with Abdou MIT anything is allowed; the
   Quran-Lab model would forbid it. Also decides how strict licence clearance must be.
2. **Quran.com Indo-Pak text terms**: obtain written permission from Quran Foundation to bundle
   the word-by-word Indo-Pak text offline, or pick another licensed source (LICENSES.md rows 3–5).
3. **Qari audio permission**: which Qari for hints, and written permission from the rights holder
   (everyayah / quranicaudio / QUL are all UNVERIFIED). The Alafasy download added now is for the
   owner's personal testing only and must be removed or licensed before any distribution.
4. **Nastaleeq font licence**: KFGQPC / QUL Indopak / Digital Khatt — confirm embedding rights.
5. **Indo-Pak test recordings**: 30–50 learner recordings (phone mic, Indo-Pak style), ideally
   teacher-annotated with mistakes, to tune thresholds and measure false alerts.
6. Model hosting: the app downloads from Hugging Face at a pinned revision; mirror on our own host
   (same SHA-256) if HF availability or terms are a concern. Target devices for the test matrix.
