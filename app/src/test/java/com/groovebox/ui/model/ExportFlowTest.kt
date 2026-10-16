package com.groovebox.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ExportFlowTest {
    @Test
    fun renderThenShareOnce() {
        val t = ExportTracker()
        t.start(true, "/c/a.wav", wholeTape = false, now = 0)
        assertEquals(ExportStatus.Rendering(0f, false), t.status)
        assertTrue(t.rendering)
        assertNull(t.poll(Float.NaN, 16)) // the engine has not reported yet
        assertNull(t.poll(0.4f, 32))
        assertEquals(ExportStatus.Rendering(0.4f, false), t.status)
        assertEquals("/c/a.wav", t.poll(1f, 48))
        assertEquals(ExportStatus.Shared, t.status)
        assertNull(t.poll(1f, 64)) // only once
        t.poll(1f, 48 + 4_000)
        assertEquals(ExportStatus.Idle, t.status)
    }

    @Test
    fun anEngineFailureShowsInlineThenClears() {
        val t = ExportTracker()
        t.start(true, "/c/a.wav", false, 0)
        assertNull(t.poll(-1f, 10))
        assertTrue(t.status is ExportStatus.Failed)
        assertNull(t.poll(1f, 20)) // a late 1 does not share a failed export
        t.poll(Float.NaN, 10 + 4_000)
        assertEquals(ExportStatus.Idle, t.status)
    }

    @Test
    fun aRefusedStartFails() {
        val t = ExportTracker()
        t.start(false, "/c/a.wav", false, 0)
        assertEquals(ExportStatus.Failed("export failed · nothing on the tape to export"), t.status)
        assertFalse(t.rendering)
    }

    @Test
    fun aStalledRenderFails() {
        val t = ExportTracker(stallMs = 1_000)
        t.start(true, "/c/a.wav", false, 0)
        t.poll(0.5f, 100)
        t.poll(0.5f, 1_099)
        assertTrue(t.rendering)
        t.poll(0.5f, 1_100)
        assertTrue(t.status is ExportStatus.Failed)
    }

    @Test
    fun aShareThatCannotOpenSaysSo() {
        val t = ExportTracker()
        t.start(true, "/c/a.wav", false, 0)
        t.poll(1f, 10)
        t.shareFailed(10)
        assertTrue(t.status is ExportStatus.Failed)
    }

    @Test
    fun wholeTapeOnlyWhenTheLoopCoversIt() {
        assertTrue(ExportFiles.wholeTape(TapeView.loopRegion(0, 0, 8)))
        assertTrue(ExportFiles.wholeTape(TapeView.loopRegion(0, 8, 8)))
        assertTrue(ExportFiles.wholeTape(TapeView.loopRegion(0, 12, 8)))
        assertFalse(ExportFiles.wholeTape(TapeView.loopRegion(2, 4, 8)))
        assertFalse(ExportFiles.wholeTape(TapeView.loopRegion(0, 4, 8)))
        assertTrue(ExportFiles.wholeTape(null))
    }

    @Test
    fun fileNamesAreTimestamped() {
        assertEquals("groovebox-20261016-090502.wav", ExportFiles.fileName(LocalDateTime.of(2026, 10, 16, 9, 5, 2)))
    }

    @Test
    fun oldExportsAreDeletedButTheNewestKept() {
        val hour = 3600_000L
        val now = 100 * hour
        val files = listOf(
            ExportFiles.Existing("groovebox-1.wav", now - 1 * hour),
            ExportFiles.Existing("groovebox-2.wav", now - 2 * hour),
            ExportFiles.Existing("groovebox-3.wav", now - 3 * hour),
            ExportFiles.Existing("groovebox-4.wav", now - 4 * hour),
            ExportFiles.Existing("other.txt", 0),
        )
        assertEquals(listOf("groovebox-4.wav"), ExportFiles.toDelete(files, now))
        val old = files.take(2) + ExportFiles.Existing("groovebox-old.wav", now - 30 * hour)
        assertEquals(listOf("groovebox-old.wav"), ExportFiles.toDelete(old, now))
    }

    @Test
    fun footerLines() {
        assertNull(TapeView.exportLine(ExportStatus.Idle, 8, 0, 0))
        assertEquals("exporting bars 3–6 · 50%", TapeView.exportLine(ExportStatus.Rendering(0.5f, false), 8, 2, 4))
        assertEquals("exporting the whole tape · 99%", TapeView.exportLine(ExportStatus.Rendering(0.999f, true), 8, 0, 0))
        assertEquals("export ready · pick an app to send it to", TapeView.exportLine(ExportStatus.Shared, 8, 0, 0))
        assertEquals("boom", TapeView.exportLine(ExportStatus.Failed("boom"), 8, 0, 0))
    }
}

class ClearConfirmTest {
    @Test
    fun secondTapWithinTheWindowConfirms() {
        val c = ClearConfirm(2_000)
        assertFalse(c.tap(5, 0))
        assertTrue(c.isArmed(5, 1_000))
        assertTrue(c.tap(5, 2_000))
        assertFalse(c.isArmed(5, 2_000)) // and it disarms
    }

    @Test
    fun aLateTapArmsAgain() {
        val c = ClearConfirm(2_000)
        c.tap(5, 0)
        assertFalse(c.tap(5, 2_001))
        assertTrue(c.tap(5, 2_500))
    }

    @Test
    fun aDifferentTargetArmsInstead() {
        val c = ClearConfirm(2_000)
        c.tap(5, 0)
        assertFalse(c.tap(6, 100))
        assertFalse(c.isArmed(5, 100))
        assertTrue(c.tap(6, 200))
    }

    @Test
    fun resetDisarms() {
        val c = ClearConfirm()
        c.tap(1, 0)
        c.reset()
        assertFalse(c.tap(1, 10))
    }
}
