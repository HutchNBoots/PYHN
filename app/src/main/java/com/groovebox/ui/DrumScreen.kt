package com.groovebox.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import com.groovebox.engine.ParamSpec
import com.groovebox.ui.model.DrumTapCells
import com.groovebox.ui.theme.GrooveRadius
import com.groovebox.ui.theme.GrooveSpace
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.roundToInt
import com.groovebox.engine.NativeEngine
import com.groovebox.ui.model.DrumLayout
import com.groovebox.ui.model.ParamMapping
import com.groovebox.ui.theme.GrooveColors
import com.groovebox.ui.theme.LocalDesign

/**
 * The Drum screen's middle row (F2.2): the kit display and the four strips for the selected
 * sound. Build-22: a swipe on the display (finger left) goes to synth slot 2 ([onSlotSwipe],
 * review R11); on the Shift layer Rev and Choke are tap cells ([DrumTapCells]).
 */
@Composable
fun DrumMiddleRow(
    state: GrooveState,
    audioStarted: Boolean,
    debug: DebugSlot,
    modifier: Modifier = Modifier,
    onSlotSwipe: ((Int) -> Unit)? = null,
) {
    val d = LocalDesign.current
    val drum = state.drum
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(d.dp(10))) {
        DrumDisplay(
            state, audioStarted, debug,
            Modifier.weight(1f).fillMaxHeight().then(if (onSlotSwipe != null) Modifier.slotSwipe(null, onSlotSwipe) else Modifier),
        )
        Row(Modifier.width(d.dp(332)).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(d.dp(8))) {
            drum.strips.forEachIndexed { i, spec ->
                val cell = Modifier.weight(1f).fillMaxHeight()
                if (spec != null && DrumTapCells.isCell(spec.key)) {
                    TapCells(i, spec, drum, cell)
                } else {
                    ColourStrip(i, spec, drum, cell)
                }
            }
        }
    }
}

/**
 * Rev or Choke as tap cells (build-22): the strip's label, then one cell per option, the
 * current one filled in the strip colour; a tap chooses (one undo step; Rev replays the sound).
 */
@Composable
private fun TapCells(position: Int, spec: ParamSpec, drum: DrumController, modifier: Modifier) {
    val d = LocalDesign.current
    val colour = GrooveColors.Strips[position]
    val options = DrumTapCells.options(spec.key)
    val current = drum.value(spec).roundToInt()
    val locked = drum.isLocked(spec)
    Column(
        modifier
            .clip(d.shape(GrooveRadius.STRIP))
            .background(GrooveColors.Panel)
            .padding(horizontal = d.dp(6), vertical = d.dp(10)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(d.dp(6)),
    ) {
        BasicText(spec.label, style = d.label(14f, colour, FontWeight.Bold), maxLines = 1)
        BasicText(
            if (locked) "locked" else "tap",
            style = d.meta(GrooveColors.TextDim),
            maxLines = 1,
        )
        // Top to bottom: the last option first, so "On" / "B" sit up and "Off" / "None" down, like a strip.
        for (i in options.indices.reversed()) {
            val isOn = i == current
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(d.shape(GrooveRadius.CHIP))
                    .background(if (isOn) colour.copy(alpha = 0.85f) else CellOff)
                    .semantics {
                        contentDescription = "${spec.label} ${options[i]}" + (if (isOn) ", on" else ", tap to choose") + (if (locked) ", locked" else "")
                        selected = isOn
                    }
                    .pointerInput(spec, i) { detectTapGestures(onTap = { drum.tapCell(spec, i) }) },
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    options[i],
                    style = d.value(13f, if (isOn) GrooveColors.Ground else GrooveColors.Text, if (isOn) FontWeight.Medium else FontWeight.Normal),
                    maxLines = 1,
                )
            }
        }
    }
}

private val CellOff = Color(0xFF26262B)

/**
 * The kit display (build-21, docs/mockups/DrumProposed.dc.html): "Kit", the selected sound and
 * the layer; the kit grid on the left (the same as on Seq: touch a sound to select and hear it);
 * on the right a picture of the sound (its ring inside its decay/level envelope, reversed and
 * trimmed as set) over a "this beat" strip of its 16 steps on the current page.
 */
@Composable
fun DrumDisplay(state: GrooveState, audioStarted: Boolean, debug: DebugSlot, modifier: Modifier = Modifier) {
    val d = LocalDesign.current
    val drum = state.drum
    val key = drum.selectedKey
    val name = drum.soundName(key)
    val shifted = drum.shifted

    Column(
        modifier
            .clip(RoundedCornerShape(d.dp(16)))
            .background(GrooveColors.Panel)
            .padding(horizontal = d.dp(GrooveSpace.DISPLAY_H), vertical = d.dp(GrooveSpace.DISPLAY_V))
            .semantics { contentDescription = "Drum display, $name" },
        verticalArrangement = Arrangement.spacedBy(d.dp(8)),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(d.dp(10))) {
            DisplayTitle("Kit", debug, Modifier.alignByBaseline())
            DisplaySubtitle("slot 1 · ${DrumLayout.shortName(key)} ($name)", audioStarted, Modifier.weight(1f).alignByBaseline())
            LayerLabel(
                if (shifted) "SHIFT · sound" else "MAIN · sound",
                shifted,
                Modifier.alignByBaseline(),
            )
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(d.dp(14))) {
            KitGrid(state, Modifier.width(d.dp(212)).fillMaxHeight())
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(d.dp(6))) {
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    DrumPicture(drum, Modifier.fillMaxSize())
                    if (debug.open) debug.panel()
                }
                ThisBeat(state, Modifier.fillMaxWidth())
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(d.dp(10)), verticalAlignment = Alignment.CenterVertically) {
            SoundUndoChip(state)
            Box(Modifier.weight(1f)) { Legend(drum.strips, drum) }
        }
    }
}

/**
 * One row of the selected sound's 16 steps on the current page of the kit's current pattern:
 * a hit is lit by its velocity, the playhead's step is ringed green, steps past the pattern's
 * end are faint. Read only (the pads and the Seq screen edit steps).
 */
@Composable
private fun ThisBeat(state: GrooveState, modifier: Modifier) {
    val d = LocalDesign.current
    state.revision // recompose on pattern edits
    val slot = NativeEngine.DRUM_SLOT
    val pattern = state.patternOf(slot)
    val note = state.laneNote
    val first = state.page * PAGE_STEPS
    val length = state.store.settings(slot, pattern).length
    val velocities = FloatArray(PAGE_STEPS) { c ->
        state.store.step(slot, pattern, first + c).hits.firstOrNull { it.note == note }?.velocity ?: 0f
    }
    val label = d.value(10f, GrooveColors.TextDim)
    Row(
        modifier.semantics {
            contentDescription = "This beat: ${velocities.count { it > 0f }} hits of ${DrumLayout.shortName(state.laneKey)} on page ${state.page + 1}"
        },
        horizontalArrangement = Arrangement.spacedBy(d.dp(6)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText("this beat", Modifier.width(d.dp(64)), style = label, maxLines = 1)
        Canvas(Modifier.weight(1f).height(d.dp(14))) {
            val playhead = state.stepOf(slot) - first // read here: it changes every step while playing
            val gap = d.px(6f)
            val w = (size.width - gap * 15) / 16
            val r = CornerRadius(d.px(3f))
            for (c in 0 until PAGE_STEPS) {
                val x = c * (w + gap)
                val v = velocities[c]
                val inside = first + c < length
                val colour = if (v > 0f) GrooveColors.Text.copy(alpha = 0.35f + 0.65f * v.coerceIn(0f, 1f)) else BeatOff
                drawRoundRect(colour, Offset(x, 0f), Size(w, size.height), r, alpha = if (inside) 1f else 0.35f)
                if (c == playhead) {
                    val s = d.px(2f)
                    drawRoundRect(GrooveColors.Strips[2], Offset(x + s / 2, s / 2), Size(w - s, size.height - s), r, style = Stroke(s))
                }
            }
        }
    }
}

private val BeatOff = Color(0xFF26262B)
private const val PAGE_STEPS = 16

@Composable
private fun DrumPicture(drum: DrumController, modifier: Modifier) {
    val d = LocalDesign.current
    val pitch = drum.value("drum.pitch")
    val decay = drum.value("drum.decay")
    val level = drum.value("drum.level")
    val start = drum.value("drum.start")
    val reverse = drum.value("drum.reverse") >= 0.5
    val tone = drum.spec("drum.tone")?.let { ParamMapping.toNormalized(it, drum.value(it)) } ?: 1.0
    val wave = remember(pitch, decay, level, start, reverse) { DrumLayout.shape(pitch, decay, level, start, reverse) }
    val env = remember(decay, level, start, reverse) { DrumLayout.envelope(decay, level, start, reverse) }
    val stroke = d.px(3f)
    val divider = d.px(2f)

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val mid = h / 2
        val amp = mid - stroke * 2

        // Envelope, mirrored above and below the centre line, filled lightly.
        val area = Path()
        env.forEachIndexed { i, p ->
            val x = (p.x * w).toFloat()
            val y = mid - (p.y * amp).toFloat()
            if (i == 0) area.moveTo(x, y) else area.lineTo(x, y)
        }
        for (i in env.indices.reversed()) {
            val p = env[i]
            area.lineTo((p.x * w).toFloat(), mid + (p.y * amp).toFloat())
        }
        area.close()
        drawPath(area, GrooveColors.Strips[2], alpha = 0.14f)

        // The ring itself; Tone (low-pass) fades its brightness.
        val line = Path()
        wave.forEachIndexed { i, p ->
            val x = (p.x * w).toFloat()
            val y = mid - (p.y * amp).toFloat()
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }
        drawPath(line, GrooveColors.Strips[0], alpha = (0.45 + 0.55 * tone).toFloat(), style = Stroke(stroke, join = StrokeJoin.Round))
        drawLine(GrooveColors.Divider, Offset(0f, mid), Offset(w, mid), divider / 2)
    }
}
