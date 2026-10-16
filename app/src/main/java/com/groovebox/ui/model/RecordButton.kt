package com.groovebox.ui.model

/**
 * The Record button (vision spec gestures, F3.6, F3.7). Holding it records motion: motion
 * recording is on from touch-down to release, so moving a strip meanwhile writes that
 * parameter into the pattern. A short tap with no strip moved toggles record-arm (note
 * recording from the keys). Times are milliseconds on one monotonic clock. Main thread only.
 */
class RecordButton(private val tapMs: Long = 300) {
    var armed: Boolean = false
        private set

    /** True while the button is held (motion recording on). */
    var held: Boolean = false
        private set

    private var pressedAtMs = 0L
    private var stripMoved = false

    /** Returns true when motion recording should start. */
    fun press(timeMs: Long): Boolean {
        if (held) return false
        held = true
        pressedAtMs = timeMs
        stripMoved = false
        return true
    }

    /** A strip moved; while held, this makes the touch a motion take rather than a tap. */
    fun stripMoved() {
        if (held) stripMoved = true
    }

    /** Returns true when the arm state changed. Motion recording stops either way. */
    fun release(timeMs: Long): Boolean {
        if (!held) return false
        held = false
        if (stripMoved || timeMs - pressedAtMs >= tapMs) return false
        armed = !armed
        return true
    }

    /** The touch was taken away: stop motion recording, leave the arm alone. */
    fun cancel() {
        held = false
    }

    fun setArmed(value: Boolean) {
        armed = value
    }
}
