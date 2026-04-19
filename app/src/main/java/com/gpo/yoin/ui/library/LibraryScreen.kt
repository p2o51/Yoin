package com.gpo.yoin.ui.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.SearchResults
import com.gpo.yoin.data.model.Starred
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.model.album
import com.gpo.yoin.data.model.artist
import com.gpo.yoin.data.model.entry
import com.gpo.yoin.data.model.song
// VisualizerData intentionally removed: LibraryScreen consumes a
// pre-smoothed playbackSignal from AudioVisualizerManager instead.
import com.gpo.yoin.ui.component.ExpressiveBackdropArtwork
import com.gpo.yoin.ui.component.ExpressiveBackdropVariant
import com.gpo.yoin.ui.component.ExpressiveMetaPill
import com.gpo.yoin.ui.component.ExpressivePageBackground
import com.gpo.yoin.ui.component.ExpressiveSectionPanel
import com.gpo.yoin.ui.component.ExpressiveSegmentedTabs
import com.gpo.yoin.ui.component.SongListItem
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.component.expressiveEntrance
import com.gpo.yoin.ui.component.minimumTouchTarget
import com.gpo.yoin.ui.component.rememberExpressiveEntranceProgress
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinShapeTokens
import com.gpo.yoin.ui.theme.YoinTheme

private val FloatingBottomGroupContentPaddingBase = 108.dp
private const val MaxAnimatedLibraryItems = 10

@Composable
private fun floatingBottomGroupContentPadding(): Dp =
    FloatingBottomGroupContentPaddingBase +
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

@Composable
private fun rememberLibraryItemEntrance(
    key: Any,
    index: Int,
    delayStepMillis: Long,
    enabled: Boolean = true,
): Float {
    if (!enabled || index >= MaxAnimatedLibraryItems) {
        return 1f
    }
    return rememberExpressiveEntranceProgress(
        key = key,
        delayMillis = index * delayStepMillis,
    )
}

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    activeSongId: String? = null,
    isPlaying: Boolean = false,
    playbackSignal: Float = 0f,
    onNavigateToSettings: () -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    onSongClick: (Track) -> Unit,
    onAddSongToPlaylist: (Track) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()

    LibraryContent(
        uiState = uiState,
        activeSongId = activeSongId,
        isPlaying = isPlaying,
        playbackSignal = playbackSignal,
        onTabSelected = viewModel::selectTab,
        onSearchQueryChanged = viewModel::search,
        onClearSearch = viewModel::clearSearch,
        onNavigateToSettings = onNavigateToSettings,
        onArtistClick = onArtistClick,
        onAlbumClick = onAlbumClick,
        onPlaylistClick = onPlaylistClick,
        onSongClick = onSongClick,
        onAddSongToPlaylist = onAddSongToPlaylist,
        onCreatePlaylist = viewModel::createPlaylist,
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
    playbackSignal: Float = 0f,
    onTabSelected: (LibraryTab) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onClearSearch: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    onSongClick: (Track) -> Unit,
    onAddSongToPlaylist: (Track) -> Unit = {},
    onCreatePlaylist: (name: String) -> Unit = {},
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
                        playbackSignal = playbackSignal,
                        onTabSelected = onTabSelected,
                        onSearchQueryChanged = onSearchQueryChanged,
                        onClearSearch = onClearSearch,
                        onNavigateToSettings = onNavigateToSettings,
                        onArtistClick = onArtistClick,
                        onAlbumClick = onAlbumClick,
                        onPlaylistClick = onPlaylistClick,
                        onSongClick = onSongClick,
                        onAddSongToPlaylist = onAddSongToPlaylist,
                        onCreatePlaylist = onCreatePlaylist,
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
    playbackSignal: Float = 0f,
    onTabSelected: (LibraryTab) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onClearSearch: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    onSongClick: (Track) -> Unit,
    onAddSongToPlaylist: (Track) -> Unit,
    onCreatePlaylist: (name: String) -> Unit,
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
                tabs = state.availableTabs,
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
                    onAddSongToPlaylist = onAddSongToPlaylist,
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
                            onAddSongToPlaylist = onAddSongToPlaylist,
                            coverArtUrlBuilder = coverArtUrlBuilder,
                        )
                        LibraryTab.Playlists -> PlaylistsTabContent(
                            playlists = state.playlists,
                            onPlaylistClick = onPlaylistClick,
                            onCreatePlaylist = onCreatePlaylist.takeIf { state.canCreatePlaylists },
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
                            onAddSongToPlaylist = onAddSongToPlaylist,
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
    tabs: List<LibraryTab>,
    selectedTab: LibraryTab,
    onTabSelected: (LibraryTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Render only tabs the active source supports (e.g. drop Playlists on a
    // provider without PLAYLISTS_READ). Callers pass
    // `LibraryUiState.Content.availableTabs`.
    ExpressiveSegmentedTabs(
        items = tabs,
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
            bottom = floatingBottomGroupContentPadding(),
        ),
    ) {
        itemsIndexed(artists, key = { _, artist -> artist.id.toString() }) { index, artist ->
            val entranceProgress = rememberLibraryItemEntrance(
                key = artist.id,
                index = index,
                delayStepMillis = 24L,
            )
            ArtistListItem(
                artist = artist,
                onClick = { onArtistClick(artist.id.toString()) },
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
            bottom = floatingBottomGroupContentPadding(),
        ),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        itemsIndexed(albums, key = { _, album -> album.id.toString() }) { index, album ->
            val entranceProgress = rememberLibraryItemEntrance(
                key = album.id,
                index = index,
                delayStepMillis = 24L,
            )
            AlbumGridItem(
                album = album,
                onClick = { onAlbumClick(album.id.toString()) },
                coverArtUrl =
                    libraryCoverArtUrl(album.coverArt, coverArtUrlBuilder)
                        ?: album.id.takeIf { it.provider == MediaId.PROVIDER_SUBSONIC }
                            ?.rawId?.let { coverArtUrlBuilder?.invoke(it) },
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
        extractBackdropColors = false,
        modifier = modifier.fillMaxWidth(),
        fixedWidth = null,
    )
}

@Composable
private fun SongsTabContent(
    songs: List<Track>,
    activeSongId: String? = null,
    isPlaying: Boolean = false,
    playbackSignal: Float = 0f,
    onSongClick: (Track) -> Unit,
    onAddSongToPlaylist: (Track) -> Unit,
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
            bottom = floatingBottomGroupContentPadding(),
        ),
    ) {
        itemsIndexed(songs, key = { _, song -> song.id.toString() }) { index, song ->
            val entranceProgress = rememberLibraryItemEntrance(
                key = song.id,
                index = index,
                delayStepMillis = 20L,
            )
            SongListItem(
                title = song.title.orEmpty(),
                artist = song.artist.orEmpty(),
                album = song.album.orEmpty(),
                durationSeconds = song.durationSec,
                coverArtUrl = libraryCoverArtUrl(song.coverArt, coverArtUrlBuilder),
                onClick = { onSongClick(song) },
                onLongClick = { onAddSongToPlaylist(song) },
                isNowPlaying = isPlaying && song.id.toString() == activeSongId,
                playbackSignal = playbackSignal,
                extractBackdropColors = false,
                modifier = Modifier.expressiveEntrance(entranceProgress),
            )
        }
    }
}

@Composable
private fun PlaylistsTabContent(
    playlists: List<Playlist>,
    onPlaylistClick: (String) -> Unit,
    /** `null` hides the "+" FAB (provider without PLAYLISTS_WRITE). */
    onCreatePlaylist: ((name: String) -> Unit)?,
    coverArtUrlBuilder: ((String) -> String)?,
    modifier: Modifier = Modifier,
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    val canCreate = onCreatePlaylist != null

    // Scroll-aware FAB visibility. NestedScrollConnection.onPreScroll fires
    // for every gesture delta before the LazyColumn consumes it: negative y
    // = content moving up (user scrolling down) → hide; positive y = user
    // scrolling up → show. The 1px threshold ignores micro-jitter from
    // overscroll snap that would otherwise flicker the FAB. Stays visible
    // when the list is short / not scrollable.
    var fabVisible by remember { mutableStateOf(true) }
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                when {
                    available.y < -1f -> fabVisible = false
                    available.y > 1f -> fabVisible = true
                }
                return Offset.Zero
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(nestedScrollConnection),
    ) {
        if (playlists.isEmpty()) {
            EmptyState(
                message = if (canCreate) {
                    "No playlists yet. Tap + to create one."
                } else {
                    "No playlists yet."
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 4.dp,
                    top = 8.dp,
                    end = 4.dp,
                    bottom = floatingBottomGroupContentPadding(),
                ),
            ) {
                itemsIndexed(playlists, key = { _, playlist -> playlist.id.toString() }) { index, playlist ->
                    val entranceProgress = rememberLibraryItemEntrance(
                        key = playlist.id,
                        index = index,
                        delayStepMillis = 24L,
                    )
                    PlaylistListItem(
                        playlist = playlist,
                        onClick = { onPlaylistClick(playlist.id.toString()) },
                        coverArtUrl = playlistBackdropArtUrl(playlist, coverArtUrlBuilder),
                        modifier = Modifier.expressiveEntrance(entranceProgress),
                    )
                }
            }
        }

        // Bottom-right FAB, sized for emphasis (regular `FloatingActionButton`
        // = 56dp; `SmallFloatingActionButton` 40dp felt apologetic). Sits at
        // the same vertical baseline as the last visible list item so it
        // never collides with the shell's floating bottom nav. Hidden via
        // AnimatedVisibility on scroll-down so users see more of their
        // playlists while scanning.
        if (canCreate) {
            AnimatedVisibility(
                visible = fabVisible,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = 16.dp,
                        bottom = floatingBottomGroupContentPadding(),
                    ),
            ) {
                FloatingActionButton(
                    onClick = { showCreateDialog = true },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "New playlist",
                    )
                }
            }
        }
    }

    if (showCreateDialog && onCreatePlaylist != null) {
        CreatePlaylistDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                showCreateDialog = false
                onCreatePlaylist(name)
            },
        )
    }
}

@Composable
private fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New playlist") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.trim().isNotEmpty(),
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
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
                extractBackdropColors = false,
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
    favorites: Starred?,
    activeSongId: String? = null,
    isPlaying: Boolean = false,
    playbackSignal: Float = 0f,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onSongClick: (Track) -> Unit,
    onAddSongToPlaylist: (Track) -> Unit,
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
            bottom = floatingBottomGroupContentPadding(),
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
                val entranceProgress = rememberLibraryItemEntrance(
                    key = "fav-artist-${artist.id}",
                    index = index,
                    delayStepMillis = 24L,
                )
                ArtistListItem(
                    artist = artist,
                    onClick = { onArtistClick(artist.id.toString()) },
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
                val entranceProgress = rememberLibraryItemEntrance(
                    key = "fav-album-${album.id}",
                    index = index,
                    delayStepMillis = 24L,
                )
                AlbumListItem(
                    album = album,
                    onClick = { onAlbumClick(album.id.toString()) },
                    coverArtUrl =
                        libraryCoverArtUrl(album.coverArt, coverArtUrlBuilder)
                            ?: album.id.takeIf { it.provider == MediaId.PROVIDER_SUBSONIC }
                                ?.rawId?.let { coverArtUrlBuilder?.invoke(it) },
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
                val entranceProgress = rememberLibraryItemEntrance(
                    key = "fav-song-${song.id}",
                    index = index,
                    delayStepMillis = 20L,
                )
                SongListItem(
                    title = song.title.orEmpty(),
                    artist = song.artist.orEmpty(),
                    album = song.album.orEmpty(),
                    durationSeconds = song.durationSec,
                    coverArtUrl = libraryCoverArtUrl(song.coverArt, coverArtUrlBuilder),
                    onClick = { onSongClick(song) },
                    onLongClick = { onAddSongToPlaylist(song) },
                    isNowPlaying = isPlaying && song.id.toString() == activeSongId,
                    playbackSignal = playbackSignal,
                    extractBackdropColors = false,
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
                extractBackdropColors = false,
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
    playlist.durationSec?.takeIf { it > 0 }?.let { parts.add(formatPlaylistDuration(it)) }
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
    searchResults: SearchResults?,
    isSearching: Boolean,
    activeSongId: String? = null,
    isPlaying: Boolean = false,
    playbackSignal: Float = 0f,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onSongClick: (Track) -> Unit,
    onAddSongToPlaylist: (Track) -> Unit,
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
            bottom = floatingBottomGroupContentPadding(),
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
                val entranceProgress = rememberLibraryItemEntrance(
                    key = "search-artist-${artist.id}",
                    index = index,
                    delayStepMillis = 24L,
                    enabled = false,
                )
                ArtistListItem(
                    artist = artist,
                    onClick = { onArtistClick(artist.id.toString()) },
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
                val entranceProgress = rememberLibraryItemEntrance(
                    key = "search-album-${album.id}",
                    index = index,
                    delayStepMillis = 24L,
                    enabled = false,
                )
                AlbumListItem(
                    album = album,
                    onClick = { onAlbumClick(album.id.toString()) },
                    coverArtUrl =
                        libraryCoverArtUrl(album.coverArt, coverArtUrlBuilder)
                            ?: album.id.takeIf { it.provider == MediaId.PROVIDER_SUBSONIC }
                                ?.rawId?.let { coverArtUrlBuilder?.invoke(it) },
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
                val entranceProgress = rememberLibraryItemEntrance(
                    key = "search-song-${song.id}",
                    index = index,
                    delayStepMillis = 20L,
                    enabled = false,
                )
                SongListItem(
                    title = song.title.orEmpty(),
                    artist = song.artist.orEmpty(),
                    album = song.album.orEmpty(),
                    durationSeconds = song.durationSec,
                    coverArtUrl = libraryCoverArtUrl(song.coverArt, coverArtUrlBuilder),
                    onClick = { onSongClick(song) },
                    onLongClick = { onAddSongToPlaylist(song) },
                    isNowPlaying = isPlaying && song.id.toString() == activeSongId,
                    playbackSignal = playbackSignal,
                    extractBackdropColors = false,
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
    playlist.coverArt?.let { coverArt ->
        libraryCoverArtUrl(coverArt, coverArtUrlBuilder)?.let { return it }
    }
    if (coverArtUrlBuilder == null) return null
    return playlist.entry.firstNotNullOfOrNull { song ->
        libraryCoverArtUrl(song.coverArt, coverArtUrlBuilder)
            ?: song.albumId?.takeIf { it.provider == MediaId.PROVIDER_SUBSONIC }?.rawId?.let(coverArtUrlBuilder)
    }
}

private fun libraryCoverArtUrl(
    ref: CoverRef?,
    coverArtUrlBuilder: ((String) -> String)?,
): String? = when (ref) {
    null -> null
    is CoverRef.Url -> ref.url
    is CoverRef.SourceRelative -> coverArtUrlBuilder?.invoke(ref.coverArtId)
}

private fun previewArtist(
    rawId: String,
    name: String,
    albumCount: Int,
): Artist = Artist(
    id = MediaId.subsonic(rawId),
    name = name,
    albumCount = albumCount,
    coverArt = null,
)

private fun previewAlbum(
    rawId: String,
    name: String,
    artist: String,
): Album = Album(
    id = MediaId.subsonic(rawId),
    name = name,
    artist = artist,
    artistId = null,
    coverArt = null,
    songCount = null,
    durationSec = null,
    year = null,
    genre = null,
)

private fun previewTrack(
    rawId: String,
    title: String,
    artist: String,
    album: String,
    durationSec: Int,
): Track = Track(
    id = MediaId.subsonic(rawId),
    title = title,
    artist = artist,
    artistId = null,
    album = album,
    albumId = null,
    coverArt = null,
    durationSec = durationSec,
    trackNumber = null,
    year = null,
    genre = null,
    userRating = null,
)

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
                    previewArtist(rawId = "1", name = "Radiohead", albumCount = 9),
                    previewArtist(rawId = "2", name = "Pink Floyd", albumCount = 15),
                    previewArtist(rawId = "3", name = "Led Zeppelin", albumCount = 9),
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
                    previewAlbum(rawId = "1", name = "OK Computer", artist = "Radiohead"),
                    previewAlbum(
                        rawId = "2",
                        name = "The Dark Side of the Moon",
                        artist = "Pink Floyd",
                    ),
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
                    previewTrack(
                        rawId = "1",
                        title = "Paranoid Android",
                        artist = "Radiohead",
                        album = "OK Computer",
                        durationSec = 386,
                    ),
                    previewTrack(
                        rawId = "2",
                        title = "Comfortably Numb",
                        artist = "Pink Floyd",
                        album = "The Wall",
                        durationSec = 382,
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
                searchResults = SearchResults(
                    artists = listOf(
                        previewArtist(rawId = "1", name = "Radiohead", albumCount = 9),
                    ),
                    albums = listOf(
                        previewAlbum(rawId = "1", name = "OK Computer", artist = "Radiohead"),
                    ),
                    tracks = listOf(
                        previewTrack(
                            rawId = "1",
                            title = "Radio Ga Ga",
                            artist = "Queen",
                            album = "The Works",
                            durationSec = 347,
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
