package com.groovebox.ui.model

/** Where a touch lands while a slot's preset strip is open. */
enum class PresetTouch { Strip, Close, Keys, OpenSlot, Elsewhere }

/**
 * When the preset strip closes (build-21, so presets can be auditioned): playing the keys or
 * touching the strip keeps it open; its own × closes it, as does a tap anywhere else (the
 * display, rail, strips and top bar) or on the slot whose strip it is. Pure Kotlin.
 */
object PresetStripRules {
    fun closesOn(touch: PresetTouch): Boolean = when (touch) {
        PresetTouch.Strip, PresetTouch.Keys -> false
        PresetTouch.Close, PresetTouch.OpenSlot, PresetTouch.Elsewhere -> true
    }

    /** The zone of a touch; the strip lies over the top of the keys, so it wins where they overlap. */
    fun zone(inStrip: Boolean, inClose: Boolean, inKeys: Boolean, inOpenSlot: Boolean): PresetTouch = when {
        inClose -> PresetTouch.Close
        inStrip -> PresetTouch.Strip
        inKeys -> PresetTouch.Keys
        inOpenSlot -> PresetTouch.OpenSlot
        else -> PresetTouch.Elsewhere
    }
}
