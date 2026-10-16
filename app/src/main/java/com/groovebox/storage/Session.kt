package com.groovebox.storage

import com.groovebox.ui.model.PatternSnapshot

/**
 * Everything the UI mirrors, as saved between launches (F7.4, F7.6). Immutable, so a copy
 * taken on the main thread can be written on a background thread.
 *
 * Parameter values are keyed by the parameter's key ("flt.cutoff"), not its id, so a save
 * still applies after the engine's tables gain, lose or reorder parameters: unknown keys are
 * ignored and missing ones keep the engine's value. Enum-like UI state (screen, section,
 * mixer pad) is kept as names for the same reason; the app maps unknown names to defaults.
 * Pure Kotlin.
 */
data class Session(
    val tempo: Double,
    val ui: UiSnapshot,
    /** Engine slots 1..7, in order. */
    val synths: List<SynthSnapshot>,
    val drum: DrumSnapshot,
    /** MixParams.def values (mixer, FX and tape settings), by key. */
    val mix: Map<String, Double>,
    /** ChannelParams.def values of mixer channels 0..11, by key. */
    val channels: List<Map<String, Double>>,
    /** The patterns that are not empty. */
    val patterns: List<PatternSnapshot>,
    /** Per slot 0..7: the pattern it plays (a queued switch counts as made). */
    val currentPatterns: List<Int>,
    /** Strip locks of the screens that are not per slot, by screen ("seq", "tape", "mixer"). */
    val locks: Map<String, Set<String>>,
)

/** Which screen and selection the app shows. */
data class UiSnapshot(
    val screen: String,
    val selectedSlot: Int,
    val lastSynthSlot: Int,
    /** The Sequencer screen's step page, 0..3. */
    val page: Int,
    /** The Tape screen's selected track, 0..3. */
    val tapeTrack: Int,
    /** The Mixer screen's selected pad, as [com.groovebox.ui.model.MixerLayout.shortName] writes it ("2", "T1", "DLY"). */
    val mixerPad: String,
) {
    companion object {
        /** What a session saved before the UI block existed (version 0) gets. */
        val DEFAULT = UiSnapshot(screen = "Synth", selectedSlot = 1, lastSynthSlot = 1, page = 0, tapeTrack = 0, mixerPad = "2")
    }
}

/** One synth slot: its last loaded preset (index and name), every value, its locks and section. */
data class SynthSnapshot(
    val presetIndex: Int,
    val presetName: String,
    val section: String,
    val values: Map<String, Double>,
    val locks: Set<String>,
)

/** The drum kit: each of the 16 keys' values, the key the strips edit, and the locks. */
data class DrumSnapshot(
    val selectedKey: Int,
    val values: List<Map<String, Double>>,
    val locks: Set<String>,
)
