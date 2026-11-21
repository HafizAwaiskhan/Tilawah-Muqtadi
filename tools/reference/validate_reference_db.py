#!/usr/bin/env python3
"""Validate data/build/quran_ref_v1.db against the raw sources and the documented invariants.

Python 3 stdlib + sqlite3 only:

    python3 -I tools/reference/validate_reference_db.py [path/to/quran_ref_v1.db]

Exit status 0 = all hard checks passed. Writes data/build/validation_report.txt.
"""
import collections
import gzip
import hashlib
import importlib.util
import json
import os
import sqlite3
import sys
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True  # importing the builder must not leave __pycache__ behind

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
RAW = os.path.join(REPO, "data", "raw")
DEFAULT_DB = os.path.join(REPO, "data", "build", "quran_ref_v1.db")

spec = importlib.util.spec_from_file_location("build_reference_db", os.path.join(HERE, "build_reference_db.py"))
B = importlib.util.module_from_spec(spec)
spec.loader.exec_module(B)

PHONEMES = set("' b t th j H kh d dh r z s sh S D T Z E gh f q k l m n h w y a i u aa ii uu".split())

lines = []
failures = []


def log(msg):
    lines.append(msg)
    print(msg)


def check(name, ok, detail=""):
    log(f"[{'PASS' if ok else 'FAIL'}] {name}" + (f" - {detail}" if detail else ""))
    if not ok:
        failures.append(name)


def main():
    db = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_DB
    con = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
    q = lambda sql, *a: con.execute(sql, a).fetchall()
    log(f"DB: {os.path.relpath(db, REPO)} ({os.path.getsize(db)} bytes, sha256 "
        f"{hashlib.sha256(open(db, 'rb').read()).hexdigest()})")

    # ---- meta
    meta = dict(q("SELECT key, value FROM meta"))
    for k in ("schema_version", "data_version", "build_date", "phonetic_status", "word_count_expected",
              "manifest_sha256.quran_com_api_v4", "manifest_sha256.tanzil"):
        check(f"meta has {k}", k in meta, meta.get(k, ""))
    check("phonetic marked as requiring teacher review", "requires teacher review" in meta.get("phonetic_status", ""))

    # ---- checksums: raw files vs manifests, DB provenance vs manifests
    srcs = {n: B.RawSource(n) for n in ("quran_com_api_v4", "tanzil")}
    bad = []
    for n, s in srcs.items():
        for path in s.files:
            try:
                s.read(path)
            except SystemExit as e:
                bad.append(str(e))
        man_sha = hashlib.sha256(s.manifest_bytes).hexdigest()
        check(f"meta manifest_sha256.{n} matches data/raw/{n}/MANIFEST.json", meta.get(f"manifest_sha256.{n}") == man_sha)
    check("raw file checksums match MANIFEST.json (all files)", not bad, "; ".join(bad[:3]))
    prov = q("SELECT source, path, sha256 FROM source_file")
    mism = [p for p in prov if srcs[p[0]].files[p[1]]["sha256"] != p[2]]
    check("source_file table checksums match manifests", not mism and len(prov) > 0, f"{len(prov)} rows")

    # ---- totals
    n_s = q("SELECT COUNT(*) FROM surah")[0][0]
    n_a = q("SELECT COUNT(*) FROM ayah")[0][0]
    n_w = q("SELECT COUNT(*) FROM word")[0][0]
    check("114 surahs", n_s == 114, str(n_s))
    check("6236 ayahs", n_a == 6236, str(n_a))
    check(f"word count == expected {meta.get('word_count_expected')}", str(n_w) == meta.get("word_count_expected"), str(n_w))

    # ---- ayah counts vs metadata (DB surah table, Tanzil quran-data.xml, quran.com chapters)
    tz_meta = ET.fromstring(srcs["tanzil"].read("quran-data.xml"))
    tz_ayas = {int(s.get("index")): int(s.get("ayas")) for s in tz_meta.find("suras").findall("sura")}
    qc_ayas = {c["id"]: c["verses_count"] for c in srcs["quran_com_api_v4"].json("chapters.json")["chapters"]}
    db_counts = dict(q("SELECT surah, COUNT(*) FROM ayah GROUP BY surah"))
    db_meta = dict(q("SELECT id, ayah_count FROM surah"))
    diff = [s for s in range(1, 115) if not (db_counts.get(s) == db_meta.get(s) == tz_ayas.get(s) == qc_ayas.get(s))]
    check("ayah count per surah: DB ayah rows == surah.ayah_count == Tanzil metadata == Quran.com", not diff, str(diff[:10]))
    check("sum(Tanzil ayas) == 6236", sum(tz_ayas.values()) == 6236)

    # ---- structural invariants
    ids = [r[0] for r in q("SELECT id FROM word ORDER BY id")]
    check("word.id contiguous 0..N-1", ids == list(range(n_w)))
    gis = [r[0] for r in q("SELECT global_index FROM ayah ORDER BY surah, ayah")]
    check("ayah.global_index contiguous 0..6235 in surah/ayah order", gis == list(range(n_a)))
    seq_bad = []
    by_ayah = collections.defaultdict(list)
    for s, a, wi, wid, agi in q("SELECT surah, ayah, word_index, id, ayah_global_index FROM word ORDER BY id"):
        by_ayah[(s, a)].append((wi, wid, agi))
    for s, a, wc, fw, gi in q("SELECT surah, ayah, word_count, first_word_id, global_index FROM ayah"):
        ws = by_ayah.get((s, a), [])
        if ([w[0] for w in ws] != list(range(1, wc + 1)) or (ws and ws[0][1] != fw)
                or any(w[2] != gi for w in ws)):
            seq_bad.append(f"{s}:{a}")
    check("word_index contiguous 1..word_count per ayah; first_word_id/ayah_global_index consistent",
          not seq_bad, str(seq_bad[:5]))
    check("sum(ayah.word_count) == word rows", q("SELECT SUM(word_count) FROM ayah")[0][0] == n_w)

    # ---- no empty fields
    for col in ("text_uthmani", "text_indopak", "norm_skeleton", "phonetic"):
        n = q(f"SELECT COUNT(*) FROM word WHERE {col} IS NULL OR TRIM({col}) = ''")[0][0]
        check(f"no empty word.{col}", n == 0, str(n))
    for col in ("text_uthmani", "text_uthmani_tanzil", "text_indopak", "text_indopak_nastaleeq"):
        n = q(f"SELECT COUNT(*) FROM ayah WHERE {col} IS NULL OR TRIM({col}) = ''")[0][0]
        check(f"no empty ayah.{col}", n == 0, str(n))

    # ---- byte-for-byte text provenance
    qc = srcs["quran_com_api_v4"]
    db_words = {(s, a, wi): (u, ip) for s, a, wi, u, ip in q(
        "SELECT surah, ayah, word_index, text_uthmani, text_indopak FROM word")}
    db_ayah = {(s, a): r for s, a, *r in q(
        "SELECT surah, ayah, text_uthmani, text_indopak, text_indopak_nastaleeq, text_uthmani_tanzil FROM ayah")}
    nq = {v["verse_key"]: v["text_indopak_nastaleeq"] for v in qc.json("ayah_text/indopak_nastaleeq.json.gz")["verses"]}
    wdiff, adiff = 0, 0
    for s in range(1, 115):
        for v in qc.json(f"words/{s:03d}.json.gz")["verses"]:
            a = v["verse_number"]
            r = db_ayah[(s, a)]
            if (r[0], r[1], r[2]) != (v["text_uthmani"], v["text_indopak"], nq[v["verse_key"]]):
                adiff += 1
            for w in v["words"]:
                if w["char_type_name"] == "word" and db_words.get((s, a, w["position"])) != (
                        w["text_uthmani"], w["text_indopak"]):
                    wdiff += 1
    check("word.text_uthmani / text_indopak byte-identical to Quran.com raw JSON", wdiff == 0, str(wdiff))
    check("ayah Quran.com texts byte-identical to raw JSON", adiff == 0, str(adiff))
    tz = {}
    for sura in ET.fromstring(srcs["tanzil"].read("quran-uthmani.xml")).findall("sura"):
        for aya in sura.findall("aya"):
            tz[(int(sura.get("index")), int(aya.get("index")))] = aya.get("text")
    tdiff = sum(1 for k, r in db_ayah.items() if r[3] != tz.get(k))
    check("ayah.text_uthmani_tanzil byte-identical to Tanzil XML", tdiff == 0 and len(tz) == 6236, str(tdiff))
    sub_bad = 0
    for s, a, t in q("SELECT surah, ayah, text_indopak_nastaleeq FROM word WHERE text_indopak_nastaleeq IS NOT NULL"):
        if t not in db_ayah[(s, a)][2]:
            sub_bad += 1
    check("every word.text_indopak_nastaleeq is an exact substring of its ayah text", sub_bad == 0, str(sub_bad))

    # ---- cross-source consistency
    n_tz = q("SELECT COUNT(*) FROM ayah WHERE tanzil_skeleton_match = 0")[0][0]
    check("Quran.com Uthmani vs Tanzil Uthmani: ayah norm_skeleton identical", n_tz == 0, f"{n_tz} ayahs differ")

    # ---- derived columns are deterministic functions of the source text
    der_bad = 0
    for u, sk, ph, wi, marks in q("SELECT text_uthmani, norm_skeleton, phonetic, word_index, stop_marks FROM word"):
        if sk != B.norm_skeleton(u) or ph != B.g2p(u, wi == 1) or marks != B.stop_marks(u):
            der_bad += 1
    check("norm_skeleton / phonetic / stop_marks reproduce from text_uthmani", der_bad == 0, str(der_bad))
    inv = collections.Counter()
    for (p,) in q("SELECT phonetic FROM word"):
        inv.update(p.split())
    check("phonetic tokens within documented symbol set", set(inv) <= PHONEMES, str(sorted(set(inv) - PHONEMES)))

    # ---- mapping coverage
    log("Mapping coverage:")
    for layer in ("indopak_check", "nastaleeq_map"):
        dist = dict(q(f"SELECT {layer}, COUNT(*) FROM word GROUP BY {layer}"))
        ok = sum(v for k, v in dist.items() if k in ("match", "split_1to2", "merged_2to1"))
        log(f"  word.{layer}: {json.dumps(dist, sort_keys=True)}  verified={ok}/{n_w} ({100.0 * ok / n_w:.3f}%)")
    nn = q("SELECT COUNT(*) FROM word WHERE text_indopak_nastaleeq IS NOT NULL")[0][0]
    log(f"  word.text_indopak_nastaleeq non-null: {nn}/{n_w}")
    issues = q("SELECT COUNT(*) FROM word_map_issue")[0][0]
    expected_issues = q("SELECT COUNT(*) FROM word WHERE indopak_check != 'match'")[0][0] + q(
        "SELECT COUNT(*) FROM word WHERE nastaleeq_map NOT IN ('match','split_1to2','merged_2to1','merged_into_prev')")[0][0]
    check("every non-verified mapping is listed in word_map_issue", issues == expected_issues, f"{issues} issues")
    check("Indo-Pak (Quran.com word) verified coverage >= 99.9%",
          q("SELECT COUNT(*) FROM word WHERE indopak_check='match'")[0][0] / n_w >= 0.999)

    log(f"RESULT: {'ALL CHECKS PASSED' if not failures else 'FAILED: ' + ', '.join(failures)}")
    out = os.path.join(os.path.dirname(db), "validation_report.txt")
    with open(out, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
