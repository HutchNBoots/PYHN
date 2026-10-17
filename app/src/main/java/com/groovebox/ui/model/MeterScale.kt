package com.groovebox.ui.model

import com.groovebox.engine.NativeEngine
import com.groovebox.engine.ParamSpec
import kotlin.math.log10

/**
 * The Mixer screen's level meters: which engine meter each pad shows, how a reading becomes a
 * bar height, and the engine's meter buffer read into per-pad values (layout: [NativeEngine.meters]).
 * Pure Kotlin.
 *
 * The scale is the channel fader's: dB from [MIN_DB] (bottom, also silence) to [MAX_DB] (top),
 * linear in dB, so a 0 dBFS peak sits exactly where a fader at 0 dB does.
 */
object MeterScale {
    const val MIN_DB = -60.0
    const val MAX_DB = 6.0

    /** Engine meter behind a pad: its channel for slots and tape tracks, then the returns and the master. */
    fun meterIndex(pad: MixerPad): Int = when (pad) {
        is MixerPad.Slot -> pad.slot
        is MixerPad.Track -> MixLimits.tapeChannel(pad.track)
        MixerPad.Delay -> NativeEngine.METER_DELAY
        MixerPad.Reverb -> NativeEngine.METER_REVERB
        MixerPad.Master -> NativeEngine.METER_MASTER
    }

    /** A linear peak amplitude in dBFS; -infinity for silence (or a bad reading). */
    fun toDb(level: Float): Double =
        if (level.isNaN() || level <= 0f) Double.NEGATIVE_INFINITY else 20.0 * log10(level.toDouble())

    /** A dB value as a 0..1 bar height on the fader's scale. */
    fun dbToHeight(db: Double): Float {
        if (db.isNaN()) return 0f
        return ((db.coerceIn(MIN_DB, MAX_DB) - MIN_DB) / (MAX_DB - MIN_DB)).toFloat()
    }

    /** A linear peak amplitude as a 0..1 bar height: 0 at or below -60 dBFS, 1 at +6 dBFS and above. */
    fun height(level: Float): Float = dbToHeight(toDb(level))

    /**
     * Where a column's setting marker sits on its meter (v1 fix 4): a dB setting (fader,
     * master) at its own dB; any other (the DLY / REV returns, linear gains 0..1) at the dB that
     * gain is, so a return of 50% sits at -6 dB, where a peak of 0.5 draws.
     */
    fun markerHeight(spec: ParamSpec, value: Double): Float =
        if (spec.unit == "dB") dbToHeight(value) else height(value.toFloat())
}

/**
 * The latest meter readings, indexed by engine meter ([MeterScale.meterIndex]). [read] copies
 * them out of a buffer filled by [NativeEngine.meters]. Pure Kotlin; the screen wraps it in
 * state that only the draw phase reads.
 */
class MeterReadings {
    val levels = FloatArray(NativeEngine.METER_COUNT)
    val clips = BooleanArray(NativeEngine.METER_COUNT)

    /** Reads [buffer] (layout: [NativeEngine.meters]); returns whether anything changed. A short
     *  buffer is ignored. */
    fun read(buffer: FloatArray): Boolean {
        if (buffer.size < NativeEngine.METER_BUFFER_SIZE) return false
        var changed = false
        for (i in 0 until NativeEngine.METER_COUNT) {
            val level = buffer[i].let { if (it.isNaN() || it < 0f) 0f else it }
            val clip = buffer[NativeEngine.METER_CLIP_OFFSET + i] >= 0.5f
            if (level != levels[i] || clip != clips[i]) changed = true
            levels[i] = level
            clips[i] = clip
        }
        return changed
    }

    fun height(pad: MixerPad): Float = MeterScale.height(levels[MeterScale.meterIndex(pad)])
    fun clipped(pad: MixerPad): Boolean = clips[MeterScale.meterIndex(pad)]
}
