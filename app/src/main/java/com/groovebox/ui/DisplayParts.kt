package com.groovebox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.max
import com.groovebox.engine.ParamSpec
import com.groovebox.ui.model.LayerText
import com.groovebox.ui.model.SlotSwipe
import com.groovebox.ui.theme.Design
import com.groovebox.ui.theme.GrooveColors
import com.groovebox.ui.theme.GrooveRadius
import com.groovebox.ui.theme.GrooveSpace
import com.groovebox.ui.theme.GrooveText
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
            // Review 4 (agent 2): a child took this touch (the Undo chip, a kit cell, the title):
            // no swipe and no long-press for it.
            if (down.isConsumed) return@awaitEachGesture
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
        style = d.title(),
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
        style = d.body(if (audioStarted) GrooveColors.TextDim else GrooveColors.Warn),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * The display's corner label (CX review R8), one form on every screen: [layer]
 * ([LayerText.MAIN], [LayerText.SHIFT], [LayerText.STEP]) and what the strips edit, as
 * "MAIN · osc" or "STEP · step 5". Amber on the Shift layer (amber = Shift only). Hints go to
 * the footers, not here.
 */
@Composable
fun LayerLabel(layer: String, focus: String, modifier: Modifier = Modifier) {
    val d = LocalDesign.current
    BasicText(
        LayerText.of(layer, focus),
        modifier,
        style = d.meta(if (layer == LayerText.SHIFT) GrooveColors.Shift else GrooveColors.TextDim),
        maxLines = 1,
    )
}

/** MAIN or SHIFT by the Shift state: `LayerLabel(shifted, "osc")`. */
@Composable
fun LayerLabel(shifted: Boolean, focus: String, modifier: Modifier = Modifier) =
    LayerLabel(if (shifted) LayerText.SHIFT else LayerText.MAIN, focus, modifier)

/** Pre-build-22 form (free text): kept so screens migrate one at a time; same style as the others. */
@Deprecated("Use LayerLabel(layer, focus) or LayerLabel(shifted, focus): one form, MAIN · osc")
@Composable
fun LayerLabel(text: String, shifted: Boolean, modifier: Modifier = Modifier) {
    val d = LocalDesign.current
    BasicText(text, modifier, style = d.meta(if (shifted) GrooveColors.Shift else GrooveColors.TextDim), maxLines = 1)
}

/** The session's key and scale ("A minor") on a display while a scale is on (F8). */
@Composable
fun KeyName(text: String, modifier: Modifier = Modifier) {
    val d = LocalDesign.current
    // Cream, not amber: amber is Shift and held keys only (CX review R6).
    BasicText(text, modifier, style = d.valueText(GrooveColors.Text, FontWeight.Medium), maxLines = 1)
}

/** One line under the picture: each strip's colour, label and value. */
@Composable
fun Legend(strips: List<ParamSpec?>, host: StripHost) {
    val d = LocalDesign.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(d.dp(GrooveSpace.ROW))) {
        strips.forEachIndexed { i, spec ->
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(d.dp(10))
                        .clip(d.shape(GrooveRadius.CELL))
                        .background(GrooveColors.Strips[i]),
                )
                Spacer(Modifier.size(d.dp(GrooveSpace.PAD)))
                val text = spec?.let { "${it.label} ${host.format(it, host.value(it))}" } ?: "—"
                BasicText(
                    text,
                    style = d.valueText(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * A chip at the left of a display's footer (build-22: Tape's Undo, the Mixer's Mute and Solo),
 * where the left thumb reaches it. [active] shows it on: tinted, outlined and lettered in
 * [activeColour]. Touch handling comes in with [modifier]; [enabled] false dims it.
 *
 * The chip looks [FOOTER_CHIP_HEIGHT] tall but takes touches over at least [Design.MinTouch]
 * (ADR 0002): the touch area is laid out past the chip's top and bottom edges, so the footer
 * row keeps the chip's height.
 */
@Composable
fun FooterChip(
    text: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    activeColour: Color = GrooveColors.Select,
    enabled: Boolean = true,
    leading: (@Composable (Color) -> Unit)? = null,
) {
    val d = LocalDesign.current
    val shape = d.shape(GrooveRadius.CHIP)
    val ink = if (active) activeColour else GrooveColors.Text
    val shown = d.dp(FOOTER_CHIP_HEIGHT)
    val touch = max(Design.MinTouch, shown)
    Box(
        Modifier
            .layout { measurable, constraints ->
                val touchPx = touch.roundToPx()
                val shownPx = shown.roundToPx()
                val placeable = measurable.measure(constraints.copy(minHeight = touchPx, maxHeight = touchPx))
                // Report the chip's height; the touch area hangs over it evenly.
                layout(placeable.width, shownPx) { placeable.place(0, (shownPx - placeable.height) / 2) }
            }
            .then(modifier),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .height(shown)
                .alpha(if (enabled) 1f else GrooveColors.INERT_ALPHA)
                .clip(shape)
                .background(if (active) activeColour.copy(alpha = 0.14f) else GrooveColors.ChipFill)
                .then(if (active) Modifier.border(d.dp(1.5f), activeColour, shape) else Modifier)
                .padding(horizontal = d.dp(12)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(d.dp(GrooveSpace.PAD)),
        ) {
            leading?.invoke(ink)
            BasicText(text, style = d.label(GrooveText.BODY, ink, FontWeight.Bold), maxLines = 1)
        }
    }
}

/** A footer chip's height in design units (the mockups' 30, plus a little for the thumb). */
const val FOOTER_CHIP_HEIGHT = 32f

/** A footer's hint line: what the gestures do (layer labels stay short). */
@Composable
fun FooterHint(text: String, modifier: Modifier = Modifier, colour: Color = GrooveColors.TextDim) {
    val d = LocalDesign.current
    BasicText(text, modifier, style = d.meta(colour), maxLines = 1, overflow = TextOverflow.Ellipsis)
}
