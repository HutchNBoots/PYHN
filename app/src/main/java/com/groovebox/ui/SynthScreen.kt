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
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.max
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.groovebox.ui.model.KeyOctave
import com.groovebox.ui.model.PresetGroups
import com.groovebox.ui.theme.GrooveRadius
import com.groovebox.engine.ParamSpec
import com.groovebox.ui.model.SynthSection
import com.groovebox.ui.theme.Design
import com.groovebox.ui.theme.GrooveColors
import com.groovebox.ui.theme.LocalDesign

/**
 * The Synth screen's middle row (docs/mockups/Main.dc.html, ADR 0002): section rail,
 * display and the four colour strips, for one synth slot.
 *
 * Build-22: the rail has a fifth item, Play (key, scale, keyboard octave, mode); the display's
 * long-press opens the presets, Shift + long-press saves a user preset ([onSavePreset]); the
 * sound Undo chip sits at its bottom-left ([state] given); [flash] is the swipe / save flash.
 */
@Composable
fun SynthMiddleRow(
    controller: SynthController,
    audioStarted: Boolean,
    debug: DebugSlot,
    modifier: Modifier = Modifier,
    keyName: String? = null,
    onSlotSwipe: ((Int) -> Unit)? = null,
    onPresets: (() -> Unit)? = null,
    state: GrooveState? = null,
    onSavePreset: (() -> Unit)? = null,
    flash: SlotFlash? = null,
) {
    val d = LocalDesign.current
    // A finger on the Undo chip: the display's long-press (presets, save) stands down.
    var chipHeld by remember { mutableStateOf(false) }
    val longPress: (() -> Unit)? = if (onPresets == null && onSavePreset == null) {
        null
    } else {
        {
            when {
                chipHeld -> Unit
                controller.shifted && onSavePreset != null -> onSavePreset()
                else -> onPresets?.invoke()
            }
        }
    }
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
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .then(if (onSlotSwipe != null) Modifier.slotSwipe(longPress, onSlotSwipe) else Modifier)
                .then(
                    if (onPresets != null) {
                        Modifier.semantics {
                            onLongClick("Presets for slot ${controller.slot + 1}") { onPresets(); true }
                            if (onSavePreset != null) {
                                customActions = listOf(CustomAccessibilityAction("Save as a user preset") { onSavePreset(); true })
                            }
                        }
                    } else {
                        Modifier
                    },
                ),
            keyName = keyName,
            keyRange = state?.let { KeyOctave.rangeLabel(it.keyOctave) },
            undo = state?.let { st -> { SoundUndoChip(st, onHeld = { chipHeld = it }) } },
            flash = flash,
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
 * A column of buttons beside the display: the synth's sections (five since build-22, with
 * Play), or the sequencer's pages. One tap each; selection happens on touch-down. Each
 * button's touch area takes half of the gaps beside it, so the column has no dead bands
 * (five items in the S25+ middle row are about 37 dp each).
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
    val half = d.dp(2)
    Column(modifier) {
        labels.forEachIndexed { i, label ->
            val isSelected = selected == i
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .semantics {
                        contentDescription = description(i)
                        this.selected = isSelected
                    }
                    .pointerInput(i) {
                        awaitEachGesture {
                            awaitFirstDown()
                            select(i)
                        }
                    }
                    .padding(top = if (i == 0) d.dp(0) else half, bottom = if (i == labels.lastIndex) d.dp(0) else half)
                    .clip(RoundedCornerShape(d.dp(10)))
                    .background(if (isSelected) GrooveColors.Text else GrooveColors.Panel),
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
 * Owner decision 1: long-press a synth slot (or the display) to show this strip of presets over
 * the keys. A tap loads a preset and keeps the strip open for auditioning; playing the keys keeps
 * it open too; it closes on a tap outside the strip and keys, on the slot, or on its own × at the
 * right end ([onClose]). Build-22: its backdrop covers the whole top key row; the presets sit in
 * their groups (Start, Bass, Lead, Pad, Pluck, FX, then User for saved ones, scrolled to) with
 * labels and dividers. The current preset is cream (coral stays for the selected slot).
 */
@Composable
fun PresetStrip(controller: SynthController, onClose: () -> Unit, modifier: Modifier) {
    val d = LocalDesign.current
    val close by rememberUpdatedState(onClose)
    val groups = remember(controller.presetNames) { PresetGroups.groups(controller.presetNames) }
    val user = controller.library.presets
    Row(
        modifier
            .heightIn(min = max(d.dp(44), Design.MinTouch))
            .clip(d.shape(GrooveRadius.PANEL))
            .background(GrooveColors.Panel)
            .semantics { contentDescription = "Presets for slot ${controller.slot + 1}" }
            .padding(start = d.dp(8), end = d.dp(6), top = d.dp(6), bottom = d.dp(6)),
        horizontalArrangement = Arrangement.spacedBy(d.dp(6)),
    ) {
        Row(
            Modifier.weight(1f).fillMaxHeight().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(d.dp(6)),
        ) {
            groups.forEachIndexed { g, (name, indexes) ->
                if (g > 0) Divider()
                PresetGroup(name, Modifier.fillMaxHeight()) {
                    for (i in indexes) {
                        PresetChip(controller.presetNames[i], i == controller.presetIndex && controller.userPresetName == null) {
                            controller.loadPreset(i)
                        }
                    }
                }
            }
            if (user.isNotEmpty()) {
                Divider()
                PresetGroup(PresetGroups.USER, Modifier.fillMaxHeight()) {
                    for (p in user) {
                        PresetChip(p.name, p.name == controller.userPresetName) { controller.loadUserPreset(p.name) }
                    }
                }
            }
        }
        Box(
            Modifier
                .fillMaxHeight()
                .width(d.dp(40))
                .clip(d.shape(GrooveRadius.CONTROL))
                .background(PresetCell)
                .semantics { contentDescription = "Close the presets" }
                .pointerInput(Unit) { detectTapGestures(onTap = { close() }) },
            contentAlignment = Alignment.Center,
        ) {
            BasicText("×", style = d.label(18f, GrooveColors.Text, FontWeight.Bold), maxLines = 1)
        }
    }
}

private val PresetCell = Color(0xFF2B2B31)
private val GroupDivider = Color(0xFF3A3A40)

@Composable
private fun Divider() {
    val d = LocalDesign.current
    Box(Modifier.fillMaxHeight().width(d.dp(1)).background(GroupDivider))
}

@Composable
private fun PresetGroup(name: String, modifier: Modifier, chips: @Composable () -> Unit) {
    val d = LocalDesign.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(d.dp(4))) {
        BasicText(name, style = d.caption(GrooveColors.TextDim), maxLines = 1)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(d.dp(4))) { chips() }
    }
}

@Composable
private fun PresetChip(name: String, isSelected: Boolean, onTap: () -> Unit) {
    val d = LocalDesign.current
    val tap by rememberUpdatedState(onTap)
    Box(
        Modifier
            .width(d.dp(52))
            .fillMaxHeight()
            .clip(d.shape(GrooveRadius.CHIP))
            .background(if (isSelected) GrooveColors.Text else PresetCell)
            .semantics {
                contentDescription = name
                selected = isSelected
            }
            .pointerInput(Unit) { detectTapGestures(onTap = { tap() }) }
            .padding(horizontal = d.dp(2)),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            name,
            style = d.label(10.5f, if (isSelected) GrooveColors.Ground else GrooveColors.Text, if (isSelected) FontWeight.Bold else FontWeight.Medium)
                .copy(textAlign = TextAlign.Center, lineHeight = d.sp(11.5f)),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
