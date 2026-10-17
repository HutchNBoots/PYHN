package com.groovebox.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.groovebox.ui.theme.Design

/**
 * The strip lock's filling ring (build-22, owner decision 1-2c): while a finger rests still on a
 * strip, from [com.groovebox.ui.model.StripGesture.RING_DELAY_MS] a ring fills clockwise where
 * the padlock appears (under the value, above the thumb, ADR 0003) until the lock at 1 s.
 * [progress] is read in the draw phase only (0..1, or below 0 for none), so the strip does not
 * recompose while it fills.
 */
fun Modifier.lockRing(progress: () -> Float, colour: Color, d: Design): Modifier = drawWithContent {
    drawContent()
    val p = progress()
    if (p < 0f) return@drawWithContent
    val r = d.px(RING_RADIUS)
    val stroke = d.px(RING_STROKE)
    val centre = Offset(size.width / 2f, d.px(RING_CENTRE_Y))
    val topLeft = Offset(centre.x - r, centre.y - r)
    drawCircle(colour.copy(alpha = 0.25f), r, centre, style = Stroke(stroke))
    drawArc(colour, -90f, 360f * p, false, topLeft, Size(2 * r, 2 * r), style = Stroke(stroke, cap = StrokeCap.Round))
}

// Design units: centred where the padlock sits (label, value, then the lock mark).
private const val RING_CENTRE_Y = 62f
private const val RING_RADIUS = 11f
private const val RING_STROKE = 3f
