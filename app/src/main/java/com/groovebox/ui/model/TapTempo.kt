package com.groovebox.ui.model

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Tap tempo on the top bar's BPM readout (build-22): three or more taps set the tempo from the
 * average interval of the last few taps. A tap more than [TIMEOUT_MS] after the last one starts
 * again; a bounce closer than [MIN_INTERVAL_MS] is ignored. The tapped tempo is folded into the
 * tempo range by octaves and snaps to exactly double or half the current tempo when close to it
 * ([snap]). A long-press sets [LONG_PRESS_BPM]. Pure Kotlin; times are monotonic ms.
 */
class TapTempo(private val maxTaps: Int = 6) {
    private val times = LongArray(maxTaps)

    /** Taps in the current run. */
    var count = 0
        private set

    /** One tap at [nowMs]; returns the tempo to set once there are 3+ taps, else null. */
    fun tap(nowMs: Long, currentBpm: Double): Double? {
        if (count > 0) {
            val gap = nowMs - times[count - 1]
            if (gap in 0 until MIN_INTERVAL_MS) return null
            if (gap > TIMEOUT_MS || gap < 0) count = 0
        }
        if (count == maxTaps) {
            System.arraycopy(times, 1, times, 0, maxTaps - 1)
            count--
        }
        times[count++] = nowMs
        if (count < MIN_TAPS) return null
        val interval = (times[count - 1] - times[0]).toDouble() / (count - 1)
        return snap(60_000.0 / interval, currentBpm)
    }

    fun reset() {
        count = 0
    }

    companion object {
        const val MIN_TAPS = 3
        /** 2 s: slower than 30 BPM is a new run. */
        const val TIMEOUT_MS = 2_000L
        /** A second touch this soon is a bounce (faster than 400 BPM). */
        const val MIN_INTERVAL_MS = 150L
        const val LONG_PRESS_BPM = 120.0
        /** How close (as a fraction) a tapped tempo must be to ×2 or ½ the current one to snap. */
        const val SNAP_TOLERANCE = 0.04

        /**
         * Folds [tapped] into SeqLimits.MIN_TEMPO..MAX_TEMPO by doubling or halving; then, if it is
         * within [SNAP_TOLERANCE] of double or half [current] (and that is in range), returns that
         * exactly; otherwise the folded tempo, rounded to 0.1 BPM.
         */
        fun snap(tapped: Double, current: Double): Double {
            var bpm = tapped
            while (bpm < SeqLimits.MIN_TEMPO) bpm *= 2
            while (bpm > SeqLimits.MAX_TEMPO) bpm /= 2
            for (target in doubleArrayOf(current * 2, current / 2)) {
                if (target < SeqLimits.MIN_TEMPO || target > SeqLimits.MAX_TEMPO) continue
                if (abs(bpm / target - 1.0) <= SNAP_TOLERANCE) return round(target)
            }
            return round(bpm).coerceIn(SeqLimits.MIN_TEMPO, SeqLimits.MAX_TEMPO)
        }

        private fun round(bpm: Double): Double = (bpm * 10).roundToLong() / 10.0
    }
}
