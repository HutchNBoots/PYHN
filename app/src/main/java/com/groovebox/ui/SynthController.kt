package com.groovebox.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.groovebox.engine.Curve
import com.groovebox.engine.NativeEngine
import com.groovebox.engine.ParamSpec
import com.groovebox.storage.SynthSnapshot
import com.groovebox.storage.UserPreset
import com.groovebox.ui.model.KeyOctave
import com.groovebox.ui.model.KeyScale
import com.groovebox.ui.model.LfoDetent
import com.groovebox.ui.model.LfoSync
import com.groovebox.ui.model.ParamMapping
import com.groovebox.ui.model.PlayStrips
import com.groovebox.ui.model.PresetRef
import com.groovebox.ui.model.Scale
import com.groovebox.ui.model.ShiftState
import com.groovebox.ui.model.SoundEdit
import com.groovebox.ui.model.SoundHistory
import com.groovebox.ui.model.SoundPresets
import com.groovebox.ui.model.SoundTarget
import com.groovebox.ui.model.SynthLayout
import com.groovebox.ui.model.SynthSection
import com.groovebox.ui.model.SynthSound
import kotlin.math.roundToInt

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

/** The session settings the Synth screen's Play rail item shows (build-22): key, scale, keyboard octave. */
interface PlayControls {
    val keyScale: KeyScale
    val keyOctave: Int
    fun setKey(root: Int)
    fun setScale(scale: Scale)
    fun setKeyOctave(octave: Int)
}

/** The user presets (build-22): saved from any slot, shared by all of them, saved with the session. */
@Stable
class UserPresetLibrary(private val onChange: () -> Unit = {}) {
    val presets = mutableStateListOf<UserPreset>()

    fun find(name: String): UserPreset? = presets.firstOrNull { it.name == name }

    fun add(preset: UserPreset) {
        presets += preset
        onChange()
    }

    /** A restored session's list; no [onChange]. */
    fun restore(list: List<UserPreset>) {
        presets.clear()
        presets.addAll(list)
    }

    val snapshot: List<UserPreset>
        get() = presets.toList()
}

/**
 * UI state of one synth slot (engine slots 1..7). It is the source of truth for the strips'
 * values: a strip change updates it and calls NativeEngine.setParam at once; it is re-read
 * from the engine after start and after a preset load. Each slot keeps its own values,
 * locks, section and preset. Each parameter has its own state so a drag only recomposes
 * what shows that parameter. [onChange] runs after anything worth saving changes (autosave);
 * [tempo] is the transport's, for the synced LFO rate's division. Main thread only
 * (NativeEngine's rule).
 *
 * Build-22: every strip gesture is one step of the shared [history] ([onHistory] after each
 * record); a double-tap returns to the preset's value; preset loads keep locked values; the
 * preset name gets an edited mark; user presets come from [library]; the Mod Rate strip
 * carries tempo sync below its detent ([LfoDetent]); the Play item's strips go to [play].
 */
@Stable
class SynthController(
    val slot: Int,
    private val shift: ShiftController,
    private val onEdit: () -> Unit = {},
    private val onChange: () -> Unit = {},
    private val tempo: () -> Double = { 120.0 },
    private val history: SoundHistory = SoundHistory(),
    private val onHistory: () -> Unit = {},
    val library: UserPresetLibrary = UserPresetLibrary(),
    private val play: PlayControls? = null,
) : StripHost {
    /** The engine's parameters, with the build-22 label renames (EnvAmt, KeyTrk, Fade). */
    val params: List<ParamSpec> = NativeEngine.params.map { it.copy(label = SynthLayout.label(it.key, it.label)) }
    val presetNames: List<String> = NativeEngine.presetNames

    private val byKey = params.associateBy { it.key }
    private val values = params.map { mutableDoubleStateOf(it.default) }
    private val locks = StripLocks(onChange)

    private val rateSpec = byKey[LFO_RATE]
    private val syncSpec = byKey[LFO_SYNC]

    /** The main-layer Rate strip: a 0..1 position over [LfoDetent]'s divisions, detent and free Hz. */
    private val rateStrip: ParamSpec? = rateSpec?.copy(min = 0.0, max = 1.0, curve = Curve.Linear, unit = "")

    private var sectionState by mutableStateOf(SynthSection.Osc)

    var section: SynthSection
        get() = sectionState
        set(value) {
            if (value == sectionState) return
            sectionState = value
            onChange()
        }

    val shifted: Boolean
        get() = shift.shifted

    /** Index into [presetNames] of the last factory preset loaded, or -1 (none, or a user preset). */
    var presetIndex by mutableIntStateOf(-1)
        private set

    /** The user preset last loaded or saved, or null for a factory preset. */
    var userPresetName by mutableStateOf<String?>(null)
        private set

    /** The preset's own values (what a double-tap returns to). */
    private var presetValues by mutableStateOf<Map<String, Double>>(emptyMap())

    /** The values right after the last load (what the edited mark compares with). */
    private var loadValues by mutableStateOf<Map<String, Double>>(emptyMap())

    val presetName: String
        get() = userPresetName ?: presetNames.getOrNull(presetIndex) ?: "Init"

    /** Some value moved since the preset was loaded (the "*" after the name; reloading clears it). */
    val edited: Boolean
        get() = SoundPresets.edited(currentValues(), loadValues)

    /** "Glass Wire*" while edited. */
    val displayName: String
        get() = SoundPresets.displayName(presetName, edited)

    private val presetRef: PresetRef
        get() = PresetRef(presetName, if (userPresetName == null) presetIndex else -1, userPresetName != null)

    /** The four strips' parameters for the current section and layer; null is a reserved slot. */
    val strips: List<ParamSpec?>
        get() = SynthLayout.keys(section, shifted).map { key ->
            when {
                key == null -> null
                key == LFO_RATE && rateStrip != null -> rateStrip
                SynthLayout.isPlayKey(key) -> if (play != null) PlayStrips.spec(key) else null
                else -> byKey[key]
            }
        }

    fun spec(key: String): ParamSpec? = byKey[key]

    fun spec(id: Int): ParamSpec? = params.getOrNull(id)

    override fun value(spec: ParamSpec): Double = when {
        spec === rateStrip -> ratePosition()
        SynthLayout.isPlayKey(spec.key) -> playValue(spec.key)
        else -> values[spec.id].doubleValue
    }

    fun value(key: String): Double = byKey[key]?.let { values[it.id].doubleValue } ?: 0.0

    override fun setValue(spec: ParamSpec, value: Double) {
        when {
            spec === rateStrip -> setRate(value)
            SynthLayout.isPlayKey(spec.key) -> setPlay(spec.key, value)
            else -> setRaw(spec, value)
        }
    }

    /** One engine parameter, unconditionally (no lock check: callers decide). */
    private fun setRaw(spec: ParamSpec, value: Double) {
        val v = value.coerceIn(spec.min, spec.max)
        onEdit()
        val state = values[spec.id]
        if (state.doubleValue == v) return // e.g. an Int strip moving within one step
        state.doubleValue = v
        NativeEngine.setParam(slot, spec.id, v)
        onChange()
    }

    private fun ratePosition(): Double {
        val rate = rateSpec ?: return 0.0
        return LfoDetent.position(lfoSynced, value(LFO_RATE), rate, tempo())
    }

    private fun setRate(position: Double) {
        val rate = rateSpec ?: return
        val sync = syncSpec ?: return
        val r = LfoDetent.fromPosition(position, rate, tempo())
        setRaw(sync, if (r.synced) LfoSync.SYNC_TEMPO.toDouble() else 0.0)
        // Synced: only a new division moves the Hz, so a drag within one cell sends nothing.
        if (!r.synced || !lfoSynced || LfoSync.nearestDivision(value(LFO_RATE).toFloat(), tempoF()) != r.division) {
            setRaw(rate, r.hz)
        }
    }

    private fun tempoF(): Float = tempo().let { if (it.isNaN()) 120f else it.coerceIn(1.0, 1000.0).toFloat() }

    private fun playValue(key: String): Double {
        val p = play ?: return 0.0
        return when (key) {
            PlayStrips.KEY -> p.keyScale.root.toDouble()
            PlayStrips.SCALE -> p.keyScale.scale.ordinal.toDouble()
            PlayStrips.OCTAVE -> p.keyOctave.toDouble()
            else -> 0.0
        }
    }

    private fun setPlay(key: String, value: Double) {
        val p = play ?: return
        onEdit()
        val i = value.roundToInt()
        when (key) {
            PlayStrips.KEY -> p.setKey(i.coerceIn(0, 11))
            PlayStrips.SCALE -> p.setScale(Scale.entries[i.coerceIn(0, Scale.entries.lastIndex)])
            PlayStrips.OCTAVE -> p.setKeyOctave(KeyOctave.clamp(i))
        }
    }

    /** A double-tap: back to the preset's value (build-22), not the engine default. */
    override fun reset(spec: ParamSpec) {
        when {
            spec === rateStrip -> {
                val rate = rateSpec ?: return
                val sync = syncSpec ?: return
                setRaw(sync, SoundPresets.resetValue(LFO_SYNC, presetValues, sync.default))
                setRaw(rate, SoundPresets.resetValue(LFO_RATE, presetValues, rate.default))
            }
            SynthLayout.isPlayKey(spec.key) -> setPlay(spec.key, spec.default)
            else -> setRaw(spec, SoundPresets.resetValue(spec.key, presetValues, spec.default))
        }
    }

    override fun format(spec: ParamSpec, value: Double): String = when {
        spec === rateStrip -> rateSpec?.let { LfoDetent.label(value, it, tempo()) } ?: ""
        spec.key == LFO_RATE && lfoSynced -> LfoSync.label(value, tempo())
        spec.key == VOICE_MODE -> SynthLayout.voiceModeLabel(value.roundToInt())
        spec.key == PlayStrips.KEY -> KeyScale.KEY_NAMES[value.roundToInt().coerceIn(0, 11)]
        spec.key == PlayStrips.SCALE -> Scale.entries[value.roundToInt().coerceIn(0, Scale.entries.lastIndex)].label
        spec.key == PlayStrips.OCTAVE -> KeyOctave.format(value.roundToInt())
        else -> ParamMapping.format(spec, value)
    }

    /** Glide does nothing in Poly: its strip is dimmed there (owner decision 1-3c). */
    override fun dimmed(spec: ParamSpec): Boolean = SynthLayout.dimmed(spec.key, value(VOICE_MODE).roundToInt())

    val lfoSynced: Boolean
        get() = value(LFO_SYNC) >= 0.5

    /** The transport's tempo (for the LFO trace). */
    val tempoBpm: Double
        get() = tempo()

    override fun isLocked(spec: ParamSpec): Boolean = locks.isLocked(spec.key)

    override fun setLocked(spec: ParamSpec, locked: Boolean) = locks.set(spec.key, locked)

    // --- sound undo (build-22): one gesture = one step ---

    private fun gestureKeys(spec: ParamSpec): List<String> = when {
        spec === rateStrip -> listOf(LFO_RATE, LFO_SYNC)
        SynthLayout.isPlayKey(spec.key) -> emptyList() // session settings, not the sound
        else -> listOf(spec.key)
    }

    override fun gestureStart(spec: ParamSpec) {
        val keys = gestureKeys(spec)
        if (keys.isNotEmpty()) history.begin(gestureId(spec), SoundTarget.Synth(slot), keys.associateWith(::value))
    }

    override fun gestureEnd(spec: ParamSpec) {
        val keys = gestureKeys(spec)
        if (keys.isNotEmpty() && history.end(gestureId(spec), keys.associateWith(::value))) onHistory()
    }

    private fun gestureId(spec: ParamSpec) = "synth$slot:${spec.key}"

    /** Applies an undo ([undo]) or redo of [edit], which targets this slot. Locks don't block it. */
    fun applyEdit(edit: SoundEdit, undo: Boolean) {
        when (edit) {
            is SoundEdit.Params -> for (c in edit.changes) byKey[c.key]?.let { setRaw(it, if (undo) c.before else c.after) }
            is SoundEdit.Whole -> applySound(if (undo) edit.before else edit.after)
        }
    }

    // --- presets ---

    private fun currentValues(): Map<String, Double> = params.associate { it.key to values[it.id].doubleValue }

    /** The whole sound, for undo, copy and save. */
    fun sound(): SynthSound = SynthSound(presetRef, currentValues(), presetValues, loadValues)

    /** Puts [s] into this slot (every value to the engine), as an undo, redo or copy does. */
    private fun applySound(s: SynthSound) {
        for (p in params) {
            val v = (s.values[p.key] ?: s.presetValues[p.key] ?: p.default).coerceIn(p.min, p.max)
            values[p.id].doubleValue = v
            NativeEngine.setParam(slot, p.id, v)
        }
        userPresetName = if (s.preset.user) s.preset.name else null
        presetIndex = if (s.preset.user) -1 else s.preset.factoryIndex
        presetValues = s.presetValues
        loadValues = s.loadValues
        onChange()
    }

    /** Values of the engine for this slot, as the UI holds them. */
    private fun readEngine(): Map<String, Double> = params.associate { it.key to NativeEngine.getParam(slot, it.id) }

    /** Re-reads every value from the engine (after start). The first read is the baseline of a slot with no preset. */
    fun refreshFromEngine() {
        for (p in params) values[p.id].doubleValue = NativeEngine.getParam(slot, p.id)
        if (presetValues.isEmpty()) {
            presetValues = currentValues()
            loadValues = presetValues
        }
    }

    /**
     * Loads factory preset [index]; locked strips keep their values (build-22). One undo step
     * unless [record] is false (the demo at launch).
     */
    fun loadPreset(index: Int, record: Boolean = true) {
        if (index !in presetNames.indices) return
        val before = sound()
        NativeEngine.loadPreset(slot, index)
        val preset = readEngine()
        load(preset, PresetRef(presetNames[index], index), before, record)
    }

    /** Loads user preset [name] as a factory one would load. */
    fun loadUserPreset(name: String) {
        val p = library.find(name) ?: return
        val before = sound()
        val preset = params.associate { it.key to (p.values[it.key] ?: it.default).coerceIn(it.min, it.max) }
        load(preset, PresetRef(name, -1, user = true), before, record = true)
    }

    private fun load(preset: Map<String, Double>, ref: PresetRef, before: SynthSound, record: Boolean) {
        val after = SoundPresets.applyRespectingLocks(preset, before.values, locks.keys)
        for (p in params) {
            val v = after[p.key] ?: continue
            values[p.id].doubleValue = v
            // A factory load already set the engine: only kept (locked) values go back.
            if (ref.user || v != preset[p.key]) NativeEngine.setParam(slot, p.id, v)
        }
        presetValues = preset
        loadValues = after
        userPresetName = if (ref.user) ref.name else null
        presetIndex = if (ref.user) -1 else ref.factoryIndex
        onChange()
        if (record) {
            history.record(SoundEdit.Whole(SoundTarget.Synth(slot), before, sound()))
            onHistory()
        }
    }

    /**
     * Shift + long-press the display (build-22): saves this slot's sound as a user preset with an
     * auto-name ("Rubber Floor 2"), no dialog; the slot then plays that preset. Returns the name,
     * or null when the library is full.
     */
    fun saveUserPreset(): String? {
        val taken = presetNames + library.presets.map { it.name }
        val name = SoundPresets.userName(presetName, taken, library.presets.size) ?: return null
        val before = sound()
        val now = currentValues()
        library.add(UserPreset(name, now))
        userPresetName = name
        presetIndex = -1
        presetValues = now
        loadValues = now
        onChange()
        // One step (review 8): undo goes back to the earlier preset's name and mark; the saved preset stays in the library.
        history.record(SoundEdit.Whole(SoundTarget.Synth(slot), before, sound()))
        onHistory()
        return name
    }

    /** Hold a slot and tap this one (build-22): [source]'s sound comes here; locked values stay. One undo step. */
    fun copyFrom(source: SynthSound) {
        val before = sound()
        applySound(SoundPresets.copy(source, before, locks.keys))
        history.record(SoundEdit.Whole(SoundTarget.Synth(slot), before, sound()))
        onHistory()
    }

    fun snapshot() = SynthSnapshot(
        presetIndex = if (userPresetName == null) presetIndex else -1,
        presetName = userPresetName ?: presetNames.getOrNull(presetIndex) ?: "",
        section = section.name,
        values = currentValues(),
        locks = locks.keys,
        userPreset = userPresetName != null,
        edited = edited,
    )

    /**
     * A saved slot (F7.6): its preset loads first (so a parameter newer than the save gets
     * the preset's value), then every saved value goes to the engine. A preset is found by
     * name first, so a reordered preset list still finds it; a user preset by its name in
     * [library] (restored first). No [onChange].
     */
    fun restore(saved: SynthSnapshot) {
        val user = if (saved.userPreset) library.find(saved.presetName) else null
        val index = when {
            saved.userPreset -> null
            saved.presetName.isEmpty() -> null // no preset was ever loaded
            else -> presetNames.indexOf(saved.presetName).takeIf { it >= 0 } ?: saved.presetIndex.takeIf { it in presetNames.indices }
        }
        var preset: Map<String, Double>? = null
        if (index != null) {
            NativeEngine.loadPreset(slot, index)
            presetIndex = index
            userPresetName = null
            preset = readEngine()
        } else if (saved.userPreset) {
            presetIndex = -1
            userPresetName = saved.presetName
            preset = params.associate { it.key to (user?.values?.get(it.key) ?: saved.values[it.key] ?: it.default) }
        }
        for (p in params) values[p.id].doubleValue = preset?.get(p.key) ?: NativeEngine.getParam(slot, p.id)
        presetValues = preset ?: currentValues()
        loadValues = presetValues
        for (p in params) {
            val v = (saved.values[p.key] ?: preset?.get(p.key) ?: continue).coerceIn(p.min, p.max)
            values[p.id].doubleValue = v
            NativeEngine.setParam(slot, p.id, v)
        }
        // Review 9: a slot saved unmarked (e.g. a load that kept locked values) comes back unmarked.
        if (!saved.edited) loadValues = currentValues()
        sectionState = SynthSection.entries.firstOrNull { it.name == saved.section } ?: SynthSection.Osc
        locks.restore(saved.locks)
        lastTempo = tempo()
    }

    // --- synced LFO across tempo changes (review 5) ---

    /** The tempo the synced Rate's Hz was last set for. */
    private var lastTempo = Double.NaN

    /**
     * The tempo moved: a synced LFO keeps its division ([LfoDetent.retime]) instead of drifting
     * to whichever division the old Hz is nearest at the new tempo. The preset's and the load's
     * synced rates move with it, so the edited mark and a double-tap stay right. Not an undo step.
     */
    fun retime(bpm: Double) {
        val old = lastTempo
        lastTempo = bpm
        val rate = rateSpec ?: return
        if (old.isNaN() || bpm.isNaN() || old == bpm) return
        fun moved(m: Map<String, Double>): Map<String, Double> {
            val hz = m[LFO_RATE] ?: return m
            if ((m[LFO_SYNC] ?: 0.0) < 0.5) return m
            return m + (LFO_RATE to LfoDetent.retime(hz, rate, old, bpm))
        }
        presetValues = moved(presetValues)
        loadValues = moved(loadValues)
        if (lfoSynced) {
            val hz = LfoDetent.retime(value(LFO_RATE), rate, old, bpm)
            val state = values[rate.id]
            if (state.doubleValue != hz) {
                state.doubleValue = hz
                NativeEngine.setParam(slot, rate.id, hz)
                onChange()
            }
        }
    }

    private companion object {
        const val LFO_RATE = "lfo.rate"
        const val LFO_SYNC = "lfo.sync"
        const val VOICE_MODE = "osc.voiceMode"
    }
}
