package com.groovebox.storage

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SessionStoreTest {
    private lateinit var dir: File
    private lateinit var store: SessionStore

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("session-test").toFile()
        store = SessionStore(dir)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private val a = TestSessions.small(tempo = 100.0)
    private val b = TestSessions.small(tempo = 110.0)
    private val c = TestSessions.small(tempo = 130.0)

    @Test
    fun firstLaunchHasNothing() {
        assertEquals(SessionStore.Loaded(null, SessionStore.Source.None), store.load())
    }

    @Test
    fun saveThenLoad() {
        assertTrue(store.save(a))
        assertEquals(SessionStore.Loaded(a, SessionStore.Source.Current), store.load())
        assertFalse(store.temp.exists())
    }

    @Test
    fun eachSaveKeepsThePreviousAsBackup() {
        store.save(a)
        store.save(b)
        assertEquals(b, SessionCodec.decode(store.file.readBytes()))
        assertEquals(a, SessionCodec.decode(store.backup.readBytes()))
        store.save(c)
        assertEquals(c, SessionCodec.decode(store.file.readBytes()))
        assertEquals(b, SessionCodec.decode(store.backup.readBytes()))
    }

    @Test
    fun aCorruptFileFallsBackToTheBackup() {
        store.save(a)
        store.save(b)
        store.file.writeBytes(store.file.readBytes().copyOf(40)) // truncated
        assertEquals(SessionStore.Loaded(a, SessionStore.Source.Backup), store.load())
    }

    @Test
    fun aGarbageFileFallsBackToTheBackup() {
        store.save(a)
        store.save(b)
        store.file.writeBytes(ByteArray(5000) { (it * 31).toByte() })
        assertEquals(a, store.load().session)
    }

    @Test
    fun bothBadMeansFirstLaunchDefaults() {
        store.save(a)
        store.save(b)
        store.file.writeBytes(byteArrayOf(1, 2, 3))
        store.backup.writeBytes(ByteArray(0))
        assertEquals(SessionStore.Loaded(null, SessionStore.Source.None), store.load())
    }

    @Test
    fun aDamagedCurrentFileDoesNotPushOutTheGoodBackup() {
        store.save(a)
        store.save(b)
        store.file.writeBytes(byteArrayOf(9, 9, 9)) // damaged on disk
        store.save(c)
        assertEquals(c, SessionCodec.decode(store.file.readBytes()))
        assertEquals(a, SessionCodec.decode(store.backup.readBytes())) // a, not the damaged bytes
    }

    @Test
    fun aCrashBetweenTheRenamesLoadsTheNewSave() {
        // State after "current -> backup" but before "temp -> current".
        store.save(a)
        store.save(b)
        store.file.renameTo(store.backup)
        store.temp.writeBytes(SessionCodec.encode(c))
        assertEquals(SessionStore.Loaded(c, SessionStore.Source.Temp), store.load())
    }

    @Test
    fun aCrashWhileWritingTheTempKeepsTheCurrent() {
        store.save(a)
        store.temp.writeBytes(SessionCodec.encode(b).copyOf(100)) // half-written
        assertEquals(SessionStore.Loaded(a, SessionStore.Source.Current), store.load())
        assertTrue(store.save(c)) // and the next save replaces the half-written temp
        assertEquals(c, store.load().session)
    }

    @Test
    fun aDirectoryThatIsMissingIsCreated() {
        val nested = SessionStore(File(dir, "a/b"))
        assertTrue(nested.save(a))
        assertEquals(a, nested.load().session)
    }

    @Test
    fun aSaveThatCannotWriteReturnsFalseAndKeepsTheOldFiles() {
        store.save(a)
        val blocked = SessionStore(File(store.file, "not-a-dir")) // a file in the way
        assertFalse(blocked.save(b))
        assertEquals(a, store.load().session)
    }

    @Test
    fun anOversizedFileIsIgnored() {
        store.save(a)
        store.save(b)
        store.file.writeBytes(ByteArray(SessionCodec.MAX_FILE_BYTES + 1))
        assertEquals(a, store.load().session)
    }

    @Test
    fun loadTimeOfATypicalSession() {
        store.save(TestSessions.small())
        repeat(20) { store.load() }
        val t0 = System.nanoTime()
        val loaded = store.load()
        val ms = (System.nanoTime() - t0) / 1e6
        println("typical session load from disk: $ms ms (${store.file.length()} B)")
        assertEquals(SessionStore.Source.Current, loaded.source)
        assertTrue("load took $ms ms", ms < 50)
    }
}
