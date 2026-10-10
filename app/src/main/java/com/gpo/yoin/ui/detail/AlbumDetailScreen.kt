package com.gpo.yoin.ui.detail

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
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
import com.gpo.yoin.ui.component.expressivePageSeamBackground
import com.gpo.yoin.ui.component.rememberStagedReveal
import com.gpo.yoin.ui.component.seamDissolveViewport
import com.gpo.yoin.ui.component.seamFade
import com.gpo.yoin.ui.component.seamRemainingPx
import com.gpo.yoin.ui.component.seamScrolledPx
import com.gpo.yoin.ui.component.stagedBeat
import com.gpo.yoin.ui.component.yoinPageContentWidth
import com.gpo.yoin.ui.experience.LayoutMode
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.LocalShellChromeInsets
import com.gpo.yoin.ui.experience.LocalYoinWindowInfo
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.experience.ProvidePreviewWindow
import com.gpo.yoin.ui.experience.RevealState
import com.gpo.yoin.ui.experience.rememberRevealState
import com.gpo.yoin.ui.navigation.YoinSection
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinArtworkShapes
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinTheme
import com.gpo.yoin.ui.theme.rememberCoverColorScheme
import com.gpo.yoin.ui.theme.withTabularFigures
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

// At or below this track count the cover docks to a big rounded "capsule"; above
// it, to the thin full-bleed band (a long list needs the band's vertical room).
internal const val DetailManyTracksThreshold = 5

// Page 1 of the pager is the scrapbook (AlbumScrapbookPage, D3 — owner-approved
// 2026-10-05). It also needs a host that passes `scrapbook`: a host that doesn't
// (yet) keeps the page single and the dots hidden. False hides it everywhere.
private const val ALBUM_SECONDARY_PAGE_ENABLED = true

@Composable
fun AlbumDetailScreen(
    uiState: AlbumDetailUiState,
    onBackClick: () -> Unit,
    // The actual window exit, invoked by the back-collapse handler AFTER its
    // commit motion. Defaults to onBackClick so previews/tests keep the old
    // direct-exit behaviour; the Activity passes a dispatcher-routed
    // onBackClick + a finish()-ing onLeavePage.
    onLeavePage: () -> Unit = onBackClick,
    onSongClick: (songId: String) -> Unit,
    onToggleStar: (songId: String) -> Unit,
    onRetry: () -> Unit,
    // The page is on screen again (its window resumed): the host re-asks for the rows' hearts.
    onResumed: () -> Unit = {},
    notedSongIds: Set<String> = emptySet(),
    currentTrackId: String? = null,
    expandedSongId: String? = null,
    expandedNoteBundle: AlbumExpandedNoteBundle? = null,
    onToggleExpandedSong: (songId: String) -> Unit = {},
    onRatingCommit: (Float) -> Unit = {},
    onReviewDraftChange: (String) -> Unit = {},
    // The rate sheet's life and its NeoDB line (owner R3): opened → read where
    // NeoDB stands; closed → keep the words and sync.
    neoDb: AlbumNeoDbSync = AlbumNeoDbSync.Unknown,
    onRateSheetOpened: () -> Unit = {},
    onRateSheetClosed: () -> Unit = {},
    onNeoDbSignIn: () -> Unit = {},
    onNeoDbRetry: () -> Unit = {},
    // Page 2 (the scrapbook). Null = this host doesn't provide it: one page, no dots.
    scrapbook: AlbumScrapbookUiState? = null,
    // A note line on page 2: play the song, then seek to the note's moment once it is ready.
    onNoteMomentClick: (songId: String, positionMs: Long?) -> Unit = { songId, _ -> onSongClick(songId) },
    // Page 2's Memory title: rename (blank = back to Yoin's) and restore the AI title.
    onRenameMemoryTitle: (String) -> Unit = {},
    onRestoreMemoryTitle: () -> Unit = {},
    onPlayAlbum: () -> Unit = {},
    onShufflePlay: () -> Unit = {},
    onShare: () -> Unit = {},
    // The ▾ rows after Shuffle play (Play next, Add to queue, …): the host builds them.
    menu: DetailMenu = DetailMenu(),
    onOpenArtist: (() -> Unit)? = null,
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
    val content = uiState as? AlbumDetailUiState.Content
    val pageAccent = rememberDetailPageAccent(content?.coverArtUrl)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { onResumed() }

    ProvideYoinMotionRole(role = YoinMotionRole.Expressive) {
        // In-window predictive back (AOSP cross-activity math): the whole
        // page — background included — collapses as one card over the LIVE
        // window beneath (the Activity turns translucent for the gesture);
        // the bar is a sibling on top and never transforms — it scrubs its
        // own morph off the same progress.
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
            visualReady = uiState !is AlbumDetailUiState.Loading,
            back = backCollapse,
        )
        Box(
            modifier = modifier.then(
                rememberDetailMotionFrameRateModifier(backCollapse, enterIntro),
            ),
        ) {
            // Do not mount the initial Loading page. The translucent window
            // then leaves the source page intact while data becomes visual-
            // ready; mounting only the current branch also avoids a hidden
            // Loading→Content crossfade becoming the first visible buffer.
            if (enterIntro.pageVisible) {
                DetailEnterPageMountEffect(enterIntro)
                ExpressivePageBackground(
                    accentColor = pageAccent,
                    isPlaying = isPlaying,
                    playbackSignal = playbackSignal,
                    modifier = Modifier
                        .fillMaxSize()
                        .detailBackCollapseTransform(backCollapse)
                        .detailEnterIntroTransform(enterIntro),
                ) {
                    AnimatedContent(
                        targetState = uiState,
                        transitionSpec = {
                            YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                                YoinMotion.fadeOut(role = YoinMotionRole.Standard)
                        },
                        // Class-keyed so Content→Content data updates (favorite
                        // toggles, rating merges) don't re-trigger the fade.
                        contentKey = { it::class },
                        label = "albumDetailState",
                        modifier = Modifier.fillMaxSize(),
                    ) { state ->
                        if (state is AlbumDetailUiState.Content) DetailPerfVisibleEffect("album", state.albumId)
                        // Landscape: header + body clear the capsule band; the
                        // background above (and the album's spectrum bar) stays
                        // full-bleed — Content applies the band inside itself.
                        when (state) {
                            is AlbumDetailUiState.Loading ->
                                Box(modifier = Modifier.fillMaxSize().detailChromeBand()) {
                                    AlbumLoadingState(intro = enterIntro, onBackClick = onBackClick)
                                }

                            is AlbumDetailUiState.Error ->
                                Box(modifier = Modifier.fillMaxSize().detailChromeBand()) {
                                    DetailErrorState(
                                        message = state.message.asString(),
                                        onRetry = onRetry,
                                        onBack = onBackClick,
                                    )
                                }

                            is AlbumDetailUiState.Content ->
                                AlbumDetailContent(
                                    content = state,
                                    onBackClick = onBackClick,
                                    onSongClick = onSongClick,
                                    onToggleStar = onToggleStar,
                                    notedSongIds = notedSongIds,
                                    currentTrackId = currentTrackId,
                                    isPlaying = isPlaying,
                                    expandedSongId = expandedSongId,
                                    expandedNoteBundle = expandedNoteBundle,
                                    onToggleExpandedSong = onToggleExpandedSong,
                                    onRatingCommit = onRatingCommit,
                                    onReviewDraftChange = onReviewDraftChange,
                                    neoDb = neoDb,
                                    onRateSheetOpened = onRateSheetOpened,
                                    onRateSheetClosed = onRateSheetClosed,
                                    onNeoDbSignIn = onNeoDbSignIn,
                                    onNeoDbRetry = onNeoDbRetry,
                                    scrapbook = scrapbook,
                                    onNoteMomentClick = onNoteMomentClick,
                                    onRenameMemoryTitle = onRenameMemoryTitle,
                                    onRestoreMemoryTitle = onRestoreMemoryTitle,
                                )
                        }
                    }
                }
            }

            run {
                // Persistent bottom bar — rendered in ALL states (the bar
                // never waits for page data; the shell's morph is already
                // playing when this window fades in). Play/menu act on
                // Content and no-op during Loading/Error.
                val barScheme = rememberCoverColorScheme(content?.coverArtUrl)
                    ?: MaterialTheme.colorScheme
            DetailBottomBar(
                    // Targets: the bar animates the change itself (YoinChromeGroup).
                    playContainer = barScheme.primary,
                    playContent = barScheme.onPrimary,
                    onPlay = onPlayAlbum,
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
                    promotable = listOfNotNull(
                        onOpenArtist?.let { openArtist ->
                            BarExtraAction(
                                icon = YoinSymbols.Artist,
                                label = stringResource(R.string.detail_album_go_to_artist),
                                onClick = openArtist,
                            )
                        },
                        BarExtraAction(
                            icon = YoinSymbols.Share,
                            label = stringResource(R.string.detail_album_share),
                            onClick = onShare,
                        ),
                    ),
                    menuItems = { dismissMenu -> DetailMenuRows(menu, dismissMenu) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AlbumDetailContent(
    content: AlbumDetailUiState.Content,
    onBackClick: () -> Unit,
    onSongClick: (songId: String) -> Unit,
    onToggleStar: (songId: String) -> Unit,
    notedSongIds: Set<String>,
    currentTrackId: String?,
    isPlaying: Boolean,
    expandedSongId: String?,
    expandedNoteBundle: AlbumExpandedNoteBundle?,
    onToggleExpandedSong: (songId: String) -> Unit,
    onRatingCommit: (Float) -> Unit,
    onReviewDraftChange: (String) -> Unit,
    neoDb: AlbumNeoDbSync,
    onRateSheetOpened: () -> Unit,
    onRateSheetClosed: () -> Unit,
    onNeoDbSignIn: () -> Unit,
    onNeoDbRetry: () -> Unit,
    scrapbook: AlbumScrapbookUiState?,
    onNoteMomentClick: (songId: String, positionMs: Long?) -> Unit,
    onRenameMemoryTitle: (String) -> Unit,
    onRestoreMemoryTitle: () -> Unit,
) {
    // Material color roles seeded from the album's OWN cover (MCU
    // SchemeExpressive) — not raw Palette swatches, which read "off" used as
    // theme color. Falls back to the app theme while the cover loads / if it
    // yields no seed. Animate the block + title colors so the resolve doesn't pop.
    val coverScheme = rememberCoverColorScheme(content.coverArtUrl)
    val s = coverScheme ?: MaterialTheme.colorScheme
    val primaryBlock by animateColorAsState(s.primary, YoinMotion.effectsSpring(), label = "albumPrimaryBlock")
    val secondaryBlock by animateColorAsState(s.secondary, YoinMotion.effectsSpring(), label = "albumSecondaryBlock")
    val titleColor by animateColorAsState(s.primary, YoinMotion.effectsSpring(), label = "albumTitleColor")
    val accentText = s.secondary

    // Height first (断点交接 §5 / §14.6): a landscape handset turns the portrait
    // hero sideways (cover left, what sits under it on the right) and keeps
    // the pull-up. Then width: >=Medium windows (Tabletop stays on the Compact
    // path) fork to a plain scrolling layout — hero row + list at Medium, an
    // identity column beside the full track list on a Wide full window.
    val windowInfo = LocalYoinWindowInfo.current
    val landscape = windowInfo.isCompactHeight
    val layoutMode = windowInfo.layoutMode
    val useWideOverview = !landscape && layoutMode == LayoutMode.Wide
    val useMediumOverview = !landscape && !useWideOverview &&
        layoutMode != LayoutMode.Compact && layoutMode != LayoutMode.Tabletop

    // Pull-up reshape: reuse RevealState. fraction 1 = hero, 0 = track list.
    // Compact and landscape only — the >=Medium overviews compose none of the
    // reveal machine, so the state (and its settle effect) does not exist there.
    val revealState = if (useMediumOverview || useWideOverview) {
        null
    } else {
        rememberRevealState(initialFraction = 1f)
    }
    var expanded by rememberSaveable(content.albumId) { mutableStateOf(false) }
    if (revealState != null) {
        // SINGLE settle owner (cf. the NowPlaying "ONE settle driver" rule):
        // `expanded` is the durable source of truth; this one effect drives the
        // reveal fraction to match it — see DetailPullUpReshape.
        DetailPullUpReconcile(revealState, expanded)
    }

    // Horizontal pager: page 0 = this overview, page 1 = the scrapbook. Page 1 is
    // an in-page state, not a place: back leaves the album from either page
    // (like the pulled-up list), so no back handler is added for it.
    val hasScrapbook = ALBUM_SECONDARY_PAGE_ENABLED && scrapbook != null
    val pagerState = rememberPagerState(pageCount = { if (hasScrapbook) 2 else 1 })
    val pagerScope = rememberCoroutineScope()
    // The scrapbook's emblem stamps once per album page (not per swipe back and forth).
    var scrapbookStamped by rememberSaveable(content.albumId) { mutableStateOf(false) }
    val pageSpec = YoinMotion.defaultSpatialSpec<Float>()

    // Back always finishes the Activity (native cross-Activity predictive back):
    // the pulled-up track list is NOT a back stop, so one back press leaves the
    // page instead of first collapsing the reshape.

    var showEditSheet by remember { mutableStateOf(false) }

    // The cover → spectrum bar (AlbumSpectrumBar.kt): one rule for every size —
    // when the cover leaves view it turns into the header's spectrum. The pull-up
    // drives it on Compact and landscape, the page scroll on Medium; a Wide full
    // window keeps its cover in the identity column and never forms the bar.
    val barLayout = when {
        useWideOverview -> null
        useMediumOverview -> AlbumBarLayout.Medium
        landscape -> AlbumBarLayout.Landscape
        else -> AlbumBarLayout.Compact
    }
    // Staged "启幕" for this album: cover lands first (grow-in), the hero meta
    // rises a beat later. Once per album per page instance — rotation never
    // replays, and the reveal compose stays out of the pull-up reshape math.
    val stagedReveal = rememberStagedReveal("album-${content.albumId}")
    val mediumListState = rememberLazyListState()
    val spectrumSource = rememberAlbumSpectrumSource(
        model = content.coverArtUrl,
        fallback = listOf(s.primary, s.secondary, s.tertiary),
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
    val layoutDirection = LocalLayoutDirection.current
    val rtl = layoutDirection == LayoutDirection.Rtl
    val reduced = LocalMotionProfile.current == MotionProfile.AdaptiveReduced
    val chromeInsets = LocalShellChromeInsets.current
    val surface = MaterialTheme.colorScheme.surface
    var headerHeight by remember { mutableIntStateOf(0) }
    var titleRowTop by remember { mutableIntStateOf(0) }
    var titleRowHeight by remember { mutableIntStateOf(0) }
    val statusBarTopPx = WindowInsets.statusBars.getTop(density)
    val headerTopPaddingPx = with(density) { AlbumHeaderTopPadding.roundToPx() }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        // Landscape handsets: the header and body clear the capsule band and a
        // cutout (detailChromeBand); the bar itself runs edge to edge under them.
        val startInsetPx = with(density) {
            if (landscape) chromeInsets.calculateStartPadding(layoutDirection).toPx() else 0f
        }
        val endInsetPx = with(density) {
            if (landscape) chromeInsets.calculateEndPadding(layoutDirection).toPx() else 0f
        }
        // Read lazily (layout / draw): the header measures in the same pass, before
        // the flying cover, so the cover never misses a frame waiting for it.
        val inColumn = LocalDetailHostMode.current == DetailHostMode.Pane
        val geometryState = remember(barLayout, widthPx, heightPx, startInsetPx, endInsetPx, rtl, density, inColumn) {
            derivedStateOf {
                if (barLayout == null || headerHeight == 0) {
                    null
                } else {
                    albumBarGeometry(
                        layout = barLayout,
                        width = widthPx,
                        height = heightPx,
                        headerHeight = headerHeight.toFloat(),
                        titleRowTop = titleRowTop.toFloat(),
                        titleRowHeight = titleRowHeight.toFloat(),
                        startInset = startInsetPx,
                        endInset = endInsetPx,
                        rtl = rtl,
                        density = density,
                        pageEdgesAreScreenEdges = !inColumn,
                    )
                }
            }
        }
        val geometry: () -> AlbumBarGeometry? = { geometryState.value }
        val progress: () -> Float = when (barLayout) {
            null -> { { 0f } }
            AlbumBarLayout.Medium -> {
                {
                    val g = geometry()
                    when {
                        g == null -> 0f
                        mediumListState.firstVisibleItemIndex > 0 -> 1f
                        else -> (mediumListState.firstVisibleItemScrollOffset / g.cover.height).coerceIn(0f, 1f)
                    }
                }
            }
            else -> { { 1f - (revealState?.fraction ?: 1f) } }
        }
        val coverNow: () -> Rect = {
            val g = geometry()
            when {
                g == null -> Rect.Zero
                barLayout != AlbumBarLayout.Medium -> g.cover
                mediumListState.firstVisibleItemIndex > 0 -> g.cover.translate(0f, -g.cover.bottom)
                else -> g.cover.translate(0f, -mediumListState.firstVisibleItemScrollOffset.toFloat())
            }
        }
        // The bar belongs to page 0: it slides away with it.
        val pageFraction: () -> Float = {
            (pagerState.currentPage + pagerState.currentPageOffsetFraction).coerceIn(0f, 1f)
        }
        val pageShift: () -> Float = { pageFraction() * widthPx * if (rtl) -1f else 1f }
        val docked by remember(barLayout) {
            derivedStateOf { barLayout != null && progress() > AlbumBarDockedThreshold && pageFraction() < 0.5f }
        }
        AlbumBarStatusBarEffect(
            enabled = barLayout != null && !inColumn,
            docked = { docked },
            darkTheme = isSystemInDarkTheme(),
        )
        val stampSize = if (barLayout == AlbumBarLayout.Landscape) AlbumBarLandscapeStampSize else AlbumBarStampSize

        if (barLayout != null) {
            AlbumSpectrumLayer(
                geometry = geometry,
                source = spectrumSource,
                previous = previousSpectrum,
                swap = { spectrumSwap.value },
                blockA = primaryBlock,
                blockB = secondaryBlock,
                surface = surface,
                reduced = reduced,
                rtl = rtl,
                progress = progress,
                coverNow = coverNow,
                pageShift = pageShift,
            )
        }

        // Below the header and pager on purpose: the artwork's Surface swallows
        // touches, so the page above must get the pull-up drag first. The stamp
        // still takes taps — nothing in the header is touchable at its spot.
        if (barLayout != null) {
            val showCoverLabel = stringResource(R.string.detail_album_cd_show_cover)
            AlbumFlyingCover(
                geometry = geometry,
                progress = progress,
                coverNow = coverNow,
                pageShift = pageShift,
                // Hero → Thumb (YoinArtworkShapes), as arcs while they move.
                restingCorner = 8.dp,
                stampCorner = 4.dp,
                modifier = Modifier.fillMaxSize(),
                reduced = reduced,
            ) {
                ExpressiveMediaArtwork(
                    model = content.coverArtUrl,
                    contentDescription = content.albumName,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (barLayout == AlbumBarLayout.Compact) {
                                Modifier.stagedBeat(progress = { stagedReveal.hero }, rise = 20.dp, scaleFrom = 0.94f)
                            } else {
                                Modifier
                            },
                        )
                        // Only the stamp is a button; the resting cover is just a picture.
                        .then(
                            if (docked) {
                                Modifier.clickable(
                                    role = Role.Button,
                                    onClickLabel = showCoverLabel,
                                ) {
                                    if (barLayout == AlbumBarLayout.Medium) {
                                        pagerScope.launch { mediumListState.animateScrollToItem(0) }
                                    } else {
                                        expanded = false
                                    }
                                }
                            } else {
                                Modifier
                            },
                        ),
                    shape = RectangleShape,
                    fallbackIcon = YoinSymbols.Album,
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
            // The bar's rect in the header's own coordinates (the header sits
            // inside the landscape band padding).
            val headerLeftPx = if (rtl) endInsetPx else startInsetPx
            val barInHeader: () -> Rect = {
                Rect(-headerLeftPx - pageShift(), 0f, widthPx - headerLeftPx - pageShift(), headerHeight.toFloat())
            }
            val textProgress: () -> Float = { if (barLayout == null) 0f else albumBarTextProgress(progress()) }
            val pageCount = if (hasScrapbook) 2 else 1
            Box {
                AlbumTopHeader(
                    albumName = content.albumName,
                    artistName = content.artistName,
                    year = content.year,
                    titleColor = titleColor,
                    accentText = accentText,
                    pageFraction = { pagerState.currentPage + pagerState.currentPageOffsetFraction },
                    selectedPage = pagerState.settledPage,
                    pageCount = pageCount,
                    onPageClick = { page ->
                        pagerScope.launch { pagerState.animateScrollToPage(page, animationSpec = pageSpec) }
                    },
                    onBackClick = onBackClick,
                    titleEndRoom = if (barLayout == null) 0.dp else stampSize + AlbumBarTitleGap,
                    titleEndProgress = {
                        if (barLayout == null) 0f else smoothStep(0f, 1f, progress()) * (1f - pageFraction())
                    },
                    // Inside the header's padding: the status bar and 4dp sit above it.
                    onTitleRowPlaced = { row ->
                        titleRowTop = statusBarTopPx + headerTopPaddingPx + row.positionInParent().y.roundToInt()
                        titleRowHeight = row.size.height
                    },
                    modifier = Modifier
                        .onSizeChanged { headerHeight = it.height }
                        .albumBarHeaderFade(textProgress, barInHeader),
                )
                if (barLayout != null) {
                    // White twin over the bar (visual only — touches fall through to the header below).
                    val showTwin by remember(barLayout) { derivedStateOf { textProgress() > 0f } }
                    if (showTwin) {
                        AlbumBarHeaderTwin(
                            albumName = content.albumName,
                            artistName = content.artistName,
                            year = content.year,
                            pageFraction = { pagerState.currentPage + pagerState.currentPageOffsetFraction },
                            selectedPage = pagerState.settledPage,
                            pageCount = pageCount,
                            titleEndRoom = stampSize + AlbumBarTitleGap,
                            titleEndProgress = { smoothStep(0f, 1f, progress()) * (1f - pageFraction()) },
                            modifier = Modifier
                                .graphicsLayer { alpha = textProgress() }
                                .drawWithContent {
                                    val r = barInHeader()
                                    clipRect(r.left, r.top, r.right, r.bottom) { this@drawWithContent.drawContent() }
                                },
                        )
                    }
                }
            }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                ) { page ->
                    when (page) {
                        0 -> {
                            // Fork at the overview call site: reveal == null means
                            // the >=Medium layout; Compact is the untouched call.
                            val reveal = revealState
                            if (useWideOverview) {
                                AlbumWideOverview(
                                    content = content,
                                    primaryBlock = primaryBlock,
                                    secondaryBlock = secondaryBlock,
                                    accent = primaryBlock,
                                    notedSongIds = notedSongIds,
                                    currentTrackId = currentTrackId,
                                    isPlaying = isPlaying,
                                    expandedSongId = expandedSongId,
                                    expandedNoteBundle = expandedNoteBundle,
                                    onSongClick = onSongClick,
                                    onToggleStar = onToggleStar,
                                    onToggleExpandedSong = onToggleExpandedSong,
                                    onEditComment = { showEditSheet = true },
                                )
                            } else if (reveal == null) {
                                AlbumMediumOverview(
                                    content = content,
                                    listState = mediumListState,
                                    accent = primaryBlock,
                                    notedSongIds = notedSongIds,
                                    currentTrackId = currentTrackId,
                                    isPlaying = isPlaying,
                                    expandedSongId = expandedSongId,
                                    expandedNoteBundle = expandedNoteBundle,
                                    onSongClick = onSongClick,
                                    onToggleStar = onToggleStar,
                                    onToggleExpandedSong = onToggleExpandedSong,
                                    onEditComment = { showEditSheet = true },
                                )
                            } else if (landscape) {
                                AlbumLandscapeOverview(
                                    content = content,
                                    accent = primaryBlock,
                                    revealState = reveal,
                                    expanded = expanded,
                                    onExpandedCommit = { expanded = it },
                                    notedSongIds = notedSongIds,
                                    currentTrackId = currentTrackId,
                                    isPlaying = isPlaying,
                                    expandedSongId = expandedSongId,
                                    expandedNoteBundle = expandedNoteBundle,
                                    onSongClick = onSongClick,
                                    onToggleStar = onToggleStar,
                                    onToggleExpandedSong = onToggleExpandedSong,
                                    onEditComment = { showEditSheet = true },
                                )
                            } else {
                                AlbumOverviewPage(
                                    content = content,
                                    stagedReveal = stagedReveal,
                                    accent = primaryBlock,
                                    revealState = reveal,
                                    expanded = expanded,
                                    onExpandedCommit = { expanded = it },
                                    notedSongIds = notedSongIds,
                                    currentTrackId = currentTrackId,
                                    isPlaying = isPlaying,
                                    expandedSongId = expandedSongId,
                                    expandedNoteBundle = expandedNoteBundle,
                                    onSongClick = onSongClick,
                                    onToggleStar = onToggleStar,
                                    onToggleExpandedSong = onToggleExpandedSong,
                                    onEditComment = { showEditSheet = true },
                                )
                            }
                        }

                        else -> AlbumScrapbookPage(
                            state = scrapbook ?: AlbumScrapbookUiState.Loading,
                            content = content,
                            colors = rememberScrapbookColors(s),
                            currentTrackId = currentTrackId,
                            pageOffset = {
                                // 0 settled here, 1 a whole page away (page 0): the
                                // pieces' parallax, read in their graphicsLayer only.
                                (1f - (pagerState.currentPage + pagerState.currentPageOffsetFraction))
                                    .coerceIn(0f, 1f)
                            },
                            onSongClick = onSongClick,
                            onNoteMomentClick = onNoteMomentClick,
                            onEditReview = { showEditSheet = true },
                            onRenameTitle = onRenameMemoryTitle,
                            onRestoreTitle = onRestoreMemoryTitle,
                            settled = pagerState.settledPage == 1,
                            stamped = scrapbookStamped,
                            onStamped = { scrapbookStamped = true },
                        )
                    }
                }

        }

    }

    LaunchedEffect(showEditSheet) {
        if (showEditSheet) onRateSheetOpened()
    }
    if (showEditSheet) {
        AlbumRateSheet(
            content = content,
            neoDb = neoDb,
            onRatingCommit = onRatingCommit,
            onReviewDraftChange = onReviewDraftChange,
            onNeoDbSignIn = onNeoDbSignIn,
            onNeoDbRetry = onNeoDbRetry,
            onDismiss = {
                showEditSheet = false
                onRateSheetClosed()
            },
        )
    }
}

private val AlbumHeaderTopPadding = 4.dp

// Room the title leaves the stamp, besides the stamp itself.
private val AlbumBarTitleGap = 12.dp

@Composable
private fun AlbumTopHeader(
    albumName: String,
    artistName: String,
    year: Int?,
    titleColor: Color,
    accentText: Color,
    pageFraction: () -> Float,
    selectedPage: Int,
    pageCount: Int,
    onPageClick: (Int) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    // The bar's stamp: the title gives up this much room at its end as the bar forms.
    titleEndRoom: Dp = 0.dp,
    titleEndProgress: () -> Float = { 0f },
    onTitleRowPlaced: (androidx.compose.ui.layout.LayoutCoordinates) -> Unit = {},
    // The bar's white twin: drawn only, never touched (the real header sits below it).
    visualOnly: Boolean = false,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 8.dp, end = 16.dp, top = AlbumHeaderTopPadding, bottom = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.onPlaced(onTitleRowPlaced),
        ) {
            if (visualOnly) {
                DetailBackButtonFace(
                    containerColor = Color.White.copy(alpha = 0.18f),
                    contentColor = Color.White,
                )
            } else {
                DetailBackButton(onClick = onBackClick)
            }
            // Air between the button's touch halo and the title cluster —
            // flush against the arrow it read as one crowded blob.
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = albumName,
                    style = MaterialTheme.typography.headlineSmall,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                DetailMetaLine(
                    groups = buildList {
                        if (artistName.isNotBlank()) add(MetaGroup.Plain(artistName))
                        add(MetaGroup.Kind(stringResource(R.string.detail_album_kind), accent = true))
                        if (year != null) add(MetaGroup.Plain(year.toString()))
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
        }
        if (pageCount > 1) {
            AlbumPageDots(
                activeFraction = pageFraction,
                selectedPage = selectedPage,
                activeColor = accentText,
                inactiveColor = if (visualOnly) {
                    Color.White.copy(alpha = 0.4f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                },
                onPageClick = onPageClick,
                count = pageCount,
                visualOnly = visualOnly,
                // ≈16dp from the subtitle's baseline down to the dots, matching
                // the ≈16dp from the dots to each page's first piece (the pages'
                // own top insets are set for it) — owner 2026-10-05: the gap
                // under the dots was twice the gap above.
                modifier = Modifier
                    .padding(top = 5.dp)
                    .align(Alignment.CenterHorizontally),
            )
        }
    }
}

/**
 * The header in white, for over the spectrum bar. Same layout as the real
 * header, so the caller clips it to the bar and fades it in on top; it has no
 * touch targets and no semantics of its own.
 */
@Composable
private fun AlbumBarHeaderTwin(
    albumName: String,
    artistName: String,
    year: Int?,
    pageFraction: () -> Float,
    selectedPage: Int,
    pageCount: Int,
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
        AlbumTopHeader(
            albumName = albumName,
            artistName = artistName,
            year = year,
            titleColor = onBar,
            accentText = onBar,
            pageFraction = pageFraction,
            selectedPage = selectedPage,
            pageCount = pageCount,
            onPageClick = {},
            onBackClick = {},
            titleEndRoom = titleEndRoom,
            titleEndProgress = titleEndProgress,
            visualOnly = true,
            modifier = modifier.clearAndSetSemantics {},
        )
    }
}

/**
 * The real header over the bar: drawn as usual beside it, faded out inside it
 * as the white twin fades in on top (so the two never stack into a dark rim).
 */
private fun Modifier.albumBarHeaderFade(progress: () -> Float, bar: () -> Rect): Modifier = drawWithContent {
    val t = progress()
    if (t <= 0f) {
        drawContent()
        return@drawWithContent
    }
    val r = bar()
    clipRect(r.left, r.top, r.right, r.bottom, clipOp = ClipOp.Difference) { this@drawWithContent.drawContent() }
    if (t < 1f) {
        drawIntoCanvas { canvas ->
            canvas.saveLayer(r, Paint().apply { alpha = 1f - t })
            clipRect(r.left, r.top, r.right, r.bottom) { this@drawWithContent.drawContent() }
            canvas.restore()
        }
    }
}

@Composable
private fun AlbumOverviewPage(
    content: AlbumDetailUiState.Content,
    stagedReveal: StagedReveal,
    accent: Color,
    revealState: RevealState,
    expanded: Boolean,
    onExpandedCommit: (Boolean) -> Unit,
    notedSongIds: Set<String>,
    currentTrackId: String?,
    isPlaying: Boolean,
    expandedSongId: String?,
    expandedNoteBundle: AlbumExpandedNoteBundle?,
    onSongClick: (songId: String) -> Unit,
    onToggleStar: (songId: String) -> Unit,
    onToggleExpandedSong: (songId: String) -> Unit,
    onEditComment: () -> Unit,
) {
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    // The reshape only traverses the upper region, not the full page height, so
    // scale the drag against a fraction of it for a closer-to-1:1 finger feel.
    // Held in state so the remembered draggable / connection read the latest.
    val travelPx = remember { mutableFloatStateOf(1f) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        travelPx.floatValue = with(density) { maxHeight.toPx() } * DetailPullUpTravelFraction
        val maxW = maxWidth

        // ---- Gesture wiring (shared with Playlist, see DetailPullUpReshape) ----
        val gestures = rememberDetailPullUpGestures(
            revealState = revealState,
            listState = listState,
            travelPx = travelPx,
            onExpandedCommit = onExpandedCommit,
        )

        // Reveal fraction read HERE (not in the parent scope) so only this page —
        // not the whole screen, header, pager and toolbar — recomposes per frame
        // during the reshape settle. 1 = hero, 0 = track list; expand 0→1.
        val expand = 1f - revealState.fraction

        // The hero slot: the cover's square plus the 56dp band behind it. The cover
        // and the two blocks are drawn by the bar layer above the pager
        // (AlbumSpectrumLayer / AlbumFlyingCover); here the slot only makes room
        // for them, and closes as they fly into the bar, so the list docks
        // straight under the header.
        val heroCoverSide = minOf(maxW * 0.74f, 300.dp)
        val slotHeight = lerp(heroCoverSide + 56.dp, 0.dp, expand.coerceIn(0f, 1f))

        Box(modifier = Modifier.fillMaxSize()) {
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
                        AlbumTrackList(
                            content = content,
                            accent = accent,
                            notedSongIds = notedSongIds,
                            currentTrackId = currentTrackId,
                            isPlaying = isPlaying,
                            expandedSongId = expandedSongId,
                            expandedNoteBundle = expandedNoteBundle,
                            onSongClick = onSongClick,
                            onToggleStar = onToggleStar,
                            onToggleExpandedSong = onToggleExpandedSong,
                            listState = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = expand.coerceIn(0f, 1f) }
                                .nestedScroll(gestures.listConnection),
                            footer = {
                                // 封面 → 封底: the hero is the front cover, the
                                // pulled-up list reads like the back sleeve —
                                // tracklist first, then the same Last Play /
                                // score Bun / Comment blocks as liner notes.
                                AlbumHeroMetaBlocks(
                                    content = content,
                                    // Mirror of the hero gate: live only while
                                    // the list is the dominant layer.
                                    interactive = expand >= 0.5f,
                                    onEditComment = onEditComment,
                                    onTapBun = onEditComment,
                                    modifier = Modifier
                                        .padding(start = 8.dp, end = 8.dp, top = 28.dp)
                                        // Late fade-in (last 40% of the reshape):
                                        // mid-drag the hero's own copy of these
                                        // blocks is still fading out elsewhere,
                                        // so the two never read as a double.
                                        .graphicsLayer {
                                            val listExpand = 1f - revealState.fraction
                                            alpha = ((listExpand - 0.6f) / 0.4f).coerceIn(0f, 1f)
                                        },
                                )
                            },
                        )
                    }
                    if (expand < 0.999f) {
                        AlbumHeroDetails(
                            content = content,
                            contentWidth = heroCoverSide,
                            // Stop interacting with the fading-out hero once the
                            // list is the dominant layer, so its (still-composed,
                            // alpha≈0) buttons can't intercept taps over the list.
                            interactive = expand < 0.5f,
                            onEditComment = onEditComment,
                            onTapBun = onEditComment,
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

@Composable
private fun AlbumHeroDetails(
    content: AlbumDetailUiState.Content,
    contentWidth: Dp,
    interactive: Boolean,
    onEditComment: () -> Unit,
    onTapBun: () -> Unit,
    onSongClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Last Play + score emblem + comment — a cover-width block.
        AlbumHeroMetaBlocks(
            content = content,
            interactive = interactive,
            onEditComment = onEditComment,
            onTapBun = onTapBun,
            modifier = Modifier.width(contentWidth),
        )

        Spacer(modifier = Modifier.height(28.dp))

        // Total track count + runtime — left-aligned with the flowing titles.
        if (content.songs.isNotEmpty()) {
            AlbumTrackCountLabel(
                count = content.trackTotal,
                totalDurationSeconds = content.totalDuration,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(6.dp))
        }

        // Flowing track titles — clickable, intentionally NOT truncated: it
        // flows down behind the toolbar and past the bottom safe area (unbounded
        // height + overflow Visible), which reads better than a hard ellipsis cut.
        Text(
            text = if (content.songs.isEmpty()) {
                buildAnnotatedString { append(content.albumName) }
            } else {
                buildAlbumTrackTitles(
                    songs = content.songs,
                    featColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    onSongClick = if (interactive) onSongClick else null,
                )
            },
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            overflow = TextOverflow.Visible,
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(align = Alignment.Top, unbounded = true),
        )
    }
}

// Last Play + score emblem + comment — the hero's metadata sub-blocks,
// extracted verbatim so the >=Medium overview reuses the exact same composables
// beside the cover. Width/placement belongs to the caller (modifier); the
// internals are shared and never re-styled per branch.
@Composable
private fun AlbumHeroMetaBlocks(
    content: AlbumDetailUiState.Content,
    interactive: Boolean,
    onEditComment: () -> Unit,
    onTapBun: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AlbumSectionLabel(text = stringResource(R.string.detail_album_last_play))
                val resources = LocalContext.current.resources
                val labels = content.lastPlayedAt?.let { albumLastPlayLabels(it, resources) }
                Text(
                    text = labels?.first ?: stringResource(R.string.detail_album_never),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (labels != null) {
                    Text(
                        text = labels.second,
                        style = MaterialTheme.typography.bodyMedium.withTabularFigures(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // No "Rating"/"Avg." caption: the emblem says what it is (owner 2026-10-05).
            AlbumScoreEmblem(
                spec = content.emblemSpec(),
                coverArtUrl = content.coverArtUrl,
                ratedCount = content.ratedTrackCount,
                total = content.trackTotal,
                enabled = interactive,
                onClick = onTapBun,
            )
        }

        // The comment is the user's own words, so no "Comment" caption: written,
        // it shows as text (tap to edit); not written yet, only a pen.
        if (content.userReview.isNotBlank()) {
            val writtenAt = content.userReviewAt?.let { at ->
                albumLastPlayLabels(at, LocalContext.current.resources).first
            }
            Column(
                modifier = Modifier.clickable(
                    enabled = interactive,
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.detail_album_cd_edit_comment),
                    onClick = onEditComment,
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = content.userReview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                // When it was written (owner 2026-10-05: "写了就留 comment，再加一个写的日期").
                if (writtenAt != null) {
                    Text(
                        text = writtenAt,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            FilledTonalIconButton(
                onClick = onEditComment,
                enabled = interactive,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = YoinSymbols.Edit,
                    contentDescription = stringResource(R.string.detail_album_cd_write_comment),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun AlbumTrackList(
    content: AlbumDetailUiState.Content,
    accent: Color,
    notedSongIds: Set<String>,
    currentTrackId: String?,
    isPlaying: Boolean,
    expandedSongId: String?,
    expandedNoteBundle: AlbumExpandedNoteBundle?,
    onSongClick: (songId: String) -> Unit,
    onToggleStar: (songId: String) -> Unit,
    onToggleExpandedSong: (songId: String) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier,
    // >=Medium overview only: a leading item (the hero row) that scrolls away
    // with the list. Compact never passes one.
    header: (@Composable () -> Unit)? = null,
    // Compact pulled-up state only: a trailing "liner notes" item after the
    // last track (the hero's meta blocks), so the list keeps the album's
    // score / last play / comment instead of ending on a bare row.
    footer: (@Composable () -> Unit)? = null,
) {
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyColumn(
        state = listState,
        // The seam is the list's top edge (the docked band's lower edge in the
        // Compact pull-up): rows fade into it instead of being cut. At the
        // bottom the bar's halftone field takes the graphics; text passes under.
        modifier = modifier.seamDissolveViewport(
            background = expressivePageSeamBackground(),
            remainingPx = { listState.seamRemainingPx() },
        ) { listState.seamScrolledPx() },
        contentPadding = PaddingValues(top = AlbumTrackListTopPadding, bottom = 112.dp + navBottom),
    ) {
        if (header != null) {
            item(key = "album-medium-hero") { header() }
        }
        // Track count + runtime sits just below the docked band, above row 1.
        if (content.songs.isNotEmpty()) {
            item {
                AlbumTrackCountLabel(
                    count = content.trackTotal,
                    totalDurationSeconds = content.totalDuration,
                    modifier = Modifier
                        .padding(start = 8.dp, top = 2.dp, bottom = 10.dp)
                        .seamFade(),
                )
            }
        }
        // Index-qualified: a provider can list the same track twice on one
        // album (seen on Apple Music), and a repeated lazy key is fatal.
        itemsIndexed(content.songs, key = { index, song -> "${song.id}#$index" }) { index, song ->
            Column {
                AlbumTrackRow(
                    index = index,
                    song = song,
                    hasNote = song.id in notedSongIds,
                    isNowPlaying = song.id == currentTrackId,
                    isPlaying = isPlaying,
                    accent = accent,
                    onClick = { onSongClick(song.id) },
                    onLongClick = { onToggleExpandedSong(song.id) },
                    onToggleStar = { onToggleStar(song.id) },
                    showArtist = song.artist.isNotBlank() && song.artist != content.artistName,
                    modifier = Modifier.seamFade(),
                )
                AnimatedVisibility(visible = expandedSongId == song.id) {
                    AlbumSongNotes(
                        bundle = expandedNoteBundle?.takeIf { it.songId == song.id },
                        modifier = Modifier.padding(start = 38.dp, end = 14.dp, bottom = 10.dp),
                    )
                }
            }
        }
        if (footer != null) {
            item(key = "album-liner-notes") {
                Box(modifier = Modifier.seamFade()) { footer() }
            }
        }
    }
}

/** The track list's top inset (the Medium hero row starts this far below the header). */
internal val AlbumTrackListTopPadding = 4.dp

// >=Medium hero cover: a fixed side instead of Compact's window-fraction lerp —
// there is no reshape to travel, so the cover holds one calm size.
internal val AlbumMediumHeroCoverSide = 240.dp

// The >=Medium / Wide overview (Tabletop stays on the Compact path): hero row
// on top — cover left, the hero's metadata blocks right — and the same track
// list Compact uses permanently below, all in ONE plain vertically scrolling
// surface (the hero is a leading list item, so it scrolls away with the page).
// Deliberately absent: RevealState, the pull-up draggable, the docked band and
// the hero<->list crossfade — none of that machinery is composed here. The
// predictive-back collapse and the persistent bottom bar wrap the whole page
// upstream and are untouched by this fork.
@Composable
private fun AlbumMediumOverview(
    content: AlbumDetailUiState.Content,
    listState: androidx.compose.foundation.lazy.LazyListState,
    accent: Color,
    notedSongIds: Set<String>,
    currentTrackId: String?,
    isPlaying: Boolean,
    expandedSongId: String?,
    expandedNoteBundle: AlbumExpandedNoteBundle?,
    onSongClick: (songId: String) -> Unit,
    onToggleStar: (songId: String) -> Unit,
    onToggleExpandedSong: (songId: String) -> Unit,
    onEditComment: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlbumTrackList(
        content = content,
        accent = accent,
        notedSongIds = notedSongIds,
        currentTrackId = currentTrackId,
        isPlaying = isPlaying,
        expandedSongId = expandedSongId,
        expandedNoteBundle = expandedNoteBundle,
        onSongClick = onSongClick,
        onToggleStar = onToggleStar,
        onToggleExpandedSong = onToggleExpandedSong,
        listState = listState,
        // Content container carries the width cap (backgrounds stay full-bleed
        // upstream); the 16dp inset mirrors the Compact list's content Box.
        modifier = modifier
            .fillMaxSize()
            .yoinPageContentWidth()
            .padding(horizontal = 16.dp),
        header = {
            AlbumMediumHeroRow(
                content = content,
                onEditComment = onEditComment,
            )
        },
    )
}

@Composable
private fun AlbumMediumHeroRow(
    content: AlbumDetailUiState.Content,
    onEditComment: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        // The cover (and the two blocks hugging it) is drawn by the bar layer
        // above the pager, so it can fly into the header as the page scrolls.
        Spacer(modifier = Modifier.size(AlbumMediumHeroCoverSide))
        // The hero's own sub-blocks, reused unchanged; always interactive here
        // (there is no fading twin layer whose buttons could steal taps).
        Column(modifier = Modifier.weight(1f)) {
            AlbumHeroMetaBlocks(
                content = content,
                interactive = true,
                onEditComment = onEditComment,
                onTapBun = onEditComment,
            )
        }
    }
}

// Wide full window (AlbumDesktop): an identity column — cover 360, Last Play
// | Avg., Comment — beside the complete track list (max 800). No 720 clamp:
// the two columns share the canvas instead of centring one narrow feed.
private val AlbumWideIdentityWidth = 400.dp
private val AlbumWideCoverSide = 360.dp
private val AlbumWideListMaxWidth = 800.dp

@Composable
private fun AlbumWideOverview(
    content: AlbumDetailUiState.Content,
    primaryBlock: Color,
    secondaryBlock: Color,
    accent: Color,
    notedSongIds: Set<String>,
    currentTrackId: String?,
    isPlaying: Boolean,
    expandedSongId: String?,
    expandedNoteBundle: AlbumExpandedNoteBundle?,
    onSongClick: (songId: String) -> Unit,
    onToggleStar: (songId: String) -> Unit,
    onToggleExpandedSong: (songId: String) -> Unit,
    onEditComment: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(start = 40.dp, end = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        Column(
            modifier = Modifier
                .width(AlbumWideIdentityWidth)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(top = 24.dp, bottom = 120.dp),
        ) {
            Box(
                modifier = Modifier.size(AlbumWideCoverSide + 40.dp),
                contentAlignment = Alignment.Center,
            ) {
                AlbumArrowBackdropHugging(
                    primaryBlock = primaryBlock,
                    secondaryBlock = secondaryBlock,
                    lineColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                    modifier = Modifier.matchParentSize(),
                )
                ExpressiveMediaArtwork(
                    model = content.coverArtUrl,
                    contentDescription = content.albumName,
                    modifier = Modifier.size(AlbumWideCoverSide),
                    shape = YoinArtworkShapes.Hero,
                    fallbackIcon = YoinSymbols.Album,
                    border = null,
                    shadowElevation = 0.dp,
                    tonalElevation = 3.dp,
                    requestSizePx = 900,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            AlbumHeroMetaBlocks(
                content = content,
                interactive = true,
                onEditComment = onEditComment,
                onTapBun = onEditComment,
                modifier = Modifier.width(AlbumWideCoverSide),
            )
        }
        AlbumTrackList(
            content = content,
            accent = accent,
            notedSongIds = notedSongIds,
            currentTrackId = currentTrackId,
            isPlaying = isPlaying,
            expandedSongId = expandedSongId,
            expandedNoteBundle = expandedNoteBundle,
            onSongClick = onSongClick,
            onToggleStar = onToggleStar,
            onToggleExpandedSong = onToggleExpandedSong,
            listState = listState,
            modifier = Modifier
                .weight(1f)
                .widthIn(max = AlbumWideListMaxWidth)
                .fillMaxHeight()
                .padding(top = 16.dp),
        )
    }
}

// Landscape handset (AlbumLandscape): the portrait hero turned sideways —
// cover 256 on the left with the two blocks hugging it, and everything that
// sits under the cover on a phone on the right (Last Play | Avg., Comment,
// track count and duration, the flowing titles). Pulling up runs the SAME reshape
// machine as portrait (RevealState + DetailPullUpReconcile) into the list.
internal val AlbumLandscapeCoverSide = 256.dp

// Room left of the cover for the whole mark (1.3 × cover, centred on it).
internal val AlbumLandscapeCoverInset = 48.dp

@Composable
private fun AlbumLandscapeOverview(
    content: AlbumDetailUiState.Content,
    accent: Color,
    revealState: RevealState,
    expanded: Boolean,
    onExpandedCommit: (Boolean) -> Unit,
    notedSongIds: Set<String>,
    currentTrackId: String?,
    isPlaying: Boolean,
    expandedSongId: String?,
    expandedNoteBundle: AlbumExpandedNoteBundle?,
    onSongClick: (songId: String) -> Unit,
    onToggleStar: (songId: String) -> Unit,
    onToggleExpandedSong: (songId: String) -> Unit,
    onEditComment: () -> Unit,
) {
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val travelPx = remember { mutableFloatStateOf(1f) }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        travelPx.floatValue = with(density) { maxHeight.toPx() } * DetailPullUpTravelFraction
        val gestures = rememberDetailPullUpGestures(
            revealState = revealState,
            listState = listState,
            travelPx = travelPx,
            onExpandedCommit = onExpandedCommit,
        )
        // Read HERE so only this page recomposes per reshape frame.
        val expand = 1f - revealState.fraction
        val coverSide = minOf(AlbumLandscapeCoverSide, maxHeight - 24.dp)
        if (expand > 0.001f) {
            AlbumTrackList(
                content = content,
                accent = accent,
                notedSongIds = notedSongIds,
                currentTrackId = currentTrackId,
                isPlaying = isPlaying,
                expandedSongId = expandedSongId,
                expandedNoteBundle = expandedNoteBundle,
                onSongClick = onSongClick,
                onToggleStar = onToggleStar,
                onToggleExpandedSong = onToggleExpandedSong,
                listState = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .graphicsLayer {
                        alpha = expand.coerceIn(0f, 1f)
                        translationY = (1f - expand) * 40f
                    }
                    .nestedScroll(gestures.listConnection),
                footer = {
                    AlbumHeroMetaBlocks(
                        content = content,
                        interactive = expand >= 0.5f,
                        onEditComment = onEditComment,
                        onTapBun = onEditComment,
                        modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 28.dp),
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
                    .padding(start = AlbumLandscapeCoverInset, top = 4.dp, end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(40.dp),
            ) {
                // The cover and its two blocks are drawn by the bar layer above the
                // pager (they fly into the header on the pull-up); this keeps their place.
                Spacer(modifier = Modifier.size(coverSide))
                Column(modifier = Modifier.weight(1f)) {
                    AlbumHeroMetaBlocks(
                        content = content,
                        interactive = expand < 0.5f,
                        onEditComment = onEditComment,
                        onTapBun = onEditComment,
                        modifier = Modifier.widthIn(max = 300.dp),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    if (content.songs.isNotEmpty()) {
                        AlbumTrackCountLabel(
                            count = content.trackTotal,
                            totalDurationSeconds = content.totalDuration,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    // Flowing titles, clickable, flowing on past the bottom edge
                    // exactly like portrait (overflow Visible, unbounded height).
                    Text(
                        text = if (content.songs.isEmpty()) {
                            buildAnnotatedString { append(content.albumName) }
                        } else {
                            buildAlbumTrackTitles(
                                songs = content.songs,
                                featColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                onSongClick = if (expand < 0.5f) onSongClick else null,
                            )
                        },
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

@Composable
private fun AlbumSongNotes(
    bundle: AlbumExpandedNoteBundle?,
    modifier: Modifier = Modifier,
) {
    // null = the Room flow hasn't emitted for this song yet — hold a quiet
    // fixed-height slot instead of flashing the "no notes" story while loading.
    if (bundle == null) {
        Spacer(modifier = modifier.height(20.dp))
        return
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.detail_album_notes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (bundle.primaryNotes.isEmpty()) {
            Text(
                text = stringResource(R.string.detail_album_no_notes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            bundle.primaryNotes.forEach { note ->
                Text(
                    text = note.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        bundle.crossProviderNotes.forEach { note ->
            Text(
                text = stringResource(
                    R.string.detail_album_note_cross,
                    note.providerLabel.asString(),
                    note.content,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AlbumLoadingState(
    intro: DetailEnterIntroState,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            DetailLoadingIndicator(intro)
        }
        // Mirrors AlbumTopHeader's nav slot — same insets AND the invisible
        // title-cluster line heights that set the row height — so the
        // Loading → Content crossfade doesn't jump the back button.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 6.dp),
        ) {
            DetailBackButton(onClick = onBackClick)
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "", style = MaterialTheme.typography.headlineSmall, maxLines = 1)
                Text(text = "", style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun AlbumDetailScreenContentPreview() {
    YoinTheme {
        AlbumDetailPreviewContent()
    }
}

@Preview(name = "Landscape handset", widthDp = 844, heightDp = 390, showBackground = true)
@Composable
private fun AlbumDetailLandscapePreview() {
    YoinTheme {
        ProvidePreviewWindow(widthDp = 844, heightDp = 390) { AlbumDetailPreviewContent() }
    }
}

@Preview(name = "Wide full window", widthDp = 1440, heightDp = 900, showBackground = true)
@Composable
private fun AlbumDetailDesktopPreview() {
    YoinTheme {
        ProvidePreviewWindow(widthDp = 1440, heightDp = 900) { AlbumDetailPreviewContent() }
    }
}

@Composable
private fun AlbumDetailPreviewContent() {
        AlbumDetailScreen(
            uiState = AlbumDetailUiState.Content(
                albumId = "album-1",
                albumName = "Describe",
                artistName = "Hannah Jadagu",
                artistId = "artist-1",
                coverArtId = "cover-1",
                coverArtUrl = null,
                year = 2025,
                songCount = 8,
                totalDuration = 1680,
                songs = listOf(
                    AlbumSong("1", "Describe", "Hannah Jadagu", 1, 231, true),
                    AlbumSong("2", "Gimme Time", "Hannah Jadagu", 2, 232, true),
                    AlbumSong("3", "More", "Hannah Jadagu", 3, 201, false),
                    AlbumSong(
                        id = "4",
                        title = "Tell Me",
                        artist = "Hannah Jadagu feat. skjkhjashf",
                        trackNumber = 4,
                        duration = 172,
                        isStarred = true,
                        featArtist = "skjkhjashf",
                    ),
                ),
                averageTrackRating = null,
                ratedTrackCount = 0,
                lastPlayedAt = System.currentTimeMillis() - 86_400_000L,
                userReview = "我爱它我爱它我爱它",
            ),
            onBackClick = {},
            onSongClick = {},
            onToggleStar = {},
            onRetry = {},
        )
}
