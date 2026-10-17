package com.groovebox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.groovebox.ui.model.DrumLayout
import com.groovebox.ui.theme.GrooveColors
import com.groovebox.ui.theme.LocalDesign

/** A kit grid cell at rest (docs/mockups/DrumProposed.dc.html). */
private val KitCell = Color(0xFF26262B)

/**
 * The kit grid (build-21), the same on the Seq drum slot and the Drum screen: 4 × 4, Kick
 * bottom-left and upward in kit order ([DrumLayout.gridKey]), the short names. The selected
 * sound is cream; a dot marks sounds the shown pattern uses. Touch-down selects the sound and
 * auditions it, never recording, even with Record armed ([GrooveState.laneDown]).
 */
@Composable
fun KitGrid(state: GrooveState, modifier: Modifier = Modifier) {
    val d = LocalDesign.current
    val used = state.notesInPattern()
    val selected = state.laneKey
    Column(modifier, verticalArrangement = Arrangement.spacedBy(d.dp(4))) {
        for (row in 0 until 4) {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(d.dp(4))) {
                for (col in 0 until 4) {
                    val key = DrumLayout.gridKey(row, col)
                    KitCell(state, key, key == selected, DrumLayout.noteOf(key) in used)
                }
            }
        }
    }
}

@Composable
private fun RowScope.KitCell(state: GrooveState, key: Int, isSelected: Boolean, used: Boolean) {
    val d = LocalDesign.current
    Box(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(RoundedCornerShape(d.dp(8)))
            .background(if (isSelected) GrooveColors.Text else KitCell)
            .semantics {
                contentDescription = state.drum.soundName(key) +
                    (if (isSelected) ", selected" else ", touch to select and hear it") +
                    (if (used) ", in this pattern" else "")
                selected = isSelected
            }
            .pointerInput(key) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    state.laneDown(key)
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed) break
                        }
                    } finally {
                        state.laneUp(key)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            DrumLayout.shortName(key),
            style = d.value(
                11f,
                if (isSelected) GrooveColors.Ground else GrooveColors.Text,
                if (isSelected) FontWeight.Medium else FontWeight.Normal,
            ),
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
        if (used) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(d.dp(4))
                    .size(d.dp(5))
                    .clip(CircleShape)
                    .background(if (isSelected) GrooveColors.Ground else GrooveColors.TextDim),
            )
        }
    }
}
