#!/usr/bin/env python3
"""
Evaluate quran-asr-small (Abdou) on real recitation, CPU only.

Usage (from the repo root, with the venv; run with -I because it reads downloaded files):

  tools/model_eval/.venv/bin/python -I tools/model_eval/run_asr.py transcribe FILE.mp3 ...
  tools/model_eval/.venv/bin/python -I tools/model_eval/run_asr.py check-sherpa FILE.mp3 ...
  tools/model_eval/.venv/bin/python -I tools/model_eval/run_asr.py bench \
        --audio models/audio/everyayah --ref models/reference/tanzil-quran-uthmani.txt \
        --out tools/model_eval/results/abdou_bench.json

Audio layout for bench: <audio>/<reciter>/<SSSAAA>.mp3 (everyayah naming).
Reference: Tanzil "txt-2" export (sura|aya|text). Ayah 1 of a surah carries the basmala in that
export, so prefer other ayahs.

Default model location: models/abdou-quran-asr-small/{model.int8.onnx,tokens.txt}.
"""
from __future__ import annotations

import argparse
import json
import os
import statistics
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)  # -I drops the script dir from sys.path; asr_core lives next to this file

import numpy as np  # noqa: E402

import asr_core as core  # noqa: E402

REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
DEFAULT_MODEL = os.path.join(REPO, "models", "abdou-quran-asr-small", "model.int8.onnx")
DEFAULT_TOKENS = os.path.join(REPO, "models", "abdou-quran-asr-small", "tokens.txt")


# ---------------------------------------------------------------- helpers

def load_reference(path):
    ref = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("|")
            if len(parts) == 3 and parts[0].isdigit():
                ref[(int(parts[0]), int(parts[1]))] = parts[2]
    return ref


def score(hyp: str, ref: str):
    hw, rw = hyp.split(), ref.split()
    hc, rc = hyp.replace(" ", ""), ref.replace(" ", "")
    return {"word_edits": core.edit_distance(hw, rw), "ref_words": len(rw),
            "char_edits": core.edit_distance(hc, rc), "ref_chars": len(rc)}


def chunked(asr: core.QuranAsr, wave: np.ndarray, win: float, hop: float, guard: float = 0.25):
    """
    Quasi-streaming: decode independent windows of `win` s every `hop` s (each window is a full
    offline decode of that slice). Two commit policies are applied to the same window outputs:

    mid      : a word is committed by the window whose middle hop-sized slice contains its start
               (first/last window also commit their head/tail). Naive tiling.
    agree    : LocalAgreement-2. A word is "interior" in a window when it lies fully inside it, at
               least `guard` s from both window edges (except at the true start/end of the audio).
               It is committed when two consecutive windows both have it as interior (same letters,
               start within 0.2 s) and it starts at or after the end of the last committed word
               (-0.1 s). The last window commits its remaining interior words.

    "Provisional" words = words of a non-final window that start in its newest `hop` s and end at
    least `guard` s before the window edge (what a live UI would show greyed out). We report how many
    of them end up committed (agree policy) with the same text and a start within 0.2 s.
    """
    sr = core.SAMPLE_RATE
    dur = len(wave) / sr
    margin = (win - hop) / 2.0
    starts, s = [], 0.0
    while True:
        starts.append(s)
        if s + win >= dur:
            break
        s += hop
    mid, inter, provisional, compute = [], [], [], 0.0
    mid_lat, inter_lat = [], []
    last_end = -1.0
    prev_interior = []
    for k, s in enumerate(starts):
        win_end = min(dur, s + win)
        seg = wave[int(s * sr): int(win_end * sr)]
        t0 = time.perf_counter()
        r = asr.transcribe(seg, offset_sec=s)
        compute += time.perf_counter() - t0
        lo = 0.0 if k == 0 else s + margin
        hi = float("inf") if k == len(starts) - 1 else s + margin + hop
        g_lo = s + guard if k > 0 else -1.0
        last = k == len(starts) - 1
        g_hi = float("inf") if last else win_end - guard
        interior = [w for w in r.words if w.start >= g_lo and w.end <= g_hi]
        for w in r.words:
            if lo <= w.start < hi:
                mid.append(w)
                mid_lat.append(win_end - w.end)
            if not last and w.start >= win_end - hop and w.end <= win_end - guard:
                provisional.append(w)
        for w in interior:
            agreed = any(p.text == w.text and abs(p.start - w.start) <= 0.2 for p in prev_interior)
            if (agreed or last) and w.start >= last_end - 0.1:
                inter.append(w)
                inter_lat.append(win_end - w.end)
                last_end = w.end
        prev_interior = interior
    kept = sum(1 for p in provisional
               if any(c.text == p.text and abs(c.start - p.start) <= 0.2 for c in inter))
    common = {"compute_sec": compute, "windows": len(starts),
              "provisional": len(provisional), "provisional_kept": kept}
    return {
        "mid": {**common, "text": " ".join(w.text for w in mid),
                "commit_delay_mean": float(np.mean(mid_lat)) if mid_lat else None},
        "agree": {**common, "text": " ".join(w.text for w in inter),
                     "commit_delay_mean": float(np.mean(inter_lat)) if inter_lat else None},
    }


def anchored(asr: core.QuranAsr, wave: np.ndarray, hop: float = 1.0, max_win: float = 12.0,
             hold_back: int = 2, pad: float = 2.0):
    """
    Anchored growing window (the recommended quasi-streaming mode). Every `hop` s of new audio,
    decode from the anchor (end of the last committed word minus `pad` s) up to "now". Commit every
    word except the last `hold_back` words (they may be cut by the window's right edge), and only
    words that start after the anchor; then move the anchor. If the window reaches `max_win` s,
    commit all but the last word anyway. At the end of the audio, commit everything left.
    The held-back tail is the "provisional" text a live UI would show.
    """
    sr = core.SAMPLE_RATE
    dur = len(wave) / sr
    committed, lat, compute, decodes, win_lens = [], [], 0.0, 0, []
    anchor = 0.0
    now = 0.0
    while now < dur:
        now = min(dur, now + hop)
        start = max(0.0, anchor - pad) if committed else 0.0
        seg = wave[int(start * sr): int(now * sr)]
        t0 = time.perf_counter()
        r = asr.transcribe(seg, offset_sec=start)
        compute += time.perf_counter() - t0
        decodes += 1
        win_lens.append(now - start)
        words = [w for w in r.words if w.start >= anchor - 0.05] if committed else r.words
        if now >= dur:
            take = words
        elif now - start >= max_win:  # never let the window grow without bound
            take = words[:-1] if len(words) > 1 else words
        else:
            take = words[:-hold_back] if len(words) > hold_back else []
        for w in take:
            committed.append(w)
            lat.append(now - w.end)
            anchor = w.end
    return {"text": " ".join(w.text for w in committed), "compute_sec": compute, "windows": decodes,
            "provisional": 0, "provisional_kept": 0, "mean_window_sec": float(np.mean(win_lens)),
            "max_window_sec": float(np.max(win_lens)),
            "commit_delay_mean": float(np.mean(lat)) if lat else None}


# ---------------------------------------------------------------- commands

def cmd_transcribe(args):
    rss0 = core.current_rss_mb()
    asr = core.QuranAsr(args.model, args.tokens, args.threads)
    rss1 = core.current_rss_mb()
    for path in args.audio:
        wave = core.load_audio(path)
        r = asr.transcribe(wave)
        dur = len(wave) / core.SAMPLE_RATE
        print(f"{path}\t{dur:.2f}s\tRTF {(r.feat_sec + r.infer_sec) / dur:.3f}\t{r.text}")
        if args.words:
            for w in r.words:
                print(f"  {w.start:7.2f} {w.end:7.2f}  {w.text}")
    print(f"load {asr.load_sec:.3f}s  rss before load {rss0:.0f} MB, after load {rss1:.0f} MB, "
          f"peak {core.peak_rss_mb():.0f} MB", file=sys.stderr)


def cmd_check_sherpa(args):
    """Cross-check this numpy/onnxruntime pipeline against sherpa-onnx (the engine the author ships)."""
    import sherpa_onnx
    asr = core.QuranAsr(args.model, args.tokens, args.threads)
    rec = sherpa_onnx.OfflineRecognizer.from_zipformer_ctc(model=args.model, tokens=args.tokens,
                                                          num_threads=args.threads)
    same = 0
    for path in args.audio:
        wave = core.load_audio(path)
        ours = asr.transcribe(wave).text
        st = rec.create_stream()
        st.accept_waveform(core.SAMPLE_RATE, wave)
        rec.decode_stream(st)
        theirs = " ".join(st.result.text.replace("▁", " ").split())
        same += ours == theirs
        if ours != theirs:
            print(f"DIFF {path}\n  ours  : {ours}\n  sherpa: {theirs}")
    print(f"identical transcripts: {same}/{len(args.audio)}")


def cmd_check_fbank(args):
    """Compare asr_core.fbank with kaldi-native-fbank (pip install kaldi-native-fbank)."""
    import kaldi_native_fbank as knf
    for path in args.audio:
        w = core.load_audio(path)
        o = knf.FbankOptions()
        o.frame_opts.dither = 0
        o.frame_opts.snip_edges = False
        o.frame_opts.samp_freq = core.SAMPLE_RATE
        o.mel_opts.num_bins = core.NUM_BINS
        o.mel_opts.high_freq = -400
        f = knf.OnlineFbank(o)
        f.accept_waveform(core.SAMPLE_RATE, w.tolist())
        f.input_finished()
        ref = np.stack([np.array(f.get_frame(i)) for i in range(f.num_frames_ready)])
        ours = core.fbank(w)
        n = min(len(ref), len(ours))
        print(f"{path}: frames knf={len(ref)} ours={len(ours)} max|diff|={np.abs(ref[:n] - ours[:n]).max():.2e}")


def cmd_bench(args):
    ref = load_reference(args.ref)
    rss0 = core.current_rss_mb()
    asr = core.QuranAsr(args.model, args.tokens, args.threads)
    rss_loaded = core.current_rss_mb()
    clips = []
    for reciter in sorted(os.listdir(args.audio)):
        d = os.path.join(args.audio, reciter)
        if not os.path.isdir(d):
            continue
        for fn in sorted(os.listdir(d)):
            if fn.endswith((".mp3", ".wav", ".flac")) and fn[:6].isdigit():
                clips.append((reciter, int(fn[:3]), int(fn[3:6]), os.path.join(d, fn)))
    conditions = {
        "clean": lambda w: w,
        "noise10dB": lambda w: core.add_noise(w, 10.0, seed=1),
        "narrowband8k": lambda w: core.narrowband(w),
        "narrowband8k+noise10dB": lambda w: core.add_noise(core.narrowband(w), 10.0, seed=1),
        "noise5dB": lambda w: core.add_noise(w, 5.0, seed=1),
        "noise0dB": lambda w: core.add_noise(w, 0.0, seed=1),
        "tempo1.5": lambda w: core.tempo(w, 1.5),
        "tempo2.0": lambda w: core.tempo(w, 2.0),
    }
    if args.conditions:
        conditions = {k: v for k, v in conditions.items() if k in args.conditions.split(",")}
    chunk_conds = set(args.chunk_conditions.split(","))
    windows = [tuple(float(x) for x in s.split("/")) for s in args.windows.split(",") if s]
    rows = []
    for reciter, sura, aya, path in clips:
        refn = core.normalize(ref[(sura, aya)])
        wave = core.load_audio(path)
        dur = len(wave) / core.SAMPLE_RATE
        for cname, fn in conditions.items():
            w = fn(wave)
            r = asr.transcribe(w)
            wdur = len(w) / core.SAMPLE_RATE
            row = {"reciter": reciter, "ayah": f"{sura}:{aya}", "condition": cname, "mode": "full",
                   "dur": wdur, "words_per_sec": len(refn.split()) / wdur,
                   "compute_sec": r.feat_sec + r.infer_sec, "feat_sec": r.feat_sec,
                   "hyp": r.text, "ref": refn, **score(r.text, refn)}
            rows.append(row)
            if cname in chunk_conds:
                runs = [(f"chunk{win:g}/{hop:g}-{policy}", c) for win, hop in windows
                        for policy, c in chunked(asr, w, win, hop).items()]
                runs.append(("anchored1s", anchored(asr, w)))
                for mode, c in runs:
                        rows.append({"reciter": reciter, "ayah": f"{sura}:{aya}", "condition": cname,
                                     "mode": mode, "dur": wdur,
                                     "mean_window_sec": c.get("mean_window_sec"),
                                     "max_window_sec": c.get("max_window_sec"),
                                     "words_per_sec": len(refn.split()) / wdur,
                                     "compute_sec": c["compute_sec"], "hyp": c["text"], "ref": refn,
                                     "windows": c["windows"], "provisional": c["provisional"],
                                     "provisional_kept": c["provisional_kept"],
                                     "commit_delay_mean": c["commit_delay_mean"], **score(c["text"], refn)})
        print(f"done {reciter} {sura}:{aya} {dur:.1f}s", file=sys.stderr)
    out = {"model": os.path.relpath(args.model, REPO), "threads": args.threads, "load_sec": asr.load_sec,
           "rss_before_load_mb": rss0, "rss_after_load_mb": rss_loaded, "peak_rss_mb": core.peak_rss_mb(),
           "rows": rows}
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    summarize(out)


def summarize(out):
    rows = out["rows"]
    print(f"threads={out['threads']} load={out['load_sec']:.3f}s rss: before load {out['rss_before_load_mb']:.0f} MB, "
          f"after load {out['rss_after_load_mb']:.0f} MB, peak {out['peak_rss_mb']:.0f} MB")

    def agg(sel):
        we = sum(r["word_edits"] for r in sel); rw = sum(r["ref_words"] for r in sel)
        ce = sum(r["char_edits"] for r in sel); rc = sum(r["ref_chars"] for r in sel)
        dur = sum(r["dur"] for r in sel); comp = sum(r["compute_sec"] for r in sel)
        exact = sum(1 for r in sel if r["word_edits"] == 0)
        return we, rw, ce, rc, dur, comp, exact

    print("\n| condition | mode | clips | audio s | WER % | word acc % | CER % | exact clips | RTF |")
    print("|---|---|---:|---:|---:|---:|---:|---:|---:|")
    keys = sorted({(r["condition"], r["mode"]) for r in rows}, key=lambda k: (k[1] != "full", k))
    for cond, mode in keys:
        sel = [r for r in rows if r["condition"] == cond and r["mode"] == mode]
        we, rw, ce, rc, dur, comp, exact = agg(sel)
        print(f"| {cond} | {mode} | {len(sel)} | {dur:.0f} | {100 * we / rw:.2f} | {100 - 100 * we / rw:.2f} | "
              f"{100 * ce / rc:.2f} | {exact}/{len(sel)} | {comp / dur:.3f} |")
    conds = [c for c in ("clean", "noise10dB", "noise5dB", "noise0dB", "narrowband8k",
                         "narrowband8k+noise10dB", "tempo1.5", "tempo2.0")
             if any(r["condition"] == c and r["mode"] == "full" for r in rows)]
    print("\n| reciter | words/s (clean) | " + " | ".join(f"{c} WER %" for c in conds) + " |")
    print("|---|---:|" + "---:|" * len(conds))
    for rec in sorted({r["reciter"] for r in rows}):
        cells = []
        for cond in conds:
            sel = [r for r in rows if r["reciter"] == rec and r["condition"] == cond and r["mode"] == "full"]
            we, rw, *_ = agg(sel)
            cells.append(f"{100 * we / rw:.2f}")
        wps = statistics.mean(r["words_per_sec"] for r in rows
                              if r["reciter"] == rec and r["condition"] == "clean" and r["mode"] == "full")
        print(f"| {rec} | {wps:.2f} | " + " | ".join(cells) + " |")
    ch = [r for r in rows if r["mode"] != "full"]
    for mode in sorted({r["mode"] for r in ch}):
        for cond in sorted({r["condition"] for r in ch}):
            sel = [r for r in ch if r["mode"] == mode and r["condition"] == cond]
            if not sel:
                continue
            prov = sum(r["provisional"] for r in sel); kept = sum(r["provisional_kept"] for r in sel)
            delays = [r["commit_delay_mean"] for r in sel if r["commit_delay_mean"] is not None]
            delays = [r["commit_delay_mean"] for r in sel if r["commit_delay_mean"] is not None]
            if mode == "anchored1s":
                mw = statistics.mean(r["mean_window_sec"] for r in sel)
                xw = max(r["max_window_sec"] for r in sel)
                print(f"anchored1s {cond}: mean decoded window {mw:.1f}s (max {xw:.1f}s), "
                      f"mean commit delay after word end {statistics.mean(delays):.2f}s (audio time, excl. compute)")
                continue
            if "agree" not in mode:
                continue
            print(f"{mode.replace('-agree', '')} {cond}: provisional words kept {kept}/{prov} ({100 * kept / max(1, prov):.0f}%), "
                  f"mean commit delay (agree policy) after word end {statistics.mean(delays):.2f}s (audio time, excl. compute)")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--model", default=DEFAULT_MODEL)
    p.add_argument("--tokens", default=DEFAULT_TOKENS)
    p.add_argument("--threads", type=int, default=2)
    sub = p.add_subparsers(dest="cmd", required=True)
    t = sub.add_parser("transcribe"); t.add_argument("audio", nargs="+"); t.add_argument("--words", action="store_true")
    c = sub.add_parser("check-sherpa"); c.add_argument("audio", nargs="+")
    k = sub.add_parser("check-fbank"); k.add_argument("audio", nargs="+")
    b = sub.add_parser("bench")
    b.add_argument("--audio", required=True)
    b.add_argument("--ref", required=True)
    b.add_argument("--out", required=True)
    b.add_argument("--windows", default="3/1,5/1", help="chunked modes as win/hop seconds, comma separated")
    b.add_argument("--conditions", default="", help="comma-separated subset of conditions (default: all)")
    b.add_argument("--chunk-conditions", default="clean,noise10dB,tempo1.5",
                   help="conditions on which the chunked modes also run")
    s = sub.add_parser("summarize"); s.add_argument("json")
    args = p.parse_args()
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    if args.cmd == "transcribe":
        cmd_transcribe(args)
    elif args.cmd == "check-sherpa":
        cmd_check_sherpa(args)
    elif args.cmd == "check-fbank":
        cmd_check_fbank(args)
    elif args.cmd == "bench":
        cmd_bench(args)
    else:
        with open(args.json, encoding="utf-8") as f:
            summarize(json.load(f))


if __name__ == "__main__":
    main()
