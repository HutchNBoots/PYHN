package com.groovebox.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrumRulesTest {
    // --- replay on release (manual 2-2A) ---

    @Test
    fun decayStartAndRevReplayTheSoundWhenTheirDragEnds() {
        for (key in listOf("drum.decay", "drum.start", "drum.reverse")) {
            assertTrue(key, DrumReplay.onRelease(key, changed = true, playing = false, soundInPattern = false))
        }
    }

    @Test
    fun paramsHeardWhileTheSoundRingsDoNotReplay() {
        for (key in listOf("drum.pitch", "drum.tone", "drum.level", "drum.pan", "drum.choke")) {
            assertFalse(key, DrumReplay.onRelease(key, changed = true, playing = false, soundInPattern = false))
        }
    }

    @Test
    fun nothingChangedNothingReplays() {
        assertFalse(DrumReplay.onRelease("drum.decay", changed = false, playing = false, soundInPattern = false))
    }

    @Test
    fun theBeatReplaysItWhenItIsInThePlayingPattern() {
        assertFalse(DrumReplay.onRelease("drum.decay", changed = true, playing = true, soundInPattern = true))
        assertTrue(DrumReplay.onRelease("drum.decay", changed = true, playing = true, soundInPattern = false))
        assertTrue(DrumReplay.onRelease("drum.decay", changed = true, playing = false, soundInPattern = true))
    }

    // --- per-sound locks ---

    @Test
    fun aLockBelongsToOneSound() {
        assertEquals("drum.decay@3", DrumLockKeys.of(3, "drum.decay"))
        assertEquals(3 to "drum.decay", DrumLockKeys.parse("drum.decay@3"))
        assertEquals(null, DrumLockKeys.parse("drum.decay"))
        assertEquals(null, DrumLockKeys.parse("drum.decay@x"))
    }

    @Test
    fun anOldKitWideLockBecomesALockOnEverySound() {
        val migrated = DrumLockKeys.migrate(setOf("drum.tone", "drum.pan@2"))
        assertEquals(DrumLayout.KEY_COUNT + 1, migrated.size)
        assertTrue((0 until 16).all { DrumLockKeys.of(it, "drum.tone") in migrated })
        assertTrue("drum.pan@2" in migrated)
    }

    // --- Rev and Choke tap cells ---

    @Test
    fun tapCellsAreTheShiftLayersToggles() {
        assertEquals(listOf(false, false, true, true), DrumLayout.shift.map { DrumTapCells.isCell(it) })
        assertTrue(DrumLayout.main.none { DrumTapCells.isCell(it) })
        assertEquals(listOf("Off", "On"), DrumTapCells.options("drum.reverse"))
        assertEquals(listOf("None", "A", "B"), DrumTapCells.options("drum.choke"))
    }
}
