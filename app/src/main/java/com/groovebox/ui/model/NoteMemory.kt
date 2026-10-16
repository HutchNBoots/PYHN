package com.groovebox.ui.model

/**
 * Remembers, per slot, the notes last played on the keys, so a pad tap can enter them
 * (F3.5) and keys played while a step is selected can set its chord.
 *
 * A key going down while others of the same slot are held adds to the chord (the held keys
 * in touch order, at most 4); a key going down alone starts a new one. Releasing keys never
 * changes the remembered chord. Before any key is played a slot uses [defaultNote].
 *
 * Main thread only.
 */
class NoteMemory(private val defaultNote: (slot: Int) -> Int) {
    private val held = Array(SeqLimits.SLOTS) { ArrayList<Int>() }
    private val chord = arrayOfNulls<List<Int>>(SeqLimits.SLOTS)
    private val velocity = FloatArray(SeqLimits.SLOTS) { SeqLimits.DEFAULT_VELOCITY }

    /** A key went down; returns the slot's chord as it is now. */
    fun keyDown(slot: Int, note: Int, velocity: Float): List<Int> {
        if (slot !in held.indices) return emptyList()
        val h = held[slot]
        if (note !in h) h += note
        val c = h.take(SeqLimits.NOTES)
        chord[slot] = c
        this.velocity[slot] = velocity
        return c
    }

    fun keyUp(slot: Int, note: Int) {
        if (slot in held.indices) held[slot].remove(note)
    }

    /** True once a key has been played on the slot. */
    fun hasPlayed(slot: Int): Boolean = chord.getOrNull(slot) != null

    /** The notes a pad tap enters on [slot]. */
    fun lastNotes(slot: Int): List<Int> = chord.getOrNull(slot) ?: listOf(defaultNote(slot))

    fun lastVelocity(slot: Int): Float = velocity.getOrElse(slot) { SeqLimits.DEFAULT_VELOCITY }

    /**
     * The notes and velocity a pad tap turns [old] on with (F3.5): the slot's last-played
     * chord once keys have been played on it; before that, the step's own notes if it has
     * any (e.g. the demo), else the default note.
     */
    fun entryFor(slot: Int, old: Step): Pair<List<Int>, Float> = when {
        hasPlayed(slot) -> lastNotes(slot) to lastVelocity(slot)
        old.notes.isNotEmpty() -> old.notes to old.velocity
        else -> lastNotes(slot) to SeqLimits.DEFAULT_VELOCITY
    }

    /** Forget held keys (e.g. the keyboard lost its touches). The chords stay. */
    fun releaseAll() = held.forEach { it.clear() }
}
