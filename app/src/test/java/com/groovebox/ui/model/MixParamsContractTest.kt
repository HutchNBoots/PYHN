package com.groovebox.ui.model

import com.groovebox.engine.Curve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks the Tape and Mixer screens against the tables the engine compiles from. */
class MixParamsContractTest {
    private val mix = ParamsDef.mix
    private val channel = ParamsDef.channel

    @Test
    fun everyLineParsesWithAKnownCurve() {
        val curves = Curve.entries.map { it.name }.toSet()
        for (def in listOf(mix, channel)) {
            assertTrue("no rows in ${def.file}", def.rows.isNotEmpty())
            assertEquals(def.lines.size, def.rows.size)
            for (r in def.rows) assertTrue("${def.file.name}:${r.lineNumber}: curve ${r.curveName}", r.curveName in curves)
            assertEquals("unique keys in ${def.file.name}", def.rows.size, def.rows.map { it.key }.toSet().size)
        }
    }

    @Test
    fun everyKeyTheUiNamesExistsInTheRightTable() {
        val mixKeys = mix.rows.map { it.key }.toSet()
        val channelKeys = channel.rows.map { it.key }.toSet()
        fun check(ref: StripRef?, where: String) {
            when (ref) {
                is StripRef.Mix -> assertTrue("$where: '${ref.key}' not in MixParams.def", ref.key in mixKeys)
                is StripRef.Channel -> assertTrue("$where: '${ref.key}' not in ChannelParams.def", ref.key in channelKeys)
                else -> Unit
            }
        }
        (TapeStrips.main + TapeStrips.shift).forEach { check(it, "TapeStrips") }
        for (pad in MixerLayout.pads) {
            for (shifted in listOf(false, true)) for (synced in listOf(false, true)) {
                MixerLayout.strips(pad, shifted, synced).forEach { check(it, "MixerLayout $pad") }
            }
        }
        for (k in MixerLayout.allKeys) assertTrue(k, k in mixKeys || k in channelKeys)
        // The constants used outside the layouts.
        for (k in listOf(MixKeys.MUTE, MixKeys.VOLUME, MixKeys.PAN, MixKeys.DELAY_SEND, MixKeys.REVERB_SEND)) {
            assertTrue(k, k in channelKeys)
        }
        for (k in listOf(MixKeys.DELAY_SYNC, MixKeys.DELAY_DIV, MixKeys.DELAY_TIME, MixKeys.SPEED, MixKeys.REVERSE, MixKeys.DUB,
            MixKeys.LOOP_START, MixKeys.LOOP_LEN, MixKeys.MASTER)) {
            assertTrue(k, k in mixKeys)
        }
    }

    @Test
    fun everyMixAndFxParamIsOnTheMixerOrTapeScreen() {
        val onScreens = MixerLayout.allKeys + (TapeStrips.main + TapeStrips.shift).mapNotNull {
            (it as? StripRef.Mix)?.key ?: (it as? StripRef.Channel)?.key
        } + MixKeys.MUTE // Shift + tap on a pad
        for (r in mix.rows + channel.rows) assertTrue("${r.key} is on no strip", r.key in onScreens)
    }

    @Test
    fun theEnumIndexesTheUiReliesOnAreTheTablesOnes() {
        assertEquals("1x", mix.spec(MixKeys.SPEED).options[MixKeys.SPEED_NORMAL])
        assertEquals("tempo", mix.spec(MixKeys.DELAY_SYNC).options[MixKeys.SYNC_TEMPO])
        assertEquals(listOf("off", "on"), channel.spec(MixKeys.MUTE).options)
        assertEquals(listOf("off", "on"), mix.spec(MixKeys.REVERSE).options)
        // tape.loopLen 0 means the whole tape ("All").
        assertEquals(0.0, mix.spec(MixKeys.LOOP_LEN).min, 0.0)
    }

    @Test
    fun rangesAreUsableAndEveryValueFormats() {
        for (s in mix.specs + channel.specs) {
            assertTrue("${s.key}: min < max", s.min < s.max)
            assertTrue("${s.key}: default in range", s.default in s.min..s.max)
            if (s.curve == Curve.Exp) assertTrue("${s.key}: Exp needs min > 0", s.min > 0.0)
            if (s.curve == Curve.Enum) assertEquals(s.key, (s.max - s.min).toInt() + 1, s.options.size)
            for (v in listOf(s.min, s.default, s.max)) assertTrue("${s.key} at $v", MixFormat.format(s, v).isNotBlank())
            val back = ParamMapping.fromNormalized(s, ParamMapping.toNormalized(s, s.default))
            assertEquals("${s.key}: default round trip", s.default, back, 1e-9 * (s.max - s.min) + 1e-12)
        }
    }

    @Test
    fun firstLaunchSendsAreInRange() {
        val dly = channel.spec(MixKeys.DELAY_SEND)
        val rev = channel.spec(MixKeys.REVERB_SEND)
        for (s in DemoContent.SENDS.values + DemoContent.DRUM_SENDS + DemoContent.DEFAULT_SENDS) {
            assertTrue(s.toString(), s.delay in dly.min..dly.max)
            assertTrue(s.toString(), s.reverb in rev.min..rev.max)
        }
    }

    @Test
    fun theChannelCountMatchesTheContract() {
        assertEquals(12, MixLimits.CHANNELS)
        assertEquals(8, MixLimits.FIRST_TAPE_CHANNEL)
        assertEquals(4, MixLimits.TRACKS)
        assertEquals(256, MixLimits.OVERVIEW_BINS)
    }

    @Test
    fun theTwoTablesNeverShareAKey() {
        // MixValues tells channel strips from global ones by key.
        assertTrue((mix.rows.map { it.key }.toSet() intersect channel.rows.map { it.key }.toSet()).isEmpty())
    }
}
