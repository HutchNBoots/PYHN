package com.groovebox.spike

/**
 * Turns per-pointer touch events into note on/off calls. Handles multi-touch, sliding
 * between keys and two fingers on one key. Only the initial touch-down is timestamped for
 * latency measurement; slides pass tapTimeNs = 0.
 *
 * Main thread only.
 */
class KeyTouchTracker(
    private val onNoteOn: (note: Int, tapTimeNs: Long) -> Unit,
    private val onNoteOff: (note: Int) -> Unit,
) {
    private val noteByPointer = HashMap<Int, Int>()
    private val holdCount = HashMap<Int, Int>()

    fun down(pointerId: Int, note: Int?, tapTimeNs: Long) {
        release(pointerId)
        if (note != null) press(pointerId, note, tapTimeNs)
    }

    fun move(pointerId: Int, note: Int?) {
        if (noteByPointer[pointerId] == note) return
        release(pointerId)
        if (note != null) press(pointerId, note, 0L)
    }

    fun up(pointerId: Int) = release(pointerId)

    fun cancel() {
        noteByPointer.keys.toList().forEach(::release)
    }

    private fun press(pointerId: Int, note: Int, tapTimeNs: Long) {
        noteByPointer[pointerId] = note
        val count = holdCount.getOrDefault(note, 0)
        holdCount[note] = count + 1
        if (count == 0) onNoteOn(note, tapTimeNs)
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
