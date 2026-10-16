package com.groovebox.ui.model

/** One of the Mixer screen's 15 pads (F6.5): 8 slots, 4 tape tracks, the two FX returns and the master. */
sealed interface MixerPad {
    data class Slot(val slot: Int) : MixerPad
    data class Track(val track: Int) : MixerPad
    data object Delay : MixerPad
    data object Reverb : MixerPad
    data object Master : MixerPad
}

/** What a touch on a mixer pad does. */
sealed interface MixerPadAction {
    data class Select(val pad: MixerPad) : MixerPadAction
    data class ToggleMute(val channel: Int) : MixerPadAction
}

/**
 * The Mixer screen (F6.5): the pads, the strips each pad puts on the four strips, and the
 * tap/Shift+tap rule. Pads without a Shift layer of their own show the same strips with
 * Shift on (Shift there is for muting). Pure Kotlin.
 */
object MixerLayout {
    val pads: List<MixerPad> =
        List(SeqLimits.SLOTS) { MixerPad.Slot(it) } +
            List(MixLimits.TRACKS) { MixerPad.Track(it) } +
            listOf(MixerPad.Delay, MixerPad.Reverb, MixerPad.Master)

    /** The mixer channel (0..11) behind a pad, or null for the FX returns and the master. */
    fun channelOf(pad: MixerPad): Int? = when (pad) {
        is MixerPad.Slot -> pad.slot
        is MixerPad.Track -> MixLimits.tapeChannel(pad.track)
        else -> null
    }

    /** The pad of a mixer channel. */
    fun padOf(channel: Int): MixerPad? = when (channel) {
        in 0 until MixLimits.FIRST_TAPE_CHANNEL -> MixerPad.Slot(channel)
        in MixLimits.FIRST_TAPE_CHANNEL until MixLimits.CHANNELS -> MixerPad.Track(channel - MixLimits.FIRST_TAPE_CHANNEL)
        else -> null
    }

    /** Tap selects; Shift + tap mutes a channel pad (an FX or master pad is selected either way). */
    fun tap(pad: MixerPad, shifted: Boolean): MixerPadAction {
        val channel = channelOf(pad)
        return if (shifted && channel != null) MixerPadAction.ToggleMute(channel) else MixerPadAction.Select(pad)
    }

    /** ch.mute's next value. */
    fun toggledMute(current: Double): Double = if (current >= 0.5) 0.0 else 1.0

    fun isMuted(value: Double): Boolean = value >= 0.5

    /** The Delay pad's first strip, Time: the division when synced to tempo, else seconds. */
    fun delayTime(synced: Boolean): StripRef.Mix =
        if (synced) StripRef.Mix(MixKeys.DELAY_DIV, label = "Time") else StripRef.Mix(MixKeys.DELAY_TIME)

    private val channelStrips = listOf(
        StripRef.Channel(MixKeys.VOLUME),
        StripRef.Channel(MixKeys.PAN),
        StripRef.Channel(MixKeys.DELAY_SEND),
        StripRef.Channel(MixKeys.REVERB_SEND),
    )
    private val trackStrips = listOf(StripRef.Channel(MixKeys.VOLUME), StripRef.Channel(MixKeys.PAN), null, null)
    private val delayShift = listOf(
        StripRef.Mix(MixKeys.DELAY_SYNC),
        StripRef.Mix(MixKeys.DELAY_DIV),
        StripRef.Mix(MixKeys.DELAY_RETURN),
        null,
    )
    private val reverbStrips = listOf(
        StripRef.Mix(MixKeys.REVERB_SIZE),
        StripRef.Mix(MixKeys.REVERB_DECAY),
        StripRef.Mix(MixKeys.REVERB_DAMP),
        StripRef.Mix(MixKeys.REVERB_RETURN),
    )
    private val masterStrips = listOf(StripRef.Mix(MixKeys.MASTER), null, null, null)

    /** The four strips for [pad]; null is a disabled strip. */
    fun strips(pad: MixerPad, shifted: Boolean, delaySynced: Boolean): List<StripRef?> = when (pad) {
        is MixerPad.Slot -> channelStrips
        is MixerPad.Track -> trackStrips
        MixerPad.Delay -> if (shifted) {
            delayShift
        } else {
            listOf(
                delayTime(delaySynced),
                StripRef.Mix(MixKeys.DELAY_FEEDBACK),
                StripRef.Mix(MixKeys.DELAY_LOW_CUT),
                StripRef.Mix(MixKeys.DELAY_HIGH_CUT),
            )
        }
        MixerPad.Reverb -> reverbStrips
        MixerPad.Master -> masterStrips
    }

    /** Every key any pad's strips name (for the contract test). */
    val allKeys: Set<String> by lazy {
        val refs = pads.flatMap { pad -> listOf(false, true).flatMap { s -> listOf(false, true).flatMap { strips(pad, s, it) } } }
        refs.mapNotNull {
            when (it) {
                is StripRef.Mix -> it.key
                is StripRef.Channel -> it.key
                else -> null
            }
        }.toSet()
    }

    /** The pad's short name: "1".."8", "T1".."T4", "DLY", "REV", "MST". */
    fun shortName(pad: MixerPad): String = when (pad) {
        is MixerPad.Slot -> "${pad.slot + 1}"
        is MixerPad.Track -> TapeView.trackName(pad.track)
        MixerPad.Delay -> "DLY"
        MixerPad.Reverb -> "REV"
        MixerPad.Master -> "MST"
    }

    /** The display's title for a pad; [instrument] names a slot's sound. */
    fun title(pad: MixerPad, instrument: (Int) -> String): String = when (pad) {
        is MixerPad.Slot -> "Slot ${pad.slot + 1} · ${instrument(pad.slot)}"
        is MixerPad.Track -> "Tape ${TapeView.trackName(pad.track)}"
        MixerPad.Delay -> "Delay"
        MixerPad.Reverb -> "Reverb"
        MixerPad.Master -> "Master"
    }
}
