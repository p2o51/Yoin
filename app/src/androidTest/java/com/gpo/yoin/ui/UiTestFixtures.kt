package com.gpo.yoin.ui

import com.gpo.yoin.data.local.ActivityActionType
import com.gpo.yoin.data.local.ActivityEntityType
import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.SearchResults
import com.gpo.yoin.data.model.Starred
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.profile.ProviderKind
import com.gpo.yoin.ui.home.HomeRediscoverItem
import com.gpo.yoin.ui.home.HomeUiState
import com.gpo.yoin.ui.home.HomeWidgetCard
import com.gpo.yoin.ui.home.HomeWidgetTarget
import com.gpo.yoin.ui.library.LibraryTab
import com.gpo.yoin.ui.library.LibraryUiState
import com.gpo.yoin.ui.memories.MemoryEntityType
import com.gpo.yoin.ui.memories.MemoryScoreKind
import com.gpo.yoin.ui.settings.ProfileCard
import com.gpo.yoin.ui.settings.SettingsUiState
import java.util.Locale

internal fun sampleTrack(id: String = "song-1"): Track = Track(
    id = MediaId.spotify(id),
    title = "Track $id",
    artist = "Artist $id",
    artistId = MediaId.spotify("artist-$id"),
    album = "Album $id",
    albumId = MediaId.spotify("album-$id"),
    coverArt = CoverRef.Url("https://example.com/$id.jpg"),
    durationSec = 180,
    trackNumber = 1,
    year = 2024,
    genre = null,
    userRating = null,
    isStarred = false,
)

internal fun sampleArtist(id: String = "artist-1"): Artist = Artist(
    id = MediaId.spotify(id),
    name = "Artist $id",
    albumCount = 3,
    coverArt = CoverRef.Url("https://example.com/$id.jpg"),
    isStarred = false,
)

internal fun sampleAlbum(id: String = "album-1"): Album = Album(
    id = MediaId.spotify(id),
    name = "Album $id",
    artist = "Artist $id",
    artistId = MediaId.spotify("artist-$id"),
    coverArt = CoverRef.Url("https://example.com/$id.jpg"),
    songCount = 10,
    durationSec = 1_800,
    year = 2024,
    genre = null,
    isStarred = false,
    tracks = listOf(sampleTrack("track-for-$id")),
)

internal fun samplePlaylist(id: String = "playlist-1"): Playlist = Playlist(
    id = MediaId.spotify(id),
    name = "Playlist $id",
    owner = "alice",
    coverArt = CoverRef.Url("https://example.com/$id.jpg"),
    songCount = 2,
    durationSec = 360,
    tracks = listOf(sampleTrack("playlist-track-$id")),
    canWrite = true,
)

internal fun sampleLibraryState(
    selectedTab: LibraryTab = LibraryTab.Artists,
    availableTabs: List<LibraryTab> = LibraryTab.entries,
): LibraryUiState.Content = LibraryUiState.Content(
    selectedTab = selectedTab,
    artists = listOf(sampleArtist("artist-a")),
    albums = listOf(sampleAlbum("album-a")),
    songs = listOf(sampleTrack("song-a")),
    playlists = listOf(samplePlaylist("playlist-a")),
    favorites = Starred(
        tracks = listOf(sampleTrack("fav-song")),
        albums = listOf(sampleAlbum("fav-album")),
        artists = listOf(sampleArtist("fav-artist")),
    ),
    searchQuery = "",
    searchResults = SearchResults(),
    isSearching = false,
    availableTabs = availableTabs,
    canCreatePlaylists = true,
)

internal fun sampleSettingsState(): SettingsUiState.Content = SettingsUiState.Content(
    profileCards = listOf(
        ProfileCard(
            id = "spotify-profile",
            displayName = "Jazz Server",
            subtitle = "Spotify",
            provider = ProviderKind.SPOTIFY,
            isActive = true,
        ),
    ),
    activeProfileId = "spotify-profile",
    canAddProfile = true,
    cacheSizeBytes = 0L,
    geminiApiKey = "",
)

/**
 * A Home with every section filled, from HomeSeamPreviewTest's data. No
 * covers, so nothing loads. Activities leads with the album "Blue Hour".
 */
internal fun sampleHomeContent(
    widgetGrid: List<HomeWidgetCard> = sampleHomeWidgetGrid(),
    now: Long = System.currentTimeMillis(),
): HomeUiState.Content = HomeUiState.Content(
    activities = sampleHomeActivities(now),
    widgetGrid = widgetGrid,
    recentlyAddedTracks = sampleRecentlyAddedTracks(),
    recentlyAddedAlbums = sampleRecentlyAddedAlbums(),
    rediscover = sampleRediscoverItems(now),
)

internal fun sampleHomeActivities(now: Long = System.currentTimeMillis()): List<ActivityEvent> = listOf(
    sampleHomeActivity(1, ActivityEntityType.ALBUM, "Blue Hour", "Kota", now - 3_600_000L),
    sampleHomeActivity(2, ActivityEntityType.ARTIST, "海野", "Artist", now - 7_200_000L),
    sampleHomeActivity(3, ActivityEntityType.ALBUM, "Night Ferry", "The Lanterns", now - 9_000_000L),
    sampleHomeActivity(4, ActivityEntityType.PLAYLIST, "春日迟迟", "白日梦乐队", now - 18_000_000L),
)

internal fun sampleHomeWidgetGrid(): List<HomeWidgetCard> = listOf(
    sampleHomeWidgetCard("g0", MemoryEntityType.ALBUM, "潮水退去以后", "Album · Nao Mori", expanded = true),
    sampleHomeWidgetCard("g1", MemoryEntityType.SONG, "Slow Bloom", "Single · Ena"),
    sampleHomeWidgetCard("g2", MemoryEntityType.ALBUM, "Paper Moon", "Album · Quiet Lines"),
    sampleHomeWidgetCard("g3", MemoryEntityType.PLAYLIST, "Glass Garden", "Playlist · 51"),
    sampleHomeWidgetCard("g4", MemoryEntityType.ALBUM, "余白", "Album · Yuna", expanded = true),
    sampleHomeWidgetCard("g5", MemoryEntityType.SONG, "Salt Window", "Single · Haru"),
)

internal fun sampleRecentlyAddedTracks(): List<Track> = listOf(
    "Soft Radio" to "Paper Satellites",
    "第七个夏天" to "林间",
    "Low Orbit" to "Kota",
    "花火の音" to "海野",
).mapIndexed { index, (title, artist) ->
    Track(
        id = MediaId.subsonic("t$index"),
        title = title,
        artist = artist,
        artistId = null,
        album = null,
        albumId = null,
        coverArt = null,
        durationSec = null,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null,
    )
}

/** Eight albums: enough for the Recently Added shelf to scroll on any width. */
internal fun sampleRecentlyAddedAlbums(): List<Album> = listOf(
    "Copper Bell" to "The Lanterns",
    "Soft Focus" to "Nao Mori",
    "Undertow" to "The Lanterns",
    "Tin Sky" to "Quiet Lines",
    "North Pier" to "Nao Mori",
    "Last Tram" to "Haru",
    "慢慢来" to "林间",
    "Far Lights" to "Ena",
).mapIndexed { index, (name, artist) ->
    Album(
        id = MediaId.subsonic("ra$index"),
        name = name,
        artist = artist,
        artistId = null,
        coverArt = null,
        songCount = 9,
        durationSec = null,
        year = null,
        genre = null,
    )
}

internal fun sampleRediscoverItems(now: Long = System.currentTimeMillis()): List<HomeRediscoverItem> = listOf(
    "Harbour Lights" to "Ena",
    "Long Way Round" to "Quiet Lines",
    "白い朝" to "Yuna",
).mapIndexed { index, (name, artist) ->
    HomeRediscoverItem(
        albumId = MediaId.subsonic("rd$index"),
        albumName = name,
        artistName = artist,
        coverArtUrl = null,
        score = 9f - index * .4f,
        scoreText = "%.1f".format(Locale.US, 9f - index * .4f),
        scoreKind = MemoryScoreKind.ALBUM_RATING,
        lastPlayedAt = now - (120L + 90L * index) * DayMillis,
        firstPlayedAt = now - 900L * DayMillis,
        playCount = 12 - index * 4,
    )
}

private fun sampleHomeActivity(
    id: Long,
    type: ActivityEntityType,
    title: String,
    subtitle: String,
    timestamp: Long,
) = ActivityEvent(
    id = id,
    entityType = type.name,
    actionType = if (type == ActivityEntityType.ARTIST) {
        ActivityActionType.VISITED.name
    } else {
        ActivityActionType.PLAYED.name
    },
    entityId = "e$id",
    title = title,
    subtitle = subtitle,
    albumId = if (type == ActivityEntityType.ALBUM) "a$id" else null,
    artistId = if (type == ActivityEntityType.ARTIST) "ar$id" else null,
    timestamp = timestamp,
)

private fun sampleHomeWidgetCard(
    id: String,
    type: MemoryEntityType,
    title: String,
    subtitle: String,
    expanded: Boolean = false,
) = HomeWidgetCard(
    stableId = "grid:$id",
    entityType = type,
    title = title,
    subtitle = subtitle,
    coverArtUrl = null,
    ratingText = if (expanded) "8.2" else null,
    ratingBasis = if (expanded) "Sep 3" else null,
    comment = if (expanded) "雨停之前，把副歌听完" else null,
    expanded = expanded,
    target = HomeWidgetTarget.AlbumDetail("a-$id"),
)

private const val DayMillis = 86_400_000L
