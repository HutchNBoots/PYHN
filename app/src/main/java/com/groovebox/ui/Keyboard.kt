package com.groovebox.ui

import android.os.Build
import android.view.MotionEvent
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import com.groovebox.ui.model.KeyTouchTracker
import com.groovebox.ui.model.KeyboardLayout
import com.groovebox.ui.theme.GrooveColors
import com.groovebox.ui.theme.LocalDesign

/** The keyboard's size as last laid out; read only by the touch handler, so not Compose state. */
private class SizeHolder {
    var size: IntSize = IntSize.Zero
}

/** Where key presses go; the app routes them to the selected slot (and the sequencer). */
interface KeyListener {
    /** tapTimeNs is the CLOCK_MONOTONIC time of the touch, or 0 for a slide onto the key. */
    fun keyDown(note: Int, velocity: Float, tapTimeNs: Long)
    fun keyUp(note: Int)
}

/**
 * Full-width polyphonic keyboard. Uses raw MotionEvents so note-ons fire on touch-down
 * without gesture-detection delay and carry the kernel touch timestamp for the tap-to-tone
 * measurement. Velocity comes from where the key is touched (0.5 at the top, 1.0 at the bottom).
 * Held keys light up. [keyLabels] puts a small name at the foot of some keys (the drum kit's
 * sounds) in place of the C labels, and [dimmed] keys are drawn faded (they play nothing).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Keyboard(
    layout: KeyboardLayout,
    listener: KeyListener,
    modifier: Modifier = Modifier,
    keyLabels: Map<Int, String>? = null,
    dimmed: Set<Int> = emptySet(),
) {
    val d = LocalDesign.current
    val pressed = remember { mutableStateMapOf<Int, Unit>() }
    val holder = remember { SizeHolder() }
    val currentListener by rememberUpdatedState(listener)
    val tracker = remember {
        KeyTouchTracker(
            onNoteOn = { note, velocity, tapTimeNs ->
                currentListener.keyDown(note, velocity, tapTimeNs)
                pressed[note] = Unit
            },
            onNoteOff = { note ->
                currentListener.keyUp(note)
                pressed.remove(note)
            },
        )
    }
    // Keys still held when the keyboard leaves the screen are released.
    DisposableEffect(tracker) { onDispose { tracker.cancel() } }
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = d.value(11f, GrooveColors.KeyLabel)
    val smallLabelStyle = d.value(9f, GrooveColors.KeyLabel)
    val blackLabelStyle = d.value(9f, GrooveColors.TextDim)

    Spacer(
        modifier
            .semantics { contentDescription = "Keyboard, two octaves" }
            .onSizeChanged { holder.size = it }
            .pointerInteropFilter { event ->
                // The MotionEvent is in this node's coordinates.
                val w = holder.size.width.toFloat()
                val h = holder.size.height.toFloat()
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                        val i = event.actionIndex
                        val hit = layout.hit(event.getX(i), event.getY(i), w, h)
                        tracker.down(event.getPointerId(i), hit?.note, hit?.velocity ?: 0f, touchTimeNs(event))
                    }
                    MotionEvent.ACTION_MOVE -> for (i in 0 until event.pointerCount) {
                        val hit = layout.hit(event.getX(i), event.getY(i), w, h)
                        tracker.move(event.getPointerId(i), hit?.note, hit?.velocity ?: 0f)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                        tracker.up(event.getPointerId(event.actionIndex))
                    MotionEvent.ACTION_CANCEL -> tracker.cancel()
                }
                true
            }
            .drawWithCache {
                val gap = d.px(2f)
                val whiteRadius = CornerRadius(d.px(10f))
                val blackRadius = CornerRadius(d.px(8f))
                // Keys are listed white first, so drawing in order puts black keys on top.
                val shapes = layout.keys.map { key ->
                    val r = Rect(
                        left = key.left * size.width + gap,
                        top = key.top * size.height,
                        right = key.right * size.width - gap,
                        bottom = key.bottom * size.height,
                    )
                    val radius = if (key.isBlack) blackRadius else whiteRadius
                    Path().apply {
                        addRoundRect(RoundRect(r, CornerRadius.Zero, CornerRadius.Zero, radius, radius))
                    }
                }
                // "C3", "C4" at the foot of each C, as in the mockup, or the given key names.
                val labels = HashMap<Int, Pair<TextLayoutResult, Offset>>()
                layout.keys.forEachIndexed { i, key ->
                    val text = when {
                        keyLabels != null -> keyLabels[key.note]
                        !key.isBlack && key.note % 12 == 0 -> "C${key.note / 12 - 1}"
                        else -> null
                    } ?: return@forEachIndexed
                    val style = when {
                        keyLabels == null -> labelStyle
                        key.isBlack -> blackLabelStyle
                        else -> smallLabelStyle
                    }
                    val measured = textMeasurer.measure(text, style)
                    val cx = (key.left + key.right) / 2f * size.width
                    val top = key.bottom * size.height - d.px(8f) - measured.size.height
                    labels[i] = measured to Offset(cx - measured.size.width / 2f, top)
                }
                onDrawBehind {
                    layout.keys.forEachIndexed { i, key ->
                        val fill = when {
                            key.note in pressed -> GrooveColors.KeyLit
                            key.isBlack -> GrooveColors.BlackKey
                            else -> GrooveColors.WhiteKey
                        }
                        // Blended, not translucent: black keys overlap the white ones.
                        val shown = if (key.note in dimmed && key.note !in pressed) lerp(fill, GrooveColors.Ground, DIM) else fill
                        drawPath(shapes[i], shown)
                        labels[i]?.let { (measured, topLeft) -> drawText(measured, topLeft = topLeft) }
                    }
                }
            },
    )
}

private const val DIM = 0.5f

/** CLOCK_MONOTONIC time of the touch sample, same clock as AAudio timestamps. */
private fun touchTimeNs(event: MotionEvent): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        event.eventTimeNanos
    } else {
        event.eventTime * 1_000_000L
    }
