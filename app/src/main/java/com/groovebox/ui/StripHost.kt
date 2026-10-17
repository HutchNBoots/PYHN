package com.groovebox.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import com.groovebox.engine.ParamSpec
import com.groovebox.ui.model.ParamMapping

/**
 * What a colour strip drives: the synth slots, the drum kit and the sequencer each provide
 * one. Values are in the spec's real units. Main thread only.
 */
@Stable
interface StripHost {
    fun value(spec: ParamSpec): Double
    fun setValue(spec: ParamSpec, value: Double)
    fun reset(spec: ParamSpec) = setValue(spec, spec.default)
    fun isLocked(spec: ParamSpec): Boolean
    fun setLocked(spec: ParamSpec, locked: Boolean)
    fun format(spec: ParamSpec, value: Double): String = ParamMapping.format(spec, value)

    /** A change requested but not yet applied (a queued pattern): the strip blinks its value. */
    fun pending(spec: ParamSpec): Boolean = false

    /** Blink phase for [pending] strips. */
    val blinkOn: Boolean get() = true

    /** The value shown is only a default (e.g. a selected step without that hit): the strip dims it. */
    fun dimmed(spec: ParamSpec): Boolean = false

    /** A finger went down on the strip showing [spec] (the first of a gesture), e.g. to start an undo step. */
    fun gestureStart(spec: ParamSpec) {}

    /** The last finger of that gesture lifted, or the gesture was dropped. */
    fun gestureEnd(spec: ParamSpec) {}
}

/** Strip locks (F4.2) by parameter key, observable by Compose. [onChange] runs after each change (autosave). */
@Stable
class StripLocks(private val onChange: () -> Unit = {}) {
    private val locked = mutableStateMapOf<String, Boolean>()

    fun isLocked(key: String): Boolean = locked[key] == true

    fun set(key: String, value: Boolean) {
        if (value == isLocked(key)) return
        if (value) locked[key] = true else locked.remove(key)
        onChange()
    }

    /** The locked keys, for saving. */
    val keys: Set<String>
        get() = locked.keys.toSet()

    /** Replaces every lock (a restored session); no [onChange]. */
    fun restore(keys: Set<String>) {
        locked.clear()
        for (k in keys) locked[k] = true
    }
}
