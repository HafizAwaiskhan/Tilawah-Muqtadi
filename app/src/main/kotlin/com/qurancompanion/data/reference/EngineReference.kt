package com.qurancompanion.data.reference

import com.qurancompanion.engine.reference.InMemoryQuranReference
import com.qurancompanion.engine.reference.QuranReference
import com.qurancompanion.engine.reference.WordRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** One row of the `word` table, as needed by the engine (no display text). */
data class EngineWordRow(
    val id: Int,
    val surah: Int,
    val ayah: Int,
    val wordIndex: Int,
    val normSkeleton: String,
    val phonetic: String,
    val stopMarks: String,
)

/**
 * Builds the engine's [QuranReference] from `word` rows (docs/REFERENCE_DB_SCHEMA.md):
 * globalId = `word.id` (must be 0..n-1 in mushaf order), matchKey = `norm_skeleton`,
 * phonetic = `phonetic` split on spaces, waqfAfter = `stop_marks` contains a pause sign.
 * Strings are interned (skeletons and phoneme symbols repeat heavily) to keep memory low.
 */
class EngineReferenceBuilder(expectedWords: Int = 0) {
    private val words = ArrayList<WordRef>(expectedWords)
    private val pool = HashMap<String, String>()
    private val phonePool = HashMap<String, List<String>>()

    private fun intern(s: String) = pool.getOrPut(s) { s }

    fun add(row: EngineWordRow) {
        require(row.id == words.size) {
            "word.id must be contiguous from 0 in mushaf order (expected ${words.size}, got ${row.id})"
        }
        val phonetic = if (row.phonetic.isBlank()) emptyList() else phonePool.getOrPut(row.phonetic) {
            row.phonetic.trim().split(' ').filter { it.isNotEmpty() }.map(::intern)
        }
        words += WordRef(
            globalId = row.id,
            surah = row.surah,
            ayah = row.ayah,
            wordIndex = row.wordIndex,
            matchKey = intern(row.normSkeleton),
            phonetic = phonetic,
            waqfAfter = isPauseSign(row.stopMarks),
        )
    }

    fun build(): QuranReference = InMemoryQuranReference(words)

    companion object {
        /** Column list / order used by every loader (Android SQLite and JDBC tests). */
        const val WORD_QUERY =
            "SELECT id, surah, ayah, word_index, norm_skeleton, phonetic, stop_marks FROM word ORDER BY id"

        /**
         * Waqf signs U+06D6..U+06DC mark a permitted/recommended pause after the word, except
         * U+06D9 (small high lam-alef), which means "do not stop here".
         */
        fun isPauseSign(stopMarks: String?): Boolean =
            stopMarks != null && stopMarks.any { it in 'ۖ'..'ۜ' && it != 'ۙ' }
    }
}

sealed interface EngineReferenceStatus {
    data object NotLoaded : EngineReferenceStatus
    data object Loading : EngineReferenceStatus
    data class Ready(val wordCount: Int, val loadMs: Long) : EngineReferenceStatus
    data class Unavailable(val message: String) : EngineReferenceStatus
}

/** Supplies the engine reference, loaded once and cached for the process lifetime. */
interface EngineReferenceSource {
    val status: StateFlow<EngineReferenceStatus>
    /** Loads (off the main thread) on first call; null when the reference DB is not available. */
    suspend fun get(): QuranReference?
}

@Singleton
class CachedEngineReference @Inject constructor(
    private val repository: QuranReferenceRepository,
) : EngineReferenceSource {
    private val mutex = Mutex()
    @Volatile private var cached: QuranReference? = null
    private val _status = MutableStateFlow<EngineReferenceStatus>(EngineReferenceStatus.NotLoaded)
    override val status: StateFlow<EngineReferenceStatus> = _status.asStateFlow()

    override suspend fun get(): QuranReference? {
        cached?.let { return it }
        return mutex.withLock {
            cached ?: withContext(Dispatchers.Default) { load() }.also { cached = it }
        }
    }

    private suspend fun load(): QuranReference? {
        _status.value = EngineReferenceStatus.Loading
        val t0 = System.nanoTime()
        return try {
            val builder = EngineReferenceBuilder(EXPECTED_WORDS)
            if (!repository.forEachEngineWord(builder::add)) {
                _status.value = EngineReferenceStatus.Unavailable("Quran reference data is not installed")
                return null
            }
            builder.build().also {
                _status.value = EngineReferenceStatus.Ready(it.wordCount, (System.nanoTime() - t0) / 1_000_000)
            }
        } catch (t: Throwable) {
            _status.value = EngineReferenceStatus.Unavailable(t.message ?: t.javaClass.simpleName)
            null
        }
    }

    companion object {
        const val EXPECTED_WORDS = 77_429
    }
}
