package com.gpo.yoin.ui.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
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
import com.gpo.yoin.ui.component.ExpressiveMediaArtwork
import com.gpo.yoin.ui.component.ExpressiveMetaPill
import com.gpo.yoin.ui.component.ExpressivePageBackground
import com.gpo.yoin.ui.component.ExpressiveSectionPanel
import com.gpo.yoin.ui.component.ExpressiveSegmentedTabs
import com.gpo.yoin.ui.component.SongListItem
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.component.minimumTouchTarget
import com.gpo.yoin.ui.theme.YoinShapeTokens
import com.gpo.yoin.ui.theme.YoinTheme

private val FloatingBottomGroupContentPadding = 132.dp

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
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
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    ExpressiveSectionPanel(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = uiState.message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(onClick = onRetry) {
                                Text("Retry")
                            }
                        }
                    }
                }
            }

            is LibraryUiState.Content -> {
                LibraryContentBody(
                    state = uiState,
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

@Composable
private fun LibraryContentBody(
    state: LibraryUiState.Content,
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
            ExpressiveSectionPanel(
                modifier = Modifier.fillMaxSize(),
                shape = YoinShapeTokens.ExtraLarge,
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 3.dp,
                shadowElevation = 8.dp,
            ) {
                if (state.searchQuery.isNotBlank()) {
                    SearchResultsContent(
                        searchResults = state.searchResults,
                        isSearching = state.isSearching,
                        onArtistClick = onArtistClick,
                        onAlbumClick = onAlbumClick,
                        onSongClick = onSongClick,
                        coverArtUrlBuilder = coverArtUrlBuilder,
                    )
                } else {
                    AnimatedContent(
                        targetState = state.selectedTab,
                        transitionSpec = {
                            fadeIn(
                                spring(stiffness = Spring.StiffnessLow),
                            ) togetherWith fadeOut(
                                spring(stiffness = Spring.StiffnessLow),
                            )
                        },
                        label = "tabContent",
                        modifier = Modifier.weight(1f),
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
                                onSongClick = onSongClick,
                                coverArtUrlBuilder = coverArtUrlBuilder,
                            )
                            LibraryTab.Playlists -> PlaylistsTabContent(
                                playlists = state.playlists,
                                onPlaylistClick = onPlaylistClick,
                            )
                            LibraryTab.Favorites -> FavoritesTabContent(
                                favorites = state.favorites,
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
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 2.dp,
                shadowElevation = 4.dp,
                border = BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
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
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
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
            top = 8.dp,
            bottom = FloatingBottomGroupContentPadding,
        ),
    ) {
        items(artists, key = { it.id }) { artist ->
            val itemAlpha = remember { Animatable(0f) }
            LaunchedEffect(artist.id) {
                itemAlpha.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessLow,
                    ),
                )
            }
            ArtistListItem(
                artist = artist,
                onClick = { onArtistClick(artist.id) },
                modifier = Modifier.alpha(itemAlpha.value),
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
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = YoinShapeTokens.ExtraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.72f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(50.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(24.dp),
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
        columns = GridCells.Adaptive(minSize = 160.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 16.dp,
            end = 16.dp,
            bottom = FloatingBottomGroupContentPadding,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(albums, key = { it.id }) { album ->
            val itemAlpha = remember { Animatable(0f) }
            LaunchedEffect(album.id) {
                itemAlpha.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessLow,
                    ),
                )
            }
            AlbumGridItem(
                album = album,
                onClick = { onAlbumClick(album.id) },
                coverArtUrl =
                    album.coverArt?.let { coverArtUrlBuilder?.invoke(it) }
                        ?: coverArtUrlBuilder?.invoke(album.id),
                modifier = Modifier.alpha(itemAlpha.value),
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
            top = 8.dp,
            bottom = FloatingBottomGroupContentPadding,
        ),
    ) {
        items(songs, key = { it.id }) { song ->
            SongListItem(
                title = song.title.orEmpty(),
                artist = song.artist.orEmpty(),
                album = song.album.orEmpty(),
                durationSeconds = song.duration,
                coverArtUrl = song.coverArt?.let { coverArtUrlBuilder?.invoke(it) },
                onClick = { onSongClick(song) },
            )
        }
    }
}

@Composable
private fun PlaylistsTabContent(
    playlists: List<Playlist>,
    onPlaylistClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (playlists.isEmpty()) {
        EmptyState(message = "No playlists found", modifier = modifier)
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = 8.dp,
            bottom = FloatingBottomGroupContentPadding,
        ),
    ) {
        items(playlists, key = { it.id }) { playlist ->
            PlaylistListItem(
                playlist = playlist,
                onClick = { onPlaylistClick(playlist.id) },
            )
        }
    }
}

@Composable
private fun PlaylistListItem(
    playlist: Playlist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = YoinShapeTokens.ExtraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.72f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(50.dp),
                shape = YoinShapeTokens.Medium,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

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
            top = 8.dp,
            bottom = FloatingBottomGroupContentPadding,
        ),
    ) {
        if (favorites.artist.isNotEmpty()) {
            item {
                SectionHeader(title = "Artists")
            }
            items(favorites.artist, key = { "fav-artist-${it.id}" }) { artist ->
                ArtistListItem(
                    artist = artist,
                    onClick = { onArtistClick(artist.id) },
                )
            }
        }
        if (favorites.album.isNotEmpty()) {
            item {
                SectionHeader(title = "Albums")
            }
            items(favorites.album, key = { "fav-album-${it.id}" }) { album ->
                AlbumListItem(
                    album = album,
                    onClick = { onAlbumClick(album.id) },
                    coverArtUrl =
                        album.coverArt?.let { coverArtUrlBuilder?.invoke(it) }
                            ?: coverArtUrlBuilder?.invoke(album.id),
                )
            }
        }
        if (favorites.song.isNotEmpty()) {
            item {
                SectionHeader(title = "Songs")
            }
            items(favorites.song, key = { "fav-song-${it.id}" }) { song ->
                SongListItem(
                    title = song.title.orEmpty(),
                    artist = song.artist.orEmpty(),
                    album = song.album.orEmpty(),
                    durationSeconds = song.duration,
                    coverArtUrl = song.coverArt?.let { coverArtUrlBuilder?.invoke(it) },
                    onClick = { onSongClick(song) },
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
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = YoinShapeTokens.ExtraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.72f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ExpressiveMediaArtwork(
                model = coverArtUrl,
                contentDescription = album.name,
                modifier = Modifier.size(54.dp),
                shape = YoinShapeTokens.Medium,
                fallbackIcon = Icons.Filled.LibraryMusic,
                tonalElevation = 1.dp,
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
            top = 8.dp,
            bottom = FloatingBottomGroupContentPadding,
        ),
    ) {
        if (searchResults.artist.isNotEmpty()) {
            item {
                SectionHeader(title = "Artists")
            }
            items(searchResults.artist, key = { "search-artist-${it.id}" }) { artist ->
                ArtistListItem(
                    artist = artist,
                    onClick = { onArtistClick(artist.id) },
                )
            }
        }
        if (searchResults.album.isNotEmpty()) {
            item {
                SectionHeader(title = "Albums")
            }
            items(searchResults.album, key = { "search-album-${it.id}" }) { album ->
                AlbumListItem(
                    album = album,
                    onClick = { onAlbumClick(album.id) },
                    coverArtUrl =
                        album.coverArt?.let { coverArtUrlBuilder?.invoke(it) }
                            ?: coverArtUrlBuilder?.invoke(album.id),
                )
            }
        }
        if (searchResults.song.isNotEmpty()) {
            item {
                SectionHeader(title = "Songs")
            }
            items(searchResults.song, key = { "search-song-${it.id}" }) { song ->
                SongListItem(
                    title = song.title.orEmpty(),
                    artist = song.artist.orEmpty(),
                    album = song.album.orEmpty(),
                    durationSeconds = song.duration,
                    coverArtUrl = song.coverArt?.let { coverArtUrlBuilder?.invoke(it) },
                    onClick = { onSongClick(song) },
                )
            }
        }
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
