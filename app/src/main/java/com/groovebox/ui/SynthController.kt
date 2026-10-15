package com.groovebox.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.groovebox.engine.NativeEngine
import com.groovebox.engine.ParamSpec
import com.groovebox.ui.model.ParamMapping
import com.groovebox.ui.model.ShiftState
import com.groovebox.ui.model.SynthLayout
import com.groovebox.ui.model.SynthSection

/**
 * UI state of the Synth screen. It is the source of truth for the strips' values: a strip
 * change updates it and calls NativeEngine.setParam at once; it is re-read from the engine
 * after start and after a preset load. Each parameter has its own state so a drag only
 * recomposes what shows that parameter. Main thread only (NativeEngine's rule).
 */
@Stable
class SynthController {
    val params: List<ParamSpec> = NativeEngine.params
    val presetNames: List<String> = NativeEngine.presetNames

    private val byKey = params.associateBy { it.key }
    private val values = params.map { mutableDoubleStateOf(it.default) }
    private val locks = params.map { mutableStateOf(false) }
    private val shiftState = ShiftState()

    var section by mutableStateOf(SynthSection.Osc)

    /** Shift layer showing (latched or held). */
    var shifted by mutableStateOf(false)
        private set

    /** Index into [presetNames] of the last preset loaded, or -1 before any. */
    var presetIndex by mutableIntStateOf(-1)
        private set

    val presetName: String
        get() = presetNames.getOrNull(presetIndex) ?: "Init"

    /** The four strips' parameters for the current section and layer; null is a reserved slot. */
    val strips: List<ParamSpec?>
        get() = SynthLayout.keys(section, shifted).map { key -> key?.let { byKey[it] } }

    fun spec(key: String): ParamSpec? = byKey[key]

    fun value(spec: ParamSpec): Double = values[spec.id].doubleValue

    fun value(key: String): Double = byKey[key]?.let(::value) ?: 0.0

    fun normalized(spec: ParamSpec): Double = ParamMapping.toNormalized(spec, value(spec))

    fun setNormalized(spec: ParamSpec, normalized: Double) =
        setValue(spec, ParamMapping.fromNormalized(spec, normalized))

    fun setValue(spec: ParamSpec, value: Double) {
        val v = value.coerceIn(spec.min, spec.max)
        val state = values[spec.id]
        if (state.doubleValue == v) return // e.g. an Int strip moving within one step
        state.doubleValue = v
        NativeEngine.setParam(spec.id, v)
    }

    fun reset(spec: ParamSpec) = setValue(spec, spec.default)

    fun isLocked(spec: ParamSpec): Boolean = locks[spec.id].value

    fun setLocked(spec: ParamSpec, locked: Boolean) {
        locks[spec.id].value = locked
    }

    fun shiftPress(timeMs: Long) {
        shiftState.press(timeMs)
        shifted = shiftState.active
    }

    fun shiftRelease(timeMs: Long) {
        shiftState.release(timeMs)
        shifted = shiftState.active
    }

    fun shiftCancel() {
        shiftState.cancel()
        shifted = shiftState.active
    }

    /** Re-reads every value from the engine (after start or a preset load). */
    fun refreshFromEngine() {
        for (p in params) values[p.id].doubleValue = NativeEngine.getParam(p.id)
    }

    fun loadPreset(index: Int) {
        if (index !in presetNames.indices) return
        NativeEngine.loadPreset(index)
        presetIndex = index
        refreshFromEngine()
    }
}
