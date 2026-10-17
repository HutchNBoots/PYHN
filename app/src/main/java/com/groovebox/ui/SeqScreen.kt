package com.groovebox.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.groovebox.ui.model.Accent
import com.groovebox.ui.model.ChordName
import com.groovebox.ui.model.KeyboardLayout
import com.groovebox.ui.model.LayerText
import com.groovebox.ui.model.NoteNames
import com.groovebox.ui.model.PadMark
import com.groovebox.ui.model.PatternRowGesture
import com.groovebox.ui.model.PitchRoll
import com.groovebox.ui.model.SeqLayer
import com.groovebox.ui.model.SeqLimits
import com.groovebox.ui.model.SeqStrips
import com.groovebox.ui.model.UndoHold
import com.groovebox.ui.theme.GrooveColors
import com.groovebox.ui.theme.GrooveRadius
import com.groovebox.ui.theme.GrooveSpace
import com.groovebox.ui.theme.GrooveText
import com.groovebox.ui.theme.LocalDesign
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/** Colours of the Sequencer mockups (docs/mockups/SeqDrumProposed, SeqSynthProposed) beyond the shared palette. */
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

    /** CX review R6: a selected item gets a cream outline (amber is Shift only). */
    val Selected = GrooveColors.Select

    /** A fill armed with Shift + tap: amber, as it is a Shift gesture. */
    val FillArmed = GrooveColors.Shift
    val Motion = GrooveColors.Strips[0]
    val Nudge = GrooveColors.Strips[3]

    /** A step where only other sounds than the lane play (drum slot). */
    val Other = Color(0xFF6E6C66)
    val OtherOutside = Color(0xFF3A3936)
    val Tick = GrooveColors.TextDim
    val CellContent = Color(0xFF34343A)
    val CellEmpty = Color(0xFF1F1F24)
    val CellEmptyText = Color(0xFF5E5C58)
    val Tie = GrooveColors.Text.copy(alpha = 0.55f)
}

private const val PADS = 16
private const val PAGES = 4

/** The Sequencer screen's middle row: page rail, pattern display and the four strips. */
@Composable
fun SeqMiddleRow(state: GrooveState, audioStarted: Boolean, debug: DebugSlot, modifier: Modifier = Modifier) {
    val d = LocalDesign.current
    val seq = state.seq
    val layer = seq.layer
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(d.dp(GrooveSpace.ZONE))) {
        Column(Modifier.width(d.dp(64)).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(d.dp(GrooveSpace.GROUP))) {
            PageRail(state, Modifier.fillMaxWidth().weight(1f))
            ClearButton(state, Modifier.fillMaxWidth().height(d.dp(40)))
        }
        // ADR 0003: swipe the pattern display to step through the slots (the drum slot included).
        SeqDisplay(state, audioStarted, debug, Modifier.weight(1f).fillMaxHeight().slotSwipe { state.swipeSlot(it, includeDrum = true) })
        val stepShift = layer == SeqLayer.StepShift
        // Build-22: Shift strip 2 waits for Slide (build-23); strip 4 is Motion when the step has some.
        val labels = if (stepShift) listOf("—", "—", "Spread", "Motion") else List(4) { "—" }
        val values = if (stepShift) listOf(null, null, null, "—") else List(4) { null }
        StripRow(seq.strips, seq, labels, values)
    }
}

/**
 * The page rail (build-22): a tap shows the page; Shift + tap the page shown toggles page
 * follow (a green bar under it while on), Shift + tap another copies the page shown there. A
 * dot marks pages holding notes.
 */
@Composable
private fun PageRail(state: GrooveState, modifier: Modifier) {
    val d = LocalDesign.current
    val page = state.page
    val shifted = state.shift.shifted
    Column(modifier, verticalArrangement = Arrangement.spacedBy(d.dp(GrooveSpace.GROUP))) {
        for (p in 0 until PAGES) {
            val shown = p == page
            val notes = state.pageHasNotes(p)
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(d.shape(GrooveRadius.CONTROL))
                    .background(if (shown) GrooveColors.Select else GrooveColors.Panel)
                    .semantics {
                        contentDescription = "Page ${p + 1}, steps ${p * PADS + 1} to ${p * PADS + PADS}" +
                            (if (notes) ", has notes" else "") + (if (shown && state.followPage) ", following the playhead" else "") +
                            when {
                                !shifted -> ""
                                shown -> ". Shift: tap to toggle page follow"
                                else -> ". Shift: tap to copy page ${page + 1} here"
                            }
                        selected = shown
                        onClick { state.pageTap(p); true }
                    }
                    .pointerInput(p) {
                        awaitEachGesture {
                            awaitFirstDown().consume()
                            state.pageTap(p)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    "${p + 1}",
                    style = d.value(15f, if (shown) GrooveColors.Ground else if (notes) GrooveColors.Text else SeqColors.PadNumber, FontWeight.Medium),
                )
                if (notes) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(d.dp(8)).size(d.dp(5)).clip(CircleShape)
                            .background(if (shown) GrooveColors.Ground else GrooveColors.Text),
                    )
                }
                if (shown && state.followPage) {
                    Box(
                        Modifier.align(Alignment.BottomCenter).padding(bottom = d.dp(5)).width(d.dp(22)).height(d.dp(3))
                            .clip(CircleShape).background(SeqColors.Playhead),
                    )
                }
            }
        }
    }
}

@Composable
private fun SeqDisplay(state: GrooveState, audioStarted: Boolean, debug: DebugSlot, modifier: Modifier) {
    val d = LocalDesign.current
    val slot = state.selectedSlot
    val drum = state.isDrum(slot)
    val pattern = state.editPattern
    val playingPattern = state.patternOf(slot)
    val queued = state.queuedOf(slot)
    val settings = state.settings()
    val layer = state.seq.layer
    val selected = state.selectedSteps
    val instrument = if (drum) "Kit" else state.synth(slot).presetName
    val loop = state.stepLoop
    val rate = SeqStrips.RATE_NAMES.getOrElse(settings.rate) { "?" }
    val chain = state.chainOf(slot)
    val subtitle = buildString {
        if (state.editingOther) append("plays ${playingPattern + 1} · ")
        append("slot ${slot + 1} · $instrument · $rate · ")
        append(if (loop != null) "loop ${loop.first + 1}–${loop.second + 1}" else "${settings.length} steps")
        if (chain != null) append(" · chain ${chain.first + 1}→${chain.then + 1}")
    }

    Column(
        modifier
            .clip(d.shape(GrooveRadius.PANEL))
            .background(GrooveColors.Panel)
            // Build-20: a tap on the display (not on the sound grid, not a swipe) deselects every step.
            .displayTap { state.deselectSteps() }
            .padding(horizontal = d.dp(GrooveSpace.DISPLAY_H), vertical = d.dp(GrooveSpace.DISPLAY_V))
            .semantics {
                contentDescription = "Pattern overview: pattern ${pattern + 1}, ${settings.length} steps" +
                    (if (state.editingOther) ", pattern ${playingPattern + 1} plays" else "") +
                    (if (selected.isNotEmpty()) ", ${selected.size} selected, tap to deselect" else "")
            },
        verticalArrangement = Arrangement.spacedBy(d.dp(GrooveSpace.ROW)),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(d.dp(GrooveSpace.ZONE))) {
            DisplayTitle("Pattern ${pattern + 1}", debug, Modifier.alignByBaseline())
            if (queued >= 0) {
                // F3.9: the queued pattern blinks until it starts.
                BasicText(
                    "→ ${queued + 1}",
                    Modifier.alignByBaseline().alpha(if (state.blinkOn) 1f else GrooveColors.BLINK_ALPHA),
                    style = d.value(15f, GrooveColors.Shift, FontWeight.Medium),
                    maxLines = 1,
                )
            }
            DisplaySubtitle(subtitle, audioStarted, Modifier.weight(1f).alignByBaseline())
            state.keyScale.displayName?.let { KeyName(it, Modifier.alignByBaseline()) }
            val focus = (state.focusStep ?: 0) + 1
            val (name, what) = when (layer) {
                SeqLayer.Pattern -> LayerText.MAIN to "pattern"
                SeqLayer.Transport -> LayerText.SHIFT to "tempo / key"
                SeqLayer.Step -> LayerText.STEP to (if (selected.size > 1) "${selected.size} steps" else "step $focus")
                SeqLayer.StepShift -> LayerText.SHIFT to (if (selected.size > 1) "${selected.size} steps" else "step $focus")
            }
            LayerLabel(name, what, Modifier.alignByBaseline())
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (drum) {
                // Build-20 lanes: the kit's 16 sounds on the left (left thumb, ADR 0003); build-21: the shared kit grid.
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(d.dp(12))) {
                    KitGrid(state, Modifier.width(d.dp(196)).fillMaxHeight())
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(d.dp(GrooveSpace.PAD))) {
                        Overview(state, Modifier.fillMaxWidth().weight(1f))
                        PatternRow(state, Modifier.fillMaxWidth().height(d.dp(18)))
                    }
                }
            } else {
                // Build-21: a synth slot shows its notes as a pitch roll of the current page.
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(d.dp(GrooveSpace.PAD))) {
                    PatternRow(state, Modifier.fillMaxWidth().height(d.dp(18)))
                    PitchRollView(state, Modifier.fillMaxWidth().weight(1f))
                }
            }
            if (debug.open) debug.panel()
        }
        Footer(state)
    }
}

/**
 * A tap on this element that no child took (the kit grid consumes its touches, the pitch roll,
 * the pattern row and the footer chips their releases) and that did not move past the touch
 * slop (a slot swipe).
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

/**
 * The pattern row (build-22): the slot's 16 patterns. The playing one is cream; patterns with
 * content are lighter with a dot; the one edited while another plays is outlined in cream; a
 * queued one blinks amber; a chain's two patterns carry a green line. Tap = edit (while
 * playing, without switching; tap the edited one again to queue it), Shift + tap = copy the
 * edited pattern here, long-press = every slot switches, hold one + tap another = chain.
 */
@Composable
private fun PatternRow(state: GrooveState, modifier: Modifier) {
    val d = LocalDesign.current
    val slot = state.selectedSlot
    val playing = state.patternOf(slot)
    val edited = state.editPattern
    val queued = state.queuedOf(slot)
    val chain = state.chainOf(slot)
    val content = List(SeqLimits.PATTERNS) { state.patternHasContent(slot, it) }
    val gesture = remember { PatternRowGesture() }
    val current by rememberUpdatedState(state)
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val style = d.value(9f, GrooveColors.Text)
    Canvas(
        modifier
            .semantics {
                contentDescription = "Patterns: ${playing + 1} plays" + (if (edited != playing) ", ${edited + 1} edited" else "") +
                    (if (queued >= 0) ", ${queued + 1} queued" else "") +
                    ", with content: ${content.indices.filter { content[it] }.joinToString(" ") { "${it + 1}" }.ifEmpty { "none" }}" +
                    ". Tap to edit, tap again to switch, Shift + tap to copy here, hold to switch every slot, hold one and tap another to chain"
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val cellW = size.width.toFloat() / SeqLimits.PATTERNS
                        for (change in event.changes) {
                            val id = change.id.value.toInt()
                            when {
                                change.pressed && !change.previousPressed -> {
                                    if (change.isConsumed) continue
                                    val cell = (change.position.x / cellW).toInt().coerceIn(0, SeqLimits.PATTERNS - 1)
                                    gesture.down(id, cell, current.shift.shifted, change.uptimeMillis)?.let(current::patternRow)
                                    change.consume()
                                }
                                !change.pressed && change.previousPressed -> {
                                    gesture.up(id, change.uptimeMillis)?.let(current::patternRow)
                                    change.consume()
                                }
                                change.pressed -> change.consume()
                            }
                        }
                    }
                }
            },
    ) {
        val gap = d.px(3f)
        val w = (size.width - gap * (SeqLimits.PATTERNS - 1)) / SeqLimits.PATTERNS
        val radius = CornerRadius(d.px(GrooveRadius.CELL))
        val ring = d.px(2f)
        for (p in 0 until SeqLimits.PATTERNS) {
            val left = p * (w + gap)
            val bg = when {
                p == playing -> GrooveColors.Select
                content[p] -> SeqColors.CellContent
                else -> SeqColors.CellEmpty
            }
            drawRoundRect(bg, Offset(left, 0f), Size(w, size.height), radius)
            val ink = when {
                p == playing -> GrooveColors.Ground
                content[p] -> GrooveColors.Text
                else -> SeqColors.CellEmptyText
            }
            val m = measurer.measure("${p + 1}", style.copy(color = ink))
            drawText(m, topLeft = Offset(left + (w - m.size.width) / 2f, (size.height - m.size.height) / 2f))
            if (content[p] && p != playing) drawCircle(GrooveColors.Text, d.px(1.6f), Offset(left + w - d.px(4f), d.px(4f)))
            val outline = when {
                p == queued -> if (state.blinkOn) GrooveColors.Shift else null
                p == edited && p != playing -> GrooveColors.Select
                else -> null
            }
            if (outline != null) {
                drawRoundRect(outline, Offset(left + ring / 2, ring / 2), Size(w - ring, size.height - ring), radius, style = Stroke(ring))
            }
            if (chain != null && (p == chain.first || p == chain.then)) {
                drawRect(SeqColors.Playhead, Offset(left + d.px(2f), size.height - d.px(2f)), Size(w - d.px(4f), d.px(2f)))
            }
        }
    }
}

/**
 * Four rows of 16 cells: every step of the pattern (the lane's on the drum slot), the playhead
 * and the page shown. Build-22: a lit cell's brightness is its velocity; cell edges mark the
 * rate's beats.
 */
@Composable
private fun Overview(state: GrooveState, modifier: Modifier) {
    val d = LocalDesign.current
    val slot = state.selectedSlot
    val settings = state.settings()
    val lane = if (state.isDrum(slot)) state.laneNote else null
    val steps = Array(64) { state.step(it) }
    val marks = Array(64) { PadMark.of(steps[it], lane) }
    val velocity = FloatArray(64) { i -> (if (lane != null) steps[i].hit(lane) else steps[i].hits.firstOrNull())?.velocity ?: 0f }
    val selected = state.selectedSteps.toSet()
    val loop = state.stepLoop
    val page = state.page
    val fill = state.fillFrom
    val beat = state.beatSteps
    val cellGap = d.px(4f)
    val radius = CornerRadius(d.px(GrooveRadius.CELL))
    val ring = d.px(1f)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(d.dp(GrooveSpace.PAD))) {
        for (row in 0 until PAGES) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.dp(GrooveSpace.GROUP))) {
                BasicText(
                    "${row + 1}",
                    Modifier.width(d.dp(14)),
                    style = d.value(11f, if (row == page) GrooveColors.Text else SeqColors.RowLabel),
                )
                Canvas(Modifier.weight(1f).fillMaxHeight()) {
                    // Read here, in the draw phase, so the playhead moving only redraws.
                    val playhead = if (state.editingOther) -1 else state.stepOf(slot)
                    val w = (size.width - cellGap * (PADS - 1)) / PADS
                    for (c in 0 until PADS) {
                        val i = row * PADS + c
                        val inside = i < settings.length
                        val lit = GrooveColors.Text.copy(alpha = 0.35f + 0.65f * velocity[i].coerceIn(0f, 1f))
                        val colour = when {
                            i == playhead -> SeqColors.Playhead
                            marks[i] == PadMark.Lane -> if (inside) lit else SeqColors.OnOutside
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
                            if (marks[i] == PadMark.Lane && inside) drawRoundRect(SeqColors.OffInside, topLeft, cell, radius)
                            drawRoundRect(colour, topLeft, cell, radius)
                        }
                        val edge = when {
                            i in selected -> SeqColors.Selected
                            i == fill -> SeqColors.FillArmed
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
                        // Build-22: the beat's first step gets a short tick above (beats follow the rate).
                        if (i % beat == 0 && inside) drawRect(SeqColors.PageRing, topLeft + Offset(0f, -d.px(3f)), Size(w * 0.35f, d.px(1.5f)))
                    }
                }
            }
        }
    }
}

/**
 * Clears the edited pattern's notes (build-22: Steps, Rate, Gate, Shuffle and motion stay).
 * With Shift: on the drum slot only the lane's sound; on a synth slot the whole pattern resets
 * (notes, motion, settings), reading "Reset". No dialog, so it takes two taps: the first arms
 * it (coral, "Clear?"), a second within 2 s clears. Undo brings it back.
 */
@Composable
private fun ClearButton(state: GrooveState, modifier: Modifier) {
    val d = LocalDesign.current
    val armed = state.clearArmed
    val pattern = state.editPattern + 1
    val drum = state.isDrum(state.selectedSlot)
    val mode = if (armed) state.clearArmedMode else when {
        !state.shift.shifted -> GrooveState.ClearMode.Notes
        drum -> GrooveState.ClearMode.Lane
        else -> GrooveState.ClearMode.Reset
    }
    val laneName = state.drum.keyLabels[state.laneNote] ?: "sound"
    Box(
        modifier
            .clip(d.shape(GrooveRadius.CONTROL))
            .background(if (armed) SeqColors.Motion else GrooveColors.Panel)
            .semantics {
                contentDescription = when (mode) {
                    GrooveState.ClearMode.Lane -> if (armed) "Tap again to clear $laneName from pattern $pattern" else "Clear $laneName from pattern $pattern. Tap twice"
                    GrooveState.ClearMode.Reset -> if (armed) "Tap again to reset pattern $pattern" else "Reset pattern $pattern: notes, motion and settings. Tap twice"
                    GrooveState.ClearMode.Notes -> if (armed) "Tap again to clear the notes of pattern $pattern" else "Clear the notes of pattern $pattern, keeping its settings. Tap twice"
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
        val colour = if (armed) GrooveColors.Ground else if (mode != GrooveState.ClearMode.Notes) GrooveColors.Shift else GrooveColors.TextDim
        val weight = if (armed) FontWeight.Bold else FontWeight.Medium
        when (mode) {
            GrooveState.ClearMode.Lane -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Two lines: "Clear" over the sound's short name ("Snare?" when armed).
                BasicText("Clear", style = d.label(11f, colour, weight), maxLines = 1)
                BasicText(if (armed) "$laneName?" else laneName, style = d.label(11f, colour, weight), maxLines = 1, overflow = TextOverflow.Clip)
            }
            GrooveState.ClearMode.Reset -> BasicText(if (armed) "Reset?" else "Reset", style = d.label(GrooveText.BODY, colour, weight), maxLines = 1)
            GrooveState.ClearMode.Notes -> BasicText(if (armed) "Clear?" else "Clear", style = d.label(GrooveText.BODY, colour, weight), maxLines = 1)
        }
    }
}

/** A footer chip that acts on touch-down and takes its touch (never a deselect or a slot swipe). */
@Composable
private fun SeqChip(text: String, description: String, enabled: Boolean = true, active: Boolean = false, onTap: () -> Unit) {
    val tap by rememberUpdatedState(onTap)
    FooterChip(
        text,
        Modifier
            .semantics {
                contentDescription = description
                if (enabled) onClick { tap(); true }
            }
            .pointerInput(enabled) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    if (enabled) tap()
                    waitForUpOrCancellation()?.consume()
                }
            },
        active = active,
        enabled = enabled,
    )
}

/**
 * The Seq footer (build-22): the chips for the left thumb, Undo · ×2 · Dup (Q arrives with the
 * build-23 engine work and stays hidden), with Shift reading ½ and Dup+ and adding Swing (the
 * Shuffle to every slot). At 1/16T a "Steps 12" chip offers whole triplet bars. Then the step
 * selected last (synth: its notes sorted and named), a moment's note of what an edit did, or
 * the pattern's settings.
 */
@Composable
private fun Footer(state: GrooveState) {
    val d = LocalDesign.current
    val slot = state.selectedSlot
    val drum = state.isDrum(slot)
    val focus = state.focusStep
    val shifted = state.shift.shifted
    val style = d.meta()
    Row(horizontalArrangement = Arrangement.spacedBy(d.dp(GrooveSpace.PAD)), verticalAlignment = Alignment.CenterVertically) {
        UndoChip(state)
        SeqChip(
            if (shifted) "½" else "×2",
            if (shifted) "Halve the pattern's steps" else "Double the pattern: steps and content copied",
            enabled = state.canDouble,
        ) { state.seqDouble() }
        SeqChip(
            if (shifted) "Dup+" else "Dup",
            if (shifted) "Copy the pattern into the next empty one, keep playing this one" else "Copy the pattern into the next empty one and switch to it",
        ) { state.seqDup() }
        if (shifted) {
            SeqChip("Swing", "Copy this Shuffle, ${(state.settings().shuffle * 100).roundToInt()}%, to every slot") { state.swingAll() }
        }
        val offer = state.tripletOffer
        if (offer != null && focus == null && !shifted) {
            SeqChip("Steps $offer", "Triplets: set the pattern to $offer steps, whole bars of 1/16T", active = true) { state.acceptTripletOffer() }
        }
        Spacer(Modifier.width(d.dp(GrooveSpace.GROUP)))
        val flash = state.seqFlash
        when {
            state.clearArmed -> {
                val text = when (state.clearArmedMode) {
                    GrooveState.ClearMode.Lane -> "tap Clear again to take ${state.drum.keyLabels[state.laneNote] ?: "the sound"} out of pattern ${state.editPattern + 1} · undoable"
                    GrooveState.ClearMode.Reset -> "tap Reset again to empty pattern ${state.editPattern + 1} and its settings · undoable"
                    GrooveState.ClearMode.Notes -> "tap Clear again to clear the notes of pattern ${state.editPattern + 1} (settings stay) · undoable"
                }
                FooterHint(text, Modifier.weight(1f, fill = false), SeqColors.Motion)
            }
            flash != null -> FooterHint(flash, Modifier.weight(1f, fill = false), GrooveColors.Text)
            state.fillFrom >= 0 -> FooterHint("fill from ${state.fillFrom + 1}: Shift + tap the next pad · same pad cancels", Modifier.weight(1f, fill = false), GrooveColors.Shift)
            focus != null -> {
                val step = state.step(focus)
                val count = state.selectedSteps.size
                val where = if (count > 1) "$count steps · last ${focus + 1}" else "step ${focus + 1}"
                val text = if (drum) {
                    val name = state.drum.keyLabels[state.laneNote] ?: "sound"
                    val others = step.hits.filter { it.note != state.laneNote }
                        .joinToString(", ") { state.drum.keyLabels[it.note] ?: "${it.note}" }
                    val also = if (others.isEmpty()) "" else " · also $others"
                    val hit = step.hit(state.laneNote)
                    if (hit == null) "$where · no $name$also · tap the pad to add it" else "$where · $name ${(hit.velocity * 100).roundToInt()}%$also"
                } else if (!step.on) {
                    "$where · empty · a key or a pad tap writes it"
                } else {
                    val offKey = if (state.stepOutOfKey(focus)) " · out of key" else ""
                    val accent = if (Accent.isOn(step)) " · accent" else ""
                    "$where · ${ChordName.describe(step.notes)}$accent$offKey"
                }
                FooterHint(text, Modifier.weight(1f, fill = false), GrooveColors.TextDim)
                val lane = state.latestLane(focus)
                if (lane != null) BasicText("motion: ${state.targetName(slot, lane.target).lowercase()}", style = d.meta(SeqColors.Motion), maxLines = 1)
            }
            else -> {
                val s = state.settings()
                BasicText(
                    "shuffle ${(s.shuffle * 100).roundToInt()}% · gate ${(s.gate * 100).roundToInt()}% · lanes ${state.laneCount()}/8",
                    Modifier.weight(1f, fill = false),
                    style = style,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.armed) BasicText("rec armed", style = d.meta(SeqColors.Motion), maxLines = 1)
            }
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
            .height(d.dp(FOOTER_CHIP_HEIGHT))
            .width(d.dp(62))
            .alpha(if (enabled) 1f else GrooveColors.INERT_ALPHA)
            .clip(d.shape(GrooveRadius.CHIP))
            .background(if (readsRedo) GrooveColors.Text else GrooveColors.ChipFill)
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
                        // A release right at the long-press time can still be the redo.
                        released -> when (hold.up(upAt)) {
                            UndoHold.Action.Undo -> state.seqUndoPress(redo = false)
                            UndoHold.Action.Redo -> {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                state.seqUndoPress(redo = true)
                                readsRedo = true
                                flash++
                            }
                            null -> Unit
                        }
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
        BasicText(label, style = d.label(GrooveText.BODY, if (readsRedo) GrooveColors.Ground else GrooveColors.Text, FontWeight.Bold), maxLines = 1)
    }
}

/**
 * The bottom zone of the Sequencer screen: 16 step pads for the current page (F3.5, F3.8,
 * build-20). Build-22: on a synth slot the pen keys are always under the pads (pads ~45%); on
 * the drum slot, with Record armed, the drum pad blocks are, so a beat records from Seq.
 */
@Composable
fun SeqBottom(state: GrooveState, @Suppress("UNUSED_PARAMETER") keyboardLayout: KeyboardLayout, modifier: Modifier = Modifier) {
    val d = LocalDesign.current
    val drum = state.isDrum(state.selectedSlot)
    val lower = !drum || state.armed
    Column(modifier, verticalArrangement = Arrangement.spacedBy(d.dp(GrooveSpace.PAD))) {
        StepPadRow(state, Modifier.fillMaxWidth().weight(if (lower) 0.45f else 1f))
        if (!drum) {
            SeqPenKeys(state, Modifier.fillMaxWidth().weight(0.55f))
        } else if (state.armed) {
            DrumPads(state, Modifier.fillMaxWidth().weight(0.55f))
        }
    }
}

/** A pointer on the pads: where it went down and whether it has moved past the slop. */
private class PadTouch(val start: Offset, val deadline: Long) {
    var drifted = false
    var fired = false
}

/**
 * The 16 pads, with one touch handler for all of them (build-22) so a finger can be followed
 * across pads: a tap toggles, a long-press selects, a drag drops onto another pad (move;
 * Shift + hold first = copy), a Shift swipe rotates or inserts / deletes ([StepPads]).
 */
@Composable
private fun StepPadRow(state: GrooveState, modifier: Modifier) {
    val d = LocalDesign.current
    val gap = d.px(5f)
    val current by rememberUpdatedState(state)
    Row(
        modifier.pointerInput(Unit) {
            val slop = viewConfiguration.touchSlop
            val longPress = viewConfiguration.longPressTimeoutMillis
            fun padAt(p: Offset): Int? {
                val h = size.height.toFloat()
                if (p.x < 0f || p.x >= size.width || p.y < -h / 2 || p.y > h * 1.5f) return null
                val w = (size.width - gap * (PADS - 1)) / PADS
                return (p.x / (w + gap)).toInt().coerceIn(0, PADS - 1)
            }
            val touches = HashMap<PointerId, PadTouch>()
            var now = 0L
            try {
                awaitPointerEventScope {
                    while (true) {
                        val next = touches.values.filter { !it.fired && !it.drifted }.minOfOrNull { it.deadline }
                        val event = if (next == null) awaitPointerEvent() else {
                            val wait = next - now
                            if (wait > 0) withTimeoutOrNull(wait) { awaitPointerEvent() } else null
                        }
                        if (event == null) {
                            now = next ?: now
                            for ((id, t) in touches) {
                                if (!t.fired && !t.drifted && t.deadline <= now) {
                                    t.fired = true
                                    current.padLongPress(id.value.toInt())
                                }
                            }
                            continue
                        }
                        for (change in event.changes) {
                            now = maxOf(now, change.uptimeMillis)
                            val pointer = change.id.value.toInt()
                            when {
                                change.pressed && !change.previousPressed -> {
                                    val pad = padAt(change.position) ?: continue
                                    touches[change.id] = PadTouch(change.position, change.uptimeMillis + longPress)
                                    current.padDown(pointer, pad)
                                    change.consume()
                                }
                                change.pressed -> {
                                    val t = touches[change.id] ?: continue
                                    if (!t.drifted && (change.position - t.start).getDistance() > slop) t.drifted = true
                                    current.padMove(pointer, padAt(change.position), t.drifted)
                                    change.consume()
                                }
                                change.previousPressed -> {
                                    if (touches.remove(change.id) == null) continue
                                    current.padUp(pointer)
                                    change.consume()
                                }
                            }
                        }
                    }
                }
            } finally {
                if (touches.isNotEmpty()) current.padAbandon()
            }
        },
        horizontalArrangement = Arrangement.spacedBy(d.dp(5)),
    ) {
        val tails = holdTails(state)
        for (pad in 0 until PADS) StepPad(state, pad, pad in tails, Modifier.weight(1f).fillMaxHeight())
    }
}

/** Pads of the page shown that a synth note held from an earlier step rings over (the tie, build-22). */
private fun holdTails(state: GrooveState): Set<Int> {
    if (state.isDrum(state.selectedSlot)) return emptySet()
    val settings = state.settings()
    val start = state.page * PADS
    val out = HashSet<Int>()
    for (i in maxOf(0, start - 16) until minOf(start + PADS, settings.length)) {
        val s = state.step(i)
        if (!s.on) continue
        val held = s.hits.maxOf { PitchRoll.lengthOf(it, settings.gate) + it.nudge }
        var j = i + 1
        while (j < i + held && j < settings.length) {
            if (j >= start && j < start + PADS && !state.step(j).on) out += j - start
            j++
        }
    }
    return out
}

/**
 * One step pad (build-22, docs/mockups/SeqDrumProposed and SeqSynthProposed): the hit's
 * velocity is the cream fill's height; a synth pad names its note or chord and shows a tie
 * where an earlier note still rings; a drum pad shows a tick per other sound and a blue nudge
 * tick (early left, late right). Edges: cream = selected, amber = fill armed, blue = loop,
 * green = the playhead; brighter on the rate's beats.
 */
@Composable
private fun StepPad(state: GrooveState, pad: Int, tail: Boolean, modifier: Modifier) {
    val d = LocalDesign.current
    val index = state.page * PADS + pad
    val step = state.step(index)
    val settings = state.settings()
    val drum = state.isDrum(state.selectedSlot)
    val lane = if (drum) state.laneNote else null
    val mark = PadMark.of(step, lane)
    val hit = if (lane != null) step.hit(lane) else step.hits.firstOrNull()
    val isPlay = state.stepOf(state.selectedSlot) == index && !state.editingOther
    val isSelected = index in state.selectedSteps
    val loop = state.stepLoop
    val inLoop = loop != null && index in loop.first..loop.second
    val inside = index < settings.length
    val motion = state.hasMotion(index)
    val offKey = state.stepOutOfKey(index)
    val lit = mark == PadMark.Lane
    val velocity = if (lit) (lane?.let { step.hit(it)?.velocity } ?: step.hits.maxOf { it.velocity }) else 0f
    val accent = !drum && Accent.isOn(step)
    val fillArmed = state.fillFrom == index
    val beat = index % state.beatSteps == 0
    val (edgeWidth, edgeColour) = when {
        isSelected -> 3f to SeqColors.Selected
        fillArmed -> 3f to SeqColors.FillArmed
        isPlay -> 2f to SeqColors.Playhead
        inLoop -> 2f to SeqColors.Loop
        beat -> 1f to SeqColors.PadEdgeBeat
        else -> 1f to SeqColors.PadEdge
    }
    val shape = d.shape(GrooveRadius.PAD)
    val laneName = lane?.let { state.drum.keyLabels[it] ?: "sound" }
    // Build-21: a lit synth pad names its note, chord or lowest note and count.
    val noteLabel = if (lane == null && step.on) NoteNames.padLabel(step.notes) else null
    val others = if (lane != null) step.hits.count { it.note != lane } else 0
    val numberOnFill = velocity >= 0.82f
    val labelOnFill = velocity >= 0.3f

    Box(
        modifier
            .alpha(if (inside) 1f else 0.35f)
            .clip(shape)
            .background(GrooveColors.Panel)
            .semantics {
                val what = when {
                    laneName == null -> if (step.on) "on, ${ChordName.describe(step.notes)}" else "off"
                    lit -> "$laneName on"
                    else -> "$laneName off"
                }
                contentDescription = "Step ${index + 1}, $what" +
                    (if (lit) ", velocity ${(velocity * 100).roundToInt()}%" else "") +
                    (if (accent) ", accent" else "") +
                    (if (others > 0) ", $others other sound${if (others > 1) "s" else ""}" else "") +
                    (if (hit != null && hit.probability < 1f) ", chance ${(hit.probability * 100).roundToInt()}%" else "") +
                    (if (hit != null && hit.nudge != 0f) ", nudge ${SeqStrips.formatNudge(hit.nudge)}" else "") +
                    (if (offKey) ", out of key" else "") +
                    (if (isSelected) ", selected" else "") +
                    (if (fillArmed) ", fill starts here" else "") +
                    (if (!inside) ", past the pattern's end" else "") +
                    ". Tap to toggle, hold to select, drag onto another pad to move"
                selected = isSelected
            },
    ) {
        // Velocity as fill height.
        if (lit) {
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(velocity.coerceIn(0.06f, 1f))
                    .background(if (isPlay) SeqColors.Playhead else GrooveColors.Text),
            )
        }
        if (tail) {
            Box(
                Modifier.align(Alignment.CenterStart).fillMaxWidth(0.7f).height(d.dp(4)).clip(d.shape(GrooveRadius.HAIRLINE))
                    .background(SeqColors.Tie),
            )
        }
        Column(Modifier.fillMaxSize().padding(d.dp(7)), verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    "${index + 1}",
                    style = d.value(12f, if (numberOnFill) GrooveColors.Ground else if (isPlay) SeqColors.Playhead else SeqColors.PadNumber, FontWeight.Medium),
                )
                if (accent) {
                    Spacer(Modifier.width(d.dp(3)))
                    BasicText(">", style = d.value(11f, if (numberOnFill) GrooveColors.Ground else GrooveColors.Text, FontWeight.Bold))
                }
                Spacer(Modifier.weight(1f))
                // Build-22: the lane hit's nudge, a short blue tick early (left) or late (right).
                if (drum && hit != null && abs(hit.nudge) >= 0.01f) {
                    Box(
                        Modifier.offset(x = d.dp(if (hit.nudge < 0) -4f else 4f)).width(d.dp(8)).height(d.dp(3))
                            .clip(d.shape(GrooveRadius.HAIRLINE)).background(SeqColors.Nudge),
                    )
                    Spacer(Modifier.width(d.dp(4)))
                }
                // F8.6: a step playing a note outside the key: a small ring (it only marks steps that are on).
                if (offKey) {
                    Box(Modifier.size(d.dp(10)).border(d.dp(2f), if (numberOnFill) GrooveColors.Ground else GrooveColors.Shift, CircleShape))
                    if (motion) Spacer(Modifier.width(d.dp(3)))
                }
                if (motion) Box(Modifier.size(d.dp(6)).clip(CircleShape).background(SeqColors.Motion))
            }
            // Build-22: one tick per other sound on the step (drum slot), under the number.
            if (others > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(d.dp(3))) {
                    repeat(minOf(others, 3)) {
                        Box(
                            Modifier.width(d.dp(9)).height(d.dp(3)).clip(d.shape(GrooveRadius.HAIRLINE))
                                .background(if (numberOnFill) GrooveColors.Ground.copy(alpha = 0.6f) else SeqColors.Tick),
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            if (noteLabel != null) {
                BasicText(
                    noteLabel,
                    style = d.padLabel(if (labelOnFill) GrooveColors.Ground else GrooveColors.Text),
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
            // Chance below 100%: a blue bar of that width.
            if (hit != null && hit.probability < 1f) {
                Box(
                    Modifier
                        .padding(top = d.dp(3))
                        .fillMaxWidth(hit.probability.coerceIn(0.02f, 1f))
                        .height(d.dp(4))
                        .clip(d.shape(GrooveRadius.HAIRLINE))
                        .background(GrooveColors.Strips[3]),
                )
            }
        }
        val edge = d.dp(edgeWidth)
        Box(Modifier.matchParentSizeCompat().border(edge, edgeColour, shape))
    }
}

/** The pad's outline over its fill. */
private fun Modifier.matchParentSizeCompat(): Modifier = this.fillMaxSize()

/** Notes of the two-octave keyboard above the kit's 16 keys: they play nothing on the drum slot. */
val DRUMLESS_NOTES: Set<Int> = (64..71).toSet()
