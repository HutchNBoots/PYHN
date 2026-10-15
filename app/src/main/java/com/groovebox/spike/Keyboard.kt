package com.groovebox.spike

import android.os.Build
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.groovebox.engine.NativeEngine

private val WhiteKey = Color(0xFFE9E6DF)
private val BlackKey = Color(0xFF26262C)
private val PressedKey = Color(0xFFFFC23D)
private val KeyEdge = Color(0xFF101014)

/**
 * Full-width keyboard. Uses raw MotionEvents so note-ons fire on touch-down without
 * gesture detection delay, and carry the kernel touch timestamp for latency measurement.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Keyboard(layout: KeyboardLayout, modifier: Modifier = Modifier) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val pressed = remember { mutableStateMapOf<Int, Unit>() }
    val tracker = remember {
        KeyTouchTracker(
            onNoteOn = { note, tapTimeNs ->
                NativeEngine.noteOn(note, tapTimeNs)
                pressed[note] = Unit
            },
            onNoteOff = { note ->
                NativeEngine.noteOff(note)
                pressed.remove(note)
            },
        )
    }

    Canvas(
        modifier
            .onSizeChanged { size = it }
            .pointerInteropFilter { event ->
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                        val i = event.actionIndex
                        tracker.down(
                            event.getPointerId(i),
                            layout.noteAt(event.getX(i), event.getY(i), w, h),
                            touchTimeNs(event),
                        )
                    }
                    MotionEvent.ACTION_MOVE -> for (i in 0 until event.pointerCount) {
                        tracker.move(event.getPointerId(i), layout.noteAt(event.getX(i), event.getY(i), w, h))
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                        tracker.up(event.getPointerId(event.actionIndex))
                    MotionEvent.ACTION_CANCEL -> tracker.cancel()
                }
                true
            },
    ) {
        val gap = 2f
        for (key in layout.keys) {
            val topLeft = Offset(key.left * this.size.width + gap, key.top * this.size.height)
            val keySize = Size(
                (key.right - key.left) * this.size.width - 2 * gap,
                (key.bottom - key.top) * this.size.height - gap,
            )
            val fill = when {
                key.note in pressed -> PressedKey
                key.isBlack -> BlackKey
                else -> WhiteKey
            }
            drawRoundRect(fill, topLeft, keySize, CornerRadius(8f, 8f))
            drawRoundRect(KeyEdge, topLeft, keySize, CornerRadius(8f, 8f), style = Stroke(gap))
        }
    }
}

/** CLOCK_MONOTONIC time of the touch sample, same clock as AAudio timestamps. */
private fun touchTimeNs(event: MotionEvent): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        event.eventTimeNanos
    } else {
        event.eventTime * 1_000_000L
    }
