package com.gpo.yoin.ui.tips

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.animateColorAsState
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
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.player.continueon.ContinueOnPrompt
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.BarPlaySplitActions
import com.gpo.yoin.ui.component.ExpressiveMediaArtwork
import com.gpo.yoin.ui.component.ExpressivePageBackground
import com.gpo.yoin.ui.component.YoinButtonGroup
import com.gpo.yoin.ui.continueon.ContinueOnCard
import com.gpo.yoin.ui.continueon.sampleContinueOnPrompt
import com.gpo.yoin.ui.detail.AlbumSong
import com.gpo.yoin.ui.detail.AlbumBarHeaderTwin
import com.gpo.yoin.ui.detail.AlbumBarTitleLift
import com.gpo.yoin.ui.detail.AlbumTrackCountLabel
import com.gpo.yoin.ui.detail.AlbumTrackRow
import com.gpo.yoin.ui.detail.TrackMenuRow
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
            quickActions = demoSongSheetQuickActions(nowPlaying = false),
        )
    }
}

/**
 * Long-press the mini player in the bar → the playing song's sheet, as Now
 * Playing's cover gives it but without the player group (owner 2026-11-03).
 */
@Composable
internal fun MiniPlayerMenuDemo(playing: Boolean) {
    var open by remember { mutableStateOf(false) }
    val album = DemoAlbums[0]
    val cover = demoCoverUrl(album)
    val track = remember(cover) { demoTrack(0, album, cover) }
    DemoPhone(playing = playing, script = {
        open = false
        delay(300)
        moveTo("bar", fx = MiniPlayerFx)
        hold { open = true }
        // Gone the frame the sheet starts up, so it never sits on a row.
        hide()
        delay(1_800)
        moveTo(0.5f, 0.12f)
        tap { open = false }
        lift()
        delay(500)
    }) {
        DemoLibraryPage(
            gridState = rememberLazyGridState(),
            onSearch = {},
            nowPlaying = track,
            playing = playing,
            onMiniPlayerLongPress = { open = true },
        )
        DemoSongSheet(
            open = open,
            onDismiss = { open = false },
            track = track,
            coverUrl = cover,
            groups = demoSongSheetRows(withGoToAlbum = true, nowPlaying = true),
            quickActions = demoSongSheetQuickActions(nowPlaying = true),
        )
    }
}

/**
 * Subsonic downloads from a song's sheet: long-press a song, tap Download,
 * and the next time the sheet says Remove download (OfflineToggle's labels).
 */
@Composable
internal fun DownloadsDemo(playing: Boolean) {
    var open by remember { mutableStateOf(false) }
    var downloaded by remember { mutableStateOf(false) }
    val album = DemoAlbums[1]
    val cover = demoCoverUrl(album)
    val addToPlaylist = stringResource(R.string.detail_menu_add_to_playlist)
    val download = stringResource(R.string.offline_download)
    val remove = stringResource(R.string.offline_remove_download)
    val baseRows = demoSongSheetRows(withGoToAlbum = false)
    val groups = remember(baseRows, downloaded, download, remove, addToPlaylist) {
        val offline = if (downloaded) {
            TrackMenuRow(remove, YoinSymbols.DownloadDone) { downloaded = false }
        } else {
            TrackMenuRow(download, YoinSymbols.Download) { downloaded = true }
        }
        listOf(listOf(TrackMenuRow(addToPlaylist, YoinSymbols.Playlist) {}, offline)) + baseRows.drop(1)
    }
    DemoPhone(playing = playing, script = {
        open = false
        downloaded = false
        delay(300)
        moveTo("row2", fx = 0.45f)
        hold { open = true }
        // Off the song the moment the sheet is up, so the dot never sits on its buttons.
        moveTo("offline", fx = 0.4f)
        delay(250)
        tap {}
        // The row answers first; the sheet goes down once the dot has left it.
        downloaded = true
        lift()
        open = false
        delay(900)
        moveTo("row2", fx = 0.45f)
        hold { open = true }
        moveTo("offline", fx = 0.4f)
        delay(300)
        lift()
        delay(1_800)
        // Close on the scrim above the sheet, not on the sheet's header.
        moveTo(0.5f, 0.12f)
        tap { open = false }
        hide()
        delay(500)
    }) {
        DemoAlbumPage(album = album, cover = cover, onLongPressRow = { open = true })
        DemoSongSheet(
            open = open,
            onDismiss = { open = false },
            track = demoTrack(2, album, cover),
            coverUrl = cover,
            groups = groups,
            quickActions = demoSongSheetQuickActions(nowPlaying = false),
            rowModifier = { row -> if (row.label == download || row.label == remove) Modifier.demoTarget("offline") else Modifier },
            onRowClick = { row -> row.onClick() },
        )
    }
}

/**
 * Continue On: another device hands its song over, the card comes up over Library, and
 * "Continue here" takes it (the other device pauses).
 */
@Composable
internal fun ContinueOnDemo(playing: Boolean) {
    var card by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    val album = DemoAlbums[2]
    val cover = demoCoverUrl(album)
    val prompt = remember(album, cover, starting) {
        sampleContinueOnPrompt(
            title = DemoTrackTitles[0],
            artist = album.artist,
            album = album.name,
            coverUrl = cover,
            stage = if (starting) ContinueOnPrompt.Stage.Starting else ContinueOnPrompt.Stage.Asking,
        )
    }
    DemoPhone(playing = playing, script = {
        card = false
        starting = false
        delay(700)
        card = true
        delay(1_400)
        moveTo("continueHere")
        tap { starting = true }
        lift()
        delay(700)
        card = false
        delay(1_600)
        hide()
    }) {
        DemoLibraryPage(gridState = rememberLazyGridState(), onSearch = {})
        ContinueOnCard(
            prompt = prompt.takeIf { card },
            onContinue = { starting = true },
            onDismiss = { card = false },
            continueModifier = Modifier.demoTarget("continueHere"),
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

/**
 * Keyboard shortcuts: Space pauses and plays the mini player, Ctrl + → moves
 * it to the next song, Ctrl + K opens search — a keyboard's keys as keycaps
 * above the bar, pressed by the dot (or the user).
 */
@Composable
internal fun KeyboardShortcutsDemo(playing: Boolean) {
    var paused by remember { mutableStateOf(false) }
    var song by remember { mutableIntStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf<DemoKey?>(null) }
    val album = DemoAlbums[0]
    val cover = demoCoverUrl(album)
    val track = remember(cover, song) { demoTrack(song, album, cover) }
    val press: (DemoKey) -> Unit = { key ->
        when (key) {
            DemoKey.Space -> paused = !paused
            DemoKey.Next -> song += 1
            DemoKey.Search -> searching = true
        }
    }
    DemoPhone(playing = playing, script = {
        paused = false
        song = 0
        searching = false
        delay(300)
        DemoKey.entries.forEach { key ->
            moveTo(key.name)
            tap {
                pressed = key
                press(key)
            }
            pressed = null
            delay(if (key == DemoKey.Search) 1_600 else 900)
        }
        moveTo("searchBack")
        tap { searching = false }
        delay(600)
        // Space again: the bar plays on.
        moveTo(DemoKey.Space.name)
        tap {
            pressed = DemoKey.Space
            press(DemoKey.Space)
        }
        pressed = null
        lift()
        delay(500)
    }) {
        DemoLibraryPage(
            gridState = rememberLazyGridState(),
            onSearch = { searching = true },
            nowPlaying = track,
            playing = !paused,
        )
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = KeycapLift),
            horizontalArrangement = Arrangement.spacedBy(KeycapGap),
        ) {
            DemoKey.entries.forEach { key ->
                DemoKeycaps(
                    key = key,
                    pressed = pressed == key,
                    onPress = { press(key) },
                    modifier = Modifier.demoTarget(key.name),
                )
            }
        }
        DemoLibrarySearch(visible = searching, onClose = { searching = false })
    }
}

/** The three chords the demo presses (ShellShortcut: PlayPause, Next, Search). */
private enum class DemoKey { Space, Next, Search }

/** One chord as a keyboard's keycaps side by side; pressing either presses the chord. */
@Composable
private fun DemoKeycaps(key: DemoKey, pressed: Boolean, onPress: () -> Unit, modifier: Modifier = Modifier) {
    val labels = when (key) {
        DemoKey.Space -> listOf(stringResource(R.string.shortcut_key_space))
        DemoKey.Next -> listOf("Ctrl", "→")
        DemoKey.Search -> listOf("Ctrl", "K")
    }
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (pressed) scheme.primaryContainer else scheme.surfaceContainerHighest,
        YoinMotion.fastEffectsSpec(),
        label = "keycap",
    )
    val content by animateColorAsState(
        if (pressed) scheme.onPrimaryContainer else scheme.onSurface,
        YoinMotion.fastEffectsSpec(),
        label = "keycapText",
    )
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(KeycapInnerGap)) {
        labels.forEach { label ->
            Surface(
                onClick = onPress,
                shape = MaterialTheme.shapes.small,
                color = container,
                contentColor = content,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .defaultMinSize(minWidth = KeycapMinSize, minHeight = KeycapMinSize)
                        .wrapContentSize()
                        .padding(horizontal = 10.dp),
                )
            }
        }
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
private fun BoxScope.DemoLibraryPage(
    gridState: LazyGridState,
    onSearch: () -> Unit,
    nowPlaying: Track? = null,
    playing: Boolean = false,
    onMiniPlayerLongPress: (() -> Unit)? = null,
) {
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
        currentTrackId = nowPlaying?.id?.toString(),
        currentTrackTitle = nowPlaying?.title,
        currentTrackArtist = nowPlaying?.artist,
        currentTrackCoverArtUrl = (nowPlaying?.coverArt as? CoverRef.Url)?.url,
        isPlaybackReady = nowPlaying != null,
        connectionErrorMessage = null,
        playbackProgress = if (nowPlaying != null) MiniPlayerProgress else 0f,
        isPlaying = playing && nowPlaying != null,
        onHomeClick = {},
        onNowPlayingClick = {},
        onLibraryClick = {},
        onLibraryLongClick = onSearch,
        onNowPlayingLongClick = onMiniPlayerLongPress,
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
                    // Docked: the title has risen into the bar, as on the real page.
                    lift = { 1f },
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
                        .padding(top = 12.dp - AlbumBarTitleLift, end = 16.dp)
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

/** Where the mini player sits across the bar (Home | mini player | Library). */
private const val MiniPlayerFx = 0.5f

/** How far into its song the mini player's ring stands. */
private const val MiniPlayerProgress = 0.35f

/** The collapsed search pill's band under the status bar. */
private val SearchPillBand = 64.dp

/** How far the scroll-edge demo scrolls Library. */
private val ScrollTravel = 360.dp

/** The keycaps sit this far above the phone's bottom edge, clear of the bar. */
private val KeycapLift = 132.dp
private val KeycapGap = 12.dp
private val KeycapInnerGap = 4.dp
private val KeycapMinSize = 40.dp
