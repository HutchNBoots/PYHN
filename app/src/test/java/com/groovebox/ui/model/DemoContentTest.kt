package com.groovebox.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoContentTest {
    private val engineNames = listOf(
        "First Light", "Rubber Floor", "Deep Current", "Gravel Bass", "Glass Wire", "Copper Lead", "Whistle Pine",
        "Slow Tide", "Velvet Fog", "Northern Hall", "Rain Glass", "Thumb Piano", "Paper Harp", "Siren Loop",
        "Static Garden", "Laser Drip",
    )

    @Test
    fun everySynthSlotGetsADifferentPresetByName() {
        val idx = DemoContent.presetIndexes(engineNames)
        assertEquals(7, idx.size)
        assertEquals(7, idx.toSet().size)
        assertEquals(DemoContent.PRESETS, idx.map { engineNames[it] })
    }

    @Test
    fun missingNamesFallBackToUnusedPresets() {
        val names = listOf("A", "B", "Glass Wire", "C", "D", "E", "F", "G")
        val idx = DemoContent.presetIndexes(names)
        assertEquals(7, idx.size)
        assertEquals(7, idx.toSet().size)
        assertEquals(2, idx[1]) // "Glass Wire" keeps its slot
    }

    @Test
    fun fewerPresetsThanSlotsStillGivesEverySlotOne() {
        val idx = DemoContent.presetIndexes(listOf("Only"))
        assertEquals(List(7) { 0 }, idx)
        assertTrue(DemoContent.presetIndexes(emptyList()).isEmpty())
    }

    @Test
    fun theDemoIsABeatAndABasslineAt120() {
        val sink = RecordingSink()
        val store = PatternStore(sink)
        DemoContent.write(store)
        assertEquals(120.0, store.tempo, 0.0)
        assertEquals("tempo 120.0", sink.calls.first())
        // Drums: kick on the downbeat, snare on the backbeat, hats throughout.
        assertTrue(48 in store.step(0, 0, 0).notes)
        assertTrue(50 in store.step(0, 0, 4).notes)
        assertTrue(50 in store.step(0, 0, 12).notes)
        val drumNotes = (0 until 16).flatMap { store.step(0, 0, it).notes }
        assertTrue(drumNotes.all { it in 48..63 })
        assertTrue(drumNotes.count { it == 54 } >= 6)
        assertEquals(16, store.settings(0, 0).length)
        // Bass: A natural minor, one note per step, on the downbeat.
        val aMinor = setOf(9, 11, 0, 2, 4, 5, 7)
        val bass = (0 until 16).map { store.step(1, 0, it) }.filter { it.on }
        assertTrue(bass.size >= 8)
        assertTrue(bass.all { it.notes.size == 1 && it.notes[0] % 12 in aMinor })
        assertEquals(listOf(45), store.step(1, 0, 0).notes)
        assertEquals(16, store.settings(1, 0).length)
        // Every write reached the engine.
        val stepCalls = sink.calls.count { it.startsWith("step") }
        assertEquals(DemoContent.BEAT.size + DemoContent.BASS.size, stepCalls)
        assertTrue(sink.calls.any { it.startsWith("settings 1 0 16") })
        // Nothing else is touched.
        for (slot in 2 until 8) assertTrue((0 until 64).none { store.step(slot, 0, it).on })
    }
}
