package com.groovebox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import com.groovebox.engine.EngineStats
import com.groovebox.BuildConfig
import com.groovebox.engine.NativeEngine
import com.groovebox.ui.model.DrumLayout
import com.groovebox.ui.model.KeyOctave
import com.groovebox.ui.model.KeyboardLayout
import com.groovebox.ui.model.SlotCopyGesture
import com.groovebox.ui.model.LatencyStats
import com.groovebox.ui.model.LatencySummary
import com.groovebox.ui.model.PresetStripRules
import com.groovebox.ui.model.PresetTouch
import com.groovebox.ui.model.SeqLimits
import com.groovebox.ui.theme.Design
import com.groovebox.ui.theme.GrooveColors
import com.groovebox.ui.theme.LocalDesign
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.min

/** Where the preset strip, the keys and the slot buttons are, so a touch elsewhere can close the strip. */
private class OverlayBounds {
    var presets: Rect = Rect.Zero
    var keys: Rect = Rect.Zero

    /** The tape lanes: a touch outside them cancels an armed lane clear (build-21). */
    var lanes: Rect = Rect.Zero
    val slots = Array(SeqLimits.SLOTS) { Rect.Zero }

    /** Build-21: where a touch lands while [slot]'s strip is open ([keysShown]: the keys are on screen). */
    fun zone(p: Offset, slot: Int, keysShown: Boolean): PresetTouch = PresetStripRules.zone(
        inStrip = presets.contains(p), // the strip's × closes it from its own tap
        inKeys = keysShown && keys.contains(p),
        inOpenSlot = slots.getOrNull(slot)?.contains(p) == true,
    )
}

/**
 * The whole app (ADR 0002): the top bar, then the selected screen's middle row (display and
 * strips) and bottom zone (keys; step pads on the Sequencer; channel pads on the Mixer). Laid out in the mockups'
 * 1040 × 480 design units, scaled to fit the screen. Polls the transport every display frame.
 */
@Composable
fun GrooveScreen(state: GrooveState, audioStarted: Boolean) {
    val keyboardLayout = remember { KeyboardLayout(lowestNote = DrumLayout.LOWEST_NOTE, octaves = 2) }
    // Build-22: the Synth and Tape keys follow the keyboard octave (Play rail); Seq keeps its own keys.
    val playLayout = remember(state.keyOctave) { KeyboardLayout(lowestNote = KeyOctave.lowestNote(state.keyOctave), octaves = 2) }
    // Build-22: the swipe / save flash on the Synth display (owner decision 1-1A).
    var flash by remember { mutableStateOf<SlotFlash?>(null) }
    var flashCount by remember { mutableStateOf(0) }
    fun showFlash(big: String, small: String) {
        flashCount++
        flash = SlotFlash(big, small, flashCount)
    }
    LaunchedEffect(flash) {
        if (flash == null) return@LaunchedEffect
        delay(FLASH_MS)
        flash = null
    }
    // Build-22: hold one slot button and tap another copies the sound.
    val slotCopy = remember { SlotCopyGesture(isSynth = { !state.isDrum(it) }) }
    val latency = remember { LatencyStats() }
    var latencySummary by remember { mutableStateOf<LatencySummary?>(null) }
    var stats by remember { mutableStateOf<EngineStats?>(null) }
    var debugOpen by remember { mutableStateOf(false) }
    // The synth slot whose preset strip is open, or -1.
    var presetSlot by remember { mutableStateOf(-1) }
    val overlay = remember { OverlayBounds() }

    // Every frame: playheads, pattern switches, recorded writes and the tape head (F3.10: read only).
    LaunchedEffect(state) {
        while (isActive) {
            withFrameNanos { now ->
                state.frame(NativeEngine.transport(), NativeEngine.pollRecorded(), now)
                state.tapeFrame(NativeEngine.tapeState())
            }
        }
    }

    // ~10 Hz. Tap-to-tone is always drained so the engine's buffer never fills;
    // the summary and stream stats are only refreshed while the debug panel shows them.
    LaunchedEffect(Unit) {
        while (isActive) {
            NativeEngine.pollTapToToneNs().forEach(latency::add)
            if (debugOpen) {
                latencySummary = latency.summary()
                stats = NativeEngine.stats()
            }
            delay(100)
        }
    }

    val debug = DebugSlot(
        open = debugOpen,
        toggle = {
            debugOpen = !debugOpen
            if (debugOpen) {
                latencySummary = latency.summary()
                stats = NativeEngine.stats()
            }
        },
        panel = {
            DebugPanel(
                build = BuildConfig.VERSION_CODE,
                stats = stats,
                latency = latencySummary,
                onBursts = {
                    NativeEngine.setBufferBursts(it)
                    stats = NativeEngine.stats()
                },
                onResetLatency = {
                    latency.reset()
                    latencySummary = null
                },
            )
        },
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(GrooveColors.Ground)
            // Watches touches before the children see them, without consuming any. Build-21:
            // playing the keys keeps the strip open so presets can be auditioned; a touch
            // anywhere else but the strip closes it (the open slot's tap and the × close it
            // from their own handlers).
            .pointerInput(presetSlot) {
                if (presetSlot < 0) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val keysShown = state.screen == Screen.Synth || (state.screen == Screen.Tape && !state.isDrum(state.selectedSlot))
                        if (event.changes.any {
                                it.changedToDownIgnoreConsumed() &&
                                    overlay.zone(it.position, presetSlot, keysShown) == PresetTouch.Elsewhere
                            }
                        ) {
                            presetSlot = -1
                        }
                    }
                }
            }
            // Build-22: watches the slot buttons (without consuming) for "hold one, tap another" = copy.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        for (c in event.changes) {
                            when {
                                c.changedToDownIgnoreConsumed() -> {
                                    // Tape shows no slot buttons in the bar (its slot chip): the rects are stale there.
                                    if (state.screen == Screen.Tape) continue
                                    val slot = overlay.slots.indexOfFirst { it.contains(c.position) }
                                    if (slot < 0) continue
                                    slotCopy.down(c.id.value, slot)?.let { copy ->
                                        state.copySound(copy.from, copy.to)
                                        presetSlot = -1
                                        showFlash("${copy.to + 1}", "${state.synth(copy.from).presetName} copied from ${copy.from + 1}")
                                    }
                                }
                                c.changedToUpIgnoreConsumed() -> slotCopy.up(c.id.value)
                            }
                        }
                    }
                }
            }
            // Build-21: while a tape lane asks "Clear T3?", a touch anywhere off the lanes cancels it.
            .pointerInput(state.tapeClearLane >= 0) {
                if (state.tapeClearLane < 0) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.any { it.changedToDownIgnoreConsumed() && !overlay.lanes.contains(it.position) }) {
                            state.tapeTouchOutsideLanes()
                        }
                    }
                }
            }
            // Build-22: the Tape slot picker closes on a touch anywhere off it.
            .pointerInput(state.slotPickerOpen) {
                if (!state.slotPickerOpen) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.any { it.changedToDownIgnoreConsumed() && !state.slotPickerBounds.contains(it.position) }) {
                            state.closeSlotPicker()
                        }
                    }
                }
            },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val density = LocalDensity.current
            val unit = min(maxWidth.value / Design.WIDTH, maxHeight.value / Design.HEIGHT)
            val d = remember(unit, density) { Design(unit, density) }
            CompositionLocalProvider(LocalDesign provides d) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(start = d.dp(14), end = d.dp(14), top = d.dp(10), bottom = d.dp(12)),
                    verticalArrangement = Arrangement.spacedBy(d.dp(10)),
                ) {
                    TopBar(
                        state = state,
                        onSlotTap = { slot -> if (presetSlot == slot) presetSlot = -1 },
                        // Build-22: a slot held to copy its sound does not also open its presets.
                        onSlotLongPress = { slot -> if (!state.isDrum(slot) && !slotCopy.copiedFrom(slot)) presetSlot = slot },
                        onSlotPositioned = { slot, bounds -> overlay.slots[slot] = bounds },
                    )
                    val middle = Modifier.fillMaxWidth().height(middleRowHeight())
                    when (state.screen) {
                        Screen.Synth -> SynthMiddleRow(
                            state.selectedSynth, audioStarted, debug, middle, state.keyScale.displayName,
                            // Build-22: a swipe flashes the slot and preset; finger right on slot 2 goes to the Drum screen (R11).
                            onSlotSwipe = { steps ->
                                state.swipeSlot(steps, includeDrum = true)
                                val slot = state.selectedSlot
                                if (!state.isDrum(slot)) showFlash("${slot + 1}", state.synth(slot).displayName)
                            },
                            // ADR 0003 P1: long-press the display for the selected slot's presets.
                            onPresets = { presetSlot = state.selectedSlot },
                            state = state,
                            onSavePreset = {
                                val name = state.saveUserPreset()
                                showFlash("${state.selectedSlot + 1}", if (name != null) "Saved · $name" else "User presets full")
                            },
                            flash = flash,
                        )
                        Screen.Drum -> DrumMiddleRow(
                            state, audioStarted, debug, middle,
                            // Build-22 (review R11): finger left goes on to synth slot 2.
                            onSlotSwipe = { steps ->
                                state.swipeSlot(steps, includeDrum = true)
                                if (!state.isDrum(state.selectedSlot)) showFlash("${state.selectedSlot + 1}", state.instrumentName(state.selectedSlot))
                            },
                        )
                        Screen.Seq -> SeqMiddleRow(state, audioStarted, debug, middle)
                        Screen.Tape -> TapeMiddleRow(state, audioStarted, debug, middle) { overlay.lanes = it }
                        Screen.Mixer -> MixerMiddleRow(state, audioStarted, debug, middle)
                    }
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        val keys = Modifier.fillMaxSize().onGloballyPositioned { overlay.keys = it.boundsInRoot() }
                        when (state.screen) {
                            Screen.Synth -> SlotKeyboard(state, playLayout, keys)
                            // Build-21: the kit plays from two pad blocks, not the keys.
                            Screen.Drum -> DrumPads(state, Modifier.fillMaxSize())
                            Screen.Seq -> SeqBottom(state, keyboardLayout, Modifier.fillMaxSize())
                            // The keys (the pads with slot 1 selected) play the selected slot over the tape.
                            Screen.Tape ->
                                if (state.isDrum(state.selectedSlot)) {
                                    DrumPads(state, Modifier.fillMaxSize())
                                } else {
                                    SlotKeyboard(state, playLayout, keys)
                                }
                            Screen.Mixer -> MixerPads(state, Modifier.fillMaxSize())
                        }
                        if (presetSlot >= 0) {
                            PresetStrip(
                                state.synth(presetSlot),
                                onClose = { presetSlot = -1 },
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .fillMaxWidth()
                                    // Build-22 (review R12): the backdrop covers the whole top key row.
                                    .fillMaxHeight(PRESET_STRIP_HEIGHT)
                                    .onGloballyPositioned { overlay.presets = it.boundsInRoot() },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The keys for the selected slot. The drum slot names its 16 sounds and dims the keys above
 * them; it ignores the key and scale (F8.7). A synth slot with a scale on dims the out-of-key
 * keys, which play nothing, and marks the root with an amber stripe (F8.2, F8.3).
 */
@Composable
fun SlotKeyboard(state: GrooveState, layout: KeyboardLayout, modifier: Modifier = Modifier) {
    val drum = state.isDrum(state.selectedSlot)
    val keyScale = state.keyScale
    val notes = remember(layout) { layout.keys.map { it.note } }
    val outOfKey = remember(keyScale, notes) { keyScale.dimmed(notes) }
    val roots = remember(keyScale, notes) { keyScale.roots(notes) }
    Keyboard(
        layout,
        state,
        modifier,
        keyLabels = if (drum) state.drum.keyLabels else null,
        dimmed = if (drum) DRUMLESS_NOTES else outOfKey,
        roots = if (drum) emptySet() else roots,
        playable = state::keyPlayable,
    )
}

/** How long the slot flash stays (build-22). */
private const val FLASH_MS = 900L

/** The preset strip's share of the keys' height: the sharps row ends at about 45 % (Keyboard.kt). */
private const val PRESET_STRIP_HEIGHT = 0.48f
