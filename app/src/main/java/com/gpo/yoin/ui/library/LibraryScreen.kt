package com.gpo.yoin.ui.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AppBarWithSearch
import androidx.compose.material3.ExpandedFullScreenSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarState
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpo.yoin.R
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.LibraryMembership
import com.gpo.yoin.data.model.isUnplayableAppleImport
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.SearchResults
import com.gpo.yoin.data.model.Starred
import com.gpo.yoin.data.model.Track
// VisualizerData intentionally removed: LibraryScreen consumes a
// pre-smoothed playbackSignal from AudioVisualizerManager instead.
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.common.UiText
import com.gpo.yoin.ui.common.asString
import com.gpo.yoin.ui.component.ExpressiveBackdropArtwork
import com.gpo.yoin.ui.component.ExpressiveBackdropVariant
import com.gpo.yoin.ui.component.ExpressiveMediaArtwork
import com.gpo.yoin.ui.component.ExpressiveMetaPill
import com.gpo.yoin.ui.component.ExpressivePageBackground
import com.gpo.yoin.ui.component.ExpressiveSectionPanel
import com.gpo.yoin.ui.component.ExpressiveSegmentedTabs
import com.gpo.yoin.ui.component.MetaGroup
import com.gpo.yoin.ui.component.MetaLine
import com.gpo.yoin.ui.component.SongListItem
import com.gpo.yoin.ui.component.TrackLibraryButton
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.component.expressiveEntrance
import com.gpo.yoin.ui.component.noRippleClickable
import com.gpo.yoin.ui.component.minimumTouchTarget
import com.gpo.yoin.ui.component.rememberExpressiveEntranceProgress
import com.gpo.yoin.ui.component.seamDissolve
import com.gpo.yoin.ui.component.seamDissolveViewport
import com.gpo.yoin.ui.component.expressivePageSeamBackground
import com.gpo.yoin.ui.component.seamFade
import com.gpo.yoin.ui.component.seamRemainingPx
import com.gpo.yoin.ui.component.seamScrolledPx
import com.gpo.yoin.ui.component.yoinPageContentWidth
import com.gpo.yoin.ui.experience.LayoutMode
import com.gpo.yoin.ui.experience.LocalYoinWindowInfo
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinArtworkShapes
import com.gpo.yoin.ui.theme.YoinContainerShapes
import com.gpo.yoin.ui.theme.YoinTheme
import kotlinx.coroutines.launch

private val FloatingBottomGroupContentPaddingBase = 108.dp
private const val MaxAnimatedLibraryItems = 10

@Composable
private fun floatingBottomGroupContentPadding(): Dp =
    // Landscape handsets keep the Button Group in the cutout band (either edge) — the
    // grid only clears the nav bar at the bottom (断点交接 §2.2).
    (
        if (LocalYoinWindowInfo.current.isCompactHeight) {
            LandscapeBottomBreathing
        } else {
            FloatingBottomGroupContentPaddingBase
        }
    ) + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

private val LandscapeBottomBreathing = 16.dp

// Albums/Artists grid columns (大屏适配基线).
//
// GridCells.Adaptive resolves count = floor((available + gutter) / (minSize + gutter)),
// with gutter = 12dp. Owner 2026-10-05 (Fold 8 inner screen: "a row shows only
// 5, everything is too big — a phone page shows ~9 covers, the big screen only
// 10"): past Compact the cells stay phone-sized and the COUNT follows the width
// (adaptive-principles: 数量跟宽度走，卡片内部不缩放) — a little under the
// phone's ~118dp Fixed(3) cell, since a big screen is read as a wall, not a
// shelf. Available = width − 64 (32dp gutters): 600 → 5 columns (~98dp),
// 800 (tablet portrait) → 6 (~112), the ~832dp Fold → 7 (~99), 1280 → 11 (~100).
// The canvas is no longer clamped to 720dp either (libraryPageWidth).
private val LibraryGridMinSize = 96.dp

// Landscape handset header: a fixed search pill so the chips keep the row.
private val LibraryLandscapeSearchWidth = 208.dp

// Compact keeps Fixed(3) because it also spans sub-389dp windows (360dp
// handsets, display-size scaling, split-screen narrow) where Adaptive
// would resolve to 2 columns. Medium, Tabletop and Wide share one cell.
@Composable
private fun libraryGridCells(): GridCells {
    val windowInfo = LocalYoinWindowInfo.current
    // Height first (断点交接 §4): a landscape handset's short window takes
    // ~100dp cells — 6 columns, two whole rows on the first screen — whether
    // its width reads Medium (780) or Wide (844).
    if (windowInfo.isCompactHeight) return GridCells.Adaptive(minSize = LibraryGridLandscapeMinSize)
    return when (windowInfo.layoutMode) {
        LayoutMode.Compact -> GridCells.Fixed(3)
        LayoutMode.Medium, LayoutMode.Tabletop, LayoutMode.Wide -> GridCells.Adaptive(minSize = LibraryGridMinSize)
    }
}

/**
 * The shell pane narrows when a split opens and widens again when it closes,
 * so a grid's column count changes under the user. LazyGrid then re-anchors
 * on the first item of the row holding its first visible item, drifting up to
 * a row on every narrow ↔ wide trip. This remembers the item the user scrolled
 * to (index changes seen while a scroll is in progress — relayouts happen
 * outside one) and brings its row back to the top after each width change.
 */
@Composable
private fun KeepGridAnchorAcrossWidthChanges(state: LazyGridState) {
    val anchor = remember(state) { intArrayOf(state.firstVisibleItemIndex) }
    LaunchedEffect(state) {
        launch {
            snapshotFlow { state.firstVisibleItemIndex }
                .collect { index -> if (state.isScrollInProgress) anchor[0] = index }
        }
        var lastWidth = 0
        snapshotFlow { state.layoutInfo.viewportSize.width }
            .collect { width ->
                if (lastWidth > 0 && width > 0 && width != lastWidth) state.scrollToItem(anchor[0])
                lastWidth = width
            }
    }
}

// Landscape handset cell (LibLandscape: ~100–108dp → 6 columns at 844).
private val LibraryGridLandscapeMinSize = 100.dp

// 宽度策略:Compact 走原 yoinPageContentWidth 限宽链(手机上本来就是
// no-op);Compact 以上(Medium / Tabletop / Wide)不再夹 720dp —— 内容铺满
// 画布,外加 16dp 把子项自带的 16dp 页边抬成 32dp gutter(Wide 自 2026-07-28
// A-prime 起如此;Medium 自 2026-10-05 owner 密度反馈起跟上,网格按宽度
// 加列,见 LibraryGridMinSize)。LayoutMode 读的是本页所在的列(适配原则 1)。
private fun Modifier.libraryPageWidth(fillCanvas: Boolean): Modifier =
    if (fillCanvas) {
        padding(horizontal = 16.dp)
    } else {
        yoinPageContentWidth()
    }

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
    onFavoriteSongClick: (track: Track, queue: List<Track>, startIndex: Int) -> Unit = { track, _, _ ->
        onSongClick(track)
    },
    onAddSongToPlaylist: (Track) -> Unit = {},
    // The Wide shell opens results as its detail column, BEHIND the
    // full-window search dialog: a result tap must collapse the search (the
    // query is kept) so the page it opened is seen.
    collapseSearchOnOpen: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val notedSongIds by viewModel.notedSongIds.collectAsState()
    val trackLibraryStates by viewModel.trackLibraryStates.collectAsState()
    val workingLibraryTrackIds by viewModel.workingLibraryTrackIds.collectAsState()
    val copyResources = LocalContext.current.resources
    SideEffect { viewModel.updateCopyResources(copyResources) }

    LibraryContent(
        uiState = uiState,
        activeSongId = activeSongId,
        isPlaying = isPlaying,
        playbackSignal = playbackSignal,
        notedSongIds = notedSongIds,
        trackLibraryStates = trackLibraryStates,
        workingLibraryTrackIds = workingLibraryTrackIds,
        onTabSelected = viewModel::selectTab,
        onSearchScopeSelected = viewModel::selectSearchScope,
        onSearchQueryChanged = viewModel::search,
        onClearSearch = viewModel::clearSearch,
        onRetrySearch = viewModel::retrySearch,
        onReshuffleSongs = viewModel::reshuffleSongs,
        onNavigateToSettings = onNavigateToSettings,
        onArtistClick = onArtistClick,
        onAlbumClick = onAlbumClick,
        onPlaylistClick = onPlaylistClick,
        onSongClick = onSongClick,
        onFavoriteSongClick = onFavoriteSongClick,
        onAddSongToPlaylist = onAddSongToPlaylist,
        collapseSearchOnOpen = collapseSearchOnOpen,
        onAddSongToLibrary = viewModel::addSongToLibrary,
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
    notedSongIds: Set<String> = emptySet(),
    trackLibraryStates: Map<MediaId, LibraryMembership> = emptyMap(),
    workingLibraryTrackIds: Set<MediaId> = emptySet(),
    onTabSelected: (LibraryTab) -> Unit,
    onSearchScopeSelected: (LibrarySearchScope) -> Unit = {},
    onSearchQueryChanged: (String) -> Unit,
    onClearSearch: () -> Unit,
    onRetrySearch: () -> Unit = {},
    onReshuffleSongs: () -> Unit = {},
    onNavigateToSettings: () -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    onSongClick: (Track) -> Unit,
    onFavoriteSongClick: (track: Track, queue: List<Track>, startIndex: Int) -> Unit = { track, _, _ ->
        onSongClick(track)
    },
    collapseSearchOnOpen: Boolean = false,
    onAddSongToPlaylist: (Track) -> Unit = {},
    onAddSongToLibrary: (Track) -> Unit = {},
    onCreatePlaylist: (name: String) -> Unit = {},
    onRetry: () -> Unit,
    coverArtUrlBuilder: ((String) -> String)?,
    modifier: Modifier = Modifier,
) {
    ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
        val haptics = rememberYoinHaptics()
        ExpressivePageBackground(modifier = modifier) {
            // contentKey = state class: only Loading/Error/Content changes
            // cross-fade — Content-to-Content data updates recompose in place.
            AnimatedContent(
                targetState = uiState,
                transitionSpec = {
                    YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                        YoinMotion.fadeOut(role = YoinMotionRole.Standard)
                },
                contentKey = { it::class },
                label = "libraryState",
                modifier = Modifier.fillMaxSize(),
            ) { state ->
                when (state) {
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
                                    text = state.message.asString(),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    TextButton(
                                        onClick = {
                                            haptics.performReject()
                                            onRetry()
                                        },
                                    ) {
                                        Text(stringResource(R.string.library_error_retry))
                                    }
                                    TextButton(
                                        onClick = {
                                            haptics.performContextClick()
                                            onNavigateToSettings()
                                        },
                                    ) {
                                        Text(stringResource(R.string.library_error_settings))
                                    }
                                }
                            }
                        }
                    }

                    is LibraryUiState.Content -> {
                        LibraryContentBody(
                            state = state,
                            activeSongId = activeSongId,
                            isPlaying = isPlaying,
                            playbackSignal = playbackSignal,
                            notedSongIds = notedSongIds,
                            trackLibraryStates = trackLibraryStates,
                            workingLibraryTrackIds = workingLibraryTrackIds,
                            onTabSelected = onTabSelected,
                            onSearchScopeSelected = onSearchScopeSelected,
                            onSearchQueryChanged = onSearchQueryChanged,
                            onClearSearch = onClearSearch,
                            onRetrySearch = onRetrySearch,
                            onReshuffleSongs = onReshuffleSongs,
                            onNavigateToSettings = onNavigateToSettings,
                            onArtistClick = onArtistClick,
                            onAlbumClick = onAlbumClick,
                            onPlaylistClick = onPlaylistClick,
                            onSongClick = onSongClick,
                            onFavoriteSongClick = onFavoriteSongClick,
                            onAddSongToPlaylist = onAddSongToPlaylist,
                            onAddSongToLibrary = onAddSongToLibrary,
                            onCreatePlaylist = onCreatePlaylist,
                            coverArtUrlBuilder = coverArtUrlBuilder,
                            collapseSearchOnOpen = collapseSearchOnOpen,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryContentBody(
    state: LibraryUiState.Content,
    activeSongId: String? = null,
    isPlaying: Boolean = false,
    playbackSignal: Float = 0f,
    notedSongIds: Set<String>,
    trackLibraryStates: Map<MediaId, LibraryMembership>,
    workingLibraryTrackIds: Set<MediaId>,
    onTabSelected: (LibraryTab) -> Unit,
    onSearchScopeSelected: (LibrarySearchScope) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onClearSearch: () -> Unit,
    onRetrySearch: () -> Unit,
    onReshuffleSongs: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    onSongClick: (Track) -> Unit,
    onFavoriteSongClick: (track: Track, queue: List<Track>, startIndex: Int) -> Unit,
    onAddSongToPlaylist: (Track) -> Unit,
    onAddSongToLibrary: (Track) -> Unit,
    onCreatePlaylist: (name: String) -> Unit,
    coverArtUrlBuilder: ((String) -> String)?,
    collapseSearchOnOpen: Boolean = false,
) {
    val haptics = rememberYoinHaptics()
    val scope = rememberCoroutineScope()

    // Wide 桌面档(A-prime):LayoutMode 读本页所在列的宽度,列 ≥ 840 才是
    // Wide —— 整窗、或被把手拖宽的 shell 列。
    // Height before width (断点交接 §4): landscape handsets get their own
    // one-row header whatever their width reads.
    val isLandscapePhone = LocalYoinWindowInfo.current.isCompactHeight
    val isDesktopWide = !isLandscapePhone && LocalYoinWindowInfo.current.layoutMode == LayoutMode.Wide
    // Past Compact the page fills its column (libraryPageWidth).
    val fillCanvas = !isLandscapePhone && LocalYoinWindowInfo.current.layoutMode != LayoutMode.Compact
    // Medium (tablet portrait, a 600–839 column): a left-anchored header —
    // the page title, then the pill — instead of the phone's centred pill
    // floating in a band far wider than itself.
    val isMediumHeader = !isLandscapePhone && LocalYoinWindowInfo.current.layoutMode == LayoutMode.Medium

    // One remembered scroll state per tab, hoisted above the tab
    // AnimatedContent: exited tab content is disposed, so a lazy state
    // created inside a tab body would reset and returning to that tab
    // would land back at the top instead of where the user left off.
    val artistsGridState = rememberLazyGridState()
    val albumsGridState = rememberLazyGridState()
    val songsListState = rememberLazyListState()
    val playlistsListState = rememberLazyListState()
    val favoritesListState = rememberLazyListState()

    // M3 Expressive Search. The collapsed bar lives in the page header; tapping
    // it morphs the input into a full-screen results surface (the official
    // SearchBarState animation + predictive-back). The new InputField is driven
    // by a TextFieldState, which we bridge to the existing debounced
    // viewModel.search()/clearSearch() so the data layer is untouched.
    val searchBarState = rememberSearchBarState()
    val textFieldState = rememberTextFieldState(state.searchQuery)
    val expanded = searchBarState.targetValue == SearchBarValue.Expanded

    // Queries the field sent that the VM may still echo back, late. Plain set,
    // not state: only these two effects touch it.
    val sentQueries = remember { mutableSetOf<String>() }
    // Field text → debounced VM search (mirrors the old per-keystroke onValueChange).
    LaunchedEffect(Unit) {
        snapshotFlow { textFieldState.text.toString() }
            .collect { query ->
                sentQueries += query
                onSearchQueryChanged(query)
            }
    }
    // External query resets (clear) → field, so the two never drift apart. A
    // late echo of an earlier keystroke is NOT a reset: writing it back ate
    // characters while typing ("you seem pretty sad" → "yo sem prtt sd",
    // device QA 2026-10-05).
    LaunchedEffect(state.searchQuery) {
        val query = state.searchQuery
        when (searchFieldSync(query, textFieldState.text.toString(), sentQueries)) {
            SearchFieldSync.InSync -> sentQueries.clear()
            SearchFieldSync.LateEcho -> Unit
            SearchFieldSync.ExternalReset -> {
                sentQueries.clear()
                textFieldState.setTextAndPlaceCursorAtEnd(query)
            }
        }
    }
    // A focus request from elsewhere (e.g. a "search" shortcut) opens the bar.
    LaunchedEffect(state.searchFocusRequestId) {
        if (state.searchFocusRequestId > 0L) {
            searchBarState.animateToExpanded()
        }
    }
    // Collapsing the bar (back gesture / close) leaves the search context —
    // except when a result opened the Wide shell's detail column, which keeps
    // the query for the way back.
    var keepQueryOnCollapse by remember { mutableStateOf(false) }
    LaunchedEffect(searchBarState.currentValue) {
        if (searchBarState.currentValue == SearchBarValue.Collapsed) {
            if (keepQueryOnCollapse) {
                keepQueryOnCollapse = false
            } else if (state.searchQuery.isNotBlank()) {
                onClearSearch()
            }
        }
    }
    val fromSearch: ((String) -> Unit) -> (String) -> Unit = { open ->
        { id ->
            if (collapseSearchOnOpen && searchBarState.currentValue == SearchBarValue.Expanded) {
                keepQueryOnCollapse = true
                scope.launch { searchBarState.animateToCollapsed() }
            }
            open(id)
        }
    }

    val inputField: @Composable () -> Unit = {
        SearchBarDefaults.InputField(
            textFieldState = textFieldState,
            searchBarState = searchBarState,
            onSearch = { onSearchQueryChanged(it) },
            placeholder = { Text(state.searchScope.placeholder()) },
            leadingIcon = {
                if (expanded) {
                    IconButton(
                        onClick = {
                            haptics.performTick()
                            scope.launch { searchBarState.animateToCollapsed() }
                        },
                    ) {
                        Icon(
                            imageVector = YoinSymbols.Back,
                            contentDescription = stringResource(R.string.library_cd_close_search),
                        )
                    }
                } else {
                    Icon(imageVector = YoinSymbols.Search, contentDescription = null)
                }
            },
            trailingIcon = {
                if (state.isSearching) {
                    YoinLoadingIndicator(modifier = Modifier.size(18.dp), size = 18.dp)
                } else if (textFieldState.text.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            haptics.performTick()
                            textFieldState.setTextAndPlaceCursorAtEnd("")
                        },
                    ) {
                        Icon(
                            imageVector = YoinSymbols.Close,
                            contentDescription = stringResource(R.string.library_cd_clear_search),
                        )
                    }
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // 大屏限宽:夹的是内容列(搜索 pill、chips、各 tab 网格/列表共享
            // 一个边缘);ExpressivePageBackground 留在上层全出血。Wide 桌面
            // 档不夹、铺满 —— 见 libraryPageWidth。
            .then(if (isLandscapePhone) Modifier else Modifier.libraryPageWidth(fillCanvas)),
    ) {
        if (isLandscapePhone) {
            // Landscape handset (LibLandscape): ONE header row — search pill
            // 208 · chips (scrolling) · settings — and no title: the Button
            // Group in the cutout band already says Library.
            LibraryWideHeaderRow(
                searchBarState = searchBarState,
                inputField = inputField,
                tabs = state.availableTabs,
                selectedTab = state.selectedTab,
                onTabSelected = onTabSelected,
                onNavigateToSettings = onNavigateToSettings,
                showTitle = false,
                searchMaxWidth = LibraryLandscapeSearchWidth,
                horizontalPadding = 16.dp,
                topPadding = 8.dp,
            )
        } else if (isDesktopWide) {
            // 桌面头排:标题、搜索 pill、tab chips、settings 一行左对齐排开。
            // pill 绑的还是同一个 searchBarState —— 点击照旧 morph 进下面的
            // ExpandedFullScreenSearchBar,预测性返回不变。
            LibraryWideHeaderRow(
                searchBarState = searchBarState,
                inputField = inputField,
                tabs = state.availableTabs,
                selectedTab = state.selectedTab,
                onTabSelected = onTabSelected,
                onNavigateToSettings = onNavigateToSettings,
            )
        } else if (isMediumHeader) {
            // [Library][pill ———][settings]; the chips keep their own row
            // below (a Medium column is too narrow for all of it in one).
            LibraryWideHeaderRow(
                searchBarState = searchBarState,
                inputField = inputField,
                tabs = state.availableTabs,
                selectedTab = state.selectedTab,
                onTabSelected = onTabSelected,
                onNavigateToSettings = onNavigateToSettings,
                showChips = false,
                titleStyle = MaterialTheme.typography.headlineLarge,
                topPadding = 8.dp,
            )
        } else {
            AppBarWithSearch(
                state = searchBarState,
                inputField = inputField,
                // Transparent app-bar band (drops the tonal/shadow elevation seam)
                // over the ExpressivePageBackground gradient. The pill itself is a
                // step BRIGHTER than the page's top stop: surfaceContainer matched
                // the gradient exactly and the pill dissolved into the background
                // (design review: 对比度太低) — surfaceContainerHighest stays in the
                // surface family but reads as a control.
                colors = SearchBarDefaults.appBarWithSearchColors(
                    appBarContainerColor = Color.Transparent,
                    scrolledAppBarContainerColor = Color.Transparent,
                    searchBarColors = SearchBarDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                    scrolledSearchBarContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
                actions = {
                    IconButton(
                        onClick = {
                            haptics.performContextClick()
                            onNavigateToSettings()
                        },
                        modifier = Modifier.minimumTouchTarget(),
                    ) {
                        Icon(
                            imageVector = YoinSymbols.Settings,
                            contentDescription = stringResource(R.string.library_cd_settings),
                        )
                    }
                },
            )
        }

        // Browse (collapsed): filter chips + the per-tab grid/list. No column
        // padding here — every child carries its own 16dp so the chips, the
        // grid covers, the list rows and the search pill all share ONE left
        // edge (the old outer 16dp stacked with the children's 16dp into a
        // misaligned 32dp).
        // Tight 4dp under the chips: items dissolve into the seam instead of
        // being cut at it, so the fixed gap no longer has to hide a hard edge.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Wide 桌面档与手机横屏的 chips 已并进头排,这里不再重复渲染一份。
            if (!isDesktopWide && !isLandscapePhone) {
                LibraryFilterChips(
                    tabs = state.availableTabs,
                    selectedTab = state.selectedTab,
                    onTabSelected = onTabSelected,
                )
            }
            Box(modifier = Modifier.weight(1f)) {
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
                            gridState = artistsGridState,
                            onArtistClick = onArtistClick,
                            coverArtUrlBuilder = coverArtUrlBuilder,
                        )
                        LibraryTab.Albums -> AlbumsTabContent(
                            albums = state.albums,
                            gridState = albumsGridState,
                            onAlbumClick = onAlbumClick,
                            coverArtUrlBuilder = coverArtUrlBuilder,
                        )
                        LibraryTab.Songs -> SongsTabContent(
                            songs = state.songs,
                            listState = songsListState,
                            activeSongId = activeSongId,
                            isPlaying = isPlaying,
                            playbackSignal = playbackSignal,
                            notedSongIds = notedSongIds,
                            onSongClick = onSongClick,
                            onAddSongToPlaylist = onAddSongToPlaylist.takeIf { state.canCreatePlaylists },
                            onReshuffle = onReshuffleSongs,
                            canReshuffle = state.canReshuffleSongs,
                            coverArtUrlBuilder = coverArtUrlBuilder,
                        )
                        LibraryTab.Playlists -> PlaylistsTabContent(
                            playlists = state.playlists,
                            listState = playlistsListState,
                            onPlaylistClick = onPlaylistClick,
                            onCreatePlaylist = onCreatePlaylist.takeIf { state.canCreatePlaylists },
                            coverArtUrlBuilder = coverArtUrlBuilder,
                        )
                        LibraryTab.Favorites -> FavoritesTabContent(
                            favorites = state.favorites,
                            listState = favoritesListState,
                            activeSongId = activeSongId,
                            isPlaying = isPlaying,
                            playbackSignal = playbackSignal,
                            notedSongIds = notedSongIds,
                            onArtistClick = onArtistClick,
                            onAlbumClick = onAlbumClick,
                            onSongClick = onSongClick,
                            onFavoriteSongClick = onFavoriteSongClick,
                            onAddSongToPlaylist = onAddSongToPlaylist.takeIf { state.canCreatePlaylists },
                            coverArtUrlBuilder = coverArtUrlBuilder,
                        )
                    }
                }
            }
        }
    }

    // Expanded: the full-screen search surface (scope chips + live results).
    // Edge-to-edge like every page: the surface keeps the status bar and the
    // cutouts clear (and yields to the keyboard), but NOT the bottom — the
    // results list runs under the gesture bar with its own bottom padding
    // instead of stopping on a hard inset line above it.
    ExpandedFullScreenSearchBar(
        state = searchBarState,
        inputField = inputField,
        windowInsets = {
            WindowInsets.safeDrawing
                .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                .union(WindowInsets.ime)
        },
    ) {
        if (state.canSearchSpotifyCatalog || state.canSearchAppleMusicCatalog) {
            LibrarySearchScopeChips(
                catalogScope = if (state.canSearchAppleMusicCatalog) {
                    LibrarySearchScope.AppleMusicGlobal
                } else {
                    LibrarySearchScope.SpotifyGlobal
                },
                selectedScope = state.searchScope,
                onScopeSelected = onSearchScopeSelected,
                modifier = Modifier
                    .libraryPageWidth(fillCanvas)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        SearchResultsContent(
            searchResults = state.searchResults,
            isSearching = state.isSearching,
            searchError = state.searchError,
            onRetrySearch = onRetrySearch,
            activeSongId = activeSongId,
            isPlaying = isPlaying,
            playbackSignal = playbackSignal,
            notedSongIds = notedSongIds,
            canAddToLibrary = state.canAddToLibrary,
            trackLibraryStates = trackLibraryStates,
            workingLibraryTrackIds = workingLibraryTrackIds,
            libraryActionFeedback = state.libraryActionFeedback,
            onArtistClick = fromSearch(onArtistClick),
            onAlbumClick = fromSearch(onAlbumClick),
            onPlaylistClick = fromSearch(onPlaylistClick),
            onSongClick = onSongClick,
            onAddSongToPlaylist = onAddSongToPlaylist.takeIf { state.canCreatePlaylists },
            onAddSongToLibrary = onAddSongToLibrary,
            coverArtUrlBuilder = coverArtUrlBuilder,
            // 全屏搜索面是独立 surface,不在上面的限宽列里 —— 结果列表
            // 单独走同一套宽度策略(Wide 铺满、其余夹宽,helper 自带
            // fillMaxWidth)。
            modifier = Modifier
                .weight(1f)
                .libraryPageWidth(fillCanvas),
        )
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
    // Full-width row; contentPadding keeps the resting chips on the 16dp
    // page margin while scrolled chips run under the screen edges with the
    // scroll-aware fade.
    val labels = libraryTabLabels()
    ExpressiveSegmentedTabs(
        items = tabs,
        selectedItem = selectedTab,
        label = { labels.getValue(it) },
        onSelectedChange = onTabSelected,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
    )
}

@Composable
private fun LibrarySearchScopeChips(
    catalogScope: LibrarySearchScope,
    selectedScope: LibrarySearchScope,
    onScopeSelected: (LibrarySearchScope) -> Unit,
    modifier: Modifier = Modifier,
) {
    val catalogLabel = catalogScope.chipLabel()
    val libraryLabel = LibrarySearchScope.CurrentLibrary.chipLabel()
    ExpressiveSegmentedTabs(
        items = listOf(
            catalogScope,
            LibrarySearchScope.CurrentLibrary,
        ),
        selectedItem = selectedScope,
        label = { scope ->
            if (scope == LibrarySearchScope.CurrentLibrary) libraryLabel else catalogLabel
        },
        onSelectedChange = onScopeSelected,
        modifier = modifier.fillMaxWidth(),
    )
}

// Wide 桌面头排的搜索 pill 宽度上限(mockup 360px 直译;M3 搜索栏本身的
// min width 也是 360dp,两者相等 → pill 恒为 360dp)。
private val LibraryWideSearchBarMaxWidth = 360.dp

/** Air under the last search result, past the gesture bar (no floating bar in the search surface). */
private val SearchResultsBottomReserve = 24.dp

// Wide 桌面档头排:[Library 标题][搜索 pill][tab chips][settings] 一行
// 左对齐。Medium 用同一排去掉 chips(showChips = false):pill 占满标题与
// 齿轮之间,chips 仍在下一行。全部由既有件重排组成 —— pill 是同一个 searchBarState/inputField
// 的 M3 collapsed SearchBar(全屏搜索面照旧从它 morph 展开),chips 直接
// 复用 LibraryFilterChips(weight 占满中段,内建横向滚动 + 边缘渐隐在
// 840dp 一类窄 Wide 窗继续成立),齿轮与 AppBarWithSearch actions 里同款。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryWideHeaderRow(
    searchBarState: SearchBarState,
    inputField: @Composable () -> Unit,
    tabs: List<LibraryTab>,
    selectedTab: LibraryTab,
    onTabSelected: (LibraryTab) -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
    // Without the chips the pill takes the row between title and settings.
    showChips: Boolean = true,
    titleStyle: TextStyle = MaterialTheme.typography.headlineMedium,
    searchMaxWidth: Dp = LibraryWideSearchBarMaxWidth,
    horizontalPadding: Dp = 16.dp,
    topPadding: Dp = 16.dp,
) {
    val haptics = rememberYoinHaptics()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            // 外层 libraryPageWidth 已有 16dp,这里再补 16dp → 32dp 桌面
            // gutter,与下方网格 contentPadding 的合计边距对齐一条线。
            .padding(start = horizontalPadding, top = topPadding, end = horizontalPadding, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (showTitle) {
            Text(
                text = stringResource(R.string.library_title),
                style = titleStyle,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        SearchBar(
            state = searchBarState,
            inputField = inputField,
            // 同 AppBarWithSearch 分支:pill 比页面渐变的顶端亮一档,才能
            // 读成控件(设计评审裁定,见上)。
            colors = SearchBarDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
            modifier = if (showChips) Modifier.widthIn(max = searchMaxWidth) else Modifier.weight(1f),
        )
        if (showChips) {
            LibraryFilterChips(
                tabs = tabs,
                selectedTab = selectedTab,
                onTabSelected = onTabSelected,
                modifier = Modifier.weight(1f),
            )
        }
        IconButton(
            onClick = {
                haptics.performContextClick()
                onNavigateToSettings()
            },
            modifier = Modifier.minimumTouchTarget(),
        ) {
            Icon(
                imageVector = YoinSymbols.Settings,
                contentDescription = stringResource(R.string.library_cd_settings_header),
            )
        }
    }
}

@Composable
private fun libraryTabLabels(): Map<LibraryTab, String> = mapOf(
    LibraryTab.Artists to stringResource(R.string.library_tab_artists),
    LibraryTab.Albums to stringResource(R.string.library_tab_albums),
    LibraryTab.Songs to stringResource(R.string.library_tab_songs),
    LibraryTab.Playlists to stringResource(R.string.library_tab_playlists),
    LibraryTab.Favorites to stringResource(R.string.library_tab_favorites),
)

@Composable
private fun LibrarySearchScope.placeholder(): String = when (this) {
    LibrarySearchScope.SpotifyGlobal -> stringResource(R.string.library_search_placeholder_spotify)
    LibrarySearchScope.AppleMusicGlobal -> stringResource(R.string.library_search_placeholder_apple_music)
    LibrarySearchScope.CurrentLibrary -> stringResource(R.string.library_search_placeholder_library)
}

@Composable
private fun LibrarySearchScope.chipLabel(): String = when (this) {
    LibrarySearchScope.SpotifyGlobal -> stringResource(R.string.library_search_scope_spotify)
    LibrarySearchScope.AppleMusicGlobal -> stringResource(R.string.library_search_scope_apple_music)
    LibrarySearchScope.CurrentLibrary -> stringResource(R.string.library_search_scope_library)
}

// ── Tab content composables ─────────────────────────────────────────────

@Composable
private fun ArtistsTabContent(
    artists: List<Artist>?,
    gridState: LazyGridState,
    onArtistClick: (String) -> Unit,
    coverArtUrlBuilder: ((String) -> String)?,
    modifier: Modifier = Modifier,
) {
    if (artists == null) {
        TabLoadingState(modifier = modifier)
        return
    }
    if (artists.isEmpty()) {
        EmptyState(message = stringResource(R.string.library_empty_artists), modifier = modifier)
        return
    }
    KeepGridAnchorAcrossWidthChanges(gridState)
    // 3-column portrait grid on phones (one artist per row wasted most of
    // the width); adaptive at Medium+ — see libraryGridCells.
    LazyVerticalGrid(
        columns = libraryGridCells(),
        state = gridState,
        // Items dissolve (artwork) and fade (text) into the chips above
        // instead of being cut at the grid's top edge.
        modifier = modifier
            .fillMaxSize()
            .seamDissolveViewport(
                background = expressivePageSeamBackground(),
                remainingPx = { gridState.seamRemainingPx() },
            ) { gridState.seamScrolledPx() },
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 8.dp,
            end = 16.dp,
            bottom = floatingBottomGroupContentPadding(),
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        itemsIndexed(artists, key = { _, artist -> artist.id.toString() }) { index, artist ->
            val entranceProgress = rememberLibraryItemEntrance(
                key = artist.id,
                index = index,
                delayStepMillis = 24L,
            )
            ArtistGridItem(
                artist = artist,
                coverArtUrl = libraryCoverArtUrl(artist.coverArt, coverArtUrlBuilder),
                onClick = { onArtistClick(artist.id.toString()) },
                modifier = Modifier
                    .animateItem(
                        fadeInSpec = YoinMotion.effectsSpring(),
                        placementSpec = YoinMotion.spatialSpring(),
                        fadeOutSpec = YoinMotion.effectsSpring(),
                    )
                    .expressiveEntrance(
                        progress = entranceProgress,
                        initialOffsetY = 22.dp,
                        initialScale = 0.92f,
                    ),
            )
        }
    }
}

@Composable
private fun ArtistGridItem(
    artist: Artist,
    coverArtUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = modifier.noRippleClickable(interactionSource = interactionSource, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The shared artwork: its fallback icon on a failed load, and the retry
        // that brings the portrait back.
        ExpressiveMediaArtwork(
            model = coverArtUrl,
            contentDescription = artist.name,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .seamDissolve(),
            shape = CircleShape,
            fallbackIcon = YoinSymbols.Artist,
            interactionSource = interactionSource,
        )
        Spacer(modifier = Modifier.height(5.dp))
        Text(
            text = artist.name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().seamFade(),
        )
        val albumCount = artist.albumCount
        if (albumCount != null) {
            Text(
                text = pluralStringResource(
                    R.plurals.library_artist_grid_album_count,
                    albumCount,
                    albumCount,
                ),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().seamFade(),
            )
        }
    }
}

@Composable
private fun ArtistListItem(
    artist: Artist,
    coverArtUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = YoinContainerShapes.ListRow,
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
            ExpressiveMediaArtwork(
                model = coverArtUrl,
                contentDescription = artist.name,
                modifier = Modifier.size(48.dp).seamDissolve(),
                shape = CircleShape,
                fallbackIcon = YoinSymbols.Artist,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = artist.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).seamFade(),
            )
            val albumCount = artist.albumCount
            if (albumCount != null) {
                Spacer(modifier = Modifier.width(8.dp))
                ExpressiveMetaPill(
                    text = pluralStringResource(
                        R.plurals.library_artist_row_album_count,
                        albumCount,
                        albumCount,
                    ),
                    modifier = Modifier.seamFade(),
                )
            }
        }
    }
}

@Composable
private fun AlbumsTabContent(
    albums: List<Album>?,
    gridState: LazyGridState,
    onAlbumClick: (String) -> Unit,
    coverArtUrlBuilder: ((String) -> String)?,
    modifier: Modifier = Modifier,
) {
    if (albums == null) {
        TabLoadingState(modifier = modifier)
        return
    }
    if (albums.isEmpty()) {
        EmptyState(message = stringResource(R.string.library_empty_albums), modifier = modifier)
        return
    }
    KeepGridAnchorAcrossWidthChanges(gridState)
    LazyVerticalGrid(
        columns = libraryGridCells(),
        state = gridState,
        // Items dissolve (artwork) and fade (text) into the chips above
        // instead of being cut at the grid's top edge.
        modifier = modifier
            .fillMaxSize()
            .seamDissolveViewport(
                background = expressivePageSeamBackground(),
                remainingPx = { gridState.seamRemainingPx() },
            ) { gridState.seamScrolledPx() },
        // 16dp page margins to match the home feed; 12dp gutters, and a
        // tighter row gap now that the cards no longer reserve dead space.
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 8.dp,
            end = 16.dp,
            bottom = floatingBottomGroupContentPadding(),
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
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
                modifier = Modifier
                    .animateItem(
                        fadeInSpec = YoinMotion.effectsSpring(),
                        placementSpec = YoinMotion.spatialSpring(),
                        fadeOutSpec = YoinMotion.effectsSpring(),
                    )
                    .expressiveEntrance(
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
    // `extractBackdropColors = false` is intentional here. Enabling
    // palette extraction on a LazyVerticalGrid with many cards triggers
    // the same frame-drop issue that already plagues the Home Jump Back
    // In row (independent-loader Coil requests + unbounded parallel
    // Palette.generate() calls). Until that palette-extractor
    // performance is fixed in its own PR, Library stays tint-less to
    // keep the grid smooth. See `@palette-perf` follow-up.
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
    songs: List<Track>?,
    listState: LazyListState,
    activeSongId: String? = null,
    isPlaying: Boolean = false,
    playbackSignal: Float = 0f,
    notedSongIds: Set<String>,
    onSongClick: (Track) -> Unit,
    onAddSongToPlaylist: ((Track) -> Unit)?,
    onReshuffle: () -> Unit,
    canReshuffle: Boolean = true,
    coverArtUrlBuilder: ((String) -> String)?,
    modifier: Modifier = Modifier,
) {
    if (songs == null) {
        TabLoadingState(modifier = modifier)
        return
    }
    val scope = rememberCoroutineScope()
    Column(modifier = modifier.fillMaxSize()) {
        // The tab is a random 50-song sample, not the whole library — say so,
        // and offer a reshuffle (the only way to redraw; favorite toggles
        // deliberately never reshuffle the visible list).
        if (canReshuffle) {
            RandomMixHeader(
                onReshuffle = {
                    onReshuffle()
                    // A fresh sample shares nothing with the old one; restoring
                    // the retained mid-list offset into it would be meaningless.
                    scope.launch { listState.scrollToItem(0) }
                },
            )
        }
        if (songs.isEmpty()) {
            EmptyState(message = stringResource(R.string.library_empty_songs), modifier = Modifier.weight(1f))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .seamDissolveViewport(
                        background = expressivePageSeamBackground(),
                        remainingPx = { listState.seamRemainingPx() },
                    ) { listState.seamScrolledPx() },
                contentPadding = PaddingValues(
                    start = 0.dp,
                    top = 8.dp,
                    end = 0.dp,
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
                        onLongClick = onAddSongToPlaylist?.let { add -> { add(song) } },
                        isNowPlaying = isPlaying && song.id.toString() == activeSongId,
                        playbackSignal = playbackSignal,
                        extractBackdropColors = false,
                        hasNote = song.id.toString() in notedSongIds,
                        isUnavailable = song.isUnplayableAppleImport,
                        modifier = Modifier
                            .animateItem(
                                fadeInSpec = YoinMotion.effectsSpring(),
                                placementSpec = YoinMotion.spatialSpring(),
                                fadeOutSpec = YoinMotion.effectsSpring(),
                            )
                            .expressiveEntrance(entranceProgress),
                    )
                }
            }
        }
    }
}

@Composable
private fun RandomMixHeader(
    onReshuffle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberYoinHaptics()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.library_songs_random_mix),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = {
                haptics.performTick()
                onReshuffle()
            },
            modifier = Modifier.minimumTouchTarget(),
        ) {
            Icon(
                imageVector = YoinSymbols.Shuffle,
                contentDescription = stringResource(R.string.library_cd_reshuffle),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun PlaylistsTabContent(
    playlists: List<Playlist>?,
    listState: LazyListState,
    onPlaylistClick: (String) -> Unit,
    /** `null` hides the "+" FAB (provider without PLAYLISTS_WRITE). */
    onCreatePlaylist: ((name: String) -> Unit)?,
    coverArtUrlBuilder: ((String) -> String)?,
    modifier: Modifier = Modifier,
) {
    if (playlists == null) {
        TabLoadingState(modifier = modifier)
        return
    }
    var showCreateDialog by remember { mutableStateOf(false) }
    val haptics = rememberYoinHaptics()
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
                message = stringResource(R.string.library_empty_playlists),
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .seamDissolveViewport(
                        background = expressivePageSeamBackground(),
                        remainingPx = { listState.seamRemainingPx() },
                    ) { listState.seamScrolledPx() },
                contentPadding = PaddingValues(
                    start = 0.dp,
                    top = 8.dp,
                    end = 0.dp,
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
                        modifier = Modifier
                            .animateItem(
                                fadeInSpec = YoinMotion.effectsSpring(),
                                placementSpec = YoinMotion.spatialSpring(),
                                fadeOutSpec = YoinMotion.effectsSpring(),
                            )
                            .expressiveEntrance(entranceProgress),
                    )
                }
            }
        }

        // Bottom-right FAB. Instead of fully hiding on scroll, we use
        // Material's scroll-aware `ExtendedFloatingActionButton` pattern:
        // `expanded = fabVisible` keeps the button on screen the whole
        // time, but collapses the "New playlist" label away and slides
        // back to a bare icon when the user scrolls down. The label
        // re-expands the moment they scroll up or stop. It's the M3
        // canonical answer for "hide a FAB while browsing a list
        // without making it feel like the entry point vanished."
        if (canCreate) {
            ExtendedFloatingActionButton(
                onClick = {
                    haptics.performTick()
                    showCreateDialog = true
                },
                expanded = fabVisible,
                icon = {
                    Icon(
                        imageVector = YoinSymbols.Add,
                        contentDescription = null,
                    )
                },
                text = { Text(stringResource(R.string.library_playlist_fab)) },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = 16.dp,
                        bottom = floatingBottomGroupContentPadding(),
                    ),
            )
        }
    }

    if (showCreateDialog && onCreatePlaylist != null) {
        CreatePlaylistDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                haptics.performConfirm()
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
        title = { Text(stringResource(R.string.library_playlist_dialog_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.library_playlist_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.trim().isNotEmpty(),
            ) { Text(stringResource(R.string.library_playlist_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_playlist_cancel)) }
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
        shape = YoinContainerShapes.ListRow,
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
                modifier = Modifier.size(48.dp).seamDissolve(),
                shape = YoinArtworkShapes.Thumb,
                fallbackIcon = YoinSymbols.Playlist,
                // Full-bleed: the sub-1f fractions were placeholders for the
                // removed animated backdrop shape and just left ghost margins.
                fillFraction = 1f,
                tonalElevation = 0.dp,
                extractBackdropColors = false,
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f).seamFade()) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                PlaylistMeta(playlist)
            }
        }
    }
}

@Composable
private fun FavoritesTabContent(
    favorites: Starred?,
    listState: LazyListState,
    activeSongId: String? = null,
    isPlaying: Boolean = false,
    playbackSignal: Float = 0f,
    notedSongIds: Set<String>,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onSongClick: (Track) -> Unit,
    onFavoriteSongClick: (track: Track, queue: List<Track>, startIndex: Int) -> Unit,
    onAddSongToPlaylist: ((Track) -> Unit)?,
    coverArtUrlBuilder: ((String) -> String)?,
    modifier: Modifier = Modifier,
) {
    if (favorites == null) {
        TabLoadingState(modifier = modifier)
        return
    }
    val hasContent = favorites.artists.isNotEmpty() ||
        favorites.albums.isNotEmpty() ||
        favorites.tracks.isNotEmpty()

    if (!hasContent) {
        EmptyState(message = stringResource(R.string.library_empty_favorites), modifier = modifier)
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .seamDissolveViewport(
                background = expressivePageSeamBackground(),
                remainingPx = { listState.seamRemainingPx() },
            ) { listState.seamScrolledPx() },
        contentPadding = PaddingValues(
            start = 0.dp,
            top = 8.dp,
            end = 0.dp,
            bottom = floatingBottomGroupContentPadding(),
        ),
    ) {
        if (favorites.artists.isNotEmpty()) {
            item {
                SectionHeader(title = stringResource(R.string.library_favorites_section_artists))
            }
            itemsIndexed(
                items = favorites.artists,
                key = { _, artist -> "fav-artist-${artist.id}" },
            ) { index, artist ->
                val entranceProgress = rememberLibraryItemEntrance(
                    key = "fav-artist-${artist.id}",
                    index = index,
                    delayStepMillis = 24L,
                )
                ArtistListItem(
                    artist = artist,
                    coverArtUrl = libraryCoverArtUrl(artist.coverArt, coverArtUrlBuilder),
                    onClick = { onArtistClick(artist.id.toString()) },
                    modifier = Modifier
                        .animateItem(
                            fadeInSpec = YoinMotion.effectsSpring(),
                            placementSpec = YoinMotion.spatialSpring(),
                            fadeOutSpec = YoinMotion.effectsSpring(),
                        )
                        .expressiveEntrance(entranceProgress),
                )
            }
        }
        if (favorites.albums.isNotEmpty()) {
            item {
                SectionHeader(title = stringResource(R.string.library_favorites_section_albums))
            }
            itemsIndexed(
                items = favorites.albums,
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
                    modifier = Modifier
                        .animateItem(
                            fadeInSpec = YoinMotion.effectsSpring(),
                            placementSpec = YoinMotion.spatialSpring(),
                            fadeOutSpec = YoinMotion.effectsSpring(),
                        )
                        .expressiveEntrance(entranceProgress),
                )
            }
        }
        if (favorites.tracks.isNotEmpty()) {
            item {
                SectionHeader(title = stringResource(R.string.library_favorites_section_songs))
            }
            itemsIndexed(
                items = favorites.tracks,
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
                    onClick = {
                        onFavoriteSongClick(song, favorites.tracks, index)
                    },
                    onLongClick = onAddSongToPlaylist?.let { add -> { add(song) } },
                    isNowPlaying = isPlaying && song.id.toString() == activeSongId,
                    playbackSignal = playbackSignal,
                    extractBackdropColors = false,
                    hasNote = song.id.toString() in notedSongIds,
                    modifier = Modifier
                        .animateItem(
                            fadeInSpec = YoinMotion.effectsSpring(),
                            placementSpec = YoinMotion.spatialSpring(),
                            fadeOutSpec = YoinMotion.effectsSpring(),
                        )
                        .expressiveEntrance(entranceProgress),
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
        shape = YoinContainerShapes.ListRow,
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
                // 48dp aligns these covers with the artist avatars beside
                // them in the Favorites mixed list; full-bleed kills the
                // ghost margin left by the removed backdrop shape.
                modifier = Modifier.size(48.dp).seamDissolve(),
                shape = YoinArtworkShapes.Thumb,
                fallbackIcon = YoinSymbols.Album,
                fillFraction = 1f,
                tonalElevation = 0.dp,
                extractBackdropColors = false,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f).seamFade()) {
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

@Composable
private fun PlaylistMeta(playlist: Playlist, modifier: Modifier = Modifier) {
    val groups = buildList {
        playlist.owner?.takeIf { it.isNotBlank() }?.let { add(MetaGroup.Plain(it)) }
        playlist.songCount?.let { count ->
            add(
                MetaGroup.Stat(
                    count.toString(),
                    pluralStringResource(R.plurals.library_playlist_track_unit, count),
                ),
            )
        }
        playlist.durationSec?.takeIf { it > 0 }?.let { seconds ->
            val totalMinutes = seconds / 60
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            if (hours > 0) {
                add(MetaGroup.Stat(hours.toString(), stringResource(R.string.library_duration_hour_unit)))
            }
            if (minutes > 0 || hours == 0) {
                add(MetaGroup.Stat(minutes.toString(), stringResource(R.string.library_duration_minute_unit)))
            }
        }
    }
    if (groups.isEmpty()) {
        Text(
            text = stringResource(R.string.library_playlist_fallback),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
    } else {
        MetaLine(
            groups = groups,
            style = MaterialTheme.typography.bodySmall,
            modifier = modifier,
        )
    }
}

@Composable
private fun SearchResultsContent(
    searchResults: SearchResults?,
    isSearching: Boolean,
    searchError: UiText? = null,
    onRetrySearch: () -> Unit = {},
    activeSongId: String? = null,
    isPlaying: Boolean = false,
    playbackSignal: Float = 0f,
    notedSongIds: Set<String>,
    canAddToLibrary: Boolean = false,
    trackLibraryStates: Map<MediaId, LibraryMembership> = emptyMap(),
    workingLibraryTrackIds: Set<MediaId> = emptySet(),
    libraryActionFeedback: Map<MediaId, LibraryActionFeedback> = emptyMap(),
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    onSongClick: (Track) -> Unit,
    onAddSongToPlaylist: ((Track) -> Unit)?,
    onAddSongToLibrary: (Track) -> Unit = {},
    coverArtUrlBuilder: ((String) -> String)?,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberYoinHaptics()
    if (isSearching) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            YoinLoadingIndicator()
        }
        return
    }

    // A failed search is not "No results found" — surface the failure with a
    // way back in (same message/Retry language as the page-level Error state).
    if (searchError != null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = searchError.asString(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                TextButton(
                    onClick = {
                        haptics.performReject()
                        onRetrySearch()
                    },
                ) {
                    Text(stringResource(R.string.library_search_retry))
                }
            }
        }
        return
    }

    if (searchResults == null) return

    val hasResults = searchResults.artists.isNotEmpty() ||
        searchResults.albums.isNotEmpty() ||
        searchResults.playlists.isNotEmpty() ||
        searchResults.tracks.isNotEmpty()

    if (!hasResults) {
        EmptyState(message = stringResource(R.string.library_empty_search), modifier = modifier)
        return
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val windowInfo = LocalYoinWindowInfo.current
        val minimumAlbumWidth = when {
            windowInfo.isCompactHeight -> LibraryGridLandscapeMinSize
            else -> LibraryGridMinSize
        }
        // Match Library's grid: Compact keeps three columns, while larger
        // search surfaces use their actual available width and the same cells.
        val albumColumns = if (windowInfo.layoutMode == LayoutMode.Compact && !windowInfo.isCompactHeight) {
            3
        } else {
            ((maxWidth - 32.dp + 12.dp) / (minimumAlbumWidth + 12.dp)).toInt().coerceAtLeast(1)
        }
        // Results dissolve into the chips above like every other Library
        // list (seam-dissolve top); no floating bar lives in the search
        // surface, so there is no bottom field — just the nav-bar reserve.
        val listState = rememberLazyListState()
        val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .seamDissolveViewport { listState.seamScrolledPx() },
            contentPadding = PaddingValues(
                start = 0.dp,
                top = 8.dp,
                end = 0.dp,
                bottom = navBarBottom + SearchResultsBottomReserve,
            ),
        ) {
            if (searchResults.artists.isNotEmpty()) {
                item {
                    SectionHeader(title = stringResource(R.string.library_search_section_artists))
                }
                itemsIndexed(
                    items = searchResults.artists,
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
                        coverArtUrl = libraryCoverArtUrl(artist.coverArt, coverArtUrlBuilder),
                        onClick = { onArtistClick(artist.id.toString()) },
                        modifier = Modifier
                            .animateItem(
                                fadeInSpec = YoinMotion.effectsSpring(),
                                placementSpec = YoinMotion.spatialSpring(),
                                fadeOutSpec = YoinMotion.effectsSpring(),
                            )
                            .expressiveEntrance(entranceProgress),
                    )
                }
            }
            if (searchResults.albums.isNotEmpty()) {
                item {
                    SectionHeader(title = stringResource(R.string.library_search_section_albums))
                }
                // Keep cover sizes consistent with the main Library grid.
                items(
                    items = searchResults.albums.chunked(albumColumns),
                    key = { row -> row.joinToString("|") { "search-album-${it.id}" } },
                ) { row ->
                    Row(
                        modifier = Modifier
                            .animateItem(
                                fadeInSpec = YoinMotion.effectsSpring(),
                                placementSpec = YoinMotion.spatialSpring(),
                                fadeOutSpec = YoinMotion.effectsSpring(),
                            )
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        row.forEach { album ->
                            AlbumGridItem(
                                album = album,
                                onClick = { onAlbumClick(album.id.toString()) },
                                coverArtUrl =
                                    libraryCoverArtUrl(album.coverArt, coverArtUrlBuilder)
                                        ?: album.id.takeIf { it.provider == MediaId.PROVIDER_SUBSONIC }
                                            ?.rawId?.let { coverArtUrlBuilder?.invoke(it) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(albumColumns - row.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
            if (searchResults.playlists.isNotEmpty()) {
                item {
                    SectionHeader(title = stringResource(R.string.library_search_section_playlists))
                }
                itemsIndexed(
                    items = searchResults.playlists,
                    key = { _, playlist -> "search-playlist-${playlist.id}" },
                ) { index, playlist ->
                    val entranceProgress = rememberLibraryItemEntrance(
                        key = "search-playlist-${playlist.id}",
                        index = index,
                        delayStepMillis = 24L,
                        enabled = false,
                    )
                    PlaylistListItem(
                        playlist = playlist,
                        onClick = { onPlaylistClick(playlist.id.toString()) },
                        coverArtUrl = playlistBackdropArtUrl(playlist, coverArtUrlBuilder),
                        modifier = Modifier
                            .animateItem(
                                fadeInSpec = YoinMotion.effectsSpring(),
                                placementSpec = YoinMotion.spatialSpring(),
                                fadeOutSpec = YoinMotion.effectsSpring(),
                            )
                            .expressiveEntrance(entranceProgress),
                    )
                }
            }
            if (searchResults.tracks.isNotEmpty()) {
                item {
                    SectionHeader(title = stringResource(R.string.library_search_section_songs))
                }
                itemsIndexed(
                    items = searchResults.tracks,
                    key = { _, song -> "search-song-${song.id}" },
                ) { index, song ->
                    val entranceProgress = rememberLibraryItemEntrance(
                        key = "search-song-${song.id}",
                        index = index,
                        delayStepMillis = 20L,
                        enabled = false,
                    )
                    val membership = trackLibraryStates[song.id] ?: LibraryMembership.Unknown
                    val feedback = libraryActionFeedback[song.id]
                    Column(
                        modifier = Modifier
                            .animateItem(
                                fadeInSpec = YoinMotion.effectsSpring(),
                                placementSpec = YoinMotion.spatialSpring(),
                                fadeOutSpec = YoinMotion.effectsSpring(),
                            )
                            .expressiveEntrance(entranceProgress),
                    ) {
                        SongListItem(
                            title = song.title.orEmpty(),
                            artist = song.artist.orEmpty(),
                            album = song.album.orEmpty(),
                            durationSeconds = song.durationSec,
                            coverArtUrl = libraryCoverArtUrl(song.coverArt, coverArtUrlBuilder),
                            onClick = { onSongClick(song) },
                            onLongClick = onAddSongToPlaylist?.let { add -> { add(song) } },
                            isNowPlaying = isPlaying && song.id.toString() == activeSongId,
                            playbackSignal = playbackSignal,
                            extractBackdropColors = false,
                            hasNote = song.id.toString() in notedSongIds,
                            isUnavailable = song.isUnplayableAppleImport,
                            trailingContent = if (canAddToLibrary && !song.isUnplayableAppleImport) {
                                {
                                    TrackLibraryButton(
                                        membership = membership,
                                        onClick = { onAddSongToLibrary(song) },
                                        isWorking = song.id in workingLibraryTrackIds,
                                    )
                                }
                            } else {
                                null
                            },
                        )
                        AnimatedVisibility(
                            visible = canAddToLibrary && feedback != null,
                            enter = expandVertically(YoinMotion.spatialSpring()) +
                                YoinMotion.fadeIn(role = YoinMotionRole.Standard),
                            exit = shrinkVertically(YoinMotion.spatialSpring()) +
                                YoinMotion.fadeOut(role = YoinMotionRole.Standard),
                        ) {
                            val addedLabel = stringResource(R.string.library_search_added)
                            feedback?.let { action ->
                                val added = membership == LibraryMembership.Added
                                Text(
                                    text = if (added) addedLabel else action.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = when {
                                        added -> MaterialTheme.colorScheme.primary
                                        action.isError -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.padding(start = 76.dp, end = 16.dp, bottom = 10.dp),
                                )
                            }
                        }
                    }
                }
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
    return playlist.tracks.firstNotNullOfOrNull { song ->
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
        // Top-heavy: the header belongs to the group BELOW it, so it pulls
        // away from the previous list and sits close to its own.
        modifier = modifier
            .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp)
            .seamFade(),
    )
}

/**
 * A tab whose payload hasn't arrived yet (`null` in [LibraryUiState.Content]).
 * Distinct from [EmptyState]: loading must never read as "No X found".
 */
@Composable
private fun TabLoadingState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        YoinLoadingIndicator()
    }
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
            shape = YoinContainerShapes.Card,
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
            uiState = LibraryUiState.Error(UiText.Raw("Unable to connect to server")),
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

/** How the Library search field treats a query the view model publishes. */
internal enum class SearchFieldSync {
    /** Same text as the field: nothing to do. */
    InSync,

    /** A query the field itself sent earlier, arriving after newer keystrokes: ignore it. */
    LateEcho,

    /** Changed from outside (a clear, a profile switch): write it into the field. */
    ExternalReset,
}

internal fun searchFieldSync(vmQuery: String, fieldText: String, sent: Set<String>): SearchFieldSync = when (vmQuery) {
    fieldText -> SearchFieldSync.InSync
    in sent -> SearchFieldSync.LateEcho
    else -> SearchFieldSync.ExternalReset
}
