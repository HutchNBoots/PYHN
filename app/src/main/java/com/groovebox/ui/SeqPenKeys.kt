package com.groovebox.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import com.groovebox.ui.model.KeyTouchTracker
import com.groovebox.ui.model.KeyboardLayout
import com.groovebox.ui.model.NoteNames
import com.groovebox.ui.theme.GrooveColors
import com.groovebox.ui.theme.GrooveRadius
import com.groovebox.ui.theme.GrooveSpace
import com.groovebox.ui.theme.LocalDesign

/**
 * The Seq screen's pen keys (build-22, docs/mockups/SeqSynthProposed.dc.html, capsule keys kept
 * by the owner's decision): always under the pads of a synth slot. Left, the pen readout
 * ("pen: A2", "pen: Am") over ‹ › that move the keys an octave; right, two octaves of capsule
 * keys, sharps above naturals, labelled by the octave shown. A key plays and sets the pen (keys
 * held together make a chord); with steps selected it edits their chord instead (the first key
 * replaces, later keys toggle, see [GrooveState.keyDown]) and is only auditioned, never
 * recorded. The keys of the step selected last are lit (cream), held keys amber, the pen's
 * notes outlined. With a scale on, out-of-key keys are dimmed and play nothing.
 */
@Composable
fun SeqPenKeys(state: GrooveState, modifier: Modifier = Modifier) {
    val d = LocalDesign.current
    val slot = state.selectedSlot
    val low = state.penLowOf(slot)
    val pen = state.penNotes(slot)
    val penText = "pen: " + NoteNames.padLabel(pen)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(d.dp(GrooveSpace.ROW))) {
        Column(Modifier.width(d.dp(84)).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(d.dp(GrooveSpace.GROUP))) {
            Box(
                Modifier.fillMaxWidth().height(d.dp(22)).clip(d.shape(GrooveRadius.CHIP)).background(GrooveColors.Panel)
                    .semantics { contentDescription = "Pen ${pen.joinToString(" ") { NoteNames.name(it) }}: a pad tap writes it" },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(penText, style = d.caption(GrooveColors.Text, FontWeight.Medium), maxLines = 1)
            }
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(d.dp(GrooveSpace.GROUP))) {
                OctaveButton("‹", "Pen keys an octave down, now ${NoteNames.name(low)}", Modifier.weight(1f).fillMaxHeight()) { state.penOctave(-1) }
                OctaveButton("›", "Pen keys an octave up, now ${NoteNames.name(low)}", Modifier.weight(1f).fillMaxHeight()) { state.penOctave(1) }
            }
        }
        PenKeyboard(state, low, pen.toSet(), Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun OctaveButton(text: String, description: String, modifier: Modifier, onTap: () -> Unit) {
    val d = LocalDesign.current
    val tap by rememberUpdatedState(onTap)
    Box(
        modifier
            .clip(d.shape(GrooveRadius.CONTROL))
            .background(GrooveColors.ChipFill)
            .semantics {
                contentDescription = description
                onClick { tap(); true }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    tap()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = d.buttonLabel(GrooveColors.Text, FontWeight.Bold), maxLines = 1)
    }
}

/** Two octaves of capsule keys from [low] (a C); Compose pointer input, multi-touch. */
@Composable
private fun PenKeyboard(state: GrooveState, low: Int, pen: Set<Int>, modifier: Modifier) {
    val d = LocalDesign.current
    val layout = remember(low) { KeyboardLayout(lowestNote = Math.floorDiv(low, 12) * 12, octaves = 2) }
    val pressed = remember { mutableStateMapOf<Int, Unit>() }
    val listener by rememberUpdatedState<KeyListener>(state)
    val playable by rememberUpdatedState(state::keyPlayable)
    val tracker = remember {
        KeyTouchTracker(
            onNoteOn = { note, velocity, tapTimeNs ->
                listener.keyDown(note, velocity, tapTimeNs)
                pressed[note] = Unit
            },
            onNoteOff = { note ->
                listener.keyUp(note)
                pressed.remove(note)
            },
            playable = { note -> playable(note) },
        )
    }
    DisposableEffect(tracker) { onDispose { tracker.cancel() } }
    // A new octave: let go of the keys held on the old one.
    DisposableEffect(layout) { onDispose { tracker.cancel() } }
    val focus = state.focusStep
    val lit = if (focus != null) state.step(focus).notes.toSet() else emptySet()
    val keyScale = state.keyScale
    val measurer = rememberTextMeasurer()
    val labelStyle = d.caption()
    val litLabel = d.caption(GrooveColors.Ground, FontWeight.Medium)
    val description = "Pen keys, ${NoteNames.name(layout.lowestNote)} to ${NoteNames.name(layout.lowestNote + 23)}" +
        (if (lit.isNotEmpty()) ", lit: ${lit.sorted().joinToString(" ") { NoteNames.name(it) }}" else "")
    Canvas(
        modifier
            .semantics { contentDescription = description }
            .pointerInput(layout) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        for (change in event.changes) {
                            val id = change.id.value.toInt()
                            val hit = layout.hit(change.position.x, change.position.y, w, h)
                            when {
                                change.pressed && !change.previousPressed -> tracker.down(id, hit?.note, hit?.velocity ?: 0f, 0L)
                                change.pressed -> tracker.move(id, hit?.note, hit?.velocity ?: 0f)
                                change.previousPressed -> tracker.up(id)
                            }
                            change.consume()
                        }
                    }
                }
            },
    ) {
        val tray = d.px(GrooveRadius.PANEL)
        drawRoundRect(GrooveColors.KeyTray, cornerRadius = CornerRadius(tray))
        val inset = d.px(6f)
        val inner = Rect(inset, inset, size.width - inset, size.height - inset)
        val whiteCount = layout.keys.count { !it.isBlack }
        val padW = inner.width / whiteCount * 0.56f
        for (key in layout.keys) {
            val (top, bottom) = if (key.isBlack) 0.03f to 0.47f else 0.52f to 0.97f
            val cx = inner.left + (key.left + key.right) / 2f * inner.width
            val r = Rect(cx - padW / 2f, inner.top + top * inner.height, cx + padW / 2f, inner.top + bottom * inner.height)
            val radius = CornerRadius(minOf(r.width, r.height) / 2f)
            val held = key.note in pressed
            val isLit = key.note in lit
            val dim = !keyScale.playable(key.note, false) && !held
            val body = when {
                held -> GrooveColors.KeyLit
                isLit -> GrooveColors.Select
                else -> GrooveColors.KeyPad
            }
            drawRoundRect(if (dim) lerp(body, GrooveColors.KeyTray, 0.5f) else body, r.topLeft, r.size, radius)
            if (!held && !isLit) {
                drawRoundRect(
                    Brush.verticalGradient(0f to Color.White.copy(alpha = 0.10f), 1f to Color.Black.copy(alpha = 0.25f), startY = r.top, endY = r.bottom),
                    r.topLeft, r.size, radius,
                )
            }
            if (key.note in pen && !isLit) {
                val ring = d.px(2f)
                drawRoundRect(GrooveColors.Select, r.topLeft + Offset(ring / 2, ring / 2), Size(r.width - ring, r.height - ring), radius, style = Stroke(ring))
            }
            if (keyScale.isOn && keyScale.isRoot(key.note) && !held && !isLit) {
                val sw = r.width * 0.16f
                drawRoundRect(GrooveColors.KeyRootStripe, Offset(r.center.x - sw / 2, r.top + r.width * 0.4f), Size(sw, r.height - r.width * 0.8f), CornerRadius(sw / 2))
            }
            // Labels follow the octave: every C, the pen's and the lit notes.
            if (!key.isBlack && (key.note % 12 == 0 || key.note in pen || isLit)) {
                val m = measurer.measure(NoteNames.name(key.note), if (isLit || held) litLabel else labelStyle)
                drawText(m, topLeft = Offset(r.center.x - m.size.width / 2f, r.bottom - m.size.height - d.px(4f)))
            }
        }
    }
}
