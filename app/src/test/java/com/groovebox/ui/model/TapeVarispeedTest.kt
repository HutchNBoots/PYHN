package com.groovebox.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TapeVarispeedTest {
    @Test
    fun atTheTapesTempoThereIsNoVarispeed() {
        assertNull(TapeView.varispeed(MixKeys.SPEED_NORMAL, 120.0, 120f, tempoMatches = true))
    }

    @Test
    fun anEmptyTapeHasNoTempo() {
        assertNull(TapeView.varispeed(MixKeys.SPEED_NORMAL, 120.0, 0f, tempoMatches = true))
        assertNull(TapeView.varispeed(MixKeys.SPEED_NORMAL, 120.0, 0f, tempoMatches = false))
    }

    @Test
    fun anotherTempoShowsTheRateAndTheTapesTempo() {
        assertEquals("0.92× · tape 130.0", TapeView.varispeed(MixKeys.SPEED_NORMAL, 120.0, 130f, tempoMatches = false))
        assertEquals("1.25× · tape 100.0", TapeView.varispeed(MixKeys.SPEED_NORMAL, 125.0, 100f, tempoMatches = false))
    }

    @Test
    fun theSpeedStripMultipliesIn() {
        assertEquals("1.85× · tape 130.0", TapeView.varispeed(2, 120.0, 130f, tempoMatches = false))
        assertEquals("0.46× · tape 130.0", TapeView.varispeed(0, 120.0, 130f, tempoMatches = false))
    }

    @Test
    fun trailingZerosAreDropped() {
        assertEquals("0.5× · tape 120.0", TapeView.varispeed(MixKeys.SPEED_NORMAL, 60.0, 120f, tempoMatches = false))
        assertEquals("2× · tape 60.0", TapeView.varispeed(MixKeys.SPEED_NORMAL, 120.0, 60f, tempoMatches = false))
    }

    @Test
    fun theSubtitleShowsVarispeedInPlaceOfTheSpeed() {
        val region = TapeView.loopRegion(0, 4, 8)
        assertEquals(
            "8 bars · looping bars 1–4 · overdub · 0.92× · tape 130.0",
            TapeView.subtitle(8, region, 1.0, "1×", reverse = false, varispeed = "0.92× · tape 130.0"),
        )
        assertEquals(
            "8 bars · looping bars 1–4 · overdub · 0.92× · tape 130.0 reverse",
            TapeView.subtitle(8, region, 1.0, "1×", reverse = true, varispeed = "0.92× · tape 130.0"),
        )
    }

    @Test
    fun theSubtitleWithoutVarispeedIsUnchanged() {
        val region = TapeView.loopRegion(0, 0, 8)
        assertEquals("8 bars · looping all · overdub · 2×", TapeView.subtitle(8, region, 1.0, "2×", reverse = false))
        assertEquals("8 bars · looping all · overdub", TapeView.subtitle(8, region, 1.0, "1×", reverse = false))
    }
}
