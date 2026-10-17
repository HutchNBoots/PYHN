package com.groovebox.ui.model

import kotlin.math.abs

/** What a strip gesture asks the strip to do. */
sealed interface StripAction {
    /** Move the strip to this normalised position (0..1). */
    data class Set(val normalized: Double) : StripAction

    /** Reset the parameter to its default (double-tap). */
    data object Reset : StripAction

    /** The strip's lock changed (long-press). */
    data class Lock(val locked: Boolean) : StripAction
}

/**
 * Touch grammar for one colour strip (feature spec F4.1, F4.2), as a pure state machine fed
 * with abstract pointer events. Times are milliseconds on one monotonic clock; y grows downwards.
 *
 * - A vertical drag by the first finger changes the value; [fullRangeHeights] strip heights
 *   of travel cover the whole range, dragging up increases it, and the value is clamped.
 *   The drag starts once the finger has moved more than [touchSlop], with no jump.
 * - While a second finger is held on the same strip, the drag runs at [fineFactor] speed.
 *   If the first finger lifts, the remaining one carries on driving at normal speed.
 * - Two taps (down/up with no drag, single finger), the second starting within [doubleTapMs]
 *   of the first one's release, reset to the default.
 * - Holding one finger still for [longPressMs] (1 s, build-22: owner decision 1-2c) toggles
 *   [locked]; any movement past the slop before then, a second finger or a lift cancels it and
 *   the drag works as normal. From [ringDelayMs] into the hold the UI draws a filling ring
 *   ([lockProgress]) and wakes at [nextWake]; it should call [timeout] at [longPressDeadline]
 *   (a release after the deadline also counts, in case the timer is late) and give a haptic
 *   tick on the [StripAction.Lock].
 * - A locked strip ignores drags and double-taps; only a long-press unlocks it.
 *
 * Main thread only.
 */
class StripGesture(
    private val touchSlop: Float,
    private val fullRangeHeights: Double = 1.5,
    private val fineFactor: Double = 0.25,
    private val doubleTapMs: Long = 300,
    private val longPressMs: Long = LOCK_HOLD_MS,
    private val ringDelayMs: Long = RING_DELAY_MS,
) {
    /** Locked strips ignore drags and taps. The UI may set it to mirror its own lock state. */
    var locked: Boolean = false

    /** Last y of every finger on the strip, in down order; the first one drives the value. */
    private val pointers = LinkedHashMap<Int, Float>()
    private var primary = NONE

    private var downTimeMs = 0L
    private var downY = 0f
    private var lastY = 0f
    private var height = 1f
    private var normalized = 0.0

    private var dragging = false
    private var multiTouch = false
    private var longPressDone = false
    private var lastTapUpMs: Long? = null

    /** When [timeout] should be called to fire the long-press, or null if none is pending. */
    val longPressDeadline: Long?
        get() = if (longPressPending()) downTimeMs + longPressMs else null

    /**
     * How far the lock hold has filled (0..1) at [timeMs], for the ring; null when no hold is
     * pending or it is younger than [ringDelayMs] (so taps and the start of a drag show nothing).
     */
    fun lockProgress(timeMs: Long): Float? {
        if (!longPressPending()) return null
        val held = timeMs - downTimeMs
        if (held < ringDelayMs) return null
        val span = (longPressMs - ringDelayMs).coerceAtLeast(1L)
        return ((held - ringDelayMs).toFloat() / span).coerceIn(0f, 1f)
    }

    /**
     * When the UI should next wake while the finger rests ([timeout], and a ring frame): the
     * ring's start, then every [FRAME_MS], never past [longPressDeadline]. Null with no hold pending.
     */
    fun nextWake(timeMs: Long): Long? {
        if (!longPressPending()) return null
        val deadline = downTimeMs + longPressMs
        val ringStart = downTimeMs + ringDelayMs
        if (timeMs < ringStart) return minOf(ringStart, deadline)
        return minOf(timeMs + FRAME_MS, deadline)
    }

    /**
     * A finger touched the strip. [height] is the strip's height in the same units as y;
     * [currentNormalized] is the strip's value now (used when this finger starts the gesture).
     */
    fun down(pointerId: Int, timeMs: Long, y: Float, height: Float, currentNormalized: Double): StripAction? {
        if (pointers.isEmpty()) {
            pointers[pointerId] = y
            primary = pointerId
            downTimeMs = timeMs
            downY = y
            lastY = y
            this.height = if (height > 0f) height else 1f
            normalized = currentNormalized.coerceIn(0.0, 1.0)
            dragging = false
            multiTouch = false
            longPressDone = false
        } else {
            pointers[pointerId] = y
            multiTouch = true
        }
        return null
    }

    fun move(pointerId: Int, timeMs: Long, y: Float): StripAction? {
        if (pointerId !in pointers) return null
        pointers[pointerId] = y
        if (pointerId != primary || longPressDone) return null
        if (!dragging) {
            if (abs(y - downY) <= touchSlop) return null
            dragging = true
            lastY = y
            return null
        }
        val dy = lastY - y
        lastY = y
        if (locked || dy == 0f) return null
        val speed = if (pointers.size > 1) fineFactor else 1.0
        normalized = (normalized + dy / (fullRangeHeights * height) * speed).coerceIn(0.0, 1.0)
        return StripAction.Set(normalized)
    }

    fun up(pointerId: Int, timeMs: Long): StripAction? {
        if (pointerId !in pointers) return null
        pointers.remove(pointerId)
        if (pointerId != primary) return null

        if (pointers.isNotEmpty()) {
            // Hand the drag to the finger that is still down, from where it is now.
            val (next, y) = pointers.entries.first()
            primary = next
            lastY = y
            dragging = true
            return null
        }

        primary = NONE
        val wasTap = !dragging && !multiTouch && !longPressDone
        if (!wasTap) {
            lastTapUpMs = null
            return null
        }
        if (timeMs - downTimeMs >= longPressMs) return fireLongPress()
        if (locked) {
            lastTapUpMs = null
            return null
        }
        val previous = lastTapUpMs
        return if (previous != null && downTimeMs - previous <= doubleTapMs) {
            lastTapUpMs = null
            StripAction.Reset
        } else {
            lastTapUpMs = timeMs
            null
        }
    }

    /** Call at [longPressDeadline] (or later) while the finger is still down. */
    fun timeout(timeMs: Long): StripAction? {
        if (!longPressPending() || timeMs - downTimeMs < longPressMs) return null
        return fireLongPress()
    }

    /** The system took the touch stream away (e.g. ACTION_CANCEL): forget everything. */
    fun cancel() {
        pointers.clear()
        primary = NONE
        dragging = false
        multiTouch = false
        longPressDone = false
        lastTapUpMs = null
    }

    private fun longPressPending() = pointers.isNotEmpty() && !dragging && !multiTouch && !longPressDone

    private fun fireLongPress(): StripAction {
        longPressDone = true
        lastTapUpMs = null
        locked = !locked
        return StripAction.Lock(locked)
    }

    companion object {
        private const val NONE = -1

        /** The still hold that locks or unlocks a strip (owner decision 1-2c). */
        const val LOCK_HOLD_MS = 1000L

        /** The ring appears this far into the hold. */
        const val RING_DELAY_MS = 250L

        /** The ring's redraw interval while it fills. */
        const val FRAME_MS = 16L
    }
}
