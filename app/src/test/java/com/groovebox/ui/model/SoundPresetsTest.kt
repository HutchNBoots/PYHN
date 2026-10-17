package com.groovebox.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundPresetsTest {
    private val glassWire = mapOf("flt.reso" to 0.55, "flt.cutoff" to 3200.0, "osc.wave" to 1.0)

    // --- double-tap = the preset's value (manual 1-4A) ---

    @Test
    fun doubleTapReturnsToThePresetsValueNotTheEngineDefault() {
        assertEquals(0.55, SoundPresets.resetValue("flt.reso", glassWire, engineDefault = 0.2), 0.0)
    }

    @Test
    fun aParamThePresetDoesNotKnowResetsToTheDefault() {
        assertEquals(0.2, SoundPresets.resetValue("flt.new", glassWire, engineDefault = 0.2), 0.0)
        assertEquals(0.2, SoundPresets.resetValue("flt.reso", emptyMap(), engineDefault = 0.2), 0.0)
    }

    // --- the edited mark ---

    @Test
    fun editedWhenAnyValueLeftTheLoadedOnes() {
        assertFalse(SoundPresets.edited(glassWire, glassWire))
        assertTrue(SoundPresets.edited(glassWire + ("flt.reso" to 0.6), glassWire))
    }

    @Test
    fun floatRoundingFromTheEngineIsNotAnEdit() {
        val viaFloat = glassWire.mapValues { it.value.toFloat().toDouble() }
        assertFalse(SoundPresets.edited(viaFloat, glassWire))
    }

    @Test
    fun theMarkReadsAsAStarAfterTheName() {
        assertEquals("Glass Wire*", SoundPresets.displayName("Glass Wire", edited = true))
        assertEquals("Glass Wire", SoundPresets.displayName("Glass Wire", edited = false))
    }

    // --- preset loads respect locks (1-2c) ---

    @Test
    fun aLoadKeepsLockedValuesAndTakesTheRest() {
        val current = mapOf("flt.reso" to 0.9, "flt.cutoff" to 500.0, "osc.wave" to 0.0)
        val after = SoundPresets.applyRespectingLocks(glassWire, current, locked = setOf("flt.cutoff"))
        assertEquals(mapOf("flt.reso" to 0.55, "flt.cutoff" to 500.0, "osc.wave" to 1.0), after)
    }

    @Test
    fun aLockedRateAlsoKeepsItsSync() {
        // The Rate strip drives lfo.rate and lfo.sync (the detent), so its lock keeps both.
        val preset = mapOf("lfo.rate" to 6.0, "lfo.sync" to 0.0)
        val current = mapOf("lfo.rate" to 4.0, "lfo.sync" to 1.0)
        assertEquals(current, SoundPresets.applyRespectingLocks(preset, current, setOf("lfo.rate")))
    }

    // --- user presets: auto-named, no dialog (1-4B) ---

    @Test
    fun theFirstSaveOfAPresetIsNumberTwo() {
        assertEquals("Rubber Floor 2", SoundPresets.userName("Rubber Floor", taken = listOf("Rubber Floor")))
    }

    @Test
    fun savingAgainCountsOnFromTheBaseName() {
        val taken = listOf("Rubber Floor", "Rubber Floor 2", "Rubber Floor 3")
        assertEquals("Rubber Floor 4", SoundPresets.userName("Rubber Floor 3", taken))
        assertEquals("Rubber Floor 4", SoundPresets.userName("Rubber Floor", taken))
    }

    @Test
    fun aGapIsFilledFirst() {
        assertEquals("Slow Tide 2", SoundPresets.userName("Slow Tide 3", listOf("Slow Tide", "Slow Tide 3")))
    }

    @Test
    fun theEditedStarIsNeverPartOfTheName() {
        assertEquals("Glass Wire 2", SoundPresets.userName("Glass Wire*", listOf("Glass Wire")))
    }

    @Test
    fun baseNamesDropOnlyATrailingNumber() {
        assertEquals("Rubber Floor", SoundPresets.baseName("Rubber Floor 12"))
        assertEquals("Init", SoundPresets.baseName("Init"))
        assertEquals("808", SoundPresets.baseName("808"))
        assertEquals("Floor", SoundPresets.baseName("  Floor  "))
    }

    @Test
    fun aFullLibraryRefusesToSave() {
        val taken = List(SoundPresets.MAX_USER_PRESETS) { "Init ${it + 2}" }
        assertNull(SoundPresets.userName("Init", taken, userCount = SoundPresets.MAX_USER_PRESETS))
    }

    // --- copy a sound to another slot ---

    @Test
    fun aCopyTakesTheSourceSoundAndKeepsTheTargetsLocks() {
        val source = SynthSound(PresetRef("Glass Wire", 4), glassWire + ("flt.reso" to 0.8), glassWire, glassWire)
        val target = SynthSound(PresetRef("Slow Tide", 7), mapOf("flt.reso" to 0.1, "flt.cutoff" to 900.0, "osc.wave" to 2.0), emptyMap(), emptyMap())
        val copied = SoundPresets.copy(source, target, targetLocks = setOf("flt.cutoff"))
        assertEquals(PresetRef("Glass Wire", 4), copied.preset)
        assertEquals(mapOf("flt.reso" to 0.8, "flt.cutoff" to 900.0, "osc.wave" to 1.0), copied.values)
        // It reads as "Glass Wire*": edited compared with the source's loaded values.
        assertTrue(SoundPresets.edited(copied.values, copied.loadValues))
        assertEquals(glassWire, copied.presetValues)
    }

    // --- preset groups (1-1B) ---

    @Test
    fun theSixteenFactoryPresetsFallIntoTheirGroups() {
        val names = listOf(
            "First Light", "Rubber Floor", "Deep Current", "Gravel Bass", "Glass Wire", "Copper Lead", "Whistle Pine",
            "Slow Tide", "Velvet Fog", "Northern Hall", "Rain Glass", "Thumb Piano", "Paper Harp", "Siren Loop", "Static Garden", "Laser Drip",
        )
        val groups = PresetGroups.groups(names)
        assertEquals(listOf("Start", "Bass", "Lead", "Pad", "Pluck", "FX"), groups.map { it.first })
        assertEquals(listOf(1, 2, 3), groups[1].second)
        assertEquals(listOf(13, 14, 15), groups[5].second)
        assertEquals((0..15).toList(), groups.flatMap { it.second })
    }

    @Test
    fun anUnknownPresetNameStillGetsAGroupByItsPlace() {
        val groups = PresetGroups.groups(List(16) { "P$it" })
        assertEquals(listOf("Start", "Bass", "Lead", "Pad", "Pluck", "FX"), groups.map { it.first })
        assertEquals(listOf(4, 5, 6), groups[2].second)
    }
}
