package com.groovebox.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DrumLayoutTest {
    private val defRows: List<List<String>> by lazy {
        val row = Regex(
            """^GROOVE_DRUM_PARAM\(\s*(\w+)\s*,\s*"([^"]*)"\s*,\s*"([^"]*)"\s*,\s*([-+0-9.eE]+)\s*,\s*([-+0-9.eE]+)\s*,""" +
                """\s*([-+0-9.eE]+)\s*,\s*(\w+)\s*,\s*"([^"]*)"\s*\)\s*$""",
        )
        findUp("engine/core/include/groove/DrumParams.def").readLines().map { it.trim() }
            .filter { it.startsWith("GROOVE_DRUM_PARAM") }
            .map { row.matchEntire(it)!!.groupValues.drop(1) }
    }

    @Test
    fun theStripsAreTheF22TableAndCoverEveryDrumParam() {
        val keys = defRows.map { it[1] }
        assertEquals(8, keys.size)
        assertEquals(keys.toSet(), (DrumLayout.main + DrumLayout.shift).toSet())
        assertEquals(listOf("Pitch", "Decay", "Tone", "Level"), DrumLayout.main.map { k -> defRows.first { it[1] == k }[2] })
        assertEquals(listOf("Pan", "Start", "Rev", "Choke"), DrumLayout.shift.map { k -> defRows.first { it[1] == k }[2] })
        assertEquals(DrumLayout.main, DrumLayout.keys(false))
        assertEquals(DrumLayout.shift, DrumLayout.keys(true))
    }

    @Test
    fun everyDrumParamFormats() {
        val specs = defRows.mapIndexed { id, r ->
            com.groovebox.engine.ParamSpec(id, r[1], r[2], r[3].toDouble(), r[4].toDouble(), r[5].toDouble(),
                com.groovebox.engine.Curve.valueOf(r[6]), r[7])
        }
        val byKey = specs.associateBy { it.key }
        assertEquals("+3 st", ParamMapping.format(byKey.getValue("drum.pitch"), 3.0))
        assertEquals("−12 st", ParamMapping.format(byKey.getValue("drum.pitch"), -12.0))
        assertEquals("0 st", ParamMapping.format(byKey.getValue("drum.pitch"), 0.0))
        assertEquals("B", ParamMapping.format(byKey.getValue("drum.choke"), 2.0))
        assertEquals("On", ParamMapping.format(byKey.getValue("drum.reverse"), 1.0))
        for (s in specs) {
            assertTrue(s.key, ParamMapping.format(s, s.default).isNotBlank())
            if (s.curve == com.groovebox.engine.Curve.Exp) assertTrue(s.min > 0)
        }
    }

    @Test
    fun keysMapToNotes48To63() {
        assertEquals(0, DrumLayout.keyOf(48))
        assertEquals(15, DrumLayout.keyOf(63))
        assertNull(DrumLayout.keyOf(47))
        assertNull(DrumLayout.keyOf(64))
        assertEquals(52, DrumLayout.noteOf(4))
    }

    @Test
    fun shortNamesAreTheHandWrittenTableInKitOrder() {
        assertEquals(
            listOf("Kick", "Punch", "Crack", "Snare", "Clap", "Rim", "CHat", "Tick",
                "OHat", "Crash", "Ride", "LoTom", "MdTom", "HiTom", "Block", "Shakr"),
            (0 until DrumLayout.KEY_COUNT).map(DrumLayout::shortName),
        )
        // Every name fits a pad and a grid cell, and no two sounds read the same.
        assertTrue(DrumLayout.SHORT_NAMES.all { it.length <= 5 })
        assertEquals(16, DrumLayout.SHORT_NAMES.toSet().size)
        // Outside the kit: the key's number, never a crash.
        assertEquals("17", DrumLayout.shortName(16))
        assertEquals("0", DrumLayout.shortName(-1))
    }

    @Test
    fun theTableMatchesTheEngineSoundsInOrder() {
        val src = findUp("engine/core/src/DrumKit.cpp").readText()
        val body = src.substringAfter("kNames[DrumKit::kSoundCount] = {").substringBefore("};")
        val engine = Regex("\"([^\"]*)\"").findAll(body).map { it.groupValues[1] }.toList()
        assertEquals(16, engine.size)
        // Each short name comes from its own engine name (a word of it, or its initials).
        val expected = listOf("Deep Kick", "Punch Kick", "Crack Snare", "Tight Snare", "Clap", "Rim", "Closed Hat", "Tick Hat",
            "Open Hat", "Crash", "Ride", "Low Tom", "Mid Tom", "High Tom", "Block", "Shaker")
        assertEquals(expected, engine)
    }

    @Test
    fun theGridHasKickBottomLeftAndRisesInKitOrder() {
        // Rows are counted from the top (row 0 = top), as drawn.
        assertEquals(0, DrumLayout.gridKey(row = 3, col = 0)) // Kick, bottom-left
        assertEquals(3, DrumLayout.gridKey(row = 3, col = 3)) // Snare, bottom-right
        assertEquals(4, DrumLayout.gridKey(row = 2, col = 0)) // Clap
        assertEquals(8, DrumLayout.gridKey(row = 1, col = 0)) // OHat
        assertEquals(12, DrumLayout.gridKey(row = 0, col = 0)) // MdTom, top-left
        assertEquals(15, DrumLayout.gridKey(row = 0, col = 3)) // Shakr, top-right
        val top = (0 until 4).map { DrumLayout.shortName(DrumLayout.gridKey(0, it)) }
        assertEquals(listOf("MdTom", "HiTom", "Block", "Shakr"), top)
        val bottom = (0 until 4).map { DrumLayout.shortName(DrumLayout.gridKey(3, it)) }
        assertEquals(listOf("Kick", "Punch", "Crack", "Snare"), bottom)
        // Every key once, and gridCell is the inverse.
        val all = (0 until 4).flatMap { r -> (0 until 4).map { c -> DrumLayout.gridKey(r, c) } }
        assertEquals((0 until 16).toSet(), all.toSet())
        for (k in 0 until 16) {
            val (r, c) = DrumLayout.gridCell(k)
            assertEquals(k, DrumLayout.gridKey(r, c))
        }
    }

    @Test
    fun padBlocksAreTheGridSplitUnderEachThumb() {
        // Left block: bottom grid rows; right block: top grid rows. Row 0 = upper row of a block.
        val left = (0 until 2).map { r -> (0 until 4).map { c -> DrumLayout.shortName(DrumLayout.padKey(0, r, c)) } }
        assertEquals(listOf(listOf("Clap", "Rim", "CHat", "Tick"), listOf("Kick", "Punch", "Crack", "Snare")), left)
        val right = (0 until 2).map { r -> (0 until 4).map { c -> DrumLayout.shortName(DrumLayout.padKey(1, r, c)) } }
        assertEquals(listOf(listOf("MdTom", "HiTom", "Block", "Shakr"), listOf("OHat", "Crash", "Ride", "LoTom")), right)
        // Same order as the grid: block rows stacked right-then-left are the grid's rows.
        for (k in 0 until 16) {
            val (r, c) = DrumLayout.gridCell(k)
            val block = if (r < 2) 1 else 0
            assertEquals(k, DrumLayout.padKey(block, r % 2, c))
        }
    }

    @Test
    fun aPadBlockTouchGivesItsKeyAndVelocityByHeightOverTheWholePad() {
        val w = 400f
        val h = 200f // two rows of 100
        // Lower-left pad of the left block is Kick; the top of the pad is soft, its foot full.
        val top = DrumLayout.padHit(block = 0, x = 10f, y = 100f, width = w, height = h)!!
        assertEquals(0, top.key)
        assertEquals(DrumLayout.MIN_PAD_VELOCITY, top.velocity, 1e-4f)
        val foot = DrumLayout.padHit(0, 10f, 199.9f, w, h)!!
        assertEquals(0, foot.key)
        assertEquals(1f, foot.velocity, 1e-3f)
        val middle = DrumLayout.padHit(0, 10f, 150f, w, h)!!
        assertEquals((DrumLayout.MIN_PAD_VELOCITY + 1f) / 2f, middle.velocity, 1e-4f)
        // The upper row's pad uses its own height, not the block's.
        val clapTop = DrumLayout.padHit(0, 10f, 0f, w, h)!!
        assertEquals(4, clapTop.key)
        assertEquals(DrumLayout.MIN_PAD_VELOCITY, clapTop.velocity, 1e-4f)
        val clapFoot = DrumLayout.padHit(0, 10f, 99.9f, w, h)!!
        assertEquals(4, clapFoot.key)
        assertEquals(1f, clapFoot.velocity, 1e-3f)
        // Columns, and the right block.
        assertEquals(3, DrumLayout.padHit(0, 399f, 150f, w, h)!!.key) // Snare
        assertEquals(8, DrumLayout.padHit(1, 0f, 150f, w, h)!!.key) // OHat
        assertEquals(15, DrumLayout.padHit(1, 399f, 10f, w, h)!!.key) // Shakr
        // Outside the block, or no size yet.
        assertNull(DrumLayout.padHit(0, -1f, 10f, w, h))
        assertNull(DrumLayout.padHit(0, 10f, 200f, w, h))
        assertNull(DrumLayout.padHit(0, 10f, 10f, 0f, 0f))
    }

    @Test
    fun theTwoBlocksSideBySideSplitAtTheGap() {
        // Two blocks of 400 with a 20 gap: 0..400 left, 420..820 right; a touch in the gap goes to the nearer.
        val w = 820f
        val h = 200f
        assertEquals(0, DrumLayout.padsHit(10f, 150f, w, h, gap = 20f)!!.key) // Kick
        assertEquals(3, DrumLayout.padsHit(399f, 150f, w, h, 20f)!!.key) // Snare
        assertEquals(3, DrumLayout.padsHit(405f, 150f, w, h, 20f)!!.key) // gap, nearer the left
        assertEquals(8, DrumLayout.padsHit(415f, 150f, w, h, 20f)!!.key) // gap, nearer the right: OHat
        assertEquals(8, DrumLayout.padsHit(420f, 150f, w, h, 20f)!!.key)
        assertEquals(15, DrumLayout.padsHit(819f, 10f, w, h, 20f)!!.key) // Shakr
        val v = DrumLayout.padsHit(500f, 199.9f, w, h, 20f)!!.velocity
        assertEquals(1f, v, 1e-3f)
        assertNull(DrumLayout.padsHit(820f, 10f, w, h, 20f))
        assertNull(DrumLayout.padsHit(10f, -1f, w, h, 20f))
    }

    @Test
    fun shapeStaysInsideItsLevelAndDecayShortensIt() {
        val loud = DrumLayout.shape(0.0, 1.0, 1.0, 0.0, false)
        assertTrue(loud.all { abs(it.y) <= 1.0 + 1e-9 && it.x in 0.0..1.0 })
        val quiet = DrumLayout.shape(0.0, 1.0, 0.3, 0.0, false)
        assertTrue(quiet.all { abs(it.y) <= 0.3 + 1e-9 })
        val long = DrumLayout.envelope(1.0, 1.0, 0.0, false)
        val short = DrumLayout.envelope(0.0, 1.0, 0.0, false)
        assertTrue(short[32].y < long[32].y)
        assertEquals(1.0, long.first().y, 1e-9)
    }

    @Test
    fun reverseSwellsInsteadOfDecaying() {
        val env = DrumLayout.envelope(0.5, 1.0, 0.0, true)
        assertTrue(env.first().y < env.last().y)
        assertEquals(1.0, env.last().y, 1e-9)
    }

    @Test
    fun startOffsetSkipsTheAttack() {
        val env = DrumLayout.envelope(0.5, 1.0, 0.5, false)
        assertTrue(env.first().y < 1.0)
    }
}
