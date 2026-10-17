package com.groovebox.ui.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SlotChipTest {
    @Test
    fun aTapStepsToTheNextSlotAndWraps() {
        assertEquals(2, SlotChip.next(1))
        assertEquals(7, SlotChip.next(6))
        assertEquals(0, SlotChip.next(7)) // 8 → 1 (the kit)
        assertEquals(1, SlotChip.next(0))
    }

    @Test
    fun aFullCycleVisitsEverySlotOnce() {
        val seen = generateSequence(1) { SlotChip.next(it) }.take(SeqLimits.SLOTS).toList()
        assertEquals((0 until SeqLimits.SLOTS).toSet(), seen.toSet())
        assertEquals(1, SlotChip.next(seen.last()))
    }

    @Test
    fun outOfRangeSlotsStartAtTheFirst() {
        assertEquals(0, SlotChip.next(-1))
        assertEquals(0, SlotChip.next(99))
    }

    @Test
    fun theChipNamesTheSlotOneBased() {
        assertEquals("slot 1 ▾", SlotChip.label(0))
        assertEquals("slot 8 ▾", SlotChip.label(7))
    }
}
