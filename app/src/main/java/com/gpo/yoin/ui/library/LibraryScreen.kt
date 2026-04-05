package com.gpo.yoin.ui.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gpo.yoin.R
import com.gpo.yoin.data.remote.Album
import com.gpo.yoin.data.remote.Artist
import com.gpo.yoin.data.remote.SearchResult
import com.gpo.yoin.data.remote.Song
import com.gpo.yoin.data.remote.StarredResponse
import com.gpo.yoin.ui.component.SongListItem
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.theme.YoinTheme

private val FloatingBottomGroupContentPadding = 132.dp

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onNavigateToSettings: () -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
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
    onSongClick: (Song) -> Unit,
    onRetry: () -> Unit,
    coverArtUrlBuilder: ((String) -> String)?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
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
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
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

            is LibraryUiState.Content -> {
                LibraryContentBody(
                    state = uiState,
                    onTabSelected = onTabSelected,
                    onSearchQueryChanged = onSearchQueryChanged,
                    onClearSearch = onClearSearch,
                    onNavigateToSettings = onNavigateToSettings,
                    onArtistClick = onArtistClick,
                    onAlbumClick = onAlbumClick,
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
    onSongClick: (Song) -> Unit,
    coverArtUrlBuilder: ((String) -> String)?,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Search bar + settings gear
        SearchHeader(
            searchQuery = state.searchQuery,
            isSearching = state.isSearching,
            onSearchQueryChanged = onSearchQueryChanged,
            onClearSearch = onClearSearch,
            onNavigateToSettings = onNavigateToSettings,
        )

        // Show search results if query is active
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
            // Tab row
            LibraryFilterChips(
                selectedTab = state.selectedTab,
                onTabSelected = onTabSelected,
            )

            // Tab content with Effects Spring crossfade
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

@Composable
private fun SearchHeader(
    searchQuery: String,
    isSearching: Boolean,
    onSearchQueryChanged: (String) -> Unit,
    onClearSearch: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChanged,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Search library…") },
            leadingIcon = {
                if (isSearching) {
                    YoinLoadingIndicator(
                        modifier = Modifier.size(20.dp),
                        size = 20.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = "Search",
                    )
                }
            },
            trailingIcon = {
                if (searchQuery.isNotBlank()) {
                    IconButton(onClick = onClearSearch) {
                        Icon(
                            imageVector = Icons.Filled.Clear,
                            contentDescription = "Clear search",
                        )
                    }
                }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.extraLarge,
        )

        IconButton(onClick = onNavigateToSettings) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "Settings",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LibraryFilterChips(
    selectedTab: LibraryTab,
    onTabSelected: (LibraryTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = LibraryTab.entries
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tabs.forEach { tab ->
            FilterChip(
                selected = selectedTab == tab,
                onClick = { onTabSelected(tab) },
                label = {
                    Text(
                        text = tab.name,
                        style = MaterialTheme.typography.labelLarge,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                ),
                shape = MaterialTheme.shapes.small,
            )
        }
    }
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
            ArtistListItem(
                artist = artist,
                onClick = { onArtistClick(artist.id) },
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(48.dp),
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
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        artist.albumCount?.let { count ->
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    text = "$count",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
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
            AlbumGridItem(
                album = album,
                onClick = { onAlbumClick(album.id) },
                coverArtUrl =
                    album.coverArt?.let { coverArtUrlBuilder?.invoke(it) }
                        ?: coverArtUrlBuilder?.invoke(album.id),
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
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = coverArtUrl,
            contentDescription = album.name,
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(MaterialTheme.shapes.medium),
            contentScale = ContentScale.Crop,
            error = painterResource(R.drawable.ic_launcher_foreground),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = album.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        album.artist?.let { artist ->
            Text(
                text = artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = coverArtUrl,
            contentDescription = album.name,
            modifier = Modifier
                .size(48.dp)
                .clip(MaterialTheme.shapes.small),
            contentScale = ContentScale.Crop,
            error = painterResource(R.drawable.ic_launcher_foreground),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = album.name,
                style = MaterialTheme.typography.bodyLarge,
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
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
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
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
            onSongClick = {},
            onRetry = {},
            coverArtUrlBuilder = null,
        )
    }
}
