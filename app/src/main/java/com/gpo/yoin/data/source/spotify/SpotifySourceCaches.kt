package com.gpo.yoin.data.source.spotify

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * A value loaded by at most one request at a time. Concurrent [get] calls
 * share the load in flight instead of each paging the same Spotify
 * collection — the library sync, a detail open and Home used to read
 * /me/tracks side by side, all under one profile's rate-limit gate. A loaded
 * value is served for [maxAgeMs]. [invalidate] drops it; a load already in
 * flight then still answers its own callers but is not kept — unless the
 * invalidate keeps recent loads, for a refresh that a load started just
 * now already serves.
 *
 * The load runs in the coroutine of the caller that started it. If that
 * caller is cancelled, the callers waiting on it start a load of their own
 * rather than failing with its cancellation. A failure is shared with the
 * waiting callers and is not cached.
 *
 * [read] also says when the request behind a value started — a cached
 * value's, or the load in flight a caller joined — so what is written from
 * it can be dated by when Spotify was asked, not by when it was used.
 */
internal class SingleFlightValue<T : Any>(
    private val maxAgeMs: Long = Long.MAX_VALUE,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private class Turn<T>(
        val result: CompletableDeferred<T>,
        val leads: Boolean,
        val generation: Long,
        val startedAtMs: Long
    )

    private val lock = Any()
    private var value: T? = null
    private var loadedAtMs = 0L
    private var valueStartedAtMs = 0L
    private var inFlight: CompletableDeferred<T>? = null
    private var inFlightStartedAtMs = 0L
    private var generation = 0L

    suspend fun get(load: suspend () -> T): T = read(load).value

    /** [get], with when the request behind the value it serves started. */
    suspend fun read(load: suspend () -> T): ReadValue<T> {
        while (true) {
            val turn = synchronized(lock) {
                val cached = value
                if (cached != null && clock() - loadedAtMs <= maxAgeMs) return ReadValue(cached, valueStartedAtMs)
                inFlight?.let { shared ->
                    Turn(shared, leads = false, generation = generation, startedAtMs = inFlightStartedAtMs)
                } ?: clock().let { now ->
                    Turn(CompletableDeferred<T>(), leads = true, generation = generation, startedAtMs = now).also {
                        inFlight = it.result
                        inFlightStartedAtMs = now
                    }
                }
            }
            if (turn.leads) return ReadValue(lead(turn, load), turn.startedAtMs)
            try {
                return ReadValue(turn.result.await(), turn.startedAtMs)
            } catch (_: CancellationException) {
                // Our own cancellation ends here; the leader's sends us round again.
                currentCoroutineContext().ensureActive()
            }
        }
    }

    /**
     * Drops the value, so the next [get] loads again. A load in flight still
     * answers its own callers but is not kept — unless it started at most
     * [keepLoadStartedWithinMs] ago: then it stays in flight, the next [get]
     * joins it, and its result is kept. That is "refresh, but a read that
     * just started will do"; a caller passes it only when no write the load
     * might predate needs a newer read.
     */
    fun invalidate(keepLoadStartedWithinMs: Long? = null) {
        synchronized(lock) {
            value = null
            val keepsLoad = keepLoadStartedWithinMs != null && inFlight != null &&
                clock() - inFlightStartedAtMs <= keepLoadStartedWithinMs
            if (!keepsLoad) {
                inFlight = null
                generation++
            }
        }
    }

    private suspend fun lead(turn: Turn<T>, load: suspend () -> T): T {
        val loaded = try {
            load()
        } catch (error: Throwable) {
            synchronized(lock) { if (inFlight === turn.result) inFlight = null }
            turn.result.completeExceptionally(error)
            throw error
        }
        synchronized(lock) {
            if (inFlight === turn.result) inFlight = null
            if (generation == turn.generation) {
                value = loaded
                loadedAtMs = clock()
                valueStartedAtMs = turn.startedAtMs
            }
        }
        turn.result.complete(loaded)
        return loaded
    }
}

/** A [SingleFlightValue]'s value, and when the request that read it started. */
internal class ReadValue<T>(val value: T, val readStartedAtMs: Long)

/**
 * Likes and unlikes written through this source, laid over the cached
 * saved-tracks list so a like doesn't throw that list away (dropping it cost
 * up to four /me/tracks pages on the next album, playlist or search).
 *
 * Spotify applies library writes eventually: a list read right after a like
 * may not have it yet. So an entry is dropped only by a list read that
 * STARTED after the write ([checkpoint]) and agrees with it, or — once
 * [graceMs] has passed since the write — by any such read, since by then the
 * remote state is the truth (it may have been changed in another app).
 */
internal class SavedTrackDelta(
    private val clock: () -> Long = System::currentTimeMillis,
    private val graceMs: Long = DEFAULT_GRACE_MS
) {
    private class Entry(val saved: Boolean, val sequence: Long, val writtenAtMs: Long)

    private val lock = Any()
    private val entries = HashMap<String, Entry>()
    private var sequence = 0L

    fun record(trackId: String, saved: Boolean) {
        synchronized(lock) { entries[trackId] = Entry(saved, ++sequence, clock()) }
    }

    /** Whether a write is still waiting for a list read to settle it. */
    fun isUnsettled(): Boolean = synchronized(lock) { entries.isNotEmpty() }

    /** Taken just before a list read starts; writes up to it are older than the read. */
    fun checkpoint(): Long = synchronized(lock) { sequence }

    fun applyTo(savedTrackIds: Set<String>): Set<String> = synchronized(lock) {
        if (entries.isEmpty()) return savedTrackIds
        LinkedHashSet(savedTrackIds).apply {
            entries.forEach { (trackId, entry) -> if (entry.saved) add(trackId) else remove(trackId) }
        }
    }

    fun reconcile(readTrackIds: Set<String>, readCheckpoint: Long) {
        synchronized(lock) {
            val now = clock()
            entries.entries.removeAll { (trackId, entry) ->
                entry.sequence <= readCheckpoint &&
                    ((trackId in readTrackIds) == entry.saved || now - entry.writtenAtMs >= graceMs)
            }
        }
    }

    companion object {
        const val DEFAULT_GRACE_MS = 60_000L
    }
}
