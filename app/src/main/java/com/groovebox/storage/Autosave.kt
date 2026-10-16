package com.groovebox.storage

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * When to autosave (F7.4): a change makes the session dirty; the save is due [quietMs] after
 * the latest change (so a strip drag is one save, not one per frame), but never later than
 * [maxDelayMs] after the first unsaved change, so a crash loses at most that much.
 * Times are any monotonic milliseconds. Pure Kotlin; main thread only.
 */
class AutosaveScheduler(val quietMs: Long = 1_000, val maxDelayMs: Long = 5_000) {
    init {
        require(quietMs in 0..maxDelayMs)
    }

    private var firstChangeAt = NONE
    private var lastChangeAt = NONE
    private var retryAt = NONE

    /** Something is not on disk yet (a change, or a save that failed). */
    val dirty: Boolean
        get() = firstChangeAt != NONE || retryAt != NONE

    fun markDirty(now: Long) {
        if (firstChangeAt == NONE) firstChangeAt = now
        lastChangeAt = maxOf(lastChangeAt, now)
    }

    /**
     * A save failed: try again [maxDelayMs] from [now] (not sooner, so a full disk is not
     * hammered), or sooner if a new change makes a save due first.
     */
    fun markFailed(now: Long) {
        if (retryAt == NONE) retryAt = now + maxDelayMs
    }

    /** When the save is due, or null when the session is clean. */
    fun deadline(): Long? {
        val change = if (firstChangeAt == NONE) null else minOf(lastChangeAt + quietMs, firstChangeAt + maxDelayMs)
        val retry = if (retryAt == NONE) null else retryAt
        return when {
            change != null && retry != null -> minOf(change, retry)
            else -> change ?: retry
        }
    }

    fun isDue(now: Long): Boolean = deadline()?.let { now >= it } ?: false

    /** The session was copied for saving: clean until the next change. */
    fun markSaved() {
        firstChangeAt = NONE
        lastChangeAt = NONE
        retryAt = NONE
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
    }
}

/** A one-shot timer on the main thread (a Handler in the app, a fake clock in tests). */
interface MainTimer {
    /** Replaces any pending action. */
    fun schedule(delayMs: Long, action: () -> Unit)
    fun cancel()
}

/**
 * Autosave: [changed] on every edit; the session is copied with [snapshot] on the main thread
 * when [scheduler] says it is due, and the immutable copy is written by [write] on
 * [background] (a single thread, so saves land in order). Nothing mutable is shared between
 * the threads: the copy goes one way, and a failed write only posts "try again" back to
 * [main]. [flush] saves at once (onStop) and waits for the write.
 * Main thread only, apart from what runs on [background].
 */
class Autosaver<T : Any>(
    private val scheduler: AutosaveScheduler,
    private val clock: () -> Long,
    private val timer: MainTimer,
    private val snapshot: () -> T,
    private val write: (T) -> Boolean,
    private val background: Executor,
    private val main: Executor,
) {
    /** Writes started (for tests and the debug readout). */
    var savesStarted = 0
        private set

    fun changed() {
        scheduler.markDirty(clock())
        arm()
    }

    /** Copies the session now if it is dirty and starts writing it; the latch opens when written. */
    fun saveNow(): CountDownLatch {
        val done = CountDownLatch(1)
        timer.cancel()
        if (!scheduler.dirty) {
            // Still queue behind any write in flight, so a flush waits for it.
            background.execute { done.countDown() }
            return done
        }
        val copy = snapshot()
        scheduler.markSaved()
        savesStarted++
        background.execute {
            val ok = try {
                write(copy)
            } catch (e: Exception) {
                false
            }
            done.countDown()
            if (!ok) main.execute { retry() }
        }
        return done
    }

    /** Saves at once and waits up to [timeoutMs] for the file; false on timeout. */
    fun flush(timeoutMs: Long): Boolean = saveNow().await(timeoutMs, TimeUnit.MILLISECONDS)

    private fun retry() {
        scheduler.markFailed(clock())
        arm()
    }

    private fun arm() {
        val deadline = scheduler.deadline() ?: return
        timer.schedule((deadline - clock()).coerceAtLeast(0L)) { tick() }
    }

    private fun tick() {
        if (scheduler.isDue(clock())) saveNow() else arm()
    }
}
