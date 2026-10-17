package com.groovebox.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import com.groovebox.ui.model.NoteNames
import com.groovebox.ui.model.PitchRoll
import com.groovebox.ui.model.RollBar
import com.groovebox.ui.model.StepGrid
import com.groovebox.ui.theme.GrooveColors
import com.groovebox.ui.theme.LocalDesign

/** Colours of the pitch roll (docs/mockups/SeqSynthProposed.dc.html). */
private object RollColors {
    val Ground = Color(0xFF18181B)
    val InKey = Color(0xFF222227)
    val RowLine = GrooveColors.Ground
    val Beat = Color(0xFF3A3A40)
    val Playhead = GrooveColors.Strips[2]
    val Selected = GrooveColors.Text
    val PastEnd = GrooveColors.Ground.copy(alpha = 0.55f)
    val Outside = Color(0xFF5C5A55)
    val Label = GrooveColors.TextDim
    val RootLabel = GrooveColors.Text
    val PageEmpty = Color(0xFF2C2C32)
    val PageNotes = Color(0xFF5C5A55)
    val PageShown = GrooveColors.Text
}

/** Width of the row labels left of the roll, in design units. */
private const val LABEL_UNITS = 26f

/** A tap within this many design units of a note's row still picks it (about a thumb's half-width). */
private const val TAP_UNITS = 14f

/**
 * The Seq display for a synth slot (build-21 item 2): a thin strip of the four pages (which
 * hold notes, which is shown, where the playhead is) over a pitch roll of the current page.
 * Rows fit the pattern's notes ([PitchRoll.rows]); C and the key's root are labelled, and with
 * a scale on the out-of-key rows are darker. A note is a bar as long as it sounds, brighter
 * for a higher velocity; chords stack. The playhead's column is green, selected steps' columns
 * are outlined in cream, and steps past the pattern's end are darkened.
 *
 * A tap on a note selects (or deselects) its step, like a long-press on its pad; a tap on no
 * note deselects every step. Both consume the release, so the display's own tap does not
 * also fire; a horizontal swipe is left to the display (slots).
 */
@Composable
fun PitchRollView(state: GrooveState, modifier: Modifier = Modifier) {
    val d = LocalDesign.current
    val slot = state.selectedSlot
    val settings = state.settings()
    val page = state.page
    val step = state::step
    val rows = PitchRoll.rows(state.notesInPattern())
    val bars = PitchRoll.bars(step, page, settings)
    val pages = PitchRoll.pagesWithNotes(step)
    val selected = state.selectedSteps.toSet()
    val key = state.keyScale
    val measurer = rememberTextMeasurer()
    val labelStyle = d.value(9f, RollColors.Label)
    val rootStyle = d.value(9f, RollColors.RootLabel)
    val heads = bars.filter { it.head && !it.outside }.distinctBy { it.step }.size
    Column(
        modifier.semantics {
            contentDescription = "Pitch roll of page ${page + 1}, ${NoteNames.name(rows.first)} to ${NoteNames.name(rows.last)}, " +
                "$heads step${if (heads == 1) "" else "s"} with notes. Tap a note to select its step"
        },
        verticalArrangement = Arrangement.spacedBy(d.dp(6)),
    ) {
        PageStrip(state, pages, settings.length, Modifier.fillMaxWidth().height(d.dp(6)))
        Canvas(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .rollTap(bars, rows, d.px(LABEL_UNITS), d.px(TAP_UNITS)) { state.rollTap(it) },
        ) {
            val gutter = d.px(LABEL_UNITS)
            val w = size.width - gutter
            val rowCount = rows.last - rows.first + 1
            val rowH = size.height / rowCount
            val colW = w / PitchRoll.COLUMNS
            val radius = CornerRadius(d.px(4f))
            val one = d.px(1f)
            fun rowTop(note: Int) = (rows.last - note) * rowH

            drawRoundRect(RollColors.Ground, Offset(gutter, 0f), Size(w, size.height), radius)
            var lastLabel = Float.MAX_VALUE
            for (note in rows.last downTo rows.first) {
                val top = rowTop(note)
                if (!key.isOn || key.inKey(note)) drawRect(RollColors.InKey, Offset(gutter, top), Size(w, rowH))
                if (rowH > d.px(6f)) drawRect(RollColors.RowLine, Offset(gutter, top + rowH - one), Size(w, one))
                val isRoot = key.isOn && key.isRoot(note)
                if (Math.floorMod(note, 12) == 0 || isRoot) {
                    val text = measurer.measure(NoteNames.name(note), if (isRoot) rootStyle else labelStyle)
                    val y = top + (rowH - text.size.height) / 2
                    // Rows thinner than a label: skip one that would overlap the one above.
                    if (y >= lastLabel + text.size.height || lastLabel == Float.MAX_VALUE) {
                        drawText(text, topLeft = Offset(gutter - text.size.width - d.px(4f), y))
                        lastLabel = y
                    }
                }
            }
            // Build-22: beat lines follow the rate (every 3 steps at 1/16T, 2 at 1/8).
            val beatEvery = StepGrid.beatSteps(settings.rate)
            for (beat in beatEvery until PitchRoll.COLUMNS step beatEvery) drawRect(RollColors.Beat, Offset(gutter + beat * colW, 0f), Size(one, size.height))
            // Steps past the pattern's end.
            val endCol = (settings.length - page * PitchRoll.COLUMNS).coerceIn(0, PitchRoll.COLUMNS)
            if (endCol < PitchRoll.COLUMNS) {
                drawRect(RollColors.PastEnd, Offset(gutter + endCol * colW, 0f), Size((PitchRoll.COLUMNS - endCol) * colW, size.height))
            }
            // Read here, in the draw phase, so the playhead moving only redraws.
            // Build-22: no playhead over a pattern edited while another plays.
            val playCol = if (state.editingOther) -1 else state.stepOf(slot) - page * PitchRoll.COLUMNS
            if (playCol in 0 until PitchRoll.COLUMNS) {
                val x = gutter + playCol * colW
                drawRect(RollColors.Playhead.copy(alpha = 0.16f), Offset(x, 0f), Size(colW, size.height))
                drawRect(RollColors.Playhead, Offset(x, 0f), Size(colW, d.px(3f)))
            }
            val inset = d.px(3f)
            val minBar = d.px(5f)
            for (bar in bars) {
                val left = gutter + bar.start * colW + if (bar.head) inset else 0f
                val right = gutter + (bar.start + bar.length) * colW - if (bar.runsOff) 0f else inset
                val colour = if (bar.outside) RollColors.Outside else GrooveColors.Text.copy(alpha = 0.4f + 0.6f * bar.velocity.coerceIn(0f, 1f))
                // Rows can be thin (a wide pattern): a bar keeps a readable height, centred on its row.
                val h = maxOf(rowH - one, minBar)
                drawRoundRect(
                    colour,
                    Offset(left, rowTop(bar.note) + (rowH - h) / 2),
                    Size(maxOf(right - left, d.px(4f)), h),
                    CornerRadius(minOf(d.px(4f), h / 2)),
                )
            }
            val ring = d.px(2f)
            for (s in selected) {
                val c = s - page * PitchRoll.COLUMNS
                if (c !in 0 until PitchRoll.COLUMNS) continue
                drawRoundRect(
                    RollColors.Selected,
                    Offset(gutter + c * colW + ring / 2 + one, ring / 2),
                    Size(colW - ring - 2 * one, size.height - ring),
                    radius,
                    style = Stroke(ring),
                )
            }
        }
    }
}

/** The four pages, thin: notes on a page lighten it, the shown page is cream, the playhead a green tick. */
@Composable
private fun PageStrip(state: GrooveState, pages: List<Boolean>, length: Int, modifier: Modifier) {
    val d = LocalDesign.current
    val page = state.page
    val slot = state.selectedSlot
    Canvas(modifier) {
        val gap = d.px(4f)
        val gutter = d.px(LABEL_UNITS)
        val w = (size.width - gutter - gap * (PitchRoll.PAGES - 1)) / PitchRoll.PAGES
        val radius = CornerRadius(size.height / 2)
        for (p in 0 until PitchRoll.PAGES) {
            val colour = when {
                p == page -> RollColors.PageShown
                pages[p] -> RollColors.PageNotes
                else -> RollColors.PageEmpty
            }
            val played = p * PitchRoll.COLUMNS < length
            drawRoundRect(colour.copy(alpha = if (played) 1f else 0.35f), Offset(gutter + p * (w + gap), 0f), Size(w, size.height), radius)
        }
        val play = state.stepOf(slot)
        if (play >= 0) {
            val p = play / PitchRoll.COLUMNS
            val x = gutter + p * (w + gap) + (play % PitchRoll.COLUMNS + 0.5f) * w / PitchRoll.COLUMNS
            val tick = d.px(3f)
            drawRoundRect(RollColors.Playhead, Offset(x - tick / 2, 0f), Size(tick, size.height), CornerRadius(tick / 2))
        }
    }
}

/**
 * Taps on the roll: a release within the touch slop of its touch-down is looked up in [bars]
 * (null when it hits no note) and consumed. A touch that moves further is left alone (a slot
 * swipe on the display). [gutter] is the label column's width, [reach] how far from a row a
 * tap still counts, in px.
 */
private fun Modifier.rollTap(bars: List<RollBar>, rows: IntRange, gutter: Float, reach: Float, onTap: (Int?) -> Unit): Modifier =
    pointerInput(bars, rows, gutter, reach) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.isConsumed) return@awaitEachGesture
            val slop = viewConfiguration.touchSlop
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if (change.isConsumed || (change.position - down.position).getDistance() > slop) return@awaitEachGesture
                if (!change.pressed) {
                    change.consume()
                    val rowH = size.height.toFloat() / (rows.last - rows.first + 1)
                    val colW = (size.width - gutter) / PitchRoll.COLUMNS
                    val column = (change.position.x - gutter) / colW
                    val pitch = rows.last - (change.position.y / rowH - 0.5f)
                    onTap(PitchRoll.stepAt(bars, column, pitch, maxRows = maxOf(1f, reach / rowH)))
                    return@awaitEachGesture
                }
            }
        }
    }
