#!/usr/bin/env python3
"""
Generates the :asr JVM test inputs from the Python reference pipeline (tools/model_eval/asr_core.py).

  tools/model_eval/.venv/bin/python -I asr/tools/prepare_test_data.py [--synthetic-only]

Writes (all gitignored, under models/asr_test/):
  <reciter>__<SSSAAA>.pcm      16 kHz mono s16le (decoded + resampled clip, quantized to PCM16)
  <reciter>__<SSSAAA>.tsv      Python reference output computed from exactly that PCM16:
                               "#\tsurah\tayah\tsamples", then one "word\tstart_s\tend_s" per word
  <...>.fbank.f32              reference log-mel features [frames, 80] float32 LE (golden clips only)
  manifest.txt                 list of clips + which ones have fbank goldens
Writes (committed, tiny, no audio):
  asr/src/test/resources/fbank_synthetic.f32   fbank of a deterministic integer-only test signal
                                               (see synthetic_signal(); mirrored in the Kotlin test)

Nothing here contains Quran text: transcripts are produced by the model from downloaded audio and
stay in the gitignored models/ directory.
"""
from __future__ import annotations

import argparse
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
sys.path.insert(0, os.path.join(REPO, "tools", "model_eval"))

import numpy as np  # noqa: E402

import asr_core as core  # noqa: E402

AUDIO = os.path.join(REPO, "models", "audio", "everyayah")
OUT = os.path.join(REPO, "models", "asr_test")
MODEL = os.path.join(REPO, "models", "abdou-quran-asr-small", "model.int8.onnx")
TOKENS = os.path.join(REPO, "models", "abdou-quran-asr-small", "tokens.txt")
RES = os.path.join(REPO, "asr", "src", "test", "resources")
GOLDEN = ["Hani_Rifai_64kbps__001002", "Parhizgar_48kbps__036009", "Khaalid_Abdullaah_al-Qahtaanee_192kbps__067002"]


def synthetic_signal(n: int = 24000) -> np.ndarray:
    """Integer-only signal (mirrored exactly in FbankExtractorTest.syntheticSignal)."""
    out = np.zeros(n, dtype=np.int16)
    seed = 12345
    for i in range(n):
        seed = (seed * 1103515245 + 12345) & 0x7FFFFFFF
        noise = (seed >> 16) % 2001 - 1000            # [-1000, 1000]
        saw = ((i * 37) % 400) * 40 - 8000             # 40 Hz-ish sawtooth, +-8000
        square = 3000 if (i // 23) % 2 == 0 else -3000 # ~348 Hz square
        if 8000 <= i < 9600:
            v = 0                                      # 100 ms digital silence (log floor)
        elif i >= 20000:
            v = noise // 10 + 200                      # quiet tail with DC offset
        else:
            v = saw + square + noise
        out[i] = max(-32768, min(32767, v))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--synthetic-only", action="store_true")
    args = ap.parse_args()

    os.makedirs(RES, exist_ok=True)
    syn = synthetic_signal().astype(np.float32) / 32768.0
    core.fbank(syn).astype("<f4").tofile(os.path.join(RES, "fbank_synthetic.f32"))
    print("synthetic fbank:", core.fbank(syn).shape)
    if args.synthetic_only:
        return

    os.makedirs(OUT, exist_ok=True)
    asr = core.QuranAsr(MODEL, TOKENS, threads=2)
    clips = []
    for reciter in sorted(os.listdir(AUDIO)):
        for f in sorted(os.listdir(os.path.join(AUDIO, reciter))):
            if not f.endswith(".mp3"):
                continue
            cid = f"{reciter}__{f[:-4]}"
            wave = core.load_audio(os.path.join(AUDIO, reciter, f))
            pcm = np.clip(np.round(wave * 32768.0), -32768, 32767).astype("<i2")
            pcm.tofile(os.path.join(OUT, cid + ".pcm"))
            x = pcm.astype(np.float32) / 32768.0
            feats = core.fbank(x)
            r = asr.greedy(asr.log_probs(feats))
            meta = {"surah": int(f[:3]), "ayah": int(f[3:6]), "seconds": len(pcm) / core.SAMPLE_RATE}
            with open(os.path.join(OUT, cid + ".tsv"), "w", encoding="utf-8") as fh:
                fh.write(f"#\t{meta['surah']}\t{meta['ayah']}\t{len(pcm)}\n")
                for w in r.words:
                    fh.write(f"{w.text}\t{w.start:.4f}\t{w.end:.4f}\n")
            if cid in GOLDEN:
                feats.astype("<f4").tofile(os.path.join(OUT, cid + ".fbank.f32"))
            clips.append(cid)
            print(cid, f"{meta['seconds']:.1f}s", len(r.words), "words")
    with open(os.path.join(OUT, "manifest.txt"), "w") as fh:
        for c in clips:
            fh.write(f"{c}\t{int(c in GOLDEN)}\n")


if __name__ == "__main__":
    main()
