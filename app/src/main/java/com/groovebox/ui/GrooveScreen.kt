package com.groovebox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import com.groovebox.engine.EngineStats
import com.groovebox.engine.NativeEngine
import com.groovebox.ui.model.DrumLayout
import com.groovebox.ui.model.KeyboardLayout
import com.groovebox.ui.model.LatencyStats
import com.groovebox.ui.model.LatencySummary
import com.groovebox.ui.model.SeqLimits
import com.groovebox.ui.theme.Design
import com.groovebox.ui.theme.GrooveColors
import com.groovebox.ui.theme.LocalDesign
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.min

/** Where the preset strip and the slot buttons are, so a touch anywhere else can close the strip. */
private class OverlayBounds {
    var presets: Rect = Rect.Zero
    val slots = Array(SeqLimits.SLOTS) { Rect.Zero }
    fun contains(p: Offset, slot: Int) = presets.contains(p) || slots.getOrNull(slot)?.contains(p) == true
}

/**
 * The whole app (ADR 0002): the top bar, then the selected screen's middle row (display and
 * strips) and bottom zone (keys; step pads on the Sequencer; channel pads on the Mixer). Laid out in the mockups'
 * 1040 × 480 design units, scaled to fit the screen. Polls the transport every display frame.
 */
@Composable
fun GrooveScreen(state: GrooveState, audioStarted: Boolean) {
    val keyboardLayout = remember { KeyboardLayout(lowestNote = DrumLayout.LOWEST_NOTE, octaves = 2) }
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
            // Watches touches before the children see them, without consuming any,
            // so a key touched to close the preset strip still plays.
            .pointerInput(presetSlot) {
                if (presetSlot < 0) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.any { it.changedToDownIgnoreConsumed() && !overlay.contains(it.position, presetSlot) }) {
                            presetSlot = -1
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
                        onSlotLongPress = { slot -> if (!state.isDrum(slot)) presetSlot = slot },
                        onSlotPositioned = { slot, bounds -> overlay.slots[slot] = bounds },
                    )
                    val middle = Modifier.fillMaxWidth().height(middleRowHeight())
                    when (state.screen) {
                        Screen.Synth -> SynthMiddleRow(state.selectedSynth, audioStarted, debug, middle)
                        Screen.Drum -> DrumMiddleRow(state.drum, audioStarted, debug, middle)
                        Screen.Seq -> SeqMiddleRow(state, audioStarted, debug, middle)
                        Screen.Tape -> TapeMiddleRow(state, audioStarted, debug, middle)
                        Screen.Mixer -> MixerMiddleRow(state, audioStarted, debug, middle)
                    }
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        when (state.screen) {
                            Screen.Synth -> Keyboard(keyboardLayout, state, Modifier.fillMaxSize())
                            Screen.Drum -> Keyboard(
                                keyboardLayout,
                                state,
                                Modifier.fillMaxSize(),
                                keyLabels = state.drum.keyLabels,
                                dimmed = DRUMLESS_NOTES,
                            )
                            Screen.Seq -> SeqBottom(state, keyboardLayout, Modifier.fillMaxSize())
                            // The keys play the selected slot over the tape.
                            Screen.Tape -> {
                                val drum = state.isDrum(state.selectedSlot)
                                Keyboard(
                                    keyboardLayout,
                                    state,
                                    Modifier.fillMaxSize(),
                                    keyLabels = if (drum) state.drum.keyLabels else null,
                                    dimmed = if (drum) DRUMLESS_NOTES else emptySet(),
                                )
                            }
                            Screen.Mixer -> MixerPads(state, Modifier.fillMaxSize())
                        }
                        if (presetSlot >= 0) {
                            PresetStrip(
                                state.synth(presetSlot),
                                Modifier
                                    .align(Alignment.TopCenter)
                                    .fillMaxWidth()
                                    .onGloballyPositioned { overlay.presets = it.boundsInRoot() },
                            )
                        }
                    }
                }
            }
        }
    }
}
