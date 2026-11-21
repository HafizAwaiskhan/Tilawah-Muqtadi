# Licence risk register — Quran reference data, fonts, audio

Checked **2026-11-21** by reading the live pages (snapshots + SHA-256 in
`data/raw/license_evidence/`). "UNVERIFIED" = no licence text could be found or the terms page
was unreachable from the build environment; it is **not** a finding that use is allowed.
Rule from PHASE0_PLAN §3 stands: no public/commercial release until every shipped row is Low
with written evidence in `docs/licenses/`.

## Risk table

| # | Item | Licence | Verbatim key terms | Risk | Required action |
|---|---|---|---|---|---|
| 1 | **Tanzil Uthmani text** (`ayah.text_uthmani_tanzil`) | Creative Commons Attribution 3.0 + Tanzil terms | "Permission is granted to copy and distribute verbatim copies of this text, but CHANGING IT IS NOT ALLOWED." / "This Quran text can be used in any website or application, provided that its source (Tanzil Project) is clearly indicated, and a link is made to tanzil.net to enable users to keep track of changes." / "This copyright notice shall be included in all verbatim copies of the text, and shall be reproduced appropriately in all files derived from or containing substantial portion of this text." (tanzil.net/docs/text_license; same block is in the XML header) | **Low** | Keep text verbatim (DB does). Show "Quran text: Tanzil Project — tanzil.net" in app About screen with link; ship the copyright notice (stored in `meta['attribution.tanzil']`). Derived `norm_skeleton`/`phonetic` are separate, never displayed as Quran text. |
| 2 | Tanzil metadata (`quran-data.xml`) | No separate licence stated; distributed by Tanzil docs | — | Low | Same attribution as row 1. |
| 3 | **Quran.com API v4 word text** (`word.text_uthmani`, `word.text_indopak`, `ayah.text_indopak*`, `surah` names) | **UNVERIFIED** | api.quran.com serves no licence/terms; `api-docs.quran.com` and `quran.foundation` were not reachable (proxy refused). Upstream text provenance (KFGQPC / Tanzil-style / Indo-Pak) not stated in responses. | **Medium** | Read Quran Foundation API terms (outside this sandbox) and record them; ask Quran Foundation for written permission to bundle the word-by-word text offline; or replace with QUL WBW files once their licence is known. Uthmani column is skeleton-identical to Tanzil (6,236/6,236 ayahs), so Tanzil can be the shipped Uthmani fallback. |
| 4 | **QUL scripts** (Indopak Nastaleeq WBW id 59, KFGQPC Hafs WBW 312, QPC Hafs 86, …) | **UNVERIFIED** | Resource licence tab: "We don't have copyright information for this resource." FAQ: "Yes, you can use QUL data in commercial projects. However, please review the licensing terms for each resource. Some data may have restrictions or require attribution, while others are freely available for commercial use." / "The resources available on QUL vary in their copyright status. Some are in the public domain, while others may be subject to specific licenses. We recommend reviewing the licensing information provided by each resource's author before use." Downloads require sign-in. | **Medium** | Not used. If adopted: sign in, download, and obtain written confirmation from Tarteel/QUL (GitHub issue) of terms for the Indo-Pak script; KFGQPC-derived scripts may carry KFGQPC terms. |
| 5 | `text_indopak_nastaleeq` (Quran.com ayah text in QUL/KFGQPC Nastaleeq encoding) | **UNVERIFIED** | as rows 3 + 4 | **Medium** | As row 3. |
| 6 | **Indo-Pak fonts** (QUL "Indopak Nastaleeq font" id 242, "KFGQPC Nastaleeq" 462, "Digital Khatt Indopak" 568) | **UNVERIFIED** | QUL licence tab for each: "We don't have copyright information for this resource." The Nastaleeq text uses private-use code points (ayah-number glyphs U+F500–U+F6FF, word ligatures) that render only with the matching font. | **High** (until a font licence is found) | Find the original font author/licence (KFGQPC terms for KFGQPC fonts; Digital Khatt project licence) and confirm embedding/redistribution in an APK. Do not bundle a font until then. |
| 7 | **QUL mushaf layouts** (Indopak 15/16/13/9-line) | **UNVERIFIED** | "We don't have copyright information for this resource." (all 11 checked) | Medium | Post-MVP; obtain terms first. |
| 8 | **Word timing segments**, recitation 7 (Alafasy) via Quran.com API | **UNVERIFIED** | No licence served. QUL page for the same reciter (id 118): "We don't have copyright information for this resource." | **High** | Raw JSON kept for internal R&D only; excluded from the DB unless `--include-unverified-segments` (table `segment_unverified`). Do not ship. Needs permission from Quran Foundation/Tarteel and the reciter's rights holder. |
| 9 | **Qari audio — everyayah.com** | **UNVERIFIED** | No licence or permission text on home page, old index, `recitations.js` or reciter directories. | **High** | Per-reciter written permission needed before bundling/streaming in a commercial app; prefer recordings with an explicit licence or commissioned recordings. |
| 10 | **Qari audio — Quran.com / quranicaudio.com** | **UNVERIFIED** | No licence served with `audio_url`s. | **High** | As row 9. |
| 11 | **Qari audio — QUL recitations** (131) | **UNVERIFIED** | Every recitation licence tab: "We don't have copyright information for this resource." | **High** | As row 9. |
| 12 | QUL source code (GitHub TarteelAI/quranic-universal-library) | MIT | "Permission is hereby granted, free of charge, to any person obtaining a copy of this software…" (Copyright (c) 2024-present Tarteel, Inc) | Low (code only) | Not used; note MIT covers the CMS code, **not** the data. |
| 13 | Derived columns (`norm_skeleton`, `phonetic`, statuses) | Our own deterministic code output | — | Low | Never presented as Quran text; `phonetic` "v0 – requires teacher review". |

## Summary verdict

* **Shippable today (Low):** Tanzil Uthmani text + metadata, with attribution.
* **Usable for development, not for release (Medium, UNVERIFIED):** all Quran.com API text,
  including the Indo-Pak word text the app needs for display.
* **Blocked for release (High):** Indo-Pak fonts, all qari audio, word timing segments.
