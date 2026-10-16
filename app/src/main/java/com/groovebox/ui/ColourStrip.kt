package com.groovebox.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.groovebox.engine.ParamSpec
import com.groovebox.ui.model.ParamMapping
import com.groovebox.ui.model.StripAction
import com.groovebox.ui.model.StripGesture
import com.groovebox.ui.theme.GrooveColors
import com.groovebox.ui.theme.LocalDesign

/**
 * One colour strip (feature spec F4). The fill rises from the bottom to the value with a
 * solid top edge; the label sits at the top in the strip colour with the value right under it,
 * where the right thumb dragging the strip never covers it (two-thumb grip, ADR 0003).
 * Touches go through [StripGesture]; each pointer is tracked on its own, so all four strips
 * and the keys can be used at once. A null [spec] is a disabled strip, labelled
 * [disabledLabel] (and [disabledValue] at the foot, if given).
 */
@Composable
fun ColourStrip(
    position: Int,
    spec: ParamSpec?,
    host: StripHost,
    modifier: Modifier = Modifier,
    disabledLabel: String = "—",
    disabledValue: String? = null,
) {
    val d = LocalDesign.current
    val colour = GrooveColors.Strips[position]
    val shape = RoundedCornerShape(d.dp(14))

    if (spec == null) {
        Column(
            modifier
                .alpha(GrooveColors.INERT_ALPHA)
                .clip(shape)
                .background(GrooveColors.Panel)
                .semantics { contentDescription = "Strip ${position + 1}, ${if (disabledLabel == "—") "unused" else "$disabledLabel, none"}" }
                .padding(horizontal = d.dp(6), vertical = d.dp(10)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(disabledLabel, style = d.label(14f, colour, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Clip)
            if (disabledValue != null) {
                BasicText(disabledValue, Modifier.padding(top = d.dp(4)), style = d.value(13f, GrooveColors.White), maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
        }
        return
    }

    val value = host.value(spec)
    val normalized = ParamMapping.toNormalized(spec, value)
    val locked = host.isLocked(spec)
    val text = host.format(spec, value)
    val pending = host.pending(spec)
    // Only a pending strip reads the blink phase, so only it recomposes as it blinks.
    val textAlpha = if (pending && !host.blinkOn) 0.25f else 1f
    val currentNormalized by rememberUpdatedState(normalized)
    val currentLocked by rememberUpdatedState(locked)
    val edge = d.px(3f)

    Box(
        modifier
            .clip(shape)
            .background(GrooveColors.Panel)
            .semantics {
                contentDescription = "${spec.label} $text${if (pending) ", queued" else ""}" +
                    "${if (locked) ", locked" else ""}, drag up or down"
            }
            // Restarts (dropping any gesture in progress) when the strip shows another parameter
            // or drives another host (e.g. another slot).
            .pointerInput(spec, host) {
                val gesture = StripGesture(touchSlop = viewConfiguration.touchSlop)
                fun handle(action: StripAction?) {
                    when (action) {
                        is StripAction.Set -> host.setValue(spec, ParamMapping.fromNormalized(spec, action.normalized))
                        StripAction.Reset -> host.reset(spec)
                        is StripAction.Lock -> host.setLocked(spec, action.locked)
                        null -> Unit
                    }
                }
                awaitPointerEventScope {
                    while (true) {
                        val deadline = gesture.longPressDeadline
                        val event = if (deadline == null) {
                            awaitPointerEvent()
                        } else {
                            // Pointer event times are SystemClock.uptimeMillis().
                            val wait = deadline - SystemClock.uptimeMillis()
                            if (wait > 0) withTimeoutOrNull(wait) { awaitPointerEvent() } else null
                        }
                        if (event == null) {
                            // The long-press deadline passed with the finger still down.
                            if (deadline != null) handle(gesture.timeout(deadline))
                            continue
                        }
                        for (change in event.changes) {
                            val id = change.id.value.toInt()
                            when {
                                change.changedToDownIgnoreConsumed() -> {
                                    gesture.locked = currentLocked
                                    handle(
                                        gesture.down(
                                            id, change.uptimeMillis, change.position.y,
                                            size.height.toFloat(), currentNormalized,
                                        ),
                                    )
                                }
                                change.changedToUpIgnoreConsumed() -> handle(gesture.up(id, change.uptimeMillis))
                                change.positionChanged() -> handle(gesture.move(id, change.uptimeMillis, change.position.y))
                            }
                            change.consume()
                        }
                    }
                }
            }
            .drawBehind {
                // The edge stays visible at the bottom when the value is at its minimum.
                val top = (size.height * (1f - normalized.toFloat())).coerceIn(0f, size.height - edge)
                drawRect(colour.copy(alpha = 0x38 / 255f), Offset(0f, top), Size(size.width, size.height - top))
                drawRect(colour, Offset(0f, top), Size(size.width, edge))
                // A queued change: outlined in the strip colour until it takes effect.
                if (pending) {
                    drawRoundRect(
                        colour,
                        topLeft = Offset(edge / 2, edge / 2),
                        size = Size(size.width - edge, size.height - edge),
                        cornerRadius = CornerRadius(d.px(14f)),
                        style = Stroke(edge * 0.67f),
                    )
                }
            },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = d.dp(6), vertical = d.dp(10)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(spec.label, style = d.label(14f, colour, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Clip)
            BasicText(
                text,
                Modifier.padding(top = d.dp(4)).alpha(textAlpha),
                style = d.value(13f, GrooveColors.White),
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
            if (locked) LockMark(colour, Modifier.padding(top = d.dp(6)).size(d.dp(14)))
            Spacer(Modifier.weight(1f))
        }
    }
}

/** A small padlock drawn in the strip colour. */
@Composable
private fun LockMark(colour: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.14f
        // Shackle: an arch over the body.
        drawArc(
            color = colour,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.25f, h * 0.06f),
            size = Size(w * 0.5f, h * 0.6f),
            style = Stroke(stroke),
        )
        drawLine(colour, Offset(w * 0.25f, h * 0.36f), Offset(w * 0.25f, h * 0.5f), stroke)
        drawLine(colour, Offset(w * 0.75f, h * 0.36f), Offset(w * 0.75f, h * 0.5f), stroke)
        drawRoundRect(colour, Offset(w * 0.1f, h * 0.46f), Size(w * 0.8f, h * 0.54f), CornerRadius(w * 0.12f))
    }
}
