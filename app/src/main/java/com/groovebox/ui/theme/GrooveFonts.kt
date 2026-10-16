package com.groovebox.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.groovebox.R

/**
 * The bundled font files (app/src/main/res/font, SIL OFL 1.1, licence texts in
 * app/src/main/assets/licenses). Only the four weights the UI uses are shipped:
 * labels Medium and Bold, values Normal and Medium.
 *
 * This is the only file that touches Android resources for type, so host harnesses can swap
 * in their own copy that loads the same .ttf files from disk.
 */
internal object GrooveFonts {
    val Label = FontFamily(
        Font(R.font.space_grotesk_medium, FontWeight.Medium),
        Font(R.font.space_grotesk_bold, FontWeight.Bold),
    )
    val Value = FontFamily(
        Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
        Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
    )
}
