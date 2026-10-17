package com.groovebox.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.max
import com.groovebox.ui.model.ExportStatus
import com.groovebox.ui.model.SeqLimits
import com.groovebox.ui.model.TapeRecordStatus
import com.groovebox.ui.model.UndoHold
import com.groovebox.ui.theme.Design
import com.groovebox.ui.theme.GrooveColors
import com.groovebox.ui.theme.LocalDesign
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * The top bar on every screen (ADR 0002): screen tabs, the 8 sound slots, the tempo
 * readout, Play/Stop, Record and Shift. On the Tape screen, Undo and Export WAV take the
 * slots' place (ADR 0002's exception) and Record records tape onto the selected track.
 */
@Composable
fun TopBar(
    state: GrooveState,
    onSlotTap: (slot: Int) -> Unit,
    onSlotLongPress: (slot: Int) -> Unit,
    onSlotPositioned: (slot: Int, bounds: Rect) -> Unit,
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
            Tab("Synth", state.screen == Screen.Synth, buttonHeight) { state.showSynth() }
            Tab("Drum", state.screen == Screen.Drum, buttonHeight) { state.showDrum() }
            Tab("Seq", state.screen == Screen.Seq, buttonHeight) { state.showSeq() }
            Tab("Tape", state.screen == Screen.Tape, buttonHeight) { state.showTape() }
            Tab("Mixer", state.screen == Screen.Mixer, buttonHeight) { state.showMixer() }
        }

        if (state.screen == Screen.Tape) TapeButtons(state, buttonHeight) else Row(horizontalArrangement = Arrangement.spacedBy(d.dp(4))) {
            for (slot in 0 until SeqLimits.SLOTS) {
                val active = slot == state.selectedSlot
                val drum = state.isDrum(slot)
                val description = buildString {
                    append("Sound slot ${slot + 1}, ")
                    append(if (drum) "drum kit" else state.synth(slot).presetName)
                    if (active) append(", selected")
                    if (!drum) append(". Long-press, or long-press the display, for presets")
                }
                BarButton(
                    width = d.dp(40),
                    height = buttonHeight,
                    background = if (active) GrooveColors.Strips[0] else GrooveColors.Panel,
                    modifier = Modifier
                        .semantics {
                            contentDescription = description
                            selected = active
                        }
                        .onGloballyPositioned { onSlotPositioned(slot, it.boundsInRoot()) }
                        .pointerInput(slot) {
                            // Selects on touch-down; a long-press on a synth slot opens its presets.
                            detectTapGestures(
                                onPress = { state.selectSlot(slot) },
                                onTap = { slotTap(slot) },
                                onLongPress = { slotLongPress(slot) },
                            )
                        },
                ) {
                    BasicText(
                        "${slot + 1}",
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

        Row(horizontalArrangement = Arrangement.spacedBy(d.dp(4))) {
            BasicText(
                String.format(Locale.ROOT, "%.1f", state.tempoBpm),
                Modifier.alignByBaseline(),
                style = d.value(22f, GrooveColors.Text, FontWeight.Medium),
            )
            BasicText("BPM", Modifier.alignByBaseline(), style = d.value(12f, GrooveColors.TextDim))
        }

        val playing = state.playing
        BarButton(
            d.dp(48),
            buttonHeight,
            if (playing) GrooveColors.Strips[2] else GrooveColors.Panel,
            Modifier
                .semantics { contentDescription = if (playing) "Stop" else "Play" }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown()
                        state.togglePlay()
                    }
                },
        ) {
            TransportIcon(if (playing) Icon.Stop else Icon.Play, Modifier.size(d.dp(18)))
        }

        val recordGesture = Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                if (state.screen == Screen.Tape) {
                    // Tape: one action on touch-down; the engine snaps it to a bar line (F5.1).
                    state.tapeRecordPress()
                    waitForUpOrCancellation()
                } else {
                    state.recordPress(down.uptimeMillis)
                    // Null when the finger slides off or the touch is cancelled.
                    val up = waitForUpOrCancellation()
                    if (up != null) state.recordRelease(up.uptimeMillis) else state.recordCancel()
                }
            }
        }
        if (state.screen == Screen.Tape) {
            TapeRecordButton(state, buttonHeight, recordGesture)
        } else {
            val armed = state.armed
            val motion = state.motionHeld
            BarButton(
                d.dp(48),
                buttonHeight,
                if (motion) GrooveColors.Strips[0].copy(alpha = 0.3f) else GrooveColors.Panel,
                recordGesture.semantics {
                    contentDescription = "Record" + (if (armed) ", armed" else "") +
                        (if (motion) ", recording motion" else "") + ". Tap to arm, hold and move a strip to record motion"
                    selected = armed
                },
            ) {
                TransportIcon(if (armed || motion) Icon.RecordOn else Icon.Record, Modifier.size(d.dp(18)))
            }
        }

        val shifted = state.shift.shifted
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
                        state.shift.press(down.uptimeMillis)
                        // Null when the finger slides off or the touch is cancelled: release now.
                        val up = waitForUpOrCancellation()
                        state.shift.release(up?.uptimeMillis ?: SystemClock.uptimeMillis())
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

/**
 * The Tape screen's Undo and Export WAV (ADR 0002). Undo (build-21): a tap undoes the last tape
 * action, a long-press redoes it (the button briefly reads "Redo"); Shift no longer changes it,
 * so it never redoes by accident ([UndoHold]). Export WAV (F7.1) is live when the tape has
 * audio: a tap renders the loop to a WAV (the button shows the progress) and then opens the
 * share sheet.
 */
@Composable
private fun TapeButtons(state: GrooveState, height: Dp) {
    val d = LocalDesign.current
    val haptics = LocalHapticFeedback.current
    val hold = remember { UndoHold() }
    var readsRedo by remember { mutableStateOf(false) }
    var flash by remember { mutableIntStateOf(0) }
    LaunchedEffect(flash) {
        if (flash == 0) return@LaunchedEffect
        delay(hold.flashMs)
        readsRedo = false
    }
    val enabled = state.canUndo || state.canRedo
    Row(horizontalArrangement = Arrangement.spacedBy(d.dp(10))) {
        BarButton(
            width = d.dp(88),
            height = height,
            background = GrooveColors.Panel,
            modifier = Modifier
                .alpha(if (enabled) 1f else GrooveColors.INERT_ALPHA)
                .semantics {
                    contentDescription = "Undo tape. Hold to redo"
                    if (!enabled) disabled()
                    onLongClick("Redo tape") { state.tapeRedo(); true }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        hold.down(down.uptimeMillis)
                        var cancelled = false
                        var upAt = 0L
                        val released = withTimeoutOrNull(hold.longPressMs) {
                            val up = waitForUpOrCancellation()
                            if (up == null) cancelled = true else upAt = up.uptimeMillis
                            Unit
                        } != null
                        when {
                            cancelled -> hold.cancel()
                            released -> if (hold.up(upAt) == UndoHold.Action.Undo) state.tapeUndo()
                            else -> {
                                // Held to the long-press time: redo once, now.
                                if (hold.poll(down.uptimeMillis + hold.longPressMs) == UndoHold.Action.Redo) {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    state.tapeRedo()
                                    readsRedo = true
                                }
                                val up = waitForUpOrCancellation()
                                if (up == null) hold.cancel() else hold.up(up.uptimeMillis)
                                flash++
                            }
                        }
                    }
                },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.dp(8))) {
                UndoIcon(readsRedo, GrooveColors.Text, Modifier.size(d.dp(16)))
                BasicText(if (readsRedo) "Redo" else "Undo", style = d.label(14f, GrooveColors.Text, FontWeight.Medium))
            }
        }
        val status = state.exportStatus
        val canExport = state.canExport
        val rendering = status as? ExportStatus.Rendering
        BarButton(
            width = d.dp(128),
            height = height,
            background = GrooveColors.Strips[3],
            modifier = Modifier
                .alpha(if (canExport || rendering != null) 1f else GrooveColors.INERT_ALPHA)
                .semantics {
                    contentDescription = when {
                        rendering != null -> "Exporting, ${(rendering.progress * 100).toInt()} percent"
                        canExport -> "Export WAV and share it"
                        else -> "Export WAV, needs audio on the tape"
                    }
                    if (!canExport) disabled()
                }
                .pointerInput(Unit) { detectTapGestures(onTap = { state.exportPress() }) },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.dp(8))) {
                ExportIcon(GrooveColors.Ground, Modifier.size(d.dp(16)))
                BasicText(
                    if (rendering != null) "Export ${(rendering.progress * 100).toInt()}%" else "Export WAV",
                    style = d.label(14f, GrooveColors.Ground, FontWeight.Bold),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Record on the Tape screen: an outlined dot when idle, a blinking dot while armed (waiting
 * for the next bar line or Play), and solid coral while recording (the mockup's look).
 * Dimmed when the tape can't record (not 1x forward).
 */
@Composable
private fun TapeRecordButton(state: GrooveState, height: Dp, gesture: Modifier) {
    val d = LocalDesign.current
    val status = state.tapeStatus
    val track = when (status) {
        TapeRecordStatus.Recording -> state.tapeRecordingTrack
        TapeRecordStatus.Armed -> state.tapeArmedTrack
        TapeRecordStatus.Idle -> state.tapeTrack
    }
    val canRecord = state.tapeCanRecord || status != TapeRecordStatus.Idle
    val icon = when (status) {
        TapeRecordStatus.Recording -> Icon.RecordOnTape
        TapeRecordStatus.Armed -> if (state.blinkOn) Icon.RecordOn else Icon.Record
        TapeRecordStatus.Idle -> Icon.Record
    }
    BarButton(
        d.dp(48),
        height,
        if (status == TapeRecordStatus.Recording) GrooveColors.Strips[0] else GrooveColors.Panel,
        gesture
            .alpha(if (canRecord) 1f else GrooveColors.INERT_ALPHA)
            .semantics {
                contentDescription = when (status) {
                    TapeRecordStatus.Recording -> "Recording onto track ${track + 1}. Tap to stop at the next bar"
                    TapeRecordStatus.Armed -> "Track ${track + 1} armed. Tap to cancel"
                    TapeRecordStatus.Idle -> if (canRecord) "Record onto track ${track + 1}" else "Record needs speed 1x and reverse off"
                }
                selected = status != TapeRecordStatus.Idle
            },
    ) {
        TransportIcon(icon, Modifier.size(d.dp(18)))
    }
}

/** The mockup's undo arrow (16 × 16); mirrored for redo. */
@Composable
private fun UndoIcon(redo: Boolean, colour: Color, modifier: Modifier) {
    val d = LocalDesign.current
    val stroke = d.px(1.8f)
    Canvas(modifier) {
        val s = size.width / 16f
        val path = Path().apply {
            moveTo(5f * s, 4f * s)
            lineTo(2f * s, 7f * s)
            lineTo(5f * s, 10f * s)
            moveTo(2f * s, 7f * s)
            lineTo(10f * s, 7f * s)
            arcTo(Rect(6f * s, 7f * s, 14f * s, 15f * s), -90f, 180f, false)
            lineTo(8f * s, 15f * s)
        }
        val style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        if (redo) scale(-1f, 1f) { drawPath(path, colour, style = style) } else drawPath(path, colour, style = style)
    }
}

/** The mockup's download arrow (16 × 16). */
@Composable
private fun ExportIcon(colour: Color, modifier: Modifier) {
    val d = LocalDesign.current
    val stroke = d.px(1.8f)
    Canvas(modifier) {
        val s = size.width / 16f
        val path = Path().apply {
            moveTo(8f * s, 2f * s)
            lineTo(8f * s, 10f * s)
            moveTo(4.5f * s, 6.5f * s)
            lineTo(8f * s, 10f * s)
            lineTo(11.5f * s, 6.5f * s)
            moveTo(3f * s, 13f * s)
            lineTo(13f * s, 13f * s)
        }
        drawPath(path, colour, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** A screen tab; selection happens on touch-down. A null [onSelect] is a tab not built yet. */
@Composable
private fun Tab(label: String, active: Boolean, height: Dp, onSelect: (() -> Unit)?) {
    val d = LocalDesign.current
    val select by rememberUpdatedState(onSelect)
    val modifier = when {
        onSelect == null -> Modifier.inert()
        else -> Modifier
            .semantics {
                contentDescription = "$label screen"
                selected = active
            }
            .pointerInput(label) {
                awaitEachGesture {
                    awaitFirstDown()
                    select?.invoke()
                }
            }
    }
    BarButton(
        width = d.dp(64),
        height = height,
        background = if (active) GrooveColors.Text else GrooveColors.Panel,
        modifier = modifier,
    ) {
        BasicText(
            label,
            style = d.label(
                14f,
                if (active) GrooveColors.Ground else GrooveColors.Text,
                if (active) FontWeight.Bold else FontWeight.Medium,
            ),
        )
    }
}

@Composable
fun BarButton(
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

/** Shown but not usable yet: dimmed, no touch handling, marked disabled for accessibility. */
fun Modifier.inert(description: String? = null): Modifier =
    alpha(GrooveColors.INERT_ALPHA).semantics {
        if (description != null) contentDescription = description
        disabled()
    }

private enum class Icon { Play, Stop, Record, RecordOn, RecordOnTape }

@Composable
private fun TransportIcon(icon: Icon, modifier: Modifier) {
    val d = LocalDesign.current
    val stroke = d.px(2f)
    Canvas(modifier) {
        val s = size.width / 18f // the mockup's icons are drawn in an 18 × 18 box
        when (icon) {
            Icon.Play -> {
                val path = Path().apply {
                    moveTo(4f * s, 2.5f * s)
                    lineTo(4f * s, 15.5f * s)
                    lineTo(15f * s, 9f * s)
                    close()
                }
                drawPath(path, GrooveColors.Strips[2], style = Stroke(stroke, join = StrokeJoin.Round))
            }
            // The Sequencer mockup's Stop: a dark rounded square on the green button.
            Icon.Stop -> drawRoundRect(
                GrooveColors.Ground,
                topLeft = Offset(3f * s, 3f * s),
                size = Size(12f * s, 12f * s),
                cornerRadius = CornerRadius(2f * s),
            )
            Icon.Record -> drawCircle(GrooveColors.Strips[0], radius = 6f * s, center = Offset(9f * s, 9f * s), style = Stroke(stroke))
            Icon.RecordOn -> drawCircle(GrooveColors.Strips[0], radius = 6.5f * s, center = Offset(9f * s, 9f * s))
            // The Tape mockup's Record: a dark dot on the coral button.
            Icon.RecordOnTape -> drawCircle(GrooveColors.Ground, radius = 6f * s, center = Offset(9f * s, 9f * s))
        }
    }
}
