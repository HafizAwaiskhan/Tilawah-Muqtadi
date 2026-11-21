package com.qurancompanion.engine.testutil

import com.qurancompanion.engine.recognition.RecognitionEvent
import com.qurancompanion.engine.recognition.RecognizerStatus
import com.qurancompanion.engine.recognition.Token
import com.qurancompanion.engine.recognition.TokenUnit
import com.qurancompanion.engine.reference.QuranReference
import com.qurancompanion.engine.session.PracticeMode
import com.qurancompanion.engine.session.RecitationSessionController
import com.qurancompanion.engine.session.SessionConfig
import com.qurancompanion.engine.session.SessionInput
import com.qurancompanion.engine.session.SessionState

/** Drives a controller with a synthetic reciter: one stable event per word, ticks between words. */
class Driver(
    val ref: QuranReference = Synthetic.reference,
    val mode: PracticeMode = PracticeMode.RECITATION,
    val unit: TokenUnit = TokenUnit.WORD,
    config: SessionConfig = SessionConfig(),
    start: Boolean = true,
) {
    val clock = ManualClock()
    val c = RecitationSessionController(ref, unit, clock, config, newSessionId = { "session-1" })
    var wordMs = 450L
    var gapMs = 150L
    val ui get() = c.uiState.value
    val confirmedTrail = ArrayList<Int?>()

    init {
        if (start) {
            c.handle(SessionInput.Initialize)
            c.handle(SessionInput.PermissionsResult(true))
            c.handle(SessionInput.ModelReady)
            c.handle(SessionInput.ReferenceReady)
            c.handle(SessionInput.StartSession(mode))
            c.handle(SessionInput.RecognizerStatusChanged(RecognizerStatus.Listening))
            check(c.currentState == SessionState.LOCATING_POSITION)
        }
    }

    fun handle(i: SessionInput) = c.handle(i)

    /** Says one recognized token (word mode) or the phonemes of [text] (phoneme mode). */
    fun sayText(text: String, confidence: Float = 0.95f, durationMs: Long = wordMs, gap: Long = gapMs) {
        val start = clock.now
        if (unit == TokenUnit.WORD) {
            clock.advance(durationMs)
            stable(listOf(Token(text, start, clock.now, confidence)))
        } else {
            val per = durationMs / text.length
            for (ch in text) {
                val s = clock.now
                clock.advance(per)
                stable(listOf(Token(ch.toString(), s, clock.now, confidence)))
            }
        }
        clock.advance(gap)
        tick()
    }

    fun stable(tokens: List<Token>) {
        c.handle(SessionInput.Recognition(RecognitionEvent(tokens, true, clock.now, unit)))
        confirmedTrail += ui.confirmed?.wordId
    }

    fun provisional(tokens: List<Token>) = c.handle(SessionInput.Recognition(RecognitionEvent(tokens, false, clock.now, unit)))

    fun sayWord(id: Int) = sayText(ref.word(id).matchKey)
    fun say(ids: Iterable<Int>) = ids.forEach { sayWord(it) }

    fun tick() = c.handle(SessionInput.Tick)

    /** Silence with ticks every 250 ms. */
    fun silence(ms: Long) {
        var left = ms
        while (left > 0) { val s = minOf(250L, left); clock.advance(s); left -= s; tick() }
    }
}
