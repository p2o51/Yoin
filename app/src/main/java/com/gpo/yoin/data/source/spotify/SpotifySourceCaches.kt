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
 * flight then still answers its own callers but is not kept.
 *
 * The load runs in the coroutine of the caller that started it. If that
 * caller is cancelled, the callers waiting on it start a load of their own
 * rather than failing with its cancellation. A failure is shared with the
 * waiting callers and is not cached.
 */
internal class SingleFlightValue<T : Any>(
    private val maxAgeMs: Long = Long.MAX_VALUE,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private class Turn<T>(val result: CompletableDeferred<T>, val leads: Boolean, val generation: Long)

    private val lock = Any()
    private var value: T? = null
    private var loadedAtMs = 0L
    private var inFlight: CompletableDeferred<T>? = null
    private var generation = 0L

    suspend fun get(load: suspend () -> T): T {
        while (true) {
            val turn = synchronized(lock) {
                value?.let { cached -> if (clock() - loadedAtMs <= maxAgeMs) return cached }
                inFlight?.let { shared -> Turn(shared, leads = false, generation = generation) }
                    ?: Turn(CompletableDeferred<T>(), leads = true, generation = generation)
                        .also { inFlight = it.result }
            }
            if (turn.leads) return lead(turn, load)
            try {
                return turn.result.await()
            } catch (_: CancellationException) {
                // Our own cancellation ends here; the leader's sends us round again.
                currentCoroutineContext().ensureActive()
            }
        }
    }

    fun invalidate() {
        synchronized(lock) {
            value = null
            inFlight = null
            generation++
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
            }
        }
        turn.result.complete(loaded)
        return loaded
    }
}

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
