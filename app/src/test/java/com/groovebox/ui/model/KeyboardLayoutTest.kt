package com.groovebox.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyboardLayoutTest {
    private val layout = KeyboardLayout(lowestNote = 48, octaves = 2)
    private val width = 1400f
    private val height = 500f
    private val whiteWidth = width / 14

    @Test
    fun twoOctavesHave14WhiteAnd10BlackKeys() {
        assertEquals(14, layout.keys.count { !it.isBlack })
        assertEquals(10, layout.keys.count { it.isBlack })
        assertEquals((48 until 72).toList(), layout.keys.map { it.note }.sorted())
    }

    @Test
    fun lowerHalfMapsToWhiteKeys() {
        val y = height * 0.9f
        assertEquals(48, layout.noteAt(whiteWidth * 0.5f, y, width, height)) // C3
        assertEquals(50, layout.noteAt(whiteWidth * 1.5f, y, width, height)) // D3
        assertEquals(53, layout.noteAt(whiteWidth * 3.5f, y, width, height)) // F3
        assertEquals(60, layout.noteAt(whiteWidth * 7.5f, y, width, height)) // C4
        assertEquals(71, layout.noteAt(width - 1f, y, width, height)) // B4
    }

    @Test
    fun upperPartOverBoundaryMapsToBlackKeys() {
        val y = height * 0.2f
        assertEquals(49, layout.noteAt(whiteWidth * 1.0f, y, width, height)) // C#3
        assertEquals(51, layout.noteAt(whiteWidth * 2.0f, y, width, height)) // D#3
        assertEquals(54, layout.noteAt(whiteWidth * 4.0f, y, width, height)) // F#3
        assertEquals(70, layout.noteAt(whiteWidth * 13.0f, y, width, height)) // A#4
    }

    @Test
    fun upperPartWithoutBlackKeyFallsThroughToWhite() {
        val y = height * 0.2f
        // Between E and F there is no black key.
        assertEquals(52, layout.noteAt(whiteWidth * 2.9f, y, width, height))
        assertEquals(53, layout.noteAt(whiteWidth * 3.1f, y, width, height))
    }

    @Test
    fun outsideBoundsIsNoNote() {
        assertNull(layout.noteAt(-1f, 10f, width, height))
        assertNull(layout.noteAt(10f, -1f, width, height))
        assertNull(layout.noteAt(width, 10f, width, height))
        assertNull(layout.noteAt(10f, height, width, height))
        assertNull(layout.noteAt(10f, 10f, 0f, 0f))
    }

    @Test
    fun velocityRisesFromHalfAtTheTopToFullAtTheBottomOfAWhiteKey() {
        val x = whiteWidth * 2.5f // E3, no black key above
        assertEquals(0.5f, layout.hit(x, 0f, width, height)!!.velocity, 1e-6f)
        assertEquals(0.75f, layout.hit(x, height / 2, width, height)!!.velocity, 1e-6f)
        assertEquals(1.0f, layout.hit(x, height - 0.001f, width, height)!!.velocity, 1e-4f)
    }

    @Test
    fun velocityOnABlackKeyUsesTheBlackKeyHeight() {
        val x = whiteWidth * 1.0f // C#3
        val blackBottom = height * 0.48f
        val top = layout.hit(x, 0f, width, height)!!
        val mid = layout.hit(x, blackBottom / 2, width, height)!!
        assertEquals(49, top.note)
        assertEquals(0.5f, top.velocity, 1e-6f)
        assertEquals(0.75f, mid.velocity, 1e-4f)
    }

    @Test
    fun whiteKeyBelowABlackKeyStillUsesTheWholeWhiteKey() {
        val x = whiteWidth * 1.0f - 0.5f // just left of the C/D boundary, below C#3
        val hit = layout.hit(x, height * 0.8f, width, height)!!
        assertEquals(48, hit.note)
        assertEquals(0.9f, hit.velocity, 1e-4f)
    }

    @Test
    fun hitOutsideIsNull() {
        assertNull(layout.hit(-1f, 10f, width, height))
        assertNull(layout.hit(10f, 10f, 0f, height))
    }
}
