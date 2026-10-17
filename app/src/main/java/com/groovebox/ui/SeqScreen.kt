package com.groovebox.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.groovebox.ui.model.KeyboardLayout
import com.groovebox.ui.model.NoteNames
import com.groovebox.ui.model.PadMark
import com.groovebox.ui.model.SeqLayer
import com.groovebox.ui.model.SeqStrips
import com.groovebox.ui.model.UndoHold
import com.groovebox.ui.theme.GrooveColors
import com.groovebox.ui.theme.LocalDesign
import kotlinx.coroutines.delay
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

    /** A step where only other sounds than the lane play (drum slot). */
    val Other = Color(0xFF6E6C66)
    val OtherOutside = Color(0xFF3A3936)
    val Cell = Color(0xFF26262C)
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
        val stepShift = layer == SeqLayer.StepShift
        val labels = if (stepShift) listOf("—", "—", "—", "Motion") else List(4) { "—" }
        val values = if (stepShift) listOf(null, null, null, "—") else List(4) { null }
        StripRow(seq.strips, seq, labels, values)
    }
}

@Composable
private fun SeqDisplay(state: GrooveState, audioStarted: Boolean, debug: DebugSlot, modifier: Modifier) {
    val d = LocalDesign.current
    val slot = state.selectedSlot
    val drum = state.isDrum(slot)
    val pattern = state.patternOf(slot)
    val queued = state.queuedOf(slot)
    val settings = state.settings()
    val layer = state.seq.layer
    val selected = state.selectedSteps
    val instrument = if (drum) "Kit" else state.synth(slot).presetName
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
            // Build-20: a tap on the display (not on the sound grid, not a swipe) deselects every step.
            .displayTap { state.deselectSteps() }
            .padding(horizontal = d.dp(18), vertical = d.dp(14))
            .semantics {
                contentDescription = "Pattern overview: pattern ${pattern + 1}, ${settings.length} steps" +
                    (if (selected.isNotEmpty()) ", ${selected.size} selected, tap to deselect" else "")
            },
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
            val focus = (state.focusStep ?: 0) + 1
            val label = when (layer) {
                SeqLayer.Pattern -> "MAIN · pattern"
                SeqLayer.Transport -> "SHIFT · tempo / key"
                SeqLayer.Step -> if (selected.size > 1) "STEP · ${selected.size} steps" else "STEP · step $focus"
                SeqLayer.StepShift -> "SHIFT · step $focus note / motion"
            }
            LayerLabel(label, layer != SeqLayer.Pattern, Modifier.alignByBaseline())
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (drum) {
                // Build-20 lanes: the kit's 16 sounds on the left (left thumb, ADR 0003); build-21: the shared kit grid.
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(d.dp(12))) {
                    KitGrid(state, Modifier.width(d.dp(196)).fillMaxHeight())
                    Overview(state, Modifier.weight(1f))
                }
            } else {
                // Build-21: a synth slot shows its notes as a pitch roll of the current page.
                PitchRollView(state, Modifier.fillMaxSize())
            }
            if (debug.open) debug.panel()
        }
        Footer(state)
    }
}

/**
 * A tap on this element that no child took (the kit grid consumes its touches, the pitch roll
 * and the Undo chip their releases) and that did not move past the touch slop (a slot swipe).
 */
private fun Modifier.displayTap(onTap: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (down.isConsumed) return@awaitEachGesture
        val slop = viewConfiguration.touchSlop
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if ((change.position - down.position).getDistance() > slop) return@awaitEachGesture
            if (!change.pressed) {
                if (!change.isConsumed) onTap()
                return@awaitEachGesture
            }
        }
    }
}

/** Four rows of 16 cells: every step of the pattern (the lane's on the drum slot), the playhead and the page shown. */
@Composable
private fun Overview(state: GrooveState, modifier: Modifier) {
    val d = LocalDesign.current
    val slot = state.selectedSlot
    val settings = state.settings()
    val lane = if (state.isDrum(slot)) state.laneNote else null
    val marks = Array(64) { PadMark.of(state.step(it), lane) }
    val selected = state.selectedSteps.toSet()
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
                            marks[i] == PadMark.Lane -> if (inside) GrooveColors.Text else SeqColors.OnOutside
                            marks[i] == PadMark.Other -> if (inside) SeqColors.Other else SeqColors.OtherOutside
                            inside -> SeqColors.OffInside
                            else -> SeqColors.OffOutside
                        }
                        val topLeft = Offset(c * (w + cellGap), 0f)
                        val cell = Size(w, size.height)
                        if (marks[i] == PadMark.Other && i != playhead) {
                            // Only other sounds: a small dot on the empty cell, as on the pads.
                            drawRoundRect(if (inside) SeqColors.OffInside else SeqColors.OffOutside, topLeft, cell, radius)
                            drawCircle(colour, radius = minOf(w, size.height) * 0.16f, center = topLeft + Offset(w / 2, size.height / 2))
                        } else {
                            drawRoundRect(colour, topLeft, cell, radius)
                        }
                        val edge = when {
                            i in selected -> SeqColors.Selected
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
 * Clears the selected slot's current pattern (steps, motion, settings), or with Shift on the
 * drum slot only the lane's sound. No dialog, so it takes two taps: the first arms it (coral,
 * "Clear?" / "Clear Snar1?"), a second within 2 s clears. Undo brings it back.
 */
@Composable
private fun ClearButton(state: GrooveState, modifier: Modifier) {
    val d = LocalDesign.current
    val armed = state.clearArmed
    val pattern = state.editPattern + 1
    val laneMode = if (armed) state.clearArmedLane else state.shift.shifted && state.isDrum(state.selectedSlot)
    val laneName = state.drum.keyLabels[state.laneNote] ?: "sound"
    Box(
        modifier
            .clip(RoundedCornerShape(d.dp(10)))
            .background(if (armed) SeqColors.Motion else GrooveColors.Panel)
            .semantics {
                contentDescription = when {
                    armed && laneMode -> "Tap again to clear $laneName from pattern $pattern"
                    armed -> "Tap again to clear pattern $pattern"
                    laneMode -> "Clear $laneName from pattern $pattern. Tap twice"
                    else -> "Clear pattern $pattern. Tap twice"
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    state.clearPress()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val colour = if (armed) GrooveColors.Ground else if (laneMode) GrooveColors.Shift else GrooveColors.TextDim
        val weight = if (armed) FontWeight.Bold else FontWeight.Medium
        if (laneMode) {
            // Two lines: "Clear" over the sound's short name ("Snar1?" when armed).
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText("Clear", style = d.label(11f, colour, weight), maxLines = 1)
                BasicText(if (armed) "$laneName?" else laneName, style = d.label(11f, colour, weight), maxLines = 1, overflow = TextOverflow.Clip)
            }
        } else {
            BasicText(if (armed) "Clear?" else "Clear", style = d.label(13f, colour, weight), maxLines = 1)
        }
    }
}

/**
 * The Seq footer: the Undo chip at its left end (left thumb; hold = redo), then the selected
 * step's values and motion lane (or the pattern's settings when none is selected).
 */
@Composable
private fun Footer(state: GrooveState) {
    val d = LocalDesign.current
    val slot = state.selectedSlot
    val drum = state.isDrum(slot)
    val focus = state.focusStep
    val style = d.value(13f, GrooveColors.TextDim)
    Row(horizontalArrangement = Arrangement.spacedBy(d.dp(12)), verticalAlignment = Alignment.CenterVertically) {
        UndoChip(state)
        if (state.clearArmed) {
            val text = if (state.clearArmedLane) {
                "tap Clear again to take ${state.drum.keyLabels[state.laneNote] ?: "the sound"} out of pattern ${state.editPattern + 1} · undoable"
            } else {
                "tap Clear again to empty pattern ${state.editPattern + 1} of slot ${slot + 1} · undoable"
            }
            BasicText(text, style = d.value(13f, SeqColors.Motion), maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else if (focus != null) {
            val step = state.step(focus)
            val count = state.selectedSteps.size
            val where = if (count > 1) "$count steps · last ${focus + 1}" else "step ${focus + 1}"
            // The hit's values are on the strips, right under their labels; the footer names the step.
            val text = if (drum) {
                val name = state.drum.keyLabels[state.laneNote] ?: "sound"
                val others = step.hits.filter { it.note != state.laneNote }
                    .joinToString(" ") { state.drum.keyLabels[it.note] ?: "${it.note}" }
                val also = if (others.isEmpty()) "" else " · also $others"
                if (step.hit(state.laneNote) == null) "$where · no $name$also · a strip adds it" else "$where · $name$also"
            } else if (!step.on) {
                "$where · empty · play keys or move a strip"
            } else {
                val offKey = if (state.stepOutOfKey(focus)) " · out of key" else ""
                "$where · ${step.notes.joinToString(" ") { NoteNames.name(it) }}$offKey"
            }
            BasicText(text, Modifier.weight(1f, fill = false), style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val lane = state.latestLane(focus)
            if (lane != null) {
                BasicText("motion: ${state.targetName(slot, lane.target).lowercase()}", style = d.value(13f, SeqColors.Motion), maxLines = 1)
            }
        } else {
            val s = state.settings()
            BasicText(
                "shuffle ${(s.shuffle * 100).roundToInt()}% · gate ${(s.gate * 100).roundToInt()}% · " +
                    "motion lanes ${state.laneCount()}/8",
                Modifier.weight(1f, fill = false),
                style = style,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.armed) BasicText("rec armed", style = d.value(13f, SeqColors.Motion), maxLines = 1)
        }
    }
}

/**
 * Undo of the sequencer's edits, at the left end of the footer (build-20, left thumb). Build-21:
 * a tap undoes, a long-press redoes (the chip briefly reads "Redo"); Shift no longer changes it,
 * so it never redoes by accident ([UndoHold], as the Tape screen's Undo). The undo acts on the
 * release, as it must wait to know the touch is not a long-press. Its touches are consumed, so
 * they neither deselect steps nor swipe the slot.
 */
@Composable
private fun UndoChip(state: GrooveState) {
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
    val enabled = state.seqCanUndo || state.seqCanRedo
    val label = if (readsRedo) "Redo" else "Undo"
    Box(
        Modifier
            .height(d.dp(26))
            .width(d.dp(58))
            .alpha(if (enabled) 1f else GrooveColors.INERT_ALPHA)
            .clip(RoundedCornerShape(d.dp(8)))
            .background(if (readsRedo) GrooveColors.Text else SeqColors.Cell)
            .semantics {
                contentDescription = "Undo sequencer edit" + (if (state.seqCanUndo) "" else ", nothing to undo") +
                    ". Hold to redo" + (if (state.seqCanRedo) "" else ", nothing to redo")
                onLongClick("Redo sequencer edit") { state.seqUndoPress(redo = true); true }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    hold.down(down.uptimeMillis)
                    var cancelled = false
                    var upAt = 0L
                    val released = withTimeoutOrNull(hold.longPressMs) {
                        val up = waitForUpOrCancellation()
                        if (up == null) cancelled = true else { upAt = up.uptimeMillis; up.consume() }
                    } != null
                    when {
                        cancelled -> hold.cancel()
                        released -> if (hold.up(upAt) == UndoHold.Action.Undo) state.seqUndoPress(redo = false)
                        else -> {
                            // Held to the long-press time: redo once, now.
                            if (hold.poll(down.uptimeMillis + hold.longPressMs) == UndoHold.Action.Redo) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                state.seqUndoPress(redo = true)
                                readsRedo = true
                            }
                            val up = waitForUpOrCancellation()
                            if (up == null) hold.cancel() else { up.consume(); hold.up(up.uptimeMillis) }
                            flash++
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = d.label(12f, if (readsRedo) GrooveColors.Ground else GrooveColors.Text, FontWeight.Medium), maxLines = 1)
    }
}

/**
 * The bottom zone of the Sequencer screen: 16 step pads for the current page (F3.5, F3.8,
 * build-20). While synth steps are selected, the keys sit under the pads so notes can be
 * played into them.
 */
@Composable
fun SeqBottom(state: GrooveState, keyboardLayout: KeyboardLayout, modifier: Modifier = Modifier) {
    val d = LocalDesign.current
    val editing = state.editingSteps.isNotEmpty()
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

/**
 * Follows pointer [id] until it lifts (true) or until it has been held still for [timeMs]
 * (null: a long-press); false when it moved past [slop] first.
 */
private suspend fun AwaitPointerEventScope.awaitTapOrLongPress(id: PointerId, start: Offset, slop: Float, timeMs: Long): Boolean? =
    withTimeoutOrNull(timeMs) {
        var result = false
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == id }
            if (change == null || !change.pressed) {
                result = true
                break
            }
            if ((change.position - start).getDistance() > slop) break
        }
        result
    }

@Composable
private fun StepPad(state: GrooveState, pad: Int, modifier: Modifier) {
    val d = LocalDesign.current
    val index = state.page * PADS + pad
    val step = state.step(index)
    val settings = state.settings()
    val drum = state.isDrum(state.selectedSlot)
    val lane = if (drum) state.laneNote else null
    val mark = PadMark.of(step, lane)
    val hit = if (lane != null) step.hit(lane) else step.hits.firstOrNull()
    val isPlay = state.stepOf(state.selectedSlot) == index
    val isSelected = index in state.selectedSteps
    val loop = state.stepLoop
    val inLoop = loop != null && index in loop.first..loop.second
    val inside = index < settings.length
    val motion = state.hasMotion(index)
    val offKey = state.stepOutOfKey(index)
    val lit = mark == PadMark.Lane
    val background = when {
        isPlay -> SeqColors.Playhead
        lit -> GrooveColors.Text
        else -> GrooveColors.Panel
    }
    val (edgeWidth, edgeColour) = when {
        isSelected -> 3f to SeqColors.Selected
        inLoop -> 2f to SeqColors.Loop
        pad % 4 == 0 -> 1f to SeqColors.PadEdgeBeat
        else -> 1f to SeqColors.PadEdge
    }
    val shape = RoundedCornerShape(d.dp(12))
    val laneName = lane?.let { state.drum.keyLabels[it] ?: "sound" }
    // Build-21: a lit synth pad names its note, chord or lowest note and count.
    val noteLabel = if (lane == null && step.on) NoteNames.padLabel(step.notes) else null

    Column(
        modifier
            .alpha(if (inside) 1f else 0.35f)
            .clip(shape)
            .background(background)
            .border(d.dp(edgeWidth), edgeColour, shape)
            .semantics {
                val what = when {
                    laneName == null -> if (step.on) "on, ${step.notes.joinToString(" ") { NoteNames.name(it) }}" else "off"
                    lit -> "$laneName on"
                    else -> "$laneName off"
                }
                val others = if (lane != null) step.hits.count { it.note != lane } else 0
                contentDescription = "Step ${index + 1}, $what" +
                    (if (others > 0) ", $others other sound${if (others > 1) "s" else ""}" else "") +
                    (if (hit != null && hit.probability < 1f) ", chance ${(hit.probability * 100).roundToInt()}%" else "") +
                    (if (offKey) ", out of key" else "") +
                    (if (isSelected) ", selected" else "") +
                    (if (!inside) ", past the pattern's end" else "") +
                    ". Tap to toggle, hold to select"
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
                        // Build-20: held still for the platform's long-press time = select.
                        when (awaitTapOrLongPress(id, down.position, viewConfiguration.touchSlop, viewConfiguration.longPressTimeoutMillis)) {
                            true -> released = true
                            null -> state.padLongPress(pointer)
                            false -> Unit // moved: still a tap when it lifts
                        }
                        // Follow this finger until it lifts, even if it wanders off the pad.
                        while (!released) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == id }
                            if (change == null || !change.pressed) released = true
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
                style = d.value(12f, if (lit || isPlay) GrooveColors.Ground else SeqColors.PadNumber),
            )
            Spacer(Modifier.weight(1f))
            // F8.6: a step playing a note outside the key: a small ring (it only marks steps that are on).
            if (offKey) {
                Box(
                    Modifier
                        .size(d.dp(10))
                        .border(d.dp(2f), if (lit || isPlay) GrooveColors.Ground else GrooveColors.Shift, CircleShape),
                )
                if (motion) Spacer(Modifier.width(d.dp(3)))
            }
            if (motion) Box(Modifier.size(d.dp(6)).clip(CircleShape).background(SeqColors.Motion))
        }
        if (noteLabel != null) {
            BasicText(
                noteLabel,
                style = d.value(12f, if (lit || isPlay) GrooveColors.Ground else GrooveColors.Text, FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
        // Build-20: only other sounds than the lane play here: a small dot.
        if (mark == PadMark.Other) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(d.dp(8))
                    .clip(CircleShape)
                    .background(if (isPlay) GrooveColors.Ground else SeqColors.Other),
            )
        }
        // Chance below 100%: a blue bar of that width.
        if (hit != null && hit.probability < 1f) {
            Box(
                Modifier
                    .fillMaxWidth(hit.probability.coerceIn(0.02f, 1f))
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
