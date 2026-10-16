package com.groovebox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.max
import com.groovebox.engine.ParamSpec
import com.groovebox.ui.model.SynthSection
import com.groovebox.ui.theme.Design
import com.groovebox.ui.theme.GrooveColors
import com.groovebox.ui.theme.LocalDesign

/**
 * The Synth screen's middle row (docs/mockups/Main.dc.html, ADR 0002): section rail,
 * display and the four colour strips, for one synth slot.
 */
@Composable
fun SynthMiddleRow(
    controller: SynthController,
    audioStarted: Boolean,
    debug: DebugSlot,
    modifier: Modifier = Modifier,
    keyName: String? = null,
) {
    val d = LocalDesign.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(d.dp(10))) {
        Rail(
            labels = SynthSection.entries.map { it.label },
            selected = controller.section.ordinal,
            description = { "${SynthSection.entries[it].label} section" },
            onSelect = { controller.section = SynthSection.entries[it] },
            modifier = Modifier.width(d.dp(64)).fillMaxHeight(),
        )
        SynthDisplay(
            controller = controller,
            audioStarted = audioStarted,
            debug = debug,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            keyName = keyName,
        )
        StripRow(controller.strips, controller)
    }
}

/** The four colour strips at the right of the middle row. */
@Composable
fun StripRow(
    strips: List<ParamSpec?>,
    host: StripHost,
    disabledLabels: List<String> = List(4) { "—" },
    disabledValues: List<String?> = List(4) { null },
) {
    val d = LocalDesign.current
    Row(
        Modifier.width(d.dp(332)).fillMaxHeight(),
        horizontalArrangement = Arrangement.spacedBy(d.dp(8)),
    ) {
        strips.forEachIndexed { i, spec ->
            ColourStrip(
                i, spec, host, Modifier.weight(1f).fillMaxHeight(),
                disabledLabel = disabledLabels[i], disabledValue = disabledValues[i],
            )
        }
    }
}

/** Height of the middle row: the mockup's 220, and at least four 44 dp rail buttons. */
@Composable
fun middleRowHeight(): Dp {
    val d = LocalDesign.current
    return max(d.dp(220), Design.MinTouch * 4 + d.dp(4) * 3)
}

/**
 * A column of four buttons beside the display: the synth's sections, or the sequencer's
 * pages. One tap each; selection happens on touch-down.
 */
@Composable
fun Rail(
    labels: List<String>,
    selected: Int,
    description: (Int) -> String,
    onSelect: (Int) -> Unit,
    modifier: Modifier,
    mono: Boolean = false,
) {
    val d = LocalDesign.current
    val select by rememberUpdatedState(onSelect)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(d.dp(4))) {
        labels.forEachIndexed { i, label ->
            val isSelected = selected == i
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(d.dp(10)))
                    .background(if (isSelected) GrooveColors.Text else GrooveColors.Panel)
                    .semantics {
                        contentDescription = description(i)
                        this.selected = isSelected
                    }
                    .pointerInput(i) {
                        awaitEachGesture {
                            awaitFirstDown()
                            select(i)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                val colour = if (isSelected) GrooveColors.Ground else if (mono) GrooveColors.TextDim else GrooveColors.Text
                BasicText(
                    label,
                    style = if (mono) {
                        d.value(15f, colour, if (isSelected) FontWeight.Medium else FontWeight.Normal)
                    } else {
                        d.label(14f, colour, if (isSelected) FontWeight.Bold else FontWeight.Medium)
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Owner decision 1: long-press a synth slot to show this thin strip of presets over the keys.
 * A tap loads a preset and keeps the strip open for auditioning; touching anywhere else
 * (or tapping the slot) closes it. It never blocks the keys outside its own area.
 */
@Composable
fun PresetStrip(controller: SynthController, modifier: Modifier) {
    val d = LocalDesign.current
    Row(
        modifier
            .height(max(d.dp(44), Design.MinTouch))
            .clip(RoundedCornerShape(d.dp(10)))
            .background(GrooveColors.Ground.copy(alpha = 0.96f))
            .semantics { contentDescription = "Presets for slot ${controller.slot + 1}" }
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = d.dp(4), vertical = d.dp(4)),
        horizontalArrangement = Arrangement.spacedBy(d.dp(4)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        controller.presetNames.forEachIndexed { i, name ->
            val isSelected = i == controller.presetIndex
            Box(
                Modifier
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(d.dp(8)))
                    .background(if (isSelected) GrooveColors.Strips[0] else GrooveColors.Panel)
                    .semantics { selected = isSelected }
                    .pointerInput(controller, i) { detectTapGestures(onTap = { controller.loadPreset(i) }) }
                    .padding(horizontal = d.dp(12)),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    name,
                    style = d.label(13f, if (isSelected) GrooveColors.Ground else GrooveColors.Text),
                    maxLines = 1,
                )
            }
        }
    }
}
