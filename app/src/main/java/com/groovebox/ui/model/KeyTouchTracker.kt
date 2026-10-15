package com.groovebox.ui.model

/**
 * Turns per-pointer touch events into note on/off calls. Handles multi-touch, sliding
 * between keys and two fingers on one key. Only the initial touch-down is timestamped for
 * latency measurement; slides pass tapTimeNs = 0. A slide onto a new key uses the velocity
 * at the point where it enters that key; moving within a key never retriggers.
 *
 * Only pointers that went down on a key are followed. pointerInteropFilter hands the
 * keyboard the whole MotionEvent, including fingers that are dragging a strip, and those
 * must not play notes if they wander over the keys.
 *
 * Main thread only.
 */
class KeyTouchTracker(
    private val onNoteOn: (note: Int, velocity: Float, tapTimeNs: Long) -> Unit,
    private val onNoteOff: (note: Int) -> Unit,
) {
    private val noteByPointer = HashMap<Int, Int>()
    private val holdCount = HashMap<Int, Int>()
    private val owned = HashSet<Int>()

    fun down(pointerId: Int, note: Int?, velocity: Float, tapTimeNs: Long) {
        release(pointerId)
        if (note == null) {
            owned.remove(pointerId)
            return
        }
        owned.add(pointerId)
        press(pointerId, note, velocity, tapTimeNs)
    }

    fun move(pointerId: Int, note: Int?, velocity: Float) {
        if (pointerId !in owned || noteByPointer[pointerId] == note) return
        release(pointerId)
        if (note != null) press(pointerId, note, velocity, 0L)
    }

    fun up(pointerId: Int) {
        owned.remove(pointerId)
        release(pointerId)
    }

    fun cancel() {
        owned.clear()
        noteByPointer.keys.toList().forEach(::release)
    }

    private fun press(pointerId: Int, note: Int, velocity: Float, tapTimeNs: Long) {
        noteByPointer[pointerId] = note
        val count = holdCount.getOrDefault(note, 0)
        holdCount[note] = count + 1
        if (count == 0) onNoteOn(note, velocity, tapTimeNs)
    }

    private fun release(pointerId: Int) {
        val note = noteByPointer.remove(pointerId) ?: return
        val count = holdCount.getOrDefault(note, 0) - 1
        if (count <= 0) {
            holdCount.remove(note)
            onNoteOff(note)
        } else {
            holdCount[note] = count
        }
    }
}
