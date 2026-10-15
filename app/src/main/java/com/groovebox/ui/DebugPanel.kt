package com.groovebox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import com.groovebox.engine.EngineStats
import com.groovebox.ui.model.LatencySummary
import com.groovebox.ui.theme.Design
import com.groovebox.ui.theme.GrooveColors
import com.groovebox.ui.theme.LocalDesign
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Debug overlay on the display (F0.2, F0.4): tap-to-tone, the stream path, buffer, xruns,
 * callback load and the buffer-size setting. Toggled by tapping the engine name.
 */
@Composable
fun DebugPanel(
    stats: EngineStats?,
    latency: LatencySummary?,
    onBursts: (Int) -> Unit,
    onResetLatency: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val d = LocalDesign.current
    val text = GrooveColors.Text
    val warn = GrooveColors.Warn

    Column(
        modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(d.dp(8)))
            .background(GrooveColors.Ground.copy(alpha = 0.94f))
            // Swallow taps so they do not fall through to the display.
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(horizontal = d.dp(10), vertical = d.dp(6)),
        verticalArrangement = Arrangement.spacedBy(d.dp(2)),
    ) {
        val tap = latency?.let {
            "tap-to-tone median %.1f  min %.1f  max %.1f  last %.1f ms  n=%d"
                .fmt(it.medianMs, it.minMs, it.maxMs, it.lastMs, it.count)
        } ?: "tap-to-tone: play a key to measure"
        Line(tap, if (latency != null && latency.medianMs > 25.0) warn else text)

        if (stats == null || !stats.running) {
            Line("stream not running", warn)
        } else {
            val pathOk = stats.audioApi == "AAudio" && stats.mmap && stats.sharingMode == "Exclusive" &&
                stats.performanceMode == "LowLatency"
            Line(
                "${stats.audioApi} ${if (stats.mmap) "MMAP" else "legacy"} ${stats.sharingMode} " +
                    "${stats.performanceMode}  ${stats.sampleRate} Hz  dev ${stats.deviceId}",
                if (pathOk) text else warn,
            )
            Line(
                "buffer %d/%d (%.2f ms)  burst %d  out %s".fmt(
                    stats.bufferSizeFrames, stats.bufferCapacityFrames, stats.bufferMs, stats.framesPerBurst,
                    if (stats.outputLatencyMs >= 0) "%.1f ms".fmt(stats.outputLatencyMs) else "n/a",
                ),
                text,
            )
            // F0.4 targets: average 35% or less, p99 70% or less (the max is a stricter proxy).
            val loadBad = stats.callbackLoadAvgPct > 35.0 || stats.callbackLoadMaxPct > 70.0
            Line(
                "xruns %d  load avg %.1f%%  max %.1f%%".fmt(
                    stats.xRunCount, stats.callbackLoadAvgPct, stats.callbackLoadMaxPct,
                ),
                if (stats.xRunCount > 0 || loadBad) warn else text,
            )
        }

        val currentBursts = stats?.takeIf { it.framesPerBurst > 0 }
            ?.let { (it.bufferSizeFrames.toDouble() / it.framesPerBurst).roundToInt() }
        Row(
            Modifier.padding(top = d.dp(4)),
            horizontalArrangement = Arrangement.spacedBy(d.dp(6)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText("bursts", style = d.value(12f, GrooveColors.TextDim))
            for (n in 1..4) Chip("$n", selected = n == currentBursts, d) { onBursts(n) }
            Chip("reset", selected = false, d, onResetLatency)
        }
    }
}

@Composable
private fun Line(text: String, colour: Color) {
    val d = LocalDesign.current
    BasicText(text, style = d.value(12f, colour), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun Chip(label: String, selected: Boolean, d: Design, onTap: () -> Unit) {
    val currentOnTap by rememberUpdatedState(onTap)
    Box(
        Modifier
            .height(d.dp(28))
            .widthIn(min = d.dp(34))
            .clip(RoundedCornerShape(d.dp(8)))
            .background(if (selected) GrooveColors.Text else GrooveColors.Panel)
            .pointerInput(Unit) { detectTapGestures(onTap = { currentOnTap() }) }
            .padding(horizontal = d.dp(8)),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = d.value(12f, if (selected) GrooveColors.Ground else GrooveColors.Text))
    }
}

private fun String.fmt(vararg args: Any): String = String.format(Locale.ROOT, this, *args)
