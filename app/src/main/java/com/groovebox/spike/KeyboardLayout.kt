package com.groovebox.spike

/** A key's note and bounds, as fractions (0..1) of the keyboard's width and height. */
data class KeyRect(
    val note: Int,
    val isBlack: Boolean,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** Piano layout of whole octaves starting on a C. Pure geometry, so it is unit-testable. */
class KeyboardLayout(val lowestNote: Int = 48, val octaves: Int = 2) {
    init {
        require(lowestNote % 12 == 0) { "lowestNote must be a C" }
        require(octaves > 0)
    }

    private val whiteCount = octaves * WHITE_OFFSETS.size

    /** White keys first, then black keys, so drawing in order puts black keys on top. */
    val keys: List<KeyRect> = buildList {
        val whiteWidth = 1f / whiteCount
        for (i in 0 until whiteCount) {
            add(KeyRect(whiteNote(i), false, i * whiteWidth, 0f, (i + 1) * whiteWidth, 1f))
        }
        for (i in 0 until whiteCount) {
            val inOctave = i % WHITE_OFFSETS.size
            if (inOctave !in HAS_BLACK_AFTER) continue
            val center = (i + 1) * whiteWidth
            val half = BLACK_WIDTH * whiteWidth / 2f
            add(KeyRect(whiteNote(i) + 1, true, center - half, 0f, center + half, BLACK_HEIGHT))
        }
    }

    /** The note under (x, y) in a keyboard of the given size, or null if outside it. */
    fun noteAt(x: Float, y: Float, width: Float, height: Float): Int? {
        if (width <= 0f || height <= 0f) return null
        if (x < 0f || y < 0f || x >= width || y >= height) return null
        val fx = x / width
        val fy = y / height
        // Black keys sit on top, so test them first.
        keys.firstOrNull { it.isBlack && fx >= it.left && fx < it.right && fy < it.bottom }
            ?.let { return it.note }
        val index = (fx * whiteCount).toInt().coerceIn(0, whiteCount - 1)
        return whiteNote(index)
    }

    private fun whiteNote(index: Int): Int =
        lowestNote + 12 * (index / WHITE_OFFSETS.size) + WHITE_OFFSETS[index % WHITE_OFFSETS.size]

    private companion object {
        val WHITE_OFFSETS = intArrayOf(0, 2, 4, 5, 7, 9, 11)
        // C, D, F, G, A are followed by a black key.
        val HAS_BLACK_AFTER = setOf(0, 1, 3, 4, 5)
        const val BLACK_WIDTH = 0.6f
        const val BLACK_HEIGHT = 0.6f
    }
}
