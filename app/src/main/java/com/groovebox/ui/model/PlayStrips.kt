package com.groovebox.ui.model

import com.groovebox.engine.Curve
import com.groovebox.engine.ParamSpec

/**
 * The Play rail item's session strips (build-22, owner decision 1-3a): the key and scale (F8,
 * the same session values the Seq Shift layer sets) and the keyboard octave ([KeyOctave]). They
 * are not engine parameters; their ids are outside the engine tables. Pure Kotlin.
 */
object PlayStrips {
    const val PREFIX = "play."
    const val KEY = "play.key"
    const val SCALE = "play.scale"
    const val OCTAVE = "play.octave"

    val Key = ParamSpec(1000, KEY, "Key", 0.0, 11.0, 0.0, Curve.Enum, KeyScale.KEY_NAMES.joinToString("|"))
    val ScaleStrip = ParamSpec(
        1001, SCALE, "Scale", 0.0, (Scale.entries.size - 1).toDouble(), 0.0, Curve.Enum,
        Scale.entries.joinToString("|") { it.label },
    )
    val Octave = ParamSpec(1002, OCTAVE, "Octave", KeyOctave.MIN.toDouble(), KeyOctave.MAX.toDouble(), 0.0, Curve.Int, "oct")

    fun spec(key: String): ParamSpec? = when (key) {
        KEY -> Key
        SCALE -> ScaleStrip
        OCTAVE -> Octave
        else -> null
    }
}

/**
 * The keyboard octave (build-22, manual 1-3B): moves only what the Synth and Tape keys play, two
 * octaves each way from C3–B4, with the C labels following; the sound's Octave and the pattern
 * stay as they are. Pure Kotlin.
 */
object KeyOctave {
    const val MIN = -2
    const val MAX = 2
    const val BASE_NOTE = 48 // C3, DrumLayout.LOWEST_NOTE

    fun clamp(octave: Int): Int = octave.coerceIn(MIN, MAX)

    fun lowestNote(octave: Int): Int = BASE_NOTE + 12 * clamp(octave)

    /** "C4" for 60 (the keyboard's own labels: C3 = 48). */
    fun noteName(note: Int): String = NAMES[Math.floorMod(note, 12)] + (Math.floorDiv(note, 12) - 1)

    /** The two octaves the keys play, "C3–B4". */
    fun rangeLabel(octave: Int): String {
        val low = lowestNote(octave)
        return "${noteName(low)}–${noteName(low + 23)}"
    }

    fun format(octave: Int): String = when {
        octave > 0 -> "+$octave"
        octave < 0 -> "−${-octave}"
        else -> "0"
    }

    private val NAMES = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
}
