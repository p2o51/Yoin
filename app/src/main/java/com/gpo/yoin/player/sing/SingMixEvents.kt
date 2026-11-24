package com.gpo.yoin.player.sing

import android.util.Log
import java.util.Locale

/**
 * What the live mixer does, for logcat — without logging on the audio thread. The mixer [record]s an
 * event (a new stream position after a flush, the first vocal removal of a binding, V coming in or
 * going out — a handful per seek or song, never one per buffer) into a fixed ring: no allocation, no
 * lock. The align thread ([SingAlignThread]) [drain]s it to the log. One producer, one consumer.
 */
class SingMixEvents(private val capacity: Int = 64) {
    private val kinds = IntArray(capacity)
    private val frames = LongArray(capacity)
    private val rates = IntArray(capacity)

    @Volatile private var written = 0L

    @Volatile private var read = 0L

    /** Events the ring had no room for (producer side). */
    @Volatile var dropped = 0
        private set

    private var lastAvailNanos = 0L
    private var suppressed = 0

    /** Audio thread: notes [kind] at track frame [frame] ([sampleRate] to tell the time). */
    fun record(kind: Int, frame: Long, sampleRate: Int): Boolean {
        val w = written
        if (w - read >= capacity) {
            dropped++
            return false
        }
        val slot = (w % capacity).toInt()
        kinds[slot] = kind
        frames[slot] = frame
        rates[slot] = sampleRate
        written = w + 1
        return true
    }

    /** Consumer: hands every recorded event to [sink], oldest first; returns how many. */
    fun drain(sink: (kind: Int, frame: Long, sampleRate: Int) -> Unit): Int {
        var r = read
        val w = written
        var n = 0
        while (r < w) {
            val slot = (r % capacity).toInt()
            sink(kinds[slot], frames[slot], rates[slot])
            r++
            read = r
            n++
        }
        return n
    }

    /** Consumer: [drain] into logcat, V in/out at most a few times a second. */
    fun drainToLog(nanoTime: Long = System.nanoTime()) {
        drain { kind, frame, rate ->
            val avail = kind == AVAIL_ON || kind == AVAIL_OFF
            if (avail && nanoTime - lastAvailNanos < AVAIL_LOG_GAP_NS) {
                suppressed++
                return@drain
            }
            if (avail) lastAvailNanos = nanoTime
            val more = if (avail && suppressed > 0) " (+$suppressed more V in/out)" else ""
            if (avail) suppressed = 0
            Log.d(TAG, "mixer: ${describe(kind, frame, rate)}$more")
        }
    }

    companion object {
        /** A new stream position (flush); frame −1: unknown. */
        const val STREAM = 1

        /** The first vocal removal for a binding: Sing cut in. */
        const val ENGAGED = 2

        /** V comes in (covered, aligned) / goes out (uncovered, unaligned or unbound). */
        const val AVAIL_ON = 3
        const val AVAIL_OFF = 4

        private const val TAG = "YoinSing"
        private const val AVAIL_LOG_GAP_NS = 250_000_000L

        fun describe(kind: Int, frame: Long, sampleRate: Int): String {
            val at = if (frame < 0 || sampleRate <= 0) "?" else String.format(Locale.ROOT, "%.2f s", frame.toDouble() / sampleRate)
            return when (kind) {
                STREAM -> "stream at $at"
                ENGAGED -> "engaged (first vocal removal) at $at"
                AVAIL_ON -> "V in at $at"
                AVAIL_OFF -> "V out at $at"
                else -> "event $kind at $at"
            }
        }
    }
}
