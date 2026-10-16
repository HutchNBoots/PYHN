package com.groovebox.ui.model

import com.groovebox.engine.Curve
import com.groovebox.engine.ParamSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SeqStripsTest {
    @Test
    fun layers() {
        assertEquals(SeqLayer.Pattern, SeqStrips.layer(shifted = false, stepSelected = false))
        assertEquals(SeqLayer.Pattern, SeqStrips.layer(shifted = false, stepSelected = true))
        assertEquals(SeqLayer.Transport, SeqStrips.layer(shifted = true, stepSelected = false))
        assertEquals(SeqLayer.Step, SeqStrips.layer(shifted = true, stepSelected = true))
    }

    @Test
    fun mainStripsAreShuffleLengthGateRate() {
        assertEquals(listOf("Shuffle", "Length", "Gate", "Rate"), SeqStrips.main.map { it.label })
    }

    @Test
    fun rangesMatchTheSpec() {
        assertEquals(1.0, SeqStrips.Length.min, 0.0)
        assertEquals(64.0, SeqStrips.Length.max, 0.0)
        assertEquals(40.0, SeqStrips.Tempo.min, 0.0)
        assertEquals(240.0, SeqStrips.Tempo.max, 0.0)
        assertEquals(15.0, SeqStrips.Pattern.max, 0.0)
        assertEquals(SeqStrips.RATE_NAMES, SeqStrips.Rate.options)
        assertEquals(0.05, SeqStrips.Gate.min, 1e-6)
    }

    @Test
    fun specsWorkWithTheStripMapping() {
        val all = listOf(
            SeqStrips.Shuffle, SeqStrips.Length, SeqStrips.Gate, SeqStrips.Rate, SeqStrips.Tempo, SeqStrips.Pattern,
            SeqStrips.Note, SeqStrips.DrumNote, SeqStrips.Velocity, SeqStrips.Probability,
        )
        assertEquals(all.size, all.map { it.key }.toSet().size)
        for (s in all) {
            assertTrue(s.key, s.default in s.min..s.max)
            val back = ParamMapping.fromNormalized(s, ParamMapping.toNormalized(s, s.default))
            assertEquals(s.key, s.default, back, 1e-9)
            assertTrue(s.key, SeqStrips.format(s, s.default).isNotBlank())
        }
        assertEquals(64.0, ParamMapping.fromNormalized(SeqStrips.Length, 1.0), 0.0)
        assertEquals(3.0, ParamMapping.fromNormalized(SeqStrips.Rate, 1.0), 0.0)
    }

    @Test
    fun formats() {
        assertEquals("12%", SeqStrips.format(SeqStrips.Shuffle, 0.12))
        assertEquals("32", SeqStrips.format(SeqStrips.Length, 32.0))
        assertEquals("60%", SeqStrips.format(SeqStrips.Gate, 0.6))
        assertEquals("1/16", SeqStrips.format(SeqStrips.Rate, 1.0))
        assertEquals("1/16T", SeqStrips.format(SeqStrips.Rate, 3.0))
        assertEquals("120.0", SeqStrips.format(SeqStrips.Tempo, 120.0))
        assertEquals("133.4", SeqStrips.format(SeqStrips.Tempo, 133.4))
        assertEquals("1", SeqStrips.format(SeqStrips.Pattern, 0.0))
        assertEquals("16", SeqStrips.format(SeqStrips.Pattern, 15.0))
        assertEquals("E3", SeqStrips.format(SeqStrips.Note, 52.0))
        assertEquals("Kick", SeqStrips.format(SeqStrips.DrumNote, 48.0) { if (it == 48) "Kick" else "?" })
        assertEquals("96", SeqStrips.format(SeqStrips.Velocity, 96 / 127.0))
        assertEquals("127", SeqStrips.format(SeqStrips.Velocity, 1.0))
        assertEquals("70%", SeqStrips.format(SeqStrips.Probability, 0.7))
    }

    @Test
    fun noteNames() {
        assertEquals("C4", NoteNames.name(60))
        assertEquals("C3", NoteNames.name(48))
        assertEquals("A2", NoteNames.name(45))
        assertEquals("C#-1", NoteNames.name(1))
        assertEquals("B4", NoteNames.name(71))
    }

    @Test
    fun transposeMovesTheWholeChordWithinRange() {
        assertEquals(listOf(62, 66, 69), SeqStrips.transpose(listOf(60, 64, 67), 62, 24, 96))
        assertEquals(listOf(70), SeqStrips.transpose(emptyList(), 70, 24, 96))
        assertEquals(listOf(24), SeqStrips.transpose(emptyList(), 10, 24, 96))
        // A chord that would leave the range stops at its edge, intervals kept.
        assertEquals(listOf(89, 93, 96), SeqStrips.transpose(listOf(60, 64, 67), 95, 24, 96))
        assertEquals(listOf(48, 54), SeqStrips.transpose(listOf(50, 56), 40, 48, 63))
    }

    @Test
    fun motionSpecCarriesItsTarget() {
        val cutoff = ParamSpec(8, "flt.cutoff", "Cutoff", 20.0, 18000.0, 1000.0, Curve.Exp, "Hz")
        val m = SeqStrips.motionSpec(8, cutoff)
        assertEquals("Motion", m.label)
        assertEquals(8, SeqStrips.motionTarget(m))
        assertEquals(cutoff.min, m.min, 0.0)
        assertEquals(Curve.Exp, m.curve)
        assertNull(SeqStrips.motionTarget(SeqStrips.Gate))
        assertEquals(19, SeqStrips.motionTarget(SeqStrips.motionSpec(19, cutoff)))
    }

    /** The model mirrors values from NativeEngine.kt that cannot be read without loading the library. */
    @Test
    fun constantsMatchTheContract() {
        val source = findUp("app/src/main/java/com/groovebox/engine/NativeEngine.kt").readText()
        val rates = Regex("""STEP_RATES = listOf\(([^)]*)\)""").find(source)!!.groupValues[1]
            .split(',').map { it.trim().trim('"') }
        assertEquals(rates, SeqStrips.RATE_NAMES)
        assertEquals(rates.size, SeqLimits.RATES)
        fun const(name: String) = Regex("""const val $name = (\d+)""").find(source)!!.groupValues[1].toInt()
        assertEquals(const("DRUM_LOWEST_NOTE"), DrumLayout.LOWEST_NOTE)
        assertEquals(const("DRUM_KEY_COUNT"), DrumLayout.KEY_COUNT)
        assertEquals(const("SLOT_COUNT"), SeqLimits.SLOTS)
        assertEquals(const("MAX_STEPS"), SeqLimits.STEPS)
    }
}

/** Walks up from the working directory to find a repo file (test-only). */
fun findUp(relative: String): File {
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
        val f = File(dir, relative)
        if (f.isFile) return f
        dir = dir.parentFile
    }
    error("$relative not found")
}
