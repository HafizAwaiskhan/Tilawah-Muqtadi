package com.qurancompanion.engine.session

import com.qurancompanion.engine.session.SessionState.*
import com.qurancompanion.engine.testutil.Driver
import com.qurancompanion.engine.testutil.Synthetic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManualPositionTest {
    @Test fun manualPositionWhileLocatingStartsTracking() {
        val d = Driver()
        val ayah = Synthetic.ayah(2, 5)
        d.handle(SessionInput.SetManualPosition(ayah.first))
        assertEquals(RECITING, d.c.currentState)
        assertEquals(ayah.first, d.ui.confirmed?.wordId)
        assertEquals(ayah.first, d.ui.candidate?.wordId)
        assertEquals(ayah.first + 1, d.ui.nextWordId)
        // continuing from there advances normally
        d.say((ayah.first + 1)..(ayah.first + 3))
        assertEquals(ayah.first + 2, d.ui.confirmed?.wordId) // one-word commit lag
    }

    @Test fun manualPositionWithdrawsOfferAndClearsHeldMistakes() {
        val d = Driver()
        val ayah = Synthetic.ayah(1, 3)
        d.handle(SessionInput.SetManualPosition(ayah.first))
        d.say((ayah.first + 1)..(ayah.first + 2))
        d.silence(12_000)
        assertEquals(WAITING_FOR_CONFIRMATION, d.c.currentState)
        val target = Synthetic.ayah(1, 7).first
        d.handle(SessionInput.SetManualPosition(target))
        assertEquals(RECITING, d.c.currentState)
        assertNull(d.ui.pendingOffer)
        assertTrue(d.ui.heldMistakes.isEmpty())
        assertEquals(target, d.ui.confirmed?.wordId)
    }

    @Test fun manualPositionWhilePausedKeepsPausedAndInvalidIdsIgnored() {
        val d = Driver()
        d.say(Synthetic.ayah(1, 2))
        d.handle(SessionInput.Pause)
        val target = Synthetic.ayah(3, 1).first
        d.handle(SessionInput.SetManualPosition(target))
        assertEquals(PAUSED, d.c.currentState)
        assertEquals(target, d.ui.confirmed?.wordId)
        assertEquals(target, d.c.snapshot()!!.lastWordId)
        d.handle(SessionInput.SetManualPosition(-1))
        d.handle(SessionInput.SetManualPosition(d.ref.wordCount))
        assertEquals(target, d.ui.confirmed?.wordId)
        d.handle(SessionInput.Resume)
        assertEquals(RECITING, d.c.currentState)
    }

    @Test fun manualPositionIgnoredBeforeSessionAndDuringPlayback() {
        val idle = Driver(start = false)
        idle.handle(SessionInput.SetManualPosition(3))
        assertEquals(IDLE, idle.c.currentState)

        val d = Driver()
        val ayah = Synthetic.ayah(1, 3)
        d.handle(SessionInput.SetManualPosition(ayah.first))
        d.handle(SessionInput.RequestLukma())
        assertTrue(d.c.currentState in SessionState.PLAYING)
        val before = d.ui.confirmed
        d.handle(SessionInput.SetManualPosition(ayah.first + 10))
        assertEquals(before, d.ui.confirmed)
    }
}
