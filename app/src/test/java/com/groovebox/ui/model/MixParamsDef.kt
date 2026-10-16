package com.groovebox.ui.model

import com.groovebox.engine.Curve
import com.groovebox.engine.ParamSpec
import java.io.File

/**
 * Reads a parameter table in the SynthParams.def row format straight from the engine source
 * (test-only): MixParams.def (GROOVE_MIX_PARAM) and ChannelParams.def (GROOVE_CHANNEL_PARAM).
 */
class ParamsDef(private val relativePath: String, private val macro: String) {
    private val row = Regex(
        """^$macro\(\s*(\w+)\s*,\s*"([^"]*)"\s*,\s*"([^"]*)"\s*,\s*([-+0-9.eE]+)\s*,""" +
            """\s*([-+0-9.eE]+)\s*,\s*([-+0-9.eE]+)\s*,\s*(\w+)\s*,\s*"([^"]*)"\s*\)\s*$""",
    )

    val file: File by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, relativePath)
            if (candidate.isFile) return@lazy candidate
            dir = dir.parentFile
        }
        error("$relativePath not found above ${System.getProperty("user.dir")}")
    }

    /** Lines that start with the macro (comments excluded), with their 1-based line numbers. */
    val lines: List<Pair<Int, String>> by lazy {
        file.readLines().mapIndexedNotNull { i, line ->
            val t = line.trim()
            if (t.startsWith(macro)) (i + 1) to t else null
        }
    }

    val rows: List<DefRow> by lazy {
        lines.map { (n, line) ->
            val m = row.matchEntire(line) ?: error("$relativePath:$n does not match the row format: $line")
            val g = m.groupValues
            DefRow(n, g[1], g[2], g[3], g[4].toDouble(), g[5].toDouble(), g[6].toDouble(), g[7], g[8])
        }
    }

    /** As the app sees it at runtime (NativeEngine.mixParams / channelParams): ID = row order. */
    val specs: List<ParamSpec> by lazy {
        rows.mapIndexed { id, r -> ParamSpec(id, r.key, r.label, r.min, r.max, r.default, Curve.valueOf(r.curveName), r.unit) }
    }

    fun spec(key: String): ParamSpec = specs.firstOrNull { it.key == key } ?: error("$key not in $relativePath")

    companion object {
        val mix = ParamsDef("engine/core/include/groove/MixParams.def", "GROOVE_MIX_PARAM")
        val channel = ParamsDef("engine/core/include/groove/ChannelParams.def", "GROOVE_CHANNEL_PARAM")
    }
}
