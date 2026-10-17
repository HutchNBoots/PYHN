package com.groovebox.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MixerSoloTest {
    @Test
    fun noSoloLeavesTheUserMutes() {
        assertFalse(MixerSolo.effectiveMute(2, userMuted = false, soloed = emptySet()))
        assertTrue(MixerSolo.effectiveMute(2, userMuted = true, soloed = emptySet()))
    }

    @Test
    fun aSoloMutesEveryOtherChannel() {
        val soloed = setOf(1)
        for (c in 0 until MixLimits.CHANNELS) {
            assertEquals("channel $c", c != 1, MixerSolo.effectiveMute(c, userMuted = false, soloed = soloed))
        }
    }

    @Test
    fun aMutedSoloedChannelStaysMuted() {
        assertTrue(MixerSolo.effectiveMute(1, userMuted = true, soloed = setOf(1)))
    }

    @Test
    fun twoSolosBothPlay() {
        val soloed = setOf(1, MixLimits.tapeChannel(0))
        assertFalse(MixerSolo.effectiveMute(1, false, soloed))
        assertFalse(MixerSolo.effectiveMute(MixLimits.tapeChannel(0), false, soloed))
        assertTrue(MixerSolo.effectiveMute(2, false, soloed))
    }

    @Test
    fun silencedBySoloIsOnlyTheSoloEffect() {
        val soloed = setOf(1)
        assertTrue(MixerSolo.silencedBySolo(2, soloed))
        assertFalse(MixerSolo.silencedBySolo(1, soloed))
        assertFalse(MixerSolo.silencedBySolo(2, emptySet()))
    }

    @Test
    fun toggleAddsAndRemoves() {
        assertEquals(setOf(3), MixerSolo.toggled(emptySet(), 3))
        assertEquals(setOf(3, 4), MixerSolo.toggled(setOf(3), 4))
        assertEquals(setOf(4), MixerSolo.toggled(setOf(3, 4), 3))
    }

    @Test
    fun toggleIgnoresNonChannels() {
        assertEquals(setOf(3), MixerSolo.toggled(setOf(3), -1))
        assertEquals(setOf(3), MixerSolo.toggled(setOf(3), MixLimits.CHANNELS))
    }

    @Test
    fun theMuteValuesForTheEngine() {
        val user = BooleanArray(MixLimits.CHANNELS).also { it[4] = true }
        val out = MixerSolo.engineMutes(user, setOf(1))
        assertEquals(MixLimits.CHANNELS, out.size)
        assertEquals(0.0, out[1], 0.0)
        assertEquals(1.0, out[4], 0.0)
        assertEquals(1.0, out[0], 0.0)
        val none = MixerSolo.engineMutes(user, emptySet())
        assertEquals(1.0, none[4], 0.0)
        assertEquals(0.0, none[0], 0.0)
    }
}
