# Model evaluation — Phase 0 (recognition model)

Status: Phase 0 evaluation, 2026‑11‑21. Owner of tooling: `tools/model_eval/`.
Answers PHASE0_PLAN §2 (candidates) and §3 (license rows for the two models). Everything marked
**[MEASURED]** was run in this repo, **[DOCS]** is quoted or summarised from the vendor's own pages,
**[ESTIMATE]** is reasoning that still needs an on‑device measurement.

---

## 1. Summary

| | A. Quran‑Lab TajweedFormer V4 | B. Abdou `quran-asr-small` |
|---|---|---|
| Output | Streaming **phoneme** tokens (38: 28 consonants, ikhfa nasal, a/i/u, aa/ii/uu, word bar, foreign, blank) + 12 heads (madd timing, gemination, ghunna, qalqalah, tafkheem, conf…) | Bare **Arabic letters** (29) + word separator; no harakat |
| Streaming | Yes, native: chunk every 160/320/480 ms, `settled` count of stable tokens | No (full‑attention Conformer). Quasi‑streaming only by re‑decoding windows |
| Timing / confidence | Per token `firstMs/peakMs/lastMs`, `confidence` | Derivable from CTC frames (40 ms); no confidence output (can use posteriors) |
| Size | 65 M params; 56.5 MB download, 73.5 MB on disk (Android CPU set) | 33.2 M params; `model.int8.onnx` 36.08 MB |
| Integration | Closed AAR `ai.quranlab:tajweedformer:4.0.1` (Kotlin, minSdk 26), encrypted model, API key, online first run | Plain ONNX + `tokens.txt`; ONNX Runtime Mobile or sherpa‑onnx |
| License | **NPL‑1.2: no charging, no ads, no paywall on any feature it powers; no commercial license, ever** | **MIT** (training data: tlog CC‑BY‑4.0) |
| Hands‑on benchmark | **Blocked** (needs an account key; model is encrypted, eval tool needs a desktop key) | **Done**: 30 clips, 5 unseen reciters, 0.00 % WER clean and degraded, RTF ≈ 0.015 (x86) |

**Recommendation:** (a) monetized app → **B** (only option); (b) free app → **B for tracking in
the MVP**, and evaluate **A** as an optional, separately gated tajweed‑feedback engine once the
owner signs up for a key — see §6.

---

## 2. Candidate A — Quran‑Lab TajweedFormer V4

Sources read: <https://sdk.quranlab.ai/> (home, `/docs`, `/docs/concepts`, `/docs/feedback`,
`/docs/keys`, `/docs/versions`, `/docs/4`, `/docs/4/offline`, `/docs/4/quickstart/android`,
`/licence`), model card <https://huggingface.co/Quran-Lab/tajweedformer-v4> (no weights there),
`github.com/Quran-Lab/tajweedformer-android` README + AGENTS.md + LICENSE. **[DOCS]**

### 2.1 Capabilities (verified against vendor docs; not measured)
- **Version:** model TajweedFormer V4 **4.0.0** (2026‑11); SDK **4.0.1** (21 Nov 2026) on Android,
  Apple, Web (WASM), Windows/Linux (C); Flutter "follows later". Zipformer2 streaming CTC, 65 M params,
  "no language model anywhere".
- **Streaming:** yes. Contexts `c8_l64` (160 ms chunks, CPU only), `c16_l64` (320 ms, default),
  `c24_l72` / `c24_l256` (480 ms). Each chunk carries the whole take so far.
- **Stable vs provisional:** yes — `chunk.decoded.settled` = number of leading tokens that will not
  change; `isFinal` on the last chunk.
- **Tokens:** 38 phonetic units. Each token: `id, name, firstMs, peakMs, lastMs, confidence`.
  Per 40 ms frame: top token, runner‑up and margin.
- **Word boundaries:** a word‑bar token exists and `decoded.words` is "split at the word bar". But
  the eval docs also say "The model gives no word boundaries" for WER purposes, i.e. words are only as
  good as the word‑bar token; mapping to Quran words must go through `model.reference` (expected
  units of every ayah, with word bars).
- **Pronunciation signals:** heads `log_probs`, `inter1_log_probs`, `inter4_log_probs`,
  `id_log_probs`, `timing_log_probs`, `gem`, `attr_tafkheem`, `attr_ghunna`, `attr_qalqalah`,
  `attr_face`, `boundary`, `conf` (20/40 ms). **Quran Lab ships no scorer**: the app must compute
  CTC‑forward LLRs of single‑unit alternatives on two heads, fuse them and compare with thresholds in
  `calibration.json`. Reported status: vowel/length/consonant classes "validated"; gemination "off";
  qalqalah "experimental"; ghunna/tafkheem uncalibrated; madd lāzim unreliable; phone and child audio
  "the weakest case" (3–7 % false flags per class family on phone audio vs 0.3–0.6 % studio).
- **Reported accuracy:** PER 1.23 % on 16 unseen reciters (1,780 studio clips, default context);
  leaderboard 0.57 % studio / 1.98 % phone. Catch rates on real learners are modest (e.g. 32 % of
  teacher‑corrected vowel words, 46 % of L2‑KSU letter errors at `balanced`).

### 2.2 Android integration form
- `implementation("ai.quranlab:tajweedformer:4.0.1")` (Maven Central). The AAR contains the closed
  **Quran Lab Core** `libqlcore.so` (arm64‑v8a, armeabi‑v7a; x86_64 only with a development key) and
  depends on LiteRT 2.2.0 (for future NPU builds; at launch every phone runs the CPU engine).
- API: `TajweedClient(context, key)` → `client.model()` → `model.listen(...)` or
  `model.session()` + `session.push(samples)` → `session.chunks` (`Flow<TajweedChunk>`).
- **Model is not a file you get.** Since 4.0 it is encrypted per install, downloaded from Quran
  Lab's CDN (56.5 MB) on the first `client.model()`, opened only inside the Core under a server‑issued
  lease. No ONNX/TFLite is exposed. "The model's licence (NPL-1.2) does not allow extracting,
  decrypting, converting or reverse engineering the model or the Core" (docs/4 page — note the
  NPL‑1.2 text itself contains no such clause; it is in the vendor's docs, see §2.4).
- **Operational dependencies** that matter for this product:
  - First run needs network; afterwards offline, but the SDK fetches signed config "every minute while
    it is in use" when online, and a key/app refusal (key rotated, app paused, account losing access,
    "Quran Lab blocked it") **removes the model from users' devices**.
  - Keystore attestation; phones with an **unlocked bootloader cannot open the model**, by design.
  - **Analytics always on** in 4.0 (anonymous daily counts) and failure reports; app auto‑registers.
  - **Recording sharing sheet**: the SDK itself asks every learner (after 24 h / 3 takes, up to 3
    times per install) to upload recitations to Quran Lab; no app can turn the ask off. Must be
    reflected in our privacy policy / Play Data safety form.
  - `listen()` takes **end when the app leaves the foreground** (`Ended(BACKGROUND)`). Our
    background design (§5 of the plan: mic foreground service) would have to use
    `session()+push()` from our own `AudioRecord`; whether the Core keeps running for a backgrounded
    FGS is **not documented** — must be tested.
  - One take ≤ 20 min; one session at a time per model.
  - SDK 3.x stops receiving V4 on 2027‑01‑15; Quran Lab serves "the newest SDK only".

### 2.3 Pricing
"Free, forever … The model and the SDKs cost nothing, and there is nothing to buy here." Access =
sign in with Hugging Face, accept NPL‑1.2 + Access terms v2, describe the app; "most requests are
accepted on the spot".

### 2.4 License — NPL‑1.2 (verbatim key clauses)
Full text: <https://sdk.quranlab.ai/licences/NPL-1.2.txt>, SHA‑256
`77526bdbfac94132e5114c3a34492c33f915e4b7f310f00b9995500d3610cab5` (verified on download). Applies to
the model weights **and** to the SDK source (the GitHub repos carry the same LICENSE).

> "Charge" means requiring or accepting payment of any kind for the Work, for access to the Work, or
> for any feature, capability, or output that the Work provides. This includes sale, licensing for a
> fee, rental, a paid product or feature, a paid subscription, a paywall, a trial that later requires
> payment, and advertising revenue earned on a feature the Work powers.

> 3. THE WORK IS NOT FOR SALE — You may not Charge for the Work or for any Derivative. It may not be
> sold, licensed for a fee, rented, or supplied for payment, and neither the Work nor any feature it
> powers may be placed behind a payment, a subscription, a paywall, or advertising.
> This condition attaches to the Work, not to You. […] an organisation may earn from products,
> services and activities that do not include the Work or any feature it powers. What may never be
> charged for is the Work itself, access to it, or a feature that depends on it.

> 7. SHARE-ALIKE — You must distribute and make available every Derivative under this same License
> […] You may not sublicense the Work or a Derivative under any other license […]

> 9. NO COMMERCIAL LICENSE — There is no license to Charge for this Work, and none will be granted.
> The right to sell the Work, to place it or a feature it powers behind a payment, or to charge for
> access to it, is not available for a fee, a revenue share, a one-time payment, or any other
> arrangement.

> 10. TERMINATION — Any use that breaches these terms immediately and automatically ends the rights
> granted to You under this License.

Also: §2 grant is "worldwide, royalty-free, non-exclusive, non-transferable … use, run, copy, and
create Derivatives"; §6 no attribution required (but keep the LICENSE file when distributing).
Access terms v2 add: never "present its output as an authoritative religious ruling on anyone's
recitation"; service emails are part of access.

**Verdict A:**
- **Commercial license: not offered, explicitly never** (§9). Emailing for one is pointless.
- **Monetized app (ads/subscription/paid/IAP): not allowed** for any screen or feature that the
  model powers — ads on the recitation screen count ("advertising revenue earned on a feature the Work
  powers"). A paid app as a whole that contains it is a "paid product". Legally, a strictly split
  model (model features free and ad‑free, paid features unrelated) is arguably allowed by §3's second
  paragraph, but in a recitation companion nearly everything depends on recognition, so treat it as
  **incompatible with monetization**.
- **Free, non‑monetized app: allowed**, with these risks: (1) share‑alike §7 — if our app or our
  phoneme reference / alignment data is a "Derivative" (definition includes "any dataset … artifact
  produced from the Work" and "any model … evaluated using the Work or its outputs"), it must be NPL‑1.2
  too; keep our code clearly separate and get an opinion before shipping; (2) vendor dependence (remote
  kill switch, mandatory analytics, mandatory sharing prompt, attestation); (3) license terms can be
  bumped ("or any later version").

### 2.5 Hands‑on benchmark: blocked
- HF repo holds no weights. The earlier `Quran-Lab/zipformer_p-arabic-v3` (plain ONNX, NPL‑1.2) is
  gated "manual" and being retired; not used.
- The vendor's route for evaluation is the C SDK's `tajweedformer-eval` (Windows x64 binary; Linux
  build from source) with a **desktop key**, or the Android SDK with a dev key. Getting a key needs the
  project owner's Hugging Face sign‑in and acceptance of NPL‑1.2 + access terms — an agent cannot and
  should not do that on the owner's behalf. Docs also state raw‑weights requests made to evaluate are
  refused.
- **To unblock:** owner signs in at sdk.quranlab.ai, creates a *desktop development* key
  (Linux reverse‑DNS app id) → we build `tajweedformer-c` 4.0.1 and run
  `tajweedformer-eval --app-id <id> clips/ --refs refs.csv --out results.jsonl` on the same 30 clips
  (refs as `36:9` ayah ranges). It reports PER/WER and RTF directly.

---

## 3. Candidate B — Abdou `quran-asr-small`

Source: <https://huggingface.co/Abdou/quran-asr-small>, revision
`0c2744b9d70aaf45ba574c30c5d318c575258e66` (2026‑11‑13). Paper: Zenodo DOI 10.5281/zenodo.23761691.

### 3.1 Card facts **[DOCS, partly MEASURED]**
- **License: MIT** (LICENSE file, "Copyright (c) 2026 Abdou"). Card: "Training data keeps its own
  terms (tlog: CC-BY-4.0)". MIT needs the copyright + permission notice in the app's licenses screen.
- Architecture: Conformer‑CTC, 9 blocks, d=384, 6 heads, rotary attention, conv kernel, ×4 conv
  subsampling, **full (bidirectional) attention — not streaming**. **33,215,699 parameters
  [MEASURED from ONNX initializers]**.
- Files: `model.int8.onnx` **36,081,762 B** (sha256 `88f9df29…cff00f0`), `model.onnx` fp32
  133 MB, `model.pt` 133 MB, `tokens.txt` (31 lines, 180 B, sha256
  `75b53814499cee84a07c7b7a318524d30bcaf84273c0f8b28a04176023e8c74f`; full int8 sha256
  `88f9df290d57b8890275d79187b77515aeeb12626a42bc76cbd0f8bfcaff00f0`, both re-checked on the local
  files and pinned in `app/…/core/model/AsrModelSpec.kt`), `spelling.json` (Uthmani→standard spelling
  table), `retrieve.py` (fuzzy ayah lookup), full training code under `code/`.
- Training data 1,532 h: 36 EveryAyah Hafs reciters, 3 Warsh, tarteel‑ai/tlog phone recordings
  (283 h), Iqra'Eval (21 h). Augmented with speed/tempo ×0.75–2.5, noise, reverb, low‑pass.
- Reported: Quran‑Lab benchmark (600 clips) **4.49 % WER / 2.17 % CER** int8 overall; **phone
  10.45 % / 2.84 %** (3.29 / 1.16 against Uthmani references); EveryAyah 0.69 / 0.20; QUL 4.61 / 3.80.
  Own held‑out reciters (1,000 clips): 0.46 % WER. Greedy CTC, no LM.
- Output: bare Uthmani‑rasm letters, **no harakat**, alif forms folded to ا, ى/ئ→ي, ؤ→و, ة→ه.
  Word separator `▁`. No timestamps in the sherpa API by default, but frame times are trivially
  derived from greedy CTC (40 ms resolution) — `asr_core.greedy()` does it.

### 3.2 Exact preprocessing / ONNX spec (port this to Android) **[MEASURED]**
Verified two ways: our numpy fbank equals `kaldi-native-fbank` (max |Δ| ≤ 2.2e‑3 on log‑mel,
identical frame counts), and our numpy+onnxruntime pipeline gives **identical transcripts to
sherpa‑onnx 2.0.2 on 30/30 clips** (`run_asr.py check-fbank`, `check-sherpa`).

**Audio:** mono, 16 kHz, float32 in [‑1, 1] (PCM16 / 32768). Resample with a proper polyphase
filter if the mic is not 16 kHz (`AudioRecord` 16 kHz mono directly is preferred).

**Features (Kaldi fbank = kaldi‑native‑fbank defaults used by sherpa‑onnx):**

| param | value |
|---|---|
| frame length / shift | 25 ms (400 samples) / 10 ms (160) |
| dither | 0 |
| snip_edges | false (frames centred: frame t covers samples `t*160 + 80 - 200 … +400`, reflected at edges); `num_frames = (n + 80) // 160` |
| remove DC | yes (per frame mean) |
| pre‑emphasis | 0.97 (first sample: `x0 * (1-0.97)`) |
| window | povey: `(0.5 - 0.5 cos(2πi/399))^0.85` |
| FFT | 512‑point, power spectrum, bins 0..255 used |
| mel | 80 triangular bins, Kaldi mel `1127 ln(1+f/700)`, 20 Hz … 7600 Hz (high_freq = ‑400) |
| log | `ln(max(mel, FLT_EPSILON=1.1920929e-7))` |
| energy / CMVN / normalisation | none |

**ONNX** (opset 17, `DynamicQuantizeLinear`+`MatMulInteger` int8 MatMuls; standard ops only —
ORT Mobile needs no custom ops):

| name | dtype | shape |
|---|---|---|
| input `x` | float32 | `[N, T, 80]` |
| input `x_lens` | int64 | `[N]` |
| output `log_probs` | float32 | `[N, T_out, 31]` (log‑softmax) |
| output `log_probs_len` | int64 | `[N]` |

`T_out = ((T - 1)//2 - 1)//2` (two stride‑2 3×3 convs) → one output frame per **40 ms**.
Metadata: `model_type=zipformer2_ctc`, `vocab_size=31`, `subsampling_factor=4` — this is why
sherpa‑onnx loads it via `OfflineRecognizer.from_zipformer_ctc` / Kotlin
`OfflineZipformerCtcModelConfig`.

**Vocabulary** (`tokens.txt`, "symbol id"): `0 <blk>`, `1..29` = `ء ا ب ت ث ج ح خ د ذ ر ز س ش ص ض ط ظ
ع غ ف ق ك ل م ن ه و ي`, `30 ▁` (word separator).

**Decoding:** greedy CTC — argmax per frame, collapse repeats, drop blank; `▁` splits words. Word
time = first/last non‑blank letter frame × 40 ms. No LM, no lexicon.

**Reference matching:** normalise the Quran text with the same rules (`asr_core.normalize`: drop
U+064B–065F, U+0670, U+06D6–06ED, tatweel, digits; fold alif variants → ا, ى ئ ے ی → ي, ؤ → و,
ة → ه, ک → ك; re‑attach a lone ا/ي to the previous word). On the 30 clips the Tanzil Uthmani text
normalised this way matched the model's output word for word, so no extra spelling table is needed
for tracking. Display text stays the separate Indo‑Pak layer (plan §4) mapped by word index.

### 3.3 Benchmark **[MEASURED]**
Setup: x86‑64 Intel Xeon @ 2.1 GHz (4 vCPU), onnxruntime 1.32 CPU, int8 model, 2 intra‑op
threads, Python fbank (included in RTF). Corpus: 6 ayahs (1:2, 2:255, 18:10, 36:9, 55:13, 67:2) ×
5 reciters from everyayah.com = **30 clips, 519 s**. All five reciters are **absent from the
model's training list** (`code/config/base.yaml`); Hani Rifai is one of its declared test reciters.
References: Tanzil Uthmani 1.1 (downloaded), normalised as above. Noise = white Gaussian at the
given SNR over the whole clip; narrowband = 16→8→16 kHz (HFP‑like); tempo = ffmpeg `atempo`
(pitch kept) to imitate hadr.

Reciters: Hani Rifai (64 kbps, held‑out), Khalid al‑Qahtani (192 kbps), Mahmoud Ali al‑Banna
(32 kbps, slow Egyptian murattal), **Parhizgar (Iranian)** and **Aziz Alili (North Macedonian)** as
non‑Arab proxies. **EveryAyah has no Indo‑Pak/South‑Asian reciter** — gap, see §7. All five recite at
0.83–0.93 words/s (murattal); tempo ×1.5/×2.0 gives ~1.3/~1.8 words/s.

Offline (whole ayah) decoding:

| condition | clips | audio s | WER % | CER % | exact clips | RTF |
|---|---:|---:|---:|---:|---:|---:|
| clean | 30 | 519 | **0.00** | 0.00 | 30/30 | 0.018 |
| white noise 10 dB SNR | 30 | 519 | 0.00 | 0.00 | 30/30 | 0.016 |
| white noise 5 dB | 30 | 519 | 0.00 | 0.00 | 30/30 | 0.016 |
| white noise 0 dB | 30 | 519 | 0.00 | 0.00 | 30/30 | 0.017 |
| narrowband 8 kHz | 30 | 519 | 0.00 | 0.00 | 30/30 | 0.016 |
| narrowband 8 kHz + noise 10 dB | 30 | 519 | 0.00 | 0.00 | 30/30 | 0.016 |
| tempo ×1.5 | 30 | 347 | 0.00 | 0.00 | 30/30 | 0.018 |
| tempo ×2.0 | 30 | 260 | 0.00 | 0.00 | 30/30 | 0.019 |

Every reciter scored 0.00 % in every condition. This shows robustness for professional recitation;
it does **not** show accuracy for ordinary learners on phones (the card's own phone WER is 10.45 %).

Quasi‑streaming (each window decoded independently, as an app would re‑run the offline model):

| mode | clean WER % | noise 10 dB WER % | tempo ×1.5 WER % | RTF (clean) | mean commit delay after word end |
|---|---:|---:|---:|---:|---:|
| 3 s window / 1 s hop, commit middle slice | 24.74 | 26.60 | 16.29 | 0.054–0.077 | 1.0 s |
| 5 s / 1 s, middle slice | 7.42 | 8.25 | 4.33 | 0.085 | 2.0 s |
| **8 s / 1 s, middle slice** | **1.44** | **1.65** | **0.82** | 0.085–0.10 | 3.4 s |
| 3 s / 1 s, LocalAgreement‑2 | 54.02 | 55.88 | 40.41 | — | 1.5 s |
| 8 s / 1 s, LocalAgreement‑2 | 9.28 | 9.90 | 11.75 | — | 2.9 s |
| anchored growing window, hold back 2 words, 2 s left pad | 16.08 (all 30 clips) | — | — | 0.056 | 1.7 s |

Findings: the model needs **context on both sides**. 3 s windows are unusable for free transcription
(murattal words last ~1 s, so most words touch a window edge); quality recovers only at ~8 s
windows, at ~3.4 s latency and ~8× compute. Words in the newest second of a window are unreliable
(only 17–50 % survive unchanged). Our simple stitching heuristics did not beat "middle slice of a
long window"; with the known expected text, the app tracker should instead **align each window's
letters against the expected ayah** (cheap, and tolerant to edge fragments) rather than
stitch free text — that is the Phase 1/2 design to measure.

Resources **[MEASURED, x86]**: model load **0.21–0.36 s**; process RSS 37 MB (python + ORT) →
**138 MB after load** (≈100 MB for the model + ORT arena); peak RSS 223 MB for a 6.5 s clip,
248 MB for 12 s, **432 MB for a 55.6 s clip** (attention is O(T²) — cap windows at ~10–15 s on
device). Threads 1/2/4 barely change RTF here (≈0.012–0.022).

### 3.4 On‑device cost **[ESTIMATE — verify in Phase 2]**
- APK/asset: +36 MB model (or download on first run); ORT Mobile AAR ~ 6–8 MB per ABI, or
  sherpa‑onnx AAR (bundles ORT + kaldi‑native‑fbank).
- Compute: a mid‑range ARM big core is typically 3–6× slower than this Xeon for int8 MatMul →
  offline RTF ≈ 0.05–0.1; an 8 s window every 1 s ≈ 0.4–0.8 s of CPU per second of audio on one core
  (too heavy for battery); 8 s every 2 s or 5 s every 1 s + text alignment ≈ 0.2–0.4. Expect RAM
  ~150–250 MB with ≤ 10 s windows.
- Latency: ~1.5–3.5 s from word end to confident commit with long windows — acceptable for
  position tracking, too slow for "instant" mistake prompts.

---

## 4. Side‑by‑side for this product

| need (spec) | A TajweedFormer V4 | B quran-asr-small |
|---|---|---|
| Position tracking (word level) | Yes (phonemes + word bars + reference) | Yes (letters + `▁`), proven 100 % on clean professional audio |
| Word mistakes (skip/substitute/insert word) | Yes | Yes |
| Harakah / vowel‑length / tajweed hints | Yes (partly validated) | **No** (no harakat in output) |
| True streaming, stable tokens | Yes (`settled`) | No — windows + alignment, 1.5–3.5 s lag |
| Works offline from install | No (first run online; periodic checks; remote revocation) | Yes |
| Background (screen off, FGS) | Unverified; `listen()` ends on background | Our own code; no restriction |
| Unlocked‑bootloader / custom ROM users | Cannot open model | Works |
| Privacy | SDK analytics always on + mandatory sharing prompt | Nothing leaves the device |
| Monetization | **Forbidden** for powered features; no commercial license | Allowed (MIT) |

---

## 5. Recommendation

**(a) Monetized app (ads, subscription, paid, IAP):** use **B, `quran-asr-small`**. A cannot be used:
NPL‑1.2 §3 and §9 forbid charging or ads for any feature it powers, and no commercial license exists
"for a fee, a revenue share, a one-time payment, or any other arrangement". Scope mistake detection to
word‑level (missed / substituted / extra / repeated words, wrong ayah), as the plan already assumes.

**(b) Free, non‑monetized app:** still build the MVP on **B** behind `QuranRecognitionEngine`:
it is MIT, fully offline, needs no account, has no remote kill switch, and on our tests is perfect on
unseen reciters including noisy/narrowband/fast audio. Add **A** later as an optional
"tajweed feedback" engine only if harakah/madd feedback is wanted, after (1) the owner gets a key and
we benchmark it with `tajweedformer-eval`, (2) we confirm push‑mode keeps working inside a
background microphone FGS, (3) a legal read of NPL‑1.2 §7 share‑alike vs our code and data, and
(4) we accept the mandatory analytics + recording‑sharing prompt in our privacy story. Never let the
app's free status depend on A being the only engine — if monetization is ever added, A must be
removable without breaking tracking.

---

## 6. Blockers and gaps
1. **Quran‑Lab hands‑on eval blocked**: needs the owner's sdk.quranlab.ai account + desktop dev key
   (and acceptance of NPL‑1.2/access terms). Weights are not downloadable; extraction is forbidden.
2. **No Indo‑Pak reciter on everyayah.com**; used Iranian and Balkan reciters as non‑Arab proxies.
   Need licensed or self‑recorded South‑Asian‑accent recitation (and ordinary‑learner phone audio,
   the real weak case: card reports 10.45 % phone WER) — Phase 2 corpus task.
3. Test corpus is small (30 clips) and professional; 0 % WER is a ceiling result, not a
   learner‑accuracy estimate. No mistake‑detection test yet (needs recordings with deliberate errors).
4. `huggingface.co` LFS redirects to `us.aws.cdn.hf.co`, which this sandbox blocks. The int8 model was
   taken from a public GitHub mirror and **accepted only because its SHA‑256 equals the HF LFS oid**
   (`fetch_inputs.sh` enforces this). For the app, host our own copy (MIT allows redistribution).
5. Not measured: on‑device RTF, battery, real Bluetooth HFP audio, echo from hint playback.
6. EveryAyah audio rights are unclear (plan §3) — used here only for local evaluation; nothing
   committed.

---

## 7. Reproduce
```sh
python3 -m venv tools/model_eval/.venv
tools/model_eval/.venv/bin/pip install -r tools/model_eval/requirements.txt
tools/model_eval/fetch_inputs.sh                      # model, Tanzil text, 30 clips -> models/ (gitignored)
P="tools/model_eval/.venv/bin/python -I tools/model_eval/run_asr.py"
$P check-fbank  models/audio/everyayah/*/*.mp3        # numpy fbank vs kaldi-native-fbank
$P check-sherpa models/audio/everyayah/*/*.mp3        # our pipeline vs sherpa-onnx: 30/30 identical
$P transcribe --words models/audio/everyayah/Parhizgar_48kbps/036009.mp3
$P --threads 2 bench --audio models/audio/everyayah --ref models/reference/tanzil-quran-uthmani.txt \
   --windows 3/1,5/1,8/1 --out tools/model_eval/results/abdou_bench.json
```
`asr_core.py` is the reference implementation to port (fbank, normalisation, greedy CTC with word
times); `run_asr.py` holds the benchmark, degradations and quasi‑streaming modes.
