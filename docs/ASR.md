# `:asr` — on-device recognizer for Abdou/quran-asr-small

Pure Kotlin/JVM module (`com.qurancompanion.asr`, depends on `:engine`). It ports the
`tools/model_eval/asr_core.py` reference pipeline (docs/MODEL_EVALUATION.md §3.2) and wraps it as a
quasi-streaming recognizer that emits engine `RecognitionEvent`s (`TokenUnit.WORD`, text in the
reference DB's `norm_skeleton` space). Build/test: `./gradlew :asr:test`.

No Quran text appears in code or tests: the vocabulary is read from the model's `tokens.txt` at
runtime, and expected outputs come from running the Python reference on downloaded audio and from
the reference DB.

## API

```kotlin
// Model + vocabulary
val vocab = Vocabulary.load(File(dir, "tokens.txt"))           // "<blk>" and "▁" (U+2581) are the only fixed symbols
val model = AbdouOnnxModel.load(File(dir, "model.int8.onnx"), vocab.size, threads = 2)  // or load(bytes, …)
// model.loadMs; model implements CtcAcousticModel (AutoCloseable)

// Streaming (what the app uses)
val rec = AbdouRecognizer(model, vocab, AbdouConfig())         // not thread-safe: one thread
val events: List<RecognitionEvent> = rec.accept(pcm16, offset, length, captureTimeMs)  // 16 kHz mono
rec.flush()   // end of utterance (Stop / Pause / before Qari playback): commits the tail
rec.reset()   // new session
rec.stats     // audioMs, windows, featureNs/modelNs/decodeNs, msPerAudioSecond, stableWords, droppedWords

// Lower level
FbankExtractor().compute(floatWave | pcm16): Features          // [frames, 80]
CtcGreedyDecoder(vocab).decode(logProbs): List<DecodedWord>    // text, startFrame, endFrame (excl.), confidence
SkeletonNormalizer().normalize(words) / normalizeWord(text)
OfflineTranscriber(model, vocab).transcribe(pcm16)             // whole-utterance decode
```

### Event semantics (engine contract, docs/ENGINE.md)
- **Stable** events are appended; each word is delivered once, in order. A word becomes stable when
  (1) it ends ≥ `stableAgeMs` before the window end, (2) it starts ≥ `leftGuardMs` after the window
  start (unless that is the start of the stream/segment), and (3) the previous window (1 hop earlier)
  has the same text with start and end within ±`agreeToleranceMs`. De-duplication uses times: a word is
  new only if its midpoint lies after the end of the last committed word. If a word never agrees before
  it leaves the reliable zone it is **dropped** (counted in `stats.droppedWords`). Later words wait for
  it, so the order is kept.
- **Provisional** events replace each other. They carry the current window's words after the last
  committed one and are emitted only when the text changes. An empty provisional event clears the tail
  (after `flush`).
- `flush()` treats the final window's right edge as the real end of the audio and commits its
  remaining words without waiting for agreement.
- Tokens: `startMs`/`endMs` = first letter frame / last letter frame + 40 ms, mapped to the session
  clock through `captureTimeMs`. When it is omitted, audio ms since the first sample are used.
  `confidence` = minimum over the word's letters of the letter's peak CTC posterior. Event
  `timestampMs` = clock time of the window end.
- **Gaps**: if `captureTimeMs` jumps more than `gapResetMs` (1 s) from the expected time (audio was
  gated during Qari playback or pause), the current utterance is flushed and a new segment starts.
  Audio from before and after a gap is never decoded in one window.

### `AbdouConfig` (defaults)
| param | default | meaning |
|---|---|---|
| `windowMs` | 6000 | decoded window (bidirectional model needs context on both sides) |
| `hopMs` | 1000 | decode every hop of new audio |
| `stableAgeMs` | 1500 | min distance of a stable word's end from the window end |
| `leftGuardMs` | 500 | ignore words starting this close to a (non-stream-start) window's left edge |
| `agreeToleranceMs` | 160 | start/end tolerance for two windows to agree (4 output frames) |
| `minDecodeMs` | 1000 | no decode before this much audio |
| `gapResetMs` | 1000 | capture-time jump treated as a discontinuity |

## Preprocessing / model (exact port)
Fbank: 16 kHz, 25 ms / 10 ms, dither 0, per-frame DC removal, pre-emphasis 0.97 (`x0·0.03`), povey
window, 512-point FFT power (bins 0..255), 80 Kaldi-mel filters 20–7600 Hz, `ln(max(·, FLT_EPSILON))`,
snip_edges=false (centred frames, reflected edges, `frames = (n + 80) / 160`). Computed in double,
output float32. Model I/O: `x[1,T,80]`, `x_lens[1]` → `log_probs[1,T_out,31]`, `log_probs_len`,
`T_out = ((T−1)/2−1)/2` (40 ms frames; windows with T < 7 return no frames). Greedy CTC decoding uses
the same segmentation and times as `asr_core.greedy`.

## Matching-key normalization (`SkeletonNormalizer`)
Inspected: the DB `norm_skeleton` alphabet is exactly 28 letters (U+0627–U+063A without U+0629,
U+0641–U+0648, U+064A). The model vocabulary has 29 letters: those 28 plus standalone hamza U+0621.
The test asserts both facts against the live DB and `tokens.txt`.

| rule | what | why |
|---|---|---|
| A1 | fold ٱأإآٲٳ→ا, ؤ→و, ئىیےۍېٮ→ي, ةۃہۂھ→ه, کڪ→ك (norm-skeleton N2–N6) | no-op for this model; keeps other recognizers/spellings in the same space |
| A2 | drop U+0621 (ء) and U+0674 | N3 removes standalone hamza; the model emits it as a letter. **The only rule that fires on real output** (e.g. words ending in hamza, or starting with hamza + alef) |
| A3 | drop anything outside the 28-letter alphabet | N1/N7 (marks, tatweel, format chars) |
| A4 | a word reduced to a lone ا or ي is appended to the previous word (times merged) | the model's training-text rule; the DB has no 1-letter alef/yeh words |
| A5 | drop empty words | — |

**Measured key match** (30 full-ayah clips, offline decode, normalized words aligned to the DB
`norm_skeleton` of the same ayah): **485/485 = 100.00 %** exact (raw model output without A2:
95.88 %). No DB change was needed.

## Measured numbers (x86-64 Xeon 2.1 GHz, 4 vCPU, JVM 21, ORT JVM 1.26.0, 2 intra-op threads)
- Fbank parity vs Python: max |Δ| 1.05e-4 (synthetic integer signal, committed golden
  `fbank_synthetic.f32`, 48 KB), 2.8e-4 / 2.9e-4 / 8.5e-4 on three real clips (533–1293 frames).
  Speed **0.95 ms per audio second** (1 thread).
- Offline pipeline vs Python reference (ORT 1.32): **30/30 clips identical text** (519 s audio).
  469/485 words have identical frame times; the other 16 differ by one 40 ms frame. This is an int8
  kernel difference between ORT versions that flips near-tie blank/letter frames (p ≈ 0.5); the text
  is never affected. Offline RTF 0.013 including fbank; model load 0.27 s.
- Streaming e2e (30 clips → 100 ms chunks → `AbdouRecognizer` → `RecitationSessionController` with the
  full 77,429-word DB reference, 2 s trailing silence then `flush`):
  - stable words equal the reference keys for 24/30 clips. 12 word errors in 485 words (2.5 %), all in
    the long ayahs (2:255, 36:9), 6 of them drops. Stable streaming output is 0 % WER on 18:10 and 67:2.
  - **0 surfaced feedback in 30/30 correct recitations.**
  - position: 20/30 clips resolve to the exact surah:ayah and the confirmed word reaches the last or
    second-to-last word. The other 10 are 1:2 (its 4 words occur 4× in the Quran) and 55:13 (31×).
    The engine correctly leaves these ambiguous and unresolved; the test verifies the ambiguity
    against the DB.
  - first position lock 3–12 s after the start (needs ≥ 3 stable words).
  - **latency** word end → stable: mean 2.2 s, p50 2.2 s, p90 3.1 s, max 4.4 s.
  - **CPU** 50 ms of compute per audio second (model 44 ms, fbank ~5 ms), 48 ms per 6 s window, on
    the calling thread with 2 ORT threads. On a mid-range ARM core, expect roughly 3–6× more
    (≈ 0.15–0.3 s per audio second) — **still to be measured on device**.

## ⚠ ONNX Runtime version
**ORT 1.22.x–1.24.x return wrong, thread-count-dependent outputs for this int8 model.** Words get split
and letters go missing; seen on both the Python wheels and the JVM 1.22.0 jar. 1.25.1, 1.26.0 and 1.32.0
are deterministic and correct. The catalog's shared `onnxruntime` version is now 1.26.0 (used by both
`onnxruntime-android` and `onnxruntime-jvm`). **Never go below 1.25.** The JVM/Python parity tests
would catch a regression.

## Binding in `:app`
- Dependencies: `implementation(project(":asr"))` + `implementation(libs.onnxruntime.android)`
  (catalog 1.26.0, must stay ≥ 1.25, see above). `:asr` declares ORT `compileOnly`, so the app supplies the runtime. Only the
  shared `ai.onnxruntime` API is used.
- Model files: `model.int8.onnx` (36.08 MB, sha256 `88f9df29…cff00f0`) + `tokens.txt` (31 lines) +
  MIT `LICENSE` (licenses screen). Recommended: download on first run from our own host to
  `filesDir/models/abdou-quran-asr-small/`, verify the SHA-256, then `AbdouOnnxModel.load(File, …)`
  (memory-maps nothing extra; avoids a 36 MB heap copy). Bundling as an asset is possible: store it
  uncompressed (`noCompress += "onnx"`) and copy it to `filesDir` once, or `load(bytes)`.
- Threads: create the model once (load ~0.3 s x86, more on device) off the main thread. Run `accept`
  on one dedicated background dispatcher (`Dispatchers.Default.limitedParallelism(1)` or a single
  thread), because a window decode blocks for tens of ms. Use `threads = 2` intra-op. Forward events
  to `RecitationSessionController.handle` on its own dispatcher.
- Clock: pass `captureTimeMs = SystemClock.elapsedRealtime()` at the first sample of each
  `AudioRecord` chunk (the controller's clock). Call `flush()` on Stop/Pause and before a hint plays,
  then forward its events. Call `reset()` for a new session.
- Memory: ~100 MB after load (model + ORT arena, measured in Python); a 6 s window is far below the
  O(T²) attention peak (432 MB was measured for 56 s). Keep `windowMs` ≤ 10 s.
- Audio: 16 kHz mono PCM16 directly from `AudioRecord`. Resample with a polyphase filter if the
  device cannot capture at 16 kHz.
- Unit: `TokenUnit.WORD`; reference `matchKey = word.norm_skeleton` (unchanged).

## Test data (`asr/tools/prepare_test_data.py`)
`tools/model_eval/fetch_inputs.sh`, then `tools/model_eval/.venv/bin/python -I asr/tools/prepare_test_data.py`.
This writes PCM16 clips, Python transcripts with times, and fbank goldens to `models/asr_test/`
(gitignored, ~17 MB). The real-audio tests run the script themselves when the venv exists. Without
the model, the test data or the DB they **skip** via JUnit assumptions. The synthetic fbank golden
and the fake-model streaming tests always run.

## Limitations
- Quasi-streaming only (full-attention model): stable words lag 1.5–4.4 s. Use provisional events
  and the engine's `candidate` for highlighting.
- Window-edge disagreement drops ~1 % of words in long, fast passages (6 drops / 485 words). The
  engine absorbs them (no false feedback here), but every drop is a possible false deletion. Longer
  windows (8 s) are more accurate but cost ~1.3× CPU and add latency (MODEL_EVALUATION §3.3).
  Aligning window letters to the expected ayah is the next step.
- Short refrains / common formulas cannot be located from themselves alone (engine design). Context
  from neighbouring ayahs resolves them in continuous recitation.
- Validated only on 5 professional reciters (everyayah). Learner/phone audio, noise and on-device
  CPU/battery are not yet measured. No harakat, so there is no tajweed feedback from this model.
- `AbdouRecognizer` is not thread-safe.
