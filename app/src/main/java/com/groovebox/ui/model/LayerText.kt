package com.groovebox.ui.model

/**
 * The display's layer label (CX review R8), one form on every screen: the layer in capitals,
 * a middle dot and what the strips edit, lower case ("MAIN · osc", "STEP · step 5",
 * "SHIFT · T2"). Hints go to the footers. Pure Kotlin.
 */
object LayerText {
    const val MAIN = "MAIN"
    const val SHIFT = "SHIFT"
    const val STEP = "STEP"

    fun of(layer: String, focus: String): String = if (focus.isEmpty()) layer else "$layer · $focus"

    fun mainOrShift(shifted: Boolean, focus: String): String = of(if (shifted) SHIFT else MAIN, focus)
}
