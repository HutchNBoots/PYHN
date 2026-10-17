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
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
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
import com.groovebox.ui.theme.GrooveRadius
import com.groovebox.ui.theme.GrooveSpace
import com.groovebox.ui.theme.GrooveText
import com.groovebox.ui.theme.LocalDesign
import kotlinx.coroutines.isActive

private object MixerColors {
    val Mute = GrooveColors.Strips[0]
    val Track = GrooveColors.Strips[3]
    val Fx = GrooveColors.Strips[2]
    /** Cream, not amber: amber is Shift only (CX review R6). */
    val Master = GrooveColors.Text
    /** Solo is a selection-like state: cream (amber stays Shift's). */
    val Solo = GrooveColors.Select
    val ColumnSelected = Color(0xFF26262B)
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
    val soloOn = state.mix.soloed.isNotEmpty()
    val subtitle = MixerLayout.title(pad, state::instrumentName).let { title ->
        val base = when {
            muted -> "$title · muted"
            pad == MixerPad.Delay -> "$title · ${state.mix.delayTimeText()}"
            else -> title
        }
        if (soloOn) "$base · solo on" else base
    }

    Column(
        modifier
            .clip(d.shape(GrooveRadius.PANEL))
            .background(GrooveColors.Panel)
            .padding(horizontal = d.dp(GrooveSpace.DISPLAY_H), vertical = d.dp(GrooveSpace.DISPLAY_V)),
        verticalArrangement = Arrangement.spacedBy(d.dp(GrooveSpace.ROW)),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(d.dp(GrooveSpace.ZONE))) {
            DisplayTitle("Mixer", debug, Modifier.alignByBaseline())
            DisplaySubtitle(subtitle, audioStarted, Modifier.weight(1f).alignByBaseline())
            LayerLabel(shifted, layerFocus(pad), Modifier.alignByBaseline())
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Levels(state, Modifier.fillMaxSize())
            if (debug.open) debug.panel()
        }
        MixerFooter(state, shifted)
    }
}

/** What the strips edit, for the layer label: "channel 2", "T1", "delay". */
private fun layerFocus(pad: MixerPad): String = when (pad) {
    is MixerPad.Slot -> "channel ${pad.slot + 1}"
    is MixerPad.Track -> MixerLayout.shortName(pad)
    MixerPad.Delay -> "delay"
    MixerPad.Reverb -> "reverb"
    MixerPad.Master -> "master"
}

/**
 * Build-22: Mute and Solo chips for the selected channel at the left of the footer (left thumb,
 * ADR 0003), then the hint line. Solo is UI-only for now: it mutes every other channel.
 */
@Composable
private fun MixerFooter(state: GrooveState, shifted: Boolean) {
    val d = LocalDesign.current
    val mixer = state.mixer
    val pad = mixer.selected
    val channel = MixerLayout.channelOf(pad)
    val muted = mixer.selectedMuted
    val soloed = mixer.selectedSoloed
    val name = MixerLayout.shortName(pad)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.dp(GrooveSpace.ROW))) {
        FooterChip(
            "Mute",
            Modifier
                .semantics {
                    contentDescription = if (channel == null) "Mute, not for $name" else if (muted) "Unmute $name" else "Mute $name"
                    selected = muted
                    if (channel == null) disabled()
                }
                .pointerInput(channel) {
                    if (channel == null) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown()
                        mixer.toggleMuteSelected()
                    }
                },
            active = muted,
            activeColour = MixerColors.Mute,
            enabled = channel != null,
        )
        FooterChip(
            "Solo",
            Modifier
                .semantics {
                    contentDescription = if (channel == null) "Solo, not for $name" else if (soloed) "Unsolo $name" else "Solo $name, mutes the others"
                    selected = soloed
                    if (channel == null) disabled()
                }
                .pointerInput(channel) {
                    if (channel == null) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown()
                        mixer.toggleSoloSelected()
                    }
                },
            active = soloed,
            activeColour = MixerColors.Solo,
            enabled = channel != null,
        )
        FooterHint(
            when {
                shifted && pad == MixerPad.Delay -> "sync / div / return · Shift + tap a pad mutes it"
                shifted -> "Shift + tap a pad mutes it"
                else -> "tap a bar or pad to select · M mute · S solo · line = fader · red = clip"
            },
            Modifier.weight(1f),
        )
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
            val soloed = channel != null && mix.isSoloed(channel)
            // Solo wins (build-22 review): a soloed channel plays and shows S even if muted.
            val muted = channel != null && mix.muted(channel) && !soloed
            val silenced = channel != null && mix.silencedBySolo(channel)
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
                    .clip(d.shape(GrooveRadius.CHIP))
                    .background(if (isSelected) MixerColors.ColumnSelected else Color.Transparent)
                    .alpha(if (silenced) SILENCED_ALPHA else 1f)
                    .semantics {
                        contentDescription = "${MixerLayout.title(pad, state::instrumentName)} level $text" +
                            (if (muted) ", muted" else "") + (if (soloed) ", soloed" else "") +
                            (if (silenced) ", silent while another channel is soloed" else "")
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
                when {
                    muted -> Badge("M", MixerColors.Mute, d.caption(GrooveColors.Ground, FontWeight.Medium))
                    soloed -> Badge("S", MixerColors.Solo, d.caption(GrooveColors.Ground, FontWeight.Medium))
                    else -> BasicText(text, style = d.caption(), maxLines = 1, overflow = TextOverflow.Clip)
                }
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
                        },
                )
                BasicText(
                    MixerLayout.shortName(pad),
                    style = d.caption(if (isSelected) GrooveColors.Text else MixerColors.Sub, if (isSelected) FontWeight.Medium else FontWeight.Normal),
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}

/** A small letter badge: M (muted, coral) or S (soloed, cream). */
@Composable
private fun Badge(text: String, colour: Color, style: TextStyle, modifier: Modifier = Modifier) {
    val d = LocalDesign.current
    BasicText(
        text,
        modifier
            .clip(d.shape(GrooveRadius.CELL))
            .background(colour)
            .padding(horizontal = d.dp(GrooveSpace.GROUP)),
        style = style,
        maxLines = 1,
    )
}

/** A channel that is silent only because another one is soloed: dimmed, not marked M. */
private const val SILENCED_ALPHA = 0.6f

/**
 * The Mixer screen's bottom zone (F6.5): 15 pads. A tap selects a pad for the strips (a slot
 * pad also selects the slot, a tape pad the tape track: build-22); Shift + tap mutes a slot or
 * tape channel (a coral M and a dimmed pad). A soloed channel shows a cream S.
 */
@Composable
fun MixerPads(state: GrooveState, modifier: Modifier = Modifier) {
    val d = LocalDesign.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(d.dp(GrooveSpace.PAD))) {
        for (pad in MixerLayout.pads) MixerPadButton(state, pad, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun MixerPadButton(state: GrooveState, pad: MixerPad, modifier: Modifier) {
    val d = LocalDesign.current
    val mixer = state.mixer
    val isSelected = mixer.selected == pad
    val channel = MixerLayout.channelOf(pad)
    val soloed = channel != null && state.mix.isSoloed(channel)
    val userMuted = channel != null && state.mix.muted(channel)
    // Solo wins (build-22 review): a soloed channel plays and shows S even if muted.
    val muted = userMuted && !soloed
    val silenced = channel != null && state.mix.silencedBySolo(channel)
    val sub = when (pad) {
        is MixerPad.Slot -> state.instrumentName(pad.slot)
        is MixerPad.Track -> "tape"
        MixerPad.Delay -> "delay"
        MixerPad.Reverb -> "reverb"
        MixerPad.Master -> "master"
    }
    val shape = d.shape(GrooveRadius.PAD)
    val name = MixerLayout.shortName(pad)
    val ink = if (isSelected) GrooveColors.Ground else GrooveColors.Text
    Box(
        modifier
            .clip(shape)
            .background(if (isSelected) GrooveColors.Text else GrooveColors.Panel)
            .border(d.dp(if (muted) 2 else 1), if (muted) MixerColors.Mute else MixerColors.PadEdge, shape)
            .semantics {
                contentDescription = "${MixerLayout.title(pad, state::instrumentName)}${if (muted) ", muted" else ""}" +
                    (if (soloed) ", soloed" else "") + (if (silenced) ", silent while another channel is soloed" else "") +
                    (if (channel != null) ". Shift and tap to ${if (userMuted) "unmute" else "mute"}" else "")
                selected = isSelected
            }
            .pointerInput(pad) {
                awaitEachGesture {
                    awaitFirstDown()
                    state.mixer.tap(pad)
                }
            }
            .padding(d.dp(GrooveSpace.PAD)),
    ) {
        Column(Modifier.fillMaxSize().alpha(if (muted) 0.5f else if (silenced) SILENCED_ALPHA else 1f)) {
            // One pad label style (CX review R12): mono 13 medium, as the step pads.
            BasicText(name, style = d.padLabel(ink), maxLines = 1)
            Spacer(Modifier.weight(1f))
            BasicText(
                sub,
                style = d.label(GrooveText.CAPTION, if (isSelected) GrooveColors.Ground else MixerColors.Sub).copy(textAlign = TextAlign.Start),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // A colour tick: which kind of channel this is.
            Box(
                Modifier
                    .padding(top = d.dp(4))
                    .fillMaxWidth()
                    .clip(d.shape(GrooveRadius.HAIRLINE))
                    .background(padColour(pad).copy(alpha = if (isSelected) 0.9f else 0.5f))
                    .padding(top = d.dp(3)),
            )
        }
        when {
            muted -> Badge("M", MixerColors.Mute, d.meta(GrooveColors.Ground, FontWeight.Medium), Modifier.align(Alignment.TopEnd))
            soloed -> Badge("S", MixerColors.Solo, d.meta(GrooveColors.Ground, FontWeight.Medium), Modifier.align(Alignment.TopEnd))
        }
    }
}
