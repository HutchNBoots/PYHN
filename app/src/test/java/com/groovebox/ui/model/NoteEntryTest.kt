package com.groovebox.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pad and key note entry: NoteMemory, plus the store's toggle and step-note edits (F3.5). */
class NoteEntryTest {
    private val memory = NoteMemory { slot -> if (slot == 0) 48 else 60 }
    private val sink = RecordingSink()
    private val store = PatternStore(sink)

    @Test
    fun defaultsBeforeAnyKey() {
        assertFalse(memory.hasPlayed(0))
        assertEquals(listOf(48), memory.lastNotes(0))
        assertEquals(listOf(60), memory.lastNotes(3))
        assertEquals(0.8f, memory.lastVelocity(3), 0f)
    }

    @Test
    fun aSingleKeyIsRemembered() {
        memory.keyDown(2, 62, 0.6f)
        memory.keyUp(2, 62)
        assertTrue(memory.hasPlayed(2))
        assertEquals(listOf(62), memory.lastNotes(2))
        assertEquals(0.6f, memory.lastVelocity(2), 0f)
        assertEquals("other slots keep their default", listOf(60), memory.lastNotes(3))
    }

    @Test
    fun heldKeysBuildAChordOfAtMostFour() {
        assertEquals(listOf(60), memory.keyDown(1, 60, 1f))
        assertEquals(listOf(60, 64), memory.keyDown(1, 64, 1f))
        assertEquals(listOf(60, 64, 67), memory.keyDown(1, 67, 1f))
        assertEquals(listOf(60, 64, 67, 71), memory.keyDown(1, 71, 1f))
        assertEquals(listOf(60, 64, 67, 71), memory.keyDown(1, 74, 1f))
        for (n in listOf(60, 64, 67, 71, 74)) memory.keyUp(1, n)
        assertEquals("releasing keeps the chord", listOf(60, 64, 67, 71), memory.lastNotes(1))
    }

    @Test
    fun aKeyAfterAllAreReleasedStartsANewChord() {
        memory.keyDown(1, 60, 1f)
        memory.keyDown(1, 64, 1f)
        memory.keyUp(1, 60)
        memory.keyUp(1, 64)
        assertEquals(listOf(57), memory.keyDown(1, 57, 1f))
    }

    @Test
    fun aChordFollowsTheKeysStillHeld() {
        memory.keyDown(1, 60, 1f)
        memory.keyDown(1, 64, 1f)
        memory.keyUp(1, 60)
        assertEquals(listOf(64, 67), memory.keyDown(1, 67, 1f))
    }

    @Test
    fun slotsKeepSeparateChords() {
        memory.keyDown(1, 60, 1f)
        memory.keyDown(2, 64, 1f)
        assertEquals(listOf(60), memory.lastNotes(1))
        assertEquals(listOf(64), memory.lastNotes(2))
    }

    @Test
    fun releaseAllForgetsHeldKeysButNotChords() {
        memory.keyDown(1, 60, 1f)
        memory.releaseAll()
        assertEquals(listOf(62), memory.keyDown(1, 62, 1f))
    }

    @Test
    fun padEntryUsesTheDefaultOnAnEmptyStep() {
        assertEquals(listOf(48) to 0.8f, memory.entryFor(0, Step()))
        assertEquals(listOf(60) to 0.8f, memory.entryFor(4, Step()))
    }

    @Test
    fun padEntryKeepsAStepsOwnNotesUntilKeysArePlayed() {
        val old = Step.of(listOf(45), velocity = 0.6f)
        assertEquals(listOf(45) to 0.6f, memory.entryFor(1, old))
        memory.keyDown(1, 52, 0.9f)
        assertEquals(listOf(52) to 0.9f, memory.entryFor(1, old))
    }

    @Test
    fun drumPadEntryUsesTheLastDrumKey() {
        memory.keyDown(0, 54, 0.7f)
        memory.keyUp(0, 54)
        assertEquals(listOf(54) to 0.7f, memory.entryFor(0, Step()))
    }

    @Test
    fun aPadTapEntersTheLastChord() {
        memory.keyDown(3, 60, 1f)
        memory.keyDown(3, 63, 0.75f)
        val (notes, velocity) = memory.entryFor(3, store.step(3, 0, 8))
        store.toggleStep(3, 0, 8, notes, velocity)
        assertEquals(Step.of(listOf(60, 63), velocity = 0.75f), store.step(3, 0, 8))
        assertEquals(listOf("step 3 0 8 [60 0.75 -1.0 1.0 0.0] [63 0.75 -1.0 1.0 0.0]"), sink.calls)
    }

    @Test
    fun keysPlayedIntoASelectedStepSetItsChord() {
        // As GrooveState.keyDown does with a step selected.
        var chord = memory.keyDown(1, 57, 0.8f)
        store.setStepNotes(1, 0, 4, chord, 0.8f)
        assertEquals(listOf(57), store.step(1, 0, 4).notes)
        chord = memory.keyDown(1, 60, 0.7f)
        store.setStepNotes(1, 0, 4, chord, 0.7f)
        chord = memory.keyDown(1, 64, 0.7f)
        store.setStepNotes(1, 0, 4, chord, 0.7f)
        assertEquals(Step.of(listOf(57, 60, 64), velocity = 0.7f), store.step(1, 0, 4))
        for (n in listOf(57, 60, 64)) memory.keyUp(1, n)
        chord = memory.keyDown(1, 62, 0.9f)
        store.setStepNotes(1, 0, 4, chord, 0.9f)
        assertEquals("a new press replaces the chord", listOf(62), store.step(1, 0, 4).notes)
    }
}
