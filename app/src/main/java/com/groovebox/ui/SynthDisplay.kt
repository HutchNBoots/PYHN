package com.groovebox.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.groovebox.engine.ParamSpec
import com.groovebox.ui.model.DisplayShapes
import com.groovebox.ui.model.ParamMapping
import com.groovebox.ui.model.SynthSection
import com.groovebox.ui.theme.GrooveColors
import com.groovebox.ui.theme.LocalDesign
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import com.groovebox.ui.model.LfoTrace
import com.groovebox.ui.model.SynthLayout
import com.groovebox.ui.theme.GrooveRadius
import kotlin.math.roundToInt
import kotlin.math.ln
import kotlin.math.pow

private val WaveColour = Color(0xFFFF6B57)
private val FilterColour = Color(0xFFFFC23D)
private val EnvColour = Color(0xFF3DD68C)
private val CutoffDot = Color(0xFF3DD68C)
private val LfoColour = Color(0xFFECE9E2) // cream: strip colours stay for strip-bound things (review R6)

/** Drawn frequency axis of the filter picture, and its dB window. */
private const val F_LO = 20.0
private const val F_HI = 20_000.0
private const val DB_TOP = 18.0
private const val DB_BOTTOM = -60.0

/**
 * The display: engine and preset names, the layer label, and live pictures of the
 * oscillator, filter and envelope drawn from the current values. It is not a control,
 * except that tapping the engine name toggles the debug panel.
 *
 * Build-22: the preset name carries the edited mark ("Glass Wire*"); the mode reads "unison
 * (mono)"; with Mod as the section a small LFO trace sits under the three pictures (all kept,
 * owner decision), with Play a line says what the keys play; [undo] (the sound Undo chip) sits
 * at the bottom-left; [flash] shows the slot and preset large for a moment (a display swipe,
 * a saved preset).
 */
@Composable
fun SynthDisplay(
    controller: SynthController,
    audioStarted: Boolean,
    debug: DebugSlot,
    modifier: Modifier = Modifier,
    keyName: String? = null,
    keyRange: String? = null,
    undo: (@Composable () -> Unit)? = null,
    flash: SlotFlash? = null,
) {
    val d = LocalDesign.current
    val section = controller.section
    val shifted = controller.shifted
    val mode = SynthLayout.voiceModeLabel(controller.value("osc.voiceMode").roundToInt()).lowercase()

    Column(
        modifier
            .clip(RoundedCornerShape(d.dp(16)))
            .background(GrooveColors.Panel)
            .padding(horizontal = d.dp(18), vertical = d.dp(14))
            .semantics {
                contentDescription = "Synth display, ${controller.presetName}" + (if (controller.edited) ", edited" else "")
            },
        verticalArrangement = Arrangement.spacedBy(d.dp(8)),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(d.dp(10))) {
            DisplayTitle("Brook", debug, Modifier.alignByBaseline())
            DisplaySubtitle(
                "${controller.displayName} · slot ${controller.slot + 1} · $mode",
                audioStarted,
                Modifier.weight(1f).alignByBaseline(),
            )
            if (keyName != null) KeyName(keyName, Modifier.alignByBaseline())
            LayerLabel("${if (shifted) "SHIFT" else "MAIN"} · ${section.label.lowercase()}", shifted, Modifier.alignByBaseline())
        }

        Box(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(d.dp(6))) {
                Pictures(controller, section, shifted, Modifier.fillMaxWidth().weight(1f))
                when (section) {
                    SynthSection.Mod -> LfoRow(controller, Modifier.fillMaxWidth().height(d.dp(22)))
                    SynthSection.Play -> PlayRow(keyRange, keyName, mode, Modifier.fillMaxWidth().height(d.dp(22)))
                    else -> Unit
                }
            }
            if (flash != null) FlashOverlay(flash, Modifier.fillMaxSize())
            if (debug.open) debug.panel()
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(d.dp(10)), verticalAlignment = Alignment.CenterVertically) {
            undo?.invoke()
            Box(Modifier.weight(1f)) { Legend(controller.strips, controller) }
        }
    }
}

/** What a swipe or a save shows large on a display for a moment (build-22, owner decision 1-1A). */
@Immutable
data class SlotFlash(val big: String, val small: String, val id: Int)

/** The flash: the slot number and the preset name, large, over the pictures. */
@Composable
fun FlashOverlay(flash: SlotFlash, modifier: Modifier = Modifier) {
    val d = LocalDesign.current
    Column(
        modifier
            .clip(d.shape(GrooveRadius.CELL))
            .background(GrooveColors.Panel.copy(alpha = 0.92f))
            .semantics { contentDescription = "${flash.big} ${flash.small}" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BasicText(flash.big, style = d.value(44f, GrooveColors.Text, FontWeight.Medium), maxLines = 1)
        BasicText(flash.small, style = d.label(22f, GrooveColors.Text, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Mod's small LFO trace (owner decision 1-2b): shape, rate (or its division), depth and fade, and the target. */
@Composable
private fun LfoRow(controller: SynthController, modifier: Modifier) {
    val d = LocalDesign.current
    val rate = controller.value("lfo.rate")
    val synced = controller.lfoSynced
    val shape = controller.value("lfo.wave").roundToInt()
    val depth = controller.value("lfo.depth")
    val fade = controller.value("lfo.delay")
    val bpm = controller.tempoBpm
    val dest = controller.spec("lfo.dest")?.let { ParamMapping.format(it, controller.value(it)).lowercase() } ?: ""
    val rateText = controller.spec("lfo.rate")?.let { controller.format(it, rate) } ?: ""
    val points = remember(rate, synced, shape, depth, fade, bpm) {
        LfoTrace.points(shape, LfoTrace.cycles(rate, synced, bpm), depth, fade, LfoTrace.windowS(rate, synced, bpm))
    }
    val stroke = d.px(2f)
    Row(
        modifier.semantics { contentDescription = "LFO $rateText to $dest" },
        horizontalArrangement = Arrangement.spacedBy(d.dp(8)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText("LFO", style = d.meta(GrooveColors.TextDim), maxLines = 1)
        Canvas(Modifier.weight(1f).fillMaxHeight()) {
            val h = size.height
            val path = Path()
            points.forEachIndexed { i, p ->
                val x = (p.x * size.width).toFloat()
                val y = (h / 2 - p.y * (h / 2 - stroke)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawLine(GrooveColors.Divider, Offset(0f, h / 2), Offset(size.width, h / 2), stroke / 2)
            drawPath(path, LfoColour, alpha = if (depth > 0.0) 1f else 0.45f, style = Stroke(stroke, join = StrokeJoin.Round))
        }
        BasicText("$rateText → $dest", style = d.meta(GrooveColors.TextDim), maxLines = 1)
    }
}

/** Play's line: what the keys play now (the keyboard octave) and that the pattern does not move. */
@Composable
private fun PlayRow(keyRange: String?, keyName: String?, mode: String, modifier: Modifier) {
    val d = LocalDesign.current
    val text = listOfNotNull("keys ${keyRange ?: "C3–B4"}", keyName ?: "no scale", mode, "pattern unchanged").joinToString(" · ")
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        BasicText(text, style = d.meta(GrooveColors.TextDim), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Oscillator | filter | envelope, the panel for the current section at full strength. */
@Composable
private fun Pictures(controller: SynthController, section: SynthSection, shifted: Boolean, modifier: Modifier) {
    val d = LocalDesign.current
    val wave = controller.value("osc.wave")
    val pw = controller.value("osc.pw")
    val cutoff = controller.value("flt.cutoff")
    val reso = controller.value("flt.reso")
    val hpf = controller.value("flt.hpf")
    val filterEnv = section == SynthSection.Env && shifted
    val prefix = if (filterEnv) "fenv" else "amp"
    val attack = controller.value("$prefix.attack")
    val decay = controller.value("$prefix.decay")
    val sustain = controller.value("$prefix.sustain")
    val release = controller.value("$prefix.release")
    val stroke = d.px(3f)
    val gap = d.px(20f)
    val divider = d.px(2f)

    fun alphaFor(panel: SynthSection) = if (section == panel || section == SynthSection.Mod || section == SynthSection.Play) 1f else 0.45f

    Canvas(modifier) {
        val w = (size.width - 2 * gap) / 3f
        val h = size.height
        val inset = stroke
        val panels = listOf(0f, w + gap, 2 * (w + gap))
        for (i in 1..2) {
            val x = panels[i] - gap / 2
            drawLine(GrooveColors.Divider, Offset(x, h * 0.05f), Offset(x, h * 0.95f), divider)
        }

        // Oscillator: two cycles of the morphing wave.
        clipRect(panels[0], 0f, panels[0] + w, h) {
            val path = Path()
            val n = 192
            for (k in 0..n) {
                val u = k / n.toDouble()
                // Stop just short of phase 2 so the last point does not wrap back to the start.
                val y = DisplayShapes.wave(wave, pw, u * 2 * (1 - 1e-6))
                val px = panels[0] + (u * w).toFloat()
                val py = (h / 2 - y * (h / 2 - inset * 2)).toFloat()
                if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(path, WaveColour, alpha = alphaFor(SynthSection.Osc), style = Stroke(stroke, join = StrokeJoin.Round))
        }

        // Filter: response over 20 Hz–20 kHz on a log axis, dot at the cutoff.
        clipRect(panels[1], 0f, panels[1] + w, h) {
            fun dbToY(db: Double) =
                (inset + (DB_TOP - db.coerceIn(DB_BOTTOM, DB_TOP)) / (DB_TOP - DB_BOTTOM) * (h - 2 * inset)).toFloat()
            val path = Path()
            val n = 128
            for (k in 0..n) {
                val u = k / n.toDouble()
                val f = F_LO * (F_HI / F_LO).pow(u)
                val px = panels[1] + (u * w).toFloat()
                val py = dbToY(DisplayShapes.filterDb(f, cutoff, reso, hpf))
                if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            val alpha = alphaFor(SynthSection.Filter)
            drawPath(path, FilterColour, alpha = alpha, style = Stroke(stroke, join = StrokeJoin.Round))
            val cu = (ln(cutoff / F_LO) / ln(F_HI / F_LO)).coerceIn(0.0, 1.0)
            drawCircle(
                CutoffDot,
                radius = stroke * 1.7f,
                center = Offset(panels[1] + (cu * w).toFloat(), dbToY(DisplayShapes.filterDb(cutoff, cutoff, reso, hpf))),
                alpha = alpha,
            )
        }

        // Envelope: amp, or the filter envelope on Env + Shift.
        clipRect(panels[2], 0f, panels[2] + w, h) {
            drawEnvelope(DisplayShapes.envelope(attack, decay, sustain, release), panels[2], w, h, inset, stroke, alphaFor(SynthSection.Env))
        }
    }
}

private fun DrawScope.drawEnvelope(
    points: List<DisplayShapes.Point>,
    left: Float,
    width: Float,
    height: Float,
    inset: Float,
    stroke: Float,
    alpha: Float,
) {
    val line = Path()
    val area = Path()
    points.forEachIndexed { k, p ->
        val x = left + inset + (p.x * (width - 2 * inset)).toFloat()
        val y = height - inset - (p.y * (height - 2 * inset)).toFloat()
        if (k == 0) {
            line.moveTo(x, y)
            area.moveTo(x, height - inset)
        }
        line.lineTo(x, y)
        area.lineTo(x, y)
    }
    area.lineTo(left + width - inset, height - inset)
    area.close()
    drawPath(area, EnvColour, alpha = 0.14f * alpha)
    drawPath(line, EnvColour, alpha = alpha, style = Stroke(stroke, join = StrokeJoin.Round))
}
