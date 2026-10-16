package com.groovebox.ui.model

import com.groovebox.engine.NativeEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class MeterScaleTest {
    private val volume = ParamsDef.channel.spec(MixKeys.VOLUME)
    private val master = ParamsDef.mix.spec(MixKeys.MASTER)

    private fun gain(db: Double) = 10.0.pow(db / 20.0).toFloat()

    @Test
    fun everyPadHasItsOwnMeter() {
        val indices = MixerLayout.pads.map(MeterScale::meterIndex)
        assertEquals((0 until NativeEngine.METER_COUNT).toList(), indices)
        // Channel pads read their mixer channel's meter.
        for (pad in MixerLayout.pads) MixerLayout.channelOf(pad)?.let { assertEquals(it, MeterScale.meterIndex(pad)) }
        assertEquals(NativeEngine.METER_MASTER, MeterScale.meterIndex(MixerPad.Master))
    }

    @Test
    fun scaleMatchesTheFader() {
        assertEquals(volume.min, MeterScale.MIN_DB, 0.0)
        assertEquals(volume.max, MeterScale.MAX_DB, 0.0)
        assertEquals(master.min, MeterScale.MIN_DB, 0.0)
        for (db in listOf(-60.0, -48.0, -24.0, -12.0, -6.0, 0.0, 3.0, 6.0)) {
            // A peak at x dBFS draws where the fader marker at x dB does.
            assertEquals("$db dB", ParamMapping.toNormalized(volume, db).toFloat(), MeterScale.height(gain(db)), 1e-5f)
        }
    }

    @Test
    fun heightsAtTheEnds() {
        assertEquals(0f, MeterScale.height(0f), 0f)
        assertEquals(0f, MeterScale.height(-1f), 0f)
        assertEquals(0f, MeterScale.height(Float.NaN), 0f)
        assertEquals(0f, MeterScale.height(gain(-80.0)), 0f)  // below the floor
        assertEquals(1f, MeterScale.height(16f), 0f)          // the engine's cap, +24 dBFS
        assertEquals(1f, MeterScale.height(Float.POSITIVE_INFINITY), 0f)
        assertEquals(60f / 66f, MeterScale.height(1f), 1e-6f)  // 0 dBFS
        assertEquals(Double.NEGATIVE_INFINITY, MeterScale.toDb(0f), 0.0)
        assertEquals(-6.0206, MeterScale.toDb(0.5f), 1e-4)
        assertEquals(0f, MeterScale.dbToHeight(Double.NaN), 0f)
    }

    @Test
    fun heightRisesWithLevel() {
        var last = -1f
        for (i in 0..200) {
            val h = MeterScale.height(i / 100f)
            assertTrue(h >= last)
            last = h
        }
    }

    @Test
    fun readingsFollowTheBufferLayout() {
        val buffer = FloatArray(NativeEngine.METER_BUFFER_SIZE)
        buffer[3] = 0.5f
        buffer[NativeEngine.METER_MASTER] = 1f
        buffer[NativeEngine.METER_CLIP_OFFSET + NativeEngine.METER_MASTER] = 1f
        buffer[NativeEngine.METER_CLIP_OFFSET + 9] = 1f
        val r = MeterReadings()
        assertTrue(r.read(buffer))
        assertFalse(r.read(buffer))  // nothing new
        assertEquals(MeterScale.height(0.5f), r.height(MixerPad.Slot(3)), 0f)
        assertEquals(0f, r.height(MixerPad.Slot(2)), 0f)
        assertEquals(MeterScale.height(1f), r.height(MixerPad.Master), 0f)
        assertTrue(r.clipped(MixerPad.Master))
        assertTrue(r.clipped(MixerPad.Track(1)))  // channel 9
        assertFalse(r.clipped(MixerPad.Slot(3)))
        assertFalse(r.clipped(MixerPad.Delay))

        buffer[3] = Float.NaN
        assertTrue(r.read(buffer))
        assertEquals(0f, r.levels[3], 0f)
        assertFalse(r.read(FloatArray(10)))  // too short: ignored
        assertTrue(r.clipped(MixerPad.Master))
    }

    // The buffer layout in NativeEngine.kt matches engine/android/JniBridge.cpp and LevelMeters.h.
    @Test
    fun layoutMatchesTheEngine() {
        // ParamsDef only to find the files above the working directory.
        val jni = ParamsDef("engine/android/JniBridge.cpp", "").file.readText()
        val meters = ParamsDef("engine/core/include/groove/LevelMeters.h", "").file.readText()
        fun constant(src: String, name: String): Int =
            Regex("""$name\s*=\s*(\d+)""").find(src)?.groupValues?.get(1)?.toInt() ?: error("$name not found")
        assertEquals(NativeEngine.METER_COUNT, constant(meters, "kCount"))
        assertEquals(NativeEngine.METER_DELAY, constant(meters, "kDelay"))
        assertEquals(NativeEngine.METER_REVERB, constant(meters, "kReverb"))
        assertEquals(NativeEngine.METER_MASTER, constant(meters, "kMaster"))
        assertEquals(NativeEngine.MIXER_CHANNEL_COUNT, constant(meters, "kChannels"))
        assertEquals(NativeEngine.METER_CLIP_OFFSET, constant(jni, "kMeterSlots"))
        assertEquals(NativeEngine.METER_BUFFER_SIZE, 2 * NativeEngine.METER_CLIP_OFFSET)
        assertTrue(Regex("""kMeterSize\s*=\s*2\s*\*\s*kMeterSlots""").containsMatchIn(jni))
    }
}
