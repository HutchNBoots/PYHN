package com.groovebox.ui.model

/**
 * The scales of F8, as sets of semitone intervals above the root (0..11). [Off] is
 * chromatic: every note is in key, which is the behaviour before F8.
 *
 * [label] is the scale's name on the Scale strip; [suffix] is how it reads after a key
 * ("A minor", "C♯ Dorian").
 */
enum class Scale(val label: String, val suffix: String, val intervals: List<Int>) {
    Off("Off", "", (0..11).toList()),
    Major("Major", "major", listOf(0, 2, 4, 5, 7, 9, 11)),
    Minor("Minor", "minor", listOf(0, 2, 3, 5, 7, 8, 10)),
    Dorian("Dorian", "Dorian", listOf(0, 2, 3, 5, 7, 9, 10)),
    Phrygian("Phrygian", "Phrygian", listOf(0, 1, 3, 5, 7, 8, 10)),
    Lydian("Lydian", "Lydian", listOf(0, 2, 4, 6, 7, 9, 11)),
    Mixolydian("Mixolyd.", "Mixolydian", listOf(0, 2, 4, 5, 7, 9, 10)),
    HarmonicMinor("Harm min", "harmonic minor", listOf(0, 2, 3, 5, 7, 8, 11)),
    MajorPentatonic("Maj pent", "major pentatonic", listOf(0, 2, 4, 7, 9)),
    MinorPentatonic("Min pent", "minor pentatonic", listOf(0, 3, 5, 7, 10)),
    Blues("Blues", "blues", listOf(0, 3, 5, 6, 7, 10));

    private val mask: Int = intervals.fold(0) { m, i -> m or (1 shl i) }

    /** True when [pitchClass] (0..11, above the root) is in the scale. */
    fun has(pitchClass: Int): Boolean = mask and (1 shl pitchClass) != 0

    companion object {
        /** The scale saved under [name], or [Off] for a name this app does not know. */
        fun named(name: String): Scale = entries.firstOrNull { it.name == name } ?: Off
    }
}

/**
 * The session's key and scale (F8): one for the whole session, applied to every synth slot
 * and never to the drum slot. Notes are MIDI numbers. With [Scale.Off] every note is in key
 * and nothing is decorated, so the app behaves as before F8. Pure Kotlin, immutable.
 */
data class KeyScale(val root: Int = 0, val scale: Scale = Scale.Off) {
    init {
        require(root in 0..11) { "root must be 0..11" }
    }

    /** A scale is on: keys are gated, decorated and the display names the key. */
    val isOn: Boolean
        get() = scale != Scale.Off

    private fun pitchClass(note: Int) = Math.floorMod(note - root, 12)

    fun inKey(note: Int): Boolean = scale.has(pitchClass(note))

    /** The note is the key's root (in any octave). */
    fun isRoot(note: Int): Boolean = pitchClass(note) == 0

    /** "A minor", "C♯ Dorian"; null while the scale is off. */
    val displayName: String?
        get() = if (isOn) "${KEY_NAMES[root]} ${scale.suffix}" else null

    /**
     * The next in-key note above ([direction] > 0) or below [note], within [low]..[high].
     * At the end of the range it stays on the last in-key note there, so stepping is
     * monotonic and only ever lands on in-key notes (F8.4).
     */
    fun step(note: Int, direction: Int, low: Int = MIDI_LOW, high: Int = MIDI_HIGH): Int {
        require(low <= high)
        if (direction >= 0) {
            var n = maxOf(note + 1, low)
            while (n <= high) {
                if (inKey(n)) return n
                n++
            }
            return lastInKey(note, low, high, up = true)
        }
        var n = minOf(note - 1, high)
        while (n >= low) {
            if (inKey(n)) return n
            n--
        }
        return lastInKey(note, low, high, up = false)
    }

    /** The in-key note nearest [note] within [low]..[high]; a tie goes to the lower one. */
    fun snap(note: Int, low: Int = MIDI_LOW, high: Int = MIDI_HIGH): Int {
        val n = note.coerceIn(low, high)
        if (inKey(n)) return n
        val down = step(n, -1, low, high)
        val up = step(n, +1, low, high)
        return when {
            down > n -> up // nothing below n in range
            up < n -> down // nothing above n in range
            n - down <= up - n -> down
            else -> up
        }
    }

    /**
     * The Note strip on a synth step (F8.4): the chord moved so its first note lands on the
     * in-key note nearest [target], each note moving by the same number of scale degrees, so
     * every note ends in key; kept inside [low]..[high] as a whole. With the scale off this is
     * the plain chromatic transpose.
     */
    fun transpose(notes: List<Int>, target: Int, low: Int, high: Int): List<Int> {
        if (!isOn) return SeqStrips.transpose(notes, target, low, high)
        val t = snap(target, low, high)
        if (notes.isEmpty()) return listOf(t)
        val degrees = notes.map { degreeOf(snap(it, low, high)) }
        val lowest = degreeOf(step(low - 1, +1, low, high)) // the first in-key note in range
        val highest = degreeOf(step(high + 1, -1, low, high)) // the last
        var delta = degreeOf(t) - degrees.first()
        val lo = degrees.min() + delta
        val hi = degrees.max() + delta
        if (lo < lowest) delta += lowest - lo
        if (hi > highest) delta -= hi - highest
        return degrees.map { noteOfDegree((it + delta).coerceIn(lowest, highest)) }.distinct()
    }

    /** Synth notes outside the key that the keyboard must not play (F8.2); drums are never gated (F8.7). */
    fun playable(note: Int, drum: Boolean): Boolean = drum || inKey(note)

    /** The keys drawn dimmed on a synth slot's keyboard (F8.3). */
    fun dimmed(notes: Iterable<Int>): Set<Int> = if (isOn) notes.filterNot(::inKey).toSet() else emptySet()

    /** The keys that get the root's accent stripe (F8.3). */
    fun roots(notes: Iterable<Int>): Set<Int> = if (isOn) notes.filter(::isRoot).toSet() else emptySet()

    /** A synth step that plays a note outside the key: its pad gets a mark (F8.6). */
    fun marksStep(step: Step, drum: Boolean): Boolean = isOn && !drum && step.on && step.notes.any { !inKey(it) }

    // Degrees count in-key notes from the root of octave -1 (MIDI root): an in-key note's index.
    private fun degreeOf(inKeyNote: Int): Int {
        val size = scale.intervals.size
        val rel = inKeyNote - root
        return Math.floorDiv(rel, 12) * size + scale.intervals.indexOf(Math.floorMod(rel, 12))
    }

    private fun noteOfDegree(degree: Int): Int {
        val size = scale.intervals.size
        return root + Math.floorDiv(degree, size) * 12 + scale.intervals[Math.floorMod(degree, size)]
    }

    /** The in-key note at the top (or bottom) end of the range; [note] clamped if a tiny range has none. */
    private fun lastInKey(note: Int, low: Int, high: Int, up: Boolean): Int {
        if (up) {
            for (n in high downTo low) if (inKey(n)) return n
        } else {
            for (n in low..high) if (inKey(n)) return n
        }
        return note.coerceIn(low, high) // only for a range under an octave; every scale has a note in any 12 semitones
    }

    companion object {
        val OFF = KeyScale()
        val KEY_NAMES = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
        const val MIDI_LOW = 0
        const val MIDI_HIGH = 127

        /** A key from saved values: an unknown root falls back to C. */
        fun of(root: Int, scale: Scale): KeyScale = KeyScale(if (root in 0..11) root else 0, scale)
    }
}
