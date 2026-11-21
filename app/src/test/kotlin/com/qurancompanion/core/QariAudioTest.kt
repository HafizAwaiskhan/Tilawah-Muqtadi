package com.qurancompanion.core

import com.qurancompanion.core.model.HttpResponse
import com.qurancompanion.core.model.HttpSource
import com.qurancompanion.core.playback.QariAudioLookup
import com.qurancompanion.core.playback.TestQariAudioResolver
import com.qurancompanion.core.qari.AlafasyTestQari
import com.qurancompanion.core.qari.QariDownloadManager
import com.qurancompanion.core.qari.QariTimingParser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

@OptIn(ExperimentalCoroutinesApi::class)
class QariAudioTest {
    @get:Rule val tmp = TemporaryFolder()

    private val mp3 = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0, 0, 0)

    private class FakeHttp(val respond: (String) -> HttpResponse) : HttpSource {
        val urls = mutableListOf<String>()
        override fun open(url: String, fromByte: Long): HttpResponse { urls += url; return respond(url) }
    }

    private fun TestScope.manager(http: HttpSource) =
        QariDownloadManager(tmp.root, http, StandardTestDispatcher(testScheduler), backgroundScope)

    @Test fun urlPatternAndSizes() {
        assertEquals("https://everyayah.com/data/Alafasy_128kbps/002255.mp3", AlafasyTestQari.url(2, 255))
        assertEquals(114, AlafasyTestQari.AYAH_COUNTS.size)
        assertEquals(6236, AlafasyTestQari.AYAH_COUNTS.sum())
        assertEquals(114, AlafasyTestQari.SURAH_BYTES.size)
        assertTrue(AlafasyTestQari.TOTAL_BYTES in 1_600_000_000L..1_800_000_000L)
    }

    @Test fun downloadsOneSurahPerAyahAndMarksItDone() = runTest {
        val http = FakeHttp { HttpResponse(200, ByteArrayInputStream(mp3)) }
        val m = manager(http)
        m.downloadSurah(1); runCurrent()
        assertEquals(setOf(1), m.state.value.downloaded)
        assertFalse(m.state.value.busy)
        assertEquals((1..7).map { AlafasyTestQari.url(1, it) }, http.urls)
        assertNotNull(m.ayahFile(1, 7))
        assertNull(m.ayahFile(2, 1))
        assertTrue(tmp.root.listFiles()!!.none { it.name.endsWith(".part") })
        // A new manager (process restart) sees the finished surah.
        assertEquals(setOf(1), manager(http).state.value.downloaded)
        m.deleteAll(); runCurrent()
        assertTrue(m.state.value.downloaded.isEmpty())
        assertNull(m.ayahFile(1, 1))
    }

    @Test fun failureKeepsFinishedAyahsAndContinuesLater() = runTest {
        var fail = true
        val http = FakeHttp { url ->
            if (fail && url.endsWith("001004.mp3")) HttpResponse(404, ByteArrayInputStream(ByteArray(0)))
            else HttpResponse(200, ByteArrayInputStream(mp3))
        }
        val m = manager(http)
        m.downloadSurah(1); runCurrent()
        assertTrue(m.state.value.downloaded.isEmpty())
        assertNotNull(m.state.value.error)
        assertNotNull(m.ayahFile(1, 3))
        assertNull(m.ayahFile(1, 4))
        fail = false
        http.urls.clear()
        m.downloadSurah(1); runCurrent()
        assertEquals(setOf(1), m.state.value.downloaded)
        assertEquals((4..7).map { AlafasyTestQari.url(1, it) }, http.urls) // only the missing ones
    }

    @Test fun nonMp3ResponseIsRejected() = runTest {
        val m = manager(FakeHttp { HttpResponse(200, ByteArrayInputStream("<html>".toByteArray())) })
        m.downloadSurah(1); runCurrent()
        assertNull(m.ayahFile(1, 1))
        assertNotNull(m.state.value.error)
    }

    @Test fun resolverPrefersFileThenStreamsOnlineElseOffline() = runTest {
        val m = manager(FakeHttp { HttpResponse(200, ByteArrayInputStream(mp3)) })
        m.downloadSurah(1); runCurrent()
        var online = true
        val timings = listOf(0L to 500L, 510L to 900L)
        val r = TestQariAudioResolver(m, { _, _ -> timings }, { online })

        val local = r.resolve(null, 1, 2) as QariAudioLookup.Available
        assertTrue(local.audio.uri.startsWith("file://") && local.audio.uri.endsWith("001002.mp3"))
        assertFalse(local.audio.streamed)
        assertEquals(timings, local.audio.wordTimings)

        val streamed = r.resolve(AlafasyTestQari.ID, 2, 255) as QariAudioLookup.Available
        assertEquals(AlafasyTestQari.url(2, 255), streamed.audio.uri)
        assertTrue(streamed.audio.streamed)

        online = false
        assertEquals(QariAudioLookup.Offline, r.resolve(null, 2, 255))
        assertTrue(r.resolve(null, 1, 1) is QariAudioLookup.Available) // downloaded: works offline
        assertEquals(QariAudioLookup.Unavailable, r.resolve("someone-else", 1, 1))
        assertEquals(QariAudioLookup.Unavailable, r.resolve(null, 1, 8))
    }

    @Test fun timingTableParsesWithGaps() {
        val table = QariTimingParser.parse(ByteArrayInputStream("1:1\t1:60:610,2:620:1310\n2:5\t1:0:100,3:200:300\nbad line\n".toByteArray()))
        assertEquals(listOf(60L to 610L, 620L to 1310L), table[QariTimingParser.key(1, 1)])
        assertEquals(listOf(0L to 100L, -1L to -1L, 200L to 300L), table[QariTimingParser.key(2, 5)])
        assertEquals(2, table.size)
    }
}
