package com.gpo.yoin.ui.detail

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.gpo.yoin.R
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.common.asString
import com.gpo.yoin.ui.component.BarExtraAction
import com.gpo.yoin.ui.component.DetailErrorState
import com.gpo.yoin.ui.component.ExpressiveMediaArtwork
import com.gpo.yoin.ui.component.ExpressivePageBackground
import com.gpo.yoin.ui.component.MetaGroup
import com.gpo.yoin.ui.component.YoinDropdownMenu
import com.gpo.yoin.ui.component.YoinDropdownMenuItem
import com.gpo.yoin.ui.component.formatTotalDuration
import com.gpo.yoin.ui.component.formatTrackDuration
import com.gpo.yoin.ui.component.rememberStagedReveal
import com.gpo.yoin.ui.component.seamDissolve
import com.gpo.yoin.ui.component.seamDissolveViewport
import com.gpo.yoin.ui.component.seamFade
import com.gpo.yoin.ui.component.seamRemainingPx
import com.gpo.yoin.ui.component.seamScrolledPx
import com.gpo.yoin.ui.component.expressivePageSeamBackground
import com.gpo.yoin.ui.component.stagedBeat
import com.gpo.yoin.ui.component.yoinPageContentWidth
import com.gpo.yoin.ui.experience.LayoutMode
import com.gpo.yoin.ui.experience.LocalYoinWindowInfo
import com.gpo.yoin.ui.experience.ProvidePreviewWindow
import com.gpo.yoin.ui.experience.rememberRevealState
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.navigation.YoinSection
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinArtworkShapes
import com.gpo.yoin.ui.theme.YoinContainerShapes
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinTheme
import com.gpo.yoin.ui.theme.rememberCoverColorScheme
import com.gpo.yoin.ui.theme.withTabularFigures

@Composable
fun PlaylistDetailScreen(
    uiState: PlaylistDetailUiState,
    onBackClick: () -> Unit,
    // The actual window exit, invoked by the back-collapse handler AFTER its
    // commit motion. Defaults to onBackClick so previews/tests keep the old
    // direct-exit behaviour; the Activity passes a dispatcher-routed
    // onBackClick + a finish()-ing onLeavePage.
    onLeavePage: () -> Unit = onBackClick,
    onPlayAllClick: () -> Unit,
    onShufflePlay: () -> Unit = {},
    onSongClick: (songId: String) -> Unit,
    onRetry: () -> Unit,
    onRename: (name: String) -> Unit = {},
    onDelete: () -> Unit = {},
    onShare: () -> Unit = {},
    isPlaying: Boolean = false,
    playbackSignal: Float = 0f,
    onOpenNowPlaying: () -> Unit = {},
    nowPlayingOpen: Boolean = false,

    // True when a nav-pose bar sits beneath this window: predictive back
    // scrubs the bar toward nav chrome (matching the reveal underneath), and
    // without a shell hand-off the bar morphs nav→detail in-window on reveal.
    morphBarOnBack: Boolean = false,
    // FullChoreography only: the back pose is bridged to the shell (its content
    // plays the entering side, its bar morphs in lockstep). Plain pushes keep
    // the morph inside this window.
    bridgeBackToShell: Boolean = morphBarOnBack,
    // Shell tab at launch time (the back scrub's revealed selection) and
    // whether the launch used the bar hand-off window animation (delays the
    // content slide-in to match the transparent hold).
    navSection: YoinSection = YoinSection.HOME,
    enterBarHandoff: Boolean = false,
    // NP-origin: the back reveal is the expanded player (no bar there) — the
    // bar rides the gesture down off-screen instead of morphing.
    barExitsOnBack: Boolean = false,
    miniPlayerState: DetailMiniPlayerState? = null,
    playbackProgress: Float = 0f,
    modifier: Modifier = Modifier,
) {
    // Dialog states lifted here so they survive child recomposition (e.g.
    // after a rename refreshes the Content).
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val content = uiState as? PlaylistDetailUiState.Content

    // Header title/subtitle + backdrop colours seeded from the cover (same MCU
    // path as the Album & Artist pages); animated so the resolve doesn't pop.
    val coverScheme = rememberCoverColorScheme(content?.coverArtUrl)
    val headerScheme = coverScheme ?: MaterialTheme.colorScheme
    val titleColor by animateColorAsState(headerScheme.primary, YoinMotion.effectsSpring(), label = "playlistTitleColor")
    // Stacked-V layers, back to front.
    val stackBack by animateColorAsState(headerScheme.tertiary, YoinMotion.effectsSpring(), label = "playlistStackBack")
    val stackMiddle by animateColorAsState(headerScheme.secondary, YoinMotion.effectsSpring(), label = "playlistStackMiddle")
    val stackFront by animateColorAsState(headerScheme.primary, YoinMotion.effectsSpring(), label = "playlistStackFront")
    val stackColors = listOf(stackBack, stackMiddle, stackFront)

    val accentColor = rememberDetailPageAccent(content?.coverArtUrl)
    ProvideYoinMotionRole(role = YoinMotionRole.Expressive) {
        // In-window predictive back (AOSP cross-activity math): the whole page
        // — background included — collapses as one card over the LIVE window
        // beneath (the Activity turns translucent for the gesture); the bar is a
        // sibling on top and never transforms. The pulled-up track list is
        // in-page state, not a back stop.
        val backCollapse = rememberDetailBackCollapse(
            onBack = onLeavePage,
            bridgeToShell = bridgeBackToShell,
        )
        // The header arrow leaves THIS page through its commit choreography —
        // never via the window's back dispatcher, where an open Now Playing
        // side panel ranks first and would take the tap. In the shell's
        // detail column it pops the column's stack, as before.
        @Suppress("NAME_SHADOWING")
        val onBackClick: () -> Unit = if (LocalDetailHostMode.current == DetailHostMode.Pane) {
            onBackClick
        } else {
            backCollapse::requestBack
        }
        val enterIntro = rememberDetailEnterIntro(
            barHandoff = enterBarHandoff && bridgeBackToShell,
            visualReady = uiState !is PlaylistDetailUiState.Loading,
            back = backCollapse,
        )
        Box(
            modifier = modifier.then(
                rememberDetailMotionFrameRateModifier(backCollapse, enterIntro),
            ),
        ) {
            if (enterIntro.pageVisible) {
                DetailEnterPageMountEffect(enterIntro)
                ExpressivePageBackground(
                    accentColor = accentColor,
                    isPlaying = isPlaying,
                    playbackSignal = playbackSignal,
                    modifier = Modifier
                        .fillMaxSize()
                        .detailBackCollapseTransform(backCollapse)
                        .detailEnterIntroTransform(enterIntro),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .detailChromeBand(),
                    ) {
                        // The header persists across Loading/Error/Content (it
                        // carries the back affordance); only the body crossfades.
                        PlaylistTopHeader(
                            playlistName = content?.playlistName.orEmpty(),
                            owner = content?.owner,
                            titleColor = titleColor,
                            canWrite = content?.canWrite == true,
                            onBackClick = onBackClick,
                            onRename = { showRenameDialog = true },
                            onDelete = { showDeleteConfirm = true },
                        )
                        AnimatedContent(
                            targetState = uiState,
                            transitionSpec = {
                                YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                                    YoinMotion.fadeOut(role = YoinMotionRole.Standard)
                            },
                            // Keyed on the state class: Content→Content refreshes
                            // (rename, remove-track) update in place, no re-fade.
                            contentKey = { it::class },
                            label = "playlistDetailState",
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        ) { state ->
                            when (state) {
                                is PlaylistDetailUiState.Loading -> {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .navigationBarsPadding(),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        DetailLoadingIndicator(enterIntro)
                                    }
                                }

                                // No onBack: the persistent header above already
                                // carries the back affordance on this page.
                                is PlaylistDetailUiState.Error ->
                                    DetailErrorState(
                                        message = state.message.asString(),
                                        onRetry = onRetry,
                                    )

                                is PlaylistDetailUiState.Content ->
                                    PlaylistDetailContent(
                                        content = state,
                                        stackColors = stackColors,
                                        onSongClick = onSongClick,
                                    )
                            }
                        }
                    }
                }
            }

            // Persistent bottom bar — rendered in ALL states (the bar never
            // waits for page data; the shell's morph is already playing when
            // this window fades in). Play rides the cover-seeded primary; on an
            // empty playlist it simply no-ops.
            DetailBottomBar(
                playContainer = headerScheme.primary,
                playContent = headerScheme.onPrimary,
                onPlay = onPlayAllClick,
                onShuffle = onShufflePlay,
                onOpenNowPlaying = onOpenNowPlaying,
                miniPlayer = miniPlayerState,
                playbackProgress = playbackProgress,
                nowPlayingOpen = nowPlayingOpen,

                interactionsEnabled = enterIntro.pageVisible,
                enterChromeProgress = rememberDetailBarEnterProgress(
                    followShell = enterBarHandoff && bridgeBackToShell,
                    back = backCollapse,
                    inWindowMorph = morphBarOnBack && !bridgeBackToShell,
                    intro = enterIntro,
                ),
                backMorphProgress = if (morphBarOnBack) {
                    { backCollapse.progress }
                } else {
                    { 0f }
                },
                navSection = navSection,
                backExitProgress = if (barExitsOnBack) {
                    { detailBarExitProgress(enterIntro, backCollapse) }
                } else {
                    { 0f }
                },
                promotable = listOf(
                    BarExtraAction(
                        icon = YoinSymbols.Share,
                        label = stringResource(R.string.detail_playlist_share),
                        onClick = onShare,
                    ),
                ),
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    if (showRenameDialog && content != null) {
        RenamePlaylistDialog(
            initialName = content.playlistName,
            onDismiss = { showRenameDialog = false },
            onConfirm = { newName ->
                showRenameDialog = false
                onRename(newName)
            },
        )
    }

    if (showDeleteConfirm && content != null) {
        val haptics = rememberYoinHaptics()
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.detail_playlist_delete_title)) },
            text = {
                // Spotify implements delete as unfollow-own, but the user-
                // visible effect is the same: the playlist disappears. The
                // message stays product-neutral.
                Text(stringResource(R.string.detail_playlist_delete_body, content.playlistName))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptics.performReject()
                        showDeleteConfirm = false
                        onDelete()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text(stringResource(R.string.detail_playlist_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.detail_playlist_cancel_delete))
                }
            },
        )
    }
}

/**
 * The Album page's compact header, in playlist terms: back, title, owner, and
 * Playlist, plus the Rename/Delete overflow when the current
 * profile can write this playlist (Spotify followed-but-not-owned playlists
 * hide it entirely rather than showing disabled items).
 */
@Composable
private fun PlaylistTopHeader(
    playlistName: String,
    owner: String?,
    titleColor: Color,
    canWrite: Boolean,
    onBackClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberYoinHaptics()
    var showOverflow by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DetailBackButton(onClick = onBackClick)
        // Air between the button's touch halo and the title cluster (Album parity).
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlistName,
                style = MaterialTheme.typography.headlineSmall,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            DetailMetaLine(
                groups = buildList {
                    owner?.takeIf { it.isNotBlank() }?.let { add(MetaGroup.Plain(it)) }
                    add(MetaGroup.Kind(stringResource(R.string.detail_playlist_kind), accent = true))
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (canWrite) {
            Box {
                IconButton(
                    onClick = {
                        haptics.performTick()
                        showOverflow = true
                    },
                ) {
                    Icon(
                        imageVector = YoinSymbols.MoreVertical,
                        contentDescription = stringResource(R.string.detail_playlist_cd_more),
                    )
                }
                YoinDropdownMenu(
                    expanded = showOverflow,
                    onDismissRequest = { showOverflow = false },
                ) {
                    YoinDropdownMenuItem(
                        text = stringResource(R.string.detail_playlist_rename),
                        leadingIcon = { Icon(YoinSymbols.Edit, contentDescription = null) },
                        onClick = {
                            showOverflow = false
                            onRename()
                        },
                    )
                    YoinDropdownMenuItem(
                        text = stringResource(R.string.detail_playlist_delete_menu),
                        leadingIcon = { Icon(YoinSymbols.Delete, contentDescription = null) },
                        onClick = {
                            showOverflow = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RenamePlaylistDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.detail_playlist_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.detail_playlist_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.trim().isNotEmpty() && name.trim() != initialName,
            ) { Text(stringResource(R.string.detail_playlist_rename_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.detail_playlist_cancel_rename)) }
        },
    )
}

/**
 * Compact: the Album page's two-state pull-up — hero (stacked-V backdrop,
 * cover, meta, flowing titles) ⇄ track list, the cover docking to a straight
 * full-bleed band (or a capsule for short playlists). >= Medium (Tabletop
 * stays Compact): one plain scrolling list with a hero row on top, like the
 * Album's Medium overview.
 */
@Composable
private fun PlaylistDetailContent(
    content: PlaylistDetailUiState.Content,
    stackColors: List<Color>,
    onSongClick: (songId: String) -> Unit,
) {
    // Same breakpoints as the Album (断点交接 §5): height first — a landscape
    // handset turns the hero sideways and keeps the pull-up — then width.
    val windowInfo = LocalYoinWindowInfo.current
    val layoutMode = windowInfo.layoutMode
    when {
        windowInfo.isCompactHeight -> PlaylistPullUpOverview(
            content = content,
            stackColors = stackColors,
            onSongClick = onSongClick,
            landscape = true,
        )
        layoutMode == LayoutMode.Wide -> PlaylistWideOverview(
            content = content,
            stackColors = stackColors,
            onSongClick = onSongClick,
        )
        layoutMode != LayoutMode.Compact && layoutMode != LayoutMode.Tabletop ->
            PlaylistMediumOverview(content = content, onSongClick = onSongClick)
        else -> PlaylistPullUpOverview(
            content = content,
            stackColors = stackColors,
            onSongClick = onSongClick,
        )
    }
}

// Wide full window: the Album's identity column (cover 360 on its stacked V,
// Length | Owner) beside the full list (max 800).
@Composable
private fun PlaylistWideOverview(
    content: PlaylistDetailUiState.Content,
    stackColors: List<Color>,
    onSongClick: (songId: String) -> Unit,
) {
    val listState = rememberLazyListState()
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 40.dp, end = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        Column(
            modifier = Modifier
                .width(PlaylistWideIdentityWidth)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(top = 24.dp, bottom = 120.dp),
        ) {
            Box(
                modifier = Modifier.size(PlaylistWideCoverSide + 40.dp),
                contentAlignment = Alignment.Center,
            ) {
                PlaylistStackBackground(
                    colors = stackColors,
                    lineColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                    coverSide = PlaylistWideCoverSide,
                    modifier = Modifier.size(PlaylistWideCoverSide),
                )
                ExpressiveMediaArtwork(
                    model = content.coverArtUrl,
                    contentDescription = content.playlistName,
                    modifier = Modifier.size(PlaylistWideCoverSide),
                    shape = YoinArtworkShapes.Hero,
                    fallbackIcon = YoinSymbols.Playlist,
                    border = null,
                    shadowElevation = 0.dp,
                    tonalElevation = 3.dp,
                    requestSizePx = 900,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            PlaylistHeroMeta(content = content, modifier = Modifier.width(PlaylistWideCoverSide))
        }
        PlaylistTrackList(
            content = content,
            listState = listState,
            onSongClick = onSongClick,
            modifier = Modifier
                .weight(1f)
                .widthIn(max = PlaylistWideListMaxWidth)
                .fillMaxHeight()
                .padding(top = 16.dp),
        )
    }
}

private val PlaylistWideIdentityWidth = 400.dp
private val PlaylistWideCoverSide = 360.dp
private val PlaylistWideListMaxWidth = 800.dp

@Composable
private fun PlaylistPullUpOverview(
    content: PlaylistDetailUiState.Content,
    stackColors: List<Color>,
    onSongClick: (songId: String) -> Unit,
    // Landscape handset (PlaylistLandscape): the hero turned sideways — cover
    // on its stacked V at the left, Length | Owner and the flowing titles on
    // the right — over the SAME pull-up into the list.
    landscape: Boolean = false,
) {
    val density = LocalDensity.current
    // fraction 1 = hero, 0 = track list; `expanded` is the durable truth and
    // DetailPullUpReconcile its only settle driver (see DetailPullUpReshape).
    val revealState = rememberRevealState(initialFraction = 1f)
    var expanded by rememberSaveable(content.playlistId) { mutableStateOf(false) }
    DetailPullUpReconcile(revealState, expanded)
    val listState = rememberLazyListState()
    val travelPx = remember { mutableFloatStateOf(1f) }
    val gestures = rememberDetailPullUpGestures(
        revealState = revealState,
        listState = listState,
        travelPx = travelPx,
        onExpandedCommit = { expanded = it },
    )
    // Staged "启幕": the cover lands first, the meta + titles rise a beat later.
    val stagedReveal = rememberStagedReveal("playlist-${content.playlistId}")
    val isMany = content.songs.size > DetailManyTracksThreshold

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        travelPx.floatValue = with(density) { maxHeight.toPx() } * DetailPullUpTravelFraction
        val maxW = maxWidth
        // Read HERE so only this page recomposes per reshape frame.
        val expand = 1f - revealState.fraction
        if (landscape) {
            PlaylistLandscapeLayers(
                content = content,
                stackColors = stackColors,
                expand = expand,
                expanded = expanded,
                coverSide = minOf(PlaylistLandscapeCoverSide, maxHeight - 36.dp),
                gestures = gestures,
                listState = listState,
                onSongClick = onSongClick,
            )
            return@BoxWithConstraints
        }

        // Pane-relative: the Album hero's cover footprint (0.74 × width, ≤ 300dp).
        val heroCoverSide = minOf(maxW * 0.74f, 300.dp)
        val dockedHeight = if (isMany) 56.dp else minOf(maxHeight * 0.26f, 220.dp)
        val dockedWidth = if (isMany) maxW else maxW - 32.dp
        val coverHeight = lerp(heroCoverSide, dockedHeight, expand)
        val coverWidth = lerp(heroCoverSide, dockedWidth, expand)
        val e = expand.coerceIn(0f, 1f)
        // Straight band (8dp hero corner → square) or capsule (8dp → stadium).
        val coverCorner = if (isMany) lerp(8.dp, 0.dp, e) else lerp(8.dp, 100.dp, e)
        // Room around the cover for the stacked V; collapses as the cover docks.
        val stackBand = lerp(PlaylistHeroStackBand, 0.dp, e)

        // Clipped to the page body: the backdrop Canvas doesn't clip itself,
        // and nothing here may paint over the header above.
        Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
            PlaylistStackBackground(
                colors = stackColors,
                lineColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                coverSide = coverHeight,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(coverHeight + stackBand)
                    .graphicsLayer { alpha = (1f - expand).coerceIn(0f, 1f) },
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(gestures.heroDrag(enabled = !expanded)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(coverHeight + stackBand)
                        .stagedBeat(
                            progress = { stagedReveal.hero },
                            rise = 20.dp,
                            scaleFrom = 0.94f,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    ExpressiveMediaArtwork(
                        model = content.coverArtUrl,
                        contentDescription = content.playlistName,
                        modifier = Modifier
                            .width(coverWidth)
                            .height(coverHeight),
                        shape = RoundedCornerShape(coverCorner),
                        fallbackIcon = YoinSymbols.Playlist,
                        // No shadow / border — flat, exactly like the Album cover.
                        border = null,
                        shadowElevation = 0.dp,
                        tonalElevation = 3.dp,
                        requestSizePx = 640,
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                ) {
                    if (expand > 0.001f) {
                        PlaylistTrackList(
                            content = content,
                            listState = listState,
                            onSongClick = onSongClick,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = expand.coerceIn(0f, 1f) }
                                .nestedScroll(gestures.listConnection),
                            footer = {
                                // The hero's meta block again at the end of the
                                // list (the Album's "liner notes"), fading in only
                                // over the last 40% of the reshape so it never
                                // doubles the hero's fading copy mid-drag.
                                PlaylistHeroMeta(
                                    content = content,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 8.dp, end = 8.dp, top = 28.dp)
                                        .graphicsLayer {
                                            val listExpand = 1f - revealState.fraction
                                            alpha = ((listExpand - 0.6f) / 0.4f).coerceIn(0f, 1f)
                                        },
                                )
                            },
                        )
                    }
                    if (expand < 0.999f) {
                        PlaylistHeroDetails(
                            content = content,
                            contentWidth = heroCoverSide,
                            // Stop interacting with the fading-out hero once the
                            // list is the dominant layer, so its (still-composed,
                            // alpha≈0) title links can't intercept taps over the list.
                            interactive = expand < 0.5f,
                            onSongClick = onSongClick,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    alpha = (1f - expand).coerceIn(0f, 1f)
                                    translationY = -expand * 40f
                                }
                                .stagedBeat(
                                    progress = { stagedReveal.meta },
                                    rise = 14.dp,
                                ),
                        )
                    }
                }
            }
        }
    }
}

// Room the hero band adds around the cover for the stacked-V backdrop.
private val PlaylistHeroStackBand = 56.dp

private val PlaylistLandscapeCoverSide = 256.dp

@Composable
private fun PlaylistLandscapeLayers(
    content: PlaylistDetailUiState.Content,
    stackColors: List<Color>,
    expand: Float,
    expanded: Boolean,
    coverSide: Dp,
    gestures: DetailPullUpGestures,
    listState: LazyListState,
    onSongClick: (songId: String) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (expand > 0.001f) {
            PlaylistTrackList(
                content = content,
                listState = listState,
                onSongClick = onSongClick,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .graphicsLayer {
                        alpha = expand.coerceIn(0f, 1f)
                        translationY = (1f - expand) * 40f
                    }
                    .nestedScroll(gestures.listConnection),
                footer = {
                    PlaylistHeroMeta(
                        content = content,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, end = 8.dp, top = 28.dp),
                    )
                },
            )
        }
        if (expand < 0.999f) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .then(gestures.heroDrag(enabled = !expanded))
                    .graphicsLayer {
                        alpha = (1f - expand).coerceIn(0f, 1f)
                        translationY = -expand * 40f
                    }
                    // Top room: the back layer rides ~6% above the cover.
                    .padding(start = 44.dp, top = 24.dp, end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(40.dp),
            ) {
                Box(
                    modifier = Modifier.size(coverSide),
                    contentAlignment = Alignment.Center,
                ) {
                    // Whole layers around the cover — nothing cut straight, and
                    // the 40dp gutter keeps the wings clear of the capsules.
                    PlaylistStackBackground(
                        colors = stackColors,
                        lineColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                        coverSide = coverSide,
                        modifier = Modifier.size(coverSide),
                    )
                    ExpressiveMediaArtwork(
                        model = content.coverArtUrl,
                        contentDescription = content.playlistName,
                        modifier = Modifier.fillMaxSize(),
                        shape = YoinArtworkShapes.Hero,
                        fallbackIcon = YoinSymbols.Playlist,
                        border = null,
                        shadowElevation = 0.dp,
                        tonalElevation = 3.dp,
                        requestSizePx = 640,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    PlaylistHeroMeta(content = content, modifier = Modifier.widthIn(max = 300.dp))
                    Spacer(modifier = Modifier.height(20.dp))
                    if (content.songs.isNotEmpty()) {
                        Text(
                            text = buildPlaylistTrackTitles(
                                songs = content.songs,
                                onSongClick = if (expand < 0.5f) onSongClick else null,
                            ),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            overflow = TextOverflow.Visible,
                            modifier = Modifier
                                .fillMaxWidth()
                                .wrapContentHeight(align = Alignment.Top, unbounded = true),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistHeroDetails(
    content: PlaylistDetailUiState.Content,
    contentWidth: Dp,
    interactive: Boolean,
    onSongClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PlaylistHeroMeta(content = content, modifier = Modifier.width(contentWidth))
        Spacer(modifier = Modifier.height(28.dp))
        if (content.songs.isNotEmpty()) {
            // Flowing track titles — each title is its own clickable link that
            // plays just that song. Intentionally NOT truncated: it flows down
            // behind the toolbar and past the bottom safe area (Album parity);
            // pull up for the full list.
            Text(
                text = buildPlaylistTrackTitles(
                    songs = content.songs,
                    onSongClick = if (interactive) onSongClick else null,
                ),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                overflow = TextOverflow.Visible,
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(align = Alignment.Top, unbounded = true),
            )
        } else {
            // Quiet empty state. The always-armed bottom bar stays; Play just
            // no-ops here.
            Text(
                text = stringResource(R.string.detail_playlist_empty_hero),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PlaylistTrackList(
    content: PlaylistDetailUiState.Content,
    listState: LazyListState,
    onSongClick: (songId: String) -> Unit,
    modifier: Modifier = Modifier,
    // >= Medium overview only: a leading hero item that scrolls away with the list.
    header: (@Composable () -> Unit)? = null,
    // Compact pulled-up state only: the trailing liner-notes item.
    footer: (@Composable () -> Unit)? = null,
) {
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyColumn(
        state = listState,
        // The seam is the list's top edge (the docked band's lower edge in the
        // Compact pull-up): rows break into the halftone instead of being cut.
        // At the bottom the bar's halftone field takes the thumbnails.
        modifier = modifier.seamDissolveViewport(
            background = expressivePageSeamBackground(),
            remainingPx = { listState.seamRemainingPx() },
        ) { listState.seamScrolledPx() },
        contentPadding = PaddingValues(top = 4.dp, bottom = 112.dp + navBottom),
    ) {
        if (header != null) {
            item(key = "playlist-medium-hero") { header() }
        }
        if (content.songs.isNotEmpty()) {
            item(key = "playlist-count") {
                AlbumTrackCountLabel(
                    count = content.songCount ?: content.songs.size,
                    totalDurationSeconds = content.totalDuration,
                    modifier = Modifier
                        .padding(start = 8.dp, top = 2.dp, bottom = 10.dp)
                        .seamFade(),
                )
            }
        } else {
            item(key = "playlist-empty") {
                Text(
                    text = stringResource(R.string.detail_playlist_empty_list),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, top = 8.dp),
                )
            }
        }
        // A playlist may hold the same song twice — position + index keep keys unique.
        itemsIndexed(
            items = content.songs,
            key = { index, song -> "${song.position}-$index-${song.id}" },
        ) { _, song ->
            PlaylistTrackRow(
                song = song,
                onClick = { onSongClick(song.id) },
            )
        }
        if (footer != null) {
            item(key = "playlist-liner-notes") {
                Box(modifier = Modifier.seamFade()) { footer() }
            }
        }
    }
}

@Composable
private fun PlaylistTrackCredit(artist: String, album: String) {
    val groups = buildList {
        if (artist.isNotBlank()) add(MetaGroup.Plain(artist))
        if (album.isNotBlank()) add(MetaGroup.Plain(album, muted = artist.isNotBlank()))
    }
    if (groups.isEmpty()) return
    DetailMetaLine(groups = groups, style = MaterialTheme.typography.bodySmall)
}

/**
 * One playlist row: tracks come from different albums, so the row leads with
 * the album thumbnail (not a track number) and credits artist, then album.
 */
@Composable
private fun PlaylistTrackRow(
    song: PlaylistSong,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberYoinHaptics()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(YoinContainerShapes.ListRow)
            .clickable {
                haptics.performClick()
                onClick()
            }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ExpressiveMediaArtwork(
            model = song.coverArtUrl,
            contentDescription = null,
            modifier = Modifier
                .size(44.dp)
                .seamDissolve(),
            shape = YoinArtworkShapes.Thumb,
            fallbackIcon = YoinSymbols.MusicNote,
            border = null,
            shadowElevation = 0.dp,
            requestSizePx = 120,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .seamFade(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            PlaylistTrackCredit(artist = song.artist, album = song.album)
        }
        song.duration?.let { duration ->
            Text(
                text = formatTrackDuration(duration),
                style = MaterialTheme.typography.labelLarge.withTabularFigures(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.seamFade(),
            )
        }
    }
}

// >= Medium hero cover: a fixed side (no reshape to travel), like the Album's.
private val PlaylistMediumHeroCoverSide = 240.dp

@Composable
private fun PlaylistMediumOverview(
    content: PlaylistDetailUiState.Content,
    onSongClick: (songId: String) -> Unit,
) {
    val listState = rememberLazyListState()
    PlaylistTrackList(
        content = content,
        listState = listState,
        onSongClick = onSongClick,
        // Content container carries the width cap; backgrounds stay full-bleed upstream.
        modifier = Modifier
            .fillMaxSize()
            .yoinPageContentWidth()
            .padding(horizontal = 16.dp),
        header = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                ExpressiveMediaArtwork(
                    model = content.coverArtUrl,
                    contentDescription = content.playlistName,
                    modifier = Modifier
                        .size(PlaylistMediumHeroCoverSide)
                        .seamDissolve(),
                    shape = YoinArtworkShapes.Hero,
                    fallbackIcon = YoinSymbols.Playlist,
                    border = null,
                    shadowElevation = 0.dp,
                    tonalElevation = 3.dp,
                    requestSizePx = 640,
                )
                Column(modifier = Modifier.weight(1f)) {
                    PlaylistHeroMeta(content = content)
                }
            }
        },
    )
}

/**
 * The Album hero's cover-width meta block, in playlist terms: "Length" (track
 * count + runtime, two lines like Last Play) and "Owner" (name +
 * visibility), then the description under a label like the album
 * Comment.
 */
@Composable
private fun PlaylistHeroMeta(
    content: PlaylistDetailUiState.Content,
    modifier: Modifier = Modifier,
) {
    val count = content.songCount ?: content.songs.size
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AlbumSectionLabel(text = stringResource(R.string.detail_playlist_length))
                Text(
                    text = pluralStringResource(R.plurals.detail_playlist_tracks, count, count),
                    style = MaterialTheme.typography.bodyLarge.withTabularFigures(),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = content.totalDuration?.takeIf { it > 0 }?.let(::formatTotalDuration) ?: "—",
                    style = MaterialTheme.typography.bodyMedium.withTabularFigures(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (content.owner.isNotBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AlbumSectionLabel(text = stringResource(R.string.detail_playlist_owner))
                    Text(
                        text = content.owner,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    content.isPublic?.let { public ->
                        Text(
                            text = stringResource(
                                if (public) R.string.detail_playlist_public else R.string.detail_playlist_private,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        content.comment?.takeIf { it.isNotBlank() }?.let { description ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AlbumSectionLabel(text = stringResource(R.string.detail_playlist_description))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// Plain text normally (no link blue / underline — inherit the surrounding style);
// an underline appears while a title is pressed. Mirrors the Album hero titles.
private val PlaylistTitleLinkStyles = TextLinkStyles(
    style = SpanStyle(),
    pressedStyle = SpanStyle(textDecoration = TextDecoration.Underline),
)

@Composable
private fun buildPlaylistTrackTitles(
    songs: List<PlaylistSong>,
    onSongClick: ((String) -> Unit)?,
): AnnotatedString {
    val primary = MaterialTheme.colorScheme.onSurface
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    return buildAnnotatedString {
        fun appendTitle(index: Int, song: PlaylistSong) {
            val tone = if (flowingTitleUsesPrimaryTone(index)) primary else variant
            withStyle(SpanStyle(color = tone)) { append(flowingTitle(song.title)) }
        }
        songs.forEachIndexed { index, song ->
            if (index > 0) append(FlowingTitleGap)
            if (onSongClick != null) {
                withLink(
                    LinkAnnotation.Clickable(
                        tag = song.id,
                        styles = PlaylistTitleLinkStyles,
                        linkInteractionListener = { onSongClick(song.id) },
                    ),
                ) { appendTitle(index, song) }
            } else {
                appendTitle(index, song)
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun PlaylistDetailContentPreview() {
    YoinTheme { PlaylistDetailPreviewContent() }
}

@Preview(name = "Landscape handset", widthDp = 844, heightDp = 390, showBackground = true)
@Composable
private fun PlaylistDetailLandscapePreview() {
    YoinTheme { ProvidePreviewWindow(widthDp = 844, heightDp = 390) { PlaylistDetailPreviewContent() } }
}

@Composable
private fun PlaylistDetailPreviewContent() {
        PlaylistDetailScreen(
            uiState = PlaylistDetailUiState.Content(
                playlistId = "playlist-preview",
                playlistName = "Late Night Rotation",
                owner = "gpo",
                comment = "Pulled from Navidrome",
                isPublic = false,
                songCount = 2,
                totalDuration = 768,
                coverArtUrl = null,
                songs = listOf(
                    PlaylistSong(
                        id = "1",
                        title = "Paranoid Android",
                        artist = "Radiohead",
                        album = "OK Computer",
                        duration = 386,
                        coverArtUrl = null,
                    ),
                    PlaylistSong(
                        id = "2",
                        title = "Comfortably Numb",
                        artist = "Pink Floyd",
                        album = "The Wall",
                        duration = 382,
                        coverArtUrl = null,
                    ),
                ),
            ),
            onBackClick = {},
            onPlayAllClick = {},
            onSongClick = {},
            onRetry = {},
        )
}
