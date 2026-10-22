package com.gpo.yoin.ui.detail

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
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
import com.gpo.yoin.ui.component.StagedReveal
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
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.LocalYoinWindowInfo
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.experience.ProvidePreviewWindow
import com.gpo.yoin.ui.experience.RevealState
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
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow

@Composable
fun PlaylistDetailScreen(
    uiState: PlaylistDetailUiState,
    onBackClick: () -> Unit,
    // The actual window exit, invoked by the back-collapse handler AFTER its
    // commit motion. Defaults to onBackClick so previews/tests keep the old
    // direct-exit behaviour; the Activity passes a dispatcher-routed
    // onBackClick + a finish()-ing onLeavePage.
    onLeavePage: () -> Unit = onBackClick,
    // A successful deletion exits through the same controller as the page's
    // back affordance, so content, bar and the revealed window settle together.
    leavePageRequests: Flow<Unit>? = null,
    onPlayAllClick: () -> Unit,
    onShufflePlay: () -> Unit = {},
    onSongClick: (songId: String) -> Unit,
    onRetry: () -> Unit,
    onRename: (name: String) -> Unit = {},
    onDelete: () -> Unit = {},
    onShare: () -> Unit = {},
    // "Back up" (docs/playlist-transfer.md §7): null hides the row; the row
    // waits while [backingUp].
    onBackUp: (() -> Unit)? = null,
    backingUp: Boolean = false,
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
    // The rows' songs that carry a note (MediaId strings, PlaylistSong.id's
    // form): those rows wear the album row's note marker.
    notedSongIds: Set<String> = emptySet(),
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
    val titleColorState =
        animateColorAsState(headerScheme.primary, YoinMotion.effectsSpring(), label = "playlistTitleColor")
    val titleColor by titleColorState
    // The rows' note markers read it while drawing: the colour spring repaints
    // them instead of recomposing the track list every frame.
    val rowAccent: () -> Color = remember(titleColorState) { { titleColorState.value } }
    // Stacked-V layers, back to front.
    val stackBack by animateColorAsState(headerScheme.tertiary, YoinMotion.effectsSpring(), label = "playlistStackBack")
    val stackMiddle by animateColorAsState(headerScheme.secondary, YoinMotion.effectsSpring(), label = "playlistStackMiddle")
    val stackFront by animateColorAsState(headerScheme.primary, YoinMotion.effectsSpring(), label = "playlistStackFront")
    val stackColors = listOf(stackBack, stackMiddle, stackFront)

    val accentColor = rememberDetailPageAccent(content?.coverArtUrl)

    // The pull-up lives here, not in the overview: the header's spectrum bar
    // follows it too. fraction 1 = hero, 0 = track list; `expanded` is the
    // durable truth and DetailPullUpReconcile its only settle driver (see
    // DetailPullUpReshape).
    val revealState = rememberRevealState(initialFraction = 1f)
    var expanded by rememberSaveable(content?.playlistId) { mutableStateOf(false) }
    DetailPullUpReconcile(revealState, expanded)
    // Staged "启幕": the cover lands first, the meta + titles rise a beat later.
    val stagedReveal = rememberStagedReveal("playlist-${content?.playlistId}")

    // The Album's cover → spectrum bar (AlbumSpectrumBar.kt), on the portrait
    // pull-up — the overview PlaylistDetailContent picks for a Compact or
    // Tabletop window. Pulling up flies the cover into the header as a stamp
    // and turns the header into the cover's spectrum, exactly as on an album.
    val windowInfo = LocalYoinWindowInfo.current
    val useBar = content != null &&
        !windowInfo.isCompactHeight &&
        (windowInfo.layoutMode == LayoutMode.Compact || windowInfo.layoutMode == LayoutMode.Tabletop)
    val spectrumSource = rememberAlbumSpectrumSource(
        model = content?.coverArtUrl?.takeIf { useBar },
        fallback = listOf(headerScheme.primary, headerScheme.secondary, headerScheme.tertiary),
    )
    // The theme's stand-in spectrum hands over to the cover's with an effects
    // spring (colours never snap).
    var shownSpectrum by remember { mutableStateOf(spectrumSource.spectrum) }
    var previousSpectrum by remember { mutableStateOf<AlbumSpectrum?>(null) }
    val spectrumSwap = remember { Animatable(1f) }
    val spectrumSwapSpec = YoinMotion.effectsSpring<Float>()
    LaunchedEffect(spectrumSource.spectrum) {
        if (spectrumSource.spectrum !== shownSpectrum) {
            previousSpectrum = shownSpectrum
            shownSpectrum = spectrumSource.spectrum
            spectrumSwap.snapTo(0f)
            spectrumSwap.animateTo(1f, spectrumSwapSpec)
            previousSpectrum = null
        }
    }
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val reduced = LocalMotionProfile.current == MotionProfile.AdaptiveReduced
    val inColumn = LocalDetailHostMode.current == DetailHostMode.Pane
    val surface = MaterialTheme.colorScheme.surface
    var headerHeight by remember { mutableIntStateOf(0) }
    // The header's title row, for the stamp: below the status bar and the
    // header's top padding, above its bottom padding.
    val titleRowTopPx = WindowInsets.statusBars.getTop(density) +
        with(density) { PlaylistHeaderTopPadding.toPx() }
    val titleRowBottomPx = with(density) { PlaylistHeaderBottomPadding.toPx() }
    val hasOverflow = content != null && (content.canWrite || onBackUp != null)
    // The stamp sits just before the overflow button when there is one (the
    // Album's header has none), and the title gives up its room as the bar forms.
    val stampEndInsetPx = with(density) { if (hasOverflow) PlaylistBarOverflowRoom.toPx() else 0f }
    val titleEndRoom = AlbumBarStampSize + AlbumBarTitleGap +
        if (hasOverflow) PlaylistBarStampOverflowGap else PlaylistBarStampEndRoom

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
        val currentOnBackClick by rememberUpdatedState(onBackClick)
        LaunchedEffect(leavePageRequests) {
            leavePageRequests?.collect { currentOnBackClick() }
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
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val widthPx = constraints.maxWidth.toFloat()
                        val heightPx = constraints.maxHeight.toFloat()
                        // Read lazily (layout / draw): the header measures in the same
                        // pass, before the flying cover, so the cover never misses a frame.
                        val geometryState = remember(
                            useBar,
                            widthPx,
                            heightPx,
                            titleRowTopPx,
                            titleRowBottomPx,
                            stampEndInsetPx,
                            rtl,
                            density,
                            inColumn,
                        ) {
                            derivedStateOf {
                                if (!useBar || headerHeight == 0) {
                                    null
                                } else {
                                    albumBarGeometry(
                                        layout = AlbumBarLayout.Compact,
                                        width = widthPx,
                                        height = heightPx,
                                        headerHeight = headerHeight.toFloat(),
                                        titleRowTop = titleRowTopPx,
                                        titleRowHeight = headerHeight - titleRowTopPx - titleRowBottomPx,
                                        startInset = 0f,
                                        endInset = stampEndInsetPx,
                                        rtl = rtl,
                                        density = density,
                                        pageEdgesAreScreenEdges = !inColumn,
                                    )
                                }
                            }
                        }
                        val geometry: () -> AlbumBarGeometry? = { geometryState.value }
                        val progress: () -> Float = { if (useBar) 1f - revealState.fraction else 0f }
                        val coverNow: () -> Rect = { geometry()?.cover ?: Rect.Zero }
                        val noPageShift: () -> Float = { 0f }
                        val docked by remember(useBar) {
                            derivedStateOf { useBar && progress() > AlbumBarDockedThreshold }
                        }
                        AlbumBarStatusBarEffect(
                            enabled = useBar && !inColumn,
                            docked = { docked },
                            darkTheme = isSystemInDarkTheme(),
                        )

                        if (useBar && content != null) {
                            // The stacked V behind the resting cover, below the header:
                            // it lands with the cover and fades as the cover leaves
                            // (the Album's blocks fly into the bar; these stay put).
                            if (headerHeight > 0) {
                                val heroCoverSide = minOf(maxWidth * 0.74f, 300.dp)
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .offset { IntOffset(0, headerHeight) }
                                        .clipToBounds(),
                                ) {
                                    PlaylistStackBackground(
                                        colors = stackColors,
                                        lineColor = surface.copy(alpha = 0.65f),
                                        coverSide = heroCoverSide,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(heroCoverSide + PlaylistHeroStackBand)
                                            .graphicsLayer {
                                                alpha = stagedReveal.hero.coerceIn(0f, 1f) *
                                                    (1f - smoothStep(0f, 0.6f, progress()))
                                            },
                                    )
                                }
                            }
                            AlbumSpectrumLayer(
                                geometry = geometry,
                                source = spectrumSource,
                                previous = previousSpectrum,
                                swap = { spectrumSwap.value },
                                blockA = stackFront,
                                blockB = stackMiddle,
                                surface = surface,
                                reduced = reduced,
                                rtl = rtl,
                                progress = progress,
                                coverNow = coverNow,
                                pageShift = noPageShift,
                                butterfly = false,
                            )
                            // Below the header and body on purpose: the artwork's
                            // Surface swallows touches, so the page above must get the
                            // pull-up drag first. The stamp still takes taps — nothing
                            // in the header is touchable at its spot.
                            val showCoverLabel = stringResource(R.string.detail_album_cd_show_cover)
                            AlbumFlyingCover(
                                geometry = geometry,
                                progress = progress,
                                coverNow = coverNow,
                                pageShift = noPageShift,
                                // Hero → Thumb (YoinArtworkShapes), as arcs while they move.
                                restingCorner = 8.dp,
                                stampCorner = 4.dp,
                                modifier = Modifier.fillMaxSize(),
                                reduced = reduced,
                            ) {
                                ExpressiveMediaArtwork(
                                    model = content.coverArtUrl,
                                    contentDescription = content.playlistName,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .stagedBeat(progress = { stagedReveal.hero }, rise = 20.dp, scaleFrom = 0.94f)
                                        // Only the stamp is a button; the resting cover is just a picture.
                                        .then(
                                            if (docked) {
                                                Modifier.clickable(
                                                    role = Role.Button,
                                                    onClickLabel = showCoverLabel,
                                                ) { expanded = false }
                                            } else {
                                                Modifier
                                            },
                                        ),
                                    shape = RectangleShape,
                                    fallbackIcon = YoinSymbols.Playlist,
                                    // No shadow / border — flat, exactly like the Album cover.
                                    border = null,
                                    shadowElevation = 0.dp,
                                    tonalElevation = 3.dp,
                                    requestSizePx = 640,
                                )
                            }
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .detailChromeBand(),
                        ) {
                            val textProgress: () -> Float = { if (useBar) albumBarTextProgress(progress()) else 0f }
                            val titleEndProgress: () -> Float = { if (useBar) smoothStep(0f, 1f, progress()) else 0f }
                            // The bar's rect in the header's own coordinates (portrait
                            // only, so the header spans the page).
                            val barInHeader: () -> Rect = { Rect(0f, 0f, widthPx, headerHeight.toFloat()) }
                            Box {
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
                                    onBackUp = if (content != null) onBackUp else null,
                                    backingUp = backingUp,
                                    titleEndRoom = if (useBar) titleEndRoom else 0.dp,
                                    titleEndProgress = titleEndProgress,
                                    modifier = Modifier
                                        .onSizeChanged { headerHeight = it.height }
                                        .albumBarHeaderFade(textProgress, barInHeader),
                                )
                                if (useBar) {
                                    // White twin over the bar (visual only — touches fall
                                    // through to the header below).
                                    val showTwin by remember(useBar) { derivedStateOf { textProgress() > 0f } }
                                    if (showTwin) {
                                        PlaylistBarHeaderTwin(
                                            playlistName = content?.playlistName.orEmpty(),
                                            owner = content?.owner,
                                            hasOverflow = hasOverflow,
                                            titleEndRoom = titleEndRoom,
                                            titleEndProgress = titleEndProgress,
                                            modifier = Modifier
                                                .graphicsLayer { alpha = textProgress() }
                                                .drawWithContent {
                                                    val r = barInHeader()
                                                    clipRect(r.left, r.top, r.right, r.bottom) {
                                                        this@drawWithContent.drawContent()
                                                    }
                                                },
                                        )
                                    }
                                }
                            }
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
                                if (state is PlaylistDetailUiState.Content) {
                                    DetailPerfVisibleEffect("playlist", state.playlistId)
                                }
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
                                            diagnostics = state.diagnostics,
                                        )

                                    is PlaylistDetailUiState.Content ->
                                        PlaylistDetailContent(
                                            content = state,
                                            stackColors = stackColors,
                                            rowMarks = PlaylistRowMarks(notedSongIds, accent = rowAccent),
                                            onSongClick = onSongClick,
                                            revealState = revealState,
                                            expanded = expanded,
                                            onExpandedCommit = { expanded = it },
                                            stagedReveal = stagedReveal,
                                        )
                                }
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
 * Playlist, plus the overflow: Rename/Delete when the current profile can
 * write this playlist (Spotify followed-but-not-owned playlists hide them
 * rather than showing disabled items), and Back up ([onBackUp] non-null). No
 * rows, no overflow.
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
    onBackUp: (() -> Unit)? = null,
    backingUp: Boolean = false,
    modifier: Modifier = Modifier,
    // The bar's stamp: the title gives up this much room at its end as the bar forms.
    titleEndRoom: Dp = 0.dp,
    titleEndProgress: () -> Float = { 0f },
    // The bar's white twin: drawn only, never touched (the real header sits below it).
    visualOnly: Boolean = false,
) {
    val haptics = rememberYoinHaptics()
    var showOverflow by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(
                start = 8.dp,
                end = 4.dp,
                top = PlaylistHeaderTopPadding,
                bottom = PlaylistHeaderBottomPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (visualOnly) {
            DetailBackButtonFace(
                containerColor = Color.White.copy(alpha = 0.18f),
                contentColor = Color.White,
            )
        } else {
            DetailBackButton(onClick = onBackClick)
        }
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
        // Read in layout only: the title's end follows the stamp frame by frame.
        Spacer(
            modifier = Modifier.layout { measurable, constraints ->
                val room = (titleEndRoom.toPx() * titleEndProgress().coerceIn(0f, 1f)).roundToInt()
                measurable.measure(constraints.copy(minWidth = 0, maxWidth = room))
                layout(room, 0) {}
            },
        )
        if (visualOnly) {
            // Same footprint as the real button; disabled, so taps fall through to it.
            if (canWrite) {
                IconButton(
                    onClick = {},
                    enabled = false,
                    colors = IconButtonDefaults.iconButtonColors(disabledContentColor = Color.White),
                ) {
                    Icon(imageVector = YoinSymbols.MoreVertical, contentDescription = null)
                }
            }
        } else if (canWrite || onBackUp != null) {
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
                    if (onBackUp != null) {
                        YoinDropdownMenuItem(
                            text = stringResource(R.string.detail_playlist_backup_menu),
                            leadingIcon = { Icon(YoinSymbols.Cloud, contentDescription = null) },
                            enabled = !backingUp,
                            onClick = {
                                showOverflow = false
                                onBackUp()
                            },
                        )
                    }
                    if (canWrite) {
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
}

/**
 * The header in white, for over the spectrum bar (the Album's
 * AlbumBarHeaderTwin). Same layout as the real header, so the caller clips it
 * to the bar and fades it in on top; it has no touch targets and no semantics.
 */
@Composable
private fun PlaylistBarHeaderTwin(
    playlistName: String,
    owner: String?,
    hasOverflow: Boolean,
    titleEndRoom: Dp,
    titleEndProgress: () -> Float,
    modifier: Modifier = Modifier,
) {
    val onBar = Color.White
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            primary = onBar.copy(alpha = 0.88f),
            onSurface = onBar,
            onSurfaceVariant = onBar.copy(alpha = 0.82f),
        ),
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
    ) {
        PlaylistTopHeader(
            playlistName = playlistName,
            owner = owner,
            titleColor = onBar,
            // Stands in for "has the overflow button" here.
            canWrite = hasOverflow,
            onBackClick = {},
            onRename = {},
            onDelete = {},
            titleEndRoom = titleEndRoom,
            titleEndProgress = titleEndProgress,
            visualOnly = true,
            modifier = modifier.clearAndSetSemantics {},
        )
    }
}

private val PlaylistHeaderTopPadding = 4.dp
private val PlaylistHeaderBottomPadding = 6.dp

// The overflow button's footprint at the header's end (48dp + the row's 4dp end
// padding): the stamp sits 4dp before it, so 40dp past the bar's own 16dp inset.
private val PlaylistBarOverflowRoom = 40.dp
private val PlaylistBarStampOverflowGap = 4.dp

// Without the overflow button the stamp keeps the Album's 16dp end inset, 12dp
// more than this header's own end padding.
private val PlaylistBarStampEndRoom = 12.dp

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
    rowMarks: PlaylistRowMarks,
    onSongClick: (songId: String) -> Unit,
    revealState: RevealState,
    expanded: Boolean,
    onExpandedCommit: (Boolean) -> Unit,
    stagedReveal: StagedReveal,
) {
    // Same breakpoints as the Album (断点交接 §5): height first — a landscape
    // handset turns the hero sideways and keeps the pull-up — then width.
    val windowInfo = LocalYoinWindowInfo.current
    val layoutMode = windowInfo.layoutMode
    when {
        windowInfo.isCompactHeight -> PlaylistPullUpOverview(
            content = content,
            stackColors = stackColors,
            rowMarks = rowMarks,
            onSongClick = onSongClick,
            revealState = revealState,
            expanded = expanded,
            onExpandedCommit = onExpandedCommit,
            stagedReveal = stagedReveal,
            landscape = true,
        )
        layoutMode == LayoutMode.Wide -> PlaylistWideOverview(
            content = content,
            stackColors = stackColors,
            rowMarks = rowMarks,
            onSongClick = onSongClick,
        )
        layoutMode != LayoutMode.Compact && layoutMode != LayoutMode.Tabletop ->
            PlaylistMediumOverview(content = content, rowMarks = rowMarks, onSongClick = onSongClick)
        else -> PlaylistPullUpOverview(
            content = content,
            stackColors = stackColors,
            rowMarks = rowMarks,
            onSongClick = onSongClick,
            revealState = revealState,
            expanded = expanded,
            onExpandedCommit = onExpandedCommit,
            stagedReveal = stagedReveal,
        )
    }
}

// Wide full window: the Album's identity column (cover 360 on its stacked V,
// Length | Owner) beside the full list (max 800).
@Composable
private fun PlaylistWideOverview(
    content: PlaylistDetailUiState.Content,
    stackColors: List<Color>,
    rowMarks: PlaylistRowMarks,
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
            rowMarks = rowMarks,
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
    rowMarks: PlaylistRowMarks,
    onSongClick: (songId: String) -> Unit,
    // The pull-up, hoisted to the screen (its spectrum bar follows it too).
    revealState: RevealState,
    expanded: Boolean,
    onExpandedCommit: (Boolean) -> Unit,
    stagedReveal: StagedReveal,
    // Landscape handset (PlaylistLandscape): the hero turned sideways — cover
    // on its stacked V at the left, Length | Owner and the flowing titles on
    // the right — over the SAME pull-up into the list.
    landscape: Boolean = false,
) {
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val travelPx = remember { mutableFloatStateOf(1f) }
    val gestures = rememberDetailPullUpGestures(
        revealState = revealState,
        listState = listState,
        travelPx = travelPx,
        onExpandedCommit = onExpandedCommit,
    )

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
                rowMarks = rowMarks,
                onSongClick = onSongClick,
            )
            return@BoxWithConstraints
        }

        // Pane-relative: the Album hero's cover footprint (0.74 × width, ≤ 300dp).
        val heroCoverSide = minOf(maxW * 0.74f, 300.dp)
        // The hero slot: the cover's square plus the band for the stacked V. The
        // cover and the V are drawn by the screen's bar layer behind this page
        // (AlbumFlyingCover, as on the Album); here the slot only makes room for
        // them, and closes as the cover flies into the bar, so the list docks
        // straight under the header.
        val slotHeight = lerp(heroCoverSide + PlaylistHeroStackBand, 0.dp, expand.coerceIn(0f, 1f))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(gestures.heroDrag(enabled = !expanded)),
        ) {
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(slotHeight),
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
            ) {
                if (expand > 0.001f) {
                    PlaylistTrackList(
                        content = content,
                        listState = listState,
                        rowMarks = rowMarks,
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
    rowMarks: PlaylistRowMarks,
    onSongClick: (songId: String) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (expand > 0.001f) {
            PlaylistTrackList(
                content = content,
                listState = listState,
                rowMarks = rowMarks,
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
                text = stringResource(
                    if (content.tracksUnavailable) {
                        R.string.detail_playlist_tracks_unavailable
                    } else {
                        R.string.detail_playlist_empty_hero
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun PlaylistTrackList(
    content: PlaylistDetailUiState.Content,
    listState: LazyListState,
    rowMarks: PlaylistRowMarks,
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
                    text = stringResource(
                        if (content.tracksUnavailable) {
                            R.string.detail_playlist_tracks_unavailable
                        } else {
                            R.string.detail_playlist_empty_list
                        },
                    ),
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
                hasNote = song.id in rowMarks.notedSongIds,
                accent = rowMarks.accent,
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
 * What the rows mark beyond the song itself: the songs carrying a note (MediaId
 * strings, [PlaylistSong.id]'s form) and the page accent their note marker is
 * tinted with (the cover scheme's primary, as the album rows' accent). The
 * accent is read while drawing, so its colour spring never recomposes a row.
 */
@Immutable
internal data class PlaylistRowMarks(
    val notedSongIds: Set<String>,
    val accent: () -> Color
)

/**
 * One playlist row: tracks come from different albums, so the row leads with
 * the album thumbnail (not a track number) and credits artist, then album. A
 * song with a note wears the album row's note marker after its title.
 */
@Composable
private fun PlaylistTrackRow(
    song: PlaylistSong,
    hasNote: Boolean,
    accent: () -> Color,
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
            // Title + the album row's note marker (AlbumTrackRow).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                PlaylistNoteMarker(visible = hasNote, accent = accent)
            }
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

/**
 * The album row's note marker (12dp [YoinSymbols.Note], [accent] at 0.8, 6dp
 * after the title). The noted set is read once the rows are up, so a note can
 * land on a row already on screen: the marker then unfolds from the title's
 * end and fades in on the motion springs, and the title's ellipsis eases
 * over rather than jumping; a note deleted folds it away the same way. A row
 * that has never had a note composes nothing here.
 */
@Suppress("ktlint:standard:function-naming") // Composables are PascalCase
@Composable
private fun PlaylistNoteMarker(visible: Boolean, accent: () -> Color) {
    val shown = remember { MutableTransitionState(visible) }
    shown.targetState = visible
    if (!shown.currentState && !shown.targetState) return
    AnimatedVisibility(
        visibleState = shown,
        enter = YoinMotion.expandHorizontally(role = YoinMotionRole.Standard, expandFrom = Alignment.Start) +
            YoinMotion.fadeIn(role = YoinMotionRole.Standard),
        exit = YoinMotion.shrinkHorizontally(role = YoinMotionRole.Standard, shrinkTowards = Alignment.Start) +
            YoinMotion.fadeOut(role = YoinMotionRole.Standard),
        label = "playlistNoteMarker"
    ) {
        val symbol = rememberVectorPainter(YoinSymbols.Note)
        val description = stringResource(R.string.detail_album_cd_has_note)
        Box(
            modifier = Modifier
                .padding(start = 6.dp)
                .size(12.dp)
                .semantics {
                    contentDescription = description
                    role = Role.Image
                }
                .drawBehind {
                    with(symbol) {
                        draw(size, colorFilter = ColorFilter.tint(accent().copy(alpha = 0.8f)))
                    }
                }
        )
    }
}

// >= Medium hero cover: a fixed side (no reshape to travel), like the Album's.
private val PlaylistMediumHeroCoverSide = 240.dp

@Composable
private fun PlaylistMediumOverview(
    content: PlaylistDetailUiState.Content,
    rowMarks: PlaylistRowMarks,
    onSongClick: (songId: String) -> Unit,
) {
    val listState = rememberLazyListState()
    PlaylistTrackList(
        content = content,
        listState = listState,
        rowMarks = rowMarks,
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
