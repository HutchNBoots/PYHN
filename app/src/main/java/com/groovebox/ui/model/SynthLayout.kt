package com.groovebox.ui.model

/**
 * The Synth screen's sections, picked from the section rail (feature spec F1). Build-22 adds
 * [Play] (owner decision 1-3a): the session's key and scale, the keyboard octave and the voice mode.
 */
enum class SynthSection(val label: String) { Osc("Osc"), Filter("Filter"), Env("Env"), Mod("Mod"), Play("Play") }

/**
 * Which parameter key each strip shows, per section and layer, as the F1 table in
 * docs/feature-spec.md plus the Play item. A null key is a reserved slot, shown as a disabled
 * strip. Keys starting "play." are not engine parameters: they are the session's key, scale and
 * keyboard octave ([PlayStrips]). Pure Kotlin.
 */
object SynthLayout {
    private val main = mapOf(
        SynthSection.Osc to listOf("osc.wave", "osc.pw", "osc.sub", "osc.noise"),
        SynthSection.Filter to listOf("flt.cutoff", "flt.reso", "flt.envAmt", "flt.keyTrack"),
        SynthSection.Env to listOf("amp.attack", "amp.decay", "amp.sustain", "amp.release"),
        // Rate carries the tempo sync below its detent (build-22, [LfoDetent]).
        SynthSection.Mod to listOf("lfo.rate", "lfo.depth", "lfo.wave", "lfo.dest"),
        SynthSection.Play to listOf(PlayStrips.KEY, PlayStrips.SCALE, PlayStrips.OCTAVE, "osc.voiceMode"),
    )

    private val shift = mapOf(
        SynthSection.Osc to listOf("osc.octave", "osc.fine", "osc.glide", "osc.voiceMode"),
        SynthSection.Filter to listOf("flt.drive", "flt.hpf", "flt.velAmt", "flt.lfoAmt"),
        SynthSection.Env to listOf("fenv.attack", "fenv.decay", "fenv.sustain", "fenv.release"),
        // The fourth slot is reserved for tilt (v1.1).
        SynthSection.Mod to listOf("lfo.sync", "lfo.delay", "lfo.retrig", null),
        // Kept for the arpeggiator (build-24).
        SynthSection.Play to listOf(null, null, null, null),
    )

    /** The four strip keys, left to right, for [section] on the main or Shift layer. */
    fun keys(section: SynthSection, shifted: Boolean): List<String?> =
        (if (shifted) shift else main).getValue(section)

    fun isPlayKey(key: String): Boolean = key.startsWith(PlayStrips.PREFIX)

    /** Every engine parameter key the layout names, once each, in section then layer order. */
    val allKeys: List<String> =
        SynthSection.entries.flatMap { keys(it, false) + keys(it, true) }.filterNotNull().filterNot(::isPlayKey).distinct()

    /** The section a parameter's strip is in (the first, for one on two), or null. */
    fun sectionOf(key: String): SynthSection? =
        SynthSection.entries.firstOrNull { key in keys(it, false) || key in keys(it, true) }

    /**
     * The label a strip shows (build-22 renames, manual 1-2D): Filter "Env" and "Key" clashed
     * with the Env section and the session key, Mod "Delay" with the FX delay.
     */
    fun label(key: String, engineLabel: String): String = RENAMES[key] ?: engineLabel

    private val RENAMES = mapOf("flt.envAmt" to "EnvAmt", "flt.keyTrack" to "KeyTrk", "lfo.delay" to "Fade")

    /** osc.voiceMode's value for Poly (SynthParams.def: poly|mono|unison). */
    const val POLY = 0

    /** Glide does nothing in Poly, so its strip is dimmed there (owner decision 1-3c). */
    fun dimmed(key: String, voiceMode: Int): Boolean = key == "osc.glide" && voiceMode == POLY

    /** "Poly", "Mono", "Unison (mono)": Unison stacks four voices on one note (1-3c). */
    fun voiceModeLabel(index: Int): String = MODE_LABELS[index.coerceIn(0, MODE_LABELS.lastIndex)]

    private val MODE_LABELS = listOf("Poly", "Mono", "Unison (mono)")
}
