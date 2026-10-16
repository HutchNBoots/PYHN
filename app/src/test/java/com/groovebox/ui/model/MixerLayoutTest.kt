package com.groovebox.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MixerLayoutTest {
    private val mix = ParamsDef.mix
    private val channel = ParamsDef.channel

    @Test
    fun fifteenPadsInOrder() {
        val pads = MixerLayout.pads
        assertEquals(15, pads.size)
        assertEquals(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "T1", "T2", "T3", "T4", "DLY", "REV", "MST"),
            pads.map(MixerLayout::shortName),
        )
    }

    @Test
    fun channelPadsMapToMixerChannels() {
        val channels = MixerLayout.pads.mapNotNull(MixerLayout::channelOf)
        assertEquals((0 until 12).toList(), channels)
        for (c in 0 until 12) assertEquals(c, MixerLayout.channelOf(MixerLayout.padOf(c)!!))
        assertNull(MixerLayout.padOf(12))
        assertNull(MixerLayout.padOf(-1))
        assertNull(MixerLayout.channelOf(MixerPad.Delay))
        assertNull(MixerLayout.channelOf(MixerPad.Master))
        assertEquals(9, MixerLayout.channelOf(MixerPad.Track(1)))
    }

    @Test
    fun tapSelectsAndShiftTapMutesChannels() {
        assertEquals(MixerPadAction.Select(MixerPad.Slot(3)), MixerLayout.tap(MixerPad.Slot(3), shifted = false))
        assertEquals(MixerPadAction.ToggleMute(3), MixerLayout.tap(MixerPad.Slot(3), shifted = true))
        assertEquals(MixerPadAction.ToggleMute(10), MixerLayout.tap(MixerPad.Track(2), shifted = true))
        // FX and master pads have no mute: Shift + tap still selects.
        assertEquals(MixerPadAction.Select(MixerPad.Reverb), MixerLayout.tap(MixerPad.Reverb, shifted = true))
        assertEquals(MixerPadAction.Select(MixerPad.Master), MixerLayout.tap(MixerPad.Master, shifted = true))
    }

    @Test
    fun muteToggles() {
        assertEquals(1.0, MixerLayout.toggledMute(0.0), 0.0)
        assertEquals(0.0, MixerLayout.toggledMute(1.0), 0.0)
        assertEquals(0.0, MixerLayout.toggledMute(MixerLayout.toggledMute(0.0)), 0.0)
        assertTrue(MixerLayout.isMuted(1.0))
        assertFalse(MixerLayout.isMuted(0.0))
    }

    private fun keys(refs: List<StripRef?>) = refs.map {
        when (it) {
            is StripRef.Mix -> it.key
            is StripRef.Channel -> it.key
            StripRef.Commit -> "commit"
            null -> null
        }
    }

    @Test
    fun stripsPerPad() {
        val slot = listOf(MixKeys.VOLUME, MixKeys.PAN, MixKeys.DELAY_SEND, MixKeys.REVERB_SEND)
        for (s in listOf(false, true)) {
            assertEquals(slot, keys(MixerLayout.strips(MixerPad.Slot(0), s, true)))
            assertEquals(listOf(MixKeys.VOLUME, MixKeys.PAN, null, null), keys(MixerLayout.strips(MixerPad.Track(3), s, true)))
            assertEquals(
                listOf(MixKeys.REVERB_SIZE, MixKeys.REVERB_DECAY, MixKeys.REVERB_DAMP, MixKeys.REVERB_RETURN),
                keys(MixerLayout.strips(MixerPad.Reverb, s, true)),
            )
            assertEquals(listOf(MixKeys.MASTER, null, null, null), keys(MixerLayout.strips(MixerPad.Master, s, false)))
            // Channel strips are per channel: they never name a global parameter.
            assertTrue(MixerLayout.strips(MixerPad.Slot(5), s, false).all { it is StripRef.Channel })
        }
        assertEquals(
            listOf(MixKeys.DELAY_TIME, MixKeys.DELAY_FEEDBACK, MixKeys.DELAY_LOW_CUT, MixKeys.DELAY_HIGH_CUT),
            keys(MixerLayout.strips(MixerPad.Delay, shifted = false, delaySynced = false)),
        )
        assertEquals(
            listOf(MixKeys.DELAY_SYNC, MixKeys.DELAY_DIV, MixKeys.DELAY_RETURN, null),
            keys(MixerLayout.strips(MixerPad.Delay, shifted = true, delaySynced = true)),
        )
    }

    @Test
    fun delayTimeStripDrivesTheDivisionWhenSynced() {
        val synced = MixerLayout.strips(MixerPad.Delay, shifted = false, delaySynced = true)[0]
        assertEquals(StripRef.Mix(MixKeys.DELAY_DIV, label = "Time"), synced)
        assertEquals(StripRef.Mix(MixKeys.DELAY_TIME), MixerLayout.delayTime(false))
    }

    @Test
    fun delayTimeText() {
        val div = mix.spec(MixKeys.DELAY_DIV)
        val time = mix.spec(MixKeys.DELAY_TIME)
        assertEquals("1/8", MixFormat.delayTime(true, div, 4.0, time, 0.375))
        assertEquals("1/8D", MixFormat.delayTime(true, div, 5.0, time, 0.375))
        assertEquals("1/16T", MixFormat.delayTime(true, div, 0.0, time, 0.375))
        assertEquals("1/2", MixFormat.delayTime(true, div, 10.0, time, 0.375))
        assertEquals("375 ms", MixFormat.delayTime(false, div, 5.0, time, 0.375))
        assertEquals("1.2 s", MixFormat.delayTime(false, div, 5.0, time, 1.2))
        // The strip itself formats the same way.
        assertEquals("1/8D", MixFormat.format(div, 5.0))
    }

    @Test
    fun mixerValuesFormat() {
        val vol = channel.spec(MixKeys.VOLUME)
        val pan = channel.spec(MixKeys.PAN)
        assertEquals("−∞ dB", MixFormat.format(vol, -60.0))
        assertEquals("−12 dB", MixFormat.format(vol, -12.2))
        assertEquals("0 dB", MixFormat.format(vol, 0.0))
        assertEquals("+6 dB", MixFormat.format(vol, 6.0))
        assertEquals("−∞ dB", MixFormat.format(mix.spec(MixKeys.MASTER), -60.0))
        assertEquals("C", MixFormat.format(pan, 0.004))
        assertEquals("L40", MixFormat.format(pan, -0.4))
        assertEquals("R100", MixFormat.format(pan, 1.0))
        assertEquals("25%", MixFormat.format(channel.spec(MixKeys.REVERB_SEND), 0.25))
        assertEquals("Tempo", MixFormat.format(mix.spec(MixKeys.DELAY_SYNC), 1.0))
        assertEquals("Free", MixFormat.format(mix.spec(MixKeys.DELAY_SYNC), 0.0))
        assertEquals("120 Hz", MixFormat.format(mix.spec(MixKeys.DELAY_LOW_CUT), 120.0))
        assertEquals("6.0k", MixFormat.format(mix.spec(MixKeys.DELAY_HIGH_CUT), 6000.0))
        assertEquals("2.5 s", MixFormat.format(mix.spec(MixKeys.REVERB_DECAY), 2.5))
        assertEquals("40%", MixFormat.format(mix.spec(MixKeys.DELAY_FEEDBACK), 0.4))
    }

    @Test
    fun titles() {
        val name = { slot: Int -> if (slot == 0) "Kit" else "Preset $slot" }
        assertEquals("Slot 1 · Kit", MixerLayout.title(MixerPad.Slot(0), name))
        assertEquals("Slot 3 · Preset 2", MixerLayout.title(MixerPad.Slot(2), name))
        assertEquals("Tape T2", MixerLayout.title(MixerPad.Track(1), name))
        assertEquals("Delay", MixerLayout.title(MixerPad.Delay, name))
    }
}
