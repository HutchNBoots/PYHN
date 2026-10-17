package com.groovebox.ui.model

import kotlin.math.exp
import kotlin.math.sin

/** The Drum screen's strips (F2.2), by DrumParams.def key, and its key mapping. Pure Kotlin. */
object DrumLayout {
    const val KEY_COUNT = 16
    const val LOWEST_NOTE = 48 // NativeEngine.DRUM_LOWEST_NOTE

    val main = listOf("drum.pitch", "drum.decay", "drum.tone", "drum.level")
    val shift = listOf("drum.pan", "drum.start", "drum.reverse", "drum.choke")

    fun keys(shifted: Boolean): List<String> = if (shifted) shift else main

    /** The kit key (0..15) a MIDI note plays, or null for notes outside the kit. */
    fun keyOf(note: Int): Int? = (note - LOWEST_NOTE).takeIf { it in 0 until KEY_COUNT }

    fun noteOf(key: Int): Int = LOWEST_NOTE + key

    /**
     * The short name of each kit key (build-21), hand-written so every one reads at a glance and
     * fits a grid cell or pad: keys 1–16 in kit order (engine/core/src/DrumKit.cpp's names).
     */
    val SHORT_NAMES = listOf(
        "Kick", "Punch", "Crack", "Snare", "Clap", "Rim", "CHat", "Tick",
        "OHat", "Crash", "Ride", "LoTom", "MdTom", "HiTom", "Block", "Shakr",
    )

    /** The short name of kit key [key] (0..15); outside the kit, the key's number. */
    fun shortName(key: Int): String = SHORT_NAMES.getOrNull(key) ?: "${key + 1}"

    /**
     * The kit grid (4 × 4, the same on Seq and Drum): Kick bottom-left, then left to right and
     * upward in kit order. [row] counts from the top (0..3), [col] from the left.
     */
    fun gridKey(row: Int, col: Int): Int = (GRID - 1 - row) * GRID + col

    /** The grid cell (row from the top, column) of kit key [key]. */
    fun gridCell(key: Int): Pair<Int, Int> = (GRID - 1 - key / GRID) to key % GRID

    /**
     * The Drum screen's two 4 × 2 pad blocks, one under each thumb, in the grid's order: block 0
     * (left) is the grid's lower two rows, block 1 (right) its upper two. [row] 0 is a block's
     * upper row.
     */
    fun padKey(block: Int, row: Int, col: Int): Int = block * PADS_PER_BLOCK + (BLOCK_ROWS - 1 - row) * GRID + col

    /** Velocity at the very top of a pad; it rises to 1.0 at the pad's foot (like the keys). */
    const val MIN_PAD_VELOCITY = 0.5f

    /** A touch on a pad block: the key, and velocity from the touch's height over the whole pad. */
    data class PadHit(val key: Int, val velocity: Float)

    /** The pad under (x, y) in a block of [width] × [height], or null outside it. Gaps go to the nearer pad. */
    fun padHit(block: Int, x: Float, y: Float, width: Float, height: Float): PadHit? {
        if (width <= 0f || height <= 0f || x < 0f || y < 0f || x >= width || y >= height) return null
        val col = (x / width * GRID).toInt().coerceIn(0, GRID - 1)
        val rowF = y / height * BLOCK_ROWS
        val row = rowF.toInt().coerceIn(0, BLOCK_ROWS - 1)
        val depth = (rowF - row).coerceIn(0f, 1f)
        return PadHit(padKey(block, row, col), MIN_PAD_VELOCITY + (1f - MIN_PAD_VELOCITY) * depth)
    }

    /**
     * The pad under (x, y) on the two blocks side by side over [width] × [height], [gap] apart.
     * A touch in the gap goes to the nearer block's edge pad. Null outside.
     */
    fun padsHit(x: Float, y: Float, width: Float, height: Float, gap: Float): PadHit? {
        if (width <= 0f || x < 0f || x >= width) return null
        val blockW = (width - gap) / 2f
        if (blockW <= 0f) return null
        return if (x < width / 2f) {
            padHit(0, x.coerceAtMost(blockW - 0.01f), y, blockW, height)
        } else {
            padHit(1, (x - blockW - gap).coerceAtLeast(0f), y, blockW, height)
        }
    }

    private const val GRID = 4
    private const val BLOCK_ROWS = 2
    private const val PADS_PER_BLOCK = 8

    /**
     * The display's picture of a drum sound: an oscillation inside its amplitude envelope,
     * x 0..1 is time, y −1..1. Higher [pitchSt] packs more cycles in; [decay] (0..1) sets
     * how long it rings; [level] (0..1) scales it; [start] (0..0.5) skips the attack;
     * [reverse] plays it backwards. Only needs to move the right way.
     */
    fun shape(pitchSt: Double, decay: Double, level: Double, start: Double, reverse: Boolean, points: Int = 160): List<DisplayShapes.Point> {
        val cycles = 6.0 * Math.pow(2.0, pitchSt.coerceIn(-12.0, 12.0) / 12.0)
        val rate = 2.0 + 28.0 * (1.0 - decay.coerceIn(0.0, 1.0)) // decay 1 rings longest
        val lvl = level.coerceIn(0.0, 1.0)
        val s = start.coerceIn(0.0, 0.5)
        return List(points + 1) { i ->
            val u = i.toDouble() / points
            val t = if (reverse) 1.0 - u else u
            val time = s + t * (1.0 - s)
            val env = lvl * exp(-rate * time)
            DisplayShapes.Point(u, env * sin(2 * Math.PI * cycles * time))
        }
    }

    /** The envelope alone (the outline drawn around [shape]), same axes. */
    fun envelope(decay: Double, level: Double, start: Double, reverse: Boolean, points: Int = 64): List<DisplayShapes.Point> {
        val rate = 2.0 + 28.0 * (1.0 - decay.coerceIn(0.0, 1.0))
        val lvl = level.coerceIn(0.0, 1.0)
        val s = start.coerceIn(0.0, 0.5)
        return List(points + 1) { i ->
            val u = i.toDouble() / points
            val t = if (reverse) 1.0 - u else u
            DisplayShapes.Point(u, lvl * exp(-rate * (s + t * (1.0 - s))))
        }
    }
}
