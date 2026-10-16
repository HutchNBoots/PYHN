package com.groovebox.ui.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SlotSwipeTest {
    private val swipe = SlotSwipe(stepPx = 100f)

    @Test
    fun shortDragDoesNothing() {
        assertEquals(0, swipe.drag(-60f))
        assertEquals(0, swipe.drag(30f))
    }

    @Test
    fun fingerLeftIsNextSlotAndRightIsPrevious() {
        assertEquals(1, swipe.drag(-100f))
        swipe.reset()
        assertEquals(-1, swipe.drag(100f))
    }

    @Test
    fun travelAccumulatesAcrossEventsAndCarriesTheRemainder() {
        assertEquals(0, swipe.drag(-70f))
        assertEquals(1, swipe.drag(-70f)) // 140: one step, 40 left over
        assertEquals(1, swipe.drag(-60f)) // 100 again
        assertEquals(0, swipe.drag(-10f))
    }

    @Test
    fun oneLongEventCanStepMoreThanOnce() {
        assertEquals(3, swipe.drag(-320f))
    }

    @Test
    fun turningBackStepsBack() {
        assertEquals(1, swipe.drag(-120f)) // 20 left over
        assertEquals(-1, swipe.drag(130f)) // +130 from -20 is past +100: one step back
    }

    @Test
    fun resetForgetsTravel() {
        swipe.drag(-90f)
        swipe.reset()
        assertEquals(0, swipe.drag(-90f))
    }

    @Test
    fun targetClampsToTheRangeWithoutWrapping() {
        assertEquals(3, SlotSwipe.target(2, 1, includeDrum = true))
        assertEquals(7, SlotSwipe.target(7, 1, includeDrum = true))
        assertEquals(0, SlotSwipe.target(1, -3, includeDrum = true))
        assertEquals(7, SlotSwipe.target(5, 4, includeDrum = true))
    }

    @Test
    fun synthScreenNeverSwipesOntoTheDrumSlot() {
        assertEquals(1, SlotSwipe.target(1, -1, includeDrum = false))
        assertEquals(1, SlotSwipe.target(4, -9, includeDrum = false))
        assertEquals(2, SlotSwipe.target(1, 1, includeDrum = false))
    }
}
