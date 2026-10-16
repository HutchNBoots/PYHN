package com.groovebox.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.groovebox.engine.ParamSpec
import com.groovebox.ui.theme.GrooveColors
import com.groovebox.ui.theme.LocalDesign

/** The debug overlay (F0.2, F0.4) as handed to a display: tapping the display's title toggles it. */
@Immutable
class DebugSlot(val open: Boolean, val toggle: () -> Unit, val panel: @Composable () -> Unit)

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
