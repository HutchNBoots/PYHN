package com.groovebox.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.groovebox.R
import kotlinx.coroutines.delay

/** Shown once per app launch, not on rotation or returning from the background. */
internal object SplashOnce {
    var shown = false
}

/**
 * The launch splash over the groovebox. It never blocks sound: the engine is already running
 * underneath, it fades by itself after [HOLD_MS], and a touch dismisses it at once.
 */
@Composable
fun Splash() {
    if (SplashOnce.shown) return
    var gone by remember { mutableStateOf(false) }
    if (gone) return
    val alpha = remember { Animatable(1f) }
    var dismiss by remember { mutableStateOf(false) }
    LaunchedEffect(dismiss) {
        if (!dismiss) delay(HOLD_MS)
        alpha.animateTo(0f, tween(FADE_MS))
        SplashOnce.shown = true
        gone = true
    }
    Box(
        Modifier
            .fillMaxSize()
            .alpha(alpha.value)
            .background(SPLASH_BG)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    dismiss = true
                }
            },
    ) {
        Image(
            painter = painterResource(R.drawable.splash),
            contentDescription = "Not for Cats, the two-thumb groovebox",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private const val HOLD_MS = 1400L
private const val FADE_MS = 350
/** The image's own background, so the letterbox bands don't show. */
private val SPLASH_BG = Color(0xFF0B1014)
