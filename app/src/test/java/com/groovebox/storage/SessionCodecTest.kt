package com.groovebox.storage

import com.groovebox.ui.model.DrumLockKeys
import com.groovebox.ui.model.Hit
import com.groovebox.ui.model.KeyScale
import com.groovebox.ui.model.LanePoint
import com.groovebox.ui.model.LaneSnapshot
import com.groovebox.ui.model.PatternSettings
import com.groovebox.ui.model.PatternSnapshot
import com.groovebox.ui.model.Scale
import com.groovebox.ui.model.Step
import com.groovebox.ui.model.SynthParamsDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Base64
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
                // Steps the older layouts can hold too (one velocity, length and chance per step, no nudge),
                // so the upgrade tests can compare them; [full] has per-hit values.
                steps = mapOf(0 to Step.of(listOf(45, 52), 0.9f), 7 to Step(listOf(Hit(60, 0.5f, 0.3f, 0.5f)))),
                lanes = listOf(LaneSnapshot(8, listOf(LanePoint(0, 1500f, 3), LanePoint(5, 200f, 9)))),
            ),
        ),
        currentPatterns = listOf(0, 3, 0, 0, 0, 0, 0, 15),
        locks = mapOf("seq" to setOf("seq.shuffle"), "tape" to emptySet(), "mixer" to setOf("2:ch.volume")),
        keyScale = KeyScale(9, Scale.Minor),
    )

    /** Every pattern full: every step with 8 hits, each its own values, 8 full lanes. The worst case for size. */
    fun full(): Session {
        val patterns = ArrayList<PatternSnapshot>()
        var stamp = 0L
        for (slot in 0 until 8) for (pattern in 0 until 16) {
            patterns += PatternSnapshot(
                slot, pattern, PatternSettings(64, 1, 0.5f, 0.5f),
                (0 until 64).associateWith { i ->
                    Step(List(8) { h -> Hit(36 + h, 0.1f * (h + 1), if (h == 0) -1f else h * 0.5f, 1f - h * 0.1f, (h - 4) * 0.1f + i * 0.001f) })
                },
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
        // Build-22: a kit-wide drum lock comes back on every sound.
        assertEquals(s.drum.copy(locks = DrumLockKeys.migrate(s.drum.locks)), back.drum)
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
    fun everyKeyAndScaleRoundTrips() {
        for (scale in Scale.entries) for (root in 0..11) {
            val s = TestSessions.small().copy(keyScale = KeyScale(root, scale))
            assertEquals(s, SessionCodec.decode(SessionCodec.encode(s)))
        }
    }

    /**
     * F8.5: a real version 1 file, written by the M4 codec before F8 (bytes captured from it, not
     * re-encoded here), still loads, with the scale off, and saves again as the current version.
     * Its step that was off is dropped (build-20: a step is on when it has a hit).
     */
    @Test
    fun aVersionOneFileFromM4LoadsWithTheScaleOff() {
        val v1 = Base64.getDecoder().decode(V1_FILE)
        assertEquals(1, ByteBuffer.wrap(v1).getInt(4))
        val back = SessionCodec.decode(v1)
        assertEquals(V1_SESSION, back)
        assertEquals(KeyScale.OFF, back!!.keyScale)
        val again = SessionCodec.encode(back)
        assertEquals(SessionCodec.VERSION, ByteBuffer.wrap(again).getInt(4))
        assertEquals(back, SessionCodec.decode(again))
    }

    /**
     * Build-20: a real version 2 file (written by the F8 codec, bytes captured before v3) loads
     * as hits: one per note with the step's velocity, gate (as the length, -1 = pattern gate)
     * and probability, nudge 0. Steps that were off, or on without a note, are dropped.
     */
    @Test
    fun aVersionTwoFileMigratesToHits() {
        val v2 = Base64.getDecoder().decode(V2_FILE)
        assertEquals(2, ByteBuffer.wrap(v2).getInt(4))
        val back = SessionCodec.decode(v2)
        assertEquals(V2_SESSION, back)
        val again = SessionCodec.encode(back!!)
        assertEquals(SessionCodec.VERSION, ByteBuffer.wrap(again).getInt(4))
        assertEquals(back, SessionCodec.decode(again))
    }

    @Test
    fun aCorruptVersionTwoFileIsRejected() {
        val v2 = Base64.getDecoder().decode(V2_FILE)
        for (n in 0 until v2.size) assertNull("truncated to $n", SessionCodec.decode(v2.copyOf(n)))
    }

    @Test
    fun versionThreeKeepsEveryHitsOwnValues() {
        val steps = mapOf(
            0 to Step(listOf(Hit(48, 1f, -1f, 1f, 0f), Hit(50, 0.35f, 2.5f, 0.25f, -0.5f), Hit(54, 0.01f, 1f / 16, 0f, 0.5f))),
            63 to Step((48 until 56).map { Hit(it, 0.5f, 16f, 0.5f, 0.125f) }),
        )
        val s = TestSessions.small().copy(patterns = listOf(PatternSnapshot(0, 15, PatternSettings(), steps, emptyList())))
        assertEquals(s, SessionCodec.decode(SessionCodec.encode(s)))
    }

    @Test
    fun aStepWithMoreHitsThanTheEngineHoldsIsRejected() {
        val s = TestSessions.small().copy(
            patterns = listOf(PatternSnapshot(0, 0, PatternSettings(), mapOf(0 to Step((40 until 49).map { Hit(it) })), emptyList())),
        )
        assertNull(SessionCodec.decode(SessionCodec.encode(s)))
    }

    @Test
    fun aCorruptVersionOneFileIsRejected() {
        val v1 = Base64.getDecoder().decode(V1_FILE)
        for (n in 0 until v1.size) assertNull("truncated to $n", SessionCodec.decode(v1.copyOf(n)))
        val flipped = v1.copyOf().also { it[40] = (it[40].toInt() xor 0x10).toByte() }
        assertNull(SessionCodec.decode(flipped))
    }

    @Test
    fun unknownScaleNamesAndBadRootsPlayChromaticallyOrInC() {
        val s = TestSessions.small()
        val good = SessionCodec.encode(s)
        // Rewrite the key block at the payload's end: root int, then the scale name as modified UTF-8.
        fun withKey(root: Int, name: String): ByteArray {
            val nameLen = 2 + "Minor".length
            val payload = good.copyOfRange(12, good.size - 4)
            val head = payload.copyOf(payload.size - 4 - nameLen)
            val utf = name.toByteArray(Charsets.UTF_8)
            val tail = ByteBuffer.allocate(4 + 2 + utf.size).putInt(root).putShort(utf.size.toShort()).put(utf).array()
            return frame(SessionCodec.VERSION, head + tail)
        }
        assertEquals(s, SessionCodec.decode(withKey(9, "Minor"))) // the rewrite itself is faithful
        assertEquals(KeyScale(9, Scale.Off), SessionCodec.decode(withKey(9, "Klingon"))!!.keyScale)
        assertEquals(KeyScale(0, Scale.Minor), SessionCodec.decode(withKey(40, "Minor"))!!.keyScale)
        assertEquals(KeyScale(0, Scale.Minor), SessionCodec.decode(withKey(-1, "Minor"))!!.keyScale)
    }

    // --- version 4 (build-22) ---

    private fun withV4() = TestSessions.small().copy(
        ui = UiSnapshot("Synth", 3, 3, 0, 1, "4", keyOctave = -1),
        synths = TestSessions.small().synths.mapIndexed { i, s -> if (i == 2) s.copy(presetName = "Glass Wire 2", userPreset = true) else s },
        drum = DrumSnapshot(5, List(16) { mapOf("drum.decay" to it / 16.0) }, setOf("drum.decay@3", "drum.pan@0")),
        userPresets = listOf(
            UserPreset("Glass Wire 2", mapOf("flt.cutoff" to 3200.0, "flt.reso" to 0.55)),
            UserPreset("Rubber Floor 2", mapOf("osc.wave" to 1.5)),
            UserPreset("Ünïcode ★ 3", emptyMap()),
        ),
    )

    @Test
    fun versionFourKeepsUserPresetsTheKeyboardOctaveAndPerSoundLocks() {
        val s = withV4()
        val bytes = SessionCodec.encode(s)
        assertEquals(4, ByteBuffer.wrap(bytes).getInt(4))
        assertEquals(s, SessionCodec.decode(bytes))
    }

    @Test
    fun aVersionThreeFileLoadsWithNoUserPresetsAndKitWideLocksSpread() {
        val s = withV4()
        val v3 = SessionCodec.encode(s, version = 3)
        assertEquals(3, ByteBuffer.wrap(v3).getInt(4))
        val back = SessionCodec.decode(v3)!!
        assertEquals(emptyList<UserPreset>(), back.userPresets)
        assertEquals(0, back.ui.keyOctave)
        assertTrue(back.synths.none { it.userPreset })
        // Already per-sound keys stay as they are; a v3 kit-wide one spreads to all 16.
        assertEquals(setOf("drum.decay@3", "drum.pan@0"), back.drum.locks)
        val kitWide = SessionCodec.decode(SessionCodec.encode(s.copy(drum = s.drum.copy(locks = setOf("drum.tone"))), version = 3))!!
        assertEquals((0 until 16).map { "drum.tone@$it" }.toSet(), kitWide.drum.locks)
        // And it saves again as version 4.
        assertEquals(SessionCodec.VERSION, ByteBuffer.wrap(SessionCodec.encode(back)).getInt(4))
    }

    @Test
    fun aKeyboardOctaveOutOfRangeIsClampedNotRejected() {
        val s = withV4().copy(ui = withV4().ui.copy(keyOctave = 9))
        assertEquals(2, SessionCodec.decode(SessionCodec.encode(s))!!.ui.keyOctave)
    }

    @Test
    fun aTruncatedVersionFourFileIsRejected() {
        val bytes = SessionCodec.encode(withV4())
        for (n in 0 until bytes.size step 7) assertNull("truncated to $n", SessionCodec.decode(bytes.copyOf(n)))
    }

    @Test
    fun sizeAndSpeedAreSmall() {
        val typical = SessionCodec.encode(TestSessions.small())
        assertTrue("typical session ${typical.size} bytes", typical.size < 32 * 1024)

        val full = TestSessions.full()
        val bytes = SessionCodec.encode(full)
        // Every step of every pattern with 8 hits (20 bytes each) is about 2.4 MB: still well inside the limit.
        assertTrue("full session ${bytes.size} bytes", bytes.size < SessionCodec.MAX_FILE_BYTES / 2)
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

    private companion object {
        /** SessionCodec.encode(V1_SESSION) by the M4 (version 1) codec, captured before F8. */
        const val V1_FILE =
            "R0JYUwAAAAEAAAF6QFhgAAAAAAAAA1NlcQAAAAMAAAADAAAAAgAAAAEAAlQxAAAACAAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAA" +
                "AAAAAQAAAAQABUdsYXNzAAZGaWx0ZXIAAAACAApmbHQuY3V0b2ZmQJLAAAAAAAAACG9zYy53YXZlP9AAAAAAAAAAAAABAAhmbHQucmVz" +
                "bwAAAAIAAAABAAAAAQAJZHJtLmxldmVsP+AAAAAAAAAAAAABAAhkcm0udHVuZQAAAAEACm1zdC52b2x1bWXACAAAAAAAAAAAAAEAAAAB" +
                "AAZjaC5wYW4/0AAAAAAAAAAAAAEAA3NlcQAAAAEACHNlcS5nYXRlAAAAAQAAAAMAAAABAAAADAAAAAI+gAAAPszMzQAAAAIAAAAAAQAA" +
                "AAIAAAA9AAAAQT9mZma/gAAAP4AAAAAAAAUAAAAAAQAAAEY/AAAAPpmZmj8AAAAAAAABAAAACAAAAAEAAAACRGEAAAAAAAAAAAAE/0vN" +
                "Dw=="

        val V1_SESSION = Session(
            tempo = 97.5,
            ui = UiSnapshot("Seq", 3, 3, 2, 1, "T1"),
            synths = listOf(SynthSnapshot(4, "Glass", "Filter", mapOf("flt.cutoff" to 1200.0, "osc.wave" to 0.25), setOf("flt.reso"))),
            // Version 1's kit-wide lock, per sound since version 4.
            drum = DrumSnapshot(2, listOf(mapOf("drm.level" to 0.5)), DrumLockKeys.migrate(setOf("drm.tune"))),
            mix = mapOf("mst.volume" to -3.0),
            channels = listOf(mapOf("ch.pan" to 0.25)),
            patterns = listOf(
                PatternSnapshot(
                    3, 1, PatternSettings(12, 2, 0.25f, 0.4f),
                    mapOf(0 to Step.of(listOf(61, 65), 0.9f)),
                    listOf(LaneSnapshot(8, listOf(LanePoint(2, 900f, 4)))),
                ),
            ),
            currentPatterns = listOf(0, 0, 0, 1, 0, 0, 0, 0),
            locks = mapOf("seq" to setOf("seq.gate")),
        )

        /** SessionCodec.encode(<the session below, with v2 steps>) by the F8 (version 2) codec, captured before v3. */
        const val V2_FILE =
            "R0JYUwAAAAIAAAHRQFlgAAAAAAAAA1NlcQAAAAAAAAACAAAAAAAAAAEAAlMxAAAACAAAAAAAAAACAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQAAAAEADFJ1YmJlciBGbG9vcgADT3NjAAAAAQAIb3NjLndhdmU/4AAAAAAAAAAAAAAAAAACAAAAAQAAAAEACmRydW0ubGV2ZWw/5mZmZmZmZgAAAAAAAAABAAptc3Qudm9sdW1lwAAAAAAAAAAAAAABAAAAAQAGY2gucGFuv9AAAAAAAAAAAAABAANzZXEAAAABAAhzZXEuZ2F0ZQAAAAIAAAAAAAAAAAAAABAAAAABPczMzT8AAAAAAAAEAAAAAAEAAAACAAAAMAAAADY/gAAAv4AAAD+AAAAAAAAEAQAAAAEAAAAyP3MzMz1MzM0/AAAAAAAABgAAAAABAAAANj8ZmZq/gAAAP4AAAAAAAAkBAAAAAD9MzM2/gAAAP4AAAAAAAAAAAAABAAAAAgAAAAwAAAACAAAAAD7mZmYAAAABAAAAAwEAAAADAAAALQAAADQAAAA5PzMzMz6ZmZo/QAAAAAAAAQAAAAgAAAABAAAAA0S7gAAAAAAAAAAABwAAAAkABU1pbm9ySFKLaA=="

        /**
         * What [V2_FILE] loads as. In version 2 it held: kit step 0 on [48, 54] vel 1.0 gate -1
         * prob 1; step 4 on [50] vel 0.95 gate 0.05 prob 0.5; step 6 off [54]; step 9 on with
         * no notes; slot 1 pattern 2 step 3 on [45, 52, 57] vel 0.7 gate 0.3 prob 0.75.
         */
        val V2_SESSION = Session(
            tempo = 101.5,
            ui = UiSnapshot("Seq", 0, 2, 0, 1, "S1"),
            synths = listOf(SynthSnapshot(1, "Rubber Floor", "Osc", mapOf("osc.wave" to 0.5), emptySet())),
            drum = DrumSnapshot(2, listOf(mapOf("drum.level" to 0.7)), emptySet()),
            mix = mapOf("mst.volume" to -2.0),
            channels = listOf(mapOf("ch.pan" to -0.25)),
            patterns = listOf(
                PatternSnapshot(
                    0, 0, PatternSettings(16, 1, 0.1f, 0.5f),
                    mapOf(
                        0 to Step(listOf(Hit(48, 1f, -1f, 1f, 0f), Hit(54, 1f, -1f, 1f, 0f))),
                        4 to Step(listOf(Hit(50, 0.95f, 0.05f, 0.5f, 0f))),
                    ),
                    emptyList(),
                ),
                PatternSnapshot(
                    1, 2, PatternSettings(12, 2, 0f, 0.45f),
                    mapOf(3 to Step(listOf(45, 52, 57).map { Hit(it, 0.7f, 0.3f, 0.75f, 0f) })),
                    listOf(LaneSnapshot(8, listOf(LanePoint(3, 1500f, 7)))),
                ),
            ),
            currentPatterns = listOf(0, 2, 0, 0, 0, 0, 0, 0),
            locks = mapOf("seq" to setOf("seq.gate")),
            keyScale = KeyScale(9, Scale.Minor),
        )
    }

    private fun frame(version: Int, payload: ByteArray): ByteArray {
        val crc = java.util.zip.CRC32().apply { update(payload) }.value.toInt()
        return ByteBuffer.allocate(16 + payload.size).putInt(0x47425853).putInt(version).putInt(payload.size)
            .put(payload).putInt(crc).array()
    }
}
