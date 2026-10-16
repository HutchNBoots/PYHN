package com.groovebox.storage

import com.groovebox.ui.model.LanePoint
import com.groovebox.ui.model.LaneSnapshot
import com.groovebox.ui.model.PatternSettings
import com.groovebox.ui.model.PatternSnapshot
import com.groovebox.ui.model.Step
import com.groovebox.ui.model.SynthParamsDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.random.Random

/** Sessions for the storage tests, built from the real parameter keys. */
object TestSessions {
    private val synthKeys = SynthParamsDef.specs.map { it.key }

    fun small(tempo: Double = 120.0) = Session(
        tempo = tempo,
        ui = UiSnapshot("Seq", 2, 2, 1, 3, "T2"),
        synths = List(7) { i ->
            SynthSnapshot(i, "Preset $i", "Filter", synthKeys.associateWith { k -> k.length * 0.1 + i }, setOf("flt.cutoff"))
        },
        drum = DrumSnapshot(5, List(16) { k -> mapOf("drm.level" to k / 16.0, "drm.tune" to -k.toDouble()) }, setOf("drm.tune")),
        mix = mapOf("dly.time" to 0.375, "mst.volume" to -3.0, "tape.loopLen" to 4.0),
        channels = List(12) { c -> mapOf("ch.volume" to -c.toDouble(), "ch.pan" to c / 12.0) },
        patterns = listOf(
            PatternSnapshot(
                slot = 1,
                pattern = 3,
                settings = PatternSettings(length = 32, rate = 2, shuffle = 0.25f, gate = 0.4f),
                steps = mapOf(0 to Step(true, listOf(45, 52), 0.9f, -1f, 1f), 7 to Step(false, listOf(60), 0.5f, 0.3f, 0.5f)),
                lanes = listOf(LaneSnapshot(8, listOf(LanePoint(0, 1500f, 3), LanePoint(5, 200f, 9)))),
            ),
        ),
        currentPatterns = listOf(0, 3, 0, 0, 0, 0, 0, 15),
        locks = mapOf("seq" to setOf("seq.shuffle"), "tape" to emptySet(), "mixer" to setOf("2:ch.volume")),
    )

    /** Every pattern full: every step on with 4 notes, 8 full lanes. The worst case for size. */
    fun full(): Session {
        val patterns = ArrayList<PatternSnapshot>()
        var stamp = 0L
        for (slot in 0 until 8) for (pattern in 0 until 16) {
            patterns += PatternSnapshot(
                slot, pattern, PatternSettings(64, 1, 0.5f, 0.5f),
                (0 until 64).associateWith { Step(true, listOf(36, 40, 43, 47), 0.7f, 0.25f, 0.9f) },
                List(8) { t -> LaneSnapshot(t, List(64) { s -> LanePoint(s, s * 10f, ++stamp) }) },
            )
        }
        return small().copy(patterns = patterns)
    }
}

class SessionCodecTest {
    @Test
    fun roundTripKeepsEverything() {
        val s = TestSessions.small()
        assertEquals(s, SessionCodec.decode(SessionCodec.encode(s)))
    }

    @Test
    fun roundTripOfAFullSession() {
        val s = TestSessions.full()
        assertEquals(s, SessionCodec.decode(SessionCodec.encode(s)))
    }

    @Test
    fun roundTripOfAnEmptySession() {
        val s = Session(
            120.0, UiSnapshot.DEFAULT, emptyList(), DrumSnapshot(0, emptyList(), emptySet()),
            emptyMap(), emptyList(), emptyList(), emptyList(), emptyMap(),
        )
        assertEquals(s, SessionCodec.decode(SessionCodec.encode(s)))
    }

    @Test
    fun valuesAreExactAndNonFiniteOnesAreDropped() {
        val odd = 0.1 + 0.2 // not a float, must survive as a double
        val s = TestSessions.small().copy(mix = mapOf("a" to odd, "nan" to Double.NaN, "inf" to Double.POSITIVE_INFINITY))
        val back = SessionCodec.decode(SessionCodec.encode(s))!!
        assertEquals(mapOf("a" to odd), back.mix)
    }

    @Test
    fun unicodeNamesSurvive() {
        val s = TestSessions.small()
        val named = s.copy(synths = s.synths.map { it.copy(presetName = "Glass › Wire ✓") })
        assertEquals(named, SessionCodec.decode(SessionCodec.encode(named)))
    }

    @Test
    fun everyTruncationIsRejected() {
        val bytes = SessionCodec.encode(TestSessions.small())
        for (n in 0 until bytes.size) assertNull("truncated to $n", SessionCodec.decode(bytes.copyOf(n)))
    }

    @Test
    fun anyFlippedByteIsRejected() {
        val bytes = SessionCodec.encode(TestSessions.small())
        val rnd = Random(7)
        for (i in bytes.indices) {
            val b = bytes.copyOf()
            b[i] = (b[i].toInt() xor (1 + rnd.nextInt(255))).toByte()
            assertNull("byte $i flipped", SessionCodec.decode(b))
        }
    }

    @Test
    fun randomBytesNeverDecodeOrThrow() {
        val rnd = Random(42)
        repeat(2000) {
            val b = rnd.nextBytes(rnd.nextInt(0, 600))
            assertNull(SessionCodec.decode(b))
        }
    }

    @Test
    fun randomPayloadWithAValidHeaderAndCrcIsRejectedWithoutThrowing() {
        // The CRC can't save us from our own writer's bugs or a hostile file: the parser must cope.
        val rnd = Random(3)
        repeat(2000) {
            val payload = rnd.nextBytes(rnd.nextInt(0, 400))
            assertNull(SessionCodec.decode(frame(SessionCodec.VERSION, payload)))
        }
    }

    @Test
    fun hugeCountsAreRejectedWithoutAllocating() {
        val payload = ByteBuffer.allocate(12).putDouble(120.0).putInt(Int.MAX_VALUE).array()
        assertNull(SessionCodec.decode(frame(0, payload)))
    }

    @Test
    fun aNewerVersionIsRejected() {
        val bytes = SessionCodec.encode(TestSessions.small())
        ByteBuffer.wrap(bytes).putInt(4, SessionCodec.VERSION + 1)
        assertNull(SessionCodec.decode(bytes))
        assertFalse(SessionCodec.isIntact(bytes))
    }

    @Test
    fun aNegativeOrWrongMagicIsRejected() {
        val bytes = SessionCodec.encode(TestSessions.small())
        val negative = bytes.copyOf().also { ByteBuffer.wrap(it).putInt(4, -1) }
        assertNull(SessionCodec.decode(negative))
        val magic = bytes.copyOf().also { it[0] = 'X'.code.toByte() }
        assertNull(SessionCodec.decode(magic))
    }

    @Test
    fun trailingBytesAfterTheFileAreRejected() {
        val bytes = SessionCodec.encode(TestSessions.small())
        assertNull(SessionCodec.decode(bytes + byteArrayOf(0)))
    }

    @Test
    fun versionZeroUpgrades() {
        val s = TestSessions.small()
        val v0 = SessionCodec.encode(s, version = 0)
        assertEquals(0, ByteBuffer.wrap(v0).getInt(4))
        val back = SessionCodec.decode(v0)
        assertNotNull(back)
        back!!
        // What version 0 had comes back, upgraded (sections were lower-case in version 0).
        assertEquals(s.tempo, back.tempo, 0.0)
        assertEquals(s.synths, back.synths)
        assertEquals(s.drum, back.drum)
        assertEquals(s.mix, back.mix)
        assertEquals(s.channels, back.channels)
        assertEquals(s.patterns, back.patterns)
        // What it lacked gets the defaults.
        assertEquals(UiSnapshot.DEFAULT, back.ui)
        assertEquals(List(8) { 0 }, back.currentPatterns)
        assertEquals(emptyMap<String, Set<String>>(), back.locks)
        // And it saves again as the current version.
        assertEquals(SessionCodec.VERSION, ByteBuffer.wrap(SessionCodec.encode(back)).getInt(4))
    }

    @Test
    fun sizeAndSpeedAreSmall() {
        val typical = SessionCodec.encode(TestSessions.small())
        assertTrue("typical session ${typical.size} bytes", typical.size < 32 * 1024)

        val full = TestSessions.full()
        val bytes = SessionCodec.encode(full)
        assertTrue("full session ${bytes.size} bytes", bytes.size < SessionCodec.MAX_FILE_BYTES / 4)
        repeat(5) { SessionCodec.decode(SessionCodec.encode(full)) } // warm up the JIT
        val t0 = System.nanoTime()
        val runs = 10
        repeat(runs) { assertNotNull(SessionCodec.decode(bytes)) }
        val decodeMs = (System.nanoTime() - t0) / 1e6 / runs
        val t1 = System.nanoTime()
        repeat(runs) { SessionCodec.encode(full) }
        val encodeMs = (System.nanoTime() - t1) / 1e6 / runs
        println("session sizes: typical ${typical.size} B, full ${bytes.size} B; full decode $decodeMs ms, encode $encodeMs ms")
        // The worst case on a desktop JVM; the phone is slower, the typical session ~100x smaller.
        assertTrue("decode took $decodeMs ms", decodeMs < 200)
    }

    private fun frame(version: Int, payload: ByteArray): ByteArray {
        val crc = java.util.zip.CRC32().apply { update(payload) }.value.toInt()
        return ByteBuffer.allocate(16 + payload.size).putInt(0x47425853).putInt(version).putInt(payload.size)
            .put(payload).putInt(crc).array()
    }
}
