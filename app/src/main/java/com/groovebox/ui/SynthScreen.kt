package com.groovebox.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.max
import com.groovebox.engine.EngineStats
import com.groovebox.engine.NativeEngine
import com.groovebox.ui.model.KeyboardLayout
import com.groovebox.ui.model.LatencyStats
import com.groovebox.ui.model.LatencySummary
import com.groovebox.ui.model.SynthSection
import com.groovebox.ui.theme.Design
import com.groovebox.ui.theme.GrooveColors
import com.groovebox.ui.theme.LocalDesign
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.min

/** Where the preset strip and slot 1 are, so a touch anywhere else can close the strip. */
private class OverlayBounds {
    var presets: Rect = Rect.Zero
    var slot: Rect = Rect.Zero
    fun contains(p: Offset) = presets.contains(p) || slot.contains(p)
}

/**
 * The Synth play screen (docs/mockups/Main.dc.html, ADR 0002): top bar, section rail,
 * display and four colour strips in the middle row, and the keyboard across the bottom.
 * Laid out in the mockup's 1040 × 480 design units, scaled to fit the screen.
 */
@Composable
fun SynthScreen(controller: SynthController, audioStarted: Boolean) {
    val keyboardLayout = remember { KeyboardLayout(lowestNote = 48, octaves = 2) }
    val latency = remember { LatencyStats() }
    var latencySummary by remember { mutableStateOf<LatencySummary?>(null) }
    var stats by remember { mutableStateOf<EngineStats?>(null) }
    var debugOpen by remember { mutableStateOf(false) }
    var presetsOpen by remember { mutableStateOf(false) }
    val overlay = remember { OverlayBounds() }

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

    Box(
        Modifier
            .fillMaxSize()
            .background(GrooveColors.Ground)
            // Watches touches before the children see them, without consuming any,
            // so a key touched to close the preset strip still plays.
            .pointerInput(presetsOpen) {
                if (!presetsOpen) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.any { it.changedToDownIgnoreConsumed() && !overlay.contains(it.position) }) {
                            presetsOpen = false
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
                        controller = controller,
                        onSlotTap = { if (presetsOpen) presetsOpen = false },
                        onSlotLongPress = { presetsOpen = true },
                        onSlotPositioned = { overlay.slot = it },
                    )
                    MiddleRow(
                        controller = controller,
                        audioStarted = audioStarted,
                        debugOpen = debugOpen,
                        onToggleDebug = {
                            debugOpen = !debugOpen
                            if (debugOpen) {
                                latencySummary = latency.summary()
                                stats = NativeEngine.stats()
                            }
                        },
                        debugPanel = {
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
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        Keyboard(keyboardLayout, Modifier.fillMaxSize())
                        if (presetsOpen) {
                            PresetStrip(
                                controller,
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

@Composable
private fun MiddleRow(
    controller: SynthController,
    audioStarted: Boolean,
    debugOpen: Boolean,
    onToggleDebug: () -> Unit,
    debugPanel: @Composable () -> Unit,
) {
    val d = LocalDesign.current
    val railGap = d.dp(4)
    // Tall enough for four rail buttons of at least 44 dp.
    val height = max(d.dp(220), Design.MinTouch * 4 + railGap * 3)
    Row(
        Modifier.fillMaxWidth().height(height),
        horizontalArrangement = Arrangement.spacedBy(d.dp(10)),
    ) {
        SectionRail(controller, railGap, Modifier.width(d.dp(64)).fillMaxHeight())
        SynthDisplay(
            controller = controller,
            audioStarted = audioStarted,
            debugOpen = debugOpen,
            onToggleDebug = onToggleDebug,
            debugPanel = debugPanel,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        Row(
            Modifier.width(d.dp(332)).fillMaxHeight(),
            horizontalArrangement = Arrangement.spacedBy(d.dp(8)),
        ) {
            controller.strips.forEachIndexed { i, spec ->
                ColourStrip(i, spec, controller, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

/** One tap picks a section (feature spec F1 decision); selection happens on touch-down. */
@Composable
private fun SectionRail(controller: SynthController, gap: Dp, modifier: Modifier) {
    val d = LocalDesign.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        for (section in SynthSection.entries) {
            val isSelected = controller.section == section
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(d.dp(10)))
                    .background(if (isSelected) GrooveColors.Text else GrooveColors.Panel)
                    .semantics {
                        contentDescription = "${section.label} section"
                        selected = isSelected
                    }
                    .pointerInput(section) {
                        awaitEachGesture {
                            awaitFirstDown()
                            controller.section = section
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    section.label,
                    style = d.label(
                        14f,
                        if (isSelected) GrooveColors.Ground else GrooveColors.Text,
                        if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun TopBar(
    controller: SynthController,
    onSlotTap: () -> Unit,
    onSlotLongPress: () -> Unit,
    onSlotPositioned: (Rect) -> Unit,
) {
    val d = LocalDesign.current
    val buttonHeight = max(d.dp(44), Design.MinTouch)
    val slotTap by rememberUpdatedState(onSlotTap)
    val slotLongPress by rememberUpdatedState(onSlotLongPress)

    Row(
        Modifier.fillMaxWidth().height(max(d.dp(48), Design.MinTouch)),
        verticalAlignment = Alignment.CenterVertically,
        // The mockup uses 14; 10 leaves room for system fonts, which run wider.
        horizontalArrangement = Arrangement.spacedBy(d.dp(10)),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(d.dp(4))) {
            for (tab in listOf("Synth", "Drum", "Seq", "Tape", "Mixer")) {
                val active = tab == "Synth"
                BarButton(
                    width = d.dp(64),
                    height = buttonHeight,
                    background = if (active) GrooveColors.Text else GrooveColors.Panel,
                    modifier = if (active) Modifier.semantics { selected = true } else Modifier.inert(),
                ) {
                    BasicText(
                        tab,
                        style = d.label(
                            14f,
                            if (active) GrooveColors.Ground else GrooveColors.Text,
                            if (active) FontWeight.Bold else FontWeight.Medium,
                        ),
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(d.dp(4))) {
            for (n in 1..8) {
                val active = n == 1
                val modifier = if (active) {
                    Modifier
                        .semantics { contentDescription = "Sound slot 1, selected. Long-press for presets" }
                        .onGloballyPositioned { onSlotPositioned(it.boundsInRoot()) }
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { slotTap() }, onLongPress = { slotLongPress() })
                        }
                } else {
                    Modifier.inert()
                }
                BarButton(
                    width = d.dp(40),
                    height = buttonHeight,
                    background = if (active) GrooveColors.Strips[0] else GrooveColors.Panel,
                    modifier = modifier,
                ) {
                    BasicText(
                        "$n",
                        style = d.value(
                            14f,
                            if (active) GrooveColors.Ground else GrooveColors.TextDim,
                            if (active) FontWeight.Medium else FontWeight.Normal,
                        ),
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // Tempo is a readout only in M1 (the sequencer arrives in M2).
        Row(horizontalArrangement = Arrangement.spacedBy(d.dp(4))) {
            BasicText("120.0", Modifier.alignByBaseline(), style = d.value(22f, GrooveColors.Text, FontWeight.Medium))
            BasicText("BPM", Modifier.alignByBaseline(), style = d.value(12f, GrooveColors.TextDim))
        }

        BarButton(d.dp(48), buttonHeight, GrooveColors.Panel, Modifier.inert("Play")) {
            TransportIcon(play = true, Modifier.size(d.dp(18)))
        }
        BarButton(d.dp(48), buttonHeight, GrooveColors.Panel, Modifier.inert("Record")) {
            TransportIcon(play = false, Modifier.size(d.dp(18)))
        }

        val shifted = controller.shifted
        BarButton(
            width = d.dp(72),
            height = buttonHeight,
            background = if (shifted) GrooveColors.Shift else GrooveColors.Panel,
            modifier = Modifier
                .semantics {
                    contentDescription = "Shift"
                    selected = shifted
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        controller.shiftPress(down.uptimeMillis)
                        // Null when the finger slides off or the touch is cancelled: release now.
                        val up = waitForUpOrCancellation()
                        controller.shiftRelease(up?.uptimeMillis ?: SystemClock.uptimeMillis())
                    }
                },
        ) {
            BasicText(
                "Shift",
                style = d.label(14f, if (shifted) GrooveColors.Ground else GrooveColors.Text, FontWeight.Bold),
            )
        }
    }
}

@Composable
private fun BarButton(
    width: Dp,
    height: Dp,
    background: Color,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val d = LocalDesign.current
    Box(
        modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(d.dp(10)))
            .background(background),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** Shown but not usable in M1: dimmed, no touch handling, marked disabled for accessibility. */
private fun Modifier.inert(description: String? = null): Modifier =
    alpha(GrooveColors.INERT_ALPHA).semantics {
        if (description != null) contentDescription = description
        disabled()
    }

@Composable
private fun TransportIcon(play: Boolean, modifier: Modifier) {
    val d = LocalDesign.current
    val stroke = d.px(2f)
    Canvas(modifier) {
        val s = size.width / 18f // the mockup's icons are drawn in an 18 × 18 box
        if (play) {
            val path = Path().apply {
                moveTo(4f * s, 2.5f * s)
                lineTo(4f * s, 15.5f * s)
                lineTo(15f * s, 9f * s)
                close()
            }
            drawPath(path, GrooveColors.Strips[2], style = Stroke(stroke, join = StrokeJoin.Round))
        } else {
            drawCircle(GrooveColors.Strips[0], radius = 6f * s, center = Offset(9f * s, 9f * s), style = Stroke(stroke))
        }
    }
}

/**
 * Owner decision 1: long-press slot 1 shows this thin strip of presets over the keys.
 * A tap loads a preset and keeps the strip open for auditioning; touching anywhere else
 * (or tapping the slot) closes it. It never blocks the keys outside its own area.
 */
@Composable
private fun PresetStrip(controller: SynthController, modifier: Modifier) {
    val d = LocalDesign.current
    Row(
        modifier
            .height(max(d.dp(44), Design.MinTouch))
            .clip(RoundedCornerShape(d.dp(10)))
            .background(GrooveColors.Ground.copy(alpha = 0.96f))
            .semantics { contentDescription = "Presets" }
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = d.dp(4), vertical = d.dp(4)),
        horizontalArrangement = Arrangement.spacedBy(d.dp(4)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        controller.presetNames.forEachIndexed { i, name ->
            val isSelected = i == controller.presetIndex
            Box(
                Modifier
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(d.dp(8)))
                    .background(if (isSelected) GrooveColors.Strips[0] else GrooveColors.Panel)
                    .semantics { selected = isSelected }
                    .pointerInput(i) { detectTapGestures(onTap = { controller.loadPreset(i) }) }
                    .padding(horizontal = d.dp(12)),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    name,
                    style = d.label(13f, if (isSelected) GrooveColors.Ground else GrooveColors.Text),
                    maxLines = 1,
                )
            }
        }
    }
}
