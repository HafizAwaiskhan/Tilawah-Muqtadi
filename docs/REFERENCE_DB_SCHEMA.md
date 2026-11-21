# `quran_ref_v1.db` — reference database schema (schema_version 1)

* File: `data/build/quran_ref_v1.db` (SQLite 3, 18,014,208 bytes, committed; read-only asset).
* Built by `python3 -I tools/reference/build_reference_db.py` from `data/raw/` (checksums verified
  against each `MANIFEST.json` before use). Reproducible: with `SOURCE_DATE_EPOCH=1795219200`
  two builds are byte-identical (sha256 `456a99fd…8fed8a` for the committed file).
* Validated by `python3 -I tools/reference/validate_reference_db.py` (results below).
* Side outputs: `data/build/mapping_report.tsv` (every unverified word mapping),
  `data/build/build_report.json`, `data/build/validation_report.txt`.
* Note: the repo root `.gitignore` ignores `build/`; `data/build/*` is committed with `git add -f`.

**Text rule.** Every `text_*` column is a byte-for-byte copy of a downloaded source string, or
(only `word.text_indopak_nastaleeq`) an exact substring of the downloaded ayah string. Derived
columns are deterministic code output and are never shown as Quran text.

## Tables

### `meta(key TEXT PRIMARY KEY, value TEXT)`
Keys: `schema_version` (`1`), `data_version` (`quran_ref_v1+<12 hex of manifest hashes>`, currently
`quran_ref_v1+8b81e3266c6c`), `build_date`, `builder`, `word_count_expected` (`77429`),
`word_id_base` (`0`), `ayah_global_index_base` (`0`), `norm_skeleton_version`
(`norm-skeleton-v1`), `loose_key_version` (`loose-key-v1`), `phonetic_version` (`hafs-g2p-v0`),
`phonetic_status` (`v0 - requires teacher review`), `phonetic_symbols`,
`manifest_sha256.quran_com_api_v4`, `manifest_sha256.tanzil`, `source.word_text`,
`source.ayah_tanzil`, `attribution.tanzil` (notice to display), `segments_included` (`0`/`1`).

### `source_file(source, path, url, retrieved_at, sha256, license)` — PK (source, path)
One row per raw file used (120 rows). `sha256` is of the exact response bytes.

### `surah` — 114 rows
| column | type | notes |
|---|---|---|
| `id` | INTEGER PK | 1..114 |
| `name_arabic` | TEXT | Quran.com `name_arabic` |
| `name_transliterated` | TEXT | Quran.com `name_simple` (e.g. `Al-Fatihah`) |
| `name_english` | TEXT | Quran.com `translated_name.name` |
| `ayah_count` | INTEGER | = Tanzil `ayas` = Quran.com `verses_count` (validated) |
| `revelation_place` | TEXT | `makkah` / `madinah` |
| `revelation_order` | INTEGER | |
| `bismillah_pre` | INTEGER 0/1 | basmala shown before ayah 1 (0 for 1 and 9) |
| `first_ayah_global_index` | INTEGER | |
| `first_word_id` | INTEGER | |

### `ayah` — 6,236 rows, PK (surah, ayah)
| column | type | notes |
|---|---|---|
| `surah`, `ayah` | INTEGER | |
| `global_index` | INTEGER UNIQUE | **0..6235** in mushaf order |
| `text_indopak` | TEXT | Quran.com ayah `text_indopak` (contains PUA/format chars) |
| `text_indopak_nastaleeq` | TEXT | Quran.com `text_indopak_nastaleeq` (needs Indopak Nastaleeq font; ends with PUA ayah-number glyph) |
| `text_uthmani` | TEXT | Quran.com ayah `text_uthmani` |
| `text_uthmani_tanzil` | TEXT | Tanzil Uthmani 1.1, verbatim |
| `word_count` | INTEGER | number of `word` rows |
| `first_word_id` | INTEGER | `word.id` of word 1 |
| `juz`, `hizb`, `rub_el_hizb`, `page_madani` | INTEGER | Quran.com verse fields |
| `sajdah` | INTEGER 0/1 | from Tanzil `<sajdas>` (15) |
| `starts_rub_el_hizb` | INTEGER 0/1 | ۞ present in Uthmani text |
| `stop_marks` | TEXT | concatenation of word `stop_marks` (waqf signs, U+06D6–U+06DC) |
| `tanzil_skeleton_match` | INTEGER 0/1 | `norm_skeleton(text_uthmani) == norm_skeleton(text_uthmani_tanzil)` |

### `word` — 77,429 rows, UNIQUE (surah, ayah, word_index)
| column | type | notes |
|---|---|---|
| `id` | INTEGER PK | **0..77428** global, mushaf order |
| `surah`, `ayah`, `word_index` | INTEGER | `word_index` 1-based = Quran.com `position` = QUL/Quran.com segment word position |
| `ayah_global_index` | INTEGER | → `ayah.global_index` |
| `text_uthmani` | TEXT | Quran.com word `text_uthmani` (may include a trailing space + waqf sign, e.g. `رَيْبَ ۛ`) |
| `text_indopak` | TEXT | Quran.com word `text_indopak` — same record/key as `text_uthmani` |
| `text_indopak_nastaleeq` | TEXT NULL | exact substring of `ayah.text_indopak_nastaleeq` aligned to this word (see Mapping); NULL only if `nastaleeq_map='merged_into_prev'`/`none` (currently never) |
| `norm_skeleton` | TEXT | normalization of `text_uthmani` (rules N1–N8); indexed |
| `phonetic` | TEXT | Hafs G2P v0 of `text_uthmani`, space-separated tokens |
| `stop_marks` | TEXT | waqf sign characters found in `text_uthmani`, in order ('' if none) |
| `indopak_check` | TEXT | `match` / `fuzzy` / `mismatch` — verification of `text_indopak` vs `text_uthmani` |
| `nastaleeq_map` | TEXT | `match` / `split_1to2` / `merged_2to1` / `merged_into_prev` / `pua_glyph` / `fuzzy` / `mismatch` / `none` |

### `word_map_issue(word_id, layer, status, uthmani_key, other_key, other_text)` — PK (word_id, layer)
Every word whose mapping is not verified. `layer` = `indopak_word` (Quran.com word
`text_indopak`) or `nastaleeq_ayah_span`. 70 rows (45 + 25).

### `segment_unverified(recitation_id, surah, ayah, word_index, start_ms, end_ms, audio_path)`
Only present when built with `--include-unverified-segments` (licence UNVERIFIED). Not in the
committed DB.

**Android usage hint:** display Indo-Pak per word from `text_indopak_nastaleeq` when
`nastaleeq_map IN ('match','split_1to2','merged_2to1','pua_glyph')` (with the Nastaleeq font),
else fall back to `text_indopak` when `indopak_check='match'`, else render the whole ayah
without word highlighting. Match recognised words on `norm_skeleton` (+ `phonetic`).

## Normalization — `norm_skeleton` (norm-skeleton-v1)
Applied character by character to `word.text_uthmani` (the same function is used on Indo-Pak
text for verification):
* **N1** drop all combining marks (Unicode Mn/Me: harakat, tanween, shadda, sukun, maddah,
  hamza above/below, dagger alef U+0670, Quranic annotation & waqf marks U+06D6–U+06ED) and small
  waw/small yeh (U+06E5/U+06E6).
* **N2** ٱ أ إ آ ٲ ٳ and presentation-form alefs → ا (U+0627).
* **N3** ؤ → و, ئ → ي; standalone hamza ء (U+0621) and high hamza (U+0674) removed.
* **N4** ى, ی, ے, ۍ, ې, ٮ (U+066E, Indo-Pak) → ي (U+064A).
* **N5** ة, ۃ, ہ, ۂ, ھ → ه (U+0647).
* **N6** ک, ڪ → ك (U+0643).
* **N7** drop tatweel, whitespace, format chars (RLM, ZWSP, BOM), private-use glyphs, ۞, ۩.
* **N8** all other letters kept in order.
Example: `ٱلرَّحْمَـٰنِ` → `الرحمن`; `يَـٰٓأَيُّهَا` → `يايها`; `شَىْءٍ` → `شي`.
Note it is rasm-based: Uthmani spellings such as `ٱلصَّلَوٰةَ` → `الصلوه`.

**Loose key (loose-key-v1)** — used only to *verify* cross-script alignment: `norm_skeleton`
with every ا, و, ي removed (**L1**), since Indo-Pak and Uthmani write long vowels/hamza seats
differently. Keys equal → `match`; difflib ratio ≥ 0.75 → `fuzzy`; else `mismatch`.

## Phonetic — Hafs G2P v0 (`hafs-g2p-v0`, **v0 – requires teacher review**)
Symbols (space-separated): consonants `' b t th j H kh d dh r z s sh S D T Z E gh f q k l m n h
w y` (`'` hamza, `H` ḥ, `S D T Z` emphatics, `E` ʿayn); vowels `a i u`, long `aa ii uu`;
gemination = consonant token doubled. Represents **connected (wasl) reading of the word**.
Rules applied to `text_uthmani` clusters (base letter + its marks):
* **P1** letters carrying U+06DF (small high rounded zero) or U+06E0 (upright rectangular zero) are silent.
* **P2** hamzat al-wasl ٱ: silent, except on word 1 of an ayah → `' a` before ل (article), else `' u` if the 3rd letter has damma, else `' i`.
* **P3** tatweel only carries marks; with hamza above/below it is a hamza; with dagger alef / small yeh it lengthens.
* **P4** bare alef lengthens a preceding fatha (`a`→`aa`); silent after fathatan (tanween alef) or otherwise.
* **P5** precomposed آ after a fatha = long `aa`; otherwise `' aa`.
* **P6** bare ى/و/ي as long-vowel letters: ى after `a`/`i` lengthens it; و after `u` → `uu`; ي after `i` → `ii`; any of them with dagger alef → `aa` (e.g. `ٱلصَّلَوٰةَ` → `S S a l aa t a`).
* **P7** an unmarked consonant before a letter with shadda is assimilated (lam shamsiyya, in-word idgham).
* **P8** noon with small high meem (iqlab) → `m`.
* **P9** shadda → doubled consonant, except on the first letter of a word (that shadda marks assimilation of the previous word's ending).
* **P10** the name Allah: لَّ + ه in a word whose skeleton contains `لله` → `l l aa h`.
* **P11** tanween → vowel + `n`; iqlab (small high meem after fathatan/dammatan, small low meem after kasratan, verified empirically to precede ب) → vowel + `m`.
* **P12** dagger alef → long `aa`.
* **P13** small waw / small yeh / small high yeh (ṣila) lengthen the preceding `u`/`i`.
* **P19** ḥurūf muqaṭṭaʿāt (a word with no harakat at all, e.g. `الٓمٓ`) → letter names from a fixed table (`' a l i f l aa m m ii m`).
* **P20** unknown base letters are skipped.

Known v0 limitations (teacher review needed): no cross-word assimilation/idgham/ikhfa
(open tanween is plain `n`), no ghunna, madd lengths, qalqala or tafkhim; pausal (waqf) forms
not produced (ta marbuta is `t`, final vowels kept); no helping vowel for sukun before wasl;
imala (`مَجْر۪ىٰهَا`, U+06EA) and tas-hil (`ءَا۬عْجَمِىٌّ`, U+06EC) not special-cased; ishmam/rawm ignored;
muqatta'at have no inter-letter idgham.
Examples: `بِسْمِ` → `b i s m i`; `ٱلْحَمْدُ` (ayah-initial) → `' a l H a m d u`;
`ٱلضَّآلِّينَ` → `D D aa l l ii n a`; `تَأْخُذُهُۥ` → `t a ' kh u dh u h uu`.

## Indo-Pak ↔ Uthmani word mapping
1. **Quran.com word `text_indopak`** shares the word record and key (`surah:ayah:word`) with
   `text_uthmani`, so it is mapped **by key**, then verified with the loose key
   (`indopak_check`). Result: 77,384 match, 7 fuzzy, 38 mismatch. Mismatches are real
   upstream misalignments (Indo-Pak word shifted by one slot) in 56:45, 59:4, 65:10, 74:46,
   93:7, 103:3, 107:3, 109:5, plus orthographic differences at 21:88:7 (`نُـۨجِى`) and 72:16:1
   (Uthmani `وَأَلَّوِ` vs Indo-Pak `وَّاَنْ لَّوِ`).
2. **`text_indopak_nastaleeq`** has no word keys, so the builder produces a mapping: tokenise
   (**T1** split on whitespace; **T2** tokens with an Arabic letter start a span; **T3** a
   private-use-only token not at ayah end is an *optional* span; **T4** other mark-only tokens,
   e.g. waqf signs and the ayah-end glyph, attach to the previous span), then a monotone DP
   alignment against the Uthmani word keys with moves 1:1, 1:2, 2:1 (+0.5) and 0:1 absorb of
   optional spans (0.3), cost 0 for equal loose keys else 1. Result: 77,400 match, 4
   `split_1to2` (2:181:3, 8:6:4, 13:37:8, 37:130:3 — Indo-Pak writes the word as two tokens),
   24 `pua_glyph` (word drawn as a font-specific private-use glyph, e.g. أُنثَىٰ, وَلْيَتَلَطَّفْ;
   position-aligned but letters unverifiable), 1 `mismatch` (72:16:1). 0 unaligned ayahs.
3. Tanzil vs Quran.com Uthmani: ayah skeletons identical for 6,236/6,236 ayahs (the Tanzil text is
   kept per ayah; it is not re-segmented into words).

## Validation results (2026-11-21, `data/build/validation_report.txt`)
```
[PASS] meta keys present; phonetic marked "requires teacher review"
[PASS] meta manifest_sha256.* match data/raw/*/MANIFEST.json
[PASS] raw file checksums match MANIFEST.json (all files)
[PASS] source_file table checksums match manifests - 120 rows
[PASS] 114 surahs / 6236 ayahs / word count == expected 77429
[PASS] ayah count per surah: DB ayah rows == surah.ayah_count == Tanzil metadata == Quran.com
[PASS] word.id contiguous 0..N-1; ayah.global_index contiguous 0..6235
[PASS] word_index contiguous 1..word_count per ayah; first_word_id/ayah_global_index consistent
[PASS] no empty word.text_uthmani/text_indopak/norm_skeleton/phonetic; no empty ayah texts
[PASS] word & ayah Quran.com texts byte-identical to raw JSON
[PASS] ayah.text_uthmani_tanzil byte-identical to Tanzil XML
[PASS] every word.text_indopak_nastaleeq is an exact substring of its ayah text
[PASS] Quran.com Uthmani vs Tanzil Uthmani: ayah norm_skeleton identical - 0 ayahs differ
[PASS] norm_skeleton / phonetic / stop_marks reproduce from text_uthmani
[PASS] phonetic tokens within documented symbol set
  word.indopak_check: {"fuzzy": 7, "match": 77384, "mismatch": 38}  verified=99.942%
  word.nastaleeq_map: {"match": 77400, "mismatch": 1, "pua_glyph": 24, "split_1to2": 4}  verified=99.968% (99.999% incl. pua_glyph)
  word.text_indopak_nastaleeq non-null: 77429/77429
[PASS] every non-verified mapping is listed in word_map_issue - 70 issues
[PASS] Indo-Pak (Quran.com word) verified coverage >= 99.9%
RESULT: ALL CHECKS PASSED
```
