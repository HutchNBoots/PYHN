package com.groovebox.ui.model

/**
 * Clearing a tape lane asks first (build-21): holding a lane with audio arms it ("Clear T3?");
 * a single tap on that lane clears it when its touch-down came within [windowMs] of the hold
 * (judged at touch-down, since the tap itself is reported only after the double-tap wait).
 * Anything else cancels: a touch on another lane, a touch outside the lanes ([cancel]), a
 * double-tap on the asking lane ([doubleTap]), holding an empty lane, or the window running out.
 * Pure Kotlin; times are monotonic ms.
 */
class TapeClearConfirm(private val windowMs: Long = 3_000) {
    private var lane = -1
    private var armedAt = 0L

    /** The lane whose last touch-down came while it was asking, or -1. */
    private var confirmingDown = -1

    /** The lane armed for clearing at [now], or null. */
    fun armedLane(now: Long): Int? = lane.takeIf { it >= 0 && now - armedAt in 0..windowMs }

    /** A hold on [lane]: arms it when it has audio (returns true), else cancels any arm. */
    fun hold(lane: Int, hasAudio: Boolean, now: Long): Boolean {
        confirmingDown = -1 // the hold's own touch-down never confirms
        if (!hasAudio || lane < 0) {
            cancel()
            return false
        }
        this.lane = lane
        armedAt = now
        return true
    }

    /** A touch-down on [lane]: on the asking lane it may confirm; on any other lane it cancels. */
    fun down(lane: Int, now: Long) {
        if (armedLane(now) == lane) {
            confirmingDown = lane
        } else {
            cancel()
        }
    }

    /** A single tap on [lane]: returns true when it confirms the clear (its touch-down was in time). */
    fun tap(lane: Int): Boolean {
        val confirmed = this.lane >= 0 && confirmingDown == lane && this.lane == lane
        cancel()
        return confirmed
    }

    /**
     * A double-tap on [lane]: when that lane was asking at its touch-down, the double-tap only
     * cancels the ask (returns true: nothing else should happen, no record). Always cancels.
     */
    fun doubleTap(lane: Int): Boolean {
        val swallowed = this.lane >= 0 && confirmingDown == lane && this.lane == lane
        cancel()
        return swallowed
    }

    fun cancel() {
        lane = -1
        confirmingDown = -1
    }
}
