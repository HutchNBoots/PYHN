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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.groovebox.ui.model.DrumLayout
import com.groovebox.ui.model.ParamMapping
import com.groovebox.ui.theme.GrooveColors
import com.groovebox.ui.theme.LocalDesign

/** The Drum screen's middle row (F2.2): the kit display and the four strips for the key last played. */
@Composable
fun DrumMiddleRow(drum: DrumController, audioStarted: Boolean, debug: DebugSlot, modifier: Modifier = Modifier) {
    val d = LocalDesign.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(d.dp(10))) {
        DrumDisplay(drum, audioStarted, debug, Modifier.weight(1f).fillMaxHeight())
        StripRow(drum.strips, drum)
    }
}

/**
 * The kit display: "Kit", the edited key's sound, the layer, a picture of the sound (its
 * ring inside its decay/level envelope, reversed and trimmed as set) and a map of the 16 keys.
 */
@Composable
fun DrumDisplay(drum: DrumController, audioStarted: Boolean, debug: DebugSlot, modifier: Modifier = Modifier) {
    val d = LocalDesign.current
    val key = drum.selectedKey
    val name = drum.soundName(key)
    val shifted = drum.shifted

    Column(
        modifier
            .clip(RoundedCornerShape(d.dp(16)))
            .background(GrooveColors.Panel)
            .padding(horizontal = d.dp(18), vertical = d.dp(14))
            .semantics { contentDescription = "Drum display, $name" },
        verticalArrangement = Arrangement.spacedBy(d.dp(8)),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(d.dp(10))) {
            DisplayTitle("Kit", debug, Modifier.alignByBaseline())
            DisplaySubtitle("$name · key ${key + 1} · slot 1", audioStarted, Modifier.weight(1f).alignByBaseline())
            LayerLabel(
                if (shifted) "SHIFT · pan / start / rev / choke" else "MAIN · pitch / decay / tone / level",
                shifted,
                Modifier.alignByBaseline(),
            )
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            DrumPicture(drum, key, Modifier.fillMaxSize())
            if (debug.open) debug.panel()
        }
        Legend(drum.strips, drum)
    }
}

@Composable
private fun DrumPicture(drum: DrumController, key: Int, modifier: Modifier) {
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
    val gap = d.px(20f)
    val divider = d.px(2f)
    val cell = d.px(16f)
    val cellGap = d.px(5f)

    Canvas(modifier) {
        val mapWidth = 4 * cell + 3 * cellGap
        val w = size.width - mapWidth - gap
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

        // Map of the 16 keys, lowest at the bottom left; the edited key in amber.
        val left = size.width - mapWidth
        drawLine(GrooveColors.Divider, Offset(left - gap / 2, h * 0.05f), Offset(left - gap / 2, h * 0.95f), divider)
        val top = (h - (4 * cell + 3 * cellGap)) / 2
        for (k in 0 until DrumLayout.KEY_COUNT) {
            val col = k % 4
            val row = 3 - k / 4
            drawRoundRect(
                if (k == key) GrooveColors.Shift else GrooveColors.Divider,
                topLeft = Offset(left + col * (cell + cellGap), top + row * (cell + cellGap)),
                size = Size(cell, cell),
                cornerRadius = CornerRadius(d.px(4f)),
            )
        }
    }
}
