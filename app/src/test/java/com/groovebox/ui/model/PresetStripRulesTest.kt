package com.groovebox.ui.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetStripRulesTest {
    @Test
    fun playingTheKeysKeepsTheStripOpenForAuditioning() {
        assertFalse(PresetStripRules.closesOn(PresetTouch.Keys))
    }

    @Test
    fun aTouchOnTheStripItselfKeepsItOpen() {
        assertFalse(PresetStripRules.closesOn(PresetTouch.Strip))
    }

    @Test
    fun aTapOutsideTheStripAndKeysClosesIt() {
        // The display, the rail, the strips and the top bar (other slots included).
        assertTrue(PresetStripRules.closesOn(PresetTouch.Elsewhere))
    }

    @Test
    fun tappingTheSlotWhoseStripIsOpenClosesIt() {
        assertTrue(PresetStripRules.closesOn(PresetTouch.OpenSlot))
    }

    @Test
    fun theZoneOfATouch() {
        // The strip sits over the top of the keys, so it wins where they overlap.
        // The strip's × is part of the strip here; it closes from its own tap.
        assertTrue(PresetStripRules.zone(inStrip = true, inKeys = true, inOpenSlot = false) == PresetTouch.Strip)
        assertTrue(PresetStripRules.zone(inStrip = false, inKeys = true, inOpenSlot = false) == PresetTouch.Keys)
        assertTrue(PresetStripRules.zone(inStrip = false, inKeys = false, inOpenSlot = true) == PresetTouch.OpenSlot)
        assertTrue(PresetStripRules.zone(inStrip = false, inKeys = false, inOpenSlot = false) == PresetTouch.Elsewhere)
    }
}
