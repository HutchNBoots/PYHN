package com.groovebox.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyScaleTest {
    private val names = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    /** The scales as step patterns (1 = semitone), written independently of the intervals. */
    private val steps = mapOf(
        Scale.Off to listOf(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1),
        Scale.Major to listOf(2, 2, 1, 2, 2, 2, 1),
        Scale.Minor to listOf(2, 1, 2, 2, 1, 2, 2),
        Scale.Dorian to listOf(2, 1, 2, 2, 2, 1, 2),
        Scale.Phrygian to listOf(1, 2, 2, 2, 1, 2, 2),
        Scale.Lydian to listOf(2, 2, 2, 1, 2, 2, 1),
        Scale.Mixolydian to listOf(2, 2, 1, 2, 2, 1, 2),
        Scale.HarmonicMinor to listOf(2, 1, 2, 2, 1, 3, 1),
        Scale.MajorPentatonic to listOf(2, 2, 3, 2, 3),
        Scale.MinorPentatonic to listOf(3, 2, 2, 3, 2),
        Scale.Blues to listOf(3, 2, 1, 1, 3, 2),
    )

    private fun pitchClasses(k: KeyScale): Set<Int> = (0..11).filter { k.inKey(60 + it) }.map { (60 + it) % 12 }.toSet()

    private fun spelled(k: KeyScale): String = (0..11).map { (k.root + it) % 12 }.filter { k.inKey(it) }.joinToString(" ") { names[it] }

    @Test
    fun everyScaleIsListedOnce() {
        assertEquals(Scale.entries.toSet(), steps.keys)
        assertEquals(11, Scale.entries.size)
        for ((scale, pattern) in steps) assertEquals(scale.name, 12, pattern.sum())
    }

    /** F8.1: every scale's note set, in all 12 keys, in every octave. */
    @Test
    fun everyScaleInEveryKeyHasTheRightNotes() {
        for ((scale, pattern) in steps) for (root in 0..11) {
            val expected = pattern.runningFold(root) { n, s -> n + s }.dropLast(1).map { it % 12 }.toSet()
            val k = KeyScale(root, scale)
            assertEquals("$root $scale", expected, pitchClasses(k))
            for (note in 0..127) assertEquals("$root $scale $note", Math.floorMod(note, 12) in expected, k.inKey(note))
        }
    }

    @Test
    fun offIsChromatic() {
        for (root in 0..11) {
            val k = KeyScale(root, Scale.Off)
            assertFalse(k.isOn)
            assertTrue((0..127).all(k::inKey))
            assertNull(k.displayName)
            assertEquals(emptySet<Int>(), k.dimmed(48..71))
            assertEquals(emptySet<Int>(), k.roots(48..71))
        }
    }

    @Test
    fun knownSpellings() {
        assertEquals("C D E F G A B", spelled(KeyScale(0, Scale.Major)))
        assertEquals("A B C D E F G", spelled(KeyScale(9, Scale.Minor)))
        assertEquals("D E F G A B C", spelled(KeyScale(2, Scale.Dorian)))
        assertEquals("E F G A B C D", spelled(KeyScale(4, Scale.Phrygian)))
        assertEquals("F G A B C D E", spelled(KeyScale(5, Scale.Lydian)))
        assertEquals("G A B C D E F", spelled(KeyScale(7, Scale.Mixolydian)))
        assertEquals("A B C D E F G#", spelled(KeyScale(9, Scale.HarmonicMinor)))
        assertEquals("C D E G A", spelled(KeyScale(0, Scale.MajorPentatonic)))
        assertEquals("A C D E G", spelled(KeyScale(9, Scale.MinorPentatonic)))
        assertEquals("A C D D# E G", spelled(KeyScale(9, Scale.Blues)))
        assertEquals("F# G# A# B C# D# F", spelled(KeyScale(6, Scale.Major)))
    }

    @Test
    fun rootsAndDecoration() {
        val a = KeyScale(9, Scale.Minor)
        assertTrue(a.isRoot(57) && a.isRoot(69) && a.isRoot(9))
        assertFalse(a.isRoot(60))
        assertEquals(setOf(57, 69), a.roots(48..71))
        assertEquals(setOf(49, 51, 54, 56, 58, 61, 63, 66, 68, 70), a.dimmed(48..71))
    }

    @Test
    fun displayNames() {
        assertEquals("A minor", KeyScale(9, Scale.Minor).displayName)
        assertEquals("C♯ Dorian", KeyScale(1, Scale.Dorian).displayName)
        assertEquals("C major", KeyScale(0, Scale.Major).displayName)
        assertEquals("E harmonic minor", KeyScale(4, Scale.HarmonicMinor).displayName)
        assertEquals("G major pentatonic", KeyScale(7, Scale.MajorPentatonic).displayName)
        assertEquals("A♯ blues", KeyScale(10, Scale.Blues).displayName)
        assertEquals(12, KeyScale.KEY_NAMES.size)
    }

    /** F8.4: stepping lands only on in-key notes, never goes the wrong way, and clamps at the ends. */
    @Test
    fun stepIsMonotonicInKeyAndClamped() {
        for (scale in Scale.entries) for (root in 0..11) {
            val k = KeyScale(root, scale)
            val inKey = (0..127).filter(k::inKey)
            for (n in 0..127) {
                val up = k.step(n, +1)
                val down = k.step(n, -1)
                assertTrue(k.inKey(up) && k.inKey(down))
                // The next in-key note, or the last one at the range end.
                assertEquals(inKey.firstOrNull { it > n } ?: inKey.last(), up)
                assertEquals(inKey.lastOrNull { it < n } ?: inKey.first(), down)
            }
            // Walking up visits every in-key note in order, then stays at the top.
            var n = inKey.first()
            val walk = mutableListOf(n)
            while (true) {
                val next = k.step(n, +1)
                if (next == n) break
                assertTrue(next > n)
                walk += next
                n = next
            }
            assertEquals(inKey, walk)
            assertEquals(inKey.first(), generateSequence(inKey.last()) { k.step(it, -1).takeIf { s -> s != it } }.last())
        }
    }

    @Test
    fun stepRespectsASmallerRange() {
        val c = KeyScale(0, Scale.Major)
        assertEquals(96, c.step(95, +1, 24, 96))
        assertEquals(96, c.step(96, +1, 24, 96))
        assertEquals(24, c.step(24, -1, 24, 96))
        assertEquals(24, c.step(10, +1, 24, 96)) // from below the range: its first in-key note
        val d = KeyScale(2, Scale.Major) // C (24, 96) is not in D major
        assertEquals(95, d.step(96, +1, 24, 96))
        assertEquals(25, d.step(24, -1, 24, 96))
    }

    @Test
    fun snapIsNearestInKeyAndMonotonic() {
        for (scale in Scale.entries) for (root in 0..11) {
            val k = KeyScale(root, scale)
            var last = Int.MIN_VALUE
            for (n in 0..127) {
                val s = k.snap(n, 24, 96)
                assertTrue(k.inKey(s))
                assertTrue(s in 24..96)
                assertTrue("monotonic $root $scale $n", s >= last)
                last = s
                if (k.inKey(n) && n in 24..96) assertEquals(n, s)
            }
        }
        val c = KeyScale(0, Scale.Major)
        assertEquals(60, c.snap(61, 24, 96)) // a tie goes down
        assertEquals(65, c.snap(65, 24, 96))
        val penta = KeyScale(0, Scale.MajorPentatonic) // C D E G A
        assertEquals(64, penta.snap(65, 24, 96))
        assertEquals(67, penta.snap(66, 24, 96))
    }

    /** Build-21 Note strip: degrees keep in-key notes in key, step monotonically, and 0 changes nothing. */
    @Test
    fun movingByDegreesIsInKeyMonotonicAndZeroIsIdentity() {
        for (scale in Scale.entries) for (root in 0..11) {
            val k = KeyScale(root, scale)
            for (note in 24..96) {
                assertEquals("$root $scale $note", note, k.moveByDegrees(note, 0))
                var last = Int.MIN_VALUE
                for (d in -20..20) {
                    val out = k.moveByDegrees(note, d)
                    if (k.inKey(note)) assertTrue("$root $scale $note $d", k.inKey(out))
                    assertTrue("monotonic $root $scale $note $d", out > last)
                    last = out
                }
            }
        }
        assertEquals(62, KeyScale.OFF.moveByDegrees(60, 2))
    }

    @Test
    fun keyboardGateNeverTouchesDrums() {
        val a = KeyScale(9, Scale.Minor)
        for (n in 0..127) {
            assertTrue(a.playable(n, drum = true))
            assertEquals(a.inKey(n), a.playable(n, drum = false))
            assertTrue(KeyScale.OFF.playable(n, drum = false))
        }
    }

    @Test
    fun outOfKeyStepsAreMarked() {
        val a = KeyScale(9, Scale.Minor)
        assertTrue(a.marksStep(Step.of(listOf(57, 61)), drum = false))
        assertFalse(a.marksStep(Step.of(listOf(57, 60)), drum = false))
        assertFalse("empty steps are not playing", a.marksStep(Step(), drum = false))
        assertFalse("drum steps are never marked", a.marksStep(Step.of(listOf(49)), drum = true))
        assertFalse(KeyScale.OFF.marksStep(Step.of(listOf(61)), drum = false))
            }

    @Test
    fun savedValuesFallBack() {
        assertEquals(Scale.Dorian, Scale.named("Dorian"))
        assertEquals(Scale.Off, Scale.named("Klingon"))
        assertEquals(KeyScale(0, Scale.Major), KeyScale.of(12, Scale.Major))
        assertEquals(KeyScale(11, Scale.Major), KeyScale.of(11, Scale.Major))
    }
}
