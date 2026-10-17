package com.groovebox.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SynthLayoutTest {
    private fun keys(section: SynthSection, shift: Boolean) = SynthLayout.keys(section, shift)

    @Test
    fun sectionsInRailOrder() {
        assertEquals(listOf("Osc", "Filter", "Env", "Mod", "Play"), SynthSection.entries.map { it.label })
    }

    @Test
    fun oscLayers() {
        assertEquals(listOf("osc.wave", "osc.pw", "osc.sub", "osc.noise"), keys(SynthSection.Osc, false))
        assertEquals(listOf("osc.octave", "osc.fine", "osc.glide", "osc.voiceMode"), keys(SynthSection.Osc, true))
    }

    @Test
    fun filterLayers() {
        assertEquals(listOf("flt.cutoff", "flt.reso", "flt.envAmt", "flt.keyTrack"), keys(SynthSection.Filter, false))
        assertEquals(listOf("flt.drive", "flt.hpf", "flt.velAmt", "flt.lfoAmt"), keys(SynthSection.Filter, true))
    }

    @Test
    fun envLayers() {
        assertEquals(listOf("amp.attack", "amp.decay", "amp.sustain", "amp.release"), keys(SynthSection.Env, false))
        assertEquals(listOf("fenv.attack", "fenv.decay", "fenv.sustain", "fenv.release"), keys(SynthSection.Env, true))
    }

    @Test
    fun modLayersWithReservedFourthShiftSlot() {
        assertEquals(listOf("lfo.rate", "lfo.depth", "lfo.wave", "lfo.dest"), keys(SynthSection.Mod, false))
        assertEquals(listOf("lfo.sync", "lfo.delay", "lfo.retrig", null), keys(SynthSection.Mod, true))
    }

    @Test
    fun everyLayerHasFourStripsAndNoKeyRepeats() {
        for (s in SynthSection.entries) for (shift in listOf(false, true)) {
            assertEquals(4, keys(s, shift).size)
            val named = keys(s, shift).filterNotNull()
            assertEquals("$s $shift", named.size, named.toSet().size)
        }
        assertEquals(31, SynthLayout.allKeys.size)
        assertEquals(SynthLayout.allKeys.size, SynthLayout.allKeys.toSet().size)
    }

    // --- build-22 ---

    @Test
    fun thePlayRailItemHoldsKeyScaleOctaveAndMode() {
        assertEquals(listOf("play.key", "play.scale", "play.octave", "osc.voiceMode"), keys(SynthSection.Play, false))
        // Its Shift layer is kept for the arpeggiator (build-24).
        assertEquals(listOf<String?>(null, null, null, null), keys(SynthSection.Play, true))
    }

    @Test
    fun allKeysAreTheEngineParamsOnly() {
        assertTrue(SynthLayout.allKeys.none { it.startsWith("play.") })
        assertTrue(SynthLayout.isPlayKey("play.octave"))
        assertFalse(SynthLayout.isPlayKey("osc.octave"))
    }

    @Test
    fun clashingLabelsAreRenamed() {
        assertEquals("EnvAmt", SynthLayout.label("flt.envAmt", "Env"))
        assertEquals("KeyTrk", SynthLayout.label("flt.keyTrack", "Key"))
        assertEquals("Fade", SynthLayout.label("lfo.delay", "Delay"))
        assertEquals("Cutoff", SynthLayout.label("flt.cutoff", "Cutoff"))
    }

    @Test
    fun glideIsDimmedInPolyOnly() {
        assertTrue(SynthLayout.dimmed("osc.glide", voiceMode = 0))
        assertFalse(SynthLayout.dimmed("osc.glide", voiceMode = 1))
        assertFalse(SynthLayout.dimmed("osc.glide", voiceMode = 2))
        assertFalse(SynthLayout.dimmed("osc.fine", voiceMode = 0))
    }

    @Test
    fun unisonSaysItIsMono() {
        assertEquals(listOf("Poly", "Mono", "Unison (mono)"), (0..2).map { SynthLayout.voiceModeLabel(it) })
        assertEquals("Unison (mono)", SynthLayout.voiceModeLabel(7))
    }

    @Test
    fun sectionsOfAParam() {
        assertEquals(SynthSection.Filter, SynthLayout.sectionOf("flt.hpf"))
        assertEquals(SynthSection.Osc, SynthLayout.sectionOf("osc.voiceMode"))
        assertEquals(SynthSection.Mod, SynthLayout.sectionOf("lfo.sync"))
        assertEquals(null, SynthLayout.sectionOf("nope"))
    }
}
