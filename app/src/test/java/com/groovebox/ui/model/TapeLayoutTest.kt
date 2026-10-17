package com.groovebox.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TapeLayoutTest {
    private val mix = ParamsDef.mix
    private val channel = ParamsDef.channel

    @Test
    fun stripsFollowF52AndF53() {
        // F5.3: loop start and length on strips 2–3; F5.2: Dub on strip 4.
        assertEquals(StripRef.Mix(MixKeys.LOOP_START), TapeStrips.main[1])
        assertEquals(StripRef.Mix(MixKeys.LOOP_LEN), TapeStrips.main[2])
        assertEquals(StripRef.Mix(MixKeys.DUB), TapeStrips.main[3])
        assertEquals(StripRef.Mix(MixKeys.SPEED), TapeStrips.main[0])
        assertEquals(
            listOf(StripRef.Channel(MixKeys.VOLUME), StripRef.Channel(MixKeys.PAN), StripRef.Mix(MixKeys.REVERSE), StripRef.Commit),
            TapeStrips.shift,
        )
        assertEquals(TapeStrips.main, TapeStrips.strips(false))
        assertEquals(TapeStrips.shift, TapeStrips.strips(true))
    }

    @Test
    fun tapeValuesFormat() {
        val start = mix.spec(MixKeys.LOOP_START)
        val len = mix.spec(MixKeys.LOOP_LEN)
        val speed = mix.spec(MixKeys.SPEED)
        assertEquals("bar 1", MixFormat.format(start, 0.0))
        assertEquals("bar 64", MixFormat.format(start, 63.0))
        assertEquals("All", MixFormat.format(len, 0.0))
        assertEquals("1 bar", MixFormat.format(len, 1.0))
        assertEquals("8 bars", MixFormat.format(len, 8.0))
        assertEquals("0.5×", MixFormat.format(speed, 0.0))
        assertEquals("1×", MixFormat.format(speed, 1.0))
        assertEquals("2×", MixFormat.format(speed, 2.0))
        assertEquals("85%", MixFormat.format(mix.spec(MixKeys.DUB), 0.85))
        assertEquals("Off", MixFormat.format(mix.spec(MixKeys.REVERSE), 0.0))
        assertEquals("On", MixFormat.format(mix.spec(MixKeys.REVERSE), 1.0))
        assertEquals("−3 dB", MixFormat.format(channel.spec(MixKeys.VOLUME), -3.0))
        assertEquals("C", MixFormat.format(channel.spec(MixKeys.PAN), 0.0))
        assertEquals("S2", TapeStrips.commitValue(1))
        assertEquals("Kit", TapeStrips.commitValue(0))
    }

    @Test
    fun loopRegionIsClampedToTheTape() {
        assertNull(TapeView.loopRegion(0, 0, 0))
        assertEquals(LoopRegion(0, 8, whole = true), TapeView.loopRegion(3, 0, 8)) // 0 = all
        assertEquals(LoopRegion(2, 6, whole = false), TapeView.loopRegion(2, 4, 8))
        assertEquals(LoopRegion(6, 8, whole = false), TapeView.loopRegion(6, 4, 8)) // cut at the end
        assertEquals(LoopRegion(7, 8, whole = false), TapeView.loopRegion(40, 4, 8)) // start kept inside
        assertEquals(LoopRegion(0, 8, whole = true), TapeView.loopRegion(0, 8, 8))
        assertEquals(4, TapeView.loopRegion(2, 4, 8)!!.bars)
    }

    @Test
    fun headFraction() {
        assertEquals(0f, TapeView.headFraction(100, 0), 0f)
        assertEquals(0.25f, TapeView.headFraction(250, 1000), 1e-6f)
        assertEquals(1f, TapeView.headFraction(2000, 1000), 0f)
        assertEquals(0f, TapeView.headFraction(-5, 1000), 0f)
    }

    @Test
    fun laneHitTesting() {
        // 4 lanes of 100 with gaps of 10: lanes at 0–100, 110–210, 220–320, 330–430.
        val h = 430f
        val g = 10f
        assertEquals(0, TapeView.laneAt(0f, h, 4, g))
        assertEquals(0, TapeView.laneAt(104f, h, 4, g)) // first half of the gap
        assertEquals(1, TapeView.laneAt(106f, h, 4, g)) // second half
        assertEquals(1, TapeView.laneAt(200f, h, 4, g))
        assertEquals(2, TapeView.laneAt(300f, h, 4, g))
        assertEquals(3, TapeView.laneAt(430f, h, 4, g))
        assertNull(TapeView.laneAt(-1f, h, 4, g))
        assertNull(TapeView.laneAt(431f, h, 4, g))
        assertNull(TapeView.laneAt(10f, 0f, 4, g))
        assertEquals(2, TapeView.laneAt(50f, 100f, 4)) // no gaps
    }

    @Test
    fun recordButtonArmsStopsOrRefuses() {
        assertEquals(TapeRecordStatus.Idle, TapeView.status(-1, -1))
        assertEquals(TapeRecordStatus.Armed, TapeView.status(2, -1))
        assertEquals(TapeRecordStatus.Recording, TapeView.status(-1, 1))
        assertEquals(TapeRecordStatus.Recording, TapeView.status(2, 1))
        assertEquals(TapeRecordAction.Arm(2), TapeView.recordPress(TapeRecordStatus.Idle, 2, canRecord = true))
        assertEquals(TapeRecordAction.Stop, TapeView.recordPress(TapeRecordStatus.Armed, 2, canRecord = true))
        assertEquals(TapeRecordAction.Stop, TapeView.recordPress(TapeRecordStatus.Recording, 0, canRecord = false))
        assertEquals(TapeRecordAction.None, TapeView.recordPress(TapeRecordStatus.Idle, 2, canRecord = false))
        assertTrue(TapeView.canRecord(1, false))
        assertFalse(TapeView.canRecord(1, true))
        assertFalse(TapeView.canRecord(0, false))
        assertFalse(TapeView.canRecord(2, false))
    }

    @Test
    fun recordNeedsTheTapeTempo() {
        // Build-20: at another tempo the tape plays varispeed and Record needs its tempo back.
        assertTrue(TapeView.canRecord(1, false, tempoMatches = true))
        assertFalse(TapeView.canRecord(1, false, tempoMatches = false))
        assertFalse(TapeView.canRecord(2, false, tempoMatches = false))
        // Record and the lane double-tap follow it.
        val can = TapeView.canRecord(1, false, tempoMatches = false)
        assertEquals(TapeRecordAction.None, TapeView.recordPress(TapeRecordStatus.Idle, 0, can))
        assertEquals(TapeRecordAction.None, TapeView.laneDoubleTap(1, TapeRecordStatus.Idle, -1, can))
        // The footer says what is needed.
        assertNull(TapeView.recordNeeds(1, false, tempoMatches = true, tapeTempoBpm = 120f))
        assertEquals("record needs Speed 1× and Rev off", TapeView.recordNeeds(2, false, tempoMatches = true, tapeTempoBpm = 120f))
        assertEquals("record needs Speed 1× and Rev off", TapeView.recordNeeds(1, true, tempoMatches = true, tapeTempoBpm = 0f))
        assertEquals("record needs 120.0 BPM, the tape's tempo", TapeView.recordNeeds(1, false, tempoMatches = false, tapeTempoBpm = 120f))
        assertEquals("record needs 133.7 BPM, the tape's tempo", TapeView.recordNeeds(1, false, tempoMatches = false, tapeTempoBpm = 133.7f))
        assertEquals(
            "record needs Speed 1×, Rev off and 96.5 BPM, the tape's tempo",
            TapeView.recordNeeds(0, true, tempoMatches = false, tapeTempoBpm = 96.5f),
        )
    }

    @Test
    fun laneDoubleTapArmsThatLaneOrStopsItsOwnTake() {
        // Idle: arm the lane that was double-tapped (if the tape can record).
        assertEquals(TapeRecordAction.Arm(3), TapeView.laneDoubleTap(3, TapeRecordStatus.Idle, -1, canRecord = true))
        assertEquals(TapeRecordAction.None, TapeView.laneDoubleTap(3, TapeRecordStatus.Idle, -1, canRecord = false))
        // Busy on this lane: stop the take, or cancel the arm.
        assertEquals(TapeRecordAction.Stop, TapeView.laneDoubleTap(1, TapeRecordStatus.Recording, 1, canRecord = true))
        assertEquals(TapeRecordAction.Stop, TapeView.laneDoubleTap(2, TapeRecordStatus.Armed, 2, canRecord = false))
        // Busy on another lane: nothing, so a stray double-tap never ends someone else's take.
        assertEquals(TapeRecordAction.None, TapeView.laneDoubleTap(0, TapeRecordStatus.Recording, 1, canRecord = true))
        assertEquals(TapeRecordAction.None, TapeView.laneDoubleTap(3, TapeRecordStatus.Armed, 2, canRecord = true))
    }

    @Test
    fun subtitle() {
        assertEquals("empty · the first take sets the length", TapeView.subtitle(0, null, 1.0, "1×", false))
        val r = TapeView.loopRegion(0, 8, 16)
        assertEquals("16 bars · looping bars 1–8 · overdub", TapeView.subtitle(16, r, 1.0, "1×", false))
        val all = TapeView.loopRegion(0, 0, 8)
        assertEquals("8 bars · looping all · replace", TapeView.subtitle(8, all, 0.0, "1×", false))
        assertEquals("1 bar · looping all · dub 85% · 2× reverse", TapeView.subtitle(1, TapeView.loopRegion(0, 0, 1), 0.85, "2×", true))
    }

    @Test
    fun overviewDecodesAndReportsChanges() {
        assertEquals(0f, TapeOverview.decode(0), 0f)
        assertEquals(1f, TapeOverview.decode(255.toByte()), 0f)
        assertEquals(128 / 255f, TapeOverview.decode(128.toByte()), 1e-6f)

        val o = TapeOverview(tracks = 2, bins = 8)
        val raw = ByteArray(8)
        assertFalse(o.update(0, raw))
        assertEquals(0L, o.revision)
        raw[3] = 255.toByte()
        raw[4] = 51
        assertTrue(o.update(0, raw))
        assertEquals(1L, o.revision)
        assertFalse(o.update(0, raw))
        assertFalse(o.update(5, raw)) // no such track
        assertEquals(1f, o.peak(0, 3), 0f)
        assertEquals(0.2f, o.peak(0, 4), 1e-6f)
        assertEquals(0f, o.peak(1, 3), 0f)
        assertEquals(0f, o.peak(9, 0), 0f)
        // Fewer columns than bins: the loudest bin of each column.
        assertEquals(0f, o.column(0, 0, 4), 0f) // bins 0–1
        assertEquals(1f, o.column(0, 1, 4), 0f) // bins 2–3
        assertEquals(0.2f, o.column(0, 2, 4), 1e-6f) // bins 4–5
        // More columns than bins: each column shows its bin.
        assertEquals(1f, o.column(0, 6, 16), 0f)
        assertEquals(1f, o.column(0, 7, 16), 0f)
        assertEquals(0f, o.column(0, 99, 16), 0f)
        // A short buffer leaves the rest at zero.
        assertTrue(o.update(0, ByteArray(2)))
        assertEquals(0f, o.peak(0, 3), 0f)
    }

    @Test
    fun trackNames() {
        assertEquals(listOf("T1", "T2", "T3", "T4"), List(4) { TapeView.trackName(it) })
    }
}
