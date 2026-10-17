package com.groovebox.ui.model

import com.groovebox.engine.ParamSpec

/**
 * The main-layer LFO Rate strip with tempo sync built in (build-22, owner decision 1-2b): the
 * lower part of the strip ([0, SYNC_TOP)) snaps to the 13 beat divisions, 4 bars at the bottom
 * to 1/32T, and turns lfo.sync to "tempo"; a short detent ([SYNC_TOP, FREE_BOTTOM)) holds still;
 * above it Rate is free in Hz on its own curve and lfo.sync is "free". The engine is unchanged:
 * a division is sent as the Hz the engine snaps back to that division at the tempo
 * ([LfoSync.nearestDivision]). Positions are the strip's 0..1. Pure Kotlin.
 */
object LfoDetent {
    const val SYNC_TOP = 0.48
    private const val DETENT_MID = 0.50
    const val FREE_BOTTOM = 0.52

    private val DIVISIONS = LfoSync.DIVISION_BEATS.size

    data class Rate(val synced: Boolean, val division: Int, val hz: Double)

    fun fromPosition(n: Double, spec: ParamSpec, bpm: Double): Rate {
        val p = if (n.isNaN()) 0.0 else n.coerceIn(0.0, 1.0)
        val tempo = tempo(bpm)
        if (p < DETENT_MID) {
            val d = if (p >= SYNC_TOP) DIVISIONS - 1 else (p / SYNC_TOP * DIVISIONS).toInt().coerceIn(0, DIVISIONS - 1)
            return Rate(true, d, LfoSync.divisionHz(d, tempo).toDouble())
        }
        val u = ((p - FREE_BOTTOM) / (1.0 - FREE_BOTTOM)).coerceIn(0.0, 1.0)
        val hz = ParamMapping.fromNormalized(spec, u)
        return Rate(false, LfoSync.nearestDivision(hz.toFloat(), tempo), hz)
    }

    /** The strip position for a rate: synced shows its division's cell centre. */
    fun position(synced: Boolean, rateHz: Double, spec: ParamSpec, bpm: Double): Double =
        if (synced) {
            val d = LfoSync.nearestDivision(rateHz.toFloat(), tempo(bpm))
            (d + 0.5) / DIVISIONS * SYNC_TOP
        } else {
            FREE_BOTTOM + ParamMapping.toNormalized(spec, rateHz) * (1.0 - FREE_BOTTOM)
        }

    /** "1/8" below the detent, "2.0 Hz" above. */
    fun label(n: Double, spec: ParamSpec, bpm: Double): String {
        val r = fromPosition(n, spec, bpm)
        return if (r.synced) LfoSync.DIVISION_NAMES[r.division] else ParamMapping.format(spec, r.hz)
    }

    private fun tempo(bpm: Double): Float = (if (bpm.isNaN()) SeqLimits.DEFAULT_TEMPO else bpm).coerceIn(1.0, 1000.0).toFloat()
}
