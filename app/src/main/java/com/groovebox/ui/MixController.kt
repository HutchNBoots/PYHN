package com.groovebox.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.groovebox.engine.NativeEngine
import com.groovebox.engine.ParamSpec
import com.groovebox.ui.model.DemoContent
import com.groovebox.ui.model.MeterReadings
import com.groovebox.ui.model.MixFormat
import com.groovebox.ui.model.MixKeys
import com.groovebox.ui.model.MixLimits
import com.groovebox.ui.model.MixerLayout
import com.groovebox.ui.model.MixerPad
import com.groovebox.ui.model.MixerPadAction
import com.groovebox.ui.model.StripRef
import com.groovebox.ui.model.TapeStrips
import kotlin.math.roundToInt

/**
 * The UI's copy of the mixer, FX and tape settings (MixParams.def) and of every mixer
 * channel's settings (ChannelParams.def, 12 channels). Like the synth slots, it is the
 * source of truth for the strips: a change updates it and calls the engine at once; it is
 * re-read from the engine after start. One state per value, so a drag only recomposes what
 * shows it. [onChange] runs after each change (autosave). Main thread only (NativeEngine's rule).
 */
@Stable
class MixValues(private val onChange: () -> Unit = {}) {
    val mixParams: List<ParamSpec> = NativeEngine.mixParams
    val channelParams: List<ParamSpec> = NativeEngine.channelParams

    private val mixByKey = mixParams.associateBy { it.key }
    private val channelByKey = channelParams.associateBy { it.key }
    private val mix = mixParams.map { mutableDoubleStateOf(it.default) }
    private val channels = List(MixLimits.CHANNELS) { channelParams.map { mutableDoubleStateOf(it.default) } }

    fun mixSpec(key: String): ParamSpec? = mixByKey[key]
    fun channelSpec(key: String): ParamSpec? = channelByKey[key]
    /** The two tables' keys never overlap ("ch." against "dly.", "rev.", "mst.", "tape."). */
    fun isChannelSpec(spec: ParamSpec): Boolean = spec.key in channelByKey

    fun mix(spec: ParamSpec): Double = mix[spec.id].doubleValue
    fun mix(key: String): Double = mixByKey[key]?.let(::mix) ?: 0.0

    fun setMix(spec: ParamSpec, value: Double) {
        val v = value.coerceIn(spec.min, spec.max)
        val state = mix[spec.id]
        if (state.doubleValue == v) return
        state.doubleValue = v
        NativeEngine.setMixParam(spec.id, v)
        onChange()
    }

    fun channel(channel: Int, spec: ParamSpec): Double = channels[channel][spec.id].doubleValue
    fun channel(channel: Int, key: String): Double = channelByKey[key]?.let { channel(channel, it) } ?: 0.0

    fun setChannel(channel: Int, spec: ParamSpec, value: Double) {
        if (channel !in 0 until MixLimits.CHANNELS) return
        val v = value.coerceIn(spec.min, spec.max)
        val state = channels[channel][spec.id]
        if (state.doubleValue == v) return
        state.doubleValue = v
        NativeEngine.setChannelParam(channel, spec.id, v)
        onChange()
    }

    fun setChannel(channel: Int, key: String, value: Double) {
        channelByKey[key]?.let { setChannel(channel, it, value) }
    }

    fun muted(channel: Int): Boolean = MixerLayout.isMuted(channel(channel, MixKeys.MUTE))

    fun toggleMute(channel: Int) {
        val spec = channelByKey[MixKeys.MUTE] ?: return
        setChannel(channel, spec, MixerLayout.toggledMute(channel(channel, spec)))
    }

    val delaySynced: Boolean
        get() = mix(MixKeys.DELAY_SYNC).roundToInt() == MixKeys.SYNC_TEMPO

    /** The delay time as heard: "1/8D" when synced to tempo, else "375 ms". */
    fun delayTimeText(): String {
        val div = mixByKey[MixKeys.DELAY_DIV] ?: return "?"
        val time = mixByKey[MixKeys.DELAY_TIME] ?: return "?"
        return MixFormat.delayTime(delaySynced, div, mix(div), time, mix(time))
    }

    /** First launch: each slot's FX sends to suit its sound (DemoContent). */
    fun seedSends(presetName: (slot: Int) -> String) {
        for (slot in 0 until MixLimits.FIRST_TAPE_CHANNEL) {
            val sends = if (slot == NativeEngine.DRUM_SLOT) DemoContent.DRUM_SENDS else DemoContent.sendsFor(presetName(slot))
            setChannel(slot, MixKeys.DELAY_SEND, sends.delay)
            setChannel(slot, MixKeys.REVERB_SEND, sends.reverb)
        }
    }

    fun refreshFromEngine() {
        for (p in mixParams) mix[p.id].doubleValue = NativeEngine.getMixParam(p.id)
        for (c in 0 until MixLimits.CHANNELS) {
            for (p in channelParams) channels[c][p.id].doubleValue = NativeEngine.getChannelParam(c, p.id)
        }
    }

    fun mixSnapshot(): Map<String, Double> = mixParams.associate { it.key to mix[it.id].doubleValue }

    fun channelSnapshot(): List<Map<String, Double>> =
        List(MixLimits.CHANNELS) { c -> channelParams.associate { it.key to channels[c][it.id].doubleValue } }

    /** Saved values (F7.6) go to the engine; missing ones keep the engine's. No [onChange]. */
    fun restore(savedMix: Map<String, Double>, savedChannels: List<Map<String, Double>>) {
        refreshFromEngine()
        for (p in mixParams) {
            val v = savedMix[p.key]?.coerceIn(p.min, p.max) ?: continue
            mix[p.id].doubleValue = v
            NativeEngine.setMixParam(p.id, v)
        }
        for (c in 0 until minOf(MixLimits.CHANNELS, savedChannels.size)) {
            for (p in channelParams) {
                val v = savedChannels[c][p.key]?.coerceIn(p.min, p.max) ?: continue
                channels[c][p.id].doubleValue = v
                NativeEngine.setChannelParam(c, p.id, v)
            }
        }
    }
}

/** Turns strip references into specs for one screen, with an optional label override. */
private fun MixValues.resolve(ref: StripRef?): ParamSpec? = when (ref) {
    is StripRef.Mix -> mixSpec(ref.key)?.let { spec -> if (ref.label != null) spec.copy(label = ref.label) else spec }
    is StripRef.Channel -> channelSpec(ref.key)
    StripRef.Commit, null -> null
}

/**
 * The Tape screen's strips (F5): tape settings, plus the selected track's channel Level and
 * Pan on the Shift layer. The Commit strip is an action, not a value (see [TapeStrips]).
 * Main thread only.
 */
@Stable
class TapeController(private val app: GrooveState, private val values: MixValues) : StripHost {
    val locks = StripLocks(app::sessionChanged)

    val refs: List<StripRef>
        get() = TapeStrips.strips(app.shift.shifted)

    /** Specs for the value strips; null where the Commit strip is. */
    val strips: List<ParamSpec?>
        get() = refs.map { values.resolve(it) }

    private val channel: Int
        get() = MixLimits.tapeChannel(app.tapeTrack)

    override fun value(spec: ParamSpec): Double =
        if (values.isChannelSpec(spec)) values.channel(channel, spec) else values.mix(spec)

    override fun setValue(spec: ParamSpec, value: Double) {
        app.stripMoved()
        if (values.isChannelSpec(spec)) values.setChannel(channel, spec, value) else values.setMix(spec, value)
    }

    override fun isLocked(spec: ParamSpec): Boolean = locks.isLocked(spec.key)
    override fun setLocked(spec: ParamSpec, locked: Boolean) = locks.set(spec.key, locked)
    override fun format(spec: ParamSpec, value: Double): String = MixFormat.format(spec, value)
}

/**
 * The Mixer screen (F6.5): the selected pad and its four strips. Channel strips act on the
 * selected pad's channel; FX and master strips on the global settings. Main thread only.
 */
@Stable
class MixerController(private val app: GrooveState, private val values: MixValues) : StripHost {
    val locks = StripLocks(app::sessionChanged)

    /** The live level meters, polled by the Mixer screen while it shows. */
    val meters = MixerMeters()

    var selected: MixerPad by mutableStateOf(MixerPad.Slot(1))
        private set

    val strips: List<ParamSpec?>
        get() = MixerLayout.strips(selected, app.shift.shifted, values.delaySynced).map { values.resolve(it) }

    /** A saved session's selection; no autosave mark. */
    fun restoreSelection(pad: MixerPad) {
        selected = pad
    }

    fun select(pad: MixerPad) {
        if (pad == selected) return
        selected = pad
        app.sessionChanged()
    }

    /** A pad touched: select it, or with Shift toggle its channel's mute. */
    fun tap(pad: MixerPad) {
        when (val action = MixerLayout.tap(pad, app.shift.shifted)) {
            is MixerPadAction.Select -> select(action.pad)
            is MixerPadAction.ToggleMute -> values.toggleMute(action.channel)
        }
    }

    private val channel: Int?
        get() = MixerLayout.channelOf(selected)

    override fun value(spec: ParamSpec): Double {
        if (!values.isChannelSpec(spec)) return values.mix(spec)
        return channel?.let { values.channel(it, spec) } ?: spec.default
    }

    override fun setValue(spec: ParamSpec, value: Double) {
        app.stripMoved()
        if (!values.isChannelSpec(spec)) {
            values.setMix(spec, value)
        } else {
            channel?.let { values.setChannel(it, spec, value) }
        }
    }

    /** Locks are per pad: locking Slot 2's volume leaves Slot 3's free. */
    private fun lockKey(spec: ParamSpec) = "${MixerLayout.shortName(selected)}:${spec.key}"

    override fun isLocked(spec: ParamSpec): Boolean = locks.isLocked(lockKey(spec))
    override fun setLocked(spec: ParamSpec, locked: Boolean) = locks.set(lockKey(spec), locked)
    override fun format(spec: ParamSpec, value: Double): String = MixFormat.format(spec, value)
}

/**
 * The Mixer screen's live level meters. [poll] reads the engine's meters (lock-free, cheap) and
 * is called once per frame while the Mixer screen shows. [version] changes whenever a reading
 * did: read it only in the draw phase, so new readings redraw the bars without recomposing the
 * screen. Main thread only.
 */
@Stable
class MixerMeters {
    private val buffer = FloatArray(NativeEngine.METER_BUFFER_SIZE)
    val readings = MeterReadings()

    var version by mutableIntStateOf(0)
        private set

    fun poll() {
        NativeEngine.meters(buffer)
        if (readings.read(buffer)) version++
    }
}
