package com.gpo.yoin.perf

import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import com.gpo.yoin.BuildConfig
import java.util.concurrent.atomic.AtomicInteger

/**
 * Debug-only performance marks. Each event is one logcat line under [TAG],
 * `event k1=v1 k2=v2 t=<elapsedRealtime ms>`, plus a Trace slice of the same
 * name, so a capture reads with `adb logcat -s YoinPerf` or in Perfetto.
 * Events, fields and the pairing recipe: docs/perf/yoinperf-logging.md.
 *
 * Release builds (BuildConfig.DEBUG = false, releaseDebugSigned included)
 * never log, trace or allocate a span: every entry point returns at once.
 */
object YoinPerf {
    const val TAG = "YoinPerf"

    @JvmField
    val enabled: Boolean = BuildConfig.DEBUG

    /** Where formatted lines go. Replaceable so JVM tests can capture them. */
    @Volatile
    internal var sink: (String) -> Unit = { line -> Log.d(TAG, line) }

    private val cookies = AtomicInteger()

    /** A point event: one line plus a zero-length Trace slice (a tick on the thread's track). */
    fun mark(event: String, vararg kv: Pair<String, Any?>) {
        if (!enabled) return
        runCatching {
            val t = SystemClock.elapsedRealtime()
            Trace.beginSection(event.take(MAX_SECTION_NAME))
            Trace.endSection()
            sink(format(event, kv, t))
        }
    }

    /** One line, no Trace slice — for callers that wrap their own section. */
    internal fun line(event: String, vararg kv: Pair<String, Any?>) {
        if (!enabled) return
        runCatching { sink(format(event, kv, SystemClock.elapsedRealtime())) }
    }

    /**
     * Starts a timed span; null when disabled. The Trace side is an async slice
     * (API 29+): spans cross suspension points and threads, which a
     * begin/endSection pair cannot.
     */
    fun begin(event: String): Span? {
        if (!enabled) return null
        return runCatching {
            val span = Span(event, cookies.incrementAndGet(), SystemClock.elapsedRealtime())
            if (Build.VERSION.SDK_INT >= 29) Trace.beginAsyncSection(event.take(MAX_SECTION_NAME), span.cookie)
            span
        }.getOrNull()
    }

    /** Ends [span]: logs `event kv… ms=<duration> t=<now>`. A null span (disabled) is a no-op. */
    fun end(span: Span?, vararg kv: Pair<String, Any?>) {
        if (span == null) return
        runCatching {
            val t = SystemClock.elapsedRealtime()
            if (Build.VERSION.SDK_INT >= 29) Trace.endAsyncSection(span.event.take(MAX_SECTION_NAME), span.cookie)
            sink(format(span.event, arrayOf(*kv, "ms" to t - span.startMs), t))
        }
    }

    /** `detail.click`: the user asked for a detail page ([via]: activity | pane | pane-push | push). */
    fun detailClick(kind: String, id: String, via: String) {
        if (enabled) mark("detail.click", "kind" to kind, "id" to id, "via" to via)
    }

    /** Millis since this process was forked — the cold-start yardstick. */
    fun sinceProcessStart(): Long = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()

    class Span internal constructor(
        internal val event: String,
        internal val cookie: Int,
        internal val startMs: Long
    )

    /** Null values are dropped; whitespace in a value becomes `_` so every field stays one token. */
    internal fun format(event: String, kv: Array<out Pair<String, Any?>>, t: Long): String = buildString {
        append(event)
        for ((key, value) in kv) {
            if (value == null) continue
            append(' ').append(key).append('=').append(value.toString().replace(whitespace, "_").ifEmpty { "-" })
        }
        append(" t=").append(t)
    }

    private val whitespace = Regex("\\s+")

    // android.os.Trace rejects section names longer than 127 chars.
    private const val MAX_SECTION_NAME = 127
}
