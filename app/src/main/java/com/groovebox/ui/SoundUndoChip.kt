package com.groovebox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.groovebox.ui.model.UndoHold
import com.groovebox.ui.theme.GrooveColors
import com.groovebox.ui.theme.GrooveRadius
import com.groovebox.ui.theme.LocalDesign
import kotlinx.coroutines.delay

/** The chip's resting fill (the Seq footer's cell colour). */
private val ChipFill = Color(0xFF2B2B31)

/**
 * Undo of sound edits (build-22), at the bottom-left of the Synth and Drum displays (left
 * thumb): a tap undoes, a hold redoes once (the chip briefly reads "Redo"), Shift plays no part
 * ([UndoHold], as on Seq and Tape). The synth slots and the kit share one history
 * ([GrooveState.soundHistory]). Its touches are consumed, so they never swipe the slot or open
 * the presets: [onHeld] says while a finger is on it, so the display's long-press can stand down.
 */
@Composable
fun SoundUndoChip(state: GrooveState, modifier: Modifier = Modifier, onHeld: (Boolean) -> Unit = {}) {
    val d = LocalDesign.current
    val haptics = LocalHapticFeedback.current
    val hold = remember { UndoHold() }
    val held by rememberUpdatedState(onHeld)
    var readsRedo by remember { mutableStateOf(false) }
    var flash by remember { mutableIntStateOf(0) }
    LaunchedEffect(flash) {
        if (flash == 0) return@LaunchedEffect
        delay(hold.flashMs)
        readsRedo = false
    }
    val canUndo = state.soundCanUndo
    val canRedo = state.soundCanRedo
    val label = if (readsRedo) "Redo" else "Undo"
    Box(
        modifier
            .height(d.dp(26))
            .width(d.dp(58))
            .alpha(if (canUndo || canRedo) 1f else GrooveColors.INERT_ALPHA)
            .clip(d.shape(GrooveRadius.CHIP))
            .background(if (readsRedo) GrooveColors.Text else ChipFill)
            .semantics {
                contentDescription = "Undo sound edit" + (if (canUndo) "" else ", nothing to undo") +
                    ". Hold to redo" + (if (canRedo) "" else ", nothing to redo")
                onLongClick("Redo sound edit") { state.soundUndoPress(redo = true); true }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    held(true)
                    try {
                        hold.down(down.uptimeMillis)
                        var cancelled = false
                        var upAt = 0L
                        val released = withTimeoutOrNull(hold.longPressMs) {
                            val up = waitForUpOrCancellation()
                            if (up == null) cancelled = true else { upAt = up.uptimeMillis; up.consume() }
                        } != null
                        when {
                            cancelled -> hold.cancel()
                            released -> when (hold.up(upAt)) {
                                UndoHold.Action.Undo -> state.soundUndoPress(redo = false)
                                UndoHold.Action.Redo -> {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    state.soundUndoPress(redo = true)
                                    readsRedo = true
                                    flash++
                                }
                                null -> Unit
                            }
                            else -> {
                                if (hold.poll(down.uptimeMillis + hold.longPressMs) == UndoHold.Action.Redo) {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    state.soundUndoPress(redo = true)
                                    readsRedo = true
                                }
                                // The rest of the hold is ours (no swipe, no preset strip).
                                do {
                                    val event = awaitPointerEvent()
                                    event.changes.forEach { it.consume() }
                                } while (event.changes.any { it.pressed })
                                hold.cancel()
                                flash++
                            }
                        }
                    } finally {
                        held(false)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = d.label(12f, if (readsRedo) GrooveColors.Ground else GrooveColors.Text, FontWeight.Medium), maxLines = 1)
    }
}
