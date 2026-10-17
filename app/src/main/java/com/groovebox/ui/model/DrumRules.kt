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
 * Hold one slot button and tap another: the held slot's sound is copied onto the tapped one
 * (build-22, manual 1-4B). Synth slots only. Fed with every pointer down / up on the slot
 * buttons; main thread only.
 */
class SlotCopyGesture(private val isSynth: (Int) -> Boolean) {
    data class Copy(val from: Int, val to: Int)

    private val held = LinkedHashMap<Long, Int>()

    fun down(pointer: Long, slot: Int): Copy? {
        val from = held.values.firstOrNull { it != slot && isSynth(it) }
        held[pointer] = slot
        return if (from != null && isSynth(slot)) Copy(from, slot) else null
    }

    fun up(pointer: Long) {
        held.remove(pointer)
    }

    fun cancel() = held.clear()
}
