package com.gpo.yoin.ui.tips

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.BarPlaySplitActions
import com.gpo.yoin.ui.component.ExpressiveMediaArtwork
import com.gpo.yoin.ui.component.ExpressivePageBackground
import com.gpo.yoin.ui.component.YoinButtonGroup
import com.gpo.yoin.ui.detail.AlbumSong
import com.gpo.yoin.ui.detail.AlbumBarHeaderTwin
import com.gpo.yoin.ui.detail.AlbumTrackCountLabel
import com.gpo.yoin.ui.detail.AlbumTrackRow
import com.gpo.yoin.ui.library.LibraryContent
import com.gpo.yoin.ui.library.LibraryItem
import com.gpo.yoin.ui.library.LibrarySearchFilter
import com.gpo.yoin.ui.library.LibrarySearchFilterRow
import com.gpo.yoin.ui.library.LibrarySearchScope
import com.gpo.yoin.ui.library.LibraryTab
import com.gpo.yoin.ui.library.LibraryUiState
import com.gpo.yoin.ui.navigation.YoinSection
import com.gpo.yoin.ui.theme.YoinArtworkShapes
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Library and an album page on the demo phone. Library is the real
 * LibraryContent over made-up albums, under the real bar; the album page is
 * the real header and track rows under the detail pose of the same bar.
 */

/** Long-press a song on an album → its song sheet. */
@Composable
internal fun SongMenuDemo(playing: Boolean) {
    var open by remember { mutableStateOf(false) }
    var openRow by remember { mutableIntStateOf(0) }
    val album = DemoAlbums[1]
    val cover = demoCoverUrl(album)
    DemoPhone(playing = playing, script = {
        open = false
        delay(300)
        moveTo("row2", fx = 0.45f)
        hold {
            openRow = 2
            open = true
        }
        lift()
        delay(1_800)
        moveTo("row0", fx = 0.5f)
        tap { open = false }
        delay(500)
    }) {
        DemoAlbumPage(album = album, cover = cover, onLongPressRow = {
            openRow = it
            open = true
        })
        DemoSongSheet(
            open = open,
            onDismiss = { open = false },
            track = demoTrack(openRow, album, cover),
            coverUrl = cover,
            groups = demoSongSheetRows(withGoToAlbum = false),
        )
    }
}

/** Long-press Library in the bar → search. */
@Composable
internal fun LibrarySearchDemo(playing: Boolean) {
    var searching by remember { mutableStateOf(false) }
    DemoPhone(playing = playing, script = {
        searching = false
        delay(300)
        moveTo("bar", fx = 0.75f)
        hold { searching = true }
        lift()
        delay(1_800)
        moveTo("searchBack")
        tap { searching = false }
        delay(500)
    }) {
        DemoLibraryPage(
            gridState = rememberLazyGridState(),
            onSearch = { searching = true },
        )
        DemoLibrarySearch(visible = searching, onClose = { searching = false })
    }
}

/** Scroll Library: the cards sink under the tide line below its header. */
@Composable
internal fun ScrollEdgeDemo(playing: Boolean) {
    val grid = rememberLazyGridState()
    val travel = with(LocalDensity.current) { ScrollTravel.toPx() }
    DemoPhone(playing = playing, script = {
        grid.scrollToItem(0)
        delay(300)
        moveTo("libraryBody", fy = 0.7f)
        coroutineScope {
            launch { grid.animateScrollBy(travel, tween(1_100)) }
            drag(at("libraryBody", fy = 0.25f).x, at("libraryBody", fy = 0.25f).y, ms = 1_100) {}
        }
        lift()
        delay(1_200)
        grid.animateScrollBy(-travel, tween(900))
        delay(400)
    }) {
        DemoLibraryPage(gridState = grid, onSearch = {})
    }
}

@Composable
private fun BoxScope.DemoLibraryPage(gridState: LazyGridState, onSearch: () -> Unit) {
    val albums = demoLibraryAlbums()
    var tab by remember { mutableStateOf(LibraryTab.All) }
    val state = remember(albums, tab) {
        val items = buildList<LibraryItem> {
            albums.forEachIndexed { i, album ->
                add(LibraryItem.AlbumItem(album))
                if (i % 3 == 1) DemoLibraryArtists.getOrNull(i / 3)?.let { add(LibraryItem.ArtistItem(it)) }
            }
        }
        LibraryUiState.Content(
            selectedTab = tab,
            artists = DemoLibraryArtists,
            albums = albums,
            songs = emptyList(),
            playlists = emptyList(),
            favorites = null,
            searchQuery = "",
            searchResults = null,
            isSearching = false,
            allItems = items,
        )
    }
    LibraryContent(
        uiState = state,
        onTabSelected = { tab = if (tab == it) LibraryTab.All else it },
        onSearchQueryChanged = {},
        onClearSearch = {},
        onNavigateToSettings = {},
        onArtistClick = {},
        onAlbumClick = {},
        onPlaylistClick = {},
        onSongClick = {},
        onRetry = {},
        coverArtUrlBuilder = null,
        allGridState = gridState,
        modifier = Modifier.matchParentSize().demoTarget("libraryBody"),
    )
    // The search pill opens the app's full-screen search, a window of its
    // own: here it opens the phone's search instead.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(SearchPillBand)
            .pointerInput(onSearch) {
                awaitEachGesture {
                    awaitFirstDown(pass = PointerEventPass.Initial).consume()
                    val up = awaitPointerEvent(PointerEventPass.Initial)
                    up.changes.forEach { it.consume() }
                    onSearch()
                }
            },
    )
    YoinButtonGroup(
        selectedSection = YoinSection.LIBRARY,
        currentTrackId = null,
        currentTrackTitle = null,
        currentTrackArtist = null,
        currentTrackCoverArtUrl = null,
        isPlaybackReady = false,
        connectionErrorMessage = null,
        onHomeClick = {},
        onNowPlayingClick = {},
        onLibraryClick = {},
        onLibraryLongClick = onSearch,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .demoTarget("bar"),
    )
}

/**
 * Library's full-screen search as it opens (M3's ExpandedFullScreenSearchBar
 * is a window of its own, so the phone draws its look): the input row with
 * the back arrow and the library placeholder, and the real filter chips.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoxScope.DemoLibrarySearch(visible: Boolean, onClose: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = YoinMotion.fadeIn(YoinMotionRole.Standard) +
            YoinMotion.scaleIn(YoinMotionRole.Expressive, initialScale = 0.94f),
        exit = YoinMotion.fadeOut(YoinMotionRole.Standard) +
            YoinMotion.scaleOut(YoinMotionRole.Standard, targetScale = 0.94f),
        modifier = Modifier.matchParentSize(),
    ) {
        Surface(color = SearchBarDefaults.colors().containerColor, modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.statusBarsPadding()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(SearchBarDefaults.InputFieldHeight + 16.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose, modifier = Modifier.demoTarget("searchBack")) {
                        Icon(imageVector = YoinSymbols.Back, contentDescription = null)
                    }
                    Text(
                        text = stringResource(R.string.library_search_placeholder_library),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                var filter by remember { mutableStateOf(LibrarySearchFilter.All) }
                LibrarySearchFilterRow(
                    catalogScope = null,
                    selectedScope = LibrarySearchScope.CurrentLibrary,
                    onScopeSelected = {},
                    filters = LibrarySearchFilter.entries,
                    selectedFilter = filter,
                    onFilterSelected = { filter = it },
                    fillCanvas = false,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    }
}

/** The album page with its track list up: the header on the album's band, the rows, the detail bar. */
@Composable
private fun BoxScope.DemoAlbumPage(album: DemoAlbum, cover: String, onLongPressRow: (Int) -> Unit) {
    val accent = DemoAlbumAccent
    val songs = remember(album) {
        List(7) { i ->
            AlbumSong(
                id = "tip-song-$i",
                title = DemoTrackTitles[i],
                artist = album.artist,
                trackNumber = i + 1,
                duration = 180 + i * 23,
                isStarred = i == 1,
            )
        }
    }
    ExpressivePageBackground(modifier = Modifier.matchParentSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxWidth().background(accent)) {
                AlbumBarHeaderTwin(
                    albumName = album.name,
                    artistName = album.artist,
                    year = 2024,
                    pageFraction = { 0f },
                    selectedPage = 0,
                    pageCount = 2,
                    titleEndRoom = 64.dp,
                    titleEndProgress = { 1f },
                )
                ExpressiveMediaArtwork(
                    model = cover,
                    contentDescription = null,
                    shape = YoinArtworkShapes.Thumb,
                    fallbackIcon = YoinSymbols.Album,
                    tonalElevation = 0.dp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(top = 12.dp, end = 16.dp)
                        .size(48.dp),
                )
            }
            Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                AlbumTrackCountLabel(
                    count = songs.size,
                    totalDurationSeconds = songs.sumOf { it.duration ?: 0 },
                    modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 10.dp),
                )
                songs.forEachIndexed { index, song ->
                    AlbumTrackRow(
                        index = index,
                        song = song,
                        hasNote = index == 3,
                        isNowPlaying = false,
                        accent = accent,
                        onClick = {},
                        onLongClick = { onLongPressRow(index) },
                        onToggleStar = {},
                        showArtist = false,
                        modifier = Modifier.demoTarget("row$index"),
                    )
                }
            }
        }
        YoinButtonGroup(
            selectedSection = YoinSection.HOME,
            currentTrackId = null,
            currentTrackTitle = null,
            currentTrackArtist = null,
            currentTrackCoverArtUrl = null,
            isPlaybackReady = false,
            connectionErrorMessage = null,
            chromeProgress = { 1f },
            playSplitActions = remember {
                BarPlaySplitActions(
                    playContainer = accent,
                    playContent = Color.White,
                    onPlay = {},
                    onShuffle = {},
                    menuItems = {},
                )
            },
            onHomeClick = {},
            onNowPlayingClick = {},
            onLibraryClick = {},
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** The album page's band and accent, as the cover of [DemoAlbums]`[1]` gives it. */
private val DemoAlbumAccent = Color(0xFF9C3826)

/** The collapsed search pill's band under the status bar. */
private val SearchPillBand = 64.dp

/** How far the scroll-edge demo scrolls Library. */
private val ScrollTravel = 360.dp
