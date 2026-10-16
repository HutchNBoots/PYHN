package com.groovebox.ui.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordButtonTest {
    private val button = RecordButton(tapMs = 300)

    @Test
    fun aTapTogglesArm() {
        assertTrue(button.press(0))
        assertTrue(button.held)
        assertTrue(button.release(100))
        assertTrue(button.armed)
        assertFalse(button.held)
        button.press(1000)
        assertTrue(button.release(1100))
        assertFalse(button.armed)
    }

    @Test
    fun holdingWhileAStripMovesRecordsMotionWithoutArming() {
        button.press(0)
        button.stripMoved()
        assertFalse(button.release(100))
        assertFalse(button.armed)
    }

    @Test
    fun aLongHoldDoesNotArm() {
        button.press(0)
        assertFalse(button.release(300))
        assertFalse(button.armed)
    }

    @Test
    fun stripMovesWhileNotHeldDoNotCount() {
        button.stripMoved()
        button.press(0)
        assertTrue(button.release(50))
        assertTrue(button.armed)
    }

    @Test
    fun aSecondPressWhileHeldIsIgnored() {
        assertTrue(button.press(0))
        assertFalse(button.press(10))
    }

    @Test
    fun cancelStopsWithoutChangingArm() {
        button.press(0)
        button.cancel()
        assertFalse(button.held)
        assertFalse(button.release(10))
        assertFalse(button.armed)
    }
}
