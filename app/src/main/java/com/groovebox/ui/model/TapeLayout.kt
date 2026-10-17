package com.groovebox.ui.model

import java.util.Locale
import kotlin.math.floor

/**
 * The Tape screen's strips (F5, docs/mockups/Tape.dc.html). Strip 4 is Dub (F5.2) and the
 * loop region is on strips 2–3 (F5.3), so Speed takes strip 1. Shift: the selected track's
 * channel Level and Pan, Reverse, and the Commit action (F5.6): tap commits the selected
 * slot's pattern, long-press commits every slot. Pure Kotlin.
 */
object TapeStrips {
    val main: List<StripRef> = listOf(
        StripRef.Mix(MixKeys.SPEED),
        StripRef.Mix(MixKeys.LOOP_START),
        StripRef.Mix(MixKeys.LOOP_LEN),
        StripRef.Mix(MixKeys.DUB),
    )

    val shift: List<StripRef> = listOf(
        StripRef.Channel(MixKeys.VOLUME),
        StripRef.Channel(MixKeys.PAN),
        StripRef.Mix(MixKeys.REVERSE),
        StripRef.Commit,
    )

    fun strips(shifted: Boolean): List<StripRef> = if (shifted) shift else main

    const val COMMIT_LABEL = "Commit"

    /** The Commit strip's value: what a tap commits ("S2", or "Kit" for the drum slot). */
    fun commitValue(slot: Int): String = if (slot == 0) "Kit" else "S${slot + 1}"
}

/** The loop region in bars of the tape: [startBar, endBar). */
data class LoopRegion(val startBar: Int, val endBar: Int, val whole: Boolean) {
    val bars: Int get() = endBar - startBar
}

/** What the Tape screen's Record button does (it records tape, not notes, on that screen). */
sealed interface TapeRecordAction {
    data class Arm(val track: Int) : TapeRecordAction
    data object Stop : TapeRecordAction
    data object None : TapeRecordAction
}

enum class TapeRecordStatus { Idle, Armed, Recording }

/** Tape display rules: the loop region, the head, lane hit-testing and the Record button. Pure Kotlin. */
object TapeView {
    /**
     * The loop region the engine plays, clamped to the tape: length 0 is the whole tape;
     * otherwise [loopStart] (kept inside the tape) for [loopLen] bars, cut at the tape's end.
     * Null when the tape is empty.
     */
    fun loopRegion(loopStart: Int, loopLen: Int, tapeBars: Int): LoopRegion? {
        if (tapeBars <= 0) return null
        if (loopLen <= 0) return LoopRegion(0, tapeBars, whole = true)
        val start = loopStart.coerceIn(0, tapeBars - 1)
        val end = (start + loopLen).coerceAtMost(tapeBars)
        return LoopRegion(start, end, whole = start == 0 && end == tapeBars)
    }

    /** The head's position across the lanes, 0..1 (0 on an empty tape). */
    fun headFraction(headFrame: Long, lengthFrames: Long): Float {
        if (lengthFrames <= 0) return 0f
        return (headFrame.toDouble() / lengthFrames).coerceIn(0.0, 1.0).toFloat()
    }

    /**
     * The lane under [y], for [lanes] lanes stacked over [height] with [gap] between them.
     * A touch in a gap goes to the nearer lane; null outside the lanes.
     */
    fun laneAt(y: Float, height: Float, lanes: Int = MixLimits.TRACKS, gap: Float = 0f): Int? {
        if (lanes <= 0 || height <= 0f || y < 0f || y > height) return null
        val pitch = (height + gap) / lanes
        return floor((y + gap / 2f) / pitch).toInt().coerceIn(0, lanes - 1)
    }

    fun status(armedTrack: Int, recordingTrack: Int): TapeRecordStatus = when {
        recordingTrack >= 0 -> TapeRecordStatus.Recording
        armedTrack >= 0 -> TapeRecordStatus.Armed
        else -> TapeRecordStatus.Idle
    }

    /**
     * The engine records only at 1x forward and at the tape's tempo (build-20; it ignores an
     * arm otherwise). [tempoMatches] is the engine's readout (TapeState.tempoMatches).
     */
    fun canRecord(speedIndex: Int, reverse: Boolean, tempoMatches: Boolean = true): Boolean =
        speedIndex == MixKeys.SPEED_NORMAL && !reverse && tempoMatches

    /** The footer's line when Record can't record ([canRecord] false), else null. */
    fun recordNeeds(speedIndex: Int, reverse: Boolean, tempoMatches: Boolean, tapeTempoBpm: Float): String? {
        val speedOk = speedIndex == MixKeys.SPEED_NORMAL && !reverse
        if (speedOk && tempoMatches) return null
        val tempo = "${bpm(tapeTempoBpm)} BPM, the tape's tempo"
        return when {
            tempoMatches -> "record needs Speed 1× and Rev off"
            speedOk -> "record needs $tempo"
            else -> "record needs Speed 1×, Rev off and $tempo"
        }
    }

    /** A tempo to 0.1 BPM, "120.0". */
    fun bpm(value: Float): String = String.format(Locale.ROOT, "%.1f", value)

    /** Record on the Tape screen: stops (or cancels an arm) when busy, else arms the selected track. */
    fun recordPress(status: TapeRecordStatus, selectedTrack: Int, canRecord: Boolean): TapeRecordAction = when {
        status != TapeRecordStatus.Idle -> TapeRecordAction.Stop
        canRecord -> TapeRecordAction.Arm(selectedTrack)
        else -> TapeRecordAction.None
    }

    /**
     * Double-tap a lane (ADR 0003: a thumb-reachable Record): when idle, arm that lane; when
     * that lane is armed or recording, stop it. A double-tap on another lane while one is
     * busy does nothing, so a stray tap never ends the take in progress.
     */
    fun laneDoubleTap(lane: Int, status: TapeRecordStatus, busyTrack: Int, canRecord: Boolean): TapeRecordAction = when {
        status == TapeRecordStatus.Idle -> if (canRecord) TapeRecordAction.Arm(lane) else TapeRecordAction.None
        lane == busyTrack -> TapeRecordAction.Stop
        else -> TapeRecordAction.None
    }

    /** The grey line under "Tape": length, loop, dub and speed. */
    fun subtitle(tapeBars: Int, region: LoopRegion?, dub: Double, speed: String, reverse: Boolean): String {
        if (tapeBars <= 0 || region == null) return "empty · the first take sets the length"
        val parts = ArrayList<String>(4)
        parts += MixFormat.bars(tapeBars)
        parts += if (region.whole) "looping all" else "looping bars ${region.startBar + 1}–${region.endBar}"
        parts += when {
            dub >= 0.995 -> "overdub"
            dub <= 0.005 -> "replace"
            else -> "dub ${(dub * 100).toInt()}%"
        }
        if (speed != "1×" || reverse) parts += speed + if (reverse) " reverse" else ""
        return parts.joinToString(" · ")
    }

    /** The subtitle while a saved tape loads (F7.6). */
    const val LOADING = "loading tape…"

    /** The footer line for Export WAV (F7.1), or null when idle. */
    fun exportLine(status: ExportStatus, tapeBars: Int, loopStart: Int, loopLen: Int): String? = when (status) {
        ExportStatus.Idle -> null
        is ExportStatus.Rendering -> {
            val region = loopRegion(loopStart, loopLen, tapeBars)
            val what = if (status.wholeTape || region == null) "the whole tape" else "bars ${region.startBar + 1}–${region.endBar}"
            "exporting $what · ${(status.progress.coerceIn(0f, 1f) * 100).toInt()}%"
        }
        ExportStatus.Shared -> "export ready · pick an app to send it to"
        is ExportStatus.Failed -> status.message
    }

    /** "T1".."T4". */
    fun trackName(track: Int): String = "T${track + 1}"
}

/**
 * The peak overviews of the four tracks (NativeEngine.tapeOverview: 0..255 per bin across
 * the whole tape), decoded to 0..1. [update] reports whether anything changed, so the
 * display redraws only then. No allocation after construction. Main thread only.
 */
class TapeOverview(val tracks: Int = MixLimits.TRACKS, val bins: Int = MixLimits.OVERVIEW_BINS) {
    private val peaks = Array(tracks) { FloatArray(bins) }

    /** Bumped on every change. */
    var revision: Long = 0L
        private set

    /** Copies one track's raw overview in; returns true when it differs from before. */
    fun update(track: Int, raw: ByteArray): Boolean {
        if (track !in 0 until tracks) return false
        val p = peaks[track]
        var changed = false
        for (i in 0 until bins) {
            val v = if (i < raw.size) decode(raw[i]) else 0f
            if (v != p[i]) {
                p[i] = v
                changed = true
            }
        }
        if (changed) revision++
        return changed
    }

    fun peak(track: Int, bin: Int): Float = peaks.getOrNull(track)?.getOrNull(bin) ?: 0f

    /** The loudest bin under column [column] of [columns] (columns span the whole tape). */
    fun column(track: Int, column: Int, columns: Int): Float {
        if (columns <= 0 || track !in 0 until tracks || column !in 0 until columns) return 0f
        val p = peaks[track]
        val from = (column.toLong() * bins / columns).toInt()
        val to = maxOf(from + 1, ((column + 1).toLong() * bins / columns).toInt()).coerceAtMost(bins)
        var max = 0f
        for (i in from until to) if (p[i] > max) max = p[i]
        return max
    }

    companion object {
        fun decode(b: Byte): Float = (b.toInt() and 0xFF) / 255f
    }
}
