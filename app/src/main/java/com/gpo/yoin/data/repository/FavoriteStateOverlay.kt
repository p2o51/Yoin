package com.gpo.yoin.data.repository

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.source.spotify.SavedTrackDelta
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** How long after one check a track's favorite state is asked about again (album page, Now Playing). */
const val FAVORITE_RECHECK_INTERVAL_MS = 30_000L

/**
 * How long a like or unlike written through Yoin outranks any answer the
 * service gives: Spotify applies library writes eventually, so a check made
 * right after one may not have it yet. The saved-tracks list overlay uses the
 * same window ([SavedTrackDelta]).
 */
const val FAVORITE_WRITE_GRACE_MS = SavedTrackDelta.DEFAULT_GRACE_MS

/**
 * A track's heart as the UI shows it. [fromUser]: the value is the user's own
 * write, in flight or landed within [FAVORITE_WRITE_GRACE_MS], so a change to
 * it answers a tap (the heart may beat); any other change — the service's
 * answer, a library sync, a failed write rolling back — is a quiet one. Which
 * track is shown is the reader's business: a host that moves to another track
 * (Now Playing) treats that change as quiet whatever this says.
 */
data class FavoriteState(val isStarred: Boolean, val fromUser: Boolean = false)

/**
 * What Yoin has learned about tracks' favorite state beyond the tracks
 * themselves, per account: the service's latest answer (App Remote's library
 * state, or the Web API's contains) and the latest like or unlike written
 * through Yoin. YoinRepository owns the only instance and reads it through
 * [resolveFavoriteState]. Bounded — past [maxEntries] the entries touched
 * longest ago go — and cleared when the account changes.
 */
internal class FavoriteStateOverlay(
    private val clock: () -> Long,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES
) {
    data class Key(val profileId: String, val trackId: MediaId)

    /** Each value with when it was learned; [touchedAtMs] orders eviction. */
    data class Entry(
        val remote: Boolean? = null,
        val remoteAtMs: Long = 0L,
        val written: Boolean? = null,
        val writtenAtMs: Long = 0L,
        val touchedAtMs: Long = 0L
    )

    private val _entries = MutableStateFlow<Map<Key, Entry>>(emptyMap())
    val entries: StateFlow<Map<Key, Entry>> = _entries.asStateFlow()

    // When each key was last asked about. Only throttles asks; never drives the UI.
    private val askLock = Any()
    private val askedAtMs = HashMap<Key, Long>()

    fun recordRemote(key: Key, saved: Boolean) {
        val now = clock()
        put(key) { entry -> entry.copy(remote = saved, remoteAtMs = now, touchedAtMs = now) }
    }

    fun recordWrite(key: Key, saved: Boolean) {
        val now = clock()
        put(key) { entry -> entry.copy(written = saved, writtenAtMs = now, touchedAtMs = now) }
    }

    /**
     * Of [keys], the ones not asked about within [minIntervalMs], each
     * marked asked now — so however many pages or players ask, one track is
     * asked once per interval, and an ask still out isn't repeated.
     */
    fun claimAsks(keys: Collection<Key>, minIntervalMs: Long): List<Key> = synchronized(askLock) {
        val now = clock()
        if (askedAtMs.size > maxEntries) askedAtMs.values.removeAll { now - it > ASK_MEMORY_MS }
        keys.distinct().filter { key ->
            val due = askedAtMs[key]?.let { now - it >= minIntervalMs } ?: true
            if (due) askedAtMs[key] = now
            due
        }
    }

    /** Marks [key] asked now, for an answer that came another way (App Remote). */
    fun markAsked(key: Key) {
        synchronized(askLock) { askedAtMs[key] = clock() }
    }

    fun clear() {
        synchronized(askLock) { askedAtMs.clear() }
        _entries.value = emptyMap()
    }

    private fun put(key: Key, change: (Entry) -> Entry) {
        _entries.update { entries ->
            val next = entries + (key to change(entries[key] ?: Entry()))
            if (next.size <= maxEntries) {
                next
            } else {
                // Drop down to 90% at once, so a full overlay doesn't sort on every answer.
                next.entries
                    .sortedByDescending { it.value.touchedAtMs }
                    .take(maxEntries * 9 / 10)
                    .associate { it.toPair() }
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 2_000

        /** Past this an ask no longer throttles anything and may be forgotten. */
        private const val ASK_MEMORY_MS = 10L * 60 * 1000
    }
}

/**
 * The heart shown for a track at [nowMs]. The user's write in flight wins.
 * Otherwise the newest of what Yoin learned: a write that landed (dated
 * [graceMs] after it, so an answer from inside that window — which may
 * predate Spotify applying it — never undoes it), the service's answer
 * ([entry]), and the saved-tracks mirror row ([mirrorSaved], written at
 * [mirrorAtMs]). With none of those, the track's own flag ([baseline]). An
 * unlike is a written false, so it holds even where the track's copy still
 * says liked; it counts as the user's only while the grace lasts.
 */
internal fun resolveFavoriteState(
    baseline: Boolean,
    inFlight: Boolean?,
    entry: FavoriteStateOverlay.Entry?,
    mirrorSaved: Boolean?,
    mirrorAtMs: Long,
    nowMs: Long,
    graceMs: Long = FAVORITE_WRITE_GRACE_MS
): FavoriteState {
    if (inFlight != null) return FavoriteState(inFlight, fromUser = true)
    var best: FavoriteState? = null
    var bestAtMs = Long.MIN_VALUE
    entry?.written?.let { written ->
        bestAtMs = entry.writtenAtMs + graceMs
        best = FavoriteState(written, fromUser = nowMs < bestAtMs)
    }
    entry?.remote?.let { remote ->
        if (entry.remoteAtMs > bestAtMs) {
            best = FavoriteState(remote)
            bestAtMs = entry.remoteAtMs
        }
    }
    mirrorSaved?.let { saved ->
        if (mirrorAtMs > bestAtMs) best = FavoriteState(saved)
    }
    return best ?: FavoriteState(baseline)
}
