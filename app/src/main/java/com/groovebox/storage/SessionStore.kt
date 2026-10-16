package com.groovebox.storage

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * The session file on disk (F7.4, F7.7), in [dir]:
 *  - `session.gbx`: the last good save;
 *  - `session.gbx.bak`: the good save before it;
 *  - `session.gbx.tmp`: only while a save is being written (or after a crash during one).
 *
 * [save] writes the temp file, fsyncs it, moves the current file (if it is intact) to the
 * backup, then atomically renames the temp file over the current one and fsyncs the folder.
 * A crash at any point leaves at least one intact file. [load] tries the current file, then
 * the temp file (complete only if a crash hit between the two renames), then the backup, and
 * returns no session (first-launch defaults) when none is intact. Nothing here throws.
 *
 * Not thread-safe: the app calls [load] once on the main thread before autosave starts, and
 * [save] only from the single autosave thread. Pure Kotlin (java.io/nio only).
 */
class SessionStore(private val dir: File) {
    val file = File(dir, NAME)
    val backup = File(dir, "$NAME.bak")
    val temp = File(dir, "$NAME.tmp")

    enum class Source { Current, Temp, Backup, None }

    data class Loaded(val session: Session?, val source: Source)

    fun load(): Loaded {
        for ((f, source) in listOf(file to Source.Current, temp to Source.Temp, backup to Source.Backup)) {
            val bytes = read(f) ?: continue
            val session = SessionCodec.decode(bytes) ?: continue
            return Loaded(session, source)
        }
        return Loaded(null, Source.None)
    }

    /** Returns false (and keeps the files as they were, apart from the temp) on any failure. */
    fun save(session: Session): Boolean = try {
        write(SessionCodec.encode(session))
        true
    } catch (e: IOException) {
        temp.delete()
        false
    } catch (e: RuntimeException) {
        temp.delete()
        false
    }

    private fun write(bytes: ByteArray) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
        FileOutputStream(temp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        // Keep the previous save only if it is good: a damaged current file must not push the
        // last good backup out.
        val current = read(file)
        if (current != null && SessionCodec.isIntact(current)) {
            Files.move(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        syncDir()
    }

    /** Makes the renames durable. Best effort: not every file system allows opening a folder. */
    private fun syncDir() {
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (e: IOException) {
            // The data itself is synced; only the rename's durability is at the mercy of the OS.
        } catch (e: RuntimeException) {
        }
    }

    private fun read(f: File): ByteArray? = try {
        if (!f.isFile || f.length() > SessionCodec.MAX_FILE_BYTES) null else f.readBytes()
    } catch (e: IOException) {
        null
    }

    companion object {
        const val NAME = "session.gbx"
    }
}
