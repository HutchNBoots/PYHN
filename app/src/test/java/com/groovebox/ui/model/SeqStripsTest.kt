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
        assertEquals("a selection takes the main layer", SeqLayer.Step, SeqStrips.layer(shifted = false, stepSelected = true))
        assertEquals(SeqLayer.Transport, SeqStrips.layer(shifted = true, stepSelected = false))
        assertEquals(SeqLayer.StepShift, SeqStrips.layer(shifted = true, stepSelected = true))
    }

    @Test
    fun stepStripsAreVelocityLengthChanceNudge() {
        assertEquals(listOf("Velocity", "Hold", "Chance", "Nudge"), SeqStrips.step.map { it.label })
        assertEquals(0.01, SeqStrips.HitVelocity.min, 1e-6)
        assertEquals(1.0, SeqStrips.HitVelocity.max, 0.0)
        assertEquals(-0.5, SeqStrips.HitNudge.min, 0.0)
        assertEquals(0.5, SeqStrips.HitNudge.max, 0.0)
        assertEquals(0.0, SeqStrips.HitNudge.default, 0.0)
        assertEquals(1.0, SeqStrips.HitChance.default, 0.0)
    }

    @Test
    fun theLengthStripRunsFromThePatternGateTo16Steps() {
        val lengths = SeqStrips.LENGTHS
        assertEquals(SeqLimits.PATTERN_GATE, lengths.first(), 0f)
        assertEquals(1f / 16, lengths[1], 0f)
        assertEquals(16f, lengths.last(), 0f)
        assertTrue("rising", lengths.zipWithNext().all { (a, b) -> b > a })
        assertTrue(1f in lengths && 0.5f in lengths && 2.5f in lengths)
        // Leftmost (and the default) is the pattern gate; every strip position is a length.
        assertEquals(0.0, SeqStrips.HitLength.default, 0.0)
        assertEquals((lengths.size - 1).toDouble(), ParamMapping.fromNormalized(SeqStrips.HitLength, 1.0), 0.0)
        for ((i, l) in lengths.withIndex()) {
            assertEquals(i, SeqStrips.lengthIndex(l))
            assertEquals(l, SeqStrips.lengthAt(i.toDouble()), 0f)
        }
    }

    @Test
    fun aLengthBetweenTheStripsValuesShowsTheNearest() {
        assertEquals(SeqStrips.lengthIndex(0.25f), SeqStrips.lengthIndex(0.3f)) // a v2 gate of 30%
        assertEquals(SeqStrips.lengthIndex(1f / 16), SeqStrips.lengthIndex(0.05f))
        assertEquals(0, SeqStrips.lengthIndex(-1f))
        assertEquals(SeqStrips.LENGTHS.lastIndex, SeqStrips.lengthIndex(40f))
    }

    @Test
    fun lengthText() {
        assertEquals("gate", SeqStrips.formatLength(-1f))
        assertEquals("1/16", SeqStrips.formatLength(1f / 16))
        assertEquals("3/16", SeqStrips.formatLength(3f / 16))
        assertEquals("1/8", SeqStrips.formatLength(0.125f))
        assertEquals("1/4", SeqStrips.formatLength(0.25f))
        assertEquals("1/2", SeqStrips.formatLength(0.5f))
        assertEquals("3/4", SeqStrips.formatLength(0.75f))
        assertEquals("1", SeqStrips.formatLength(1f))
        assertEquals("1½", SeqStrips.formatLength(1.5f))
        assertEquals("2½", SeqStrips.formatLength(2.5f))
        assertEquals("16", SeqStrips.formatLength(16f))
        // Lengths from a v2 gate show as they are.
        assertEquals("0.3", SeqStrips.formatLength(0.3f))
        assertEquals("0.45", SeqStrips.formatLength(0.45f))
        assertEquals("gate", SeqStrips.format(SeqStrips.HitLength, 0.0))
        assertEquals("16", SeqStrips.format(SeqStrips.HitLength, SeqStrips.LENGTHS.lastIndex.toDouble()))
    }

    @Test
    fun nudgeText() {
        assertEquals("0", SeqStrips.formatNudge(0f))
        assertEquals("0", SeqStrips.formatNudge(0.003f))
        assertEquals("+25%", SeqStrips.formatNudge(0.25f))
        assertEquals("\u221212%", SeqStrips.formatNudge(-0.12f))
        assertEquals("\u221250%", SeqStrips.formatNudge(-0.5f))
        assertEquals("+50%", SeqStrips.format(SeqStrips.HitNudge, 0.5))
        assertEquals("0", SeqStrips.format(SeqStrips.SpreadStrip, 0.0))
        assertEquals("\u00B140%", SeqStrips.format(SeqStrips.SpreadStrip, 0.4))
    }

    @Test
    fun velocityAndChanceText() {
        assertEquals("80%", SeqStrips.format(SeqStrips.HitVelocity, 0.8))
        assertEquals("1%", SeqStrips.format(SeqStrips.HitVelocity, 0.01))
        assertEquals("100%", SeqStrips.format(SeqStrips.HitVelocity, 1.0))
        assertEquals("100%", SeqStrips.format(SeqStrips.HitChance, 1.0))
        assertEquals("0%", SeqStrips.format(SeqStrips.HitChance, 0.0))
    }

    @Test
    fun mainStripsAreShuffleLengthGateRate() {
        assertEquals("build-22 renames: pattern Length → Steps", listOf("Shuffle", "Steps", "Gate", "Rate"), SeqStrips.main.map { it.label })
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
            SeqStrips.Note, SeqStrips.Key, SeqStrips.ScaleStrip,
            SeqStrips.HitVelocity, SeqStrips.HitLength, SeqStrips.HitChance, SeqStrips.HitNudge, SeqStrips.SpreadStrip,
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
        assertEquals("+2", SeqStrips.format(SeqStrips.Note, 2.0))
        assertEquals("\u22125", SeqStrips.format(SeqStrips.Note, -5.0))
        assertEquals("0", SeqStrips.format(SeqStrips.Note, 0.0))
        // With a scale on the move is in scale degrees.
        assertEquals("+2°", SeqStrips.formatMove(2, degrees = true))
        assertEquals("\u22121°", SeqStrips.formatMove(-1, degrees = true))
        assertEquals("0", SeqStrips.formatMove(0, degrees = true))
        assertEquals("+2", SeqStrips.formatMove(2, degrees = false))
        assertEquals("C", SeqStrips.format(SeqStrips.Key, 0.0))
        assertEquals("C♯", SeqStrips.format(SeqStrips.Key, 1.0))
        assertEquals("A", SeqStrips.format(SeqStrips.Key, 9.0))
        assertEquals("B", SeqStrips.format(SeqStrips.Key, 11.0))
        assertEquals("Off", SeqStrips.format(SeqStrips.ScaleStrip, 0.0))
        assertEquals("Minor", SeqStrips.format(SeqStrips.ScaleStrip, 2.0))
        assertEquals("Blues", SeqStrips.format(SeqStrips.ScaleStrip, 10.0))
    }

    @Test
    fun keyAndScaleStripsCoverEveryChoice() {
        assertEquals(KeyScale.KEY_NAMES, SeqStrips.Key.options)
        assertEquals(Scale.entries.map { it.label }, SeqStrips.ScaleStrip.options)
        // Every strip position lands on a key and a scale, both ends included; defaults are C and Off.
        assertEquals(11.0, ParamMapping.fromNormalized(SeqStrips.Key, 1.0), 0.0)
        assertEquals(Scale.Blues.ordinal.toDouble(), ParamMapping.fromNormalized(SeqStrips.ScaleStrip, 1.0), 0.0)
        assertEquals(0.0, SeqStrips.Key.default, 0.0)
        assertEquals(Scale.Off.ordinal.toDouble(), SeqStrips.ScaleStrip.default, 0.0)
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
        assertEquals(const("MAX_HITS_PER_STEP"), SeqLimits.HITS)
        assertEquals(const("HIT_FIELDS"), SeqLimits.HIT_FIELDS)
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
