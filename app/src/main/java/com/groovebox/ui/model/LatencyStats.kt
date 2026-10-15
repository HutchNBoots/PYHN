package com.groovebox.ui.model

data class LatencySummary(
    val count: Int,
    val total: Int,
    val lastMs: Double,
    val minMs: Double,
    val medianMs: Double,
    val maxMs: Double,
)

/** Rolling window of tap-to-tone measurements. */
class LatencyStats(private val window: Int = 50) {
    private val samplesNs = ArrayDeque<Long>()
    private var total = 0

    fun add(latencyNs: Long) {
        samplesNs.addLast(latencyNs)
        if (samplesNs.size > window) samplesNs.removeFirst()
        total++
    }

    fun reset() {
        samplesNs.clear()
        total = 0
    }

    fun summary(): LatencySummary? {
        if (samplesNs.isEmpty()) return null
        val sorted = samplesNs.sorted()
        val mid = sorted.size / 2
        val medianNs =
            if (sorted.size % 2 == 1) sorted[mid].toDouble() else (sorted[mid - 1] + sorted[mid]) / 2.0
        return LatencySummary(
            count = samplesNs.size,
            total = total,
            lastMs = samplesNs.last() / NS_PER_MS,
            minMs = sorted.first() / NS_PER_MS,
            medianMs = medianNs / NS_PER_MS,
            maxMs = sorted.last() / NS_PER_MS,
        )
    }

    private companion object {
        const val NS_PER_MS = 1_000_000.0
    }
}
