package com.groovebox.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.groovebox.engine.NativeEngine
import com.groovebox.engine.ParamSpec
import com.groovebox.ui.model.DrumLayout

/**
 * UI state of the drum kit (slot 1, engine slot 0; F2.2). Every key has its own value of each
 * drum parameter; the strips edit the key played (or touched) last. Values are re-read from
 * the engine after start. Main thread only.
 */
@Stable
class DrumController(
    private val shift: ShiftController,
    private val onEdit: () -> Unit = {},
) : StripHost {
    val params: List<ParamSpec> = NativeEngine.drumParams
    val soundNames: List<String> = NativeEngine.drumSoundNames

    private val byKey = params.associateBy { it.key }
    private val values = List(DrumLayout.KEY_COUNT) { params.map { mutableDoubleStateOf(it.default) } }
    private val locks = StripLocks()

    /** The kit key (0..15) the strips edit. */
    var selectedKey by mutableIntStateOf(0)
        private set

    val shifted: Boolean
        get() = shift.shifted

    val strips: List<ParamSpec?>
        get() = DrumLayout.keys(shifted).map { byKey[it] }

    /** Short key labels for the keyboard, by MIDI note. */
    val keyLabels: Map<Int, String> by lazy {
        soundNames.mapIndexed { key, name -> DrumLayout.noteOf(key) to DrumLayout.shortName(name) }.toMap()
    }

    fun soundName(key: Int): String = soundNames.getOrNull(key)?.takeIf { it.isNotBlank() } ?: "Sound ${key + 1}"

    fun spec(key: String): ParamSpec? = byKey[key]

    fun select(key: Int) {
        if (key in 0 until DrumLayout.KEY_COUNT) selectedKey = key
    }

    fun value(key: Int, spec: ParamSpec): Double = values[key][spec.id].doubleValue

    fun value(name: String): Double = byKey[name]?.let(::value) ?: 0.0

    override fun value(spec: ParamSpec): Double = value(selectedKey, spec)

    override fun setValue(spec: ParamSpec, value: Double) {
        val v = value.coerceIn(spec.min, spec.max)
        onEdit()
        val state = values[selectedKey][spec.id]
        if (state.doubleValue == v) return
        state.doubleValue = v
        NativeEngine.setDrumParam(selectedKey, spec.id, v)
    }

    override fun isLocked(spec: ParamSpec): Boolean = locks.isLocked(spec.key)

    override fun setLocked(spec: ParamSpec, locked: Boolean) = locks.set(spec.key, locked)

    fun refreshFromEngine() {
        for (key in 0 until DrumLayout.KEY_COUNT) {
            for (p in params) values[key][p.id].doubleValue = NativeEngine.getDrumParam(key, p.id)
        }
    }
}
