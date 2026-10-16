package com.groovebox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.groovebox.engine.ParamSpec
import com.groovebox.ui.model.SlotSwipe
import com.groovebox.ui.theme.GrooveColors
import com.groovebox.ui.theme.LocalDesign

/** The debug overlay (F0.2, F0.4) as handed to a display: tapping the display's title toggles it. */
@Immutable
class DebugSlot(val open: Boolean, val toggle: () -> Unit, val panel: @Composable () -> Unit)

/** Swipe travel per slot, in design units (about 58 dp on the S25+). */
private const val SLOT_SWIPE_UNITS = 72f

/**
 * ADR 0003: a horizontal swipe on a display steps the selected slot ([SlotSwipe]), so the left
 * thumb never has to reach the slot buttons at the top centre. With [onLongPress], holding
 * still on the display for the long-press time calls it instead (P1: the Synth display opens
 * the preset strip). One detector does both, so a slow swipe never turns into a long-press:
 * the swipe wins once the finger passes the horizontal touch slop. Taps (the title's debug
 * toggle) still work: nothing here consumes the touch until a swipe starts.
 */
@Composable
fun Modifier.slotSwipe(onLongPress: (() -> Unit)? = null, onSteps: (Int) -> Unit): Modifier {
    val d = LocalDesign.current
    val steps by rememberUpdatedState(onSteps)
    val longPress by rememberUpdatedState(onLongPress)
    val stepPx = d.px(SLOT_SWIPE_UNITS)
    return pointerInput(stepPx) {
        val swipe = SlotSwipe(stepPx)
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var overSlop = 0f
            // null: the long-press time ran out with the finger still down and not swiping.
            // Without a long-press action there is no time limit: a swipe can start any time.
            val limit = if (longPress != null) viewConfiguration.longPressTimeoutMillis else Long.MAX_VALUE
            val outcome = withTimeoutOrNull(limit) {
                awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                    change.consume()
                    overSlop = over
                } ?: SwipeOutcome.Cancelled
            }
            when (outcome) {
                null -> longPress?.let { open ->
                    open()
                    // The rest of this touch is ours: consumed before the children see it, so
                    // lifting the finger doesn't also count as a tap (the title's debug toggle).
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
                is PointerInputChange -> {
                    swipe.reset()
                    swipe.drag(overSlop).let { if (it != 0) steps(it) }
                    horizontalDrag(outcome.id) { change ->
                        val dx = change.positionChange().x  // before consume(): it zeroes this
                        change.consume()
                        val n = swipe.drag(dx)
                        if (n != 0) steps(n)
                    }
                }
                else -> Unit  // lifted or taken by a child: a tap, not ours
            }
        }
    }
}

private object SwipeOutcome {
    val Cancelled = Any()
}

/** The display's big name ("Brook", "Kit", "Pattern 3"); a tap toggles the debug panel. */
@Composable
fun DisplayTitle(text: String, debug: DebugSlot, modifier: Modifier = Modifier) {
    val d = LocalDesign.current
    val toggle by rememberUpdatedState(debug.toggle)
    BasicText(
        text,
        modifier.pointerInput(Unit) { detectTapGestures(onTap = { toggle() }) },
        style = d.label(20f, GrooveColors.Text, FontWeight.Bold),
        maxLines = 1,
    )
}

/** The grey line after the title, or a warning when the audio stream did not open. */
@Composable
fun DisplaySubtitle(text: String, audioStarted: Boolean, modifier: Modifier = Modifier) {
    val d = LocalDesign.current
    BasicText(
        if (audioStarted) text else "audio stream failed to open",
        modifier,
        style = d.label(13f, if (audioStarted) GrooveColors.TextDim else GrooveColors.Warn),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The display's corner label naming the active layer; amber on the Shift layer. */
@Composable
fun LayerLabel(text: String, shifted: Boolean, modifier: Modifier = Modifier) {
    val d = LocalDesign.current
    BasicText(text, modifier, style = d.value(12f, if (shifted) GrooveColors.Shift else GrooveColors.TextDim), maxLines = 1)
}

/** The session's key and scale ("A minor") on a display while a scale is on (F8). */
@Composable
fun KeyName(text: String, modifier: Modifier = Modifier) {
    val d = LocalDesign.current
    BasicText(text, modifier, style = d.value(13f, GrooveColors.Shift, FontWeight.Medium), maxLines = 1)
}

/** One line under the picture: each strip's colour, label and value. */
@Composable
fun Legend(strips: List<ParamSpec?>, host: StripHost) {
    val d = LocalDesign.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(d.dp(8))) {
        strips.forEachIndexed { i, spec ->
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(d.dp(10))
                        .clip(RoundedCornerShape(d.dp(3)))
                        .background(GrooveColors.Strips[i]),
                )
                Spacer(Modifier.size(d.dp(6)))
                val text = spec?.let { "${it.label} ${host.format(it, host.value(it))}" } ?: "—"
                BasicText(
                    text,
                    style = d.value(13f, GrooveColors.Text),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
