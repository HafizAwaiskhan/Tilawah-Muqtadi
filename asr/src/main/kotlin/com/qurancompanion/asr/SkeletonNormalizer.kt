package com.qurancompanion.asr

/**
 * Maps recognizer words into the reference DB's `word.norm_skeleton` space (norm-skeleton-v1,
 * docs/REFERENCE_DB_SCHEMA.md), which is what the engine compares tokens with (`WordRef.matchKey`).
 * Rules are documented in docs/ASR.md ("Matching-key normalization"). Only code points appear here.
 *
 *  A1 fold letter variants exactly as norm-skeleton N2-N6 (no-ops for this model's 29 letters, kept
 *     so other recognizers / spelling tables land in the same space).
 *  A2 drop standalone hamza U+0621 and high hamza U+0674 (N3). The model emits U+0621 as a letter
 *     (vocab id 1); norm_skeleton never contains it. This is the only rule that fires on real output.
 *  A3 drop everything outside the target alphabet (marks, tatweel, digits, separators, unknowns).
 *  A4 a word reduced to a lone alef/yeh is re-attached to the previous word (the model's training
 *     text rule, `asr_core.normalize`; the DB has no 1-letter alef/yeh words). Its end time is merged.
 *  A5 words that become empty are dropped.
 */
class SkeletonNormalizer(val alphabet: Set<Char> = DEFAULT_ALPHABET) {

    fun normalizeWord(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            val f = FOLD[c] ?: c
            if (f == HAMZA || f == HIGH_HAMZA) continue
            if (f in alphabet) sb.append(f)
        }
        return sb.toString()
    }

    /** Normalizes a decoded word sequence (applies A4/A5 across neighbours). */
    fun normalize(words: List<DecodedWord>): List<DecodedWord> {
        val out = ArrayList<DecodedWord>(words.size)
        for (w in words) {
            val t = normalizeWord(w.text)
            if (t.isEmpty()) continue
            if ((t == ALEF_S || t == YEH_S) && out.isNotEmpty()) {
                val p = out.removeAt(out.size - 1)
                out += p.copy(text = p.text + t, endFrame = maxOf(p.endFrame, w.endFrame), confidence = minOf(p.confidence, w.confidence))
            } else {
                out += w.copy(text = t)
            }
        }
        return out
    }

    companion object {
        const val HAMZA = '\u0621'
        const val HIGH_HAMZA = '\u0674'
        const val ALEF = '\u0627'
        const val YEH = '\u064A'
        private val ALEF_S = ALEF.toString()
        private val YEH_S = YEH.toString()

        /** The 28 letters of norm-skeleton-v1: U+0627..U+063A without U+0629 (teh marbuta), U+0641..U+0648, U+064A. */
        val DEFAULT_ALPHABET: Set<Char> =
            ((0x0627..0x063A).filter { it != 0x0629 } + (0x0641..0x0648) + listOf(0x064A)).map { it.toChar() }.toSet()

        /** norm-skeleton N2-N6 folds. */
        val FOLD: Map<Char, Char> = buildMap {
            for (c in listOf(0x0671, 0x0623, 0x0625, 0x0622, 0x0672, 0x0673)) put(c.toChar(), ALEF)
            put('\u0624', '\u0648')                                   // waw with hamza -> waw
            for (c in listOf(0x0626, 0x0649, 0x06CC, 0x06D2, 0x06CD, 0x06D0, 0x066E)) put(c.toChar(), YEH)
            for (c in listOf(0x0629, 0x06C3, 0x06C1, 0x06C2, 0x06BE)) put(c.toChar(), '\u0647')
            for (c in listOf(0x06A9, 0x06AA)) put(c.toChar(), '\u0643')
        }
    }
}
