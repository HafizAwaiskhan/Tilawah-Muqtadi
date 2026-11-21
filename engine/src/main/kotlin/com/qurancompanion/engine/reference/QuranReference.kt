package com.qurancompanion.engine.reference

/**
 * One word of the reference text. The engine never looks at display text: it only uses
 * [matchKey] (normalized skeleton, compared with word tokens) and [phonetic] (phoneme tokens).
 *
 * [globalId] is the 0-based position of the word in the whole reference; loaders must map
 * database ids to this contiguous range.
 */
data class WordRef(
    val globalId: Int,
    val surah: Int,
    val ayah: Int,
    val wordIndex: Int,
    val matchKey: String,
    val phonetic: List<String> = emptyList(),
    /** Optional waqf (pause) hint after this word. */
    val waqfAfter: Boolean = false,
)

/** A position expressed as a word of the reference. */
data class WordPosition(val wordId: Int, val surah: Int, val ayah: Int, val wordIndex: Int)

fun WordRef.toPosition() = WordPosition(globalId, surah, ayah, wordIndex)

/** Read-only access to the reference text, independent of where it comes from (SQLite, memory, ...). */
interface QuranReference {
    val wordCount: Int
    fun word(globalId: Int): WordRef

    /** Global ids of the words of an ayah, or null when the ayah does not exist. */
    fun ayahWords(surah: Int, ayah: Int): IntRange?

    fun isAyahEnd(globalId: Int): Boolean {
        if (globalId >= wordCount - 1) return true
        val w = word(globalId)
        val n = word(globalId + 1)
        return n.ayah != w.ayah || n.surah != w.surah
    }

    fun hasWaqfAfter(globalId: Int): Boolean = word(globalId).waqfAfter

    /** True when a natural pause is expected after [globalId] (end of ayah or waqf hint). */
    fun isPausePoint(globalId: Int): Boolean = isAyahEnd(globalId) || hasWaqfAfter(globalId)

    fun position(globalId: Int): WordPosition = word(globalId).toPosition()
}

class InMemoryQuranReference(words: List<WordRef>) : QuranReference {
    private val words: List<WordRef> = words.toList()
    private val ayahs = HashMap<Long, IntRange>()

    init {
        require(this.words.isNotEmpty()) { "empty reference" }
        var start = 0
        this.words.forEachIndexed { i, w ->
            require(w.globalId == i) { "globalId must be the 0-based index (word $i has ${w.globalId})" }
            require(w.matchKey.isNotEmpty()) { "word $i has an empty matchKey" }
            if (i > 0) {
                val p = this.words[i - 1]
                if (p.surah != w.surah || p.ayah != w.ayah) {
                    ayahs[key(p.surah, p.ayah)] = start until i
                    start = i
                }
            }
        }
        val last = this.words.last()
        ayahs[key(last.surah, last.ayah)] = start until this.words.size
    }

    override val wordCount: Int get() = words.size
    override fun word(globalId: Int): WordRef = words[globalId]
    override fun ayahWords(surah: Int, ayah: Int): IntRange? = ayahs[key(surah, ayah)]

    private fun key(surah: Int, ayah: Int) = surah.toLong() * 100_000L + ayah
}
