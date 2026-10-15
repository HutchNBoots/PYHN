package com.groovebox.ui.model

import com.groovebox.engine.Curve
import com.groovebox.engine.ParamSpec
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

class ParamMappingTest {
    private fun spec(min: Double, max: Double, curve: Curve, unit: String = "", default: Double = min) =
        ParamSpec(0, "test", "Test", min, max, default, curve, unit)

    private fun def(key: String) = SynthParamsDef.spec(key)
    private fun fmt(key: String, value: Double) = ParamMapping.format(def(key), value)

    // --- curves ---

    @Test
    fun linearMapsStraight() {
        val s = spec(-1.0, 1.0, Curve.Linear)
        assertEquals(-1.0, ParamMapping.fromNormalized(s, 0.0), 1e-12)
        assertEquals(0.0, ParamMapping.fromNormalized(s, 0.5), 1e-12)
        assertEquals(1.0, ParamMapping.fromNormalized(s, 1.0), 1e-12)
        assertEquals(0.75, ParamMapping.toNormalized(s, 0.5), 1e-12)
    }

    @Test
    fun expIsGeometric() {
        val s = spec(20.0, 18000.0, Curve.Exp)
        assertEquals(20.0, ParamMapping.fromNormalized(s, 0.0), 1e-9)
        assertEquals(18000.0, ParamMapping.fromNormalized(s, 1.0), 1e-6)
        assertEquals(sqrt(20.0 * 18000.0), ParamMapping.fromNormalized(s, 0.5), 1e-6)
        assertEquals(0.5, ParamMapping.toNormalized(s, sqrt(20.0 * 18000.0)), 1e-12)
    }

    @Test
    fun pow3IsCubic() {
        val s = spec(0.0, 2.0, Curve.Pow3)
        assertEquals(0.25, ParamMapping.fromNormalized(s, 0.5), 1e-12)
        assertEquals(0.5, ParamMapping.toNormalized(s, 0.25), 1e-12)
        assertEquals(0.0, ParamMapping.toNormalized(s, 0.0), 1e-12)
        assertEquals(2.0, ParamMapping.fromNormalized(s, 1.0), 1e-12)
    }

    @Test
    fun intRoundsToWholeNumbers() {
        val s = spec(-2.0, 2.0, Curve.Int)
        assertEquals(0.0, ParamMapping.fromNormalized(s, 0.5), 0.0)
        assertEquals(0.0, ParamMapping.fromNormalized(s, 0.6), 0.0) // -2 + 2.4 = 0.4
        assertEquals(1.0, ParamMapping.fromNormalized(s, 0.65), 0.0) // 0.6
        assertEquals(-2.0, ParamMapping.fromNormalized(s, 0.0), 0.0)
        assertEquals(0.75, ParamMapping.toNormalized(s, 1.0), 1e-12)
    }

    @Test
    fun enumIndexesAreEvenlySpaced() {
        val s = def("osc.voiceMode") // poly|mono|unison
        assertEquals(0.0, ParamMapping.fromNormalized(s, 0.0), 0.0)
        assertEquals(1.0, ParamMapping.fromNormalized(s, 0.5), 0.0)
        assertEquals(1.0, ParamMapping.fromNormalized(s, 0.74), 0.0)
        assertEquals(2.0, ParamMapping.fromNormalized(s, 0.76), 0.0)
        assertEquals(2.0, ParamMapping.fromNormalized(s, 1.0), 0.0)
        assertEquals(0.5, ParamMapping.toNormalized(s, 1.0), 1e-12)
        assertEquals(1.0, ParamMapping.toNormalized(s, 2.0), 1e-12)
    }

    @Test
    fun clampsBothWays() {
        val s = spec(20.0, 18000.0, Curve.Exp)
        assertEquals(20.0, ParamMapping.fromNormalized(s, -0.5), 1e-9)
        assertEquals(18000.0, ParamMapping.fromNormalized(s, 1.5), 1e-6)
        assertEquals(0.0, ParamMapping.toNormalized(s, 1.0), 0.0)
        assertEquals(1.0, ParamMapping.toNormalized(s, 1e9), 0.0)
        assertEquals(0.0, ParamMapping.toNormalized(s, Double.NaN), 0.0)
    }

    @Test
    fun continuousCurvesRoundTrip() {
        for (curve in listOf(Curve.Linear, Curve.Exp, Curve.Pow3)) {
            val s = if (curve == Curve.Exp) spec(0.001, 10.0, curve) else spec(-50.0, 50.0, curve)
            for (i in 0..20) {
                val n = i / 20.0
                val v = ParamMapping.fromNormalized(s, n)
                assertEquals("$curve n=$n", n, ParamMapping.toNormalized(s, v), 1e-9)
            }
        }
    }

    @Test
    fun steppedCurvesRoundTripEveryStep() {
        for (key in listOf("osc.octave", "osc.voiceMode", "lfo.wave", "lfo.dest", "lfo.sync", "lfo.retrig")) {
            val s = def(key)
            var v = s.min
            while (v <= s.max) {
                assertEquals("$key v=$v", v, ParamMapping.fromNormalized(s, ParamMapping.toNormalized(s, v)), 0.0)
                v += 1.0
            }
        }
    }

    // --- display ---

    @Test
    fun hertz() {
        assertEquals("880 Hz", fmt("flt.cutoff", 880.0))
        assertEquals("20 Hz", fmt("flt.cutoff", 20.0))
        assertEquals("2.4k", fmt("flt.cutoff", 2400.0))
        assertEquals("1.0k", fmt("flt.cutoff", 999.7))
        assertEquals("18k", fmt("flt.cutoff", 18000.0))
        assertEquals("10k", fmt("flt.cutoff", 9960.0))
        assertEquals("2.0 Hz", fmt("lfo.rate", 2.0))
        assertEquals("0.05 Hz", fmt("lfo.rate", 0.05))
        assertEquals("30 Hz", fmt("lfo.rate", 30.0))
    }

    @Test
    fun seconds() {
        assertEquals("4 ms", fmt("amp.attack", 0.004))
        assertEquals("1 ms", fmt("amp.attack", 0.001))
        assertEquals("250 ms", fmt("amp.release", 0.25))
        assertEquals("1.2 s", fmt("amp.release", 1.2))
        assertEquals("1.0 s", fmt("amp.release", 0.9996))
        assertEquals("10 s", fmt("amp.release", 10.0))
        assertEquals("0 ms", fmt("osc.glide", 0.0))
    }

    @Test
    fun percent() {
        assertEquals("32%", fmt("flt.reso", 0.32))
        assertEquals("0%", fmt("osc.sub", 0.0))
        assertEquals("100%", fmt("osc.sub", 1.0))
        assertEquals("50%", fmt("osc.pw", 0.5))
    }

    @Test
    fun bipolarPercentIsSigned() {
        assertEquals("+40%", fmt("flt.envAmt", 0.4))
        assertEquals("−40%", fmt("flt.envAmt", -0.4))
        assertEquals("0%", fmt("flt.envAmt", 0.0))
        assertEquals("0%", fmt("flt.envAmt", -0.001))
        assertEquals("+100%", fmt("flt.envAmt", 1.0))
    }

    @Test
    fun centsDecibelsAndOctaves() {
        assertEquals("+12c", fmt("osc.fine", 12.0))
        assertEquals("−12c", fmt("osc.fine", -12.0))
        assertEquals("0c", fmt("osc.fine", 0.0))
        assertEquals("6 dB", fmt("flt.drive", 6.0))
        assertEquals("+1", fmt("osc.octave", 1.0))
        assertEquals("−2", fmt("osc.octave", -2.0))
        assertEquals("0", fmt("osc.octave", 0.0))
    }

    @Test
    fun enumLabelsAreTitleCased() {
        assertEquals("Poly", fmt("osc.voiceMode", 0.0))
        assertEquals("Unison", fmt("osc.voiceMode", 2.0))
        assertEquals("S&H", fmt("lfo.wave", 3.0))
        assertEquals("Tri", fmt("lfo.wave", 1.0))
        assertEquals("PW", fmt("lfo.dest", 2.0))
        assertEquals("Cutoff", fmt("lfo.dest", 1.0))
        assertEquals("Tempo", fmt("lfo.sync", 1.0))
        assertEquals("On", fmt("lfo.retrig", 1.0))
        // Out-of-range indexes are clamped rather than crashing.
        assertEquals("Amp", fmt("lfo.dest", 7.0))
    }

    @Test
    fun waveShowsNearestShapeOrTheMorph() {
        assertEquals("Saw", fmt("osc.wave", 0.0))
        assertEquals("Square", fmt("osc.wave", 1.0))
        assertEquals("Square", fmt("osc.wave", 0.97))
        assertEquals("Square", fmt("osc.wave", 1.04))
        assertEquals("Sine", fmt("osc.wave", 3.0))
        assertEquals("Saw›Sq", fmt("osc.wave", 0.5))
        assertEquals("Tri›Sine", fmt("osc.wave", 2.5))
    }

    @Test
    fun unknownUnitFallsBackToNumberAndUnit() {
        assertEquals("0.50 x", ParamMapping.format(spec(0.0, 1.0, Curve.Linear, "x"), 0.5))
        assertEquals("0.50", ParamMapping.format(spec(0.0, 1.0, Curve.Linear, ""), 0.5))
    }
}
