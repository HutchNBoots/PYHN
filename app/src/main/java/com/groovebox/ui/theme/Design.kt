package com.groovebox.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
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
    /** The key's root note (F8.3): an amber stripe. */
    val KeyRootStripe = Color(0xFFFFC23D)
    val Shift = Color(0xFFFFC23D)
    val Warn = Color(0xFFFF6B57)
    val White = Color(0xFFFFFFFF)

    /** Fixed by position on every screen: 1 coral, 2 amber, 3 green, 4 blue. */
    val Strips = listOf(Color(0xFFFF6B57), Color(0xFFFFC23D), Color(0xFF3DD68C), Color(0xFF4F8BFF))

    /** Selection (CX review R6): cream fill = current choice, cream 3 u outline = selected item. */
    val Select = Color(0xFFECE9E2)
    /** A footer chip's resting fill. */
    val ChipFill = Color(0xFF2B2B31)
    /** Coral: the selected slot and record only. */
    val SlotSelect = Color(0xFFFF6B57)

    /** Alpha for controls that are shown but inert (dimmed, disabled, not set on this step). */
    const val INERT_ALPHA = 0.4f
    /** Alpha for the "off" phase of a blinking value (a queued change). */
    const val BLINK_ALPHA = 0.25f
}

/**
 * Corner radii in design units (CX review token proposal). Keys stay capsules (owner decision),
 * so they are not here; "full" (dots, stripes) is CircleShape.
 */
object GrooveRadius {
    /** Display, keyboard tray. */
    const val PANEL = 16f
    /** Colour strips. */
    const val STRIP = 14f
    /** Step pads, mixer pads, kit pads. */
    const val PAD = 12f
    /** Buttons, rails, preset strip. */
    const val CONTROL = 10f
    /** Chips: sound cells, tape tracks, footer chips, debug. */
    const val CHIP = 8f
    /** Cells, badges. */
    const val CELL = 4f
    /** Hairline bars. */
    const val HAIRLINE = 2f
}

/** Spacing in design units. */
object GrooveSpace {
    /** Inside a group. */
    const val GROUP = 4f
    /** Between pads. */
    const val PAD = 6f
    /** Between strips, and between display rows. */
    const val ROW = 8f
    /** Between zones (display, strips, keys). */
    const val ZONE = 10f
    /** Display padding, top and bottom. */
    const val DISPLAY_V = 14f
    /** Display padding, left and right. */
    const val DISPLAY_H = 18f
}

/** The seven text sizes in design units (L = Space Grotesk label, V = IBM Plex Mono value). */
object GrooveText {
    /** L20 Bold: display title. */
    const val TITLE = 20f
    /** L14: buttons, strip labels. */
    const val LABEL = 14f
    /** L13: body, subtitle. */
    const val BODY = 13f
    /** V22: the BPM readout. */
    const val BPM = 22f
    /** V13: values (strip values, pad labels, legend). */
    const val VALUE = 13f
    /** V11: meta (layer labels, footers, small readouts). */
    const val META = 11f
    /** 10: captions, key labels, mixer channel names. */
    const val CAPTION = 10f
}

/**
 * The app's two type families, in one place (ADR 0002): Space Grotesk for labels and IBM
 * Plex Mono for values, both SIL OFL 1.1 and bundled in res/font (see [GrooveFonts]).
 * Every label and value goes through [Design.label] / [Design.value], which use these.
 *
 * Sources: Space Grotesk static TTFs from github.com/floriankarsten/space-grotesk
 * (fonts/ttf/static, commit 03507d0); IBM Plex Mono from github.com/google/fonts
 * (ofl/ibmplexmono, commit 47d54ca).
 */
object GrooveType {
    val Label: FontFamily = GrooveFonts.Label
    val Value: FontFamily = GrooveFonts.Value
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

    /** A rounded-corner shape from a [GrooveRadius] token. */
    fun shape(radius: Float) = RoundedCornerShape(dp(radius))

    // The type scale (GrooveText) as ready styles.
    fun title(color: Color = GrooveColors.Text) = label(GrooveText.TITLE, color, FontWeight.Bold)
    fun buttonLabel(color: Color, weight: FontWeight = FontWeight.Medium) = label(GrooveText.LABEL, color, weight)
    fun body(color: Color = GrooveColors.TextDim) = label(GrooveText.BODY, color)
    fun bpm(color: Color = GrooveColors.Text) = value(GrooveText.BPM, color, FontWeight.Medium)
    fun valueText(color: Color = GrooveColors.Text, weight: FontWeight = FontWeight.Normal) = value(GrooveText.VALUE, color, weight)
    fun meta(color: Color = GrooveColors.TextDim, weight: FontWeight = FontWeight.Normal) = value(GrooveText.META, color, weight)
    fun caption(color: Color = GrooveColors.TextDim, weight: FontWeight = FontWeight.Normal) = value(GrooveText.CAPTION, color, weight)
    /** One pad label style everywhere (step pads, mixer pads): mono 13 medium. */
    fun padLabel(color: Color = GrooveColors.Text) = value(GrooveText.VALUE, color, FontWeight.Medium)

    companion object {
        const val WIDTH = 1040f
        const val HEIGHT = 480f

        /** ADR 0002: touch targets are at least 44 dp, whatever the scale. */
        val MinTouch = 44.dp
    }
}

val LocalDesign = staticCompositionLocalOf<Design> { error("Design not provided") }
