package com.qurancompanion.asr

import com.qurancompanion.engine.recognition.RecognitionEvent
import com.qurancompanion.engine.recognition.RecognizerStatus
import com.qurancompanion.engine.recognition.TokenUnit
import com.qurancompanion.engine.session.Clock
import com.qurancompanion.engine.session.PracticeMode
import com.qurancompanion.engine.session.RecitationSessionController
import com.qurancompanion.engine.session.SessionInput
import com.qurancompanion.engine.session.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 6: real clip -> 100 ms PCM16 chunks -> AbdouRecognizer -> RecitationSessionController with the
 * full DB reference (77,429 words). Skips without the model / test inputs / DB.
 */
class EndToEndTest {

    private class Run(val clip: TestData.Clip, val stable: List<String>, val lagsMs: List<Long>, val stats: RecognizerStats,
                      val located: Boolean, val surah: Int?, val ayah: Int?, val wordIndex: Int?, val feedback: Int,
                      val firstLocatedAudioMs: Long?)

    private fun runClip(c: TestData.Clip, cfg: AbdouConfig = AbdouConfig()): Run {
        var now = 0L
        val ctl = RecitationSessionController(DbReference.reference, TokenUnit.WORD, Clock { now }, newSessionId = { "e2e" })
        ctl.handle(SessionInput.Initialize); ctl.handle(SessionInput.PermissionsResult(true)); ctl.handle(SessionInput.ModelReady)
        ctl.handle(SessionInput.ReferenceReady); ctl.handle(SessionInput.StartSession(PracticeMode.RECITATION))
        ctl.handle(SessionInput.RecognizerStatusChanged(RecognizerStatus.Listening))
        val rec = AbdouRecognizer(TestData.model, TestData.vocab, cfg)
        val stable = ArrayList<String>(); val lags = ArrayList<Long>()
        var firstLocated: Long? = null
        fun deliver(evs: List<RecognitionEvent>) {
            for (e in evs) {
                if (e.isStable) for (t in e.tokens) { stable += t.text; lags += now - t.endMs!! }
                ctl.handle(SessionInput.Recognition(e))
                if (firstLocated == null && ctl.uiState.value.confirmed != null) firstLocated = now
            }
        }
        // the clip, then 2 s of silence (the reciter pauses), then stop -> flush
        val pcm = c.pcm() + ShortArray(32000)
        val chunk = 1600
        var i = 0; var n = 0
        while (i < pcm.size) {
            val len = minOf(chunk, pcm.size - i)
            now = (i + len) / 16L
            deliver(rec.accept(pcm, i, len, captureTimeMs = i / 16L))
            if (++n % 3 == 0) ctl.handle(SessionInput.Tick)
            i += len
        }
        deliver(rec.flush())
        repeat(4) { now += 250; ctl.handle(SessionInput.Tick) }
        val ui = ctl.uiState.value
        val conf = ui.confirmed
        return Run(c, stable, lags, rec.stats, ctl.history.contains(SessionState.RECITING), conf?.surah, conf?.ayah, conf?.wordIndex,
            ui.feedback.size, firstLocated)
    }

    private fun occurrences(keys: List<String>): Int {
        val ref = DbReference.reference
        var n = 0
        for (s in 0..ref.wordCount - keys.size) if (keys.indices.all { ref.word(s + it).matchKey == keys[it] }) n++
        return n
    }

    @Test fun realClipsTrackPositionWithoutFeedback() {
        TestData.requireModel(); TestData.requireDb()
        val clips = TestData.requireClips()
        val ref = DbReference.reference
        val runs = clips.map { runClip(it) }
        var exactStable = 0; var refWords = 0; var unresolved = 0
        for (r in runs) {
            val c = r.clip
            val keys = DbReference.ayahKeys(c.surah, c.ayah)
            refWords += keys.size
            if (r.stable == keys) exactStable++
            val wer = RealAudioTest.align(r.stable, keys).count { (h, k) -> h == null || k == null || r.stable[h] != keys[k] }
            println("e2e ${c.id}: stable ${r.stable.size}/${keys.size} words, word errors $wer, drops ${r.stats.droppedWords}, " +
                "confirmed ${r.surah}:${r.ayah}:${r.wordIndex}, feedback ${r.feedback}, first located at ${r.firstLocatedAudioMs} ms")
            assertEquals("${c.id}: no feedback on a correct recitation", 0, r.feedback)
            if (r.surah == null) {
                // Not located: only acceptable when the recited word sequence is ambiguous in the Quran
                // (the engine leaves ambiguity unresolved by design).
                val occ = occurrences(keys)
                println("   ${c.id}: unresolved; its ${keys.size}-word sequence occurs $occ times in the reference")
                assertTrue("${c.id}: not located although unique", occ >= 2)
                unresolved++
                continue
            }
            // A refrain (e.g. 55:13, repeated verbatim 31x) may legitimately resolve to an identical ayah.
            val gotKeys = DbReference.ayahKeys(r.surah, r.ayah!!)
            assertEquals("${c.id}: resolved ayah ${r.surah}:${r.ayah} has the same words", keys, gotKeys)
            assertTrue("${c.id}: confirmed advanced to the ayah end (lag 1 word allowed): ${r.wordIndex}/${keys.size}",
                r.wordIndex!! >= keys.size - 1)
        }
        val lags = runs.flatMap { it.lagsMs }.sorted()
        val audioMs = runs.sumOf { it.stats.audioMs }
        val computeMs = runs.sumOf { it.stats.computeMs }
        val modelMs = runs.sumOf { it.stats.modelNs } / 1e6
        val windows = runs.sumOf { it.stats.windows }
        val exactRefrain = runs.count { it.surah == it.clip.surah && it.ayah == it.clip.ayah }
        println("e2e summary: ${runs.size} clips, stable == reference keys for $exactStable/${runs.size} clips ($refWords words); " +
            "exact surah:ayah $exactRefrain/${runs.size}, unresolved (ambiguous) $unresolved; drops ${runs.sumOf { it.stats.droppedWords }}; " +
            "latency word end -> stable: mean %d ms, p50 %d, p90 %d, max %d; ".format(lags.average().toLong(), lags[lags.size / 2], lags[lags.size * 9 / 10], lags.last()) +
            "CPU %.0f ms per audio second (model %.0f), %d windows, %.0f ms per window".format(computeMs * 1000 / audioMs, modelMs * 1000 / audioMs, windows, computeMs / windows))
        assertTrue(ref.wordCount == 77429)
    }
}
