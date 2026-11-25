package com.gpo.yoin.perf

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Debug-only running totals per traffic class (media, api, image), fed by
 * [YoinPerfHttp]'s call listener: calls, failed calls, response body bytes,
 * new vs reused connections. Every [LOG_EVERY] calls one `net.totals` line
 * sums them up, so a session's bytes read off logcat without adding up each
 * `net` line. Every entry point returns at once in release.
 */
object YoinPerfMetrics {
    data class Totals(
        val calls: Long,
        val failed: Long,
        val bytes: Long,
        val newConnections: Long,
        val reusedConnections: Long,
    )

    private class Counters {
        val calls = AtomicLong()
        val failed = AtomicLong()
        val bytes = AtomicLong()
        val newConnections = AtomicLong()
        val reusedConnections = AtomicLong()

        fun totals() = Totals(calls.get(), failed.get(), bytes.get(), newConnections.get(), reusedConnections.get())
    }

    private val byClass = ConcurrentHashMap<String, Counters>()
    private val allCalls = AtomicLong()

    fun recordCall(trafficClass: String, bodyBytes: Long, failed: Boolean) {
        if (!YoinPerf.enabled) return
        val counters = byClass.getOrPut(trafficClass, ::Counters)
        counters.calls.incrementAndGet()
        if (failed) counters.failed.incrementAndGet()
        if (bodyBytes > 0) counters.bytes.addAndGet(bodyBytes)
        if (allCalls.incrementAndGet() % LOG_EVERY == 0L) logTotals()
    }

    fun recordConnection(trafficClass: String, reused: Boolean) {
        if (!YoinPerf.enabled) return
        val counters = byClass.getOrPut(trafficClass, ::Counters)
        (if (reused) counters.reusedConnections else counters.newConnections).incrementAndGet()
    }

    fun totals(): Map<String, Totals> = byClass.mapValues { (_, counters) -> counters.totals() }

    /** One `net.totals` line per traffic class. */
    fun logTotals() {
        if (!YoinPerf.enabled) return
        totals().forEach { (trafficClass, t) ->
            YoinPerf.line(
                "net.totals",
                "cls" to trafficClass,
                "calls" to t.calls,
                "failed" to t.failed,
                "bytes" to t.bytes,
                "conn_new" to t.newConnections,
                "conn_reused" to t.reusedConnections,
            )
        }
    }

    internal fun reset() {
        byClass.clear()
        allCalls.set(0)
    }

    private const val LOG_EVERY = 25L
}
