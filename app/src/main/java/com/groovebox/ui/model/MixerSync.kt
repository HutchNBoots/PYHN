package com.groovebox.ui.model

/**
 * Build-22 (CX review R9): the Mixer's pads and the rest of the app pick the same thing. A slot
 * pad selects that slot (the top bar follows), a tape pad selects that tape track (the Tape
 * screen follows); and when the Mixer shows, a selected slot or track pad follows what the top
 * bar or the Tape screen picked meanwhile. Pure Kotlin.
 */
object MixerSync {
    fun slotOf(pad: MixerPad): Int? = (pad as? MixerPad.Slot)?.slot

    fun trackOf(pad: MixerPad): Int? = (pad as? MixerPad.Track)?.track

    fun padOnShow(pad: MixerPad, selectedSlot: Int, tapeTrack: Int): MixerPad = when (pad) {
        is MixerPad.Slot -> MixerPad.Slot(selectedSlot)
        is MixerPad.Track -> MixerPad.Track(tapeTrack)
        else -> pad
    }
}

/**
 * Solo on the Mixer (build-22), in the UI only until the engine has a solo (build-23): while any
 * channel is soloed, every channel that is not soloed is muted in the engine and a soloed one
 * plays even if the user muted it (solo wins). The user's own mutes stay as they were (and are
 * what a session saves), so un-soloing brings them back.
 * Pure Kotlin.
 */
object MixerSolo {
    /** While anything is soloed, solo decides (a soloed channel plays even if muted); else the user's mute. */
    fun effectiveMute(channel: Int, userMuted: Boolean, soloed: Set<Int>): Boolean =
        if (soloed.isEmpty()) userMuted else channel !in soloed

    /** Muted only because another channel is soloed. */
    fun silencedBySolo(channel: Int, soloed: Set<Int>): Boolean = soloed.isNotEmpty() && channel !in soloed

    fun toggled(soloed: Set<Int>, channel: Int): Set<Int> = when {
        channel !in 0 until MixLimits.CHANNELS -> soloed
        channel in soloed -> soloed - channel
        else -> soloed + channel
    }

    /** ch.mute for each channel as the engine should get it (1.0 = muted). */
    fun engineMutes(userMuted: BooleanArray, soloed: Set<Int>): DoubleArray =
        DoubleArray(userMuted.size) { c -> if (effectiveMute(c, userMuted[c], soloed)) 1.0 else 0.0 }
}
