"""
Reference implementation of the quran-asr-small (Abdou) pipeline, written so it can be ported
line for line to Kotlin on Android. Only numpy + onnxruntime; no sherpa-onnx, no torch.

Pipeline (see docs/MODEL_EVALUATION.md for the spec):
  audio -> mono float32 in [-1, 1] at 16 kHz
        -> 80-bin Kaldi fbank (25 ms povey window, 10 ms shift, dither 0, DC removal,
           pre-emphasis 0.97, 512-pt FFT power, mel 20..7600 Hz, log(max(x, eps)),
           snip_edges=false i.e. reflected edges, frames = (n + 80) // 160)
        -> ONNX  x[N,T,80] float32, x_lens[N] int64
           ->   log_probs[N,T_out,31] float32 (log-softmax), log_probs_len[N] int64
           (T_out = ((T - 1) // 2 - 1) // 2, i.e. one output frame per 40 ms)
        -> greedy CTC (argmax per frame, collapse repeats, drop blank id 0),
           id 30 "▁" = word separator, ids 1..29 = bare Arabic letters.

The fbank code mirrors the model author's code/quran_asr/features.py (which states it is
bit-for-bit kaldi-native-fbank as used by sherpa-onnx) and is checked against sherpa-onnx in
run_asr.py --check-sherpa.
"""
from __future__ import annotations

import math
import os
import time
from dataclasses import dataclass, field

import numpy as np

SAMPLE_RATE = 16000
NUM_BINS = 80
SHIFT = 160          # 10 ms
LENGTH = 400         # 25 ms
FFT = 512
PREEMPH = 0.97
LOW_HZ = 20.0
HIGH_HZ = SAMPLE_RATE / 2 - 400.0   # 7600 Hz (Kaldi high_freq = -400)
EPS = float(np.finfo(np.float32).eps)
FRAME_SEC_OUT = 0.04  # 4x subsampling of 10 ms frames
BLANK = 0
WORD_ID = 30


# ---------------------------------------------------------------- features

def _mel(f):
    return 1127.0 * math.log(1.0 + f / 700.0)


def mel_banks() -> np.ndarray:
    """[80, 256] triangular filters on the Kaldi mel scale (bin 256 / Nyquist is dropped)."""
    bins = FFT // 2
    width = SAMPLE_RATE / FFT
    lo, hi = _mel(LOW_HZ), _mel(HIGH_HZ)
    delta = (hi - lo) / (NUM_BINS + 1)
    banks = np.zeros((NUM_BINS, bins), dtype=np.float64)
    for b in range(NUM_BINS):
        left, center, right = lo + b * delta, lo + (b + 1) * delta, lo + (b + 2) * delta
        for i in range(bins):
            m = _mel(width * i)
            if left < m < right:
                banks[b, i] = (m - left) / (center - left) if m <= center else (right - m) / (right - center)
    return banks.astype(np.float32)


BANKS = mel_banks()
WINDOW = np.power(0.5 - 0.5 * np.cos(2 * math.pi * np.arange(LENGTH, dtype=np.float64) / (LENGTH - 1)),
                  0.85).astype(np.float32)  # "povey" window


def num_frames(num_samples: int) -> int:
    return (num_samples + SHIFT // 2) // SHIFT


def fbank(wave: np.ndarray) -> np.ndarray:
    """wave: 1-D float32 at 16 kHz in [-1, 1]. Returns [frames, 80] float32."""
    n = wave.shape[0]
    t = num_frames(n)
    idx = (np.arange(t)[:, None] * SHIFT + SHIFT // 2 - LENGTH // 2 + np.arange(LENGTH)[None, :])
    idx = np.where(idx < 0, -idx - 1, idx)            # reflect at the start
    idx = np.where(idx >= n, 2 * n - 1 - idx, idx)    # reflect at the end
    idx = np.clip(idx, 0, n - 1)
    frames = wave[idx].astype(np.float32)
    frames = frames - frames.mean(axis=1, keepdims=True)                       # remove DC
    frames = np.concatenate([frames[:, :1] * (1 - PREEMPH),
                             frames[:, 1:] - PREEMPH * frames[:, :-1]], axis=1)  # pre-emphasis
    frames = frames * WINDOW
    spec = np.fft.rfft(frames, n=FFT)
    power = (spec.real ** 2 + spec.imag ** 2).astype(np.float32)
    mel = power[:, : FFT // 2] @ BANKS.T
    return np.log(np.maximum(mel, EPS)).astype(np.float32)


# ---------------------------------------------------------------- audio

def load_audio(path: str) -> np.ndarray:
    """Any file soundfile/libsndfile can read (wav, flac, mp3) -> mono float32 16 kHz."""
    import soundfile as sf
    wave, sr = sf.read(path, dtype="float32", always_2d=True)
    wave = wave.mean(axis=1)
    return resample(wave, sr, SAMPLE_RATE)


def resample(wave: np.ndarray, sr_in: int, sr_out: int) -> np.ndarray:
    if sr_in == sr_out:
        return wave.astype(np.float32)
    from scipy.signal import resample_poly
    g = math.gcd(sr_in, sr_out)
    return resample_poly(wave, sr_out // g, sr_in // g).astype(np.float32)


def add_noise(wave: np.ndarray, snr_db: float, seed: int = 0) -> np.ndarray:
    """White Gaussian noise at the given SNR, measured against the whole clip's power."""
    rng = np.random.default_rng(seed)
    p_sig = float(np.mean(wave.astype(np.float64) ** 2))
    noise = rng.standard_normal(wave.shape[0]) * math.sqrt(p_sig / (10 ** (snr_db / 10)))
    out = wave + noise.astype(np.float32)
    peak = float(np.max(np.abs(out)))
    return (out / peak * 0.99 if peak > 1.0 else out).astype(np.float32)


def narrowband(wave: np.ndarray, sr_low: int = 8000) -> np.ndarray:
    """16 kHz -> 8 kHz -> 16 kHz (Bluetooth HFP/CVSD-like bandwidth: nothing above 4 kHz)."""
    return resample(resample(wave, SAMPLE_RATE, sr_low), sr_low, SAMPLE_RATE)


# ---------------------------------------------------------------- text

LETTERS = "ءابتثجحخدذرزسشصضطظعغفقكلمنهوي"
VOCAB = ["<blk>"] + list(LETTERS) + ["▁"]
_FOLD = {"أ": "ا", "إ": "ا", "آ": "ا", "ٱ": "ا", "ٲ": "ا", "ٳ": "ا",
         "ى": "ي", "ئ": "ي", "ے": "ي", "ی": "ي", "ؤ": "و", "ة": "ه", "ک": "ك"}


def load_tokens(path: str) -> list[str]:
    toks = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            sym, i = line.rstrip("\n").rsplit(" ", 1)
            toks[int(i)] = sym
    return [toks[i] for i in range(len(toks))]


def _is_mark(c):
    o = ord(c)
    return (0x064B <= o <= 0x065F or o == 0x0670 or 0x06D6 <= o <= 0x06ED or o in (0x0640, 0x200F, 0x06DE)
            or 0x0660 <= o <= 0x0669 or c.isdigit())


def normalize(text: str) -> str:
    """Reference text -> the model's letter space (same rules as the author's quran_asr/text.py)."""
    out = []
    for w in text.replace(" ", " ").split():
        w = "".join(_FOLD.get(c, c) for c in w if not _is_mark(c))
        w = "".join(c for c in w if c in LETTERS)
        if w in ("ا", "ي") and out:
            out[-1] += w
        elif w:
            out.append(w)
    return " ".join(out)


def edit_distance(a, b) -> int:
    prev = list(range(len(b) + 1))
    for i in range(1, len(a) + 1):
        cur = [i] + [0] * len(b)
        for j in range(1, len(b) + 1):
            cur[j] = min(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + (a[i - 1] != b[j - 1]))
        prev = cur
    return prev[-1]


# ---------------------------------------------------------------- model

@dataclass
class Word:
    text: str
    start: float   # seconds, first letter frame
    end: float     # seconds, last letter frame + 40 ms


@dataclass
class Result:
    text: str
    words: list[Word] = field(default_factory=list)
    infer_sec: float = 0.0
    feat_sec: float = 0.0


class QuranAsr:
    def __init__(self, model_path: str, tokens_path: str, threads: int = 2):
        import onnxruntime as ort
        t0 = time.perf_counter()
        so = ort.SessionOptions()
        so.intra_op_num_threads = threads
        so.inter_op_num_threads = 1
        so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        self.sess = ort.InferenceSession(model_path, so, providers=["CPUExecutionProvider"])
        self.load_sec = time.perf_counter() - t0
        self.vocab = load_tokens(tokens_path)
        assert self.vocab[0] == "<blk>" and self.vocab[WORD_ID] == "▁", self.vocab
        meta = self.sess.get_modelmeta().custom_metadata_map
        self.subsampling = int(meta.get("subsampling_factor", 4))

    def log_probs(self, feats: np.ndarray) -> np.ndarray:
        x = feats[None].astype(np.float32)
        x_lens = np.array([feats.shape[0]], dtype=np.int64)
        lp, lens = self.sess.run(["log_probs", "log_probs_len"], {"x": x, "x_lens": x_lens})
        return lp[0, : int(lens[0])]

    def greedy(self, lp: np.ndarray, offset_sec: float = 0.0) -> Result:
        best = lp.argmax(axis=-1)
        words, cur, cur_start, cur_end = [], [], None, None
        prev = BLANK
        frame = FRAME_SEC_OUT
        for t, tok in enumerate(best.tolist()):
            if tok != prev and tok != BLANK:
                if tok == WORD_ID:
                    if cur:
                        words.append(Word("".join(cur), cur_start, cur_end))
                    cur, cur_start = [], None
                else:
                    if cur_start is None:
                        cur_start = offset_sec + t * frame
                    cur.append(self.vocab[tok])
            if tok != BLANK and tok != WORD_ID and cur:
                cur_end = offset_sec + (t + 1) * frame
            prev = tok
        if cur:
            words.append(Word("".join(cur), cur_start, cur_end))
        return Result(" ".join(w.text for w in words), words)

    def transcribe(self, wave: np.ndarray, offset_sec: float = 0.0) -> Result:
        t0 = time.perf_counter()
        feats = fbank(wave)
        t1 = time.perf_counter()
        lp = self.log_probs(feats)
        t2 = time.perf_counter()
        r = self.greedy(lp, offset_sec)
        r.feat_sec, r.infer_sec = t1 - t0, t2 - t1
        return r


def peak_rss_mb() -> float:
    import resource
    return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024.0


def current_rss_mb() -> float:
    try:
        with open(f"/proc/{os.getpid()}/status") as f:
            for line in f:
                if line.startswith("VmRSS:"):
                    return int(line.split()[1]) / 1024.0
    except OSError:
        pass
    return float("nan")


def tempo(wave: np.ndarray, factor: float) -> np.ndarray:
    """Speed up without changing pitch (ffmpeg atempo), to imitate faster (hadr) recitation."""
    import subprocess
    filt = []
    f = factor
    while f > 2.0:
        filt.append("atempo=2.0")
        f /= 2.0
    filt.append(f"atempo={f}")
    cmd = ["ffmpeg", "-nostdin", "-loglevel", "error", "-f", "f32le", "-ar", str(SAMPLE_RATE), "-ac", "1",
           "-i", "pipe:0", "-filter:a", ",".join(filt), "-f", "f32le", "-ar", str(SAMPLE_RATE), "-ac", "1", "pipe:1"]
    out = subprocess.run(cmd, input=wave.astype(np.float32).tobytes(), capture_output=True, check=True).stdout
    return np.frombuffer(out, dtype=np.float32).copy()
