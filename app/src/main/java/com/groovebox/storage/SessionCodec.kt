package com.groovebox.storage

import com.groovebox.ui.model.Hit
import com.groovebox.ui.model.KeyScale
import com.groovebox.ui.model.LanePoint
import com.groovebox.ui.model.LaneSnapshot
import com.groovebox.ui.model.PatternSettings
import com.groovebox.ui.model.PatternSnapshot
import com.groovebox.ui.model.Scale
import com.groovebox.ui.model.SeqLimits
import com.groovebox.ui.model.Step
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.util.zip.CRC32

/**
 * The session file format (F7.7): a small, versioned binary file written with
 * [DataOutputStream] (big-endian, strings as modified UTF-8).
 *
 * ```
 * magic    4 bytes  "GBXS"
 * version  int      [VERSION]
 * length   int      payload size in bytes
 * payload  bytes    see writePayload
 * crc      int      CRC-32 of the payload
 * ```
 *
 * Any mismatch (magic, a version newer than this app's, length, CRC, or a payload that does not
 * parse exactly to its end) makes [decode] return null, never throw: the caller then falls back
 * to the backup file. Older versions upgrade on load: [readPayload] reads each version's layout
 * and fills what it lacks, then [upgrade] runs per-version fix-ups.
 *
 * Version history:
 *  - 0: (never shipped; kept to prove the upgrade path) no UI block, current patterns or
 *    screen locks.
 *  - 1: M4, the first shipped format.
 *  - 2: F8, the key and scale at the end of the payload (root int, scale name). A version 1
 *    file loads with the scale off, which is how it played.
 *  - 3: build-20, steps as hits: per step its index, a hit count (0..8), then per hit note
 *    int, velocity, length, probability, nudge floats. Versions 0..2 stored one set of values
 *    per step (on, notes, velocity, gate, probability): they load as one hit per note with
 *    the step's velocity, its gate as the length (-1 = the pattern's gate) and probability,
 *    nudge 0; steps that were off, or had no note, are dropped.
 *
 * Pure Kotlin.
 */
object SessionCodec {
    const val VERSION = 3
    private const val MAGIC = 0x47425853 // "GBXS"
    private const val HEADER_BYTES = 12
    private const val TRAILER_BYTES = 4

    /** Upper bound for any count in the file, so random bytes cannot ask for huge allocations. */
    private const val MAX_COUNT = 1 shl 14

    /** A session file bigger than this is not ours (a full session is well under 1 MB). */
    const val MAX_FILE_BYTES = 8 shl 20

    fun encode(session: Session): ByteArray = encode(session, VERSION)

    /** [version] other than [VERSION] only for tests of the upgrade path. */
    internal fun encode(session: Session, version: Int): ByteArray {
        val payload = ByteArrayOutputStream(16 * 1024)
        DataOutputStream(payload).use { writePayload(it, session, version) }
        val bytes = payload.toByteArray()
        val crc = CRC32().apply { update(bytes) }.value.toInt()
        val out = ByteArrayOutputStream(bytes.size + HEADER_BYTES + TRAILER_BYTES)
        DataOutputStream(out).use {
            it.writeInt(MAGIC)
            it.writeInt(version)
            it.writeInt(bytes.size)
            it.write(bytes)
            it.writeInt(crc)
        }
        return out.toByteArray()
    }

    /** The session, or null when [bytes] is not a complete, intact session of a known version. */
    fun decode(bytes: ByteArray): Session? {
        val version = checkedVersion(bytes) ?: return null
        return try {
            val input = DataInputStream(ByteArrayInputStream(bytes, HEADER_BYTES, bytes.size - HEADER_BYTES - TRAILER_BYTES))
            val session = readPayload(input, version)
            if (input.available() != 0) return null // trailing garbage inside a valid CRC: not our writer
            upgrade(session, version)
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            null // e.g. a malformed string; never crash on a bad file
        }
    }

    /** Header, length and CRC are intact (without parsing the payload). */
    fun isIntact(bytes: ByteArray): Boolean = checkedVersion(bytes) != null

    private fun checkedVersion(bytes: ByteArray): Int? {
        if (bytes.size < HEADER_BYTES + TRAILER_BYTES || bytes.size > MAX_FILE_BYTES) return null
        if (readInt(bytes, 0) != MAGIC) return null
        val version = readInt(bytes, 4)
        if (version < 0 || version > VERSION) return null
        val length = readInt(bytes, 8)
        if (length != bytes.size - HEADER_BYTES - TRAILER_BYTES) return null
        val crc = CRC32().apply { update(bytes, HEADER_BYTES, length) }.value.toInt()
        if (crc != readInt(bytes, HEADER_BYTES + length)) return null
        return version
    }

    private fun readInt(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF shl 24) or (b[at + 1].toInt() and 0xFF shl 16) or
            (b[at + 2].toInt() and 0xFF shl 8) or (b[at + 3].toInt() and 0xFF)

    /** The migration hook: fix-ups for files written by older versions, after [readPayload]. */
    private fun upgrade(session: Session, fromVersion: Int): Session {
        var s = session
        if (fromVersion < 1) {
            // Version 0 had no UI block: [readPayload] filled the defaults. Its synth sections
            // were lower-case ("osc"); version 1 stores the enum's name ("Osc").
            s = s.copy(synths = s.synths.map { it.copy(section = it.section.replaceFirstChar(Char::uppercaseChar)) })
        }
        if (fromVersion < 2) {
            // Version 1 had no key and scale: it played chromatically.
            s = s.copy(keyScale = KeyScale.OFF)
        }
        return s
    }

    // --- payload ---

    private fun writePayload(out: DataOutputStream, s: Session, version: Int) {
        out.writeDouble(s.tempo)
        if (version >= 1) {
            out.writeUTF(s.ui.screen)
            out.writeInt(s.ui.selectedSlot)
            out.writeInt(s.ui.lastSynthSlot)
            out.writeInt(s.ui.page)
            out.writeInt(s.ui.tapeTrack)
            out.writeUTF(s.ui.mixerPad)
            writeInts(out, s.currentPatterns)
        }
        out.writeInt(s.synths.size)
        for (synth in s.synths) {
            out.writeInt(synth.presetIndex)
            out.writeUTF(synth.presetName)
            out.writeUTF(if (version >= 1) synth.section else synth.section.lowercase())
            writeValues(out, synth.values)
            writeStrings(out, synth.locks)
        }
        out.writeInt(s.drum.selectedKey)
        out.writeInt(s.drum.values.size)
        for (key in s.drum.values) writeValues(out, key)
        writeStrings(out, s.drum.locks)
        writeValues(out, s.mix)
        out.writeInt(s.channels.size)
        for (channel in s.channels) writeValues(out, channel)
        if (version >= 1) {
            out.writeInt(s.locks.size)
            for ((screen, keys) in s.locks.toSortedMap()) {
                out.writeUTF(screen)
                writeStrings(out, keys)
            }
        }
        out.writeInt(s.patterns.size)
        for (p in s.patterns) writePattern(out, p, version)
        if (version >= 2) {
            out.writeInt(s.keyScale.root)
            out.writeUTF(s.keyScale.scale.name)
        }
    }

    private fun readPayload(input: DataInputStream, version: Int): Session {
        val tempo = input.readDouble()
        var ui = UiSnapshot.DEFAULT
        var current = List(8) { 0 }
        if (version >= 1) {
            ui = UiSnapshot(
                screen = input.readUTF(),
                selectedSlot = input.readInt(),
                lastSynthSlot = input.readInt(),
                page = input.readInt(),
                tapeTrack = input.readInt(),
                mixerPad = input.readUTF(),
            )
            current = readInts(input)
        }
        val synths = List(count(input)) {
            SynthSnapshot(
                presetIndex = input.readInt(),
                presetName = input.readUTF(),
                section = input.readUTF(),
                values = readValues(input),
                locks = readStrings(input),
            )
        }
        val selectedKey = input.readInt()
        val drumValues = List(count(input)) { readValues(input) }
        val drum = DrumSnapshot(selectedKey, drumValues, readStrings(input))
        val mix = readValues(input)
        val channels = List(count(input)) { readValues(input) }
        val locks = if (version >= 1) {
            val n = count(input)
            val map = LinkedHashMap<String, Set<String>>(n)
            repeat(n) { map[input.readUTF()] = readStrings(input) }
            map
        } else {
            emptyMap()
        }
        val patterns = List(count(input)) { readPattern(input, version) }
        // A root out of range falls back to C and an unknown scale name to Off, rather than failing the load.
        val keyScale = if (version >= 2) KeyScale.of(input.readInt(), Scale.named(input.readUTF())) else KeyScale.OFF
        return Session(tempo, ui, synths, drum, mix, channels, patterns, current, locks, keyScale)
    }

    private fun writePattern(out: DataOutputStream, p: PatternSnapshot, version: Int) {
        out.writeInt(p.slot)
        out.writeInt(p.pattern)
        out.writeInt(p.settings.length)
        out.writeInt(p.settings.rate)
        out.writeFloat(p.settings.shuffle)
        out.writeFloat(p.settings.gate)
        out.writeInt(p.steps.size)
        for ((i, step) in p.steps.toSortedMap()) {
            out.writeInt(i)
            if (version >= 3) {
                out.writeInt(step.hits.size)
                for (h in step.hits) {
                    out.writeInt(h.note)
                    out.writeFloat(h.velocity)
                    out.writeFloat(h.length)
                    out.writeFloat(h.probability)
                    out.writeFloat(h.nudge)
                }
            } else {
                // The older layout (upgrade-path tests only): the first hit's values for the step.
                val first = step.hits.firstOrNull() ?: Hit(0)
                out.writeBoolean(step.on)
                writeInts(out, step.notes)
                out.writeFloat(first.velocity)
                out.writeFloat(first.length)
                out.writeFloat(first.probability)
            }
        }
        out.writeInt(p.lanes.size)
        for (lane in p.lanes) {
            out.writeInt(lane.target)
            out.writeInt(lane.points.size)
            for (pt in lane.points) {
                out.writeInt(pt.step)
                out.writeFloat(pt.value)
                out.writeLong(pt.stamp)
            }
        }
    }

    private fun readPattern(input: DataInputStream, version: Int): PatternSnapshot {
        val slot = input.readInt()
        val pattern = input.readInt()
        val settings = PatternSettings(input.readInt(), input.readInt(), input.readFloat(), input.readFloat())
        val n = count(input)
        val steps = LinkedHashMap<Int, Step>(n)
        repeat(n) {
            val i = input.readInt()
            if (version >= 3) {
                val hits = input.readInt()
                if (hits < 0 || hits > SeqLimits.HITS) throw IOException("bad hit count $hits")
                steps[i] = Step(List(hits) { Hit(input.readInt(), input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat()) })
            } else {
                val on = input.readBoolean()
                val notes = readInts(input)
                val velocity = input.readFloat()
                val gate = input.readFloat()
                val probability = input.readFloat()
                // Version 2 and older: one hit per note, sharing the step's values; off steps are dropped.
                if (on && notes.isNotEmpty()) {
                    val length = if (gate.isNaN() || gate < 0f) SeqLimits.PATTERN_GATE else gate
                    steps[i] = Step(notes.map { Hit(it, velocity, length, probability, 0f) })
                }
            }
        }
        val lanes = List(count(input)) {
            val target = input.readInt()
            LaneSnapshot(target, List(count(input)) { LanePoint(input.readInt(), input.readFloat(), input.readLong()) })
        }
        return PatternSnapshot(slot, pattern, settings, steps, lanes)
    }

    /** Values that are not finite are left out (a missing key keeps the engine's value). */
    private fun writeValues(out: DataOutputStream, values: Map<String, Double>) {
        val finite = values.filterValues { it.isFinite() }.toSortedMap()
        out.writeInt(finite.size)
        for ((key, v) in finite) {
            out.writeUTF(key)
            out.writeDouble(v)
        }
    }

    private fun readValues(input: DataInputStream): Map<String, Double> {
        val n = count(input)
        val map = LinkedHashMap<String, Double>(n)
        repeat(n) {
            val key = input.readUTF()
            val v = input.readDouble()
            if (v.isFinite()) map[key] = v
        }
        return map
    }

    private fun writeStrings(out: DataOutputStream, values: Set<String>) {
        out.writeInt(values.size)
        for (v in values.sorted()) out.writeUTF(v)
    }

    private fun readStrings(input: DataInputStream): Set<String> {
        val n = count(input)
        val set = LinkedHashSet<String>(n)
        repeat(n) { set += input.readUTF() }
        return set
    }

    private fun writeInts(out: DataOutputStream, values: List<Int>) {
        out.writeInt(values.size)
        for (v in values) out.writeInt(v)
    }

    private fun readInts(input: DataInputStream): List<Int> = List(count(input)) { input.readInt() }

    private fun count(input: DataInputStream): Int {
        val n = input.readInt()
        if (n < 0 || n > MAX_COUNT) throw IOException("bad count $n")
        if (n > input.available()) throw EOFException("count $n past the end")
        return n
    }
}
