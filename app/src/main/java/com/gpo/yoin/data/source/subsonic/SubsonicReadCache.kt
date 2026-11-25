package com.gpo.yoin.data.source.subsonic

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Coalesces identical Subsonic list reads ([SubsonicMusicSource]'s artists,
 * playlists, starred, album lists) and keeps a success for [ttlMs], so a
 * profile switch's `prime()`, Home and Library landing together cost one
 * request each instead of three.
 *
 * - **Cancelling one caller never cancels the others.** The shared fetch runs
 *   in [scope], which belongs to the source, not to any caller: a caller that
 *   is cancelled (prime's 8 s timeout, a cleared viewModelScope) only stops
 *   waiting. The fetch still completes and is kept for the next caller.
 * - **A write wins over a read in flight.** Every key has a generation;
 *   [invalidate] bumps it and drops the read in flight, so its answer (which
 *   may predate the write) is neither stored nor handed to callers that arrive
 *   after the write: they start a fresh read. Callers that joined before the
 *   write get that read's answer, as their own request would have.
 * - **Errors are never kept.** A failed read is handed to its waiters and
 *   forgotten; the next caller asks the server again.
 */
internal class SubsonicReadCache(
    private val ttlMs: Long,
    private val clock: () -> Long,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private class Entry(val value: Any, val expiresAtMs: Long)

    private class Flight(val generation: Long, val deferred: Deferred<Any>)

    private val lock = Any()
    private val entries = HashMap<String, Entry>()
    private val inFlight = HashMap<String, Flight>()
    private val generations = HashMap<String, Long>()

    @Suppress("UNCHECKED_CAST")
    suspend fun <T : Any> getOrFetch(key: String, fetch: suspend () -> T): T {
        if (ttlMs <= 0L) return fetch()
        val flight = synchronized(lock) {
            val cached = entries[key]
            if (cached != null) {
                if (cached.expiresAtMs > clock()) return cached.value as T
                entries.remove(key)
            }
            inFlight[key] ?: startFlight(key, fetch).also { inFlight[key] = it }
        }
        flight.deferred.start()
        return try {
            flight.deferred.await() as T
        } catch (cancelled: CancellationException) {
            // Our own cancellation: rethrow. The shared read was cancelled under us (its scope
            // went away) while we are still active: a failed read, not a silent cancellation.
            currentCoroutineContext().ensureActive()
            throw IOException("Subsonic read '$key' was cancelled", cancelled)
        }
    }

    /** Drops [key]'s stored answer and its read in flight (see the class doc). */
    fun invalidate(key: String) {
        synchronized(lock) { invalidateLocked(key) }
    }

    /** [invalidate] for every key starting with [prefix], stored or in flight. */
    fun invalidatePrefix(prefix: String) {
        synchronized(lock) {
            val keys = (entries.keys + inFlight.keys + generations.keys).filter { it.startsWith(prefix) }
            keys.forEach(::invalidateLocked)
        }
    }

    private fun invalidateLocked(key: String) {
        entries.remove(key)
        inFlight.remove(key)
        generations[key] = (generations[key] ?: 0L) + 1L
    }

    private fun startFlight(key: String, fetch: suspend () -> Any): Flight {
        val generation = generations[key] ?: 0L
        lateinit var flight: Flight
        val deferred = scope.async(start = CoroutineStart.LAZY) {
            try {
                val value = fetch()
                synchronized(lock) {
                    if ((generations[key] ?: 0L) == generation) {
                        entries[key] = Entry(value, clock() + ttlMs)
                    }
                }
                value
            } finally {
                synchronized(lock) {
                    if (inFlight[key] === flight) inFlight.remove(key)
                }
            }
        }
        flight = Flight(generation, deferred)
        return flight
    }
}
