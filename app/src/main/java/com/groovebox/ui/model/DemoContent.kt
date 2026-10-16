package com.groovebox.ui.model

/**
 * What a fresh launch starts with (no persistence until M4): slots 2–8 on different presets,
 * and pattern 1 of the drum kit and of slot 2 holding a beat and a bassline at 120 BPM, so
 * Play makes music at once (M2 plan); the synth slots also start with FX sends to suit
 * their sound (M3). Pure Kotlin.
 */
object DemoContent {
    /** Preferred presets for engine slots 1..7 (bass, lead, pad, pluck, ...), by name. */
    val PRESETS = listOf("Rubber Floor", "Glass Wire", "Slow Tide", "Rain Glass", "Thumb Piano", "Northern Hall", "Laser Drip")

    /**
     * Preset index for each synth slot 1..7: the preferred name when the engine has it,
     * otherwise the next preset not used yet, so every slot still differs.
     */
    fun presetIndexes(names: List<String>): List<Int> {
        if (names.isEmpty()) return emptyList()
        val used = HashSet<Int>()
        val wanted = PRESETS.map { want -> names.indexOf(want).takeIf { it >= 0 && used.add(it) } }
        return wanted.map { idx ->
            idx ?: (names.indices.firstOrNull { it !in used } ?: 0).also { used.add(it) }
        }
    }

    /** A slot's FX sends at first launch (ChannelParams.def ch.dlySend / ch.revSend, 0..1). */
    data class Sends(val delay: Double, val reverb: Double)

    /** The kit gets a touch of room. */
    val DRUM_SENDS = Sends(delay = 0.0, reverb = 0.08)

    /** By preset name: the bass stays dry, pads get the most reverb, leads and plucks some delay. */
    val SENDS: Map<String, Sends> = mapOf(
        "Rubber Floor" to Sends(0.0, 0.0), // bass
        "Glass Wire" to Sends(0.15, 0.15), // lead
        "Slow Tide" to Sends(0.0, 0.4), // pad
        "Rain Glass" to Sends(0.2, 0.25), // pluck
        "Thumb Piano" to Sends(0.1, 0.2),
        "Northern Hall" to Sends(0.0, 0.35),
        "Laser Drip" to Sends(0.3, 0.2), // fx
    )

    /** A preset without its own entry gets a modest reverb send. */
    val DEFAULT_SENDS = Sends(0.0, 0.15)

    fun sendsFor(presetName: String): Sends = SENDS[presetName] ?: DEFAULT_SENDS

    const val TEMPO = 120.0

    // Kit keys (F2.1 order, MIDI 48 upward).
    private const val KICK = 48
    private const val SNARE = 50
    private const val CLAP = 52
    private const val CLOSED_HAT = 54
    private const val OPEN_HAT = 56

    /** step → (notes, velocity, probability) of the beat (16 steps of 1/16). */
    val BEAT: Map<Int, Triple<List<Int>, Float, Float>> = mapOf(
        0 to Triple(listOf(KICK, CLOSED_HAT), 1.0f, 1f),
        2 to Triple(listOf(CLOSED_HAT), 0.6f, 1f),
        4 to Triple(listOf(SNARE, CLOSED_HAT), 0.95f, 1f),
        6 to Triple(listOf(CLOSED_HAT), 0.6f, 1f),
        7 to Triple(listOf(KICK), 0.75f, 1f),
        8 to Triple(listOf(KICK, CLOSED_HAT), 1.0f, 1f),
        10 to Triple(listOf(CLOSED_HAT), 0.6f, 1f),
        12 to Triple(listOf(SNARE, CLAP, CLOSED_HAT), 0.95f, 1f),
        14 to Triple(listOf(OPEN_HAT), 0.7f, 1f),
        15 to Triple(listOf(CLOSED_HAT), 0.45f, 0.5f), // a ghost hat, half the time
    )

    /** step → (note, velocity) of the bassline: A minor around A2, under the kicks. */
    val BASS: Map<Int, Pair<Int, Float>> = mapOf(
        0 to (45 to 1.0f), // A2
        2 to (45 to 0.6f),
        3 to (57 to 0.75f), // A3
        6 to (48 to 0.85f), // C3
        8 to (45 to 1.0f),
        10 to (43 to 0.8f), // G2
        11 to (45 to 0.6f),
        12 to (52 to 0.85f), // E3
        14 to (50 to 0.75f), // D3
        15 to (48 to 0.6f), // C3
    )

    const val BASS_GATE = 0.45f

    /** Writes the demo into [store] (tempo, the beat in slot 0 and the bass in slot 1, pattern 0). */
    fun write(store: PatternStore) {
        store.setTempo(TEMPO, force = true)
        store.setSettings(0, 0, PatternSettings(length = 16))
        for ((step, v) in BEAT) {
            store.setStep(0, 0, step, Step(on = true, notes = v.first, velocity = v.second, probability = v.third))
        }
        store.setSettings(1, 0, PatternSettings(length = 16, gate = BASS_GATE))
        for ((step, v) in BASS) {
            store.setStep(1, 0, step, Step(on = true, notes = listOf(v.first), velocity = v.second))
        }
    }
}
