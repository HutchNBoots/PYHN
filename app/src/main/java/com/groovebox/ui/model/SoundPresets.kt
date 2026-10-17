package com.groovebox.ui.model

import kotlin.math.abs
import kotlin.math.max

/**
 * Preset rules of the synth slots (build-22, manual 1.1 and 1.4): the value a double-tap
 * returns to, the "edited" mark, loads that respect strip locks, user preset names and the
 * copy of one slot's sound to another. Pure Kotlin.
 */
object SoundPresets {
    /** At most this many user presets (there is no delete yet, so a stray hold can't fill memory). */
    const val MAX_USER_PRESETS = 48

    /** Strips that drive more than one parameter: their lock keeps all of them. */
    val LOCKED_TOGETHER: Map<String, List<String>> = mapOf("lfo.rate" to listOf("lfo.sync"))

    /** A double-tap: the preset's value for [key], or the engine default when the preset has none. */
    fun resetValue(key: String, presetValues: Map<String, Double>, engineDefault: Double): Double =
        presetValues[key] ?: engineDefault

    /** True when some value differs from [loadValues] (the values right after the last load). */
    fun edited(values: Map<String, Double>, loadValues: Map<String, Double>): Boolean =
        values.any { (k, v) -> loadValues[k]?.let { !same(v, it) } ?: false }

    /** Engine values pass through a float; anything closer than that is the same value. */
    fun same(a: Double, b: Double): Boolean = abs(a - b) <= 1e-6 * max(1.0, abs(b))

    fun displayName(name: String, edited: Boolean): String = if (edited) "$name*" else name

    /** Every key a lock on [locked] keys protects. */
    fun lockedKeys(locked: Set<String>): Set<String> =
        locked + locked.flatMap { LOCKED_TOGETHER[it].orEmpty() }

    /** The values after loading [preset] over [current]: locked parameters keep their value. */
    fun applyRespectingLocks(preset: Map<String, Double>, current: Map<String, Double>, locked: Set<String>): Map<String, Double> {
        val keep = lockedKeys(locked)
        val out = LinkedHashMap(current)
        for ((k, v) in preset) if (k !in keep || k !in current) out[k] = v
        return out
    }

    /** "Rubber Floor 3" → "Rubber Floor"; a trailing "*" (edited) and spaces go too. */
    fun baseName(name: String): String {
        val n = name.trim().removeSuffix("*").trim()
        val cut = TRAILING_NUMBER.find(n) ?: return n
        val base = n.substring(0, cut.range.first).trim()
        return base.ifEmpty { n }
    }

    /**
     * The auto-name for saving a slot named [current]: its base name and the lowest free number
     * from 2 ("Rubber Floor 2"), against every preset name in [taken]. Null when the library holds
     * [MAX_USER_PRESETS] already ([userCount]).
     */
    fun userName(current: String, taken: Collection<String>, userCount: Int = 0): String? {
        if (userCount >= MAX_USER_PRESETS) return null
        val base = baseName(current)
        val used = taken.toHashSet()
        var n = 2
        while ("$base $n" in used) n++
        return "$base $n"
    }

    /**
     * [source]'s sound copied onto a slot that has [target] and [targetLocks]: the source's
     * preset, baselines and values, except that the target's locked values stay (as on a load).
     */
    fun copy(source: SynthSound, target: SynthSound, targetLocks: Set<String>): SynthSound =
        source.copy(values = applyRespectingLocks(source.values, target.values, targetLocks))

    private val TRAILING_NUMBER = Regex("""\s+\d+$""")
}

/**
 * The preset strip's groups (owner decision 1-1B): the engine stores a category per preset
 * (engine/core/src/Presets.cpp) but does not expose it, so the 16 factory names are mapped
 * here, with the factory order as the fallback. Pure Kotlin.
 */
object PresetGroups {
    val NAMES = listOf("Start", "Bass", "Lead", "Pad", "Pluck", "FX")
    const val USER = "User"

    private val BY_NAME = mapOf(
        "First Light" to 0,
        "Rubber Floor" to 1, "Deep Current" to 1, "Gravel Bass" to 1,
        "Glass Wire" to 2, "Copper Lead" to 2, "Whistle Pine" to 2,
        "Slow Tide" to 3, "Velvet Fog" to 3, "Northern Hall" to 3,
        "Rain Glass" to 4, "Thumb Piano" to 4, "Paper Harp" to 4,
        "Siren Loop" to 5, "Static Garden" to 5, "Laser Drip" to 5,
    )

    /** Group of factory preset [index] named [name]: by name, else by its place (1 + 5 groups of 3). */
    fun groupOf(index: Int, name: String): Int =
        BY_NAME[name] ?: if (index <= 0) 0 else ((index - 1) / 3 + 1).coerceAtMost(NAMES.lastIndex)

    /** The non-empty groups in order, each with its preset indexes (in factory order). */
    fun groups(names: List<String>): List<Pair<String, List<Int>>> {
        val byGroup = names.indices.groupBy { groupOf(it, names[it]) }
        return NAMES.indices.mapNotNull { g -> byGroup[g]?.let { NAMES[g] to it } }
    }
}
