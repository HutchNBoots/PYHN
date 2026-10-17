package com.groovebox.storage

import com.groovebox.ui.model.KeyScale
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
    /** The session's key and scale (F8); saved as the root and the scale's name. */
    val keyScale: KeyScale = KeyScale.OFF,
    /** The user presets (build-22), in the order they were saved; they follow the 16 factory ones. */
    val userPresets: List<UserPreset> = emptyList(),
)

/** A sound saved from a slot (build-22): its auto-name and every value, by parameter key. */
data class UserPreset(val name: String, val values: Map<String, Double>)

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
    /** The Synth and Tape keys' octave (build-22, the Play rail), −2..+2; 0 = C3–B4. */
    val keyOctave: Int = 0,
) {
    companion object {
        /** What a session saved before the UI block existed (version 0) gets. */
        val DEFAULT = UiSnapshot(screen = "Synth", selectedSlot = 1, lastSynthSlot = 1, page = 0, tapeTrack = 0, mixerPad = "2")
    }
}

/**
 * One synth slot: its last loaded preset (index and name), every value, its locks and section.
 * With [userPreset] the name is a user preset's (build-22) and the index is unused.
 */
data class SynthSnapshot(
    val presetIndex: Int,
    val presetName: String,
    val section: String,
    val values: Map<String, Double>,
    val locks: Set<String>,
    val userPreset: Boolean = false,
)

/** The drum kit: each of the 16 keys' values, the key the strips edit, and the locks (per sound since version 4: "drum.decay@3"). */
data class DrumSnapshot(
    val selectedKey: Int,
    val values: List<Map<String, Double>>,
    val locks: Set<String>,
)
