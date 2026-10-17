package com.groovebox.ui.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

/**
 * The small LFO picture on the Synth display while Mod is the section (build-22, owner decision
 * 1-2b; the three main pictures stay). x 0..1 is one second free, or one bar synced; y −1..1.
 * Shapes as lfo.wave (sine, tri, square, S&H); [depth] scales it (a faint floor keeps the shape
 * readable at 0); [fadeS] (lfo.delay) ramps it in. A rate under 1 Hz stretches the window to one cycle. Only needs to move the right way. Pure Kotlin.
 */
object LfoTrace {
    /** More cycles than this would read as noise. */
    const val MAX_CYCLES = 16.0
    private const val MIN_AMP = 0.12

    /** Cycles across the picture: Rate × 1 s, or a bar's worth of the synced division. */
    fun cycles(rateHz: Double, synced: Boolean, bpm: Double): Double {
        val c = if (synced) {
            val tempo = (if (bpm.isNaN()) SeqLimits.DEFAULT_TEMPO else bpm).coerceIn(1.0, 1000.0).toFloat()
            4.0 / LfoSync.DIVISION_BEATS[LfoSync.nearestDivision(rateHz.toFloat(), tempo)]
        } else {
            rateHz
        }
        return c.coerceIn(1.0, MAX_CYCLES)
    }

    /** The window's length in seconds (for the fade): a bar synced, else 1 s, or one cycle when slower. */
    fun windowS(rateHz: Double, synced: Boolean, bpm: Double): Double =
        if (synced) 4 * 60.0 / bpm.coerceIn(1.0, 1000.0) else maxOf(1.0, 1.0 / rateHz.coerceAtLeast(1e-3))

    fun points(shape: Int, cycles: Double, depth: Double, fadeS: Double, windowS: Double, n: Int = 160): List<DisplayShapes.Point> {
        val amp = MIN_AMP + (1 - MIN_AMP) * depth.coerceIn(0.0, 1.0)
        val fade = (fadeS / windowS.coerceAtLeast(1e-3)).coerceIn(0.0, 1.0)
        return List(n + 1) { i ->
            val u = i.toDouble() / n
            val ph = u * cycles
            val f = ph - floor(ph)
            val y = when (shape) {
                1 -> 1 - 4 * abs(f - 0.5)
                2 -> if (f < 0.5) 1.0 else -1.0
                3 -> sh(floor(ph).toInt())
                else -> sin(2 * PI * ph)
            }
            val ramp = if (fade <= 0.0) 1.0 else (u / fade).coerceAtMost(1.0)
            DisplayShapes.Point(u, y * amp * ramp)
        }
    }

    /** A fixed pseudo-random level per cycle, so S&H looks like steps and never flickers. */
    private fun sh(cycle: Int): Double {
        var x = cycle * 0x9E3779B1.toInt() + 0x7F4A7C15
        x = x xor (x ushr 15)
        x *= 0x2C1B3C6D
        x = x xor (x ushr 12)
        return ((x and 0xFFFF) / 65535.0) * 2 - 1
    }
}
