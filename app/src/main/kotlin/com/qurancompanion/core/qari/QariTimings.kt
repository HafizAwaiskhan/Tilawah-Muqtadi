package com.qurancompanion.core.qari

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Word timings (ms) of the test Qari's ayah files, index = word − 1, (-1, -1) = no segment. */
fun interface QariTimingSource {
    suspend fun timings(surah: Int, ayah: Int): List<Pair<Long, Long>>
}

/**
 * Parses the timing table produced by the `buildQariTestTimings` Gradle task (debug builds only):
 * one line per ayah, `surah:ayah<TAB>pos:start:end,pos:start:end,…` (pos = 1-based word).
 */
object QariTimingParser {
    fun parse(input: InputStream): Map<Int, List<Pair<Long, Long>>> {
        val out = HashMap<Int, List<Pair<Long, Long>>>(6300)
        BufferedReader(input.reader()).useLines { lines ->
            for (line in lines) {
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val key = line.substring(0, tab).split(':')
                val s = key.getOrNull(0)?.toIntOrNull() ?: continue
                val a = key.getOrNull(1)?.toIntOrNull() ?: continue
                val segs = line.substring(tab + 1).split(',').mapNotNull { seg ->
                    val p = seg.split(':')
                    if (p.size != 3) null else Triple(p[0].toIntOrNull() ?: return@mapNotNull null, p[1].toLong(), p[2].toLong())
                }
                val n = segs.maxOfOrNull { it.first } ?: continue
                val list = MutableList(n) { -1L to -1L }
                for ((pos, st, en) in segs) if (pos >= 1) list[pos - 1] = st to en
                out[key(s, a)] = list
            }
        }
        return out
    }

    fun key(surah: Int, ayah: Int) = surah * 1000 + ayah
}

/** Loads the asset once on first use; an absent asset (release builds) means "no timings". */
@Singleton
class AssetQariTimingSource @Inject constructor(@ApplicationContext private val context: Context) : QariTimingSource {
    private val mutex = Mutex()
    private var table: Map<Int, List<Pair<Long, Long>>>? = null

    override suspend fun timings(surah: Int, ayah: Int): List<Pair<Long, Long>> {
        val t = mutex.withLock {
            table ?: withContext(Dispatchers.IO) {
                runCatching { context.assets.open(AlafasyTestQari.TIMINGS_ASSET).use(QariTimingParser::parse) }
                    .getOrDefault(emptyMap())
            }.also { table = it }
        }
        return t[QariTimingParser.key(surah, ayah)].orEmpty()
    }
}
