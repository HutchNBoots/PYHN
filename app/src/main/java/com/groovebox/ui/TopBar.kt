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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.groovebox.ui.model.SlotChip
import com.groovebox.ui.model.TapTempo
import com.groovebox.ui.theme.Design
import com.groovebox.ui.theme.GrooveColors
import com.groovebox.ui.theme.GrooveRadius
import com.groovebox.ui.theme.GrooveSpace
import com.groovebox.ui.theme.GrooveText
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

        if (state.screen == Screen.Tape) {
            TapeButtons(state, buttonHeight) { slot, picked ->
                SlotButton(state, slot, buttonHeight, onSlotPositioned, onTap = { picked() }, onLongPress = { picked() })
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(d.dp(GrooveSpace.GROUP))) {
                for (slot in 0 until SeqLimits.SLOTS) {
                    SlotButton(state, slot, buttonHeight, onSlotPositioned, onTap = { slotTap(slot) }, onLongPress = { slotLongPress(slot) })
                }
            }
        }

        Spacer(Modifier.weight(1f))

        TempoReadout(state, buttonHeight)

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

/** One sound slot button: selects on touch-down; [onLongPress] (presets on a synth slot elsewhere). */
@Composable
private fun SlotButton(
    state: GrooveState,
    slot: Int,
    height: Dp,
    onPositioned: (slot: Int, bounds: Rect) -> Unit,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val d = LocalDesign.current
    val tap by rememberUpdatedState(onTap)
    val longPress by rememberUpdatedState(onLongPress)
    val active = slot == state.selectedSlot
    val drum = state.isDrum(slot)
    val description = buildString {
        append("Sound slot ${slot + 1}, ")
        append(if (drum) "drum kit" else state.synth(slot).presetName)
        if (active) append(", selected")
        if (!drum && state.screen != Screen.Tape) append(". Long-press, or long-press the display, for presets")
    }
    BarButton(
        width = d.dp(40),
        height = height,
        background = if (active) GrooveColors.SlotSelect else GrooveColors.Panel,
        modifier = Modifier
            .semantics {
                contentDescription = description
                selected = active
            }
            .onGloballyPositioned { onPositioned(slot, it.boundsInRoot()) }
            .pointerInput(slot) {
                detectTapGestures(
                    onPress = { state.selectSlot(slot) },
                    onTap = { tap() },
                    onLongPress = { longPress() },
                )
            },
    ) {
        BasicText(
            "${slot + 1}",
            style = d.value(
                GrooveText.LABEL,
                if (active) GrooveColors.Ground else GrooveColors.TextDim,
                if (active) FontWeight.Medium else FontWeight.Normal,
            ),
        )
    }
}

/**
 * The tempo readout, also tap tempo (build-22): each touch-down is a tap; from the third the
 * tempo follows the taps ([com.groovebox.ui.model.TapTempo]: ×2 / ½ snap). While a run is going
 * the readout is lit and reads "TAP"; it ends 2 s after the last one. A long-press sets 120.
 */
@Composable
private fun TempoReadout(state: GrooveState, height: Dp) {
    val d = LocalDesign.current
    val haptics = LocalHapticFeedback.current
    val taps = state.tapCount
    LaunchedEffect(taps) {
        if (taps == 0) return@LaunchedEffect
        delay(TapTempo.TIMEOUT_MS)
        state.tapTempoIdle()
    }
    val tapping = taps > 0
    Row(
        Modifier
            .height(height)
            // The lit plate is drawn past the readout's edges: the bar has no width to spare.
            .drawBehind {
                if (tapping) {
                    val out = d.px(GrooveSpace.GROUP)
                    drawRoundRect(
                        GrooveColors.Panel,
                        topLeft = Offset(-out, 0f),
                        size = Size(size.width + 2 * out, size.height),
                        cornerRadius = CornerRadius(d.px(GrooveRadius.CONTROL)),
                    )
                }
            }
            .semantics {
                contentDescription = "Tempo ${String.format(Locale.ROOT, "%.1f", state.tempoBpm)} BPM. " +
                    "Tap three times or more to set it, hold for 120"
                onLongClick("Tempo 120") { state.tapTempoReset(); true }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    state.tapTempo(down.uptimeMillis)
                    val held = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation() } == null
                    if (held && currentEvent.changes.any { it.pressed }) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        state.tapTempoReset()
                        waitForUpOrCancellation()
                    }
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(d.dp(GrooveSpace.GROUP))) {
            BasicText(
                String.format(Locale.ROOT, "%.1f", state.tempoBpm),
                Modifier.alignByBaseline(),
                style = d.bpm(),
            )
            BasicText(
                // Same width as "BPM" (mono), so the bar never shifts while tapping.
                if (tapping) "TAP" else "BPM",
                Modifier.alignByBaseline(),
                style = d.meta(if (tapping) GrooveColors.Select else GrooveColors.TextDim),
            )
        }
    }
}

/**
 * The Tape screen's slot chip and Export WAV (ADR 0002's exception: they take the slot buttons'
 * place). Build-22 (ADR 0003 P5, P6): Undo moved to the Tape footer, in the left thumb's reach,
 * and the slot chip ("slot 2 ▾") names the slot the keys play and Commit takes: a tap steps to
 * the next slot ([SlotChip]); a long-press opens the eight slot buttons here for a direct pick,
 * and picking one closes them. Export WAV (F7.1) is live when the tape has audio: a tap renders
 * the loop to a WAV (the button shows the progress) and then opens the share sheet.
 */
@Composable
private fun TapeButtons(
    state: GrooveState,
    height: Dp,
    slotButton: @Composable (slot: Int, onPicked: () -> Unit) -> Unit,
) {
    val d = LocalDesign.current
    val haptics = LocalHapticFeedback.current
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        Row(horizontalArrangement = Arrangement.spacedBy(d.dp(GrooveSpace.GROUP))) {
            for (slot in 0 until SeqLimits.SLOTS) slotButton(slot) { picking = false }
        }
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(d.dp(GrooveSpace.ZONE))) {
        val slot = state.selectedSlot
        BarButton(
            width = d.dp(108),
            height = height,
            background = GrooveColors.Panel,
            modifier = Modifier
                .semantics {
                    contentDescription = "Slot ${slot + 1}, ${state.instrumentName(slot)}, plays on the keys and commits. " +
                        "Tap for the next slot, hold to pick one"
                    onLongClick("Pick a slot") { picking = true; true }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { state.slotChipTap() },
                        onLongPress = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            picking = true
                        },
                    )
                },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.dp(GrooveSpace.PAD))) {
                // The selected slot's coral, as on the slot buttons.
                Box(Modifier.size(d.dp(8)).clip(CircleShape).background(GrooveColors.SlotSelect))
                BasicText(SlotChip.label(slot), style = d.buttonLabel(GrooveColors.Text), maxLines = 1)
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
            .clip(d.shape(GrooveRadius.CONTROL))
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
