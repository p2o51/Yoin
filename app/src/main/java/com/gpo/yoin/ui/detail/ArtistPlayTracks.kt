package com.gpo.yoin.ui.detail

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.player.SPOTIFY_START_MAX_URIS
import kotlin.random.Random
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Most album reads an artist's Play, Shuffle or Add to queue keeps in
 * flight. Albums from the artist endpoint are summaries without tracks, so
 * each is a detail read; Spotify serves the whole process from four read
 * slots, first come first served, and three leave one free for a tap. A
 * slot goes to the next album the moment any read finishes: a sliding
 * window, not batches that wait for their slowest album.
 */
internal const val ARTIST_ALBUM_LOADS_IN_FLIGHT = 3

/** One release on the artist page: its id and the track count the provider reports (null: not reported). */
internal data class ArtistRelease(val id: MediaId, val songCount: Int?)

/**
 * How many tracks an artist's Play or Shuffle start on [provider] can use
 * (null: the whole discography).
 *
 * - Spotify: its `uris` start carries the first [SPOTIFY_START_MAX_URIS].
 * - Apple Music: MusicKit prepares a queue with one catalog request for all
 *   its ids, which Apple caps at 300, so the start takes the same 100 tracks
 *   a Library Songs start hands over ([com.gpo.yoin.player.startWindowQueue]).
 * - Subsonic and local files stream track by track: the whole discography.
 */
internal fun artistStartLimit(provider: String?): Int? = when (provider) {
    MediaId.PROVIDER_SPOTIFY, MediaId.PROVIDER_APPLE_MUSIC -> SPOTIFY_START_MAX_URIS
    else -> null
}

/**
 * Every track of [releases], in discography order, read at most
 * [ARTIST_ALBUM_LOADS_IN_FLIGHT] albums at a time. [loadAlbum] answers an
 * empty list for an album that can't be read.
 */
internal suspend fun loadArtistTracks(
    releases: List<ArtistRelease>,
    loadAlbum: suspend (MediaId) -> List<Track>
): List<Track> = loadAlbumsInOrder(releases, loadAlbum).flatten()

/**
 * The tracks an artist's Play ([shuffle] false) or Shuffle starts, in play
 * order. [startLimit] is how many tracks the provider's start can use
 * ([artistStartLimit]; null: all of them): the list never holds more, and
 * albums past those are never read.
 *
 * - Play: albums load in discography order until the list holds
 *   [startLimit] tracks, judged after every read from the real counts of
 *   the albums read and the reported counts of the ones still in flight.
 * - Shuffle: the shuffled order is fixed first, over every track the
 *   albums report, then only the albums holding its first [startLimit]
 *   places load — the same draw as shuffling the whole discography, not
 *   a shuffle of the first few albums. A drawn place whose album can't be
 *   read (or is shorter than it reported) drops out rather than being
 *   redrawn, so the list can come up short. Without a count for every
 *   album the draw can't be laid out, and the whole discography loads and
 *   shuffles.
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
    else -> loadAlbumsInOrder(releases, loadAlbum, enough = { it >= startLimit }).flatten().take(startLimit)
}

private suspend fun loadShuffledStart(
    releases: List<ArtistRelease>,
    startLimit: Int,
    random: Random,
    loadAlbum: suspend (MediaId) -> List<Track>
): List<Track> {
    if (releases.any { it.songCount == null }) {
        return loadArtistTracks(releases, loadAlbum).shuffled(random).take(startLimit)
    }
    // One place per reported track: (album, track number within the album).
    val places = releases.flatMapIndexed { album, release ->
        List(release.reportedCount) { track -> album to track }
    }
    if (places.size <= startLimit) return loadArtistTracks(releases, loadAlbum).shuffled(random).take(startLimit)
    val start = places.shuffled(random).take(startLimit)
    val needed = start.mapTo(sortedSetOf()) { (album, _) -> album }.toList()
    val loaded = needed.zip(loadAlbumsInOrder(needed.map(releases::get), loadAlbum)).toMap()
    // A place past an album's real length (its count was off, or it couldn't be read) drops out.
    return start.mapNotNull { (album, track) -> loaded[album]?.getOrNull(track) }
}

private val ArtistRelease.reportedCount: Int get() = (songCount ?: 0).coerceAtLeast(0)

/**
 * The tracks of [releases], album by album in discography order, with at
 * most [ARTIST_ALBUM_LOADS_IN_FLIGHT] reads in flight: albums start in
 * order, and each finished read hands its slot to the next album at once.
 *
 * No album starts once [enough] holds for the tracks the started albums can
 * count on — real counts for the ones read, reported counts for the ones in
 * flight. It is asked again as each read lands, so a read that comes up
 * short lets the next album start. The result holds the albums that
 * started, in order.
 */
private suspend fun loadAlbumsInOrder(
    releases: List<ArtistRelease>,
    loadAlbum: suspend (MediaId) -> List<Track>,
    enough: (expectedTracks: Int) -> Boolean = { false }
): List<List<Track>> = coroutineScope {
    val loaded = arrayOfNulls<List<Track>>(releases.size)
    val finished = Channel<Int>(Channel.UNLIMITED)
    var started = 0
    var inFlight = 0
    var expected = 0
    while (true) {
        while (started < releases.size && inFlight < ARTIST_ALBUM_LOADS_IN_FLIGHT && !enough(expected)) {
            val index = started++
            expected += releases[index].reportedCount
            inFlight++
            launch {
                loaded[index] = loadAlbum(releases[index].id)
                finished.send(index)
            }
        }
        if (inFlight == 0) break
        val index = finished.receive()
        inFlight--
        expected += loaded[index].orEmpty().size - releases[index].reportedCount
    }
    List(started) { loaded[it].orEmpty() }
}
