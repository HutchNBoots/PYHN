package com.groovebox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.groovebox.engine.ParamSpec
import com.groovebox.ui.model.MeterScale
import com.groovebox.ui.model.MixFormat
import com.groovebox.ui.model.MixKeys
import com.groovebox.ui.model.MixerLayout
import com.groovebox.ui.model.MixerPad
import com.groovebox.ui.theme.GrooveColors
import com.groovebox.ui.theme.LocalDesign
import kotlinx.coroutines.isActive

private object MixerColors {
    val Mute = GrooveColors.Strips[0]
    val Track = GrooveColors.Strips[3]
    val Fx = GrooveColors.Strips[2]
    val Master = GrooveColors.Strips[1]
    val PadEdge = Color(0xFF26262C)
    val Sub = Color(0xFF7A7872)
    val Clip = GrooveColors.Warn
    val ClipOff = GrooveColors.Divider
    val Marker = GrooveColors.Text
}

private fun padColour(pad: MixerPad): Color = when (pad) {
    is MixerPad.Slot -> GrooveColors.Text
    is MixerPad.Track -> MixerColors.Track
    MixerPad.Delay, MixerPad.Reverb -> MixerColors.Fx
    MixerPad.Master -> MixerColors.Master
}

/** The Mixer screen's middle row (F6.5): the levels display and the selected pad's strips. */
@Composable
fun MixerMiddleRow(state: GrooveState, audioStarted: Boolean, debug: DebugSlot, modifier: Modifier = Modifier) {
    val d = LocalDesign.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(d.dp(10))) {
        MixerDisplay(state, audioStarted, debug, Modifier.weight(1f).fillMaxHeight())
        StripRow(state.mixer.strips, state.mixer)
    }
}

@Composable
private fun MixerDisplay(state: GrooveState, audioStarted: Boolean, debug: DebugSlot, modifier: Modifier) {
    val d = LocalDesign.current
    val mixer = state.mixer
    val pad = mixer.selected
    val shifted = state.shift.shifted
    val channel = MixerLayout.channelOf(pad)
    val muted = channel != null && state.mix.muted(channel)
    val subtitle = MixerLayout.title(pad, state::instrumentName).let { title ->
        when {
            muted -> "$title · muted"
            pad == MixerPad.Delay -> "$title · ${state.mix.delayTimeText()}"
            else -> title
        }
    }
    val layer = when {
        !shifted -> "MAIN · tap a bar or pad to select"
        pad == MixerPad.Delay -> "SHIFT · sync / div / return"
        else -> "SHIFT · tap a pad to mute"
    }

    Column(
        modifier
            .clip(RoundedCornerShape(d.dp(16)))
            .background(GrooveColors.Panel)
            .padding(horizontal = d.dp(18), vertical = d.dp(12)),
        verticalArrangement = Arrangement.spacedBy(d.dp(8)),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(d.dp(10))) {
            DisplayTitle("Mixer", debug, Modifier.alignByBaseline())
            DisplaySubtitle(subtitle, audioStarted, Modifier.weight(1f).alignByBaseline())
            LayerLabel(layer, shifted, Modifier.alignByBaseline())
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Levels(state, Modifier.fillMaxSize())
            if (debug.open) debug.panel()
        }
        Legend(mixer.strips, mixer)
    }
}

/**
 * One column per pad: the live peak level (NativeEngine.meters, dB-scaled like the fader:
 * -60..+6 dB) as a bar, the fader setting as a marker line across it, a clip light on top
 * (coral for ~1 s after the channel reached full scale), the setting's value above and the name
 * below. Muted channels are greyed with a coral M. Tapping a column selects that channel
 * (Shift + tap mutes), like the pads.
 *
 * The meters are polled every frame while this shows; only the bars' draw phase reads them,
 * so a new reading redraws the bars without recomposing anything.
 */
@Composable
private fun Levels(state: GrooveState, modifier: Modifier) {
    val d = LocalDesign.current
    val mix = state.mix
    val selected = state.mixer.selected
    val volume = mix.channelSpec(MixKeys.VOLUME)
    val meters = state.mixer.meters
    LaunchedEffect(meters) {
        while (isActive) withFrameNanos { meters.poll() }
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(d.dp(4))) {
        for (pad in MixerLayout.pads) {
            val channel = MixerLayout.channelOf(pad)
            val spec: ParamSpec? = when (pad) {
                is MixerPad.Slot, is MixerPad.Track -> volume
                MixerPad.Delay -> mix.mixSpec(MixKeys.DELAY_RETURN)
                MixerPad.Reverb -> mix.mixSpec(MixKeys.REVERB_RETURN)
                MixerPad.Master -> mix.mixSpec(MixKeys.MASTER)
            }
            val value = when {
                spec == null -> 0.0
                channel != null -> mix.channel(channel, spec)
                else -> mix.mix(spec)
            }
            // On the meter's dB scale (v1 fix 4): a DLY / REV return of 50% sits at -6 dB.
            val setting = spec?.let { MeterScale.markerHeight(it, value) } ?: 0f
            val muted = channel != null && mix.muted(channel)
            val text = when {
                spec == null -> "—"
                spec.unit == "dB" -> MixFormat.decibels(value, spec.min).removeSuffix(" dB")
                else -> MixFormat.format(spec, value)
            }
            val isSelected = pad == selected
            val colour = if (muted) GrooveColors.Divider else padColour(pad)
            // Each column is also a target for the left thumb: a tap selects the channel and
            // Shift + tap mutes it, exactly like its pad (two-thumb grip, ADR 0003).
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .semantics {
                        contentDescription = "${MixerLayout.title(pad, state::instrumentName)} level $text" +
                            (if (muted) ", muted" else "")
                        this.selected = isSelected
                    }
                    .pointerInput(pad) {
                        awaitEachGesture {
                            awaitFirstDown()
                            state.mixer.tap(pad)
                        }
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(d.dp(3)),
            ) {
                BasicText(
                    if (muted) "M" else text,
                    style = d.value(10f, if (muted) MixerColors.Mute else GrooveColors.TextDim, if (muted) FontWeight.Medium else FontWeight.Normal),
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(0.55f)
                        .drawBehind {
                            meters.version  // read here, so each new reading redraws this bar
                            val r = CornerRadius(d.px(3f))
                            // Clip light on top, then the meter well below it.
                            val clipH = d.px(5f)
                            val top = clipH + d.px(3f)
                            val well = (size.height - top).coerceAtLeast(0f)
                            drawRoundRect(
                                if (meters.readings.clipped(pad)) MixerColors.Clip else MixerColors.ClipOff,
                                size = Size(size.width, clipH),
                                cornerRadius = CornerRadius(d.px(2f)),
                            )
                            drawRoundRect(GrooveColors.Ground, Offset(0f, top), Size(size.width, well), r)
                            val h = well * meters.readings.height(pad)
                            if (h > 0f) drawRoundRect(colour, Offset(0f, size.height - h), Size(size.width, h), r)
                            // The fader setting: a line across the bar, with a ground-coloured
                            // edge so it reads over the meter.
                            val y = top + well * (1f - setting.coerceIn(0f, 1f))
                            val over = d.px(3f)
                            val line = d.px(2f)
                            drawRect(
                                GrooveColors.Ground,
                                Offset(-over, y - line),
                                Size(size.width + 2 * over, 2 * line),
                            )
                            drawRect(
                                if (muted) GrooveColors.TextDim else MixerColors.Marker,
                                Offset(-over, y - line / 2),
                                Size(size.width + 2 * over, line),
                            )
                            if (isSelected) {
                                drawRoundRect(
                                    GrooveColors.Shift,
                                    topLeft = Offset(0f, size.height - d.px(2f)),
                                    size = Size(size.width, d.px(2f)),
                                )
                            }
                        },
                )
                BasicText(
                    MixerLayout.shortName(pad),
                    style = d.value(10f, if (isSelected) GrooveColors.Text else MixerColors.Sub, if (isSelected) FontWeight.Medium else FontWeight.Normal),
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}

/**
 * The Mixer screen's bottom zone (F6.5): 15 pads. A tap selects a pad for the strips;
 * Shift + tap mutes a slot or tape channel (shown by a coral M and a dimmed pad).
 */
@Composable
fun MixerPads(state: GrooveState, modifier: Modifier = Modifier) {
    val d = LocalDesign.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(d.dp(5))) {
        for (pad in MixerLayout.pads) MixerPadButton(state, pad, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun MixerPadButton(state: GrooveState, pad: MixerPad, modifier: Modifier) {
    val d = LocalDesign.current
    val mixer = state.mixer
    val isSelected = mixer.selected == pad
    val channel = MixerLayout.channelOf(pad)
    val muted = channel != null && state.mix.muted(channel)
    val sub = when (pad) {
        is MixerPad.Slot -> state.instrumentName(pad.slot)
        is MixerPad.Track -> "tape"
        MixerPad.Delay -> "delay"
        MixerPad.Reverb -> "reverb"
        MixerPad.Master -> "master"
    }
    val shape = RoundedCornerShape(d.dp(12))
    val name = MixerLayout.shortName(pad)
    val ink = if (isSelected) GrooveColors.Ground else GrooveColors.Text
    Box(
        modifier
            .clip(shape)
            .background(if (isSelected) GrooveColors.Text else GrooveColors.Panel)
            .border(d.dp(if (muted) 2 else 1), if (muted) MixerColors.Mute else MixerColors.PadEdge, shape)
            .semantics {
                contentDescription = "${MixerLayout.title(pad, state::instrumentName)}${if (muted) ", muted" else ""}" +
                    (if (channel != null) ". Shift and tap to ${if (muted) "unmute" else "mute"}" else "")
                selected = isSelected
            }
            .pointerInput(pad) {
                awaitEachGesture {
                    awaitFirstDown()
                    state.mixer.tap(pad)
                }
            }
            .padding(d.dp(6)),
    ) {
        Column(Modifier.fillMaxSize().alpha(if (muted) 0.5f else 1f)) {
            BasicText(name, style = d.value(15f, ink, FontWeight.Medium), maxLines = 1)
            Spacer(Modifier.weight(1f))
            BasicText(
                sub,
                style = d.label(10f, if (isSelected) GrooveColors.Ground else MixerColors.Sub).copy(textAlign = TextAlign.Start),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // A colour tick: which kind of channel this is.
            Box(
                Modifier
                    .padding(top = d.dp(4))
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(d.dp(2)))
                    .background(padColour(pad).copy(alpha = if (isSelected) 0.9f else 0.5f))
                    .padding(top = d.dp(3)),
            )
        }
        if (muted) {
            BasicText(
                "M",
                Modifier
                    .align(Alignment.TopEnd)
                    .clip(RoundedCornerShape(d.dp(4)))
                    .background(MixerColors.Mute)
                    .padding(horizontal = d.dp(4)),
                style = d.value(11f, GrooveColors.Ground, FontWeight.Medium),
            )
        }
    }
}
