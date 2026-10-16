package com.groovebox.ui.model

/**
 * The Sequencer's Clear button (no dialog, no undo): the first tap arms it ("tap again"), a
 * second tap within [windowMs] confirms. Arming is for one target (slot and pattern): if the
 * selection changes in between, the next tap arms again instead of clearing. Pure Kotlin.
 */
class ClearConfirm(private val windowMs: Long = 2_000) {
    private var armedAt = Long.MIN_VALUE
    private var armedFor: Int? = null

    fun isArmed(target: Int, now: Long): Boolean = armedFor == target && now - armedAt in 0..windowMs

    /** Returns true when this tap confirms the clear of [target]. */
    fun tap(target: Int, now: Long): Boolean {
        if (isArmed(target, now)) {
            reset()
            return true
        }
        armedFor = target
        armedAt = now
        return false
    }

    fun reset() {
        armedFor = null
        armedAt = Long.MIN_VALUE
    }
}
