package com.qurancompanion.engine.tracking

import com.qurancompanion.engine.recognition.TokenUnit
import com.qurancompanion.engine.reference.QuranReference

/**
 * The reference flattened into the recognizer's unit: one unit per word (matchKey) for WORD, or the
 * concatenated phonemes for PHONEME, with the mapping between units and words.
 */
class UnitSequence(val reference: QuranReference, val unit: TokenUnit) {
    val units: List<String>
    private val wordOfUnit: IntArray
    private val firstUnit: IntArray

    init {
        val u = ArrayList<String>()
        val wo = ArrayList<Int>()
        firstUnit = IntArray(reference.wordCount)
        for (w in 0 until reference.wordCount) {
            val word = reference.word(w)
            firstUnit[w] = u.size
            val parts = if (unit == TokenUnit.WORD) listOf(word.matchKey) else word.phonetic
            require(parts.isNotEmpty()) { "word $w has no ${unit.name.lowercase()} units" }
            parts.forEach { u += it; wo += w }
        }
        units = u
        wordOfUnit = wo.toIntArray()
    }

    val size: Int get() = units.size
    fun wordOf(unitIndex: Int): Int = wordOfUnit[unitIndex.coerceIn(0, units.size - 1)]
    fun firstUnitOf(word: Int): Int = firstUnit[word]
    fun lastUnitOf(word: Int): Int = if (word + 1 < firstUnit.size) firstUnit[word + 1] - 1 else units.size - 1
    fun unitsOf(word: Int): List<String> = units.subList(firstUnitOf(word), lastUnitOf(word) + 1)
}
