package com.groovebox.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.groovebox.engine.NativeEngine
import com.groovebox.storage.AutosaveScheduler
import com.groovebox.storage.Autosaver
import com.groovebox.storage.MainTimer
import com.groovebox.storage.Session
import com.groovebox.storage.SessionStore
import com.groovebox.ui.model.ExportFiles
import com.groovebox.ui.model.ExportHost
import java.io.File
import java.time.LocalDateTime
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private var audioStarted by mutableStateOf(true)
    private var exportHost: ExportHost? = null

    private val state: GrooveState
        get() = ProcessState.state

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProcessState.init(applicationContext)
        exportHost = ProcessState.exportHost(this).also { state.exportHost = it }
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        setContent { GrooveScreen(state = state, audioStarted = audioStarted) }
    }

    override fun onDestroy() {
        // The host holds this activity (for the share sheet); a re-created activity has set its own.
        if (state.exportHost === exportHost) state.exportHost = null
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        // F7.6 / F0.1: the saved session (or, on first launch, the demo) is in the engine before
        // the stream opens, so the first sound is already the player's. It is a few small writes.
        ProcessState.launchOnce()
        // Sound first: the stream opens before anything else can ask a question.
        audioStarted = NativeEngine.start()
        state.onStart()
        // The tape's audio loads in the background once the stream runs (F7.6).
        ProcessState.loadTapeOnce()
    }

    override fun onStop() {
        state.onStop()
        // F7.4: save now, not up to 5 s from now, and get the tape onto disk, before the
        // process may be killed. The session writes on its own thread meanwhile.
        val session = ProcessState.autosaver.saveNow()
        if (!NativeEngine.flushTape(TAPE_FLUSH_MS)) Log.w(TAG, "tape flush timed out or failed")
        if (!session.await(SESSION_FLUSH_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) Log.w(TAG, "session save still running")
        NativeEngine.stop()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private companion object {
        const val TAG = "Groovebox"
        const val TAPE_FLUSH_MS = 1000
        const val SESSION_FLUSH_MS = 500L
    }
}

/**
 * One app state per process, so an activity re-created by the system keeps the slots and the
 * pattern mirror in step with the engine (which lives as long as the process). Created on
 * first use, after the engine library has loaded (NativeEngine's init). Also owns the session
 * file, the autosave and its writer thread. Main thread only, apart from the writer thread.
 */
private object ProcessState {
    private const val TAG = "Groovebox"

    val state by lazy { GrooveState() }

    private lateinit var appContext: Context
    private lateinit var store: SessionStore
    private var loaded: Session? = null
    private var launched = false
    private var tapeRequested = false

    /** Session writes and export clean-up: one background thread, so saves land in order. */
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "session-writer").apply { isDaemon = true }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    lateinit var autosaver: Autosaver<Session>
        private set

    /** Once per process, before the first start(): storage folders and the session file. */
    fun init(context: Context) {
        if (::store.isInitialized) return
        appContext = context.applicationContext
        val tapeDir = File(appContext.filesDir, "tape").apply { mkdirs() }
        NativeEngine.setTapeStorageDir(tapeDir.absolutePath)
        store = SessionStore(appContext.filesDir)
        val t0 = System.nanoTime()
        val result = store.load()
        val ms = (System.nanoTime() - t0) / 1e6
        loaded = result.session
        Log.i(TAG, "session load: ${result.source} in ${"%.1f".format(java.util.Locale.ROOT, ms)} ms")

        val timer = object : MainTimer {
            private var pending: Runnable? = null
            override fun schedule(delayMs: Long, action: () -> Unit) {
                cancel()
                val r = Runnable {
                    pending = null
                    action()
                }
                pending = r
                mainHandler.postDelayed(r, delayMs)
            }

            override fun cancel() {
                pending?.let { mainHandler.removeCallbacks(it) }
                pending = null
            }
        }
        autosaver = Autosaver(
            scheduler = AutosaveScheduler(),
            clock = SystemClock::uptimeMillis,
            timer = timer,
            snapshot = { state.snapshotSession() },
            write = { session -> store.save(session).also { if (!it) Log.w(TAG, "session save failed") } },
            background = writer,
            main = { r -> mainHandler.post(r) },
        )
        writer.execute { cleanExports() }
    }

    /** The saved session (or the demo) into the engine; autosave watches from then on. */
    fun launchOnce() {
        if (launched) return
        launched = true
        state.launch(loaded)
        loaded = null
        state.onSessionChange = autosaver::changed
    }

    fun loadTapeOnce() {
        if (tapeRequested) return
        tapeRequested = true
        NativeEngine.loadSavedTape()
    }

    private val exportDir: File
        get() = File(appContext.cacheDir, ExportFiles.FOLDER)

    /** Deletes old exports (writer thread). */
    private fun cleanExports() {
        val files = exportDir.listFiles()?.map { ExportFiles.Existing(it.name, it.lastModified()) } ?: return
        for (name in ExportFiles.toDelete(files, System.currentTimeMillis())) File(exportDir, name).delete()
    }

    fun exportHost(activity: ComponentActivity): ExportHost = object : ExportHost {
        override fun newExportPath(): String? {
            val dir = exportDir
            if (!dir.isDirectory && !dir.mkdirs()) return null
            writer.execute { cleanExports() }
            return File(dir, ExportFiles.fileName(LocalDateTime.now())).absolutePath
        }

        override fun share(path: String): Boolean = try {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.exports", File(path))
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "audio/wav"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(File(path).name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(send, null))
            true
        } catch (e: Exception) {
            // IllegalArgumentException (path outside the provider) or ActivityNotFoundException.
            Log.w(TAG, "share failed", e)
            false
        }
    }
}
