package com.gpo.yoin.ui.nowplaying

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.VerticalAlignCenter
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ButtonGroupScope
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.ui.component.edgeFade
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinShapeTokens

/**
 * Fullscreen detail surface opened from inside Now Playing. Rendered over
 * the compact content by an [androidx.compose.animation.AnimatedVisibility]
 * in `NowPlayingScreen` — it does NOT install a second nav destination
 * and does NOT register its own shared-element keys. Back priority is
 * handled by YoinNavHost.
 *
 * Three pages (Lyrics / About / Note) share selection with the compact
 * pager via [detailPage]. The Ask Gemini bar is rendered as a Box
 * overlay anchored to the bottom of the screen so that expanding it
 * visually covers the song title + artist sitting directly above.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NowPlayingFullscreenPane(
    state: NowPlayingUiState.Playing,
    detailPage: NowPlayingDetailPage,
    onDetailPageChange: (NowPlayingDetailPage) -> Unit,
    onBack: () -> Unit,
    aboutUiState: AboutUiState,
    askState: AskBarState,
    onAboutOpened: () -> Unit,
    onAskQuestion: (String) -> Unit,
    onAskBarFocused: () -> Unit,
    onAskBarCollapseRequested: () -> Unit,
    onDismissAskError: () -> Unit,
    onRetryCanonical: () -> Unit,
    notes: List<SongNote>,
    onSaveNote: (String) -> Unit,
    onDeleteNote: (String) -> Unit,
    onToggleFavorite: () -> Unit,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    onSeekToMs: (Long) -> Unit,
    lyricsSearchState: LyricsSearchState,
    onOpenLyricsSearch: () -> Unit,
    onLyricsSearchQueryChange: (String) -> Unit,
    onSearchLyrics: (String) -> Unit,
    onApplyLyricsSearchResult: (LyricsSearchResultUi) -> Unit,
    onDismissLyricsSearch: () -> Unit,
    onTranslateLyrics: () -> Unit,
    onApplyLyrics: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer
    val background = MaterialTheme.colorScheme.background
    var lyricsAutoScroll by remember(state.songId) { mutableStateOf(true) }
    var lyricsRecenterTick by remember(state.songId) { mutableIntStateOf(0) }
    val hasSyncedLyrics = remember(state.lyrics) { state.lyrics.any { it.startMs != null } }
    var showApplyDialog by remember(state.songId) { mutableStateOf(false) }

    ProvideYoinMotionRole(role = YoinMotionRole.Expressive) {
        // Gradient paints edge-to-edge (under status bar + nav bar).
        // Insets are consumed at the content layer, not the background:
        // top via statusBarsPadding, bottom via each component's own
        // navigationBarsPadding / imePadding — so the Ask bar can lift
        // above nav bar + keyboard itself without fighting a parent Box.
        // Horizontal padding is applied per-child rather than on the outer
        // Box so the pager can swipe edge-to-edge. Each page adds its own
        // 24dp inset so content stays aligned with the top bar / tabs /
        // hero while the swipe itself flows off-screen.
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(surfaceContainer, background)))
                .statusBarsPadding(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
                horizontalAlignment = Alignment.Start,
            ) {
                TopBar(
                    onBack = onBack,
                    state = state,
                    onAlbumClick = onAlbumClick,
                    onArtistClick = onArtistClick,
                    onPlaylistClick = onPlaylistClick,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )

                Spacer(modifier = Modifier.height(12.dp))

                FullscreenTabGroup(
                    selected = detailPage,
                    onSelect = onDetailPageChange,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )

                Spacer(modifier = Modifier.height(16.dp))

                val pagerState = rememberPagerState(
                    initialPage = detailPage.ordinal,
                    pageCount = { 3 },
                )
                LaunchedEffect(detailPage) {
                    if (pagerState.currentPage != detailPage.ordinal) {
                        pagerState.animateScrollToPage(detailPage.ordinal)
                    }
                }
                LaunchedEffect(pagerState.currentPage) {
                    val page = NowPlayingDetailPage.entries[pagerState.currentPage]
                    if (page != detailPage) onDetailPageChange(page)
                    if (page == NowPlayingDetailPage.About) onAboutOpened()
                }

                // Pager host: edge-to-edge, with a horizontal fade so
                // content softens into the surface at the swipe bounds.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .edgeFade(start = 24.dp, end = 24.dp),
                ) {
                    HorizontalPager(
                        state = pagerState,
                        beyondViewportPageCount = 1,
                        modifier = Modifier.fillMaxSize(),
                    ) { page ->
                        val pageModifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp)
                        when (NowPlayingDetailPage.entries[page]) {
                            NowPlayingDetailPage.Lyrics -> LyricsFullscreenPane(
                                lyrics = state.lyrics,
                                positionMs = state.positionMs,
                                loading = state.lyricsLoading,
                                showTranslation = state.showLyricsTranslation,
                                autoScrollEnabled = lyricsAutoScroll,
                                recenterRequestKey = lyricsRecenterTick,
                                onUserScroll = { lyricsAutoScroll = false },
                                onSeekToMs = { positionMs ->
                                    lyricsAutoScroll = true
                                    lyricsRecenterTick += 1
                                    onSeekToMs(positionMs)
                                },
                                modifier = pageModifier,
                            )
                            NowPlayingDetailPage.About -> AboutFullscreenPane(
                                aboutUiState = aboutUiState,
                                onRetryCanonical = onRetryCanonical,
                                modifier = pageModifier,
                            )
                            NowPlayingDetailPage.Note -> NoteFullscreenPane(
                                notes = notes,
                                onSave = onSaveNote,
                                onDelete = onDeleteNote,
                                autoFocusComposer = detailPage == NowPlayingDetailPage.Note,
                                modifier = pageModifier,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Bottom title + artist. Always in the layout; when the
                // Ask bar expands it renders on top of this via the
                // sibling overlay below.
                BottomHero(
                    title = state.songTitle,
                    artist = state.artist,
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 12.dp),
                )

                // Reserve room for the floating bottom bar (56dp idle +
                // 8dp bottom padding + breathing). About uses this slot for
                // Ask Gemini; Lyrics uses it for search / translate / apply /
                // recenter. Note gets the full bottom area.
                if (detailPage == NowPlayingDetailPage.About ||
                    detailPage == NowPlayingDetailPage.Lyrics
                ) {
                    Spacer(modifier = Modifier.height(72.dp))
                }
            }

            // Ask Gemini bar — only rendered on the About page. Anchored
            // to the bottom so expanding upward covers the hero above.
            if (detailPage == NowPlayingDetailPage.About) {
                AskGeminiBar(
                    askState = askState,
                    onSubmit = onAskQuestion,
                    onFocus = onAskBarFocused,
                    onCollapseRequest = onAskBarCollapseRequested,
                    onDismissError = onDismissAskError,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        // The outer Box only applies statusBarsPadding —
                        // the Ask bar owns the bottom inset itself so it
                        // can both rise with the keyboard (imePadding)
                        // AND stay above the nav bar when the keyboard
                        // is hidden.
                        .imePadding()
                        .navigationBarsPadding()
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 8.dp),
                    )
            }

            if (detailPage == NowPlayingDetailPage.Lyrics) {
                LyricsActionBar(
                    actionInFlight = state.lyricsActionInFlight,
                    canTranslate = state.lyrics.isNotEmpty(),
                    canRecenter = !lyricsAutoScroll && hasSyncedLyrics,
                    onSearchClick = onOpenLyricsSearch,
                    onTranslateClick = onTranslateLyrics,
                    onApplyClick = { showApplyDialog = true },
                    onRecenterClick = {
                        lyricsAutoScroll = true
                        lyricsRecenterTick += 1
                    },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 8.dp),
                )
            }

            if (lyricsSearchState.isOpen) {
                LyricsSearchSheet(
                    state = lyricsSearchState,
                    onQueryChange = onLyricsSearchQueryChange,
                    onSearch = onSearchLyrics,
                    onSelect = onApplyLyricsSearchResult,
                    onDismiss = onDismissLyricsSearch,
                )
            }

            if (showApplyDialog) {
                LyricsApplyDialog(
                    initialText = remember(state.songId, state.lyrics) {
                        state.lyrics.toEditableLyricsText()
                    },
                    onDismiss = { showApplyDialog = false },
                    onApply = { rawLyrics ->
                        showApplyDialog = false
                        onApplyLyrics(rawLyrics)
                    },
                )
            }
        }
    }
}

@Composable
private fun TopBar(
    onBack: () -> Unit,
    state: NowPlayingUiState.Playing,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Close fullscreen",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(modifier = Modifier.size(4.dp))
        Column {
            val kindLabel: String
            val nameLabel: String
            val routeAction: (() -> Unit)?
            when (val ctx = state.activityContext) {
                is com.gpo.yoin.data.repository.ActivityContext.Album -> {
                    kindLabel = "PLAYING FROM ALBUM"
                    nameLabel = ctx.albumName
                    routeAction = { onAlbumClick(ctx.albumId) }
                }
                is com.gpo.yoin.data.repository.ActivityContext.Playlist -> {
                    kindLabel = "PLAYING FROM PLAYLIST"
                    nameLabel = ctx.playlistName
                    routeAction = { onPlaylistClick(ctx.playlistId) }
                }
                is com.gpo.yoin.data.repository.ActivityContext.Artist,
                is com.gpo.yoin.data.repository.ActivityContext.LikedSongs,
                com.gpo.yoin.data.repository.ActivityContext.None,
                -> {
                    kindLabel = ""
                    nameLabel = "NOW PLAYING"
                    routeAction = null
                }
            }
            if (kindLabel.isNotBlank()) {
                Text(
                    text = kindLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val nameModifier = if (routeAction != null) {
                Modifier.clickable { routeAction() }
            } else {
                Modifier
            }
            if (nameLabel.isNotBlank()) {
                Text(
                    text = nameLabel,
                    style = if (kindLabel.isBlank()) {
                        MaterialTheme.typography.labelSmall
                    } else {
                        MaterialTheme.typography.labelMedium
                    },
                    color = if (kindLabel.isBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = nameModifier,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FullscreenTabGroup(
    selected: NowPlayingDetailPage,
    onSelect: (NowPlayingDetailPage) -> Unit,
    modifier: Modifier = Modifier,
) {
    ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
        val lyricsInteraction = remember { MutableInteractionSource() }
        val aboutInteraction = remember { MutableInteractionSource() }
        val noteInteraction = remember { MutableInteractionSource() }

        ButtonGroup(
            overflowIndicator = { _ -> },
            modifier = modifier
                .fillMaxWidth()
                .height(52.dp),
            expandedRatio = ButtonGroupDefaults.ExpandedRatio,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Three buttons each take an equal 1f weight so the group
            // fills the available width. `animateWidth` still animates
            // size changes on press within the shared slot.
            customItem(
                buttonGroupContent = {
                    TabButton(
                        label = "Lyrics",
                        isSelected = selected == NowPlayingDetailPage.Lyrics,
                        interactionSource = lyricsInteraction,
                        onClick = { onSelect(NowPlayingDetailPage.Lyrics) },
                        modifier = Modifier.weight(1f),
                    )
                },
                menuContent = { _ -> },
            )
            customItem(
                buttonGroupContent = {
                    TabButton(
                        label = "About",
                        isSelected = selected == NowPlayingDetailPage.About,
                        interactionSource = aboutInteraction,
                        onClick = { onSelect(NowPlayingDetailPage.About) },
                        modifier = Modifier.weight(1f),
                    )
                },
                menuContent = { _ -> },
            )
            customItem(
                buttonGroupContent = {
                    TabButton(
                        label = "Note",
                        isSelected = selected == NowPlayingDetailPage.Note,
                        interactionSource = noteInteraction,
                        onClick = { onSelect(NowPlayingDetailPage.Note) },
                        modifier = Modifier.weight(1f),
                    )
                },
                menuContent = { _ -> },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ButtonGroupScope.TabButton(
    label: String,
    isSelected: Boolean,
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.primaryContainer
        },
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "tabContainer",
    )
    val content by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onPrimaryContainer
        },
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "tabContent",
    )
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxHeight()
            .animateWidth(interactionSource),
        interactionSource = interactionSource,
        shape = YoinShapeTokens.Full,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = container,
            contentColor = content,
        ),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            ),
        )
    }
}

@Composable
private fun BottomHero(
    title: String,
    artist: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1500),
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = artist,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = MaterialTheme.typography.titleMedium.fontSize * 0.9f,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LyricsActionBar(
    actionInFlight: LyricsAction?,
    canTranslate: Boolean,
    canRecenter: Boolean,
    onSearchClick: () -> Unit,
    onTranslateClick: () -> Unit,
    onApplyClick: () -> Unit,
    onRecenterClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val searchInteraction = remember { MutableInteractionSource() }
    val translateInteraction = remember { MutableInteractionSource() }
    val applyInteraction = remember { MutableInteractionSource() }
    val recenterInteraction = remember { MutableInteractionSource() }

    ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
        ButtonGroup(
            overflowIndicator = { _ -> },
            modifier = modifier.height(52.dp),
            expandedRatio = ButtonGroupDefaults.ExpandedRatio,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            customItem(
                buttonGroupContent = {
                    LyricsActionIcon(
                        icon = Icons.Rounded.Search,
                        contentDescription = "Search lyrics",
                        interactionSource = searchInteraction,
                        enabled = actionInFlight == null,
                        onClick = onSearchClick,
                    )
                },
                menuContent = { _ -> },
            )
            customItem(
                buttonGroupContent = {
                    LyricsActionIcon(
                        icon = Icons.Rounded.Translate,
                        contentDescription = "Translate lyrics",
                        interactionSource = translateInteraction,
                        enabled = actionInFlight == null && canTranslate,
                        onClick = onTranslateClick,
                    )
                },
                menuContent = { _ -> },
            )
            customItem(
                buttonGroupContent = {
                    LyricsActionIcon(
                        icon = Icons.Rounded.Check,
                        contentDescription = "Apply lyrics",
                        interactionSource = applyInteraction,
                        enabled = actionInFlight == null,
                        onClick = onApplyClick,
                    )
                },
                menuContent = { _ -> },
            )
            customItem(
                buttonGroupContent = {
                    LyricsActionIcon(
                        icon = Icons.Rounded.VerticalAlignCenter,
                        contentDescription = "Return to current line",
                        interactionSource = recenterInteraction,
                        enabled = actionInFlight == null && canRecenter,
                        onClick = onRecenterClick,
                    )
                },
                menuContent = { _ -> },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ButtonGroupScope.LyricsActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    interactionSource: MutableInteractionSource,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(width = 52.dp, height = 52.dp)
            .animateWidth(interactionSource),
        interactionSource = interactionSource,
        shape = RoundedCornerShape(16.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LyricsSearchSheet(
    state: LyricsSearchState,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onSelect: (LyricsSearchResultUi) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentWindowInsets = {
            BottomSheetDefaults.modalWindowInsets.only(
                WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
            )
        },
        modifier = modifier,
    ) {
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, top = 8.dp, end = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Search lyrics",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close lyrics search",
                    )
                }
            }

            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                singleLine = true,
                placeholder = { Text("Song or artist") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                    )
                },
                trailingIcon = {
                    if (state.loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(onClick = { onSearch(state.query) }) {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = "Search lyrics",
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(state.query) }),
            )

            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = 8.dp,
                    end = 16.dp,
                    bottom = 16.dp + navBottom,
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (state.errorMessage != null) {
                    item(key = "error") {
                        Text(
                            text = state.errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }

                when {
                    state.providers.isEmpty() && state.loading -> {
                        item(key = "loading") {
                            LyricsProviderStatusRow(
                                text = "Searching providers...",
                                loading = true,
                            )
                        }
                    }
                    state.providers.isEmpty() &&
                        !state.loading &&
                        state.query.isNotBlank() &&
                        state.errorMessage == null -> {
                        item(key = "empty") {
                            Text(
                                text = "No lyrics found.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 18.dp),
                            )
                        }
                    }
                    else -> {
                        state.providers.forEachIndexed { providerIndex, provider ->
                            item(key = "header:${provider.providerName}") {
                                LyricsProviderHeader(providerName = provider.providerName)
                            }
                            when {
                                provider.errorMessage != null -> {
                                    item(key = "error:${provider.providerName}") {
                                        LyricsProviderStatusRow(
                                            text = provider.errorMessage,
                                            error = true,
                                        )
                                    }
                                }
                                state.loading && provider.results.isEmpty() -> {
                                    item(key = "loading:${provider.providerName}") {
                                        LyricsProviderStatusRow(
                                            text = "Searching...",
                                            loading = true,
                                        )
                                    }
                                }
                                provider.results.isEmpty() -> {
                                    item(key = "empty:${provider.providerName}") {
                                        LyricsProviderStatusRow(text = "No results")
                                    }
                                }
                                else -> {
                                    items(
                                        items = provider.results,
                                        key = LyricsSearchResultUi::stableKey,
                                    ) { result ->
                                        LyricsSearchResultRow(
                                            result = result,
                                            applying = state.applyingCandidateKey == result.stableKey,
                                            enabled = state.applyingCandidateKey == null,
                                            onClick = { onSelect(result) },
                                        )
                                    }
                                }
                            }
                            if (providerIndex != state.providers.lastIndex) {
                                item(key = "divider:${provider.providerName}") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(1.dp)
                                            .background(
                                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                            ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricsProviderHeader(
    providerName: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = providerName.toLyricsProviderLabel(),
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(horizontal = 8.dp, vertical = 8.dp),
    )
}

@Composable
private fun LyricsProviderStatusRow(
    text: String,
    loading: Boolean = false,
    error: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )
            Spacer(modifier = Modifier.size(10.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun LyricsSearchResultRow(
    result: LyricsSearchResultUi,
    applying: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = result.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = result.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (applying) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LyricsApplyDialog(
    initialText: String,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit,
) {
    var draft by remember(initialText) { mutableStateOf(initialText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apply lyrics") },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp, max = 360.dp),
                placeholder = { Text("Lyrics") },
                minLines = 8,
            )
        },
        confirmButton = {
            TextButton(
                enabled = draft.isNotBlank(),
                onClick = { onApply(draft) },
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

private fun List<LyricLine>.toEditableLyricsText(): String {
    if (isEmpty()) return ""
    return joinToString("\n") { line ->
        val start = line.startMs
        if (start == null) {
            line.text
        } else {
            "${start.toLrcTimestamp()}${line.text}"
        }
    }
}

private fun Long.toLrcTimestamp(): String {
    val totalSeconds = this.coerceAtLeast(0L) / 1_000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    val hundredths = (this.coerceAtLeast(0L) % 1_000L) / 10L
    return "[%02d:%02d.%02d]".format(minutes, seconds, hundredths)
}

private fun String.toLyricsProviderLabel(): String = when (this) {
    "qq" -> "QQ Music"
    "netease" -> "NetEase"
    "lrclib" -> "LRCLIB"
    else -> this
}
