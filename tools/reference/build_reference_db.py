#!/usr/bin/env python3
"""Build data/build/quran_ref_v1.db from the verified raw sources in data/raw/.

Python 3 stdlib + sqlite3 only. Run from anywhere:

    python3 -I tools/reference/build_reference_db.py [--include-unverified-segments]

Principles (see docs/REFERENCE_DB_SCHEMA.md):
  * Quran text columns (text_*) are copied byte-for-byte from the downloaded
    sources (or are exact substrings of a downloaded ayah string, located by a
    documented alignment). Nothing is typed, re-typed or corrected here.
  * Derived columns (norm_skeleton, phonetic, keys, statuses) are produced by the
    deterministic, documented code below and stored separately.
  * Raw file checksums are verified against data/raw/*/MANIFEST.json before use.
"""
import argparse
import datetime as _dt
import difflib
import gzip
import hashlib
import json
import os
import sqlite3
import sys
import unicodedata
import xml.etree.ElementTree as ET

REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
RAW = os.path.join(REPO, "data", "raw")
BUILD = os.path.join(REPO, "data", "build")
DB_NAME = "quran_ref_v1.db"

SCHEMA_VERSION = "1"
NORM_VERSION = "norm-skeleton-v1"
LOOSE_VERSION = "loose-key-v1"
G2P_VERSION = "hafs-g2p-v0"
G2P_STATUS = "v0 - requires teacher review"
EXPECTED_WORDS = 77429  # quran.com v4 word records with char_type_name == "word"

# --------------------------------------------------------------------------------------
# Raw source access with checksum verification
# --------------------------------------------------------------------------------------


def sha256_bytes(b):
    return hashlib.sha256(b).hexdigest()


class RawSource:
    def __init__(self, name):
        self.name = name
        self.dir = os.path.join(RAW, name)
        with open(os.path.join(self.dir, "MANIFEST.json"), "rb") as f:
            self.manifest_bytes = f.read()
        self.manifest = json.loads(self.manifest_bytes)
        self.files = {e["path"]: e for e in self.manifest["files"]}

    def read(self, relpath):
        e = self.files[relpath]
        with open(os.path.join(self.dir, relpath), "rb") as f:
            stored = f.read()
        if "stored_sha256" in e:
            if sha256_bytes(stored) != e["stored_sha256"]:
                raise SystemExit(f"CHECKSUM MISMATCH (stored) {self.name}/{relpath}")
            body = gzip.decompress(stored)
        else:
            body = stored
        if sha256_bytes(body) != e["sha256"]:
            raise SystemExit(f"CHECKSUM MISMATCH {self.name}/{relpath}")
        return body

    def json(self, relpath):
        return json.loads(self.read(relpath).decode("utf-8"))


# --------------------------------------------------------------------------------------
# Character classes (Unicode code points; see docs/REFERENCE_DB_SCHEMA.md section "Normalization")
# --------------------------------------------------------------------------------------
FATHATAN, DAMMATAN, KASRATAN = "\u064B", "\u064C", "\u064D"
FATHA, DAMMA, KASRA = "\u064E", "\u064F", "\u0650"
SHADDA, SUKUN = "\u0651", "\u0652"
MADDAH, HAMZA_ABOVE, HAMZA_BELOW = "\u0653", "\u0654", "\u0655"
DAGGER_ALEF = "\u0670"
TATWEEL = "\u0640"
ALEF_WASLA = "\u0671"
SMALL_ROUNDED_ZERO = "\u06DF"     # letter always silent
SMALL_RECT_ZERO = "\u06E0"        # letter silent in connected reading
SMALL_HIGH_MEEM = "\u06E2"        # iqlab
SMALL_LOW_MEEM = "\u06ED"         # tanween variant marker (Tanzil-style encoding)
SMALL_WAW = "\u06E5"
SMALL_YEH = "\u06E6"
SMALL_HIGH_YEH = "\u06E7"
INDOPAK_SUKUN = "\u06E1"          # 'small high dotless head of khah' used as sukun in Indo-Pak
RUB_EL_HIZB = "\u06DE"
SAJDAH = "\u06E9"
STOP_MARKS = set(chr(c) for c in range(0x06D6, 0x06DD))  # U+06D6..U+06DC waqf / pause marks

HARAKAT = {FATHATAN, DAMMATAN, KASRATAN, FATHA, DAMMA, KASRA, SHADDA, SUKUN}


def is_mark(ch):
    """Combining / annotation characters that attach to the preceding base letter."""
    cat = unicodedata.category(ch)
    return cat in ("Mn", "Me") or ch in (SMALL_WAW, SMALL_YEH)


def is_ignorable(ch):
    """Format/space/private-use characters that carry no letter identity."""
    cat = unicodedata.category(ch)
    return cat in ("Zs", "Cf", "Co", "Cc") or ch.isspace()


# --------------------------------------------------------------------------------------
# norm_skeleton (rules N1..N8)
# --------------------------------------------------------------------------------------
_SKELETON_MAP = {
    # N2 alef variants -> bare alef
    "\u0671": "\u0627", "\u0623": "\u0627", "\u0625": "\u0627", "\u0622": "\u0627", "\u0672": "\u0627",
    "\u0673": "\u0627", "\uFE8E": "\u0627", "\uFE8D": "\u0627",
    # N3 hamza on carriers -> carrier; standalone hamza removed
    "\u0624": "\u0648", "\u0626": "\u064A", "\u0621": "", "\u0674": "",
    # N4 alef maksura / Farsi yeh / other yeh shapes -> yeh
    "\u0649": "\u064A", "\u06CC": "\u064A", "\u06D2": "\u064A", "\u06CD": "\u064A", "\u06D0": "\u064A",
    "\u066E": "\u064A",  # dotless beh used for yeh/alef-maksura shape in Indo-Pak
    # N5 ta marbuta -> heh; heh variants -> heh
    "\u0629": "\u0647", "\u06C3": "\u0647", "\u06C1": "\u0647", "\u06C2": "\u0647", "\u06BE": "\u0647",
    # N6 kaf variants -> kaf
    "\u06A9": "\u0643", "\u06AA": "\u0643",
}


def norm_skeleton(text):
    """Deterministic rasm skeleton used for word-level matching.

    N1 drop every combining mark (category Mn/Me: harakat, tanween, shadda, sukun, maddah,
       hamza above/below, dagger alef, Quranic annotation and waqf marks) and small waw /
       small yeh (U+06E5/U+06E6).
    N2 alef wasla / hamza-alef / madda-alef -> bare alef (U+0627).
    N3 waw-hamza -> waw, yeh-hamza -> yeh, standalone hamza (U+0621, U+0674) removed.
    N4 alef maksura and other yeh shapes -> yeh (U+064A).
    N5 ta marbuta and heh variants -> heh (U+0647).
    N6 keheh / swash kaf -> kaf (U+0643).
    N7 drop tatweel, whitespace, format chars (RLM, ZWSP, BOM), private-use glyphs,
       and the symbols rub-el-hizb (U+06DE) and place-of-sajdah (U+06E9).
    N8 everything else (Arabic letters) kept as-is, in source order.
    """
    out = []
    for ch in text:
        if is_mark(ch) or is_ignorable(ch) or ch in (TATWEEL, RUB_EL_HIZB, SAJDAH):
            continue
        out.append(_SKELETON_MAP.get(ch, ch))
    return "".join(out)


def loose_key(text):
    """Looser key used only to *verify* cross-script word alignment (Indo-Pak vs Uthmani):
    norm_skeleton with all alef / waw / yeh letters removed (L1), because the two scripts
    write long vowels and hamza seats differently."""
    return "".join(c for c in norm_skeleton(text) if c not in "\u0627\u0648\u064A")


def compare_keys(a, b):
    if a == b:
        return "match"
    r = difflib.SequenceMatcher(None, a, b, autojunk=False).ratio()
    return "fuzzy" if r >= 0.75 else "mismatch"


def stop_marks(text):
    return "".join(ch for ch in text if ch in STOP_MARKS)


# --------------------------------------------------------------------------------------
# Rule-based Hafs grapheme-to-phoneme v0 (rules P1..P20, see docs)
# --------------------------------------------------------------------------------------
CONS = {
    "\u0628": "b", "\u062A": "t", "\u062B": "th", "\u062C": "j", "\u062D": "H", "\u062E": "kh",
    "\u062F": "d", "\u0630": "dh", "\u0631": "r", "\u0632": "z", "\u0633": "s", "\u0634": "sh",
    "\u0635": "S", "\u0636": "D", "\u0637": "T", "\u0638": "Z", "\u0639": "E", "\u063A": "gh",
    "\u0641": "f", "\u0642": "q", "\u0643": "k", "\u0644": "l", "\u0645": "m", "\u0646": "n",
    "\u0647": "h", "\u0648": "w", "\u064A": "y", "\u0629": "t", "\u0649": "y",
    "\u0621": "'", "\u0623": "'", "\u0625": "'", "\u0624": "'", "\u0626": "'",
}
LETTER_NAMES = {  # P19 huruf muqatta'at letter names
    "\u0627": "' a l i f", "\u0644": "l aa m", "\u0645": "m ii m", "\u0635": "S aa d",
    "\u0631": "r aa", "\u0643": "k aa f", "\u0647": "h aa", "\u064A": "y aa", "\u0649": "y aa",
    "\u0639": "E a y n", "\u0637": "T aa", "\u0633": "s ii n", "\u062D": "H aa",
    "\u0642": "q aa f", "\u0646": "n uu n",
}
VOWEL_OF = {FATHA: "a", KASRA: "i", DAMMA: "u"}
TANWEEN_OF = {FATHATAN: "a", KASRATAN: "i", DAMMATAN: "u"}


def clusters(text):
    """Split into (base, marks) clusters; whitespace/format/private-use chars are dropped."""
    out = []
    for ch in text:
        if is_mark(ch):
            if out:
                out[-1][1].append(ch)
            continue
        if is_ignorable(ch) or ch in (RUB_EL_HIZB, SAJDAH):
            continue
        out.append((ch, []))
    return out


def g2p(text, ayah_initial):
    cl = clusters(text)
    allm = [m for _, ms in cl for m in ms]
    # P19 huruf muqatta'at: a word with no harakat/sukun/shadda/tanween at all
    if cl and not any(m in HARAKAT for m in allm) and all(b in LETTER_NAMES for b, _ in cl):
        return " ".join(LETTER_NAMES[b] for b, _ in cl)
    toks = []
    prev_tanween_a = False

    def lengthen(v):
        if toks and toks[-1] == v:
            toks[-1] = v + v
        elif toks and toks[-1] == v + v:
            pass
        else:
            toks.append(v + v)

    skel = norm_skeleton(text)
    for i, (b, ms) in enumerate(cl):
        m = set(ms)
        nxt = cl[i + 1] if i + 1 < len(cl) else None
        vowel = next((VOWEL_OF[x] for x in ms if x in VOWEL_OF), None)
        tanween = next((TANWEEN_OF[x] for x in ms if x in TANWEEN_OF), None)
        shadda = SHADDA in m
        sukun = SUKUN in m
        dagger = DAGGER_ALEF in m
        bare = not (vowel or tanween or shadda or sukun)
        was_tanween_a = prev_tanween_a
        prev_tanween_a = tanween == "a"
        # P1 letters marked with small rounded / rectangular zero are silent
        if SMALL_ROUNDED_ZERO in m or SMALL_RECT_ZERO in m:
            continue
        # P2 hamzat al-wasl
        if b == ALEF_WASLA:
            if i == 0 and ayah_initial:
                if nxt and nxt[0] == "\u0644":
                    toks += ["'", "a"]
                else:
                    third = cl[i + 2] if i + 2 < len(cl) else None
                    toks += ["'", "u" if third and DAMMA in third[1] else "i"]
            continue  # silent in connected speech
        # P3 tatweel carries marks only
        if b == TATWEEL:
            if HAMZA_ABOVE in m or HAMZA_BELOW in m:
                b = "\u0621"
            else:
                if dagger:
                    lengthen("a")
                if SMALL_HIGH_YEH in m or SMALL_YEH in m:
                    lengthen("i")
                continue
        # P4 bare alef: lengthens a preceding fatha; silent after fathatan or otherwise
        if b in ("\u0627", "\uFE8E") and not (HAMZA_ABOVE in m or HAMZA_BELOW in m):
            if not (vowel or tanween or shadda):
                if not was_tanween_a and toks and toks[-1] == "a":
                    lengthen("a")
                continue
        if b in ("\u0627", "\uFE8E"):
            b = "\u0621"
        # P5 precomposed alef-with-madda: after a fatha it is a madd (long) alef, like
        #    ALEF+MADDAH elsewhere in the text; otherwise hamza + long a
        if b == "\u0622":
            if toks and toks[-1] == "a":
                lengthen("a")
            else:
                toks += ["'", "aa"]
            continue
        # P6 alef maksura / waw / yeh as long-vowel letters
        if b in ("\u0649", "\u0648", "\u064A") and bare and HAMZA_ABOVE not in m:
            if dagger:
                lengthen("a")
                continue
            if b == "\u0649":
                if toks and toks[-1] in ("a", "i"):
                    lengthen(toks[-1])
                continue
            if b == "\u0648" and toks and toks[-1] == "u":
                lengthen("u")
                continue
            if b == "\u064A" and toks and toks[-1] == "i":
                lengthen("i")
                continue
        if b in ("\u0648", "\u064A", "\u0649") and HAMZA_ABOVE in m:
            b = "\u0621"
        c = CONS.get(b)
        if c is None:
            continue  # P20 unknown base letters are skipped (none expected in Uthmani words)
        # P7 assimilation: an unmarked consonant before a shadda letter is not pronounced
        #    (lam shamsiyya, in-word idgham)
        if bare and nxt and SHADDA in nxt[1]:
            continue
        # P8 iqlab on noon: small high meem
        if b == "\u0646" and SMALL_HIGH_MEEM in m:
            c = "m"
        toks.append(c)
        if shadda and i > 0:
            toks.append(c)  # P9 gemination (a word-initial shadda marks assimilation of the
            #                 previous word's ending; the geminate is not repeated inside the word)
        if vowel:
            # P10 the name Allah: lam+shadda+fatha before heh in a word containing l-l-h
            if (b == "\u0644" and shadda and vowel == "a" and nxt and nxt[0] == "\u0647"
                    and "\u0644\u0644\u0647" in skel):
                toks.append("aa")
            else:
                toks.append(vowel)
        if tanween:
            # P11 tanween = vowel + n. Iqlab -> vowel + m. In this (Tanzil-style) encoding the
            #     iqlab meem sits on the tanween's side: SMALL_HIGH_MEEM after fathatan/dammatan,
            #     SMALL_LOW_MEEM after kasratan. The *other* meem marks open (idgham/ikhfa)
            #     tanween, which v0 renders as plain "n". (Verified empirically: every such
            #     iqlab-marked tanween is followed by ba within the ayah or ends the ayah.)
            toks.append(tanween)
            iqlab = (SMALL_LOW_MEEM in m) if tanween == "i" else (SMALL_HIGH_MEEM in m)
            toks.append("m" if iqlab else "n")
        if dagger and not tanween:
            lengthen("a")  # P12 dagger alef = long a
        if SMALL_WAW in m and toks and toks[-1] == "u":
            lengthen("u")  # P13 silah: small waw
        if (SMALL_YEH in m or SMALL_HIGH_YEH in m) and toks and toks[-1] == "i":
            lengthen("i")  # P13 silah: small yeh
    return " ".join(toks)


# --------------------------------------------------------------------------------------
# Nastaleeq ayah tokenisation + monotone alignment to word keys
# --------------------------------------------------------------------------------------


def has_letter(tok):
    return any(unicodedata.category(c) == "Lo" for c in tok)


def is_pua(ch):
    return unicodedata.category(ch) == "Co"


def nastaleeq_spans(ayah_text):
    """Return [(start, end, optional)] spans into ayah_text.

    T1 whitespace (str.isspace) separates raw tokens.
    T2 a raw token containing an Arabic letter (category Lo) starts a new span.
    T3 a raw token made only of private-use glyphs (+marks) that is NOT the last token of the
       ayah becomes an *optional* span: the font draws some words/ligatures as PUA glyphs, but
       the same code points can also be waqf glyphs, so the aligner decides (see align()).
    T4 any other raw token (stop marks, ayah-end glyph) is attached to the preceding span
       (or to the following one if it is at the start of the ayah)."""
    raw = []
    i, n = 0, len(ayah_text)
    while i < n:
        if ayah_text[i].isspace():
            i += 1
            continue
        j = i
        while j < n and not ayah_text[j].isspace():
            j += 1
        raw.append((i, j))
        i = j
    spans = []
    pending_start = None
    for idx, (s, e) in enumerate(raw):
        tok = ayah_text[s:e]
        if has_letter(tok):
            spans.append([pending_start if pending_start is not None else s, e, False])
            pending_start = None
        elif any(is_pua(c) for c in tok) and idx != len(raw) - 1:
            spans.append([pending_start if pending_start is not None else s, e, True])
            pending_start = None
        elif spans:
            spans[-1][1] = e
        else:
            pending_start = s
    return [tuple(x) for x in spans]


def align(ukeys, nkeys, optional):
    """Monotone DP alignment of Uthmani word keys to Nastaleeq span keys.
    Moves: 1:1, 1:2 (one Uthmani word = two spans), 2:1 (two Uthmani words = one span),
    0:1 (absorb an *optional* PUA-only span into the previous word's span).
    Cost: 0 for an exact loose-key match, 1 otherwise; +0.5 for 1:2 / 2:1; 0.3 for 0:1.
    Returns list of (u_indices, n_indices); absorbed spans appear with u_indices == []."""
    U, N = len(ukeys), len(nkeys)
    INF = float("inf")
    cost = [[INF] * (N + 1) for _ in range(U + 1)]
    back = [[None] * (N + 1) for _ in range(U + 1)]
    cost[0][0] = 0.0
    for u in range(U + 1):
        for k in range(N + 1):
            c0 = cost[u][k]
            if c0 == INF:
                continue
            for du, dn, pen in ((1, 1, 0.0), (1, 2, 0.5), (2, 1, 0.5), (0, 1, 0.3)):
                if u + du > U or k + dn > N:
                    continue
                if du == 0:
                    if not (optional[k] and u > 0):
                        continue
                    c = c0 + pen
                else:
                    a = "".join(ukeys[u:u + du])
                    b = "".join(nkeys[k:k + dn])
                    c = c0 + pen + (0.0 if a == b else 1.0)
                if c < cost[u + du][k + dn]:
                    cost[u + du][k + dn] = c
                    back[u + du][k + dn] = (du, dn)
    if cost[U][N] == INF:
        return None
    path = []
    u, k = U, N
    while u or k:
        du, dn = back[u][k]
        path.append((list(range(u - du, u)), list(range(k - dn, k))))
        u, k = u - du, k - dn
    path.reverse()
    merged = []
    for us, ns in path:  # fold absorbed optional spans into the preceding group
        if not us:
            merged[-1][1].extend(ns)
        else:
            merged.append((us, list(ns)))
    return merged


# --------------------------------------------------------------------------------------
# Build
# --------------------------------------------------------------------------------------
SCHEMA_SQL = """
CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE source_file (
  source TEXT NOT NULL, path TEXT NOT NULL, url TEXT NOT NULL, retrieved_at TEXT NOT NULL,
  sha256 TEXT NOT NULL, license TEXT NOT NULL, PRIMARY KEY (source, path));
CREATE TABLE surah (
  id INTEGER PRIMARY KEY, name_arabic TEXT NOT NULL, name_transliterated TEXT NOT NULL,
  name_english TEXT NOT NULL, ayah_count INTEGER NOT NULL, revelation_place TEXT,
  revelation_order INTEGER, bismillah_pre INTEGER NOT NULL,
  first_ayah_global_index INTEGER NOT NULL, first_word_id INTEGER NOT NULL);
CREATE TABLE ayah (
  surah INTEGER NOT NULL REFERENCES surah(id), ayah INTEGER NOT NULL,
  global_index INTEGER NOT NULL UNIQUE,
  text_indopak TEXT NOT NULL, text_indopak_nastaleeq TEXT NOT NULL,
  text_uthmani TEXT NOT NULL, text_uthmani_tanzil TEXT NOT NULL,
  word_count INTEGER NOT NULL, first_word_id INTEGER NOT NULL,
  juz INTEGER, hizb INTEGER, rub_el_hizb INTEGER, page_madani INTEGER,
  sajdah INTEGER NOT NULL, starts_rub_el_hizb INTEGER NOT NULL, stop_marks TEXT NOT NULL,
  tanzil_skeleton_match INTEGER NOT NULL,
  PRIMARY KEY (surah, ayah));
CREATE TABLE word (
  id INTEGER PRIMARY KEY, surah INTEGER NOT NULL, ayah INTEGER NOT NULL,
  word_index INTEGER NOT NULL, ayah_global_index INTEGER NOT NULL,
  text_uthmani TEXT NOT NULL, text_indopak TEXT NOT NULL, text_indopak_nastaleeq TEXT,
  norm_skeleton TEXT NOT NULL, phonetic TEXT NOT NULL, stop_marks TEXT NOT NULL,
  indopak_check TEXT NOT NULL, nastaleeq_map TEXT NOT NULL,
  UNIQUE (surah, ayah, word_index));
CREATE INDEX word_skeleton ON word(norm_skeleton);
CREATE TABLE word_map_issue (
  word_id INTEGER NOT NULL REFERENCES word(id), layer TEXT NOT NULL, status TEXT NOT NULL,
  uthmani_key TEXT NOT NULL, other_key TEXT NOT NULL, other_text TEXT,
  PRIMARY KEY (word_id, layer));
"""

SEGMENT_SQL = """
CREATE TABLE segment_unverified (
  recitation_id INTEGER NOT NULL, surah INTEGER NOT NULL, ayah INTEGER NOT NULL,
  word_index INTEGER NOT NULL, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL,
  audio_path TEXT NOT NULL);
"""


def build(include_segments=False, out_dir=BUILD):
    os.makedirs(out_dir, exist_ok=True)
    db_path = os.path.join(out_dir, DB_NAME)
    tmp = db_path + ".tmp"
    if os.path.exists(tmp):
        os.remove(tmp)

    qc = RawSource("quran_com_api_v4")
    tz = RawSource("tanzil")

    # ---- surah metadata
    chapters = qc.json("chapters.json")["chapters"]
    tz_meta = ET.fromstring(tz.read("quran-data.xml"))
    tz_suras = {int(s.get("index")): s for s in tz_meta.find("suras").findall("sura")}
    sajdas = {(int(s.get("sura")), int(s.get("aya"))) for s in tz_meta.find("sajdas").findall("sajda")}

    # ---- Tanzil Uthmani ayah text
    tz_text = {}
    for sura in ET.fromstring(tz.read("quran-uthmani.xml")).findall("sura"):
        for aya in sura.findall("aya"):
            tz_text[(int(sura.get("index")), int(aya.get("index")))] = aya.get("text")

    # ---- quran.com ayah-level nastaleeq
    nq = {v["verse_key"]: v["text_indopak_nastaleeq"]
          for v in qc.json("ayah_text/indopak_nastaleeq.json.gz")["verses"]}

    con = sqlite3.connect(tmp)
    con.executescript(SCHEMA_SQL)
    if include_segments:
        con.executescript(SEGMENT_SQL)

    stats = {"indopak_check": {}, "nastaleeq_map": {}, "tanzil_skeleton_mismatch_ayahs": [],
             "nastaleeq_unaligned_ayahs": []}
    report_rows = []
    word_id = 0
    ayah_gi = 0
    for ch in sorted(chapters, key=lambda c: c["id"]):
        s = ch["id"]
        tzs = tz_suras[s]
        con.execute("INSERT INTO surah VALUES (?,?,?,?,?,?,?,?,?,?)", (
            s, ch["name_arabic"], ch["name_simple"], ch["translated_name"]["name"], ch["verses_count"],
            ch.get("revelation_place"), ch.get("revelation_order"), 1 if ch.get("bismillah_pre") else 0,
            ayah_gi, word_id))
        verses = qc.json(f"words/{s:03d}.json.gz")["verses"]
        for v in verses:
            a = v["verse_number"]
            key = v["verse_key"]
            words = [w for w in v["words"] if w["char_type_name"] == "word"]
            first_word = word_id
            u_text = v["text_uthmani"]
            t_text = tz_text[(s, a)]
            sk_match = 1 if norm_skeleton(u_text) == norm_skeleton(t_text) else 0
            if not sk_match:
                stats["tanzil_skeleton_mismatch_ayahs"].append(key)
            nq_text = nq[key]
            spans = nastaleeq_spans(nq_text)
            ukeys = [loose_key(w["text_uthmani"]) for w in words]
            nkeys = [loose_key(nq_text[x:y]) for x, y, _ in spans]
            path = align(ukeys, nkeys, [o for _, _, o in spans])
            nq_for_word = {}
            if path is None:
                stats["nastaleeq_unaligned_ayahs"].append(key)
            else:
                for us, ns in path:
                    text = nq_text[spans[ns[0]][0]:spans[ns[-1]][1]]
                    a_key = "".join(ukeys[i] for i in us)
                    b_key = "".join(nkeys[k] for k in ns)
                    real = [k for k in ns if not spans[k][2] or nkeys[k]]
                    st = compare_keys(a_key, b_key)
                    if st != "match" and any(is_pua(c) for c in text):
                        st = "pua_glyph"  # letters drawn as font-specific PUA glyphs: unverifiable
                    if len(us) == 2:
                        nq_for_word[us[0]] = (text, "merged_2to1" if st == "match" else st, b_key)
                        nq_for_word[us[1]] = (None, "merged_into_prev", b_key)
                    elif len(ns) == 2 and len(real) == 2:
                        nq_for_word[us[0]] = (text, "split_1to2" if st == "match" else st, b_key)
                    else:
                        nq_for_word[us[0]] = (text, st, b_key)
            ayah_marks = ""
            for wi, w in enumerate(words):
                loc = w["location"].split(":")
                assert (int(loc[0]), int(loc[1]), int(loc[2])) == (s, a, w["position"]), w["location"]
                ut, ip = w["text_uthmani"], w["text_indopak"]
                uk, ik = loose_key(ut), loose_key(ip)
                ip_status = compare_keys(uk, ik)
                stats["indopak_check"][ip_status] = stats["indopak_check"].get(ip_status, 0) + 1
                nq_word, nq_status, nq_key = nq_for_word.get(wi, (None, "none", ""))
                stats["nastaleeq_map"][nq_status] = stats["nastaleeq_map"].get(nq_status, 0) + 1
                marks = stop_marks(ut)
                ayah_marks += marks
                con.execute("INSERT INTO word VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)", (
                    word_id, s, a, w["position"], ayah_gi, ut, ip, nq_word, norm_skeleton(ut),
                    g2p(ut, ayah_initial=(w["position"] == 1)), marks, ip_status, nq_status))
                if ip_status != "match":
                    con.execute("INSERT INTO word_map_issue VALUES (?,?,?,?,?,?)",
                                (word_id, "indopak_word", ip_status, uk, ik, ip))
                    report_rows.append((w["location"], "indopak_word", ip_status, ut, ip, uk, ik))
                if nq_status not in ("match", "merged_2to1", "split_1to2", "merged_into_prev"):
                    # pua_glyph / fuzzy / mismatch / none are reported
                    con.execute("INSERT INTO word_map_issue VALUES (?,?,?,?,?,?)",
                                (word_id, "nastaleeq_ayah_span", nq_status, uk, nq_key, nq_word))
                    report_rows.append((w["location"], "nastaleeq_ayah_span", nq_status, ut, nq_word or "",
                                        uk, nq_key))
                word_id += 1
            con.execute("INSERT INTO ayah VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", (
                s, a, ayah_gi, v["text_indopak"], nq_text, u_text, t_text, len(words), first_word,
                v.get("juz_number"), v.get("hizb_number"), v.get("rub_el_hizb_number"), v.get("page_number"),
                1 if (s, a) in sajdas else 0, 1 if RUB_EL_HIZB in u_text else 0, ayah_marks, sk_match))
            ayah_gi += 1
        if include_segments:
            seg = qc.json(f"segments_recitation7/{s:03d}.json.gz")
            for af in seg["audio_files"]:
                ss, aa = (int(x) for x in af["verse_key"].split(":"))
                for sg in af.get("segments") or []:
                    if len(sg) >= 4:
                        con.execute("INSERT INTO segment_unverified VALUES (7,?,?,?,?,?,?)",
                                    (ss, aa, int(sg[1]), int(sg[2]), int(sg[3]), af["url"]))

    # ---- provenance
    checksums = {}
    for src in (qc, tz):
        checksums[src.name] = sha256_bytes(src.manifest_bytes)
        for e in src.manifest["files"]:
            if "segments_recitation7" in e["path"] and not include_segments:
                continue
            con.execute("INSERT INTO source_file VALUES (?,?,?,?,?,?)",
                        (src.name, e["path"], e["url"], e["retrieved_at"], e["sha256"], e["license"]))
    build_date = os.environ.get("SOURCE_DATE_EPOCH")
    build_date = (_dt.datetime.fromtimestamp(int(build_date), _dt.timezone.utc) if build_date
                  else _dt.datetime.now(_dt.timezone.utc)).replace(microsecond=0).isoformat()
    data_version = "quran_ref_v1+" + sha256_bytes(
        (checksums["quran_com_api_v4"] + checksums["tanzil"]).encode())[:12]
    meta = {
        "schema_version": SCHEMA_VERSION,
        "data_version": data_version,
        "build_date": build_date,
        "builder": "tools/reference/build_reference_db.py",
        "word_count_expected": str(EXPECTED_WORDS),
        "word_id_base": "0",
        "ayah_global_index_base": "0",
        "norm_skeleton_version": NORM_VERSION,
        "loose_key_version": LOOSE_VERSION,
        "phonetic_version": G2P_VERSION,
        "phonetic_status": G2P_STATUS,
        "phonetic_symbols": "consonants: ' b t th j H kh d dh r z s sh S D T Z E gh f q k l m n h w y; "
                            "vowels: a i u aa ii uu; gemination = doubled consonant token; tokens space-separated",
        "manifest_sha256.quran_com_api_v4": checksums["quran_com_api_v4"],
        "manifest_sha256.tanzil": checksums["tanzil"],
        "source.word_text": "Quran.com API v4 verses/by_chapter words (text_uthmani, text_indopak) - license UNVERIFIED",
        "source.ayah_tanzil": "Tanzil Uthmani 1.1, CC BY 3.0, verbatim - https://tanzil.net",
        "attribution.tanzil": "Tanzil Quran Text (Uthmani, Version 1.1), Copyright (C) 2007-2026 Tanzil Project, "
                              "License: Creative Commons Attribution 3.0. Source: https://tanzil.net - "
                              "check updates at http://tanzil.net/updates/",
        "segments_included": "1" if include_segments else "0",
    }
    con.executemany("INSERT INTO meta VALUES (?,?)", sorted(meta.items()))
    con.commit()
    con.execute("VACUUM")
    con.close()
    os.replace(tmp, db_path)

    with open(os.path.join(out_dir, "mapping_report.tsv"), "w", encoding="utf-8") as f:
        f.write("location\tlayer\tstatus\ttext_uthmani\tother_text\tuthmani_loose_key\tother_loose_key\n")
        for r in report_rows:
            f.write("\t".join(x.replace("\t", " ").replace("\n", " ") for x in r) + "\n")
    stats["words"] = word_id
    stats["ayahs"] = ayah_gi
    stats["data_version"] = data_version
    with open(os.path.join(out_dir, "build_report.json"), "w", encoding="utf-8") as f:
        json.dump(stats, f, ensure_ascii=False, indent=2, sort_keys=True)
        f.write("\n")
    print(json.dumps({k: v for k, v in stats.items() if not isinstance(v, list)}, ensure_ascii=False))
    print("tanzil skeleton mismatches:", len(stats["tanzil_skeleton_mismatch_ayahs"]),
          "nastaleeq unaligned ayahs:", len(stats["nastaleeq_unaligned_ayahs"]))
    print("wrote", db_path, os.path.getsize(db_path), "bytes")
    return db_path


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--include-unverified-segments", action="store_true",
                    help="add table segment_unverified (license UNVERIFIED; never ship)")
    ap.add_argument("--out-dir", default=BUILD)
    a = ap.parse_args()
    build(a.include_unverified_segments, a.out_dir)


if __name__ == "__main__":
    sys.exit(main())
