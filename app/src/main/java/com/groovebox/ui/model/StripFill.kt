package com.groovebox.ui.model

/**
 * Where a colour strip's top edge sits (CX review R5): the fill runs from the bottom up to a
 * header band, so at a high value the edge line stays below the label and value instead of
 * crossing them. The whole range maps onto the space under the band; the edge stays visible at
 * the bottom at the minimum. A strip too short for the band uses its full height. Pure Kotlin.
 */
object StripFill {
    /** The header band in design units: label, value and padding. */
    const val HEADER_UNITS = 52f

    fun edgeTop(normalized: Double, height: Float, header: Float, edge: Float): Float {
        val n = normalized.coerceIn(0.0, 1.0).toFloat()
        val top = if (height >= header + edge) header else 0f
        val travel = (height - top - edge).coerceAtLeast(0f)
        return top + travel * (1f - n)
    }
}
