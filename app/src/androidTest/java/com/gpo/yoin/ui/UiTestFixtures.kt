package com.gpo.yoin.ui

import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.SearchResults
import com.gpo.yoin.data.model.Starred
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.profile.ProviderKind
import com.gpo.yoin.ui.library.LibraryTab
import com.gpo.yoin.ui.library.LibraryUiState
import com.gpo.yoin.ui.settings.ProfileCard
import com.gpo.yoin.ui.settings.SettingsUiState

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
    spotifyClientId = "",
    spotifyClientIdUsesFallback = false,
)
