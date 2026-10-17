package com.groovebox.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Build-22 Seq edits: ×2, halve, page copy, paste, move / copy, fill, rotate, insert / delete, clear. */
class PatternEditsTest {
    private fun hit(note: Int, v: Float = 0.8f, len: Float = SeqLimits.PATTERN_GATE, chance: Float = 1f, nudge: Float = 0f) =
        Hit(note, v, len, chance, nudge)

    private fun pattern(length: Int, steps: Map<Int, Step>, lanes: List<LaneSnapshot> = emptyList(), rate: Int = 1) =
        PatternSnapshot(1, 0, PatternSettings(length = length, rate = rate, shuffle = 0.3f, gate = 0.4f), steps, lanes)

    private fun lane(target: Int, vararg points: Pair<Int, Float>) =
        LaneSnapshot(target, points.map { (s, v) -> LanePoint(s, v, 10L + s) })

    private fun notesAt(p: PatternSnapshot) = p.steps.filterValues { it.on }.mapValues { (_, s) -> s.notes }.toSortedMap()

    private fun laneValues(p: PatternSnapshot, target: Int) =
        p.lanes.first { it.target == target }.points.associate { it.step to it.value }.toSortedMap()

    private val drum = EditScope.drumLane(note = 50, key = 2, paramCount = 10)

    // --- ×2 and halve ---

    @Test
    fun doubleCopiesEveryHitValueAndMotionIntoTheSecondHalf() {
        val p = pattern(
            16,
            mapOf(0 to Step(listOf(hit(45, 1f, 2f, 0.5f, 0.1f))), 6 to Step(listOf(hit(57), hit(60))), 20 to Step(listOf(hit(70)))),
            listOf(lane(8, 0 to 0.2f, 6 to 0.9f, 19 to 0.5f)),
        )
        val d = PatternEdits.doubled(p)!!
        assertEquals(32, d.settings.length)
        assertEquals("rate, shuffle and gate stay", p.settings.copy(length = 32), d.settings)
        assertEquals(p.steps.getValue(0), d.steps.getValue(16))
        assertEquals(listOf(57, 60), d.steps.getValue(22).notes)
        // A hidden step inside the copy's range is overwritten (empty, as step 4 is).
        assertFalse(d.steps.containsKey(20))
        assertEquals(sortedMapOf(0 to 0.2f, 6 to 0.9f, 16 to 0.2f, 22 to 0.9f), laneValues(d, 8))
    }

    @Test
    fun doubleWorksForOddLengthsAndStopsAbove32() {
        val p = pattern(12, mapOf(11 to Step(listOf(hit(60)))))
        val d = PatternEdits.doubled(p)!!
        assertEquals(24, d.settings.length)
        assertEquals(sortedMapOf(11 to listOf(60), 23 to listOf(60)), notesAt(d))
        assertEquals(64, PatternEdits.doubled(pattern(32, emptyMap()))!!.settings.length)
        assertNull(PatternEdits.doubled(pattern(33, emptyMap())))
    }

    @Test
    fun halveRoundsUpAndKeepsTheHiddenSteps() {
        val p = pattern(32, mapOf(0 to Step(listOf(hit(60))), 20 to Step(listOf(hit(62)))))
        val h = PatternEdits.halved(p)!!
        assertEquals(16, h.settings.length)
        assertEquals(p.steps, h.steps)
        assertEquals(7, PatternEdits.halved(pattern(13, emptyMap()))!!.settings.length)
        assertNull(PatternEdits.halved(pattern(1, emptyMap())))
    }

    @Test
    fun doubleThenHalveIsBackWhereItStarted() {
        val p = pattern(16, mapOf(3 to Step(listOf(hit(60)))))
        assertEquals(16, PatternEdits.halved(PatternEdits.doubled(p)!!)!!.settings.length)
    }

    // --- review fixes: full drum steps, page copy on non-16 lengths ---

    private val full = Step((36 until 44).map { hit(it) }) // 8 other sounds, no lane (50)

    @Test
    fun mergeNeverDropsTheLaneHitOnAFullStep() {
        assertEquals(full, drum.merge(full, Step(listOf(hit(50)))))
        assertFalse(drum.fits(full, Step(listOf(hit(50)))))
        assertTrue("an empty part always fits", drum.fits(full, Step()))
        assertTrue(EditScope.WHOLE.fits(full, Step(listOf(hit(50)))))
    }

    @Test
    fun moveFillPasteRotateIntoAFullStepAreRefused() {
        val p = pattern(16, mapOf(0 to Step(listOf(hit(50))), 4 to full))
        assertNull(PatternEdits.moved(p, 0, 4, copy = false, scope = drum))
        assertNull(PatternEdits.moved(p, 0, 4, copy = true, scope = drum))
        assertNull(PatternEdits.filled(p, 0, 2, Step(listOf(hit(50))), drum))
        assertNull(PatternEdits.pasted(p, listOf(0), 4, drum))
        assertNull(PatternEdits.rotated(p, 4, drum))
        assertNull(PatternEdits.inserted(p, 3, drum))
        // Into a step with room it works.
        assertEquals(listOf(50), PatternEdits.moved(p, 0, 5, copy = false, scope = drum)!!.steps.getValue(5).notes)
    }

    @Test
    fun pageCopyOfA12StepTripletPatternAppendsWithoutAGap() {
        val steps = (0 until 12).associateWith { Step.of(listOf(60 + it)) }
        val out = PatternEdits.pageCopied(pattern(12, steps, rate = 3), 0, 1)
        assertEquals(24, out.settings.length)
        assertEquals((0 until 24).toList(), notesAt(out).keys.toList())
        assertEquals(listOf(60), out.steps.getValue(12).notes)
        assertEquals(listOf(71), out.steps.getValue(23).notes)
    }

    @Test
    fun pageCopyCopiesOnlyTheUsedStepsOfAPartPageAndCapsAt64() {
        // 40 steps: page 3 (index 2) uses 8; copied to page 4 it lands after the end (40..47).
        val p = pattern(40, mapOf(32 to Step.of(listOf(60)), 39 to Step.of(listOf(61))))
        val out = PatternEdits.pageCopied(p, 2, 3)
        assertEquals(48, out.settings.length)
        assertEquals(listOf(32, 39, 40, 47), notesAt(out).keys.toList())
        assertEquals(0, PatternEdits.pageUsed(40, 3))
        assertSame("an unused page copies nothing", p, PatternEdits.pageCopied(p, 3, 0))
        // Whole pages: onto the page tapped, even past the end.
        assertEquals(48, PatternEdits.pageCopyStart(16, 3))
        assertEquals(12, PatternEdits.pageCopyStart(12, 3))
    }

    // --- page copy ---

    @Test
    fun pageCopyCopiesHitsAndMotionAndLengthensThePattern() {
        val p = pattern(16, mapOf(1 to Step(listOf(hit(60, 0.5f))), 15 to Step(listOf(hit(64)))), listOf(lane(3, 1 to 0.7f)))
        val c = PatternEdits.pageCopied(p, from = 0, to = 2)
        assertEquals(48, c.settings.length)
        assertEquals(sortedMapOf(1 to listOf(60), 15 to listOf(64), 33 to listOf(60), 47 to listOf(64)), notesAt(c))
        assertEquals(0.5f, c.steps.getValue(33).hits[0].velocity)
        assertEquals(sortedMapOf(1 to 0.7f, 33 to 0.7f), laneValues(c, 3))
    }

    @Test
    fun pageCopyOverwritesTheTargetPageAndKeepsALongerLength() {
        val p = pattern(64, mapOf(16 to Step(listOf(hit(50))), 0 to Step(listOf(hit(60)))))
        val c = PatternEdits.pageCopied(p, from = 0, to = 1)
        assertEquals(64, c.settings.length)
        assertEquals(sortedMapOf(0 to listOf(60), 16 to listOf(60)), notesAt(c))
        assertSame(p, PatternEdits.pageCopied(p, 1, 1))
    }

    // --- paste ---

    @Test
    fun pasteKeepsTheSpacingFromTheEarliestSelectedStep() {
        val p = pattern(32, mapOf(2 to Step(listOf(hit(60))), 5 to Step(listOf(hit(64, 0.3f))), 9 to Step(listOf(hit(67)))))
        val out = PatternEdits.pasted(p, listOf(5, 2), anchor = 20, scope = EditScope.WHOLE)!!
        assertEquals(sortedMapOf(2 to listOf(60), 5 to listOf(64), 9 to listOf(67), 20 to listOf(60), 23 to listOf(64)), notesAt(out))
        assertEquals(0.3f, out.steps.getValue(23).hits[0].velocity)
    }

    @Test
    fun pastePastStep64DropsTheRestAndSkipsEmptySources() {
        val p = pattern(64, mapOf(0 to Step(listOf(hit(60))), 4 to Step(listOf(hit(62))), 62 to Step(listOf(hit(70)))))
        val out = PatternEdits.pasted(p, listOf(0, 2, 4), anchor = 61, scope = EditScope.WHOLE)!!
        // 0 → 61; 2 (empty) skipped, so 62 keeps its note; 4 → 65 is past the end.
        assertEquals(sortedMapOf(0 to listOf(60), 4 to listOf(62), 61 to listOf(60), 62 to listOf(70)), notesAt(out))
    }

    @Test
    fun pasteOnADrumLaneCopiesOnlyThatSoundAndItsMotion() {
        val p = pattern(
            16,
            mapOf(0 to Step(listOf(hit(48), hit(50, 0.4f))), 8 to Step(listOf(hit(54)))),
            listOf(lane(25, 0 to 0.6f), lane(5, 0 to 0.1f)), // 25 = key 2's param 5; 5 = key 0's
        )
        val out = PatternEdits.pasted(p, listOf(0), anchor = 8, scope = drum)!!
        assertEquals(listOf(54, 50), out.steps.getValue(8).notes)
        assertEquals(0.4f, out.steps.getValue(8).hit(50)!!.velocity)
        assertEquals(sortedMapOf(0 to 0.6f, 8 to 0.6f), laneValues(out, 25))
        assertEquals("another sound's motion stays", sortedMapOf(0 to 0.1f), laneValues(out, 5))
    }

    // --- move and copy ---

    @Test
    fun moveTakesEveryValueAndTheMotionAlong() {
        val h = hit(60, 0.4f, 2f, 0.5f, -0.2f)
        val p = pattern(16, mapOf(5 to Step(listOf(h)), 6 to Step(listOf(hit(72)))), listOf(lane(9, 5 to 0.3f, 6 to 0.8f)))
        val out = PatternEdits.moved(p, 5, 6, copy = false, scope = EditScope.WHOLE)!!
        assertEquals(sortedMapOf(6 to listOf(60)), notesAt(out))
        assertEquals(h, out.steps.getValue(6).hits.single())
        assertEquals(sortedMapOf(6 to 0.3f), laneValues(out, 9))
    }

    @Test
    fun copyLeavesTheSource() {
        val p = pattern(16, mapOf(5 to Step(listOf(hit(60)))), listOf(lane(9, 5 to 0.3f)))
        val out = PatternEdits.moved(p, 5, 11, copy = true, scope = EditScope.WHOLE)!!
        assertEquals(sortedMapOf(5 to listOf(60), 11 to listOf(60)), notesAt(out))
        assertEquals(sortedMapOf(5 to 0.3f, 11 to 0.3f), laneValues(out, 9))
    }

    @Test
    fun moveOnADrumLaneMovesOnlyThatSound() {
        val p = pattern(16, mapOf(4 to Step(listOf(hit(48), hit(50, 0.9f))), 6 to Step(listOf(hit(54)))))
        val out = PatternEdits.moved(p, 4, 6, copy = false, scope = drum)!!
        assertEquals(listOf(48), out.steps.getValue(4).notes)
        assertEquals(listOf(54, 50), out.steps.getValue(6).notes)
    }

    @Test
    fun movingAPadWithoutThePartChangesNothing() {
        val p = pattern(16, mapOf(4 to Step(listOf(hit(48)))))
        assertSame(p, PatternEdits.moved(p, 4, 6, copy = false, scope = drum))
        assertSame(p, PatternEdits.moved(p, 3, 6, copy = false, scope = EditScope.WHOLE))
        assertSame(p, PatternEdits.moved(p, 4, 4, copy = false, scope = EditScope.WHOLE))
    }

    // --- fill ---

    @Test
    fun fillPutsThePartOnEveryIntervalToThePatternsEnd() {
        val p = pattern(16, mapOf(2 to Step(listOf(hit(48)))))
        val out = PatternEdits.filled(p, a = 2, b = 4, part = Step(listOf(hit(50))), scope = drum)!!
        assertEquals(listOf(2, 4, 6, 8, 10, 12, 14), notesAt(out).keys.toList())
        assertEquals(listOf(48, 50), out.steps.getValue(2).notes)
        assertEquals("B before A fills from B", listOf(0, 3, 6, 9, 12, 15), notesAt(PatternEdits.filled(pattern(16, emptyMap()), 3, 0, Step(listOf(hit(50))), drum)!!).keys.toList())
    }

    @Test
    fun fillOnASynthSlotWritesThePenNotesOverEachStep() {
        val p = pattern(12, mapOf(4 to Step(listOf(hit(70)))))
        val pen = Step(listOf(hit(57), hit(60)))
        val out = PatternEdits.filled(p, 0, 4, pen, EditScope.WHOLE)!!
        assertEquals(sortedMapOf(0 to listOf(57, 60), 4 to listOf(57, 60), 8 to listOf(57, 60)), notesAt(out))
        assertSame(p, PatternEdits.filled(p, 3, 3, pen, EditScope.WHOLE))
    }

    // --- rotate, insert, delete ---

    @Test
    fun rotateWrapsAtTheLengthAndLeavesHiddenStepsAlone() {
        val p = pattern(8, mapOf(0 to Step(listOf(hit(60))), 7 to Step(listOf(hit(62))), 20 to Step(listOf(hit(70)))), listOf(lane(4, 7 to 0.5f)))
        val r = PatternEdits.rotated(p, 1, EditScope.WHOLE)!!
        assertEquals(sortedMapOf(0 to listOf(62), 1 to listOf(60), 20 to listOf(70)), notesAt(r))
        assertEquals(sortedMapOf(0 to 0.5f), laneValues(r, 4))
        assertEquals(notesAt(p), notesAt(PatternEdits.rotated(r, -1, EditScope.WHOLE)!!))
        assertSame(p, PatternEdits.rotated(p, 8, EditScope.WHOLE))
    }

    @Test
    fun rotateOnADrumLaneRotatesOnlyThatSound() {
        val p = pattern(4, mapOf(0 to Step(listOf(hit(48), hit(50))), 1 to Step(listOf(hit(48)))))
        val r = PatternEdits.rotated(p, 1, drum)!!
        assertEquals(sortedMapOf(0 to listOf(48), 1 to listOf(48, 50)), notesAt(r))
    }

    @Test
    fun insertPushesTheLineLaterFromTheStepAndDropsTheLast() {
        val p = pattern(8, (0 until 8).associateWith { Step(listOf(hit(60 + it))) })
        val out = PatternEdits.inserted(p, 3, EditScope.WHOLE)!!
        assertEquals(listOf(0, 1, 2, 4, 5, 6, 7), notesAt(out).keys.toList())
        assertEquals(listOf(62), out.steps.getValue(2).notes)
        assertEquals(listOf(63), out.steps.getValue(4).notes)
        assertEquals(listOf(66), out.steps.getValue(7).notes)
    }

    @Test
    fun deletePullsTheLineEarlierAndEmptiesTheLastStep() {
        val p = pattern(8, (0 until 8).associateWith { Step(listOf(hit(60 + it))) }, listOf(lane(2, 4 to 0.9f)))
        val out = PatternEdits.deleted(p, 3, EditScope.WHOLE)!!
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), notesAt(out).keys.toList())
        assertEquals(listOf(64), out.steps.getValue(3).notes)
        assertEquals(sortedMapOf(3 to 0.9f), laneValues(out, 2))
    }

    // --- clear, empty ---

    @Test
    fun clearRemovesTheNotesOnlyAndKeepsSettingsAndMotion() {
        val p = pattern(24, mapOf(1 to Step(listOf(hit(60)))), listOf(lane(3, 1 to 0.2f)), rate = 3)
        val c = PatternEdits.notesCleared(p)
        assertTrue(c.steps.isEmpty())
        assertEquals(p.settings, c.settings)
        assertEquals(p.lanes, c.lanes)
    }

    @Test
    fun nextEmptySkipsTheCurrentAndWraps() {
        val empty = setOf(0, 3, 9)
        assertEquals(3, PatternEdits.nextEmpty(1) { it in empty })
        assertEquals(0, PatternEdits.nextEmpty(9) { it in empty })
        assertNull(PatternEdits.nextEmpty(5) { it == 5 })
        assertTrue(PatternEdits.isEmpty(pattern(32, emptyMap())))
        assertFalse(PatternEdits.isEmpty(pattern(16, emptyMap(), listOf(lane(1, 0 to 0f)))))
    }
}
