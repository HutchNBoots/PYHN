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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.groovebox.ui.theme.GrooveRadius
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
 * [playable] gates touches (key and scale, F8.2; see [KeyTouchTracker]) and [roots] get an
 * amber stripe (the key's root, F8.3).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Keyboard(
    layout: KeyboardLayout,
    listener: KeyListener,
    modifier: Modifier = Modifier,
    keyLabels: Map<Int, String>? = null,
    dimmed: Set<Int> = emptySet(),
    roots: Set<Int> = emptySet(),
    playable: (Int) -> Boolean = { true },
) {
    val d = LocalDesign.current
    val pressed = remember { mutableStateMapOf<Int, Unit>() }
    val holder = remember { SizeHolder() }
    val currentListener by rememberUpdatedState(listener)
    val currentPlayable by rememberUpdatedState(playable)
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
            playable = { note -> currentPlayable(note) },
        )
    }
    // Keys still held when the keyboard leaves the screen are released.
    DisposableEffect(tracker) { onDispose { tracker.cancel() } }
    val textMeasurer = rememberTextMeasurer()
    // CX review R12: every key label at the caption size (10 u), none smaller.
    val labelStyle = d.caption()

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
                // Capsule-pad look: a recessed tray holding two rows of separate rounded pads,
                // sharps above and naturals below (piano order), each with a light stripe down
                // its middle. A held pad and its stripe light up. Touch geometry is unchanged:
                // KeyboardLayout still owns which note a point plays.
                val tray = RoundRect(Rect(0f, 0f, size.width, size.height), CornerRadius(d.px(TRAY_RADIUS)))
                val inset = d.px(TRAY_INSET)
                val inner = Rect(inset, inset, size.width - inset, size.height - inset)
                // Every pad, natural or sharp, has the same width: a fraction of a natural key's
                // slot, centred on its key, so the pads stand upright like capsules.
                val whiteCount = layout.keys.count { !it.isBlack }
                val padWidth = inner.width / whiteCount * PAD_WIDTH
                val rects = layout.keys.map { key ->
                    val (top, bottom) = if (key.isBlack) TOP_ROW else BOTTOM_ROW
                    val cx = inner.left + (key.left + key.right) / 2f * inner.width
                    Rect(
                        left = cx - padWidth / 2f,
                        top = inner.top + top * inner.height,
                        right = cx + padWidth / 2f,
                        bottom = inner.top + bottom * inner.height,
                    )
                }
                val pads = rects.map { r -> RoundRect(r, CornerRadius(minOf(r.width, r.height) / 2f)) }
                val stripes = rects.map { r ->
                    val w = r.width * STRIPE_WIDTH
                    val cx = r.center.x
                    val inY = r.width * 0.45f
                    RoundRect(Rect(cx - w / 2f, r.top + inY, cx + w / 2f, r.bottom - inY), CornerRadius(w / 2f))
                }
                // A soft top-lit sheen on each pad, darker toward its foot.
                val sheen = rects.map { r ->
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = SHEEN_ALPHA),
                        0.5f to Color.Transparent,
                        1f to Color.Black.copy(alpha = SHADE_ALPHA),
                        startY = r.top,
                        endY = r.bottom,
                    )
                }
                // "C3", "C4" (or the given key names) just under each pad's stripe.
                val labels = HashMap<Int, Pair<TextLayoutResult, Offset>>()
                layout.keys.forEachIndexed { i, key ->
                    val text = when {
                        keyLabels != null -> keyLabels[key.note]
                        !key.isBlack && key.note % 12 == 0 -> "C${key.note / 12 - 1}"
                        else -> null
                    } ?: return@forEachIndexed
                    val measured = textMeasurer.measure(text, labelStyle)
                    val r = rects[i]
                    val top = r.bottom + d.px(1f)
                    labels[i] = measured to Offset(r.center.x - measured.size.width / 2f, top)
                }
                fun roundPath(rr: RoundRect) = Path().apply { addRoundRect(rr) }
                val trayPath = roundPath(tray)
                val padPaths = pads.map(::roundPath)
                val stripePaths = stripes.map(::roundPath)
                onDrawBehind {
                    drawPath(trayPath, GrooveColors.KeyTray)
                    layout.keys.forEachIndexed { i, key ->
                        val held = key.note in pressed
                        val dim = key.note in dimmed && !held
                        val body = if (held) GrooveColors.KeyLit else GrooveColors.KeyPad
                        val stripe = when {
                            held -> GrooveColors.KeyLitStripe
                            key.note in roots -> GrooveColors.KeyRootStripe
                            else -> GrooveColors.KeyStripe
                        }
                        drawPath(padPaths[i], if (dim) lerp(body, GrooveColors.KeyTray, DIM) else body)
                        if (!held) drawPath(padPaths[i], sheen[i])
                        drawPath(stripePaths[i], if (dim) lerp(stripe, GrooveColors.KeyPad, DIM) else stripe)
                    }
                    labels.values.forEach { (measured, topLeft) -> drawText(measured, topLeft = topLeft) }
                }
            },
    )
}

private const val DIM = 0.5f

// Capsule-pad geometry (design units, or fractions of the tray's inner height) and finish.
private const val TRAY_RADIUS = GrooveRadius.PANEL
private const val TRAY_INSET = 6f
private const val PAD_WIDTH = 0.56f
private val TOP_ROW = 0.03f to 0.45f
private val BOTTOM_ROW = 0.50f to 0.90f
private const val STRIPE_WIDTH = 0.12f
private const val SHEEN_ALPHA = 0.10f
private const val SHADE_ALPHA = 0.25f

/** CLOCK_MONOTONIC time of the touch sample, same clock as AAudio timestamps. */
private fun touchTimeNs(event: MotionEvent): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        event.eventTimeNanos
    } else {
        event.eventTime * 1_000_000L
    }
