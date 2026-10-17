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
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
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
import com.groovebox.ui.model.StripFill
import com.groovebox.ui.model.StripGesture
import com.groovebox.ui.theme.GrooveColors
import com.groovebox.ui.theme.GrooveRadius
import com.groovebox.ui.theme.GrooveSpace
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
    val shape = d.shape(GrooveRadius.STRIP)

    if (spec == null) {
        Column(
            modifier
                .alpha(GrooveColors.INERT_ALPHA)
                .clip(shape)
                .background(GrooveColors.Panel)
                .semantics { contentDescription = "Strip ${position + 1}, ${if (disabledLabel == "—") "unused" else "$disabledLabel, none"}" }
                .padding(horizontal = d.dp(GrooveSpace.PAD), vertical = d.dp(GrooveSpace.ZONE)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(disabledLabel, style = d.buttonLabel(colour, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Clip)
            if (disabledValue != null) {
                BasicText(disabledValue, Modifier.padding(top = d.dp(GrooveSpace.GROUP)), style = d.valueText(GrooveColors.White), maxLines = 1)
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
    val dimmed = host.dimmed(spec)
    // Only a pending strip reads the blink phase, so only it recomposes as it blinks.
    val textAlpha = when {
        pending && !host.blinkOn -> GrooveColors.BLINK_ALPHA
        dimmed -> GrooveColors.INERT_ALPHA
        else -> 1f
    }
    val currentNormalized by rememberUpdatedState(normalized)
    val currentLocked by rememberUpdatedState(locked)
    // Build-22 (agent 2): the lock hold's ring (draw phase only, driven by the frame clock while
    // a still hold is pending) and the haptic at the lock.
    val ring = remember { mutableFloatStateOf(-1f) }
    val holding = remember { mutableStateOf(false) }
    val gestureRef = remember { arrayOfNulls<StripGesture>(1) }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(holding.value) {
        if (!holding.value) {
            ring.floatValue = -1f
            return@LaunchedEffect
        }
        while (true) withFrameMillis { ring.floatValue = gestureRef[0]?.lockProgress(SystemClock.uptimeMillis()) ?: -1f }
    }
    val edge = d.px(3f)
    val header = d.px(StripFill.HEADER_UNITS)

    Box(
        modifier
            .clip(shape)
            .background(GrooveColors.Panel)
            .semantics {
                contentDescription = "${spec.label} $text${if (pending) ", queued" else ""}" +
                    "${if (dimmed) ", not set on this step" else ""}" +
                    "${if (locked) ", locked" else ""}, drag up or down"
            }
            // Restarts (dropping any gesture in progress) when the strip shows another parameter
            // or drives another host (e.g. another slot).
            .pointerInput(spec, host) {
                val gesture = StripGesture(touchSlop = viewConfiguration.touchSlop)
                gestureRef[0] = gesture
                fun handle(action: StripAction?) {
                    when (action) {
                        is StripAction.Set -> host.setValue(spec, ParamMapping.fromNormalized(spec, action.normalized))
                        StripAction.Reset -> host.reset(spec)
                        is StripAction.Lock -> {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            host.setLocked(spec, action.locked)
                        }
                        null -> Unit
                    }
                }
                // Fingers down on this strip: the first starts the host's gesture, the last ends it.
                val down = HashSet<Int>()
                try {
                    awaitPointerEventScope {
                        while (true) {
                            val deadline = gesture.longPressDeadline
                            holding.value = deadline != null
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
                                holding.value = gesture.longPressDeadline != null
                                continue
                            }
                            for (change in event.changes) {
                                val id = change.id.value.toInt()
                                when {
                                    change.changedToDownIgnoreConsumed() -> {
                                        if (down.isEmpty()) host.gestureStart(spec)
                                        down += id
                                        gesture.locked = currentLocked
                                        handle(
                                            gesture.down(
                                                id, change.uptimeMillis, change.position.y,
                                                size.height.toFloat(), currentNormalized,
                                            ),
                                        )
                                    }
                                    change.changedToUpIgnoreConsumed() -> {
                                        handle(gesture.up(id, change.uptimeMillis))
                                        if (down.remove(id) && down.isEmpty()) host.gestureEnd(spec)
                                    }
                                    change.positionChanged() -> handle(gesture.move(id, change.uptimeMillis, change.position.y))
                                }
                                change.consume()
                            }
                        }
                    }
                } finally {
                    holding.value = false
                    // Restarted or left the screen mid-gesture: close it.
                    if (down.isNotEmpty()) host.gestureEnd(spec)
                }
            }
            .drawBehind {
                // CX review R5: the fill tops out below the label band, so the edge never
                // crosses the label or value; at the minimum it stays visible at the bottom.
                val top = StripFill.edgeTop(normalized, size.height, header, edge)
                drawRect(colour.copy(alpha = 0x38 / 255f), Offset(0f, top), Size(size.width, size.height - top))
                drawRect(colour, Offset(0f, top), Size(size.width, edge))
                // A queued change: outlined in the strip colour until it takes effect.
                if (pending) {
                    drawRoundRect(
                        colour,
                        topLeft = Offset(edge / 2, edge / 2),
                        size = Size(size.width - edge, size.height - edge),
                        cornerRadius = CornerRadius(d.px(GrooveRadius.STRIP)),
                        style = Stroke(edge * 0.67f),
                    )
                }
            }
            .lockRing({ ring.floatValue }, colour, d),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = d.dp(GrooveSpace.PAD), vertical = d.dp(GrooveSpace.ZONE)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(spec.label, style = d.buttonLabel(colour, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Clip)
            BasicText(
                text,
                Modifier.padding(top = d.dp(GrooveSpace.GROUP)).alpha(textAlpha),
                style = d.valueText(GrooveColors.White),
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
