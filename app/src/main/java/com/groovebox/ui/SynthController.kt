package com.groovebox.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.groovebox.engine.NativeEngine
import com.groovebox.engine.ParamSpec
import com.groovebox.ui.model.ShiftState
import com.groovebox.ui.model.SynthLayout
import com.groovebox.ui.model.SynthSection

/** The top bar's Shift button (F4.4), shared by every screen. Main thread only. */
@Stable
class ShiftController {
    private val state = ShiftState()

    /** Shift layer showing (latched or held). */
    var shifted by mutableStateOf(false)
        private set

    /** Called with the new value whenever [shifted] changes. */
    var onChange: ((Boolean) -> Unit)? = null

    fun press(timeMs: Long) = update { state.press(timeMs) }
    fun release(timeMs: Long) = update { state.release(timeMs) }
    fun cancel() = update { state.cancel() }

    private inline fun update(action: () -> Unit) {
        action()
        if (state.active == shifted) return
        shifted = state.active
        onChange?.invoke(shifted)
    }
}

/**
 * UI state of one synth slot (engine slots 1..7). It is the source of truth for the strips'
 * values: a strip change updates it and calls NativeEngine.setParam at once; it is re-read
 * from the engine after start and after a preset load. Each slot keeps its own values,
 * locks, section and preset. Each parameter has its own state so a drag only recomposes
 * what shows that parameter. Main thread only (NativeEngine's rule).
 */
@Stable
class SynthController(
    val slot: Int,
    private val shift: ShiftController,
    private val onEdit: () -> Unit = {},
) : StripHost {
    val params: List<ParamSpec> = NativeEngine.params
    val presetNames: List<String> = NativeEngine.presetNames

    private val byKey = params.associateBy { it.key }
    private val values = params.map { mutableDoubleStateOf(it.default) }
    private val locks = StripLocks()

    var section by mutableStateOf(SynthSection.Osc)

    val shifted: Boolean
        get() = shift.shifted

    /** Index into [presetNames] of the last preset loaded, or -1 before any. */
    var presetIndex by mutableIntStateOf(-1)
        private set

    val presetName: String
        get() = presetNames.getOrNull(presetIndex) ?: "Init"

    /** The four strips' parameters for the current section and layer; null is a reserved slot. */
    val strips: List<ParamSpec?>
        get() = SynthLayout.keys(section, shifted).map { key -> key?.let { byKey[it] } }

    fun spec(key: String): ParamSpec? = byKey[key]

    fun spec(id: Int): ParamSpec? = params.getOrNull(id)

    override fun value(spec: ParamSpec): Double = values[spec.id].doubleValue

    fun value(key: String): Double = byKey[key]?.let(::value) ?: 0.0

    override fun setValue(spec: ParamSpec, value: Double) {
        val v = value.coerceIn(spec.min, spec.max)
        onEdit()
        val state = values[spec.id]
        if (state.doubleValue == v) return // e.g. an Int strip moving within one step
        state.doubleValue = v
        NativeEngine.setParam(slot, spec.id, v)
    }

    override fun isLocked(spec: ParamSpec): Boolean = locks.isLocked(spec.key)

    override fun setLocked(spec: ParamSpec, locked: Boolean) = locks.set(spec.key, locked)

    /** Re-reads every value from the engine (after start or a preset load). */
    fun refreshFromEngine() {
        for (p in params) values[p.id].doubleValue = NativeEngine.getParam(slot, p.id)
    }

    fun loadPreset(index: Int) {
        if (index !in presetNames.indices) return
        NativeEngine.loadPreset(slot, index)
        presetIndex = index
        refreshFromEngine()
    }
}
