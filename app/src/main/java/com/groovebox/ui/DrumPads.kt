package com.groovebox.ui

import android.os.Build
import android.view.MotionEvent
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import com.groovebox.ui.model.DrumLayout
import com.groovebox.ui.model.KeyTouchTracker
import com.groovebox.ui.theme.GrooveColors
import com.groovebox.ui.theme.LocalDesign

/** The pads' size as last laid out; read only by the touch handler, so not Compose state. */
private class PadSizeHolder {
    var size: IntSize = IntSize.Zero
}

/**
 * The drum slot's bottom zone (build-21, docs/mockups/DrumProposed.dc.html): the 16 kit sounds
 * as two 4 × 2 pad blocks, one under each thumb, in the kit grid's order ([DrumLayout.padKey]).
 * A pad plays its sound through [listener] like a key (so it records when armed and selects the
 * sound), with velocity from the touch's height over the whole pad, and lights while held. The
 * selected sound's pad is cream. Raw MotionEvents, as on the keyboard, so a note fires on
 * touch-down with the kernel's touch time (tap-to-tone).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DrumPads(state: GrooveState, modifier: Modifier = Modifier) {
    val d = LocalDesign.current
    val drum = state.drum
    val holder = remember { PadSizeHolder() }
    val listener by rememberUpdatedState<KeyListener>(state)
    val tracker = remember(drum) {
        KeyTouchTracker(
            onNoteOn = { note, velocity, tapTimeNs ->
                listener.keyDown(note, velocity, tapTimeNs)
                DrumLayout.keyOf(note)?.let { drum.padHeld(it, true) }
            },
            onNoteOff = { note ->
                listener.keyUp(note)
                DrumLayout.keyOf(note)?.let { drum.padHeld(it, false) }
            },
        )
    }
    // Pads still held when they leave the screen are released.
    DisposableEffect(tracker) { onDispose { tracker.cancel() } }
    val measurer = rememberTextMeasurer()
    val nameStyle = d.value(12f, GrooveColors.Text)
    val gapPx = d.px(BLOCK_GAP)
    val selected = drum.selectedKey

    Spacer(
        modifier
            .semantics { contentDescription = "Drum pads, two blocks of eight, Kick at the lower left" }
            .onSizeChanged { holder.size = it }
            .pointerInteropFilter { event ->
                val w = holder.size.width.toFloat()
                val h = holder.size.height.toFloat()
                fun note(i: Int): Pair<Int?, Float> {
                    val hit = DrumLayout.padsHit(event.getX(i), event.getY(i), w, h, gapPx) ?: return null to 0f
                    return DrumLayout.noteOf(hit.key) to hit.velocity
                }
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                        val i = event.actionIndex
                        val (n, v) = note(i)
                        tracker.down(event.getPointerId(i), n, v, padTouchTimeNs(event))
                    }
                    MotionEvent.ACTION_MOVE -> for (i in 0 until event.pointerCount) {
                        val (n, v) = note(i)
                        tracker.move(event.getPointerId(i), n, v)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> tracker.up(event.getPointerId(event.actionIndex))
                    MotionEvent.ACTION_CANCEL -> tracker.cancel()
                }
                true
            }
            .drawWithCache {
                val blockW = (size.width - gapPx) / 2f
                val padGap = d.px(PAD_GAP)
                val padW = (blockW - padGap * 3) / 4f
                val padH = (size.height - padGap) / 2f
                val radius = CornerRadius(d.px(10f))
                val pads = (0 until DrumLayout.KEY_COUNT).associateWith { key ->
                    val block = key / 8
                    val row = 1 - (key % 8) / 4
                    val col = key % 4
                    val left = block * (blockW + gapPx) + col * (padW + padGap)
                    val top = row * (padH + padGap)
                    Rect(left, top, left + padW, top + padH)
                }
                val paths = pads.mapValues { (_, r) -> Path().apply { addRoundRect(RoundRect(r, radius)) } }
                val inset = d.px(10f)
                val foot = d.px(8f)
                onDrawBehind {
                    for ((key, r) in pads) {
                        val held = drum.isPadHeld(key)
                        val isSelected = key == selected
                        val body = when {
                            held -> GrooveColors.KeyLit
                            isSelected -> GrooveColors.Text
                            else -> GrooveColors.KeyPad
                        }
                        drawPath(paths.getValue(key), body)
                        val colour = if (held || isSelected) GrooveColors.Ground else GrooveColors.Text
                        val text = measurer.measure(DrumLayout.shortName(key), nameStyle.copy(color = colour))
                        drawText(text, topLeft = Offset(r.left + inset, r.bottom - foot - text.size.height))
                    }
                }
            },
    )
}

// Design units (docs/mockups/DrumProposed.dc.html).
private const val BLOCK_GAP = 18f
private const val PAD_GAP = 6f

/** CLOCK_MONOTONIC time of the touch sample, same clock as AAudio timestamps. */
private fun padTouchTimeNs(event: MotionEvent): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        event.eventTimeNanos
    } else {
        event.eventTime * 1_000_000L
    }
