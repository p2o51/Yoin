package com.gpo.yoin.data.source.spotify

import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.Track
import java.util.concurrent.ConcurrentHashMap

/*
 * Spotify's "Made For You" playlists (owner 2026-11-15): Daily Mix,
 * Discover Weekly, Release Radar, On Repeat, daylist…
 *
 * The Web API answers 404 for every playlist Spotify makes (`37i9…`) to a
 * development-mode app since 2024-11-27, and their ids differ per user, so
 * there is no id to guess. App Remote's ContentApi reads them from the Spotify
 * app itself (Pixel Tablet, Spotify 9.2, 2026-11-15): `getChildrenOfItem` on
 * the Made For You hub ([MADE_FOR_YOU_HUB_URI]) lists the whole page — the
 * same ids Spotify's own UI opens — and on a playlist's uri lists its songs
 * (uri, title, artists and cover only: no duration, no album). Nothing plays
 * for either read.
 */

/** One App Remote content item ([com.spotify.protocol.types.ListItem]), without the SDK's types. */
data class SpotifyContentItem(
    val uri: String,
    val title: String,
    val subtitle: String,
    /** An https image (i.scdn.co, pickasso, seed-mix-image…); null when Spotify gives none. */
    val imageUrl: String?,
    val playable: Boolean,
)

/** One page of [SpotifyContentBrowser.children]: its items, and how many the parent has in all. */
data class SpotifyContentPage(val items: List<SpotifyContentItem>, val total: Int)

/**
 * App Remote's content tree: the children of [uri], [limit] from [offset]. Fails when App Remote
 * can't be reached (no Spotify app, not connected in time) or the read errors.
 */
fun interface SpotifyContentBrowser {
    suspend fun children(uri: String, limit: Int, offset: Int): Result<SpotifyContentPage>
}

/**
 * Where the Spotify source and Home reach App Remote's content tree: the player owns the
 * connection ([com.gpo.yoin.player.PlaybackManager] sets [browser] when it is built), and the
 * last Made For You list read is kept here for the playlist page, which only has an id.
 */
object SpotifyContentBridge {
    @Volatile
    var browser: SpotifyContentBrowser? = null

    private val known = ConcurrentHashMap<String, MadeForYouPlaylist>()

    /** The Made For You playlist [rawId] as last listed; null when it wasn't. */
    fun known(rawId: String): MadeForYouPlaylist? = known[rawId]

    internal fun remember(playlists: List<MadeForYouPlaylist>) {
        playlists.forEach { known[it.rawId] = it }
    }
}

/** What kind of Made For You playlist an id is, from the prefix Spotify gives each kind. */
enum class MadeForYouKind {
    Daylist,
    DiscoverWeekly,
    ReleaseRadar,
    OnRepeat,
    DailyMix,
    Blend,
    Mix,
    Other,
}

/** One Made For You playlist, as the hub lists it. */
data class MadeForYouPlaylist(
    val rawId: String,
    val title: String,
    val subtitle: String,
    val imageUrl: String?,
    val kind: MadeForYouKind,
) {
    val mediaId: MediaId get() = MediaId.spotify(rawId)
    val uri: String get() = "spotify:playlist:$rawId"
}

/** The Made For You hub's uri; Spotify's own deep link opens the same page. */
const val MADE_FOR_YOU_HUB_URI = "spotify:genre:0JQ5DAt0tbjZptfcdMSKl3"

/** Whether [rawId] is a playlist Spotify itself makes (Made For You and editorial alike). */
fun isSpotifyMadePlaylist(rawId: String): Boolean = rawId.startsWith(SPOTIFY_MADE_PREFIX)

/**
 * The kind of a Spotify-made playlist, from the characters after `37i9dQZ` (seen 2026-11-15:
 * EVXc Discover Weekly, EVXb Release Radar, F1EP daylist, F1Ep On Repeat / Repeat Rewind,
 * F1E3 Daily Mix, F1EJ Blend, F1EQ / F1EV / F1EI genre, decade, mood, artist and niche mixes).
 * An observation, not a contract: an unknown prefix is [MadeForYouKind.Other].
 */
fun madeForYouKind(rawId: String): MadeForYouKind {
    if (!isSpotifyMadePlaylist(rawId)) return MadeForYouKind.Other
    val rest = rawId.removePrefix(SPOTIFY_MADE_PREFIX)
    return when {
        rest.startsWith("EVXc") -> MadeForYouKind.DiscoverWeekly
        rest.startsWith("EVXb") -> MadeForYouKind.ReleaseRadar
        rest.startsWith("F1EP") -> MadeForYouKind.Daylist
        rest.startsWith("F1Ep") -> MadeForYouKind.OnRepeat
        rest.startsWith("F1E3") -> MadeForYouKind.DailyMix
        rest.startsWith("F1EJ") -> MadeForYouKind.Blend
        rest.startsWith("F1EQ") || rest.startsWith("F1EV") || rest.startsWith("F1EI") -> MadeForYouKind.Mix
        else -> MadeForYouKind.Other
    }
}

/** The hub's items that are playlists, in its order. */
fun List<SpotifyContentItem>.toMadeForYouPlaylists(): List<MadeForYouPlaylist> = mapNotNull { item ->
    val rawId = item.uri.removePrefix(PLAYLIST_URI_PREFIX).takeIf { item.uri.startsWith(PLAYLIST_URI_PREFIX) && it.isNotBlank() }
        ?: return@mapNotNull null
    if (item.title.isBlank()) return@mapNotNull null
    MadeForYouPlaylist(
        rawId = rawId,
        title = item.title,
        subtitle = item.subtitle,
        imageUrl = item.imageUrl,
        kind = madeForYouKind(rawId),
    )
}.distinctBy { it.rawId }

/**
 * Home's shelf: the playlists Spotify makes for the user alone and refreshes — daylist,
 * Discover Weekly, Release Radar, On Repeat (and Repeat Rewind), the Daily Mixes, Blends —
 * in that order, leaving out the long tail of genre, mood and artist mixes.
 */
fun List<MadeForYouPlaylist>.forHomeShelf(): List<MadeForYouPlaylist> {
    val order = listOf(
        MadeForYouKind.Daylist,
        MadeForYouKind.DiscoverWeekly,
        MadeForYouKind.ReleaseRadar,
        MadeForYouKind.OnRepeat,
        MadeForYouKind.DailyMix,
        MadeForYouKind.Blend,
    )
    return order.flatMap { kind -> filter { it.kind == kind } }
}

/** Every page of [uri]'s children, at most [max]; the first failure fails the read. */
suspend fun SpotifyContentBrowser.allChildren(uri: String, max: Int = CONTENT_READ_MAX): Result<List<SpotifyContentItem>> {
    val items = mutableListOf<SpotifyContentItem>()
    while (items.size < max) {
        val page = children(uri, CONTENT_PAGE_SIZE, items.size).getOrElse { return Result.failure(it) }
        if (page.items.isEmpty()) break
        items += page.items
        if (items.size >= page.total) break
    }
    return Result.success(items.take(max))
}

/** The whole Made For You hub, kept for the playlist page ([SpotifyContentBridge.known]). */
suspend fun SpotifyContentBrowser.madeForYou(): Result<List<MadeForYouPlaylist>> =
    allChildren(MADE_FOR_YOU_HUB_URI).map { it.toMadeForYouPlaylists() }.onSuccess(SpotifyContentBridge::remember)

/**
 * A Spotify-made playlist read from App Remote, for when the Web API won't serve it: its
 * songs (no duration or album, which ContentApi doesn't give), named as the hub last
 * listed it. Null when App Remote can't read it or it has no songs.
 */
internal suspend fun SpotifyContentBrowser.spotifyMadePlaylist(rawId: String): Playlist? {
    val items = allChildren("$PLAYLIST_URI_PREFIX$rawId").getOrNull() ?: return null
    val tracks = items.mapNotNull { it.toTrack() }
    if (tracks.isEmpty()) return null
    val known = SpotifyContentBridge.known(rawId)
        ?: madeForYou().getOrNull()?.firstOrNull { it.rawId == rawId }
    return Playlist(
        id = MediaId.spotify(rawId),
        name = known?.title ?: SPOTIFY_OWNER_NAME,
        owner = SPOTIFY_OWNER_NAME,
        coverArt = (known?.imageUrl ?: tracks.firstNotNullOfOrNull { (it.coverArt as? CoverRef.Url)?.url })
            ?.let(CoverRef::Url),
        songCount = tracks.size,
        durationSec = null,
        tracks = tracks,
        canWrite = false,
        comment = known?.subtitle?.takeIf { it.isNotBlank() },
        ownedByMe = false,
    )
}

private fun SpotifyContentItem.toTrack(): Track? {
    val rawId = uri.removePrefix(TRACK_URI_PREFIX).takeIf { uri.startsWith(TRACK_URI_PREFIX) && it.isNotBlank() }
        ?: return null
    return Track(
        id = MediaId.spotify(rawId),
        title = title.takeIf { it.isNotBlank() },
        artist = subtitle.takeIf { it.isNotBlank() },
        artistId = null,
        album = null,
        albumId = null,
        coverArt = imageUrl?.let(CoverRef::Url),
        durationSec = null,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null,
    )
}

private const val SPOTIFY_MADE_PREFIX = "37i9dQZ"
private const val PLAYLIST_URI_PREFIX = "spotify:playlist:"
private const val TRACK_URI_PREFIX = "spotify:track:"
private const val SPOTIFY_OWNER_NAME = "Spotify"
private const val CONTENT_PAGE_SIZE = 50
private const val CONTENT_READ_MAX = 200
