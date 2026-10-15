package com.groovebox.spike

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.groovebox.engine.EngineStats
import com.groovebox.engine.NativeEngine
import com.groovebox.engine.Waveform
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private val Background = Color(0xFF101014)
private val TextColour = Color(0xFFE9E6DF)
private val Warn = Color(0xFFFF5A4F)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpikeScreen(audioStarted: Boolean) {
    val layout = remember { KeyboardLayout(lowestNote = 48, octaves = 2) }
    val latency = remember { LatencyStats() }
    var summary by remember { mutableStateOf<LatencySummary?>(null) }
    var stats by remember { mutableStateOf<EngineStats?>(null) }
    var waveform by remember { mutableStateOf(Waveform.Saw) }
    var bursts by remember { mutableIntStateOf(2) }

    LaunchedEffect(Unit) {
        while (isActive) {
            NativeEngine.pollTapToToneNs().forEach(latency::add)
            summary = latency.summary()
            stats = NativeEngine.stats()
            delay(100)
        }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Background)
                .safeDrawingPadding(),
        ) {
            Row(
                Modifier
                    .weight(0.45f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                StatsPanel(audioStarted, stats, summary, Modifier.weight(1f))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Waveform.entries.forEach { w ->
                            FilterChip(
                                selected = w == waveform,
                                onClick = {
                                    waveform = w
                                    NativeEngine.setWaveform(w)
                                },
                                label = { Text(w.label) },
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (1..4).forEach { n ->
                            FilterChip(
                                selected = n == bursts,
                                onClick = {
                                    bursts = n
                                    NativeEngine.setBufferBursts(n)
                                },
                                label = { Text("$n burst${if (n > 1) "s" else ""}") },
                            )
                        }
                    }
                    Button(onClick = {
                        latency.reset()
                        summary = null
                    }) { Text("Reset latency") }
                }
            }
            Spacer(Modifier.height(4.dp))
            Keyboard(
                layout,
                Modifier
                    .weight(0.55f)
                    .fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun StatsPanel(
    audioStarted: Boolean,
    stats: EngineStats?,
    summary: LatencySummary?,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        if (!audioStarted) {
            StatLine("Audio stream failed to open", Warn)
        }
        val tapLine = summary?.let {
            "Tap-to-tone  last %.1f  min %.1f  median %.1f  max %.1f ms  (n=%d)"
                .format(it.lastMs, it.minMs, it.medianMs, it.maxMs, it.count)
        } ?: "Tap-to-tone  tap a key to measure"
        StatLine(tapLine, TextColour, 18)
        StatLine("  excludes touch-panel scan time; compare with OboeTester round trip", TextColour.copy(alpha = 0.6f))
        if (stats == null || !stats.running) {
            StatLine("Stream not running", Warn)
            return@Column
        }
        val pathOk = stats.audioApi == "AAudio" && stats.mmap && stats.sharingMode == "Exclusive" &&
            stats.performanceMode == "LowLatency"
        StatLine(
            "Stream  ${stats.audioApi}  ${if (stats.mmap) "MMAP" else "legacy"}  ${stats.sharingMode}  " +
                "${stats.performanceMode}  device ${stats.deviceId}",
            if (pathOk) TextColour else Warn,
        )
        StatLine(
            "Rate ${stats.sampleRate} Hz  burst ${stats.framesPerBurst}  buffer ${stats.bufferSizeFrames}" +
                "/${stats.bufferCapacityFrames} (%.2f ms)".format(stats.bufferMs),
            TextColour,
        )
        val oboe = if (stats.outputLatencyMs >= 0) "%.1f ms".format(stats.outputLatencyMs) else "n/a"
        StatLine("Oboe output latency estimate $oboe   xruns ${stats.xRunCount}", if (stats.xRunCount > 0) Warn else TextColour)
    }
}

@Composable
private fun StatLine(text: String, colour: Color, sizeSp: Int = 14) {
    Text(text, color = colour, fontFamily = FontFamily.Monospace, fontSize = sizeSp.sp)
}
