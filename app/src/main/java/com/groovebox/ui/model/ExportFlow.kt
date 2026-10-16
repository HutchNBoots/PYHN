package com.groovebox.ui.model

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The platform side of exporting: where the file goes, and the share sheet (MainActivity). */
interface ExportHost {
    /** A fresh file path in the app's cache for the next export, or null if the folder can't be made. */
    fun newExportPath(): String?

    /** Opens the share sheet for the finished file; false if it could not. */
    fun share(path: String): Boolean
}

/** What the Tape screen shows about Export WAV (F7.1, F7.2). */
sealed interface ExportStatus {
    data object Idle : ExportStatus

    /** Rendering; [progress] 0..1. */
    data class Rendering(val progress: Float, val wholeTape: Boolean) : ExportStatus

    /** The share sheet opened. */
    data object Shared : ExportStatus

    data class Failed(val message: String) : ExportStatus
}

/**
 * The export flow's state, driven by the main thread: [start] after NativeEngine.startExport,
 * [poll] once per frame with NativeEngine.exportProgress (0..1 rendering, 1 done, -1 failed,
 * NaN before the engine reports). On completion [poll] returns the file to share exactly
 * once. Shared and Failed fall back to Idle after [messageMs]; a render that makes no
 * progress for [stallMs] counts as failed. Pure Kotlin.
 */
class ExportTracker(private val messageMs: Long = 4_000, private val stallMs: Long = 15_000) {
    var status: ExportStatus = ExportStatus.Idle
        private set

    private var path: String? = null
    private var since = 0L
    private var lastProgress = Float.NaN

    val rendering: Boolean
        get() = status is ExportStatus.Rendering

    /** [started] is what startExport returned; false means an empty tape or an export already running. */
    fun start(started: Boolean, path: String, wholeTape: Boolean, now: Long) {
        since = now
        lastProgress = Float.NaN
        if (!started) {
            this.path = null
            status = ExportStatus.Failed("export failed · nothing on the tape to export")
            return
        }
        this.path = path
        status = ExportStatus.Rendering(0f, wholeTape)
    }

    /** Returns the finished file's path once, when it is ready to share. */
    fun poll(progress: Float, now: Long): String? {
        when (val s = status) {
            is ExportStatus.Rendering -> {
                when {
                    progress >= 1f -> {
                        val done = path
                        path = null
                        status = ExportStatus.Shared
                        since = now
                        return done
                    }
                    progress < 0f -> fail("export failed · couldn't write the file", now)
                    progress.isNaN() || progress == lastProgress -> if (now - since >= stallMs) fail("export failed · the render stopped", now)
                    else -> {
                        lastProgress = progress
                        since = now
                        status = s.copy(progress = progress)
                    }
                }
            }
            ExportStatus.Shared, is ExportStatus.Failed -> if (now - since >= messageMs) status = ExportStatus.Idle
            ExportStatus.Idle -> Unit
        }
        return null
    }

    /** The share sheet could not open. */
    fun shareFailed(now: Long) = fail("export done, but no app can take it", now)

    fun fail(message: String, now: Long) {
        path = null
        since = now
        status = ExportStatus.Failed(message)
    }
}

/** Export file rules: names, what to render and which old files to delete. Pure Kotlin. */
object ExportFiles {
    const val FOLDER = "exports"
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)

    /** "groovebox-20261016-142501.wav". */
    fun fileName(time: LocalDateTime): String = "groovebox-${STAMP.format(time)}.wav"

    /**
     * Decision: export the loop region the player hears; when the loop is the whole tape (loop
     * length 0, or a region from bar 1 to the end) ask for the whole tape, which is the same
     * audio without the engine having to clamp a region.
     */
    fun wholeTape(region: LoopRegion?): Boolean = region == null || region.whole

    data class Existing(val name: String, val lastModifiedMs: Long)

    /**
     * Old exports in the cache to delete: everything but the [keep] newest, and anything
     * older than [maxAgeMs]. The newest few stay so an app still reading a recent share
     * (an upload, say) is not cut off. Only our own .wav names are touched.
     */
    fun toDelete(files: List<Existing>, now: Long, keep: Int = 3, maxAgeMs: Long = 24L * 3600 * 1000): List<String> {
        val ours = files.filter { it.name.startsWith("groovebox-") && it.name.endsWith(".wav") }
            .sortedByDescending { it.lastModifiedMs }
        return ours.filterIndexed { i, f -> i >= keep || now - f.lastModifiedMs > maxAgeMs }.map { it.name }
    }
}
