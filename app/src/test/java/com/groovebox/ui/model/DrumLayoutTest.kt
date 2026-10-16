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
    fun keysMapToNotes48To63() {
        assertEquals(0, DrumLayout.keyOf(48))
        assertEquals(15, DrumLayout.keyOf(63))
        assertNull(DrumLayout.keyOf(47))
        assertNull(DrumLayout.keyOf(64))
        assertEquals(52, DrumLayout.noteOf(4))
    }

    @Test
    fun shortNames() {
        assertEquals("Kick1", DrumLayout.shortName("Kick 1"))
        assertEquals("CH1", DrumLayout.shortName("Closed Hat 1"))
        assertEquals("OH", DrumLayout.shortName("Open Hat"))
        assertEquals("Clap", DrumLayout.shortName("Clap"))
        assertEquals("Snar2", DrumLayout.shortName("Snare 2"))
        assertEquals("", DrumLayout.shortName("  "))
        assertEquals("7", DrumLayout.shortName("7"))
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
