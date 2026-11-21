package com.qurancompanion.data.reference

/**
 * The single place that decides which stored text is shown for a word
 * (docs/REFERENCE_DB_SCHEMA.md, "Android usage hint").
 *
 * Current rule (no licensed Indo-Pak Nastaleeq font is bundled):
 *  - INDOPAK: `text_indopak` when `indopak_check = 'match'`, otherwise `text_uthmani` for that
 *    word (the 45 unverified words are shown in Uthmani rather than risk a shifted word).
 *  - UTHMANI: `text_uthmani`.
 *
 * TODO(font): once a licensed Nastaleeq font is bundled, prefer `text_indopak_nastaleeq` when
 * `nastaleeq_map IN ('match','split_1to2','pua_glyph')`.
 */
object WordDisplayRule {
    const val CHECK_MATCH = "match"

    fun select(script: Script, indopak: String?, indopakCheck: String?, uthmani: String?): String? {
        val u = uthmani?.takeIf { it.isNotBlank() }
        return when (script) {
            Script.UTHMANI -> u
            Script.INDOPAK ->
                if (indopakCheck == CHECK_MATCH && !indopak.isNullOrBlank()) indopak else u
        }
    }
}
