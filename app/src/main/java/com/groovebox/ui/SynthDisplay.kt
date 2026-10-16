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
import kotlin.math.ln
import kotlin.math.pow

private val WaveColour = Color(0xFFFF6B57)
private val FilterColour = Color(0xFFFFC23D)
private val EnvColour = Color(0xFF3DD68C)
private val CutoffDot = Color(0xFF3DD68C)

/** Drawn frequency axis of the filter picture, and its dB window. */
private const val F_LO = 20.0
private const val F_HI = 20_000.0
private const val DB_TOP = 18.0
private const val DB_BOTTOM = -60.0

/**
 * The display: engine and preset names, the layer label, and live pictures of the
 * oscillator, filter and envelope drawn from the current values. It is not a control,
 * except that tapping the engine name toggles the debug panel.
 */
@Composable
fun SynthDisplay(
    controller: SynthController,
    audioStarted: Boolean,
    debug: DebugSlot,
    modifier: Modifier = Modifier,
) {
    val d = LocalDesign.current
    val section = controller.section
    val shifted = controller.shifted
    val modeSpec = controller.spec("osc.voiceMode")
    val mode = modeSpec?.let { ParamMapping.format(it, controller.value(it)).lowercase() } ?: ""

    Column(
        modifier
            .clip(RoundedCornerShape(d.dp(16)))
            .background(GrooveColors.Panel)
            .padding(horizontal = d.dp(18), vertical = d.dp(14))
            .semantics { contentDescription = "Synth display, ${controller.presetName}" },
        verticalArrangement = Arrangement.spacedBy(d.dp(8)),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(d.dp(10))) {
            DisplayTitle("Brook", debug, Modifier.alignByBaseline())
            DisplaySubtitle(
                "${controller.presetName} · slot ${controller.slot + 1} · $mode",
                audioStarted,
                Modifier.weight(1f).alignByBaseline(),
            )
            BasicText(
                "${section.label.uppercase()} · ${if (shifted) "SHIFT" else "MAIN"} LAYER",
                Modifier.alignByBaseline(),
                style = d.value(12f, if (shifted) GrooveColors.Shift else GrooveColors.TextDim),
                maxLines = 1,
            )
        }

        Box(Modifier.fillMaxWidth().weight(1f)) {
            Pictures(controller, section, shifted, Modifier.fillMaxSize())
            if (debug.open) debug.panel()
        }

        Legend(controller.strips, controller)
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

    fun alphaFor(panel: SynthSection) = if (section == panel || section == SynthSection.Mod) 1f else 0.45f

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
