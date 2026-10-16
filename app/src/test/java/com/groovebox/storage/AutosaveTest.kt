package com.groovebox.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

class AutosaveSchedulerTest {
    @Test
    fun cleanUntilAChange() {
        val s = AutosaveScheduler(1000, 5000)
        assertFalse(s.dirty)
        assertNull(s.deadline())
        assertFalse(s.isDue(1_000_000))
    }

    @Test
    fun oneChangeIsSavedAfterTheQuietTime() {
        val s = AutosaveScheduler(1000, 5000)
        s.markDirty(100)
        assertEquals(1100L, s.deadline())
        assertFalse(s.isDue(1099))
        assertTrue(s.isDue(1100))
    }

    @Test
    fun aStreamOfChangesIsSavedNoLaterThanFiveSecondsAfterTheFirst() {
        val s = AutosaveScheduler(1000, 5000)
        for (t in 0L..20_000L step 16) s.markDirty(t).also { if (t >= 4000) assertEquals(5000L, s.deadline()) }
        assertTrue(s.isDue(5000))
    }

    @Test
    fun savedMeansClean() {
        val s = AutosaveScheduler()
        s.markDirty(0)
        s.markSaved()
        assertFalse(s.dirty)
        s.markDirty(10_000)
        assertEquals(11_000L, s.deadline())
    }

    @Test
    fun aFailedSaveRetriesAfterTheMaximumDelay() {
        val s = AutosaveScheduler(1000, 5000)
        s.markFailed(100)
        assertEquals(5100L, s.deadline())
        s.markDirty(200) // a new change: due after the quiet time
        assertEquals(1200L, s.deadline())
    }
}

/** A fake clock, main-thread timer and executors: everything runs when the test says so. */
private class Rig {
    var now = 0L
    private var timerAt: Long? = null
    private var timerAction: (() -> Unit)? = null
    val background = ArrayDeque<Runnable>()
    val main = ArrayDeque<Runnable>()
    val written = ArrayList<Int>()
    var writeOk = true
    var value = 0
    var snapshots = 0

    val timer = object : MainTimer {
        override fun schedule(delayMs: Long, action: () -> Unit) {
            timerAt = now + delayMs
            timerAction = action
        }

        override fun cancel() {
            timerAt = null
            timerAction = null
        }
    }

    val saver = Autosaver(
        scheduler = AutosaveScheduler(1000, 5000),
        clock = { now },
        timer = timer,
        snapshot = { snapshots++; value },
        write = { v -> written += v; writeOk },
        background = Executor { background.addLast(it) },
        main = Executor { main.addLast(it) },
    )

    /** Moves the clock, firing the timer when it is due, and runs the queued work. */
    fun advanceTo(t: Long) {
        while (true) {
            val at = timerAt
            if (at == null || at > t) break
            now = at
            val action = timerAction!!
            timerAt = null
            timerAction = null
            action()
            drain()
        }
        now = t
        drain()
    }

    fun drain() {
        while (background.isNotEmpty() || main.isNotEmpty()) {
            background.removeFirstOrNull()?.run()
            main.removeFirstOrNull()?.run()
        }
    }

    fun edit(v: Int) {
        value = v
        saver.changed()
    }
}

class AutosaverTest {
    @Test
    fun nothingIsWrittenWithoutAChange() {
        val r = Rig()
        r.advanceTo(60_000)
        assertEquals(emptyList<Int>(), r.written)
    }

    @Test
    fun aChangeIsWrittenOnceAfterTheQuietTime() {
        val r = Rig()
        r.now = 100
        r.edit(1)
        r.advanceTo(1099)
        assertEquals(emptyList<Int>(), r.written)
        r.advanceTo(1100)
        assertEquals(listOf(1), r.written)
        r.advanceTo(30_000)
        assertEquals(listOf(1), r.written)
    }

    @Test
    fun aDragOfManyChangesIsOneWriteOfTheLastValueWithinFiveSeconds() {
        val r = Rig()
        var t = 0L
        var v = 0
        while (t < 3000) {
            r.advanceTo(t)
            r.edit(++v)
            t += 16
        }
        r.advanceTo(t + 1000)
        assertEquals(listOf(v), r.written)
        assertEquals(1, r.snapshots) // the copy is taken once, when due
    }

    @Test
    fun continuousChangesStillSaveEveryFiveSeconds() {
        val r = Rig()
        var t = 0L
        var v = 0
        while (t <= 12_000) {
            r.advanceTo(t)
            r.edit(++v)
            t += 100
        }
        // Writes at 5 s and 10 s (5 s after the first change after each save), each within 5 s.
        assertEquals(2, r.written.size)
        assertTrue(r.written[0] in 49..51)
        assertTrue(r.written[1] in 99..101)
    }

    @Test
    fun theCopyIsTakenOnTheMainThreadAndWrittenInTheBackground() {
        val r = Rig()
        r.edit(5)
        r.advanceTo(999)
        r.now = 1000
        // Fire the timer by hand without draining the background: the copy exists, the write waits.
        r.saver.saveNow()
        assertEquals(1, r.snapshots)
        assertEquals(emptyList<Int>(), r.written)
        r.value = 6 // a later edit does not leak into the copy being written
        r.drain()
        assertEquals(listOf(5), r.written)
    }

    @Test
    fun flushOnStopWritesAtOnce() {
        val r = Rig()
        r.now = 10
        r.edit(7)
        val done = r.saver.saveNow()
        assertEquals(1L, done.count)
        r.drain()
        assertEquals(0L, done.count)
        assertEquals(listOf(7), r.written)
        // The pending timer was cancelled: nothing more later.
        r.advanceTo(60_000)
        assertEquals(listOf(7), r.written)
    }

    @Test
    fun flushWhenCleanWritesNothingButWaitsForAWriteInFlight() {
        val r = Rig()
        r.edit(1)
        r.saver.saveNow() // write queued, not run
        val done = r.saver.saveNow() // clean now: just a barrier behind it
        assertEquals(2, r.background.size) // the write, then the barrier
        assertEquals(1L, done.count)
        r.drain()
        assertEquals(0L, done.count)
        assertEquals(listOf(1), r.written)
    }

    @Test
    fun aFailedWriteIsRetried() {
        val r = Rig()
        r.writeOk = false
        r.edit(3)
        r.advanceTo(1000)
        assertEquals(listOf(3), r.written)
        r.writeOk = true
        r.advanceTo(5999)
        assertEquals(listOf(3), r.written) // not hammered: waits the 5 s
        r.advanceTo(6000)
        assertEquals(listOf(3, 3), r.written)
        r.advanceTo(60_000)
        assertEquals(listOf(3, 3), r.written)
    }

    @Test
    fun aWriteThatThrowsCountsAsFailed() {
        val r = Rig()
        val saver = Autosaver(
            AutosaveScheduler(1000, 5000), { r.now }, r.timer, { 1 }, { _ -> error("disk on fire") },
            Executor { r.background.addLast(it) }, Executor { r.main.addLast(it) },
        )
        saver.changed()
        val done = saver.saveNow()
        r.drain()
        assertEquals(0L, done.count) // the latch still opens
        assertEquals(1, saver.savesStarted)
    }
}
