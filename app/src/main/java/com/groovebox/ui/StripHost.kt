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
}

/** Strip locks (F4.2) by parameter key, observable by Compose. */
@Stable
class StripLocks {
    private val locked = mutableStateMapOf<String, Boolean>()

    fun isLocked(key: String): Boolean = locked[key] == true

    fun set(key: String, value: Boolean) {
        if (value) locked[key] = true else locked.remove(key)
    }
}
