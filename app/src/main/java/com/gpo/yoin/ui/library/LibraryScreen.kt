package com.gpo.yoin.ui.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.data.remote.Album
import com.gpo.yoin.data.remote.Artist
import com.gpo.yoin.data.remote.Playlist
import com.gpo.yoin.data.remote.SearchResult
import com.gpo.yoin.data.remote.Song
import com.gpo.yoin.data.remote.StarredResponse
import com.gpo.yoin.player.VisualizerData
import com.gpo.yoin.ui.component.ExpressiveBackdropArtwork
import com.gpo.yoin.ui.component.ExpressiveBackdropVariant
import com.gpo.yoin.ui.component.ExpressiveMediaArtwork
import com.gpo.yoin.ui.component.ExpressiveMetaPill
import com.gpo.yoin.ui.component.ExpressivePageBackground
import com.gpo.yoin.ui.component.ExpressiveSectionPanel
import com.gpo.yoin.ui.component.ExpressiveSegmentedTabs
import com.gpo.yoin.ui.component.SongListItem
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.component.expressiveEntrance
import com.gpo.yoin.ui.component.minimumTouchTarget
import com.gpo.yoin.ui.component.playbackBackdropSignal
import com.gpo.yoin.ui.component.rememberExpressiveEntranceProgress
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinShapeTokens
import com.gpo.yoin.ui.theme.YoinTheme

private val FloatingBottomGroupContentPadding = 132.dp

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    activeSongId: String? = null,
    isPlaying: Boolean = false,
    visualizerData: VisualizerData = VisualizerData.Empty,
    onNavigateToSettings: () -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    onSongClick: (Song) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()

    LibraryContent(
        uiState = uiState,
        activeSongId = activeSongId,
        isPlaying = isPlaying,
        visualizerData = visualizerData,
        onTabSelected = viewModel::selectTab,
        onSearchQueryChanged = viewModel::search,
        onClearSearch = viewModel::clearSearch,
        onNavigateToSettings = onNavigateToSettings,
        onArtistClick = onArtistClick,
        onAlbumClick = onAlbumClick,
        onPlaylistClick = onPlaylistClick,
        onSongClick = onSongClick,
        onRetry = viewModel::refresh,
        coverArtUrlBuilder = viewModel::buildCoverArtUrl,
        modifier = modifier,
    )
}

@Composable
fun LibraryContent(
    uiState: LibraryUiState,
    activeSongId: String? = null,
    isPlaying: Boolean = false,
    visualizerData: VisualizerData = VisualizerData.Empty,
    onTabSelected: (LibraryTab) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onClearSearch: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    onSongClick: (Song) -> Unit,
    onRetry: () -> Unit,
    coverArtUrlBuilder: ((String) -> String)?,
    modifier: Modifier = Modifier,
) {
    ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
        ExpressivePageBackground(modifier = modifier) {
            when (uiState) {
                is LibraryUiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        YoinLoadingIndicator()
                    }
                }

                is LibraryUiState.Error -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = uiState.message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                TextButton(onClick = onRetry) {
                                    Text("Retry")
                                }
                                TextButton(onClick = onNavigateToSettings) {
                                    Text("Settings")
                                }
                            }
                        }
                    }
                }

                is LibraryUiState.Content -> {
                    LibraryContentBody(
                        state = uiState,
                        activeSongId = activeSongId,
                        isPlaying = isPlaying,
                        visualizerData = visualizerData,
                        onTabSelected = onTabSelected,
                        onSearchQueryChanged = onSearchQueryChanged,
                        onClearSearch = onClearSearch,
                        onNavigateToSettings = onNavigateToSettings,
                        onArtistClick = onArtistClick,
                        onAlbumClick = onAlbumClick,
                        onPlaylistClick = onPlaylistClick,
                        onSongClick = onSongClick,
                        coverArtUrlBuilder = coverArtUrlBuilder,
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryContentBody(
    state: LibraryUiState.Content,
    activeSongId: String? = null,
    isPlaying: Boolean = false,
    visualizerData: VisualizerData = VisualizerData.Empty,
    onTabSelected: (LibraryTab) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onClearSearch: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    onSongClick: (Song) -> Unit,
    coverArtUrlBuilder: ((String) -> String)?,
) {
    val playbackSignal = remember(visualizerData, isPlaying) {
        playbackBackdropSignal(
            visualizerData = visualizerData,
            isPlaying = isPlaying,
        )
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SearchHeader(
            searchQuery = state.searchQuery,
            isSearching = state.isSearching,
            onSearchQueryChanged = onSearchQueryChanged,
            onClearSearch = onClearSearch,
            onNavigateToSettings = onNavigateToSettings,
        )

        if (state.searchQuery.isBlank()) {
            LibraryFilterChips(
                selectedTab = state.selectedTab,
                onTabSelected = onTabSelected,
            )
        }

        Box(
            modifier = Modifier.weight(1f),
        ) {
            if (state.searchQuery.isNotBlank()) {
                SearchResultsContent(
                    searchResults = state.searchResults,
                    isSearching = state.isSearching,
                    activeSongId = activeSongId,
                    isPlaying = isPlaying,
                    playbackSignal = playbackSignal,
                    onArtistClick = onArtistClick,
                    onAlbumClick = onAlbumClick,
                    onSongClick = onSongClick,
                    coverArtUrlBuilder = coverArtUrlBuilder,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                AnimatedContent(
                    targetState = state.selectedTab,
                    transitionSpec = {
                        YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                            YoinMotion.fadeOut(role = YoinMotionRole.Standard)
                    },
                    label = "tabContent",
                    modifier = Modifier.fillMaxSize(),
                ) { tab ->
                    when (tab) {
                        LibraryTab.Artists -> ArtistsTabContent(
                            artists = state.artists,
                            onArtistClick = onArtistClick,
                        )
                        LibraryTab.Albums -> AlbumsTabContent(
                            albums = state.albums,
                            onAlbumClick = onAlbumClick,
                            coverArtUrlBuilder = coverArtUrlBuilder,
                        )
                        LibraryTab.Songs -> SongsTabContent(
                            songs = state.songs,
                            activeSongId = activeSongId,
                            isPlaying = isPlaying,
                            playbackSignal = playbackSignal,
                            onSongClick = onSongClick,
                            coverArtUrlBuilder = coverArtUrlBuilder,
                        )
                        LibraryTab.Playlists -> PlaylistsTabContent(
                            playlists = state.playlists,
                            onPlaylistClick = onPlaylistClick,
                            coverArtUrlBuilder = coverArtUrlBuilder,
                        )
                        LibraryTab.Favorites -> FavoritesTabContent(
                            favorites = state.favorites,
                            activeSongId = activeSongId,
                            isPlaying = isPlaying,
                            playbackSignal = playbackSignal,
                            onArtistClick = onArtistClick,
                            onAlbumClick = onAlbumClick,
                            onSongClick = onSongClick,
                            coverArtUrlBuilder = coverArtUrlBuilder,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchHeader(
    searchQuery: String,
    isSearching: Boolean,
    onSearchQueryChanged: (String) -> Unit,
    onClearSearch: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Library",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.96f),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isSearching) {
                        YoinLoadingIndicator(
                            modifier = Modifier.size(18.dp),
                            size = 18.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChanged,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (searchQuery.isBlank()) {
                                    Text(
                                        text = "Search library",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                                    )
                                }
                                innerTextField()
                            }
                        },
                    )
                    if (searchQuery.isNotBlank()) {
                        IconButton(
                            onClick = onClearSearch,
                            modifier = Modifier.minimumTouchTarget(),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Clear,
                                contentDescription = "Clear search",
                            )
                        }
                    }
                }
            }

            androidx.compose.material3.FilledIconButton(
                onClick = onNavigateToSettings,
                modifier = Modifier
                    .size(52.dp)
                    .minimumTouchTarget(),
                colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.96f),
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Settings",
                )
            }
        }
    }
}

@Composable
private fun LibraryFilterChips(
    selectedTab: LibraryTab,
    onTabSelected: (LibraryTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    ExpressiveSegmentedTabs(
        items = LibraryTab.entries,
        selectedItem = selectedTab,
        label = { it.name },
        onSelectedChange = onTabSelected,
        modifier = modifier.fillMaxWidth(),
    )
}

// ── Tab content composables ─────────────────────────────────────────────

@Composable
private fun ArtistsTabContent(
    artists: List<Artist>,
    onArtistClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (artists.isEmpty()) {
        EmptyState(message = "No artists found", modifier = modifier)
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 4.dp,
            top = 8.dp,
            end = 4.dp,
            bottom = FloatingBottomGroupContentPadding,
        ),
    ) {
        itemsIndexed(artists, key = { _, artist -> artist.id }) { index, artist ->
            val entranceProgress = rememberExpressiveEntranceProgress(
                key = artist.id,
                delayMillis = index * 24L,
            )
            ArtistListItem(
                artist = artist,
                onClick = { onArtistClick(artist.id) },
                modifier = Modifier.expressiveEntrance(entranceProgress),
            )
        }
    }
}

@Composable
private fun ArtistListItem(
    artist: Artist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = YoinShapeTokens.ExtraLarge,
        color = androidx.compose.ui.graphics.Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.84f),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = artist.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            artist.albumCount?.let { count ->
                Spacer(modifier = Modifier.width(8.dp))
                ExpressiveMetaPill(text = "$count albums")
            }
        }
    }
}

@Composable
private fun AlbumsTabContent(
    albums: List<Album>,
    onAlbumClick: (String) -> Unit,
    coverArtUrlBuilder: ((String) -> String)?,
    modifier: Modifier = Modifier,
) {
    if (albums.isEmpty()) {
        EmptyState(message = "No albums found", modifier = modifier)
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 4.dp,
            top = 12.dp,
            end = 4.dp,
            bottom = FloatingBottomGroupContentPadding,
        ),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        items(albums, key = { it.id }) { album ->
            val entranceProgress = rememberExpressiveEntranceProgress(key = album.id)
            AlbumGridItem(
                album = album,
                onClick = { onAlbumClick(album.id) },
                coverArtUrl =
                    album.coverArt?.let { coverArtUrlBuilder?.invoke(it) }
                        ?: coverArtUrlBuilder?.invoke(album.id),
                modifier = Modifier.expressiveEntrance(
                    progress = entranceProgress,
                    initialOffsetY = 22.dp,
                    initialScale = 0.92f,
                ),
            )
        }
    }
}

@Composable
private fun AlbumGridItem(
    album: Album,
    onClick: () -> Unit,
    coverArtUrl: String?,
    modifier: Modifier = Modifier,
) {
    com.gpo.yoin.ui.component.AlbumCard(
        coverArtUrl = coverArtUrl,
        title = album.name,
        subtitle = album.artist,
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        fixedWidth = null,
    )
}

@Composable
private fun SongsTabContent(
    songs: List<Song>,
    activeSongId: String? = null,
    isPlaying: Boolean = false,
    playbackSignal: Float = 0f,
    onSongClick: (Song) -> Unit,
    coverArtUrlBuilder: ((String) -> String)?,
    modifier: Modifier = Modifier,
) {
    if (songs.isEmpty()) {
        EmptyState(message = "No songs found", modifier = modifier)
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 4.dp,
            top = 8.dp,
            end = 4.dp,
            bottom = FloatingBottomGroupContentPadding,
        ),
    ) {
        itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
            val entranceProgress = rememberExpressiveEntranceProgress(
                key = song.id,
                delayMillis = index * 20L,
            )
            SongListItem(
                title = song.title.orEmpty(),
                artist = song.artist.orEmpty(),
                album = song.album.orEmpty(),
                durationSeconds = song.duration,
                coverArtUrl = song.coverArt?.let { coverArtUrlBuilder?.invoke(it) },
                onClick = { onSongClick(song) },
                isNowPlaying = isPlaying && song.id == activeSongId,
                playbackSignal = playbackSignal,
                modifier = Modifier.expressiveEntrance(entranceProgress),
            )
        }
    }
}

@Composable
private fun PlaylistsTabContent(
    playlists: List<Playlist>,
    onPlaylistClick: (String) -> Unit,
    coverArtUrlBuilder: ((String) -> String)?,
    modifier: Modifier = Modifier,
) {
    if (playlists.isEmpty()) {
        EmptyState(message = "No playlists found", modifier = modifier)
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 4.dp,
            top = 8.dp,
            end = 4.dp,
            bottom = FloatingBottomGroupContentPadding,
        ),
    ) {
        itemsIndexed(playlists, key = { _, playlist -> playlist.id }) { index, playlist ->
            val entranceProgress = rememberExpressiveEntranceProgress(
                key = playlist.id,
                delayMillis = index * 24L,
            )
            PlaylistListItem(
                playlist = playlist,
                onClick = { onPlaylistClick(playlist.id) },
                coverArtUrl = playlistBackdropArtUrl(playlist, coverArtUrlBuilder),
                modifier = Modifier.expressiveEntrance(entranceProgress),
            )
        }
    }
}

@Composable
private fun PlaylistListItem(
    playlist: Playlist,
    onClick: () -> Unit,
    coverArtUrl: String?,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = YoinShapeTokens.ExtraLarge,
        color = androidx.compose.ui.graphics.Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ExpressiveBackdropArtwork(
                model = coverArtUrl,
                contentDescription = playlist.name,
                variant = ExpressiveBackdropVariant.Ghostish,
                modifier = Modifier.size(48.dp),
                shape = YoinShapeTokens.Small,
                fallbackIcon = Icons.AutoMirrored.Filled.QueueMusic,
                fillFraction = 0.8f,
                backdropScale = 0.8f,
                artworkShiftFraction = 0.06f,
                tonalElevation = 0.dp,
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildPlaylistMeta(playlist),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun FavoritesTabContent(
    favorites: StarredResponse?,
    activeSongId: String? = null,
    isPlaying: Boolean = false,
    playbackSignal: Float = 0f,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onSongClick: (Song) -> Unit,
    coverArtUrlBuilder: ((String) -> String)?,
    modifier: Modifier = Modifier,
) {
    if (favorites == null) {
        EmptyState(message = "Loading favorites…", modifier = modifier)
        return
    }
    val hasContent = favorites.artist.isNotEmpty() ||
        favorites.album.isNotEmpty() ||
        favorites.song.isNotEmpty()

    if (!hasContent) {
        EmptyState(message = "No favorites yet", modifier = modifier)
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 4.dp,
            top = 8.dp,
            end = 4.dp,
            bottom = FloatingBottomGroupContentPadding,
        ),
    ) {
        if (favorites.artist.isNotEmpty()) {
            item {
                SectionHeader(title = "Artists")
            }
            itemsIndexed(
                items = favorites.artist,
                key = { _, artist -> "fav-artist-${artist.id}" },
            ) { index, artist ->
                val entranceProgress = rememberExpressiveEntranceProgress(
                    key = "fav-artist-${artist.id}",
                    delayMillis = index * 24L,
                )
                ArtistListItem(
                    artist = artist,
                    onClick = { onArtistClick(artist.id) },
                    modifier = Modifier.expressiveEntrance(entranceProgress),
                )
            }
        }
        if (favorites.album.isNotEmpty()) {
            item {
                SectionHeader(title = "Albums")
            }
            itemsIndexed(
                items = favorites.album,
                key = { _, album -> "fav-album-${album.id}" },
            ) { index, album ->
                val entranceProgress = rememberExpressiveEntranceProgress(
                    key = "fav-album-${album.id}",
                    delayMillis = index * 24L,
                )
                AlbumListItem(
                    album = album,
                    onClick = { onAlbumClick(album.id) },
                    coverArtUrl =
                        album.coverArt?.let { coverArtUrlBuilder?.invoke(it) }
                            ?: coverArtUrlBuilder?.invoke(album.id),
                    modifier = Modifier.expressiveEntrance(entranceProgress),
                )
            }
        }
        if (favorites.song.isNotEmpty()) {
            item {
                SectionHeader(title = "Songs")
            }
            itemsIndexed(
                items = favorites.song,
                key = { _, song -> "fav-song-${song.id}" },
            ) { index, song ->
                val entranceProgress = rememberExpressiveEntranceProgress(
                    key = "fav-song-${song.id}",
                    delayMillis = index * 20L,
                )
                SongListItem(
                    title = song.title.orEmpty(),
                    artist = song.artist.orEmpty(),
                    album = song.album.orEmpty(),
                    durationSeconds = song.duration,
                    coverArtUrl = song.coverArt?.let { coverArtUrlBuilder?.invoke(it) },
                    onClick = { onSongClick(song) },
                    isNowPlaying = isPlaying && song.id == activeSongId,
                    playbackSignal = playbackSignal,
                    modifier = Modifier.expressiveEntrance(entranceProgress),
                )
            }
        }
    }
}

@Composable
private fun AlbumListItem(
    album: Album,
    onClick: () -> Unit,
    coverArtUrl: String?,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = YoinShapeTokens.ExtraLarge,
        color = androidx.compose.ui.graphics.Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ExpressiveBackdropArtwork(
                model = coverArtUrl,
                contentDescription = album.name,
                variant = ExpressiveBackdropVariant.Bun,
                modifier = Modifier.size(52.dp),
                shape = YoinShapeTokens.Small,
                fallbackIcon = Icons.Filled.LibraryMusic,
                fillFraction = 0.8f,
                backdropScale = 0.8f,
                artworkShiftFraction = 0.06f,
                tonalElevation = 0.dp,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = album.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                album.artist?.let { artist ->
                    Text(
                        text = artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun buildPlaylistMeta(playlist: Playlist): String {
    val parts = mutableListOf<String>()
    playlist.owner?.takeIf { it.isNotBlank() }?.let(parts::add)
    playlist.songCount?.let { parts.add("$it tracks") }
    playlist.duration?.takeIf { it > 0 }?.let { parts.add(formatPlaylistDuration(it)) }
    playlist.isPublic?.let { parts.add(if (it) "Public" else "Private") }
    return parts.joinToString(" · ").ifBlank { "Playlist" }
}

private fun formatPlaylistDuration(seconds: Int): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}

@Composable
private fun SearchResultsContent(
    searchResults: SearchResult?,
    isSearching: Boolean,
    activeSongId: String? = null,
    isPlaying: Boolean = false,
    playbackSignal: Float = 0f,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onSongClick: (Song) -> Unit,
    coverArtUrlBuilder: ((String) -> String)?,
    modifier: Modifier = Modifier,
) {
    if (isSearching) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            YoinLoadingIndicator()
        }
        return
    }

    if (searchResults == null) return

    val hasResults = searchResults.artist.isNotEmpty() ||
        searchResults.album.isNotEmpty() ||
        searchResults.song.isNotEmpty()

    if (!hasResults) {
        EmptyState(message = "No results found", modifier = modifier)
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 4.dp,
            top = 8.dp,
            end = 4.dp,
            bottom = FloatingBottomGroupContentPadding,
        ),
    ) {
        if (searchResults.artist.isNotEmpty()) {
            item {
                SectionHeader(title = "Artists")
            }
            itemsIndexed(
                items = searchResults.artist,
                key = { _, artist -> "search-artist-${artist.id}" },
            ) { index, artist ->
                val entranceProgress = rememberExpressiveEntranceProgress(
                    key = "search-artist-${artist.id}",
                    delayMillis = index * 24L,
                )
                ArtistListItem(
                    artist = artist,
                    onClick = { onArtistClick(artist.id) },
                    modifier = Modifier.expressiveEntrance(entranceProgress),
                )
            }
        }
        if (searchResults.album.isNotEmpty()) {
            item {
                SectionHeader(title = "Albums")
            }
            itemsIndexed(
                items = searchResults.album,
                key = { _, album -> "search-album-${album.id}" },
            ) { index, album ->
                val entranceProgress = rememberExpressiveEntranceProgress(
                    key = "search-album-${album.id}",
                    delayMillis = index * 24L,
                )
                AlbumListItem(
                    album = album,
                    onClick = { onAlbumClick(album.id) },
                    coverArtUrl =
                        album.coverArt?.let { coverArtUrlBuilder?.invoke(it) }
                            ?: coverArtUrlBuilder?.invoke(album.id),
                    modifier = Modifier.expressiveEntrance(entranceProgress),
                )
            }
        }
        if (searchResults.song.isNotEmpty()) {
            item {
                SectionHeader(title = "Songs")
            }
            itemsIndexed(
                items = searchResults.song,
                key = { _, song -> "search-song-${song.id}" },
            ) { index, song ->
                val entranceProgress = rememberExpressiveEntranceProgress(
                    key = "search-song-${song.id}",
                    delayMillis = index * 20L,
                )
                SongListItem(
                    title = song.title.orEmpty(),
                    artist = song.artist.orEmpty(),
                    album = song.album.orEmpty(),
                    durationSeconds = song.duration,
                    coverArtUrl = song.coverArt?.let { coverArtUrlBuilder?.invoke(it) },
                    onClick = { onSongClick(song) },
                    isNowPlaying = isPlaying && song.id == activeSongId,
                    playbackSignal = playbackSignal,
                    modifier = Modifier.expressiveEntrance(entranceProgress),
                )
            }
        }
    }
}

private fun playlistBackdropArtUrl(
    playlist: Playlist,
    coverArtUrlBuilder: ((String) -> String)?,
): String? {
    if (coverArtUrlBuilder == null) return null
    return playlist.entry.firstNotNullOfOrNull { song ->
        song.coverArt?.let(coverArtUrlBuilder)
            ?: song.albumId?.let(coverArtUrlBuilder)
    }
}

@Composable
private fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun EmptyState(
    message: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        ExpressiveSectionPanel(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = YoinShapeTokens.Large,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.72f),
            tonalElevation = 1.dp,
            shadowElevation = 0.dp,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            )
        }
    }
}

// ── Previews ────────────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun LibraryContentArtistsPreview() {
    YoinTheme {
        LibraryContent(
            uiState = LibraryUiState.Content(
                selectedTab = LibraryTab.Artists,
                artists = listOf(
                    Artist(id = "1", name = "Radiohead", albumCount = 9),
                    Artist(id = "2", name = "Pink Floyd", albumCount = 15),
                    Artist(id = "3", name = "Led Zeppelin", albumCount = 9),
                ),
                albums = emptyList(),
                songs = emptyList(),
                playlists = emptyList(),
                favorites = null,
                searchQuery = "",
                searchResults = null,
                isSearching = false,
            ),
            onTabSelected = {},
            onSearchQueryChanged = {},
            onClearSearch = {},
            onNavigateToSettings = {},
            onArtistClick = {},
            onAlbumClick = {},
            onPlaylistClick = {},
            onSongClick = {},
            onRetry = {},
            coverArtUrlBuilder = null,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun LibraryContentAlbumsPreview() {
    YoinTheme {
        LibraryContent(
            uiState = LibraryUiState.Content(
                selectedTab = LibraryTab.Albums,
                artists = emptyList(),
                albums = listOf(
                    Album(id = "1", name = "OK Computer", artist = "Radiohead"),
                    Album(id = "2", name = "The Dark Side of the Moon", artist = "Pink Floyd"),
                ),
                songs = emptyList(),
                playlists = emptyList(),
                favorites = null,
                searchQuery = "",
                searchResults = null,
                isSearching = false,
            ),
            onTabSelected = {},
            onSearchQueryChanged = {},
            onClearSearch = {},
            onNavigateToSettings = {},
            onArtistClick = {},
            onAlbumClick = {},
            onPlaylistClick = {},
            onSongClick = {},
            onRetry = {},
            coverArtUrlBuilder = null,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun LibraryContentSongsPreview() {
    YoinTheme {
        LibraryContent(
            uiState = LibraryUiState.Content(
                selectedTab = LibraryTab.Songs,
                artists = emptyList(),
                albums = emptyList(),
                songs = listOf(
                    Song(
                        id = "1",
                        title = "Paranoid Android",
                        artist = "Radiohead",
                        album = "OK Computer",
                        duration = 386,
                    ),
                    Song(
                        id = "2",
                        title = "Comfortably Numb",
                        artist = "Pink Floyd",
                        album = "The Wall",
                        duration = 382,
                    ),
                ),
                playlists = emptyList(),
                favorites = null,
                searchQuery = "",
                searchResults = null,
                isSearching = false,
            ),
            onTabSelected = {},
            onSearchQueryChanged = {},
            onClearSearch = {},
            onNavigateToSettings = {},
            onArtistClick = {},
            onAlbumClick = {},
            onPlaylistClick = {},
            onSongClick = {},
            onRetry = {},
            coverArtUrlBuilder = null,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun LibraryContentLoadingPreview() {
    YoinTheme {
        LibraryContent(
            uiState = LibraryUiState.Loading,
            onTabSelected = {},
            onSearchQueryChanged = {},
            onClearSearch = {},
            onNavigateToSettings = {},
            onArtistClick = {},
            onAlbumClick = {},
            onPlaylistClick = {},
            onSongClick = {},
            onRetry = {},
            coverArtUrlBuilder = null,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun LibraryContentErrorPreview() {
    YoinTheme {
        LibraryContent(
            uiState = LibraryUiState.Error("Unable to connect to server"),
            onTabSelected = {},
            onSearchQueryChanged = {},
            onClearSearch = {},
            onNavigateToSettings = {},
            onArtistClick = {},
            onAlbumClick = {},
            onPlaylistClick = {},
            onSongClick = {},
            onRetry = {},
            coverArtUrlBuilder = null,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun LibraryContentSearchPreview() {
    YoinTheme {
        LibraryContent(
            uiState = LibraryUiState.Content(
                selectedTab = LibraryTab.Artists,
                artists = emptyList(),
                albums = emptyList(),
                songs = emptyList(),
                playlists = emptyList(),
                favorites = null,
                searchQuery = "radio",
                searchResults = SearchResult(
                    artist = listOf(
                        Artist(id = "1", name = "Radiohead", albumCount = 9),
                    ),
                    album = listOf(
                        Album(id = "1", name = "OK Computer", artist = "Radiohead"),
                    ),
                    song = listOf(
                        Song(
                            id = "1",
                            title = "Radio Ga Ga",
                            artist = "Queen",
                            album = "The Works",
                            duration = 347,
                        ),
                    ),
                ),
                isSearching = false,
            ),
            onTabSelected = {},
            onSearchQueryChanged = {},
            onClearSearch = {},
            onNavigateToSettings = {},
            onArtistClick = {},
            onAlbumClick = {},
            onPlaylistClick = {},
            onSongClick = {},
            onRetry = {},
            coverArtUrlBuilder = null,
        )
    }
}
