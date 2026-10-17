package com.groovebox.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.groovebox.ui.model.ExportStatus
import com.groovebox.ui.model.MixKeys
import com.groovebox.ui.model.MixLimits
import com.groovebox.ui.model.StripRef
import com.groovebox.ui.model.TapeRecordStatus
import com.groovebox.ui.model.TapeStrips
import com.groovebox.ui.model.TapeView
import com.groovebox.ui.theme.GrooveColors
import com.groovebox.ui.theme.LocalDesign
import kotlin.math.roundToInt

/** Colours of the Tape mockup (docs/mockups/Tape.dc.html) beyond the shared palette. */
private object TapeColors {
    val Lane = Color(0xFF18181C)
    val LaneSelected = Color(0xFF2A2A30)
    val Name = Color(0xFF7A7872)
    val Head = GrooveColors.Strips[2]
    val Loop = GrooveColors.Strips[1]
    val Record = GrooveColors.Strips[0]

    /** Lane colours follow the strip colours by track. */
    val Tracks = GrooveColors.Strips
}

/** The Tape screen's middle row (F5): the four-track display and the strips. */
@Composable
fun TapeMiddleRow(
    state: GrooveState,
    audioStarted: Boolean,
    debug: DebugSlot,
    modifier: Modifier = Modifier,
    onLanesPositioned: (Rect) -> Unit = {},
) {
    val d = LocalDesign.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(d.dp(10))) {
        TapeDisplay(state, audioStarted, debug, Modifier.weight(1f).fillMaxHeight(), onLanesPositioned)
        val tape = state.tape
        val specs = tape.strips
        Row(Modifier.width(d.dp(332)).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(d.dp(8))) {
            tape.refs.forEachIndexed { i, ref ->
                val strip = Modifier.weight(1f).fillMaxHeight()
                if (ref == StripRef.Commit) CommitStrip(i, state, strip) else ColourStrip(i, specs[i], tape, strip)
            }
        }
    }
}

@Composable
private fun TapeDisplay(state: GrooveState, audioStarted: Boolean, debug: DebugSlot, modifier: Modifier, onLanesPositioned: (Rect) -> Unit) {
    val d = LocalDesign.current
    val mix = state.mix
    val shifted = state.shift.shifted
    val track = state.tapeTrack
    val speed = mix.mixSpec(MixKeys.SPEED)?.let { tape -> state.tape.format(tape, mix.mix(tape)) } ?: "1×"
    val region = TapeView.loopRegion(
        mix.mix(MixKeys.LOOP_START).roundToInt(),
        mix.mix(MixKeys.LOOP_LEN).roundToInt(),
        state.tapeBars,
    )
    val subtitle = if (state.tapeLoading) {
        TapeView.LOADING
    } else {
        TapeView.subtitle(state.tapeBars, region, mix.mix(MixKeys.DUB), speed, mix.mix(MixKeys.REVERSE) >= 0.5)
    }

    Column(
        modifier
            .clip(RoundedCornerShape(d.dp(16)))
            .background(GrooveColors.Panel)
            .padding(horizontal = d.dp(18), vertical = d.dp(12)),
        verticalArrangement = Arrangement.spacedBy(d.dp(8)),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(d.dp(10))) {
            DisplayTitle("Tape", debug, Modifier.alignByBaseline())
            DisplaySubtitle(subtitle, audioStarted, Modifier.weight(1f).alignByBaseline())
            LayerLabel(
                if (shifted) "SHIFT · track ${TapeView.trackName(track)}" else "MAIN · speed, loop and dub",
                shifted,
                Modifier.alignByBaseline(),
            )
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            // A saved tape still loading (F7.6): the lanes fill in as it lands, dimmed until then.
            TapeLanes(
                state,
                Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { onLanesPositioned(it.boundsInRoot()) }
                    .alpha(if (state.tapeLoading) 0.45f else 1f),
            )
            if (debug.open) debug.panel()
        }
        val export = state.exportStatus
        if (export is ExportStatus.Rendering) ExportProgress(export.progress, Modifier.fillMaxWidth())
        TapeFooter(state)
    }
}

/**
 * The four lanes, drawn from the peak overviews, with the loop bracket above them and the
 * moving head. Everything that changes while playing is read in the draw phase, so the head
 * moving only redraws this canvas. Tap a lane to select its track, double-tap it to record
 * onto it (or stop its take). Build-21: hold it and it asks "Clear T3?" (coral); a tap on that
 * lane within 3 s clears it, anything else cancels.
 */
@Composable
private fun TapeLanes(state: GrooveState, modifier: Modifier) {
    val d = LocalDesign.current
    val haptics = LocalHapticFeedback.current
    val measurer = rememberTextMeasurer()
    val nameStyle = d.value(12f, TapeColors.Name)
    val tagStyle = d.value(10f, TapeColors.Record, FontWeight.Medium)
    val askStyle = d.label(14f, TapeColors.Record, FontWeight.Bold)
    val top = d.px(10f) // room for the loop bracket
    val gap = d.px(6f)
    val nameWidth = d.px(36f)
    val radius = CornerRadius(d.px(8f))
    val column = d.px(2f)
    val clearLane = state.tapeClearLane
    val description = "Tape tracks, ${TapeView.trackName(state.tapeTrack)} selected. " +
        (if (clearLane >= 0) "Tap ${TapeView.trackName(clearLane)} again to clear it. " else "") +
        "Tap a lane to select it, double-tap to record onto it, hold to clear it"

    Canvas(
        modifier
            .semantics { contentDescription = description }
            .pointerInput(top, gap) {
                fun laneAt(y: Float): Int? = TapeView.laneAt(y - top, size.height - top, MixLimits.TRACKS, gap)
                detectTapGestures(
                    onPress = { p -> laneAt(p.y)?.let(state::tapeLaneDown) },
                    onTap = { p -> laneAt(p.y)?.let(state::tapeLaneTap) },
                    // ADR 0003: Record within the left thumb's reach (arm this lane, or stop its take).
                    onDoubleTap = { p -> laneAt(p.y)?.let(state::tapeLaneDoubleTap) },
                    onLongPress = { p ->
                        val lane = laneAt(p.y)
                        if (lane != null && state.tapeLaneHold(lane)) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    },
                )
            },
    ) {
        state.overviewRevision // redraw when the overviews change
        val mix = state.mix
        val bars = state.tapeBars
        val region = TapeView.loopRegion(mix.mix(MixKeys.LOOP_START).roundToInt(), mix.mix(MixKeys.LOOP_LEN).roundToInt(), bars)
        val selected = state.tapeTrack
        val status = state.tapeStatus
        val busyTrack = if (status == TapeRecordStatus.Recording) state.tapeRecordingTrack else state.tapeArmedTrack
        val laneH = (size.height - top - gap * (MixLimits.TRACKS - 1)) / MixLimits.TRACKS
        val waveLeft = nameWidth
        val waveW = size.width - waveLeft
        val edge = d.px(2f)

        fun xOf(fraction: Float) = waveLeft + waveW * fraction

        for (t in 0 until MixLimits.TRACKS) {
            val y = top + t * (laneH + gap)
            val isSelected = t == selected
            drawRoundRect(if (isSelected) TapeColors.LaneSelected else TapeColors.Lane, Offset(0f, y), Size(size.width, laneH), radius)

            // Peaks, mirrored about the lane's centre; dimmer outside the loop region.
            if (state.trackHasAudio(t) && bars > 0) {
                val colour = TapeColors.Tracks[t]
                val columns = (waveW / column).toInt().coerceAtLeast(1)
                val mid = y + laneH / 2
                val amp = laneH / 2 - d.px(5f)
                for (c in 0 until columns) {
                    val p = state.overview.column(t, c, columns)
                    if (p <= 0f) continue
                    val bar = c.toFloat() / columns * bars
                    val inside = region == null || (bar >= region.startBar && bar < region.endBar)
                    val h = (p * amp).coerceAtLeast(d.px(0.5f))
                    drawRect(
                        colour.copy(alpha = if (inside) 0.85f else 0.35f),
                        Offset(waveLeft + c * column, mid - h),
                        Size(column * 0.7f, h * 2),
                    )
                }
                drawRect(colour, Offset(waveLeft, y + d.px(5f)), Size(d.px(3f), laneH - d.px(10f)))
            }

            if (isSelected) {
                drawRoundRect(GrooveColors.Text, Offset(0f, y), Size(size.width, laneH), radius, style = Stroke(d.px(1f)))
            }
            // Armed: a blinking coral outline; recording: a solid one and a REC tag.
            if (t == busyTrack && (status == TapeRecordStatus.Recording || state.blinkOn)) {
                drawRoundRect(
                    TapeColors.Record,
                    Offset(edge / 2, y + edge / 2),
                    Size(size.width - edge, laneH - edge),
                    radius,
                    style = Stroke(edge),
                )
            }

            // Asking before a clear: a solid coral outline and "Clear T3?" across the lane.
            if (t == clearLane) {
                drawRoundRect(TapeColors.Record.copy(alpha = 0.16f), Offset(0f, y), Size(size.width, laneH), radius)
                drawRoundRect(
                    TapeColors.Record,
                    Offset(edge / 2, y + edge / 2),
                    Size(size.width - edge, laneH - edge),
                    radius,
                    style = Stroke(edge),
                )
                val ask = measurer.measure("Clear ${TapeView.trackName(t)}?", askStyle)
                val askLeft = waveLeft + (waveW - ask.size.width) / 2
                val askTop = y + (laneH - ask.size.height) / 2
                val pad = d.px(10f)
                // A dark plate under the question so it reads over the waveform.
                drawRoundRect(
                    GrooveColors.Ground,
                    Offset(askLeft - pad, y + edge * 2),
                    Size(ask.size.width + pad * 2, laneH - edge * 4),
                    CornerRadius(d.px(6f)),
                )
                drawText(ask, topLeft = Offset(askLeft, askTop))
            }

            val name = measurer.measure(TapeView.trackName(t), nameStyle.copy(color = if (isSelected) GrooveColors.Text else TapeColors.Name))
            drawText(name, topLeft = Offset(d.px(8f), y + (laneH - name.size.height) / 2))
            val tag = when {
                t == busyTrack && status == TapeRecordStatus.Recording -> "REC"
                t == busyTrack && status == TapeRecordStatus.Armed -> "ARMED"
                mix.muted(MixLimits.tapeChannel(t)) -> "MUTED"
                else -> null
            }
            if (tag != null) {
                val text = measurer.measure(tag, tagStyle)
                drawText(text, topLeft = Offset(size.width - text.size.width - d.px(8f), y + (laneH - text.size.height) / 2))
            }
        }

        // The loop bracket over the lanes.
        if (region != null) {
            val x0 = xOf(region.startBar.toFloat() / bars)
            val x1 = xOf(region.endBar.toFloat() / bars)
            drawRoundRect(TapeColors.Loop, Offset(x0, d.px(2f)), Size(x1 - x0, d.px(4f)), CornerRadius(d.px(2f)))
        }

        // The head, every frame.
        val head = TapeView.headFraction(state.tapeHeadFrame, state.tapeLengthFrames)
        drawRect(TapeColors.Head, Offset(xOf(head) - d.px(1f), d.px(6f)), Size(d.px(2f), size.height - d.px(6f)))
    }
}

/** A thin bar under the lanes while Export WAV renders. */
@Composable
private fun ExportProgress(progress: Float, modifier: Modifier) {
    val d = LocalDesign.current
    Box(modifier.height(d.dp(4)).clip(RoundedCornerShape(d.dp(2))).background(TapeColors.Lane)) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(d.dp(2)))
                .background(GrooveColors.Strips[3]),
        )
    }
}

/** One line under the lanes: the export's progress or result, what Record is doing, or how to use the lanes. */
@Composable
private fun TapeFooter(state: GrooveState) {
    val d = LocalDesign.current
    val track = TapeView.trackName(state.tapeTrack)
    val export = state.exportStatus
    val exportLine = TapeView.exportLine(export, state.tapeBars, state.mix.mix(MixKeys.LOOP_START).roundToInt(), state.mix.mix(MixKeys.LOOP_LEN).roundToInt())
    if (exportLine != null) {
        val colour = if (export is ExportStatus.Failed) GrooveColors.Warn else GrooveColors.Strips[3]
        BasicText(exportLine, style = d.value(13f, colour), maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }
    val recordNeeds = state.tapeRecordNeeds
    val clearLane = state.tapeClearLane
    if (clearLane >= 0) {
        val name = TapeView.trackName(clearLane)
        BasicText("tap $name again to clear it · anything else keeps it · Undo brings it back", style = d.value(13f, TapeColors.Record), maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }
    val (text, colour) = when (state.tapeStatus) {
        TapeRecordStatus.Recording ->
            "recording ${TapeView.trackName(state.tapeRecordingTrack)} · Record (or double-tap the lane) stops on the next bar" to TapeColors.Record
        TapeRecordStatus.Armed -> {
            val armed = TapeView.trackName(state.tapeArmedTrack)
            (if (state.playing) "$armed armed · starts on the next bar" else "$armed armed · starts on Play") to TapeColors.Record
        }
        TapeRecordStatus.Idle -> when {
            recordNeeds != null -> recordNeeds to GrooveColors.Warn
            state.tapeLoading -> "loading the saved tape · play on, it lands in a moment" to GrooveColors.TextDim
            state.tapeBars == 0 -> "$track selected · double-tap a lane or Record to start a take" to GrooveColors.TextDim
            else -> "$track selected · tap a lane to pick it, double-tap to record, hold to clear" to GrooveColors.TextDim
        }
    }
    BasicText(text, style = d.value(13f, colour), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/**
 * The Commit strip (F5.6) on the Tape Shift layer: tap commits the selected slot's current
 * pattern onto the selected track; long-press commits the full mix of every slot.
 */
@Composable
private fun CommitStrip(position: Int, state: GrooveState, modifier: Modifier) {
    val d = LocalDesign.current
    val haptics = LocalHapticFeedback.current
    val colour = GrooveColors.Strips[position]
    val shape = RoundedCornerShape(d.dp(14))
    val slot = state.selectedSlot
    val target = TapeStrips.commitValue(slot)
    val track = TapeView.trackName(state.tapeTrack)
    Column(
        modifier
            .clip(shape)
            .background(colour.copy(alpha = 0x38 / 255f))
            .border(d.dp(2), colour, shape)
            .semantics {
                contentDescription = "Commit slot ${slot + 1}'s pattern to $track. Hold to commit every slot"
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { state.commit(allSlots = false) },
                    onLongPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        state.commit(allSlots = true)
                    },
                )
            }
            .padding(horizontal = d.dp(6), vertical = d.dp(10)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(TapeStrips.COMMIT_LABEL, style = d.label(14f, colour, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Clip)
        Spacer(Modifier.weight(1f))
        BasicText(
            "tap\n$target\n\nhold\nall",
            style = d.value(11f, GrooveColors.TextDim).copy(textAlign = TextAlign.Center),
        )
        Spacer(Modifier.weight(1f))
        BasicText("→ $track", style = d.value(13f, GrooveColors.White), maxLines = 1, overflow = TextOverflow.Clip)
    }
}
