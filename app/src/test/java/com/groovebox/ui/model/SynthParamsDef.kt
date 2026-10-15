package com.groovebox.ui.model

import com.groovebox.engine.Curve
import com.groovebox.engine.ParamSpec
import java.io.File

/** One GROOVE_PARAM line of engine/core/include/groove/SynthParams.def, as written. */
data class DefRow(
    val lineNumber: Int,
    val enumName: String,
    val key: String,
    val label: String,
    val min: Double,
    val max: Double,
    val default: Double,
    val curveName: String,
    val unit: String,
)

/** Reads the shared parameter table straight from the engine source (test-only). */
object SynthParamsDef {
    private const val RELATIVE_PATH = "engine/core/include/groove/SynthParams.def"

    private val ROW = Regex(
        """^GROOVE_PARAM\(\s*(\w+)\s*,\s*"([^"]*)"\s*,\s*"([^"]*)"\s*,\s*([-+0-9.eE]+)\s*,""" +
            """\s*([-+0-9.eE]+)\s*,\s*([-+0-9.eE]+)\s*,\s*(\w+)\s*,\s*"([^"]*)"\s*\)\s*$""",
    )

    /** Walks up from the working directory until the .def is found. */
    val file: File by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, RELATIVE_PATH)
            if (candidate.isFile) return@lazy candidate
            dir = dir.parentFile
        }
        error("$RELATIVE_PATH not found above ${System.getProperty("user.dir")}")
    }

    /** Lines that start with GROOVE_PARAM (comments excluded), with their 1-based line numbers. */
    val paramLines: List<Pair<Int, String>> by lazy {
        file.readLines().mapIndexedNotNull { i, line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("GROOVE_PARAM")) (i + 1) to trimmed else null
        }
    }

    val rows: List<DefRow> by lazy {
        paramLines.map { (n, line) ->
            val m = ROW.matchEntire(line) ?: error("SynthParams.def:$n does not match the row format: $line")
            val g = m.groupValues
            DefRow(n, g[1], g[2], g[3], g[4].toDouble(), g[5].toDouble(), g[6].toDouble(), g[7], g[8])
        }
    }

    /** The table as the app sees it at runtime (NativeEngine.params): ID = row order. */
    val specs: List<ParamSpec> by lazy {
        rows.mapIndexed { id, r ->
            ParamSpec(id, r.key, r.label, r.min, r.max, r.default, Curve.valueOf(r.curveName), r.unit)
        }
    }

    fun spec(key: String): ParamSpec = specs.first { it.key == key }
}
