package com.groovebox.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.groovebox.engine.NativeEngine

class MainActivity : ComponentActivity() {
    private var audioStarted by mutableStateOf(true)

    // Created on first use, after the engine library has loaded (NativeEngine's init).
    private val controller by lazy { SynthController() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        setContent { SynthScreen(controller = controller, audioStarted = audioStarted) }
    }

    override fun onStart() {
        super.onStart()
        // Sound first: the stream opens before anything else can ask a question.
        audioStarted = NativeEngine.start()
        // First launch plays the default preset; later starts keep what the player had.
        // Either way the strips end up showing the engine's values.
        if (controller.presetIndex < 0) controller.loadPreset(0) else controller.refreshFromEngine()
    }

    override fun onStop() {
        NativeEngine.allNotesOff()
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
}
