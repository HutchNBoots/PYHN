package com.groovebox.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a live-record take's undo step opens and closes (build-20 review fix 3). */
class RecordTakeTest {
    private val take = RecordTake()

    @Test
    fun armingOpensATake() {
        assertEquals(RecordTake.Action.Open, take.armed(true))
        assertTrue(take.open)
        assertEquals(RecordTake.Action.None, take.armed(true))
    }

    @Test
    fun theTakeClosesOneFrameAfterTheEngineReportsDisarmed() {
        take.armed(true)
        assertEquals(RecordTake.Action.None, take.armed(false))
        assertTrue("still open: events may still come", take.open)
        assertFalse("engine still armed", take.frameApplied(engineArmed = true))
        assertFalse("first disarmed frame: its events joined the take", take.frameApplied(engineArmed = false))
        assertTrue("one more frame of events, then close", take.frameApplied(engineArmed = false))
        assertFalse(take.open)
        assertFalse(take.frameApplied(engineArmed = false))
    }

    @Test
    fun rearmingWhileClosingClosesTheOldTakeAndOpensANewOne() {
        take.armed(true)
        take.armed(false)
        take.frameApplied(engineArmed = false)
        assertEquals(RecordTake.Action.CloseAndOpen, take.armed(true))
        assertTrue(take.open)
        assertFalse(take.frameApplied(engineArmed = true))
    }

    @Test
    fun framesWithoutATakeDoNothing() {
        assertFalse(take.frameApplied(engineArmed = false))
        assertEquals(RecordTake.Action.None, take.armed(false))
    }
}
