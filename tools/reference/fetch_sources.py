#!/usr/bin/env python3
"""Download the verified Quran reference sources into data/raw/<source>/.

Python 3 stdlib only. Run from the repo root with:

    python3 -I tools/reference/fetch_sources.py [--only tanzil,quran_com_api_v4,license_evidence]

Every response body is stored exactly as received (byte-for-byte). Large JSON
responses are stored gzip-compressed (mtime=0, no filename => reproducible
container); the MANIFEST records the SHA-256 of the *uncompressed response
bytes* (`sha256`) and of the stored file (`stored_sha256`).

Nothing in this script edits, re-types or "fixes" Quran text.
"""
import argparse
import datetime as _dt
import gzip
import hashlib
import io
import json
import os
import sys
import time
import urllib.request

REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
RAW = os.path.join(REPO, "data", "raw")
UA = "Mozilla/5.0 (compatible; reference-data-fetcher/1.0)"

TANZIL_LICENSE = (
    "Tanzil Quran Text, Copyright (C) 2007-2026 Tanzil Project, Creative Commons Attribution 3.0. "
    "Verbatim copies only - CHANGING IT IS NOT ALLOWED; source (Tanzil Project) must be clearly "
    "indicated with a link to tanzil.net; copyright notice must be included. "
    "See https://tanzil.net/docs/text_license"
)
QURAN_COM_LICENSE = (
    "UNVERIFIED - Quran.com / Quran Foundation API v4 public endpoint; no license or terms text is "
    "served by api.quran.com and the API terms pages (api-docs.quran.com, quran.foundation) were not "
    "reachable from the build environment. Underlying text provenance per Quran.com/QUL. "
    "Treat as research use until written terms/permission are on file."
)
SEGMENTS_LICENSE = (
    "UNVERIFIED - word timing segments for recitation id 7 (Mishari Rashid al-Afasy) from the "
    "Quran.com API v4. No license stated by api.quran.com; QUL lists the same reciter with "
    "\"We don't have copyright information for this resource.\" Internal R&D only; do not ship."
)
EVIDENCE_LICENSE = "Copy of a public web page kept only as evidence of its license/terms wording on the retrieval date."

API = "https://api.quran.com/api/v4"


def now_iso():
    return _dt.datetime.now(_dt.timezone.utc).replace(microsecond=0).isoformat()


def fetch(url, tries=3):
    last = None
    for i in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Encoding": "identity"})
            with urllib.request.urlopen(req, timeout=120) as r:
                body = r.read()
                return body, r.headers.get("Content-Type", "")
        except Exception as e:  # network hiccup / proxy warm-up
            last = e
            time.sleep(5 * (i + 1))
    raise RuntimeError(f"failed to fetch {url}: {last}")


def sha256(b):
    return hashlib.sha256(b).hexdigest()


def store(source_dir, relpath, url, license_text, compress=False, note=None):
    body, ctype = fetch(url)
    path = os.path.join(source_dir, relpath)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    entry = {
        "path": relpath,
        "url": url,
        "retrieved_at": now_iso(),
        "content_type": ctype,
        "bytes": len(body),
        "sha256": sha256(body),
        "license": license_text,
    }
    if compress:
        buf = io.BytesIO()
        with gzip.GzipFile(filename="", mode="wb", fileobj=buf, mtime=0, compresslevel=9) as gz:
            gz.write(body)
        data = buf.getvalue()
        entry["stored_as"] = "gzip(mtime=0) of the exact response bytes"
        entry["stored_bytes"] = len(data)
        entry["stored_sha256"] = sha256(data)
    else:
        data = body
    if note:
        entry["note"] = note
    with open(path, "wb") as f:
        f.write(data)
    print(f"  {relpath}: {len(body)} bytes sha256={entry['sha256'][:16]}...", flush=True)
    return entry


def write_manifest(source_dir, source, description, files):
    manifest = {
        "source": source,
        "description": description,
        "generated_by": "tools/reference/fetch_sources.py",
        "files": files,
    }
    with open(os.path.join(source_dir, "MANIFEST.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
        f.write("\n")


def fetch_tanzil():
    d = os.path.join(RAW, "tanzil")
    print("tanzil ->", d)
    files = [
        store(d, "quran-uthmani.xml",
              "https://tanzil.net/pub/download/index.php?marks=true&sajdah=true&rub=false&tatweel=true"
              "&quranType=uthmani&outType=xml&agree=true",
              TANZIL_LICENSE,
              note="Tanzil Uthmani (Hafs) text, XML. Options: pause marks=on, sajdah sign=on, rub-el-hizb=off, "
                   "tatweel=on (site defaults for Uthmani)."),
        store(d, "quran-data.xml", "https://tanzil.net/res/text/metadata/quran-data.xml",
              "Tanzil Quran metadata (see https://tanzil.net/docs/Quran_Metadata); distributed with Tanzil text; "
              "same project terms assumed - attribution to Tanzil Project.",
              note="Surah names, ayah counts, revelation type/order, juz/hizb/page/ruku/sajda partitions."),
    ]
    write_manifest(d, "Tanzil Project (tanzil.net)", "Uthmani Hafs Quran text + metadata", files)


def fetch_quran_com():
    d = os.path.join(RAW, "quran_com_api_v4")
    print("quran_com_api_v4 ->", d)
    files = [store(d, "chapters.json", f"{API}/chapters?language=en", QURAN_COM_LICENSE)]
    for t in ("indopak", "uthmani", "indopak_nastaleeq"):
        files.append(store(d, f"ayah_text/{t}.json.gz", f"{API}/quran/verses/{t}", QURAN_COM_LICENSE, compress=True,
                           note=f"Whole-Quran ayah-level text_{t}"))
    for s in range(1, 115):
        url = (f"{API}/verses/by_chapter/{s}?words=true&per_page=300&page=1"
               "&fields=text_indopak,text_uthmani"
               "&word_fields=text_indopak,text_uthmani,location,verse_key")
        files.append(store(d, f"words/{s:03d}.json.gz", url, QURAN_COM_LICENSE, compress=True,
                           note="Word-by-word: each word carries text_indopak and text_uthmani under one location key s:a:w"))
    for s in range(1, 115):
        url = f"{API}/recitations/7/by_chapter/{s}?per_page=300&page=1&fields=segments"
        files.append(store(d, f"segments_recitation7/{s:03d}.json.gz", url, SEGMENTS_LICENSE, compress=True))
    write_manifest(d, "Quran.com API v4 (api.quran.com)",
                   "Word-by-word Indo-Pak + Uthmani text keyed by surah:ayah:word, ayah-level texts, "
                   "chapter metadata, word timing segments (recitation 7, UNVERIFIED license)", files)


def fetch_evidence():
    d = os.path.join(RAW, "license_evidence")
    print("license_evidence ->", d)
    pages = [
        ("tanzil_text_license.html", "https://tanzil.net/docs/text_license"),
        ("qul_faq.html", "https://qul.tarteel.ai/faq"),
        ("qul_indopak_nastaleeq_wbw_licence.html", "https://qul.tarteel.ai/resources/quran-script/59/licence"),
        ("qul_indopak_nastaleeq_font_licence.html", "https://qul.tarteel.ai/resources/font/242/licence"),
        ("qul_alafasy_recitation_licence.html", "https://qul.tarteel.ai/resources/recitation/118/licence"),
        ("qul_indopak_15_lines_layout_licence.html", "https://qul.tarteel.ai/resources/mushaf-layout/12/licence"),
        ("qul_github_LICENSE.txt", "https://raw.githubusercontent.com/TarteelAI/quranic-universal-library/main/LICENSE"),
    ]
    files = [store(d, name, url, EVIDENCE_LICENSE) for name, url in pages]
    write_manifest(d, "License evidence snapshots", "Pages whose wording is quoted in docs/LICENSES.md", files)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", default="tanzil,quran_com_api_v4,license_evidence")
    a = ap.parse_args()
    want = set(a.only.split(","))
    if "tanzil" in want:
        fetch_tanzil()
    if "quran_com_api_v4" in want:
        fetch_quran_com()
    if "license_evidence" in want:
        fetch_evidence()


if __name__ == "__main__":
    sys.exit(main())
