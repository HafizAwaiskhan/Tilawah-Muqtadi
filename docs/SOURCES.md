# Quran reference data sources

Retrieved **2026-11-21** (UTC) from the build environment. Exact URLs, timestamps and
SHA-256 for every file are in `data/raw/<source>/MANIFEST.json`. Licence verdicts are in
[`LICENSES.md`](LICENSES.md). Re-fetch everything with:

```
python3 -I tools/reference/fetch_sources.py            # all sources
python3 -I tools/reference/build_reference_db.py       # -> data/build/quran_ref_v1.db
python3 -I tools/reference/validate_reference_db.py    # -> data/build/validation_report.txt
```

Raw data is **8.1 MB** in git (large JSON is stored gzip'd, mtime=0; the manifest records the
SHA-256 of the exact response bytes *and* of the stored `.gz`). Nothing exceeds 20 MB, so no
out-of-git fetch is required. Audio is **not** downloaded.

## Chosen sources (used in `quran_ref_v1.db`)

| Source | What we took | URL(s) | Version | Stored at |
|---|---|---|---|---|
| **Quran.com API v4** | Word-by-word text: each word record carries `text_uthmani` **and** `text_indopak` under one key `location = surah:ayah:word` (77,429 words, plus 6,236 `char_type_name="end"` ayah-number records which we drop) | `https://api.quran.com/api/v4/verses/by_chapter/{1..114}?words=true&per_page=300&fields=text_indopak,text_uthmani&word_fields=text_indopak,text_uthmani,location,verse_key` | unversioned live API; pinned by SHA-256 | `data/raw/quran_com_api_v4/words/NNN.json.gz` |
| Quran.com API v4 | Ayah-level `text_indopak_nastaleeq` (the KFGQPC/QUL "Indopak Nastaleeq" encoding; needs its font, contains private-use glyphs) | `.../quran/verses/indopak_nastaleeq` | same | `ayah_text/indopak_nastaleeq.json.gz` |
| Quran.com API v4 | Ayah-level `text_indopak`, `text_uthmani` (kept for cross-checks; not used by the builder) | `.../quran/verses/indopak`, `.../quran/verses/uthmani` | same | `ayah_text/*.json.gz` |
| Quran.com API v4 | Surah metadata: Arabic / transliterated / English names, ayah count, revelation place & order, bismillah flag | `.../chapters?language=en` | same | `chapters.json` |
| Quran.com API v4 | Word timing segments, recitation id 7 (Mishari Rashid al-`Afasy, ayah-by-ayah files): `[word_idx0, word_position, start_ms, end_ms]` per ayah | `.../recitations/7/by_chapter/{1..114}?per_page=300&fields=segments` | same | `segments_recitation7/NNN.json.gz` — **licence UNVERIFIED; not in the DB by default** |
| **Tanzil** | Uthmani (Hafs) ayah text, XML, pause marks + sajdah sign on, rub-el-hizb off, tatweel on | `https://tanzil.net/pub/download/index.php?marks=true&sajdah=true&rub=false&tatweel=true&quranType=uthmani&outType=xml&agree=true` | "Tanzil Quran Text (Uthmani, Version 1.1)" (from file header) | `data/raw/tanzil/quran-uthmani.xml` |
| Tanzil | Metadata: suras (ayas, names, type, order, rukus), juz, hizb quarters, manzil, ruku, page, sajda | `https://tanzil.net/res/text/metadata/quran-data.xml` | "ver 1.0" (docs page) | `data/raw/tanzil/quran-data.xml` |
| Licence evidence | Snapshots of the pages quoted in LICENSES.md | see `data/raw/license_evidence/MANIFEST.json` | — | `data/raw/license_evidence/` |

**Cross-check result:** the Quran.com `text_uthmani` and the Tanzil Uthmani text have an
identical `norm_skeleton` for **all 6,236 ayahs** (the Quran.com Uthmani encoding is the
Tanzil-style encoding). Ayah counts agree across Tanzil metadata, Quran.com and the DB for all
114 surahs.

## Evaluated, not used (yet)

### QUL — Quranic Universal Library (`qul.tarteel.ai`, by Tarteel)
* **Offers** (resources page, 2026-11-21): Quran scripts (30: KFGQPC Hafs WBW, *Indopak
  Nastaleeq script – Word by Word* (id 59), *Digital Khatt Indopak – WBW*, QPC Nastaleeq WBW,
  V1/V2/V4 glyphs, Imlaei…), fonts (26, incl. *Indopak Nastaleeq font* (id 242), *KFGQPC
  Nastaleeq* (462), *Digital Khatt Indopak font* (568)), recitations (131: 73 unsegmented, 58
  segmented), mushaf layouts (20 approved incl. *Indopak 15 lines (Qudratullah)* (12), *Indopak
  16 lines (Taj company)* (11), 13-line and 9-line variants), metadata, morphology, etc.
  Formats: **SQLite and JSON**; "no API is available".
* **Segment format** (recitation docs page): "A segment is a three-number array: `[word
  position, start in ms, end in ms]`. Word positions start at 1 and match the word order of the
  ayah." Surah-by-surah (gapless, 114 files) or ayah-by-ayah (gapped, 6,236 files).
* **Blocked:** every "Download sqlite/json" link opens a **sign-in modal**
  (`/users/sign_in?modal=true&user_return_to=...`), so files could not be fetched
  automatically. A user with a QUL account can download *Indopak Nastaleeq script – Word by
  Word* (id 59) and drop it into `data/raw/qul/` with a manifest entry.
* Every QUL resource licence tab checked (all 131 recitations, 17 fonts, 11 layouts, scripts 59,
  86, 312) says: **"We don't have copyright information for this resource."**

### Qari audio
* **everyayah.com** — ayah-by-ayah MP3 per reciter under `https://everyayah.com/data/<Reciter_bitrate>/SSSAAA.mp3`,
  reciter list in `data/recitations.js`, timing files under `data/timings_files/`. No licence or
  permission text found on the home page, the old index page, `recitations.js` or reciter
  folder listings (they contain only MP3s, zips and `000_checksum.md5`). Contact link points to
  `quran.zendesk.com`.
* **Quran.com audio** — `audio_url`s point to `verses.quran.com` (e.g. `Alafasy/mp3/001001.mp3`)
  and `download.quranicaudio.com/qdc/...`; word audio `wbw/001_001_001.mp3`. No licence served.
* **QUL recitations** — see above (no copyright information; login required).

## Word-level Indo-Pak: known data-quality issues

`text_indopak` at word level (Quran.com) is the only freely downloadable word-segmented
Indo-Pak text, but it is **noisy**: it contains private-use waqf glyphs (U+E003–U+E022), RLM,
ZWSP, EN/EM spaces and BOMs, and in **8 ayahs the Indo-Pak words are shifted one slot against
the Uthmani words** (56:45, 59:4, 65:10, 74:46, 93:7, 103:3, 107:3, 109:5), plus 2 genuine
orthographic differences (21:88:7, 72:16:1) — 45 words flagged in total, see
`data/build/mapping_report.tsv`. The ayah-level `text_indopak_nastaleeq` is cleaner; the
builder splits it into exact substrings aligned to the word keys (see REFERENCE_DB_SCHEMA.md).
