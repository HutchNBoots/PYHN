package com.groovebox.ui.model

/**
 * Clearing a tape lane asks first (build-21): holding a lane with audio arms it ("Clear T3?");
 * a tap on that lane within [windowMs] clears it. Anything else cancels: a touch on another
 * lane, a touch outside the lanes ([cancel]), holding an empty lane, or the window running out.
 * Pure Kotlin; times are monotonic ms.
 */
class TapeClearConfirm(private val windowMs: Long = 3_000) {
    private var lane = -1
    private var armedAt = 0L

    /** The lane armed for clearing at [now], or null. */
    fun armedLane(now: Long): Int? = lane.takeIf { it >= 0 && now - armedAt in 0..windowMs }

    /** A hold on [lane]: arms it when it has audio (returns true), else cancels any arm. */
    fun hold(lane: Int, hasAudio: Boolean, now: Long): Boolean {
        if (!hasAudio || lane < 0) {
            cancel()
            return false
        }
        this.lane = lane
        armedAt = now
        return true
    }

    /** A touch-down on [lane]: one on any other lane cancels the arm. */
    fun down(lane: Int, now: Long) {
        if (armedLane(now) != lane) cancel()
    }

    /** A tap on [lane]: returns true when it confirms the clear of that lane. Any other tap cancels. */
    fun tap(lane: Int, now: Long): Boolean {
        val confirmed = armedLane(now) == lane
        cancel()
        return confirmed
    }

    fun cancel() {
        lane = -1
    }
}
