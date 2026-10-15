package com.groovebox.ui.model

import com.groovebox.engine.Curve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks the UI against the shared parameter table the engine compiles from. */
class SynthParamsContractTest {
    private val rows = SynthParamsDef.rows

    @Test
    fun everyParamLineParses() {
        assertTrue("no GROOVE_PARAM rows found", rows.isNotEmpty())
        assertEquals(SynthParamsDef.paramLines.size, rows.size)
    }

    @Test
    fun everyCurveNameMapsToTheCurveEnum() {
        val names = Curve.entries.map { it.name }.toSet()
        for (r in rows) {
            assertTrue("line ${r.lineNumber}: unknown curve ${r.curveName}", r.curveName in names)
        }
    }

    @Test
    fun idsAreLineOrderAndKeysAreUnique() {
        // NativeEngine.params assigns id = index, so the specs must follow the file's line order.
        val lineNumbers = rows.map { it.lineNumber }
        assertEquals(lineNumbers.sorted(), lineNumbers)
        SynthParamsDef.specs.forEachIndexed { i, spec ->
            assertEquals(i, spec.id)
            assertEquals(rows[i].key, spec.key)
        }
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
        assertEquals(rows.size, rows.map { it.enumName }.toSet().size)
    }

    @Test
    fun everyLayoutKeyExistsInTheTable() {
        val keys = rows.map { it.key }.toSet()
        for (key in SynthLayout.allKeys) {
            assertTrue("SynthLayout names unknown param '$key'", key in keys)
        }
    }

    @Test
    fun everyParamIsOnAStrip() {
        // F1.7: every parameter in the table is reachable from the strips.
        assertEquals(rows.map { it.key }.toSet(), SynthLayout.allKeys.toSet())
    }

    @Test
    fun rangesAreUsableByTheMappings() {
        for (s in SynthParamsDef.specs) {
            assertTrue("${s.key}: min < max", s.min < s.max)
            assertTrue("${s.key}: default in range", s.default in s.min..s.max)
            if (s.curve == Curve.Exp) assertTrue("${s.key}: Exp needs min > 0", s.min > 0.0)
            if (s.curve == Curve.Enum) {
                assertEquals("${s.key}: one label per index", (s.max - s.min).toInt() + 1, s.options.size)
            }
        }
    }

    @Test
    fun everyParamFormatsAndRoundTripsItsDefault() {
        for (s in SynthParamsDef.specs) {
            val text = ParamMapping.format(s, s.default)
            assertTrue("${s.key}: empty display", text.isNotBlank())
            val back = ParamMapping.fromNormalized(s, ParamMapping.toNormalized(s, s.default))
            assertEquals("${s.key}: default round trip", s.default, back, 1e-9 * (s.max - s.min) + 1e-12)
        }
    }
}
