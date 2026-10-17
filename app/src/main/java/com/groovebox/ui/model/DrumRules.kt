package com.groovebox.ui.model

/**
 * Decay, Start and Rev only change the next hit, so a drag on them replays the sound when it
 * ends (build-22, manual 2-2A), unless the playing beat will replay it anyway. Pure Kotlin.
 */
object DrumReplay {
    val REPLAY_KEYS = setOf("drum.decay", "drum.start", "drum.reverse")

    fun onRelease(key: String, changed: Boolean, playing: Boolean, soundInPattern: Boolean): Boolean =
        changed && key in REPLAY_KEYS && !(playing && soundInPattern)
}

/**
 * Drum strip locks are per sound (build-22): "drum.decay@3" locks Decay of kit key 3 only.
 * Sessions before version 4 locked a parameter for the whole kit; [migrate] turns each such
 * lock into one per sound, so nothing that was locked comes unlocked. Pure Kotlin.
 */
object DrumLockKeys {
    fun of(sound: Int, param: String): String = "$param@$sound"

    fun parse(key: String): Pair<Int, String>? {
        val at = key.lastIndexOf('@')
        if (at <= 0) return null
        val sound = key.substring(at + 1).toIntOrNull() ?: return null
        return sound to key.substring(0, at)
    }

    fun migrate(locks: Set<String>): Set<String> = locks.flatMapTo(LinkedHashSet()) { key ->
        if (parse(key) != null) listOf(key) else (0 until DrumLayout.KEY_COUNT).map { of(it, key) }
    }
}

/** Rev and Choke are tap cells on the Drum Shift layer (build-22): one cell per option, tap to choose. */
object DrumTapCells {
    private val OPTIONS = mapOf("drum.reverse" to listOf("Off", "On"), "drum.choke" to listOf("None", "A", "B"))

    fun isCell(key: String): Boolean = key in OPTIONS

    fun options(key: String): List<String> = OPTIONS[key].orEmpty()
}

/**
 * Touch grammar of the Rev / Choke tap cells (build-22, review 1): a quick still touch on a cell
 * is a [Action.Tap] (choose it); a 1 s still hold anywhere on the cells toggles the lock, with
 * the same timing and ring as a strip ([StripGesture.LOCK_HOLD_MS], [StripGesture.RING_DELAY_MS]);
 * moving past the slop (2D) cancels both. Times are monotonic ms. Main thread only.
 */
class CellHold(private val touchSlop: Float) {
    enum class Action { Tap, Lock }

    private var downAt = -1L
    private var x0 = 0f
    private var y0 = 0f
    private var moved = false
    private var locked = false

    fun down(timeMs: Long, x: Float, y: Float) {
        downAt = timeMs
        x0 = x
        y0 = y
        moved = false
        locked = false
    }

    fun move(timeMs: Long, x: Float, y: Float) {
        if (downAt >= 0 && kotlin.math.hypot(x - x0, y - y0) > touchSlop) moved = true
    }

    private fun pending() = downAt >= 0 && !moved && !locked

    val deadline: Long?
        get() = if (pending()) downAt + LOCK_HOLD_MS else null

    fun timeout(timeMs: Long): Action? {
        if (!pending() || timeMs - downAt < LOCK_HOLD_MS) return null
        locked = true
        return Action.Lock
    }

    fun up(timeMs: Long): Action? {
        if (downAt < 0) return null
        val action = when {
            locked || moved -> null
            timeMs - downAt >= LOCK_HOLD_MS -> Action.Lock // the timer was late
            else -> Action.Tap
        }
        downAt = -1L
        return action
    }

    fun cancel() {
        downAt = -1L
    }

    /** The ring, as [StripGesture.lockProgress]. */
    fun lockProgress(timeMs: Long): Float? {
        if (!pending()) return null
        val held = timeMs - downAt
        if (held < StripGesture.RING_DELAY_MS) return null
        return ((held - StripGesture.RING_DELAY_MS).toFloat() / (LOCK_HOLD_MS - StripGesture.RING_DELAY_MS)).coerceIn(0f, 1f)
    }

    companion object {
        const val LOCK_HOLD_MS = StripGesture.LOCK_HOLD_MS
    }
}

/**
 * Hold one slot button and tap another: the held slot's sound is copied onto the tapped one
 * (build-22, manual 1-4B). Synth slots only. A finger counts as holding its slot only after
 * [MIN_HOLD_MS] (review 2), so a two-finger brush over the slots copies nothing. While two slot
 * fingers are down, or after a copy from a still-held slot, no slot opens its presets
 * ([suppressLongPress], review 3). Fed with every pointer down / up on the slot buttons; main
 * thread only.
 */
class SlotCopyGesture(private val isSynth: (Int) -> Boolean) {
    data class Copy(val from: Int, val to: Int)

    private class Held(val slot: Int, val downAt: Long)

    private val held = LinkedHashMap<Long, Held>()
    private val sources = HashSet<Int>()

    fun down(pointer: Long, slot: Int, timeMs: Long): Copy? {
        val from = held.values.firstOrNull { it.slot != slot && isSynth(it.slot) && timeMs - it.downAt >= MIN_HOLD_MS }?.slot
        held[pointer] = Held(slot, timeMs)
        if (from == null || !isSynth(slot)) return null
        sources += from
        return Copy(from, slot)
    }

    fun up(pointer: Long) {
        held.remove(pointer)
        sources.retainAll(held.values.map { it.slot }.toSet())
    }

    /** [slot]'s long-press (its preset strip) stands down: another slot finger is down, or it gave a copy. */
    fun suppressLongPress(slot: Int): Boolean = slot in sources || held.values.any { it.slot != slot }

    fun cancel() {
        held.clear()
        sources.clear()
    }

    companion object {
        const val MIN_HOLD_MS = 300L
    }
}
