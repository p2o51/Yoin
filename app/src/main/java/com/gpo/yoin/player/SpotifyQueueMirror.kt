package com.gpo.yoin.player

import com.gpo.yoin.data.model.Track

/**
 * What the queue sheet shows while Spotify plays: [queue] with the song now
 * playing at [currentIndex], the entries the user added ([userQueued]), and
 * [upcoming], the entries still to play in the order Spotify plays them.
 * [foreignIndex] is the song Spotify plays from outside the queue Yoin handed
 * it (one queued in the Spotify app, a Liked Songs song past Yoin's window),
 * shown where Spotify plays it; -1 when there is none.
 */
internal data class SpotifyQueueView(
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val userQueued: Set<Int> = emptySet(),
    val upcoming: List<Int> = emptyList(),
    val foreignIndex: Int = -1,
)

/**
 * Yoin's copy of the queue it handed Spotify. App Remote reports only the
 * song playing, never Spotify's queue, so the copy follows Spotify's rules
 * itself (Gemini review 2026-10-20, every way the queue used to empty):
 *
 * - Add to queue goes into Spotify's own queue, which plays right after the
 *   current song, ahead of the rest of the album or playlist: the copy puts
 *   it there too ([add]), after what the user queued before, and marks it as
 *   queued. It used to go at the end, and when Spotify played it the queue
 *   "jumped" to the end and the rest of the album vanished.
 * - A song is looked up near the last one Spotify played ([onPlaying]), so a
 *   queued copy of an album's song, or a playlist that repeats one, doesn't
 *   jump the queue to another of its copies.
 * - With shuffle on, Spotify's order is its own: what's left is every song
 *   not played yet, queued ones first ([SpotifyQueueView.upcoming]). It used
 *   to be only the songs after the current one in Yoin's list, none at all
 *   once Spotify shuffled onto the list's last song.
 * - A song from outside the queue shows as the song now playing, right after
 *   the last song of the queue Spotify played, with the queue continuing
 *   after it. It used to show the list's first song as the one playing.
 */
internal class SpotifyQueueMirror {
    var tracks: List<Track> = emptyList()
        private set
    private var userQueued: Set<Int> = emptySet()
    private var played: Set<Int> = emptySet()

    /** The entry Spotify last played (or the start's, before it is heard). */
    private var position: Int = -1

    /** The entry Spotify plays now; -1 while it plays a song from outside the queue. */
    private var current: Int = -1

    /** A start handed Spotify [queue] from [startIndex]; [queued] is still in Spotify's own queue, next. */
    fun reset(queue: List<Track>, startIndex: Int, queued: List<Track> = emptyList()) {
        tracks = queue
        userQueued = emptySet()
        played = emptySet()
        position = startIndex
        current = startIndex
        queued.forEach(::insertQueued)
    }

    fun clear() = reset(emptyList(), -1)

    /**
     * Add to queue: after the current song and whatever the user queued
     * there before, as Spotify plays it. With nothing mirrored (playback
     * adopted from the Spotify app), [playing] becomes the queue's first song.
     */
    fun add(track: Track, playing: Track?) {
        if (tracks.isEmpty() && playing != null) reset(listOf(playing), 0)
        insertQueued(track)
    }

    private fun insertQueued(track: Track) {
        var at = (position + 1).coerceIn(0, tracks.size)
        while (at < tracks.size && at in userQueued && at !in played) at++
        tracks = tracks.take(at) + track + tracks.drop(at)
        userQueued = userQueued.shiftedFrom(at) + at
        played = played.shiftedFrom(at)
        if (position >= at) position++
        if (current >= at) current++
    }

    /** Spotify plays [track]: its entry, or -1 when it isn't one of the queue's. */
    fun onPlaying(track: Track): Int {
        val index = indexOf(track)
        current = index
        if (index >= 0) {
            position = index
            // Spotify went round again (repeat all): the album or playlist is all to come
            // once more. The queued songs it played are gone from Spotify's queue.
            val context = tracks.indices.filter { it !in userQueued }
            if (index in played && context.all { it in played }) {
                played = played.filterTo(mutableSetOf()) { it in userQueued }
            }
            played = played + index
        }
        return index
    }

    /** The queue as the sheet shows it while Spotify plays [playing], [shuffle] on or off. */
    fun view(playing: Track?, shuffle: Boolean): SpotifyQueueView {
        if (tracks.isEmpty()) {
            return playing?.let { SpotifyQueueView(queue = listOf(it), currentIndex = 0) } ?: SpotifyQueueView()
        }
        if (current >= 0 || playing == null) {
            val now = if (current >= 0) current else position
            return SpotifyQueueView(
                queue = tracks,
                currentIndex = now,
                userQueued = userQueued,
                upcoming = upcoming(tracks.size, now, userQueued, played, shuffle),
            )
        }
        // A song from outside the queue: where Spotify plays it, after the last one it played.
        val at = (position + 1).coerceIn(0, tracks.size)
        val queued = userQueued.shiftedFrom(at)
        val playedNow = played.shiftedFrom(at) + at
        return SpotifyQueueView(
            queue = tracks.take(at) + playing + tracks.drop(at),
            currentIndex = at,
            userQueued = queued,
            upcoming = upcoming(tracks.size + 1, at, queued, playedNow, shuffle),
            foreignIndex = at,
        )
    }

    /**
     * The entry for [track], nearest the one Spotify last played: that one,
     * the next, the previous, then the first after it, then any. The same id
     * wins over a copy Spotify relinked ([isSameSongAs]).
     */
    private fun indexOf(track: Track): Int {
        val exact = tracks.indices.filter { tracks[it].id == track.id }
        val matches = exact.ifEmpty { tracks.indices.filter { tracks[it].isSameSongAs(track) } }
        if (matches.isEmpty()) return -1
        return listOf(position, position + 1, position - 1).firstOrNull { it in matches }
            ?: matches.firstOrNull { it > position }
            ?: matches.first()
    }
}

/**
 * Spotify's play order after [current]: the user's queue first, then the
 * rest of the list in order, or with [shuffle] on every entry not [played]
 * yet (Spotify's shuffled order is its own; the list order stands in for it).
 */
private fun upcoming(size: Int, current: Int, queued: Set<Int>, played: Set<Int>, shuffle: Boolean): List<Int> {
    if (current !in 0 until size) return emptyList()
    val after = (current + 1 until size).toList()
    if (!shuffle) return after
    val next = after.filter { it in queued && it !in played }
    val rest = (0 until size).filter { it != current && it !in queued && it !in played }
    return next + rest
}

private fun Set<Int>.shiftedFrom(at: Int): Set<Int> = mapTo(mutableSetOf()) { if (it >= at) it + 1 else it }
