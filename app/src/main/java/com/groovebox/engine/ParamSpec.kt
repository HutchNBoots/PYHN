package com.groovebox.engine

/** Mirrors the Curve column of engine/core/include/groove/SynthParams.def (same order). */
enum class Curve { Linear, Exp, Pow3, Int, Enum }

/**
 * One synth parameter as described by the engine (see NativeEngine.params()).
 * Values are in real units. For Enum, [options] holds the labels and the value is the index.
 */
data class ParamSpec(
    val id: Int,
    val key: String,
    val label: String,
    val min: Double,
    val max: Double,
    val default: Double,
    val curve: Curve,
    val unit: String,
) {
    val options: List<String>
        get() = if (curve == Curve.Enum) unit.split('|') else emptyList()
}
