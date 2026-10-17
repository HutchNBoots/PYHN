package com.groovebox.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MixerSyncTest {
    @Test
    fun aSlotPadSelectsThatSlot() {
        assertEquals(3, MixerSync.slotOf(MixerPad.Slot(3)))
        assertNull(MixerSync.trackOf(MixerPad.Slot(3)))
    }

    @Test
    fun aTrackPadSelectsThatTapeTrack() {
        assertEquals(2, MixerSync.trackOf(MixerPad.Track(2)))
        assertNull(MixerSync.slotOf(MixerPad.Track(2)))
    }

    @Test
    fun returnsAndMasterSelectNeither() {
        for (pad in listOf(MixerPad.Delay, MixerPad.Reverb, MixerPad.Master)) {
            assertNull(MixerSync.slotOf(pad))
            assertNull(MixerSync.trackOf(pad))
        }
    }

    @Test
    fun onShowASlotPadFollowsTheTopBarSlot() {
        assertEquals(MixerPad.Slot(5), MixerSync.padOnShow(MixerPad.Slot(1), selectedSlot = 5, tapeTrack = 0))
    }

    @Test
    fun onShowATrackPadFollowsTheTapeTrack() {
        assertEquals(MixerPad.Track(3), MixerSync.padOnShow(MixerPad.Track(0), selectedSlot = 5, tapeTrack = 3))
    }

    @Test
    fun onShowAnFxOrMasterPadStays() {
        for (pad in listOf(MixerPad.Delay, MixerPad.Reverb, MixerPad.Master)) {
            assertEquals(pad, MixerSync.padOnShow(pad, selectedSlot = 5, tapeTrack = 3))
        }
    }

    @Test
    fun everySlotAndTrackRoundTrips() {
        for (s in 0 until SeqLimits.SLOTS) assertEquals(MixerPad.Slot(s), MixerSync.padOnShow(MixerPad.Slot(0), s, 0))
        for (t in 0 until MixLimits.TRACKS) assertEquals(MixerPad.Track(t), MixerSync.padOnShow(MixerPad.Track(0), 0, t))
    }
}
