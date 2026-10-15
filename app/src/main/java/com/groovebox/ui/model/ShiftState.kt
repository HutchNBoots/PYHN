package com.groovebox.ui.model

/**
 * The Shift button (feature spec F4.4). Shift is on while the button is held, so the second
 * layer appears in the same frame as the press. On release, a tap (shorter than [holdMs])
 * toggles the latch; a hold was momentary and returns to the state before the press.
 * Times are milliseconds on one monotonic clock. Main thread only.
 */
class ShiftState(private val holdMs: Long = 300) {
    var active: Boolean = false
        private set

    private var pressedAtMs: Long? = null
    private var beforePress = false

    fun press(timeMs: Long) {
        if (pressedAtMs != null) return
        pressedAtMs = timeMs
        beforePress = active
        active = true
    }

    fun release(timeMs: Long) {
        val pressedAt = pressedAtMs ?: return
        pressedAtMs = null
        active = if (timeMs - pressedAt >= holdMs) beforePress else !beforePress
    }

    /** The touch was taken away: treat it as momentary. */
    fun cancel() {
        if (pressedAtMs == null) return
        pressedAtMs = null
        active = beforePress
    }
}
