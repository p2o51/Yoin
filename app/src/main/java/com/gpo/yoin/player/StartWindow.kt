package com.gpo.yoin.player

import com.gpo.yoin.data.model.Track

/**
 * The part of a [size]-long list that a start at [startIndex] hands a player:
 * always min([size], [SPOTIFY_START_MAX_URIS]) tracks, so a list that short
 * goes whole. A longer one keeps up to [SPOTIFY_START_HISTORY] tracks before
 * the start, for "previous"; near the end the window reaches further back
 * rather than shrink, so repeat and shuffle still get a full window.
 * Spotify's `uris` start carries no more, and a Library Songs start cuts its
 * list the same way on every provider ([startWindowQueue]). Empty when
 * [startIndex] isn't in the list.
 */
internal fun startWindow(size: Int, startIndex: Int): IntRange {
    if (startIndex !in 0 until size) return IntRange.EMPTY
    val from = (startIndex - SPOTIFY_START_HISTORY)
        .coerceIn(0, maxOf(0, size - SPOTIFY_START_MAX_URIS))
    return from until minOf(size, from + SPOTIFY_START_MAX_URIS)
}

/**
 * [tracks] cut to the [startWindow] around [startIndex], with the index moved
 * to where that track lands in the window. A start outside the list is
 * returned as given (the player turns it down).
 */
internal fun startWindowQueue(tracks: List<Track>, startIndex: Int): PlayableQueue {
    val window = startWindow(tracks.size, startIndex)
    if (window.isEmpty()) return PlayableQueue(tracks, startIndex)
    return PlayableQueue(tracks = tracks.slice(window), startIndex = startIndex - window.first)
}
