package com.groovebox.ui.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SynthLayoutTest {
    private fun keys(section: SynthSection, shift: Boolean) = SynthLayout.keys(section, shift)

    @Test
    fun sectionsInRailOrder() {
        assertEquals(listOf("Osc", "Filter", "Env", "Mod"), SynthSection.entries.map { it.label })
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
        }
        assertEquals(31, SynthLayout.allKeys.size)
        assertEquals(SynthLayout.allKeys.size, SynthLayout.allKeys.toSet().size)
    }
}
