package com.groovebox.ui.model

import com.groovebox.engine.NativeEngine
import com.groovebox.engine.ParamSpec
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Mixer and tape limits from the engine contract, readable without loading the library. */
object MixLimits {
    const val CHANNELS = NativeEngine.MIXER_CHANNEL_COUNT
    const val FIRST_TAPE_CHANNEL = NativeEngine.FIRST_TAPE_CHANNEL
    const val TRACKS = NativeEngine.TAPE_TRACK_COUNT
    const val OVERVIEW_BINS = NativeEngine.TAPE_OVERVIEW_BINS

    fun tapeChannel(track: Int): Int = FIRST_TAPE_CHANNEL + track
}

/**
 * What one colour strip on the Tape or Mixer screen drives: a global parameter of
 * MixParams.def, a per-channel parameter of ChannelParams.def (on the channel the screen has
 * selected), or the Tape screen's Commit action. [label] replaces the table's label.
 */
sealed interface StripRef {
    data class Mix(val key: String, val label: String? = null) : StripRef
    data class Channel(val key: String) : StripRef
    data object Commit : StripRef
}

/** Keys of MixParams.def and ChannelParams.def that the UI uses. */
object MixKeys {
    const val DELAY_TIME = "dly.time"
    const val DELAY_SYNC = "dly.sync"
    const val DELAY_DIV = "dly.div"
    const val DELAY_FEEDBACK = "dly.fb"
    const val DELAY_LOW_CUT = "dly.lowCut"
    const val DELAY_HIGH_CUT = "dly.highCut"
    const val DELAY_RETURN = "dly.return"
    const val REVERB_SIZE = "rev.size"
    const val REVERB_DECAY = "rev.decay"
    const val REVERB_DAMP = "rev.damp"
    const val REVERB_RETURN = "rev.return"
    const val MASTER = "mst.volume"
    const val LOOP_START = "tape.loopStart"
    const val LOOP_LEN = "tape.loopLen"
    const val SPEED = "tape.speed"
    const val REVERSE = "tape.reverse"
    const val DUB = "tape.dub"

    const val VOLUME = "ch.volume"
    const val PAN = "ch.pan"
    const val MUTE = "ch.mute"
    const val DELAY_SEND = "ch.dlySend"
    const val REVERB_SEND = "ch.revSend"

    /** tape.speed's index of 1x, the only speed that records (with reverse off). */
    const val SPEED_NORMAL = 1

    /** dly.sync's index of "tempo". */
    const val SYNC_TEMPO = 1
}

/**
 * Strip and display text for mixer, FX and tape parameters. Falls back to [ParamMapping]
 * for anything without a rule of its own. Pure Kotlin.
 */
object MixFormat {
    private const val MINUS = '−'

    fun format(spec: ParamSpec, value: Double): String = when {
        spec.key == MixKeys.LOOP_START -> "bar ${value.roundToInt() + 1}"
        spec.key == MixKeys.LOOP_LEN -> bars(value.roundToInt())
        spec.key == MixKeys.SPEED -> speed(spec, value)
        spec.key == MixKeys.DELAY_DIV -> option(spec, value)
        spec.key == MixKeys.PAN -> pan(value)
        spec.unit == "dB" -> decibels(value, spec.min)
        else -> ParamMapping.format(spec, value)
    }

    /** tape.loopLen: 0 is the whole tape. */
    fun bars(n: Int): String = when {
        n <= 0 -> "All"
        n == 1 -> "1 bar"
        else -> "$n bars"
    }

    /** "−3 dB", "+6 dB", "0 dB"; the table's minimum is silence, "−∞ dB". */
    fun decibels(value: Double, min: Double = -60.0): String {
        if (value <= min) return "$MINUS∞ dB"
        val n = value.roundToInt()
        return when {
            n > 0 -> "+$n dB"
            n < 0 -> "$MINUS${-n} dB"
            else -> "0 dB"
        }
    }

    /** Equal-power pan −1..1 as "L40", "C", "R100". */
    fun pan(value: Double): String {
        val pct = (abs(value) * 100.0).roundToInt()
        return when {
            pct == 0 -> "C"
            value < 0 -> "L$pct"
            else -> "R$pct"
        }
    }

    /** "0.5×", "1×", "2×" from the table's "0.5x|1x|2x". */
    private fun speed(spec: ParamSpec, value: Double): String = option(spec, value).replace('x', '×')

    private fun option(spec: ParamSpec, value: Double): String {
        val options = spec.options
        if (options.isEmpty()) return String.format(Locale.ROOT, "%.0f", value)
        return options[(value - spec.min).roundToInt().coerceIn(0, options.lastIndex)]
    }

    /**
     * The delay time as heard: the division's name when Sync is tempo, else seconds
     * ("1/8D", "375 ms", "1.2 s").
     */
    fun delayTime(synced: Boolean, division: ParamSpec, divisionValue: Double, time: ParamSpec, seconds: Double): String =
        if (synced) option(division, divisionValue) else ParamMapping.format(time, seconds)
}
