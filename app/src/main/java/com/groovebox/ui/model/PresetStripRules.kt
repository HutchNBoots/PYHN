package com.groovebox.ui.model

/** Where a touch lands while a slot's preset strip is open. The strip's × is part of [Strip] and closes it from its own tap. */
enum class PresetTouch { Strip, Keys, OpenSlot, Elsewhere }

/**
 * When the preset strip closes (build-21, so presets can be auditioned): playing the keys or
 * touching the strip keeps it open (its own × closes it from its tap handler); a tap anywhere
 * else (the display, rail, strips and top bar) or on the slot whose strip it is closes it.
 * Pure Kotlin.
 */
object PresetStripRules {
    fun closesOn(touch: PresetTouch): Boolean = when (touch) {
        PresetTouch.Strip, PresetTouch.Keys -> false
        PresetTouch.OpenSlot, PresetTouch.Elsewhere -> true
    }

    /** The zone of a touch; the strip lies over the top of the keys, so it wins where they overlap. */
    fun zone(inStrip: Boolean, inKeys: Boolean, inOpenSlot: Boolean): PresetTouch = when {
        inStrip -> PresetTouch.Strip
        inKeys -> PresetTouch.Keys
        inOpenSlot -> PresetTouch.OpenSlot
        else -> PresetTouch.Elsewhere
    }
}
