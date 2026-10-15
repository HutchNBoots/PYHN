package com.groovebox.ui.model

/** The Synth screen's sections, picked from the section rail (feature spec F1). */
enum class SynthSection(val label: String) { Osc("Osc"), Filter("Filter"), Env("Env"), Mod("Mod") }

/**
 * Which parameter key each strip shows, per section and layer, exactly as the F1 table in
 * docs/feature-spec.md. A null key is a reserved slot, shown as a disabled strip.
 */
object SynthLayout {
    private val main = mapOf(
        SynthSection.Osc to listOf("osc.wave", "osc.pw", "osc.sub", "osc.noise"),
        SynthSection.Filter to listOf("flt.cutoff", "flt.reso", "flt.envAmt", "flt.keyTrack"),
        SynthSection.Env to listOf("amp.attack", "amp.decay", "amp.sustain", "amp.release"),
        SynthSection.Mod to listOf("lfo.rate", "lfo.depth", "lfo.wave", "lfo.dest"),
    )

    private val shift = mapOf(
        SynthSection.Osc to listOf("osc.octave", "osc.fine", "osc.glide", "osc.voiceMode"),
        SynthSection.Filter to listOf("flt.drive", "flt.hpf", "flt.velAmt", "flt.lfoAmt"),
        SynthSection.Env to listOf("fenv.attack", "fenv.decay", "fenv.sustain", "fenv.release"),
        // The fourth slot is reserved for tilt (v1.1).
        SynthSection.Mod to listOf("lfo.sync", "lfo.delay", "lfo.retrig", null),
    )

    /** The four strip keys, left to right, for [section] on the main or Shift layer. */
    fun keys(section: SynthSection, shifted: Boolean): List<String?> =
        (if (shifted) shift else main).getValue(section)

    /** Every key the layout names, in section then layer order. */
    val allKeys: List<String> =
        SynthSection.entries.flatMap { keys(it, false) + keys(it, true) }.filterNotNull()
}
