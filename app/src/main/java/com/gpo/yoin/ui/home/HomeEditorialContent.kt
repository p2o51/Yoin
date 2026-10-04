package com.gpo.yoin.ui.home

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.key
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.experience.feedFrameClass
import com.gpo.yoin.ui.theme.YoinMotionRole
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpo.yoin.data.local.ActivityEntityType
import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.ExpressiveMediaArtwork
import com.gpo.yoin.ui.component.ExpressiveSectionPanel
import com.gpo.yoin.ui.component.MarqueeText
import com.gpo.yoin.ui.component.SeamBackground
import com.gpo.yoin.ui.component.SeamDissolveTokens
import com.gpo.yoin.ui.component.SeamFlow
import com.gpo.yoin.ui.component.SeamTop
import com.gpo.yoin.ui.component.elasticPress
import com.gpo.yoin.ui.component.ignoreParentHorizontalPadding
import com.gpo.yoin.ui.component.horizontalEdgeFadeOnScroll
import com.gpo.yoin.ui.component.noRippleClickable
import com.gpo.yoin.ui.component.rememberExpressiveBackdropColors
import com.gpo.yoin.ui.component.rememberStagedReveal
import com.gpo.yoin.ui.component.seamDissolve
import com.gpo.yoin.ui.component.expressivePageSeamBackground
import com.gpo.yoin.ui.component.seamDissolveViewport
import com.gpo.yoin.ui.component.seamFade
import com.gpo.yoin.ui.component.seamRemainingPx
import com.gpo.yoin.ui.component.seamScrolledPx
import com.gpo.yoin.ui.component.seamTide
import com.gpo.yoin.ui.component.stagedBeat
import com.gpo.yoin.ui.experience.LayoutMode
import com.gpo.yoin.ui.experience.LocalPaneWidthInMotion
import com.gpo.yoin.ui.experience.LocalYoinWindowInfo
import com.gpo.yoin.ui.experience.RevealState
import com.gpo.yoin.ui.experience.rememberRevealState
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinShapeTokens
import com.gpo.yoin.ui.theme.YoinArtworkShapes
import com.gpo.yoin.ui.theme.YoinContainerShapes
import com.gpo.yoin.ui.theme.withTabularFigures
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

internal sealed interface HomeEntryTarget {
    data class Album(val albumId: String, val sharedTransitionKey: String?) : HomeEntryTarget
    data class Artist(val artistId: String) : HomeEntryTarget
    data class Playlist(val playlistId: String) : HomeEntryTarget
    data class SongTarget(val song: Track) : HomeEntryTarget
}

private data class HomeMomentEntry(
    val stableId: String,
    val entityType: String,
    val title: String,
    val subtitle: String,
    // Split so the small bento card can stack them ("Playlist" / "1d ago",
    // the Figma layout); hero/wide join them with a dot.
    val typeLabel: String,
    val timeAgo: String,
    val coverArtUrl: String?,
    val target: HomeEntryTarget,
)

/**
 * The entry's entity identity (not its activity row): replaying the same album
 * keeps the same key, so the bento's stagger doesn't reshuffle on a replay.
 */
private val HomeMomentEntry.layoutKey: String
    get() = entityType + ":" + when (val t = target) {
        is HomeEntryTarget.Album -> t.albumId
        is HomeEntryTarget.Artist -> t.artistId
        is HomeEntryTarget.Playlist -> t.playlistId
        is HomeEntryTarget.SongTarget -> t.song.id.toString()
    }

private const val HomeBackdropPaletteWarmupDelayMillis = 350L

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun HomeEditorialContent(
    activities: List<ActivityEvent>,
    widgetGrid: List<HomeWidgetCard> = emptyList(),
    activityHeroFootnote: String? = null,
    recentlyAddedTracks: List<Track> = emptyList(),
    recentlyAddedAlbums: List<Album> = emptyList(),
    // The header's Memories pill; null keeps today's bare chevron.
    memoryPill: HomeMemoryPill? = null,
    // Something above Home owns the screen (Now Playing, the detail column):
    // the Memories bubble keeps quiet.
    homeCovered: Boolean = false,
    sections: List<HomeSectionState> = HomeLayout.Default.sections,
    onNavigateToSettings: () -> Unit,
    onNavigateToMemories: () -> Unit,
    // Long-press anywhere on the feed enters the home layout editor.
    onEnterEditMode: () -> Unit = {},
    // Memory-flavoured grid cards open the deck stopped on a specific album
    // (by candidate sessionId). The chevron + pull-to-reveal stay generic via
    // onNavigateToMemories.
    onOpenMemoryFocus: (sessionId: Long) -> Unit = {},
    memoriesRevealState: RevealState = rememberRevealState(),
    onCommitMemoriesReveal: () -> Unit = {},
    onAlbumClick: (albumId: String, sharedTransitionKey: String?) -> Unit,
    onArtistClick: (artistId: String) -> Unit,
    onPlaylistClick: (playlistId: String) -> Unit,
    onSongClick: (Track) -> Unit,
    buildCoverArtUrl: (String) -> String,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val haptics = rememberYoinHaptics()
    var containerHeightPx by remember { mutableFloatStateOf(0f) }
    var isCommittedToMemories by remember { mutableStateOf(false) }
    // Visual hint = how far open the reveal is, capped at 1 so rubber-band
    // overshoot doesn't inflate the chevron.
    // Read in the hint's draw phase only: a pull frame must not recompose Home.
    val memoriesHintProgress: () -> Float = { (1f - memoriesRevealState.fraction).coerceIn(0f, 1f) }
    var allowBackdropPalette by remember { mutableStateOf(false) }
    val pullToMemoriesConnection = remember(listState, memoriesRevealState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) {
                    return Offset.Zero
                }
                if (isCommittedToMemories) {
                    // Settle in flight — own the rest of this touch sequence
                    // so the next event doesn't fight the open animation.
                    return Offset(0f, available.y)
                }
                val pullingDownAtTop = available.y > 0f && listState.isAtTop()
                val pullingUpWhileEngaged = available.y < 0f && memoriesRevealState.fraction < 1f
                if (pullingDownAtTop || pullingUpWhileEngaged) {
                    memoriesRevealState.dragBy(available.y, containerHeightPx)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (memoriesRevealState.fraction >= 1f) return Velocity.Zero
                isCommittedToMemories = true
                try {
                    val target = memoriesRevealState.settle(
                        velocityPxPerSec = available.y,
                        containerPx = containerHeightPx,
                    )
                    if (target <= 0f) {
                        haptics.performConfirm()
                        onCommitMemoriesReveal()
                    }
                } finally {
                    isCommittedToMemories = false
                }
                return available
            }
        }
    }
    LaunchedEffect(listState, allowBackdropPalette) {
        if (allowBackdropPalette) return@LaunchedEffect
        snapshotFlow { listState.isScrollInProgress }
            .collectLatest { isScrollInProgress ->
                if (!isScrollInProgress) {
                    delay(HomeBackdropPaletteWarmupDelayMillis)
                    allowBackdropPalette = true
                }
            }
    }
    val activityEntries = remember(activities, buildCoverArtUrl) {
        buildActivityEntries(
            activities = activities,
            buildCoverArtUrl = buildCoverArtUrl,
        )
    }
    // Keep a single stable dispatcher for entry clicks. Nav lambdas are held
    // via rememberUpdatedState so each call reaches the latest referenced
    // lambda without invalidating `remember`-cached entry lists.
    val onAlbumClickState = rememberUpdatedState(onAlbumClick)
    val onArtistClickState = rememberUpdatedState(onArtistClick)
    val onPlaylistClickState = rememberUpdatedState(onPlaylistClick)
    val onSongClickState = rememberUpdatedState(onSongClick)
    val onOpenMemoryFocusState = rememberUpdatedState(onOpenMemoryFocus)
    val onEnterEditModeState = rememberUpdatedState(onEnterEditMode)
    val onEntryClick = remember {
        { target: HomeEntryTarget ->
            when (target) {
                is HomeEntryTarget.Album -> onAlbumClickState.value(
                    target.albumId,
                    target.sharedTransitionKey,
                )
                is HomeEntryTarget.Artist -> onArtistClickState.value(target.artistId)
                is HomeEntryTarget.Playlist -> onPlaylistClickState.value(target.playlistId)
                is HomeEntryTarget.SongTarget -> onSongClickState.value(target.song)
            }
        }
    }
    val onWidgetCardClick = remember {
        { target: HomeWidgetTarget ->
            when (target) {
                is HomeWidgetTarget.AlbumDetail -> onAlbumClickState.value(target.albumId, null)
                is HomeWidgetTarget.PlaylistDetail -> onPlaylistClickState.value(target.playlistId)
                is HomeWidgetTarget.PlaySong -> onSongClickState.value(target.song)
                is HomeWidgetTarget.MemoryFocus -> onOpenMemoryFocusState.value(target.sessionId)
            }
        }
    }

    val shouldExtractBackdropColors = allowBackdropPalette && !listState.isScrollInProgress

    // Cold-start "启幕": one staged reveal for the above-the-fold sections
    // (hero bento → widget grid → recently added). Once per process per key —
    // rememberSaveable survives the covered-shell pause, so returning from a
    // detail page never replays it (motion audit: no repeat-visit staggers).
    // Gated on content arrival: the feed loads async, and a reveal fired
    // against the loading spinner is a reveal nobody sees.
    val firstReveal = rememberStagedReveal(
        key = "home-feed",
        // A resolved Memories pill counts too: on a profile with nothing in
        // the feed yet, it is the one thing to reveal.
        ready = activityEntries.isNotEmpty() || widgetGrid.isNotEmpty() ||
            recentlyAddedTracks.isNotEmpty() || recentlyAddedAlbums.isNotEmpty() ||
            memoryPill != null,
    )
    // Jump Back In's height follows the pane width on Medium / Wide: while
    // that width moves, sections place 1:1 instead of chasing it on a spring.
    val paneWidthInMotion = LocalPaneWidthInMotion.current

    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Wide 全窗桌面态（owner A-prime 裁决 2026-07-28）：feed 不再夹 720dp
    // Feed 档，铺满画布 + 32dp 侧 gutter。Compact/Medium/Tabletop 走原路
    // （限宽 + 16dp 页边），逐字节不变。出血 shelf 的页边与 contentPadding
    // 同源，静止边继续贴住页边（no-midpage-truncation 纪律不破）。
    // 先判高度，再判宽度（断点交接 §4 / §14.6）：高 < 480 的手机横屏走单独的
    // 横屏档 —— 844 宽读成 Wide 也不能落到桌面档。
    val windowInfo = LocalYoinWindowInfo.current
    val isLandscapePhone = windowInfo.isCompactHeight
    // The page frame (Home density rules, owner 2026-10-03): the list runs the
    // container's full width and the margins are its content padding — 16dp
    // with the content capped at 688dp, 32dp on a Wide canvas, 24dp on a
    // landscape handset — so a shelf bleeds to the container's real edge.
    // Margins follow the live width in the layout pass (HomeFeedFrame).
    val feedFrame = rememberHomeFeedFrame(feedFrameClass(windowInfo))
    // Seams (dissolve-final §1.3, §3): the status bar gets the tide line —
    // content sinks under two waves of page colour, text fades out just below
    // it — and the bottom bar gets the halftone field. Both read one set of
    // scroll followers.
    val seamFlow = remember { SeamFlow() }
    // The page runs the same gradient as Library (ExpressivePageBackground in
    // HomeScreen), so both seams ease toward those stops, not a flat colour.
    val seamBackground = expressivePageSeamBackground()
    // The tide line washes with the gradient's TOP stop, where it lives.
    val pageColor = MaterialTheme.colorScheme.surfaceContainer
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val statusBarPx = with(LocalDensity.current) { statusBarTop.toPx() }
    // The Memories entry (owner 2026-10-04): a safe-area arrow with a speech
    // bubble (HomeMemoryBubble.kt), or the trial's header pill / chevron.
    val memoryEntry = LocalHomeHintVariant.current.entry
    val bubbleController = if (memoryEntry == MemoryEntryStyle.Bubble) remember { MemoryBubbleController() } else null
    Box(
        modifier = modifier
            .fillMaxSize()
            .watchMemoryBubbleTouches(bubbleController)
            .seamTide(
                flow = seamFlow,
                color = pageColor,
                statusBarPx = statusBarPx,
            ) { listState.seamScrolledPx() },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .feedFrameWidth(feedFrame)
                .onSizeChanged { containerHeightPx = it.height.toFloat().coerceAtLeast(1f) }
                .seamDissolveViewport(
                    top = SeamTop.FadeText,
                    topInset = statusBarTop + SeamDissolveTokens.TideRest,
                    flow = seamFlow,
                    background = seamBackground,
                    remainingPx = { listState.seamRemainingPx() },
                ) { listState.seamScrolledPx() }
                .nestedScroll(pullToMemoriesConnection)
                // Long-press → layout editor. Cards only consume taps
                // (noRippleClickable), so the press passes through them; any scroll
                // movement cancels it before the timeout.
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = {
                            haptics.performLongPress()
                            onEnterEditModeState.value()
                        },
                    )
                },
            // The landscape Button Group lives in the left cutout band, not at
            // the bottom: only the nav bar needs clearing there.
            contentPadding = remember(feedFrame, isLandscapePhone, navBarBottom) {
                FeedFramePadding(
                    frame = feedFrame,
                    top = 4.dp,
                    bottom = (if (isLandscapePhone) 16.dp else 108.dp) + navBarBottom,
                )
            },
            verticalArrangement = Arrangement.spacedBy(if (isLandscapePhone) 10.dp else 18.dp),
        ) {
            // The page header (title + nav icons) is pinned above the reorderable
            // sections — it's chrome, not a section.
            item(key = "home-header") {
                HomeContentHeader(
                    // Page-level title: sections below it are user-reorderable, so
                    // the header can't borrow the first section's name anymore.
                    title = "Home",
                    compact = isLandscapePhone,
                    bubbleController = bubbleController,
                    onNavigateToSettings = onNavigateToSettings,
                    onNavigateToMemories = onNavigateToMemories,
                    memoriesHintProgress = memoriesHintProgress,
                    memoryPill = memoryPill,
                    // The pill surfaces on the feed's last launch beat.
                    memoryPillReveal = { firstReveal.payload },
                    extractBackdropColors = shouldExtractBackdropColors,
                    onOpenMemoryFocus = { sessionId -> onOpenMemoryFocusState.value(sessionId) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Data-driven feed: render each enabled section in the user's chosen
            // order.
            for (sectionState in sections) {
                if (!sectionState.enabled) continue
                when (sectionState.section) {
                    HomeSection.Activities -> item(key = "section-activities") {
                        if (activityEntries.isNotEmpty()) {
                            // Density by width (owner 2026-10-03): the bento's
                            // recipe and item count follow the container's feed
                            // units (HomeFeedDensity.kt), not LayoutMode. The
                            // phone and landscape compositions are today's.
                            val unitsRecipe = !isLandscapePhone && windowInfo.feedUnits >= 3
                            val bentoEntries = if (unitsRecipe) {
                                remember(activities, buildCoverArtUrl) {
                                    buildActivityEntries(
                                        activities = activities,
                                        buildCoverArtUrl = buildCoverArtUrl,
                                        limit = ActivityBentoUnitsMaxEntries,
                                    )
                                }
                            } else {
                                activityEntries
                            }
                            // Hero slot = first album/playlist; artists fill the
                            // smaller cards in recency order.
                            val heroEntry = bentoEntries.firstOrNull { entry ->
                                entry.entityType == ActivityEntityType.ALBUM.name ||
                                    entry.entityType == ActivityEntityType.PLAYLIST.name
                            }
                            // The stagger is seeded by the hero's identity: the
                            // same feed lays out the same way every time.
                            val bentoSpec = activityBentoSpec(
                                feedUnits = windowInfo.feedUnits,
                                isCompactHeight = isLandscapePhone,
                                seed = activityLayoutSeed(heroEntry?.layoutKey),
                                hasHero = heroEntry != null,
                            )
                            ActivityBento(
                                hero = heroEntry,
                                candidates = bentoEntries.filterNot { it === heroEntry },
                                spec = bentoSpec,
                                heroFootnoteExtra = activityHeroFootnote,
                                extractBackdropColors = shouldExtractBackdropColors,
                                onEntryClick = onEntryClick,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateItem(
                                        fadeInSpec = YoinMotion.effectsSpring(),
                                        placementSpec = if (paneWidthInMotion.value || feedFrame.isBlending) null else YoinMotion.spatialSpring(),
                                        fadeOutSpec = YoinMotion.effectsSpring(),
                                    )
                                    .stagedBeat(
                                        progress = { firstReveal.hero },
                                        rise = 18.dp,
                                        scaleFrom = 0.97f,
                                    ),
                            )
                        } else {
                            HomeEmptyCard(
                                title = "No recent activity yet",
                                supporting = "Once you listen or visit albums and artists, this feed will start filling in.",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateItem(
                                        fadeInSpec = YoinMotion.effectsSpring(),
                                        placementSpec = if (paneWidthInMotion.value || feedFrame.isBlending) null else YoinMotion.spatialSpring(),
                                        fadeOutSpec = YoinMotion.effectsSpring(),
                                    ),
                            )
                        }
                    }

                    // The merged Jump Back In × memories widget grid. Empty means
                    // nothing resolved from any source — skip the section entirely.
                    HomeSection.JumpBackIn -> if (widgetGrid.isNotEmpty()) {
                        item(key = "section-widget-grid") {
                            HomeWidgetGridSection(
                                title = "Jump Back In",
                                cards = widgetGrid,
                                extractBackdropColors = shouldExtractBackdropColors,
                                onCardClick = onWidgetCardClick,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateItem(
                                        fadeInSpec = YoinMotion.effectsSpring(),
                                        placementSpec = if (paneWidthInMotion.value || feedFrame.isBlending) null else YoinMotion.spatialSpring(),
                                        fadeOutSpec = YoinMotion.effectsSpring(),
                                    )
                                    .stagedBeat(
                                        progress = { firstReveal.meta },
                                        rise = 16.dp,
                                    ),
                            )
                        }
                    }

                    // Only render when there's something added this week — an empty
                    // "recently added" shelf is noise, not information.
                    HomeSection.RecentlyAdded ->
                        if (recentlyAddedTracks.isNotEmpty() || recentlyAddedAlbums.isNotEmpty()) {
                            item(key = "section-recently-added") {
                                RecentlyAddedSection(
                                    tracks = recentlyAddedTracks,
                                    albums = recentlyAddedAlbums,
                                    extractBackdropColors = shouldExtractBackdropColors,
                                    onTrackClick = { track -> onEntryClick(HomeEntryTarget.SongTarget(track)) },
                                    onAlbumClick = { album ->
                                        onEntryClick(HomeEntryTarget.Album(album.id.toString(), null))
                                    },
                                    buildCoverArtUrl = buildCoverArtUrl,
                                    frame = feedFrame,
                                    singleRowShelf = isLandscapePhone,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .animateItem(
                                            fadeInSpec = YoinMotion.effectsSpring(),
                                            placementSpec = if (paneWidthInMotion.value || feedFrame.isBlending) null else YoinMotion.spatialSpring(),
                                            fadeOutSpec = YoinMotion.effectsSpring(),
                                        )
                                        .stagedBeat(
                                            progress = { firstReveal.payload },
                                            rise = 16.dp,
                                        ),
                                )
                            }
                        }
                }
            }
        }
        if (bubbleController != null) {
            MemoryBubbleOverlay(
                pill = memoryPill,
                controller = bubbleController,
                hintProgress = memoriesHintProgress,
                // It speaks once the feed's launch reveal has landed.
                revealProgress = { firstReveal.payload },
                scrolledPx = { listState.seamScrolledPx() },
                covered = homeCovered,
                extractBackdropColors = shouldExtractBackdropColors,
                onOpenMemoryFocus = { sessionId -> onOpenMemoryFocusState.value(sessionId) },
                onNavigateToMemories = onNavigateToMemories,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

@Composable
private fun HomeContentHeader(
    title: String,
    onNavigateToSettings: () -> Unit,
    onNavigateToMemories: () -> Unit,
    memoriesHintProgress: () -> Float,
    modifier: Modifier = Modifier,
    // Non-null = the Memories entry is the safe-area bubble overlay: the
    // header only reports the span it leaves between the title and Settings.
    bubbleController: MemoryBubbleController? = null,
    memoryPill: HomeMemoryPill? = null,
    memoryPillReveal: () -> Float = { 1f },
    extractBackdropColors: Boolean = false,
    onOpenMemoryFocus: (sessionId: Long) -> Unit = {},
    // Landscape handset: a 28sp title (LandscapeHome).
    compact: Boolean = false,
) {
    val haptics = rememberYoinHaptics()
    Row(
        modifier = modifier
            .statusBarsPadding()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val titleStyle = MaterialTheme.typography.let { if (compact) it.headlineMedium else it.headlineLarge }
        // Display type fades over 0.75 × its size (≈24dp at 32sp) instead of
        // looking sliced by the short text band.
        Text(
            text = title,
            style = titleStyle,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .padding(end = HomeHeaderTitleBreathing)
                .seamFade(fontSize = titleStyle.fontSize),
        )
        if (bubbleController != null) {
            // The bubble overlay hangs inside this span.
            Spacer(modifier = Modifier.weight(1f).memoryBubbleFreeSpan(bubbleController))
        } else {
            // The Memories entry takes whatever the title and Settings leave, and
            // picks the pill form that fits it (HomeMemoryPill's fit rule).
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterEnd,
            ) {
                HomeMemoryEntry(
                    pill = memoryPill,
                    hintProgress = memoriesHintProgress,
                    revealProgress = memoryPillReveal,
                    extractBackdropColors = extractBackdropColors,
                    onOpenMemoryFocus = onOpenMemoryFocus,
                    onNavigateToMemories = onNavigateToMemories,
                )
            }
        }
        Spacer(modifier = Modifier.width(2.dp))
        IconButton(
            onClick = {
                haptics.performContextClick()
                onNavigateToSettings()
            },
            modifier = Modifier.seamFade(),
        ) {
            Icon(
                imageVector = YoinSymbols.Settings,
                contentDescription = "Settings",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── Activities bento (Figma node 405:362) ──────────────────────────────
//
// Four recent activities in a bento of decreasing prominence: a full-width
// hero, a small square + wide card row, and a single-line strip. Each card's
// container is tonally derived from its own cover art, echoing the mockup's
// per-card colour washes.

/** One bento composition with the entries it seats — the outgoing layer keeps its own while it fades. */
private data class BentoContent(
    val spec: ActivityBentoSpec,
    val hero: HomeMomentEntry?,
    val candidates: List<HomeMomentEntry>,
)

/** Enough recent entries to fill the widest bento (13 slots) and still find an album / playlist hero. */
private const val ActivityBentoUnitsMaxEntries = 16

@Composable
private fun ActivityBento(
    // The hero slot only carries an album / playlist (or nothing); the
    // supporting cards take the rest in recency order. [candidates] is every
    // supporting entry on offer — each composition takes the share its own
    // [spec] seats, so an outgoing composition keeps its cards while it fades.
    // Phone: [0] small square, [1] wide, [2] strip. Units (feed units ≥ 3,
    // owner 2026-10-03): the count follows the width and the rows stagger —
    // see HomeFeedDensity.kt.
    hero: HomeMomentEntry?,
    candidates: List<HomeMomentEntry>,
    spec: ActivityBentoSpec,
    heroFootnoteExtra: String?,
    extractBackdropColors: Boolean,
    onEntryClick: (HomeEntryTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HomeSectionTitle(
            text = "Activities",
            modifier = Modifier.padding(bottom = 6.dp),
        )
        // Fixed row heights, scaled with the user's font size: IntrinsicSize
        // would crash here — MarqueeTitle's BoxWithConstraints is a
        // SubcomposeLayout, which cannot answer intrinsic measurements.
        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        val reduced = LocalMotionProfile.current == MotionProfile.AdaptiveReduced
        val heightSpec = YoinMotion.spatialSpring<Float>()
        // A width class (or the stagger seed) changes the composition: the old
        // one fades out — with its OWN entries — while the new fades in, and
        // the section's height springs between them — never a hard cut. Data
        // changes inside one composition update in place (contentKey).
        AnimatedContent(
            targetState = BentoContent(spec, hero, candidates),
            contentKey = { it.spec },
            transitionSpec = {
                if (reduced) {
                    ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = null)
                } else {
                    // No SizeTransform: its animated WIDTH lags a container
                    // that is shrinking under a column spring, and the parent
                    // centres the overflow. springHeight eases the height.
                    YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                        YoinMotion.fadeOut(role = YoinMotionRole.Standard) using null
                }
            },
            label = "activityBento",
            modifier = Modifier.springHeight(spec = heightSpec, key = spec, enabled = !reduced),
        ) { state ->
            val s = state.spec
            val hero = state.hero
            // Without a hero, row 1's lead slot is a supporting entry too.
            val supporting = state.candidates.take(seatedSupportingCount(s, hasHero = hero != null))
            Box(Modifier.heightOfIncomingOnly { transition.targetState == EnterExitState.PostExit }) {
            when (s.recipe) {
                BentoRecipe.Landscape -> {
                // One row, so the first screen keeps Recently Added in view.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(124.dp * fontScale),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    hero?.let { entry ->
                        ActivityHeroCard(
                            entry = entry,
                            footnoteExtra = heroFootnoteExtra,
                            extractBackdropColors = extractBackdropColors,
                            onClick = { onEntryClick(entry.target) },
                            modifier = Modifier
                                .weight(2f)
                                .fillMaxHeight(),
                        )
                    }
                    supporting.getOrNull(0)?.let { small ->
                        ActivitySmallCard(
                            entry = small,
                            extractBackdropColors = extractBackdropColors,
                            onClick = { onEntryClick(small.target) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    } ?: Spacer(modifier = Modifier.weight(1f))
                    supporting.getOrNull(1)?.let { wide ->
                        ActivityWideCard(
                            entry = wide,
                            extractBackdropColors = extractBackdropColors,
                            onClick = { onEntryClick(wide.target) },
                            modifier = Modifier
                                .weight(1.4f)
                                .fillMaxHeight(),
                        )
                    } ?: Spacer(modifier = Modifier.weight(1.4f))
                }
                }
                BentoRecipe.Phone,
                BentoRecipe.PhoneNarrow,
                -> ActivityBentoPhone(
                    hero = hero,
                    supporting = supporting,
                    narrow = s.recipe == BentoRecipe.PhoneNarrow,
                    heroFootnoteExtra = heroFootnoteExtra,
                    extractBackdropColors = extractBackdropColors,
                    onEntryClick = onEntryClick,
                    fontScale = fontScale,
                )
                BentoRecipe.Units -> ActivityUnitGrid(
                    spec = s,
                    hero = hero,
                    supporting = supporting,
                    heroFootnoteExtra = heroFootnoteExtra,
                    extractBackdropColors = extractBackdropColors,
                    onEntryClick = onEntryClick,
                    fontScale = fontScale,
                )
            }
            }
        }
    }
}

/**
 * The phone composition (feed units 2), unchanged: a full-width hero, a small
 * square + wide row, one strip. [narrow] (feed units 1 — a ~330dp column beside
 * the Now Playing panel on a foldable) splits the supporting row into two equal
 * smalls instead, so the small never shrinks to ~96dp.
 */
@Composable
private fun ActivityBentoPhone(
    hero: HomeMomentEntry?,
    supporting: List<HomeMomentEntry>,
    narrow: Boolean,
    heroFootnoteExtra: String?,
    extractBackdropColors: Boolean,
    onEntryClick: (HomeEntryTarget) -> Unit,
    fontScale: Float,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        hero?.let { entry ->
            ActivityHeroCard(
                entry = entry,
                footnoteExtra = heroFootnoteExtra,
                extractBackdropColors = extractBackdropColors,
                onClick = { onEntryClick(entry.target) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val rowSmall = supporting.getOrNull(0)
        val rowSecond = supporting.getOrNull(1)
        if (rowSmall != null || rowSecond != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(118.dp * fontScale),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rowSmall?.let { small ->
                    ActivitySmallCard(
                        entry = small,
                        extractBackdropColors = extractBackdropColors,
                        onClick = { onEntryClick(small.target) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
                if (narrow) {
                    rowSecond?.let { small ->
                        ActivitySmallCard(
                            entry = small,
                            extractBackdropColors = extractBackdropColors,
                            onClick = { onEntryClick(small.target) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    } ?: Spacer(modifier = Modifier.weight(1f))
                } else {
                    rowSecond?.let { wide ->
                        ActivityWideCard(
                            entry = wide,
                            extractBackdropColors = extractBackdropColors,
                            onClick = { onEntryClick(wide.target) },
                            modifier = Modifier
                                .weight(2f)
                                .fillMaxHeight(),
                        )
                    } ?: run {
                        // Keep the lone small card at column width instead of
                        // letting its weight stretch it across the whole row.
                        Spacer(modifier = Modifier.weight(2f))
                    }
                }
            }
        }
        supporting.getOrNull(2)?.let { strip ->
            ActivityStripCard(
                entry = strip,
                extractBackdropColors = extractBackdropColors,
                onClick = { onEntryClick(strip.target) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The bento on true feed units (feed units ≥ 3): each card sits on the unit
 * grid ([activityUnitSlots]) — a small on one unit, a wide (or the hero) on two
 * or three — at fixed row heights (row 1 [ActivityBentoSpec.row1Height], row 2
 * 118dp, × fontScale), strips sharing the last row equally. One layout node per
 * card; widths come from the live width in the measure pass only, so a column
 * or side-panel spring never recomposes the bento.
 */
@Composable
private fun ActivityUnitGrid(
    spec: ActivityBentoSpec,
    hero: HomeMomentEntry?,
    supporting: List<HomeMomentEntry>,
    heroFootnoteExtra: String?,
    extractBackdropColors: Boolean,
    onEntryClick: (HomeEntryTarget) -> Unit,
    fontScale: Float,
) {
    val slots = remember(spec, hero != null, supporting.size) {
        activityUnitSlots(spec, hasHero = hero != null, supportingCount = supporting.size)
    }
    val placed = slots.mapNotNull { slot ->
        val entry = if (slot.kind == SlotKind.Hero) hero else supporting.getOrNull(slot.entryIndex)
        entry?.let { slot to it }
    }
    Layout(
        content = {
            placed.forEach { (slot, entry) ->
                key(entry.stableId) {
                    val onClick = { onEntryClick(entry.target) }
                    when (slot.kind) {
                        SlotKind.Hero -> ActivityHeroCard(
                            entry = entry,
                            footnoteExtra = heroFootnoteExtra,
                            extractBackdropColors = extractBackdropColors,
                            onClick = onClick,
                        )
                        SlotKind.Wide -> ActivityWideCard(
                            entry = entry,
                            extractBackdropColors = extractBackdropColors,
                            onClick = onClick,
                        )
                        SlotKind.Small -> ActivitySmallCard(
                            entry = entry,
                            extractBackdropColors = extractBackdropColors,
                            onClick = onClick,
                        )
                        SlotKind.Strip -> ActivityStripCard(
                            entry = entry,
                            extractBackdropColors = extractBackdropColors,
                            onClick = onClick,
                        )
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val gapPx = ActivityBentoGap.toPx()
        val gap = ActivityBentoGap.roundToPx()
        val pitch = jbiColumnPitch(width.toFloat(), spec.units, gapPx)
        val rowHeights = intArrayOf(
            (spec.row1Height * fontScale).roundToPx(),
            (118.dp * fontScale).roundToPx(),
        )
        // Strips share the last row on the same snapped lines as the units,
        // so the row ends exactly on the unit rows' edge.
        val strips = spec.strips.coerceAtLeast(1)
        val stripPitch = jbiColumnPitch(width.toFloat(), strips, gapPx)
        val placeables = arrayOfNulls<Placeable>(measurables.size)
        val xs = IntArray(measurables.size)
        val ys = IntArray(measurables.size)
        var y = 0
        var index = 0
        for (row in 0..2) {
            val inRow = placed.indices.filter { placed[it].first.row == row }
            if (inRow.isEmpty()) continue
            if (index > 0) y += gap
            var rowHeight = 0
            inRow.forEach { i ->
                val slot = placed[i].first
                val placeable = if (row < 2) {
                    val cell = JbiCellPlacement(startColumn = slot.startUnit, span = slot.span)
                    xs[i] = cell.cellLeft(pitch)
                    val w = cell.cellWidth(pitch, gapPx).coerceAtLeast(0)
                    measurables[i].measure(Constraints.fixed(w, rowHeights[row]))
                } else {
                    val cell = JbiCellPlacement(startColumn = slot.startUnit, span = 1)
                    xs[i] = cell.cellLeft(stripPitch)
                    val w = cell.cellWidth(stripPitch, gapPx).coerceAtLeast(0)
                    measurables[i].measure(Constraints(minWidth = w, maxWidth = w))
                }
                placeables[i] = placeable
                ys[i] = y
                rowHeight = maxOf(rowHeight, placeable.height)
            }
            y += rowHeight
            index += inRow.size
        }
        layout(width, constraints.constrainHeight(y)) {
            placeables.forEachIndexed { i, placeable -> placeable?.placeRelative(xs[i], ys[i]) }
        }
    }
}

private val ActivityBentoGap = 10.dp

private data class ActivityCardColors(
    val container: Color,
    val content: Color,
    val contentMuted: Color,
)

/**
 * Container wash lerped straight from this card's own cover palette — NOT an
 * `ExpressiveColorSchemeFactory.fromSeed` scheme, whose M3-Expressive hue
 * rotation turns a green cover into a peach card. The direct lerp keeps each
 * card hue-faithful to its artwork (the Figma look) and skips building a
 * ColorScheme per palette-animation frame. Text stays on the theme's
 * on-surface roles, which hold contrast on the soft wash in both modes.
 */
@Composable
private fun rememberActivityCardColors(
    coverArtUrl: String?,
    extractBackdropColors: Boolean,
): ActivityCardColors {
    val backdrop = rememberExpressiveBackdropColors(
        model = coverArtUrl,
        fallbackBaseColor = MaterialTheme.colorScheme.secondaryContainer,
        fallbackAccentColor = MaterialTheme.colorScheme.tertiaryContainer,
        enabled = extractBackdropColors,
    )
    return ActivityCardColors(
        container = lerp(
            MaterialTheme.colorScheme.surfaceContainerLow,
            backdrop.baseColor,
            0.30f,
        ),
        content = MaterialTheme.colorScheme.onSurface,
        contentMuted = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// Design decision (settled after trying all-none): every activities card gets
// the same tinted container — all-or-none, and all won. The wash comes from
// each card's own cover palette, so the bento reads like the Figma's colour
// blocks while the entity shape still carries the identity inside.
@Composable
private fun ActivityHeroCard(
    entry: HomeMomentEntry,
    footnoteExtra: String?,
    extractBackdropColors: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val colors = rememberActivityCardColors(entry.coverArtUrl, extractBackdropColors)
    Surface(
        // The tinted card and its cover break up as one print; the text on it
        // is lifted out and passes under the bar whole.
        modifier = modifier
            .elasticPress(interactionSource)
            .seamDissolve(),
        shape = YoinContainerShapes.Card,
        color = colors.container,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .noRippleClickable(interactionSource = interactionSource, onClick = onClick)
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WidgetBackdropArtwork(
                model = entry.coverArtUrl,
                kind = widgetShapeKindForActivity(entry.entityType),
                contentDescription = entry.title,
                extractBackdropColors = extractBackdropColors,
                interactionSource = interactionSource,
                modifier = Modifier.size(96.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .seamFade(),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = "${entry.typeLabel} · ${entry.timeAgo}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.contentMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                MarqueeText(
                    text = entry.title,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.content,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = entry.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.contentMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                footnoteExtra?.let { extra ->
                    Text(
                        text = extra,
                        style = MaterialTheme.typography.labelSmall.withTabularFigures(),
                        color = colors.contentMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActivitySmallCard(
    entry: HomeMomentEntry,
    extractBackdropColors: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val colors = rememberActivityCardColors(entry.coverArtUrl, extractBackdropColors)
    Surface(
        // The tinted card and its cover break up as one print; the text on it
        // is lifted out and passes under the bar whole.
        modifier = modifier
            .elasticPress(interactionSource)
            .seamDissolve(),
        shape = YoinContainerShapes.Card,
        color = colors.container,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .noRippleClickable(interactionSource = interactionSource, onClick = onClick)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // The entity shape+cover anchors the slot (same language as every
            // other card), with the type + time stacked to its right — two
            // plain lines, no separator dot.
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WidgetBackdropArtwork(
                    model = entry.coverArtUrl,
                    kind = widgetShapeKindForActivity(entry.entityType),
                    contentDescription = entry.title,
                    extractBackdropColors = extractBackdropColors,
                    interactionSource = interactionSource,
                    modifier = Modifier.size(48.dp),
                )
                Column(modifier = Modifier.seamFade()) {
                    Text(
                        text = entry.typeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.contentMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = entry.timeAgo,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.contentMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = entry.title,
                // titleSmall's stock 20sp leading reads as two separate rows
                // when this wraps; tightened so a 2-line title is one block.
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 17.sp,
                ),
                color = colors.content,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.seamFade(),
            )
        }
    }
}

@Composable
private fun ActivityWideCard(
    entry: HomeMomentEntry,
    extractBackdropColors: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val colors = rememberActivityCardColors(entry.coverArtUrl, extractBackdropColors)
    Surface(
        // The tinted card and its cover break up as one print; the text on it
        // is lifted out and passes under the bar whole.
        modifier = modifier
            .elasticPress(interactionSource)
            .seamDissolve(),
        shape = YoinContainerShapes.Card,
        color = colors.container,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .noRippleClickable(interactionSource = interactionSource, onClick = onClick)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WidgetBackdropArtwork(
                model = entry.coverArtUrl,
                kind = widgetShapeKindForActivity(entry.entityType),
                contentDescription = entry.title,
                extractBackdropColors = extractBackdropColors,
                interactionSource = interactionSource,
                modifier = Modifier.size(80.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .seamFade(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "${entry.typeLabel} · ${entry.timeAgo}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.contentMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                MarqueeText(
                    text = entry.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.content,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = entry.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.contentMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ActivityStripCard(
    entry: HomeMomentEntry,
    extractBackdropColors: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    // Text-only, per the design — no cover chip. The TITLE is bold (Figma),
    // the ・artist tail stays regular so the pair reads as one line without
    // flattening into a single weight.
    val stripTitle = remember(entry) {
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                append(entry.title)
            }
            if (entry.subtitle.isNotBlank()) {
                append("・")
                append(entry.subtitle)
            }
        }
    }
    val colors = rememberActivityCardColors(entry.coverArtUrl, extractBackdropColors)
    Surface(
        modifier = modifier
            .elasticPress(interactionSource)
            .seamDissolve(),
        shape = YoinShapeTokens.Full,
        color = colors.container,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .noRippleClickable(interactionSource = interactionSource, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stripTitle,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .seamFade(),
            )
            Text(
                text = "${entry.typeLabel} · ${entry.timeAgo}",
                style = MaterialTheme.typography.labelSmall,
                color = colors.contentMuted,
                maxLines = 1,
                modifier = Modifier.seamFade(),
            )
        }
    }
}

private fun widgetShapeKindForActivity(entityType: String): WidgetShapeKind = when (entityType) {
    ActivityEntityType.SONG.name -> WidgetShapeKind.Song
    ActivityEntityType.PLAYLIST.name -> WidgetShapeKind.Playlist
    ActivityEntityType.ARTIST.name -> WidgetShapeKind.Artist
    else -> WidgetShapeKind.Album
}

// ── Recently Added (tracks grid + album shelf, Figma 622:777) ──────────
//
// A split shelf: on the left a compact 2×2 grid of the four most-recently
// added tracks (small cover + title / artist), on the right a horizontally
// scrolling row of recently-added albums, each nested on its Bun backdrop
// shape. Either half collapses when its list is empty, and the lone survivor
// takes the full width.

// Track cover sized so a tight 2×2 (two rows + one 14dp gap) lands near the
// album card's height (album cover + its two label lines) without a hollow
// middle. Kept modest so the title/artist column beside it stays wide (the
// covers and the album shrink together to hold the height match). Still clearly
// smaller than the album cover, matching the mock ratio.
private val RecentlyAddedTrackCover = 52.dp
private val RecentlyAddedAlbumCover = 82.dp

@Composable
private fun RecentlyAddedSection(
    tracks: List<Track>,
    albums: List<Album>,
    extractBackdropColors: Boolean,
    onTrackClick: (Track) -> Unit,
    onAlbumClick: (Album) -> Unit,
    buildCoverArtUrl: (String) -> String,
    // The feed's frame supplies the live margins: the shelf bleeds past them
    // to the container's edge and pads its content by the same amounts, so
    // resting items stay on the page margin (no-midpage-truncation).
    frame: HomeFeedFrame,
    // Landscape handset: the track grid gives up width so the album covers
    // read as one row across the page (LandscapeHome).
    singleRowShelf: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HomeSectionTitle(text = "Recently Added")
        // ONE shelf: the 2×2 track grid is the shelf's first card and the
        // albums follow it, all panning together (user call — the albums
        // scrolling alone under a pinned grid read as two disjoint widgets).
        // Full-bleed to the container's edge with page-margin content
        // padding; content clips hard at the screen edge — no edge-fade scrim
        // here (2026-07-18 ruling: the translucent mask read as clutter on this
        // shelf; the seamless cut wins).
        val shelfState = rememberLazyListState()
        val sidePadding = remember(frame) { FeedFrameSidePadding(frame) }
        LazyRow(
            state = shelfState,
            modifier = Modifier
                .fillMaxWidth()
                .ignoreParentHorizontalPadding(start = { frame.start }, end = { frame.end }),
            contentPadding = sidePadding,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            // Both halves hang from the top. The track covers are sized
            // so a tight 2×2 lands at roughly the album card's height.
            verticalAlignment = Alignment.Top,
        ) {
            if (tracks.isNotEmpty()) {
                item(key = "recently-added-tracks") {
                    RecentlyAddedTrackGrid(
                        tracks = tracks,
                        onTrackClick = onTrackClick,
                        buildCoverArtUrl = buildCoverArtUrl,
                        // The grid's width follows the feed's content width,
                        // capped (HomeFeedDensity: recentlyAddedGridWidth) —
                        // read in the layout pass only, so a column spring
                        // never re-subcomposes the shelf.
                        modifier = Modifier.layout { measurable, constraints ->
                            val width = recentlyAddedGridWidth(frame.contentWidth, singleRowShelf).roundToPx()
                            val placeable = measurable.measure(
                                constraints.copy(minWidth = width, maxWidth = width),
                            )
                            layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
                        },
                    )
                }
            }
            items(
                items = albums,
                key = { album -> "recently-added-album:${album.id}" },
            ) { album ->
                RecentlyAddedAlbumCard(
                    album = album,
                    extractBackdropColors = extractBackdropColors,
                    onClick = { onAlbumClick(album) },
                    buildCoverArtUrl = buildCoverArtUrl,
                )
            }
        }
    }
}

/** The shelf's lead card: up to four tracks packed into a 2×2 grid. */
@Composable
private fun RecentlyAddedTrackGrid(
    tracks: List<Track>,
    onTrackClick: (Track) -> Unit,
    buildCoverArtUrl: (String) -> String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        // Tight, even gap between the two rows — the covers (not the gap) carry
        // the height, so the pair reads as one block instead of two stranded
        // rows with a hollow middle.
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        tracks.take(4).chunked(2).forEach { rowTracks ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                rowTracks.forEach { track ->
                    RecentlyAddedTrackTile(
                        track = track,
                        onClick = { onTrackClick(track) },
                        buildCoverArtUrl = buildCoverArtUrl,
                        modifier = Modifier.weight(1f),
                    )
                }
                // Pad an odd final row so a lone tile keeps its column width
                // instead of stretching across the whole grid.
                if (rowTracks.size < 2) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun RecentlyAddedTrackTile(
    track: Track,
    onClick: () -> Unit,
    buildCoverArtUrl: (String) -> String,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val coverArtUrl = resolveHomeCoverArtUrl(track.coverArt, buildCoverArtUrl)
        ?: track.albumId?.let { buildCoverArtUrl(it.rawId) }
    Row(
        modifier = modifier
            .noRippleClickable(interactionSource = interactionSource, onClick = onClick)
            .elasticPress(interactionSource),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExpressiveMediaArtwork(
            model = coverArtUrl,
            contentDescription = track.title.orEmpty(),
            modifier = Modifier
                .size(RecentlyAddedTrackCover)
                .seamDissolve(),
            shape = YoinArtworkShapes.Thumb,
            fallbackIcon = YoinSymbols.Album,
            interactionSource = interactionSource,
            tonalElevation = 1.dp,
            shadowElevation = 0.dp,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .seamFade(),
        ) {
            Text(
                text = track.title.orEmpty(),
                // 13sp (vs bodyMedium's 14) so short titles like "Describe" fit
                // the narrow two-column cell instead of truncating.
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            track.artist?.takeIf { it.isNotBlank() }?.let { artist ->
                Text(
                    text = artist,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun RecentlyAddedAlbumCard(
    album: Album,
    extractBackdropColors: Boolean,
    onClick: () -> Unit,
    buildCoverArtUrl: (String) -> String,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val coverArtUrl = resolveHomeCoverArtUrl(album.coverArt, buildCoverArtUrl)
    Column(
        modifier = modifier
            .width(RecentlyAddedAlbumCover)
            .noRippleClickable(interactionSource = interactionSource, onClick = onClick)
            .elasticPress(interactionSource),
    ) {
        WidgetBackdropArtwork(
            model = coverArtUrl,
            kind = WidgetShapeKind.Album,
            contentDescription = album.name,
            extractBackdropColors = extractBackdropColors,
            interactionSource = interactionSource,
            modifier = Modifier.size(RecentlyAddedAlbumCover),
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = album.name,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.seamFade(),
        )
        album.artist?.takeIf { it.isNotBlank() }?.let { artist ->
            Text(
                text = artist,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.seamFade(),
            )
        }
    }
}

@Composable
private fun HomeEmptyCard(
    title: String,
    supporting: String,
    modifier: Modifier = Modifier,
) {
    ExpressiveSectionPanel(
        modifier = modifier.seamDissolve(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .padding(18.dp)
                .seamFade(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun dedupeActivitiesForHome(
    activities: List<ActivityEvent>,
): List<ActivityEvent> = activities.distinctBy(::homeActivityDedupKey)

/**
 * What the Activities bento actually shows: deduped, and tracks dropped —
 * the bento opens album / playlist / artist pages only, single plays don't
 * earn a card.
 */
internal fun selectHomeActivities(
    activities: List<ActivityEvent>,
): List<ActivityEvent> = dedupeActivitiesForHome(activities)
    .filterNot { it.entityType == ActivityEntityType.SONG.name }

/**
 * The hero (topmost, biggest) bento slot only ever shows an album or a
 * playlist — artists keep to the smaller cards. Shared with the ViewModel so
 * the hero footnote is resolved for the same entry the UI crowns.
 */
internal fun selectHomeHeroActivity(
    activities: List<ActivityEvent>,
): ActivityEvent? = selectHomeActivities(activities).firstOrNull {
    it.entityType == ActivityEntityType.ALBUM.name ||
        it.entityType == ActivityEntityType.PLAYLIST.name
}

/**
 * `ActivityEvent.entityId` / `songId` 历史上存过两种形态：
 *   • 裸 rawId — 当前所有写入路径（`YoinRepository.recordAlbumVisit` /
 *     `recordArtistVisit` 等）统一写入这个形态
 *   • 带 provider 前缀的 MediaId 字符串（形如 `"spotify:xxxxxx"`）— 来自
 *     老版本或某些 Subsonic 路径的遗留
 *
 * Home 聚合（去重 + MediaId 构造）必须先 normalize 到纯 rawId，否则:
 *   1. 两种格式的同一实体会被 `distinctBy` 当成不同 key，导致同一张专辑
 *      在 Activities 列表里出现两次
 *   2. 拼 `"${activity.provider}:$entityId"` 时如果 entityId 已经含前缀，
 *      就会得到 `"spotify:spotify:xxx"` 被 Spotify API 当成 rawId 塞进
 *      `/v1/albums/...` 返回 400
 * 只剥本 provider 的前缀：Apple Music 资料库 rawId 自带冒号（`library:l.xxx`）。
 */
private fun activityEntityRawId(activity: ActivityEvent, raw: String): String =
    MediaId.storedRawId(activity.provider, raw)

private fun homeActivityDedupKey(activity: ActivityEvent): String {
    val canonicalEntityId = when (activity.entityType) {
        ActivityEntityType.SONG.name ->
            activityEntityRawId(activity, activity.songId ?: activity.entityId)
        else -> activityEntityRawId(activity, activity.entityId)
    }
    return "${activity.entityType}:$canonicalEntityId"
}

private fun buildActivityEntries(
    activities: List<ActivityEvent>,
    buildCoverArtUrl: (String) -> String,
    // 6 = the phone bento's historical cap (hero + 3 supporting from the top
    // 6); the unit bento (feed units ≥ 3) asks for [ActivityBentoUnitsMaxEntries].
    // The default keeps the phone pipeline byte-identical.
    limit: Int = 6,
): List<HomeMomentEntry> = selectHomeActivities(activities).take(limit).map { activity ->
    val stableId = "activity:${activity.id}:${activity.entityType}:${activity.entityId}:${activity.actionType}"
    val rawEntityId = activityEntityRawId(activity, activity.entityId)
    val entityMediaId = "${activity.provider}:$rawEntityId"
    val target: HomeEntryTarget = when (activity.entityType) {
        ActivityEntityType.ALBUM.name -> HomeEntryTarget.Album(entityMediaId, stableId)
        ActivityEntityType.ARTIST.name -> HomeEntryTarget.Artist(entityMediaId)
        ActivityEntityType.PLAYLIST.name -> HomeEntryTarget.Playlist(entityMediaId)
        else -> HomeEntryTarget.SongTarget(activity.asSong())
    }
    HomeMomentEntry(
        stableId = stableId,
        entityType = activity.entityType,
        title = activity.title,
        subtitle = activity.subtitle.ifBlank {
            when (activity.entityType) {
                ActivityEntityType.ARTIST.name -> "Artist"
                else -> "Recently active"
            }
        },
        typeLabel = activityTypeLabel(activity.entityType),
        timeAgo = formatTimeAgo(activity.timestamp),
        coverArtUrl = buildActivityCoverArtUrl(activity, buildCoverArtUrl),
        target = target,
    )
}

private fun ActivityEvent.asSong(): Track = Track(
    id = MediaId(provider, songId ?: entityId),
    title = title,
    artist = subtitle,
    artistId = artistId?.takeIf { !it.isNullOrBlank() }?.let { MediaId(provider, it) },
    album = null,
    albumId = albumId.takeIf { !it.isNullOrBlank() }?.let { MediaId(provider, it) },
    // Reconstitute the stored key into the right CoverRef variant. URLs round-
    // trip as Url (Spotify), everything else as SourceRelative (Subsonic).
    coverArt = CoverRef.fromStorageKey(coverArtId),
    durationSec = null,
    trackNumber = null,
    year = null,
    genre = null,
    userRating = null,
)

/**
 * Stored `coverArtId` is a storage-key string: either a direct URL
 * (Spotify) or a Subsonic raw id. Direct URLs bypass the Subsonic resolver.
 * The fallback cascade (coverArtId → album entityId → albumId) only makes
 * sense on Subsonic; Spotify provider rows without a storage key have no
 * useful id to hand to `buildCoverArtUrl`.
 */
private fun buildActivityCoverArtUrl(
    activity: ActivityEvent,
    buildCoverArtUrl: (String) -> String,
): String? {
    val key = activity.coverArtId
        ?: activity.entityId.takeIf {
            activity.entityType == ActivityEntityType.ALBUM.name &&
                activity.provider == MediaId.PROVIDER_SUBSONIC
        }
        ?: activity.albumId?.takeIf {
            it.isNotBlank() && activity.provider == MediaId.PROVIDER_SUBSONIC
        }
        ?: return null

    return when (val ref = CoverRef.fromStorageKey(key)) {
        is CoverRef.Url -> ref.url
        is CoverRef.SourceRelative -> buildCoverArtUrl(ref.coverArtId).takeIf { it.isNotBlank() }
        null -> null
    }
}

private fun resolveHomeCoverArtUrl(
    ref: CoverRef?,
    buildCoverArtUrl: (String) -> String,
): String? = when (ref) {
    null -> null
    is CoverRef.Url -> ref.url
    is CoverRef.SourceRelative -> buildCoverArtUrl(ref.coverArtId)
}

private fun activityTypeLabel(entityType: String): String = when (entityType) {
    ActivityEntityType.ALBUM.name -> "Album"
    ActivityEntityType.ARTIST.name -> "Artist"
    ActivityEntityType.PLAYLIST.name -> "Playlist"
    else -> "Track"
}

private fun LazyListState.isAtTop(): Boolean =
    firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0

private fun formatTimeAgo(timestampMillis: Long): String {
    val diff = System.currentTimeMillis() - timestampMillis
    val minutes = diff / 60_000L
    val hours = minutes / 60L
    val days = hours / 24L
    return when {
        minutes < 1L -> "just now"
        minutes < 60L -> "${minutes}m ago"
        hours < 24L -> "${hours}h ago"
        days < 7L -> "${days}d ago"
        else -> "${days / 7}w ago"
    }
}
