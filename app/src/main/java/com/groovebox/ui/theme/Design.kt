package com.groovebox.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/** Colours from ADR 0002 and docs/mockups/Main.dc.html. */
object GrooveColors {
    val Ground = Color(0xFF121214)
    val Panel = Color(0xFF1C1C20)
    val Text = Color(0xFFECE9E2)
    val TextDim = Color(0xFFA9A69F)
    val Divider = Color(0xFF2C2C32)
    val WhiteKey = Color(0xFFECE9E2)
    val BlackKey = Color(0xFF2A2A30)
    val KeyLabel = Color(0xFF5C5A55)
    val KeyLit = Color(0xFFFFC23D)
    // Capsule keyboard: dark pads with a light stripe, in a recessed tray.
    val KeyTray = Color(0xFF0C0C0E)
    val KeyPad = Color(0xFF2B2B31)
    val KeyStripe = Color(0xFFECE9E2)
    val KeyLitStripe = Color(0xFFFFFFFF)
    val Shift = Color(0xFFFFC23D)
    val Warn = Color(0xFFFF6B57)
    val White = Color(0xFFFFFFFF)

    /** Fixed by position on every screen: 1 coral, 2 amber, 3 green, 4 blue. */
    val Strips = listOf(Color(0xFFFF6B57), Color(0xFFFFC23D), Color(0xFF3DD68C), Color(0xFF4F8BFF))

    /** Alpha for controls that are shown but inert in M1. */
    const val INERT_ALPHA = 0.4f
}

/**
 * The app's two type families, in one place. ADR 0002 picks Space Grotesk for labels and
 * IBM Plex Mono for values (both SIL OFL). They are not bundled yet; to add them, put the
 * .ttf files in app/src/main/res/font/ and replace these with
 * FontFamily(Font(R.font.space_grotesk_medium, FontWeight.Medium), ...).
 */
object GrooveType {
    val Label: FontFamily = FontFamily.SansSerif
    val Value: FontFamily = FontFamily.Monospace
}

/**
 * Sizes in the mockup's design units (a 1040 × 480 frame), scaled to the actual screen.
 * Text sizes ignore the system font scale on purpose: the layout is a fixed instrument panel.
 */
@Immutable
class Design(val dpPerUnit: Float, private val density: Density) {
    fun dp(units: Float): Dp = (units * dpPerUnit).dp
    fun dp(units: Int): Dp = dp(units.toFloat())
    fun px(units: Float): Float = with(density) { dp(units).toPx() }
    fun sp(units: Float): TextUnit = with(density) { dp(units).toSp() }

    fun label(units: Float, color: Color, weight: FontWeight = FontWeight.Medium) =
        TextStyle(color = color, fontSize = sp(units), fontFamily = GrooveType.Label, fontWeight = weight)

    fun value(units: Float, color: Color, weight: FontWeight = FontWeight.Normal) =
        TextStyle(color = color, fontSize = sp(units), fontFamily = GrooveType.Value, fontWeight = weight)

    companion object {
        const val WIDTH = 1040f
        const val HEIGHT = 480f

        /** ADR 0002: touch targets are at least 44 dp, whatever the scale. */
        val MinTouch = 44.dp
    }
}

val LocalDesign = staticCompositionLocalOf<Design> { error("Design not provided") }
