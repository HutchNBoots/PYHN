package com.groovebox.ui.model

import kotlin.math.abs
import kotlin.math.log2

/**
 * The tempo-synced LFO's beat divisions, copied from engine/core/src/Lfo.cpp (same table, same
 * float maths), so the Rate strip shows what the engine plays when lfo.sync is "tempo": the
 * division whose frequency at the current tempo is nearest, in octaves, to the Rate value
 * (at 120 BPM, 2 Hz is 1/4 and 8 Hz is 1/16; a tie goes to the longer division).
 * Pure Kotlin.
 */
object LfoSync {
    /** Beats per LFO cycle, 4 bars down to 1/32 triplet (Lfo::kDivisionBeats). */
    val DIVISION_BEATS = floatArrayOf(
        16.0f, 8.0f, 4.0f, 2.0f, 4.0f / 3.0f, 1.0f, 2.0f / 3.0f, 0.5f, 1.0f / 3.0f, 0.25f, 1.0f / 6.0f, 0.125f, 1.0f / 12.0f,
    )

    /** Lfo::kDivisionNames. */
    val DIVISION_NAMES = listOf("4 bars", "2 bars", "1 bar", "1/2", "1/2T", "1/4", "1/4T", "1/8", "1/8T", "1/16", "1/16T", "1/32", "1/32T")

    /** lfo.sync's index of "tempo". */
    const val SYNC_TEMPO = 1

    fun divisionHz(division: Int, bpm: Float): Float {
        val d = division.coerceIn(0, DIVISION_BEATS.size - 1)
        return bpm / (60.0f * DIVISION_BEATS[d])
    }

    /** Lfo::nearestDivision. */
    fun nearestDivision(rateHz: Float, bpm: Float): Int {
        var best = 0
        var bestDistance = 1e30f
        val target = log2(if (rateHz > 1e-6f) rateHz else 1e-6f)
        for (i in DIVISION_BEATS.indices) {
            val d = abs(log2(divisionHz(i, bpm)) - target)
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return best
    }

    /** "1/16" for the Rate strip; [bpm] as the engine clamps it (SynthEngine::setTempo). */
    fun label(rateHz: Double, bpm: Double): String {
        val tempo = (if (bpm.isNaN()) SeqLimits.DEFAULT_TEMPO else bpm).coerceIn(1.0, 1000.0).toFloat()
        return DIVISION_NAMES[nearestDivision(rateHz.toFloat(), tempo)]
    }
}
