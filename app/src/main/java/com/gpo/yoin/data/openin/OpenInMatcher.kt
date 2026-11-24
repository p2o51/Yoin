package com.gpo.yoin.data.openin

import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.transfer.MatchDecision
import com.gpo.yoin.data.transfer.TrackMatcher
import kotlin.math.max

/**
 * A found link's results on the account in use, best first (docs/open-in-yoin.md). [exact]: the best is the
 * same item by the playlist-transfer rules (an ISRC match, or [TrackMatcher]'s confident match) — the page
 * still asks, but doesn't say it couldn't confirm one.
 */
data class OpenInRanking<T>(val items: List<T>, val exact: Boolean)

/**
 * Ranks a found link's search results with the playlist-transfer matcher (docs/playlist-transfer.md §4):
 * ISRC first where the link's service named one, then title, artist and duration.
 */
object OpenInMatcher {
    /** At most this many rows on the results page. */
    const val MAX_RESULTS = 20

    private const val NAME_EXACT = 0.9
    private const val ARTIST_EXACT = 0.8

    /** The link's track as [TrackMatcher] reads it. */
    fun sourceTrack(metadata: MusicLinkMetadata): Track = Track(
        id = MediaId(SOURCE_PROVIDER, "link"),
        title = metadata.title,
        artist = metadata.artist,
        artistId = null,
        album = metadata.album,
        albumId = null,
        coverArt = null,
        durationSec = metadata.durationSec,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null,
        extras = metadata.isrc?.let { mapOf(Track.EXTRA_ISRC to it) }.orEmpty(),
    )

    /**
     * [byIsrc]: the account's tracks sharing the link's ISRC; [searched]: its title search. ISRC matches lead,
     * then the search's tracks [TrackMatcher] scores, best first, then the rest of the search as it came.
     */
    fun rankTracks(source: Track, byIsrc: List<Track>, searched: List<Track>): OpenInRanking<Track> {
        val isrcDecision = byIsrc.takeIf { it.isNotEmpty() }?.let { TrackMatcher.chooseByIsrc(source, it) }
        val isrcBest = (isrcDecision as? MatchDecision.Matched)?.track
        val searchDecision = TrackMatcher.choose(source, searched)
        val scored = searched
            .map { it to TrackMatcher.score(source, it) }
            .filter { (_, score) -> score > 0.0 }
            .sortedByDescending { (_, score) -> score }
            .map { it.first }
        val ordered = buildList {
            isrcBest?.let(::add)
            addAll(byIsrc)
            addAll(scored)
            addAll(searched)
        }.distinctBy(Track::id).take(MAX_RESULTS)
        return OpenInRanking(ordered, exact = isrcBest != null || searchDecision is MatchDecision.Matched)
    }

    fun rankAlbums(name: String?, artist: String?, albums: List<Album>): OpenInRanking<Album> =
        rankByName(albums, name, artist, { it.name }, { it.artist })

    fun rankArtists(name: String?, artists: List<Artist>): OpenInRanking<Artist> =
        rankByName(artists, name, null, { it.name }, { null })

    fun rankPlaylists(name: String?, owner: String?, playlists: List<Playlist>): OpenInRanking<Playlist> =
        rankByName(playlists, name, owner, { it.name }, { it.owner })

    /** Name first, the artist (or owner) after; items scoring nothing keep the search's order after the rest. */
    private fun <T> rankByName(
        items: List<T>,
        name: String?,
        artist: String?,
        nameOf: (T) -> String?,
        artistOf: (T) -> String?,
    ): OpenInRanking<T> {
        if (name.isNullOrBlank()) return OpenInRanking(items.take(MAX_RESULTS), exact = false)
        val scored = items.mapIndexed { index, item ->
            val nameScore = TrackMatcher.titleSimilarity(name, nameOf(item))
            val artistScore = if (artist.isNullOrBlank()) 1.0 else TrackMatcher.artistSimilarity(artist, artistOf(item))
            Scored(item, index, nameScore, artistScore)
        }
        val ordered = scored.sortedWith(
            compareByDescending<Scored<T>> { it.nameScore * 0.65 + it.artistScore * 0.35 }.thenBy { it.index },
        )
        val best = ordered.firstOrNull()
        val exact = best != null && best.nameScore >= NAME_EXACT && best.artistScore >= ARTIST_EXACT
        return OpenInRanking(ordered.map { it.item }.take(MAX_RESULTS), exact)
    }

    private class Scored<T>(val item: T, val index: Int, nameScore: Double, artistScore: Double) {
        val nameScore = max(0.0, nameScore)
        val artistScore = max(0.0, artistScore)
    }

    private const val SOURCE_PROVIDER = "openin"
}
