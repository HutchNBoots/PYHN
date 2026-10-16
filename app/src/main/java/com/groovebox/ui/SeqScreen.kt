package com.groovebox.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.groovebox.ui.model.KeyboardLayout
import com.groovebox.ui.model.NoteNames
import com.groovebox.ui.model.SeqLayer
import com.groovebox.ui.model.SeqStrips
import com.groovebox.ui.theme.GrooveColors
import com.groovebox.ui.theme.LocalDesign
import kotlin.math.roundToInt

/** Colours of the Sequencer mockup (docs/mockups/Sequencer.dc.html) beyond the shared palette. */
private object SeqColors {
    val Playhead = GrooveColors.Strips[2]
    val OnOutside = Color(0xFF5C5A55)
    val OffInside = Color(0xFF2C2C32)
    val OffOutside = Color(0xFF1F1F24)
    val PageRing = Color(0xFF45454D)
    val RowLabel = Color(0xFF5C5A55)
    val PadEdgeBeat = Color(0xFF3A3A42)
    val PadEdge = Color(0xFF26262C)
    val PadNumber = Color(0xFF7A7872)
    val Loop = GrooveColors.Strips[3]
    val Selected = GrooveColors.Shift
    val Motion = GrooveColors.Strips[0]
}

private const val PADS = 16
private const val PAGES = 4

/** The Sequencer screen's middle row: page rail, pattern display and the four strips. */
@Composable
fun SeqMiddleRow(state: GrooveState, audioStarted: Boolean, debug: DebugSlot, modifier: Modifier = Modifier) {
    val d = LocalDesign.current
    val seq = state.seq
    val layer = seq.layer
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(d.dp(10))) {
        Column(Modifier.width(d.dp(64)).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(d.dp(4))) {
            Rail(
                labels = List(PAGES) { "${it + 1}" },
                selected = state.page,
                description = { "Page ${it + 1}, steps ${it * PADS + 1} to ${it * PADS + PADS}" },
                onSelect = { state.page = it },
                modifier = Modifier.fillMaxWidth().weight(1f),
                mono = true,
            )
            ClearButton(state, Modifier.fillMaxWidth().height(d.dp(40)))
        }
        // ADR 0003: swipe the pattern display to step through the slots (the drum slot included).
        SeqDisplay(state, audioStarted, debug, Modifier.weight(1f).fillMaxHeight().slotSwipe { state.swipeSlot(it, includeDrum = true) })
        val labels = if (layer == SeqLayer.Step) listOf("—", "—", "—", "Motion") else List(4) { "—" }
        val values = if (layer == SeqLayer.Step) listOf(null, null, null, "—") else List(4) { null }
        StripRow(seq.strips, seq, labels, values)
    }
}

@Composable
private fun SeqDisplay(state: GrooveState, audioStarted: Boolean, debug: DebugSlot, modifier: Modifier) {
    val d = LocalDesign.current
    val slot = state.selectedSlot
    val pattern = state.patternOf(slot)
    val queued = state.queuedOf(slot)
    val settings = state.settings()
    val layer = state.seq.layer
    val selected = state.selectedStep
    val instrument = if (state.isDrum(slot)) "Kit" else state.synth(slot).presetName
    val loop = state.stepLoop
    val rate = SeqStrips.RATE_NAMES.getOrElse(settings.rate) { "?" }
    val subtitle = buildString {
        append("slot ${slot + 1} · $instrument · $rate · ")
        append(if (loop != null) "loop ${loop.first + 1}–${loop.second + 1}" else "length ${settings.length}")
    }

    Column(
        modifier
            .clip(RoundedCornerShape(d.dp(16)))
            .background(GrooveColors.Panel)
            .padding(horizontal = d.dp(18), vertical = d.dp(14))
            .semantics { contentDescription = "Pattern overview: pattern ${pattern + 1}, ${settings.length} steps" },
        verticalArrangement = Arrangement.spacedBy(d.dp(10)),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(d.dp(10))) {
            DisplayTitle("Pattern ${pattern + 1}", debug, Modifier.alignByBaseline())
            if (queued >= 0) {
                // F3.9: the queued pattern blinks until it starts.
                BasicText(
                    "→ ${queued + 1}",
                    Modifier.alignByBaseline().alpha(if (state.blinkOn) 1f else 0.25f),
                    style = d.value(15f, GrooveColors.Shift, FontWeight.Medium),
                    maxLines = 1,
                )
            }
            DisplaySubtitle(subtitle, audioStarted, Modifier.weight(1f).alignByBaseline())
            state.keyScale.displayName?.let { KeyName(it, Modifier.alignByBaseline()) }
            val label = when (layer) {
                SeqLayer.Pattern -> "MAIN · pattern"
                SeqLayer.Transport -> "SHIFT · tempo / key"
                SeqLayer.Step -> "SHIFT · editing step ${(selected ?: 0) + 1}"
            }
            LayerLabel(label, layer != SeqLayer.Pattern, Modifier.alignByBaseline())
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Overview(state, Modifier.fillMaxWidth())
            if (debug.open) debug.panel()
        }
        Footer(state)
    }
}

/** Four rows of 16 cells: every step of the pattern, the playhead and the page being shown. */
@Composable
private fun Overview(state: GrooveState, modifier: Modifier) {
    val d = LocalDesign.current
    val slot = state.selectedSlot
    val settings = state.settings()
    val on = BooleanArray(64) { state.step(it).on }
    val selected = state.selectedStep
    val loop = state.stepLoop
    val page = state.page
    val cellGap = d.px(4f)
    val radius = CornerRadius(d.px(4f))
    val ring = d.px(1f)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(d.dp(6))) {
        for (row in 0 until PAGES) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.dp(4))) {
                BasicText(
                    "${row + 1}",
                    Modifier.width(d.dp(14)),
                    style = d.value(11f, if (row == page) GrooveColors.Text else SeqColors.RowLabel),
                )
                Canvas(Modifier.weight(1f).height(d.dp(18))) {
                    // Read here, in the draw phase, so the playhead moving only redraws.
                    val playhead = state.stepOf(slot)
                    val w = (size.width - cellGap * (PADS - 1)) / PADS
                    for (c in 0 until PADS) {
                        val i = row * PADS + c
                        val inside = i < settings.length
                        val colour = when {
                            i == playhead -> SeqColors.Playhead
                            on[i] && inside -> GrooveColors.Text
                            on[i] -> SeqColors.OnOutside
                            inside -> SeqColors.OffInside
                            else -> SeqColors.OffOutside
                        }
                        val topLeft = Offset(c * (w + cellGap), 0f)
                        val cell = Size(w, size.height)
                        drawRoundRect(colour, topLeft, cell, radius)
                        val edge = when {
                            i == selected -> SeqColors.Selected
                            loop != null && i in loop.first..loop.second -> SeqColors.Loop
                            row == page -> SeqColors.PageRing
                            else -> null
                        }
                        if (edge != null) {
                            val width = if (edge == SeqColors.PageRing) ring else ring * 2
                            drawRoundRect(
                                edge,
                                topLeft + Offset(width / 2, width / 2),
                                Size(cell.width - width, cell.height - width),
                                radius,
                                style = Stroke(width),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Clears the selected slot's current pattern (steps, motion, settings). No dialog and no
 * undo, so it takes two taps: the first arms it (coral, "Clear?"), a second within 2 s clears.
 */
@Composable
private fun ClearButton(state: GrooveState, modifier: Modifier) {
    val d = LocalDesign.current
    val armed = state.clearArmed
    val pattern = state.editPattern + 1
    Box(
        modifier
            .clip(RoundedCornerShape(d.dp(10)))
            .background(if (armed) SeqColors.Motion else GrooveColors.Panel)
            .semantics {
                contentDescription = if (armed) "Tap again to clear pattern $pattern" else "Clear pattern $pattern. Tap twice"
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    state.clearPress()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            if (armed) "Clear?" else "Clear",
            style = d.label(13f, if (armed) GrooveColors.Ground else GrooveColors.TextDim, if (armed) FontWeight.Bold else FontWeight.Medium),
            maxLines = 1,
        )
    }
}

/** The selected step's values and motion lane (or the pattern's settings when none is selected). */
@Composable
private fun Footer(state: GrooveState) {
    val d = LocalDesign.current
    val slot = state.selectedSlot
    val selected = state.selectedStep
    val style = d.value(13f, GrooveColors.TextDim)
    Row(horizontalArrangement = Arrangement.spacedBy(d.dp(18))) {
        if (state.clearArmed) {
            BasicText(
                "tap Clear again to empty pattern ${state.editPattern + 1} of slot ${slot + 1} · no undo",
                style = d.value(13f, SeqColors.Motion),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else if (selected != null) {
            val step = state.step(selected)
            val notes = if (step.notes.isEmpty()) {
                "no note"
            } else {
                step.notes.joinToString(" ") { note ->
                    if (state.isDrum(slot)) state.drum.keyLabels[note] ?: "$note" else NoteNames.name(note)
                }
            }
            val offKey = if (state.stepOutOfKey(selected)) " · out of key" else ""
            BasicText(
                "step ${selected + 1} · ${if (step.on) "" else "off · "}$notes$offKey · vel ${SeqStrips.velocity127(step.velocity)}" +
                    " · prob ${(step.probability * 100).roundToInt()}%",
                style = style,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val lane = state.latestLane(selected)
            BasicText(
                if (lane != null) "motion: ${state.targetName(slot, lane.target).lowercase()}" else "no motion",
                style = d.value(13f, if (lane != null) SeqColors.Motion else GrooveColors.TextDim),
                maxLines = 1,
            )
        } else {
            val s = state.settings()
            BasicText(
                "shuffle ${(s.shuffle * 100).roundToInt()}% · gate ${(s.gate * 100).roundToInt()}% · " +
                    "motion lanes ${state.laneCount()}/8",
                style = style,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.armed) BasicText("rec armed", style = d.value(13f, SeqColors.Motion), maxLines = 1)
        }
    }
}

/**
 * The bottom zone of the Sequencer screen: 16 step pads for the current page (F3.5, F3.8).
 * While a step is being edited, the keys sit under the pads so notes can be played into it.
 */
@Composable
fun SeqBottom(state: GrooveState, keyboardLayout: KeyboardLayout, modifier: Modifier = Modifier) {
    val d = LocalDesign.current
    val editing = state.seq.layer == SeqLayer.Step
    Column(modifier, verticalArrangement = Arrangement.spacedBy(d.dp(6))) {
        Row(
            Modifier.fillMaxWidth().weight(if (editing) 0.45f else 1f),
            horizontalArrangement = Arrangement.spacedBy(d.dp(5)),
        ) {
            for (pad in 0 until PADS) StepPad(state, pad, Modifier.weight(1f).fillMaxHeight())
        }
        if (editing) SlotKeyboard(state, keyboardLayout, Modifier.fillMaxWidth().weight(0.55f))
    }
}

@Composable
private fun StepPad(state: GrooveState, pad: Int, modifier: Modifier) {
    val d = LocalDesign.current
    val index = state.page * PADS + pad
    val step = state.step(index)
    val settings = state.settings()
    val isPlay = state.stepOf(state.selectedSlot) == index
    val isSelected = state.selectedStep == index
    val loop = state.stepLoop
    val inLoop = loop != null && index in loop.first..loop.second
    val inside = index < settings.length
    val motion = state.hasMotion(index)
    val offKey = state.stepOutOfKey(index)
    val background = when {
        isPlay -> SeqColors.Playhead
        step.on -> GrooveColors.Text
        else -> GrooveColors.Panel
    }
    val (edgeWidth, edgeColour) = when {
        isSelected -> 3f to SeqColors.Selected
        inLoop -> 2f to SeqColors.Loop
        pad % 4 == 0 -> 1f to SeqColors.PadEdgeBeat
        else -> 1f to SeqColors.PadEdge
    }
    val shape = RoundedCornerShape(d.dp(12))

    Column(
        modifier
            .alpha(if (inside) 1f else 0.35f)
            .clip(shape)
            .background(background)
            .border(d.dp(edgeWidth), edgeColour, shape)
            .semantics {
                contentDescription = "Step ${index + 1}, ${if (step.on) "on" else "off"}" +
                    (if (step.on && step.probability < 1f) ", probability ${(step.probability * 100).roundToInt()}%" else "") +
                    (if (offKey) ", out of key" else "") +
                    (if (!inside) ", past the pattern's end" else "")
                selected = isSelected
            }
            .pointerInput(pad) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val id = down.id
                    val pointer = id.value.toInt()
                    state.padDown(pointer, pad)
                    var released = false
                    try {
                        // Follow this finger until it lifts, even if it wanders off the pad.
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == id }
                            if (change == null || !change.pressed) {
                                released = true
                                break
                            }
                        }
                    } finally {
                        if (released) state.padUp(pointer) else state.padAbandon()
                    }
                }
            }
            .padding(d.dp(8)),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                "${index + 1}",
                style = d.value(12f, if (step.on || isPlay) GrooveColors.Ground else SeqColors.PadNumber),
            )
            Spacer(Modifier.weight(1f))
            // F8.6: a step playing a note outside the key: a small ring (it only marks steps that are on).
            if (offKey) {
                Box(
                    Modifier
                        .size(d.dp(10))
                        .border(d.dp(2f), if (step.on || isPlay) GrooveColors.Ground else GrooveColors.Shift, CircleShape),
                )
                if (motion) Spacer(Modifier.width(d.dp(3)))
            }
            if (motion) Box(Modifier.size(d.dp(6)).clip(CircleShape).background(SeqColors.Motion))
        }
        // Probability below 100%: a blue bar of that width.
        if (step.on && step.probability < 1f) {
            Box(
                Modifier
                    .fillMaxWidth(step.probability.coerceIn(0.02f, 1f))
                    .height(d.dp(4))
                    .clip(RoundedCornerShape(d.dp(2)))
                    .background(GrooveColors.Strips[3]),
            )
        } else {
            Spacer(Modifier.height(d.dp(4)))
        }
    }
}

/** Notes of the two-octave keyboard above the kit's 16 keys: they play nothing on the drum slot. */
val DRUMLESS_NOTES: Set<Int> = (64..71).toSet()
