package com.gpo.yoin.ui.detail

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import kotlin.random.Random
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Most of an artist's albums loaded at once for Play, Shuffle or Add to
 * queue. Albums from the artist endpoint are summaries without tracks, so
 * each is a detail read; Spotify serves the whole process from four read
 * slots, first come first served, and three leave one free for a tap.
 */
internal const val ARTIST_ALBUM_LOAD_BATCH = 3

/** One release on the artist page: its id and the track count the provider reports (null: not reported). */
internal data class ArtistRelease(val id: MediaId, val songCount: Int?)

/**
 * Every track of [releases], in discography order, loaded at most
 * [ARTIST_ALBUM_LOAD_BATCH] albums at a time. [loadAlbum] answers an empty
 * list for an album that can't be read.
 */
internal suspend fun loadArtistTracks(
    releases: List<ArtistRelease>,
    loadAlbum: suspend (MediaId) -> List<Track>
): List<Track> = loadAlbumsInBatches(releases, loadAlbum).flatten()

/**
 * The tracks an artist's Play ([shuffle] false) or Shuffle starts, in play
 * order. [startLimit] is how many tracks of the list the provider's start
 * can use (null: all of them): Spotify's start carries the first
 * [com.gpo.yoin.player.SPOTIFY_START_MAX_URIS], so albums past those are
 * never loaded.
 *
 * - Play: albums load in discography order until the list holds
 *   [startLimit] tracks, sized by the counts the albums report.
 * - Shuffle: the shuffled order is fixed first, over every track the
 *   albums report, then only the albums holding its first [startLimit]
 *   places load — the same draw as shuffling the whole discography, not
 *   a shuffle of the first few albums. Without a count for every album the
 *   draw can't be laid out, and the whole discography loads and shuffles.
 */
internal suspend fun loadArtistPlayTracks(
    releases: List<ArtistRelease>,
    shuffle: Boolean,
    startLimit: Int?,
    random: Random = Random.Default,
    loadAlbum: suspend (MediaId) -> List<Track>
): List<Track> = when {
    startLimit == null -> loadArtistTracks(releases, loadAlbum).let { if (shuffle) it.shuffled(random) else it }
    shuffle -> loadShuffledStart(releases, startLimit, random, loadAlbum)
    else -> loadOrderedStart(releases, startLimit, loadAlbum)
}

private suspend fun loadOrderedStart(
    releases: List<ArtistRelease>,
    startLimit: Int,
    loadAlbum: suspend (MediaId) -> List<Track>
): List<Track> {
    val tracks = mutableListOf<Track>()
    var next = 0
    while (next < releases.size && tracks.size < startLimit) {
        // No more albums than their reported counts say the start still needs.
        val batch = mutableListOf<ArtistRelease>()
        var expected = tracks.size
        while (next < releases.size && batch.size < ARTIST_ALBUM_LOAD_BATCH && expected < startLimit) {
            val release = releases[next++]
            batch += release
            expected += release.songCount ?: 0
        }
        loadAlbumsInBatches(batch, loadAlbum).forEach { tracks += it }
    }
    return tracks
}

private suspend fun loadShuffledStart(
    releases: List<ArtistRelease>,
    startLimit: Int,
    random: Random,
    loadAlbum: suspend (MediaId) -> List<Track>
): List<Track> {
    if (releases.any { it.songCount == null }) return loadArtistTracks(releases, loadAlbum).shuffled(random)
    // One place per reported track: (album, track number within the album).
    val places = releases.flatMapIndexed { album, release ->
        List((release.songCount ?: 0).coerceAtLeast(0)) { track -> album to track }
    }
    if (places.size <= startLimit) return loadArtistTracks(releases, loadAlbum).shuffled(random)
    val start = places.shuffled(random).take(startLimit)
    val needed = start.mapTo(sortedSetOf()) { (album, _) -> album }.toList()
    val loaded = needed.zip(loadAlbumsInBatches(needed.map(releases::get), loadAlbum)).toMap()
    // A place past an album's real length (its count was off) drops out.
    return start.mapNotNull { (album, track) -> loaded[album]?.getOrNull(track) }
}

/** Each release's tracks, in order, with at most [ARTIST_ALBUM_LOAD_BATCH] loads in flight. */
private suspend fun loadAlbumsInBatches(
    releases: List<ArtistRelease>,
    loadAlbum: suspend (MediaId) -> List<Track>
): List<List<Track>> = releases.chunked(ARTIST_ALBUM_LOAD_BATCH).flatMap { batch ->
    coroutineScope { batch.map { release -> async { loadAlbum(release.id) } }.awaitAll() }
}
