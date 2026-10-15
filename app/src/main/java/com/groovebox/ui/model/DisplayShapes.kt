package com.groovebox.ui.model

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The math behind the display's drawings, kept pure so it can be tested on the host.
 * These are pictures of the sound, not the DSP: they only need to move the right way.
 */
object DisplayShapes {
    data class Point(val x: Double, val y: Double)

    /** Ceiling of the drawn filter response, so self-oscillation stays on screen. */
    const val MAX_DB = 24.0

    /**
     * One sample (−1..1) of the oscillator at [phase] (0..1), for [wave] 0..3 morphing
     * saw → square → triangle → sine, with [pulseWidth] applied to the square.
     */
    fun wave(wave: Double, pulseWidth: Double, phase: Double): Double {
        val w = wave.coerceIn(0.0, 3.0)
        val p = phase - floor(phase)
        val lo = floor(w).toInt().coerceAtMost(2)
        val t = w - lo
        return shape(lo, pulseWidth, p) * (1 - t) + shape(lo + 1, pulseWidth, p) * t
    }

    private fun shape(index: Int, pw: Double, p: Double): Double = when (index) {
        0 -> 2 * p - 1
        1 -> if (p < pw) 1.0 else -1.0
        2 -> if (p < 0.5) 4 * p - 1 else 3 - 4 * p
        else -> sin(2 * PI * p)
    }

    /**
     * Gain in dB at [freqHz] of a 4-pole ladder low-pass at [cutoffHz] with resonance
     * [reso] (0..1, feedback 0..4), passband-normalised, after a one-pole high-pass at [hpfHz].
     * Clamped to [MAX_DB] above and −120 dB below.
     */
    fun filterDb(freqHz: Double, cutoffHz: Double, reso: Double, hpfHz: Double): Double {
        val x = freqHz / cutoffHz
        val k = 4.0 * reso.coerceIn(0.0, 1.0)
        // (1 + jx)^4 + k
        var re = 1.0
        var im = 0.0
        repeat(4) {
            val r = re - im * x
            im = re * x + im
            re = r
        }
        re += k
        val lowPass = (1 + k) / hypot(re, im)
        val xh = freqHz / hpfHz
        val highPass = xh / sqrt(1 + xh * xh)
        val g = lowPass * highPass
        if (g.isNaN()) return MAX_DB // 0/0 cannot happen for k <= 4, but stay on screen
        if (g <= 0.0) return -120.0
        return (20 * log10(g)).coerceIn(-120.0, MAX_DB)
    }

    /**
     * The envelope's outline in a unit box: x 0..1 is time (each stage's width grows with the
     * log of its duration, so 1 ms and 10 s both read), y 0..1 is level. Attack, decay,
     * a fixed-width sustain hold, then release.
     */
    fun envelope(attack: Double, decay: Double, sustain: Double, release: Double, pointsPerStage: Int = 16): List<Point> {
        val s = sustain.coerceIn(0.0, 1.0)
        val wa = stageWidth(attack)
        val wd = stageWidth(decay)
        val wr = stageWidth(release)
        val total = wa + wd + SUSTAIN_WIDTH + wr
        val pts = ArrayList<Point>(pointsPerStage * 3 + 2)
        var x0 = 0.0
        pts += Point(0.0, 0.0)
        // Attack: a slightly convex rise, as an RC charge towards above full.
        for (i in 1..pointsPerStage) {
            val u = i.toDouble() / pointsPerStage
            pts += Point((x0 + wa * u) / total, curve(u, 1.5).let { 1 - it })
        }
        x0 += wa
        for (i in 1..pointsPerStage) {
            val u = i.toDouble() / pointsPerStage
            pts += Point((x0 + wd * u) / total, s + (1 - s) * curve(u, 4.0))
        }
        x0 += wd + SUSTAIN_WIDTH
        pts += Point(x0 / total, s)
        for (i in 1..pointsPerStage) {
            val u = i.toDouble() / pointsPerStage
            pts += Point((x0 + wr * u) / total, s * curve(u, 4.0))
        }
        return pts
    }

    /** Falls exactly from 1 at u = 0 to 0 at u = 1, exponentially with rate [k]. */
    private fun curve(u: Double, k: Double): Double = (exp(-k * u) - exp(-k)) / (1 - exp(-k))

    private fun stageWidth(seconds: Double): Double =
        (log10(1 + 1000 * seconds.coerceIn(0.0, 10.0)) / log10(10_001.0)).coerceAtLeast(MIN_STAGE_WIDTH)

    private const val SUSTAIN_WIDTH = 0.25
    private const val MIN_STAGE_WIDTH = 0.02
}
