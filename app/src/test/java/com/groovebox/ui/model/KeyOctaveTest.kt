package com.groovebox.ui.model

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyOctaveTest {
    @Test
    fun theKeyboardStartsAtC3AndMovesByOctaves() {
        assertEquals(48, KeyOctave.lowestNote(0))
        assertEquals(60, KeyOctave.lowestNote(1))
        assertEquals(24, KeyOctave.lowestNote(-2))
    }

    @Test
    fun itIsClampedToTwoOctavesEachWay() {
        assertEquals(KeyOctave.lowestNote(2), KeyOctave.lowestNote(5))
        assertEquals(KeyOctave.lowestNote(-2), KeyOctave.lowestNote(-9))
        assertEquals(2, KeyOctave.clamp(3))
    }

    @Test
    fun theKeysPlayTheOffsetNotesAndTheirCLabelsFollow() {
        val keys = KeyboardLayout(lowestNote = KeyOctave.lowestNote(1), octaves = 2).keys
        assertEquals(60, keys.minOf { it.note })
        assertEquals(83, keys.maxOf { it.note })
        val cs = keys.filter { !it.isBlack && it.note % 12 == 0 }.map { KeyOctave.noteName(it.note) }
        assertEquals(listOf("C4", "C5"), cs)
    }

    @Test
    fun theRangeReadsAsNoteNames() {
        assertEquals("C3–B4", KeyOctave.rangeLabel(0))
        assertEquals("C1–B2", KeyOctave.rangeLabel(-2))
    }

    @Test
    fun theStripShowsASignedOctave() {
        assertEquals("+1", KeyOctave.format(1))
        assertEquals("0", KeyOctave.format(0))
        assertEquals("−2", KeyOctave.format(-2))
    }
}
