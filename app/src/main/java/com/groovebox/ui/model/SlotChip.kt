package com.groovebox.ui.model

/**
 * The Tape screen's slot chip (build-22, ADR 0003 P6): it sits in the top bar where the slot
 * buttons are on the other screens and names the slot the keys play and Commit takes. A tap
 * steps to the next slot (8 wraps to 1, the kit); a long-press opens the slot buttons for a
 * direct pick. Pure Kotlin.
 */
object SlotChip {
    fun next(slot: Int): Int = if (slot !in 0 until SeqLimits.SLOTS) 0 else (slot + 1) % SeqLimits.SLOTS

    /** "slot 2 ▾"; the drum slot reads "Kit ▾", as the Commit strip names it. */
    fun label(slot: Int): String = if (slot == 0) "${TapeStrips.commitValue(0)} ▾" else "slot ${slot + 1} ▾"
}
