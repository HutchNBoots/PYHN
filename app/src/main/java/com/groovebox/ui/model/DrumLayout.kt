package com.groovebox.ui.model

import kotlin.math.exp
import kotlin.math.sin

/** The Drum screen's strips (F2.2), by DrumParams.def key, and its key mapping. Pure Kotlin. */
object DrumLayout {
    const val KEY_COUNT = 16
    const val LOWEST_NOTE = 48 // NativeEngine.DRUM_LOWEST_NOTE

    val main = listOf("drum.pitch", "drum.decay", "drum.tone", "drum.level")
    val shift = listOf("drum.pan", "drum.start", "drum.reverse", "drum.choke")

    fun keys(shifted: Boolean): List<String> = if (shifted) shift else main

    /** The kit key (0..15) a MIDI note plays, or null for notes outside the kit. */
    fun keyOf(note: Int): Int? = (note - LOWEST_NOTE).takeIf { it in 0 until KEY_COUNT }

    fun noteOf(key: Int): Int = LOWEST_NOTE + key

    /**
     * A short label for a key: one word keeps up to four letters ("Kick 2" → "Kick2"),
     * several words become initials ("Closed Hat 1" → "CH1").
     */
    fun shortName(name: String): String {
        val words = name.trim().split(' ', '-', '_').filter { it.isNotEmpty() }
        if (words.isEmpty()) return ""
        val number = words.last().takeIf { w -> w.all { it.isDigit() } }
        val letters = if (number != null) words.dropLast(1) else words
        val stem = when {
            letters.isEmpty() -> ""
            letters.size == 1 -> letters[0].take(4)
            else -> letters.joinToString("") { it.take(1).uppercase() }
        }
        return stem + (number ?: "")
    }

    /**
     * The display's picture of a drum sound: an oscillation inside its amplitude envelope,
     * x 0..1 is time, y −1..1. Higher [pitchSt] packs more cycles in; [decay] (0..1) sets
     * how long it rings; [level] (0..1) scales it; [start] (0..0.5) skips the attack;
     * [reverse] plays it backwards. Only needs to move the right way.
     */
    fun shape(pitchSt: Double, decay: Double, level: Double, start: Double, reverse: Boolean, points: Int = 160): List<DisplayShapes.Point> {
        val cycles = 6.0 * Math.pow(2.0, pitchSt.coerceIn(-12.0, 12.0) / 12.0)
        val rate = 2.0 + 28.0 * (1.0 - decay.coerceIn(0.0, 1.0)) // decay 1 rings longest
        val lvl = level.coerceIn(0.0, 1.0)
        val s = start.coerceIn(0.0, 0.5)
        return List(points + 1) { i ->
            val u = i.toDouble() / points
            val t = if (reverse) 1.0 - u else u
            val time = s + t * (1.0 - s)
            val env = lvl * exp(-rate * time)
            DisplayShapes.Point(u, env * sin(2 * Math.PI * cycles * time))
        }
    }

    /** The envelope alone (the outline drawn around [shape]), same axes. */
    fun envelope(decay: Double, level: Double, start: Double, reverse: Boolean, points: Int = 64): List<DisplayShapes.Point> {
        val rate = 2.0 + 28.0 * (1.0 - decay.coerceIn(0.0, 1.0))
        val lvl = level.coerceIn(0.0, 1.0)
        val s = start.coerceIn(0.0, 0.5)
        return List(points + 1) { i ->
            val u = i.toDouble() / points
            val t = if (reverse) 1.0 - u else u
            DisplayShapes.Point(u, lvl * exp(-rate * (s + t * (1.0 - s))))
        }
    }
}
