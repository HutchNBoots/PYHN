package com.groovebox.ui.model

import com.groovebox.engine.ParamSpec

/**
 * The main-layer LFO Rate strip with tempo sync built in (build-22, owner decision 1-2b): the
 * lower part of the strip ([0, SYNC_TOP)) snaps to the 13 beat divisions, 4 bars at the bottom
 * to 1/32T, and turns lfo.sync to "tempo"; a short detent ([SYNC_TOP, FREE_BOTTOM)) holds still;
 * above it Rate is free in Hz on its own curve and lfo.sync is "free". The engine is unchanged:
 * a division is sent as the Hz the engine snaps back to that division at the tempo
 * ([LfoSync.nearestDivision]). Only divisions whose Hz fit Rate's range at the tempo are offered
 * ([divisions], review 6), and a tempo change re-sends the chosen division's Hz ([retime],
 * review 5). Positions are the strip's 0..1. Pure Kotlin.
 */
object LfoDetent {
    const val SYNC_TOP = 0.48
    private const val DETENT_MID = 0.50
    const val FREE_BOTTOM = 0.52

    /** The divisions whose Hz fit [spec]'s range at [bpm] (all 13 between about 50 and 184 BPM). */
    fun divisions(spec: ParamSpec, bpm: Double): List<Int> {
        val tempo = tempo(bpm)
        val fit = LfoSync.DIVISION_BEATS.indices.filter { LfoSync.divisionHz(it, tempo).toDouble() in spec.min..spec.max }
        // A tempo so far out that nothing fits: offer the one nearest the range.
        return fit.ifEmpty { listOf(LfoSync.nearestDivision(spec.max.toFloat().coerceAtMost(LfoSync.divisionHz(0, tempo)), tempo)) }
    }

    data class Rate(val synced: Boolean, val division: Int, val hz: Double)

    fun fromPosition(n: Double, spec: ParamSpec, bpm: Double): Rate {
        val p = if (n.isNaN()) 0.0 else n.coerceIn(0.0, 1.0)
        val tempo = tempo(bpm)
        if (p < DETENT_MID) {
            val list = divisions(spec, bpm)
            val i = if (p >= SYNC_TOP) list.lastIndex else (p / SYNC_TOP * list.size).toInt().coerceIn(0, list.lastIndex)
            val d = list[i]
            return Rate(true, d, LfoSync.divisionHz(d, tempo).toDouble().coerceIn(spec.min, spec.max))
        }
        val u = ((p - FREE_BOTTOM) / (1.0 - FREE_BOTTOM)).coerceIn(0.0, 1.0)
        val hz = ParamMapping.fromNormalized(spec, u)
        return Rate(false, LfoSync.nearestDivision(hz.toFloat(), tempo), hz)
    }

    /** The strip position for a rate: synced shows its division's cell centre. */
    fun position(synced: Boolean, rateHz: Double, spec: ParamSpec, bpm: Double): Double =
        if (synced) {
            val list = divisions(spec, bpm)
            val d = LfoSync.nearestDivision(rateHz.toFloat(), tempo(bpm))
            val i = list.indexOf(d).takeIf { it >= 0 } ?: list.indices.minBy { kotlin.math.abs(list[it] - d) }
            (i + 0.5) / list.size * SYNC_TOP
        } else {
            FREE_BOTTOM + ParamMapping.toNormalized(spec, rateHz) * (1.0 - FREE_BOTTOM)
        }

    /**
     * A synced rate after the tempo moved from [oldBpm] to [newBpm]: the division it played at
     * [oldBpm], as Hz at [newBpm] (the engine re-derives the division from the Hz, so without
     * this a tempo change would drift it). Beyond the range, the nearest division that fits.
     */
    fun retime(rateHz: Double, spec: ParamSpec, oldBpm: Double, newBpm: Double): Double {
        val d = LfoSync.nearestDivision(rateHz.toFloat(), tempo(oldBpm))
        val list = divisions(spec, newBpm)
        val fit = if (d in list) d else list.minBy { kotlin.math.abs(it - d) }
        return LfoSync.divisionHz(fit, tempo(newBpm)).toDouble().coerceIn(spec.min, spec.max)
    }

    /** "1/8" below the detent, "2.0 Hz" above. */
    fun label(n: Double, spec: ParamSpec, bpm: Double): String {
        val r = fromPosition(n, spec, bpm)
        return if (r.synced) LfoSync.DIVISION_NAMES[r.division] else ParamMapping.format(spec, r.hz)
    }

    private fun tempo(bpm: Double): Float = (if (bpm.isNaN()) SeqLimits.DEFAULT_TEMPO else bpm).coerceIn(1.0, 1000.0).toFloat()
}
