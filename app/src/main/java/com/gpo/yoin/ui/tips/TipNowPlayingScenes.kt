package com.gpo.yoin.ui.tips

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import com.gpo.yoin.ui.nowplaying.DotLayers
import com.gpo.yoin.ui.nowplaying.EdgeBandState
import com.gpo.yoin.ui.nowplaying.StageColumnPose
import com.gpo.yoin.ui.nowplaying.drawEdgeBand
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.player.PlayMode
import com.gpo.yoin.symbols.rememberFavoriteSymbolPainter
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.LyricsDisplay
import com.gpo.yoin.ui.detail.TrackMenuGroup
import com.gpo.yoin.ui.detail.TrackMenuQuickAction
import com.gpo.yoin.ui.detail.TrackMenuRow
import com.gpo.yoin.ui.detail.TrackMenuSheetBody
import com.gpo.yoin.ui.detail.TrackMenuToggleRow
import com.gpo.yoin.ui.nowplaying.AlbumCover
import com.gpo.yoin.ui.nowplaying.BottomPills
import com.gpo.yoin.ui.nowplaying.CompactBottomHero
import com.gpo.yoin.ui.nowplaying.FavoriteButton
import com.gpo.yoin.ui.nowplaying.LyricLine
import com.gpo.yoin.ui.nowplaying.NowPlayingDetailPage
import com.gpo.yoin.ui.nowplaying.NowPlayingRatingBar
import com.gpo.yoin.ui.nowplaying.LyricFrame
import com.gpo.yoin.ui.nowplaying.OneLineLyricRow
import com.gpo.yoin.ui.nowplaying.OneLineLyricRowHeight
import com.gpo.yoin.ui.nowplaying.NowPlayingStageMode
import com.gpo.yoin.ui.nowplaying.NowPlayingUiState
import com.gpo.yoin.ui.nowplaying.PlayModeSplitHoldMs
import com.gpo.yoin.ui.nowplaying.PlaybackControls
import com.gpo.yoin.ui.nowplaying.StageTabs
import com.gpo.yoin.ui.nowplaying.StageTopBar
import com.gpo.yoin.ui.nowplaying.rememberStageColumnMetrics
import com.gpo.yoin.ui.nowplaying.resolveStageColumnBudget
import com.gpo.yoin.ui.nowplaying.stageColumnBottomInset
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Now Playing on the demo phone: the real single-column player — top bar,
 * cover, rating bar and heart, text tabs, lyrics, transport with the wave
 * progress bar and its note anchors, the hero title and the pills — at the
 * slot heights the real height budget gives a phone of this size. Its sheet
 * is the real song sheet's body in a sheet of the phone's own.
 */

/** Long-press the cover → the song sheet; with [keepScreenOn], switch Keep screen on in it. */
@Composable
internal fun CoverMenuDemo(playing: Boolean, keepScreenOn: Boolean) {
    var open by remember { mutableStateOf(false) }
    var awake by remember { mutableStateOf(false) }
    val coverSource = remember { MutableInteractionSource() }
    val position by rememberDemoPlayhead(playing)
    val sheetScroll = rememberScrollState()
    DemoPhone(playing = playing, script = {
        open = false
        awake = false
        sheetScroll.scrollTo(0)
        delay(300)
        moveTo("cover", fy = 0.45f)
        hold(pressed = coverSource.pressedAt()) { open = true }
        if (keepScreenOn) {
            delay(500)
            // The sheet is taller than this phone: up to its last group first.
            moveTo(0.5f, 0.8f)
            coroutineScope {
                launch { sheetScroll.animateScrollTo(sheetScroll.maxValue, tween(700)) }
                drag(0.5f, 0.45f, ms = 700) {}
            }
            delay(300)
            moveTo("keep", fx = 0.86f)
            tap { awake = !awake }
            delay(1_200)
        } else {
            lift()
            delay(1_800)
        }
        moveTo("cover", fy = 0.3f)
        tap { open = false }
        delay(500)
    }) {
        DemoNowPlaying(
            playing = playing,
            positionMs = { position },
            coverSource = coverSource,
            onCoverLongPress = { open = true },
        )
        DemoSongSheet(
            open = open,
            onDismiss = { open = false },
            track = DemoNowPlayingTrack(),
            coverUrl = demoCoverUrl(DemoAlbums[0]),
            groups = demoSongSheetRows(withGoToAlbum = true, nowPlaying = true),
            quickActions = demoSongSheetQuickActions(nowPlaying = true),
            scrollState = sheetScroll,
            playerSection = {
                TrackMenuGroup(count = 1) { _, shape ->
                    Box(modifier = Modifier.demoTarget("keep")) {
                        TrackMenuToggleRow(
                            label = stringResource(R.string.np_keep_screen_on),
                            icon = YoinSymbols.LightMode,
                            checked = awake,
                            onCheckedChange = { awake = it },
                            shape = shape,
                        )
                    }
                }
            },
        )
    }
}

/**
 * Tap the cover → Focus, laid out by the real height budget's focus pose: the
 * top bar, the rating column, the tabs and the pills step away, the cover
 * grows and the column narrows to it, centred, while the edge band bursts out
 * around it; tap it again and the rest pose comes back.
 */
@Composable
internal fun FocusCoverDemo(playing: Boolean) {
    var focused by remember { mutableStateOf(false) }
    val coverSource = remember { MutableInteractionSource() }
    val position by rememberDemoPlayhead(playing)
    DemoPhone(playing = playing, script = {
        focused = false
        delay(600)
        moveTo("cover", fy = 0.45f)
        tap { focused = true }
        lift()
        delay(2_400)
        moveTo("cover", fy = 0.45f)
        tap { focused = false }
        lift()
        delay(1_400)
    }) {
        DemoNowPlaying(
            playing = playing,
            positionMs = { position },
            coverSource = coverSource,
            focused = focused,
            onCoverClick = { focused = !focused },
        )
    }
}

/** The real play-mode button in its place in the transport: tap through the modes, hold to split. */
@Composable
internal fun PlayModesDemo(playing: Boolean) {
    var mode by remember { mutableStateOf(PlayMode.RepeatAll) }
    val split = remember { mutableStateOf(false) }
    val position by rememberDemoPlayhead(playing)
    DemoPhone(playing = playing, script = {
        split.value = false
        mode = PlayMode.RepeatAll
        delay(300)
        moveTo("mode")
        tap { mode = mode.next() }
        delay(600)
        tap { mode = mode.next() }
        delay(600)
        hold { split.value = true }
        delay(250)
        moveTo("mode", fx = 0.27f)
        tap { mode = mode.withShuffle(!mode.shuffle) }
        delay(500)
        moveTo("mode", fx = 0.73f)
        tap { mode = mode.nextRepeat() }
        lift()
        delay(PlayModeSplitHoldMs + 600)
    }) {
        DemoNowPlaying(
            playing = playing,
            positionMs = { position },
            playMode = mode,
            onSetPlayMode = { mode = it },
            playModeSplit = split,
        )
    }
}

/**
 * Write keeps the second it was tapped at as the note's anchor; scrubbing the
 * wave progress bar back across it, the bar catches there and its bubble reads
 * "Note" (WaveProgressBar's note anchors).
 */
@Composable
internal fun NoteMomentsDemo(playing: Boolean) {
    val position by rememberDemoPlayhead(playing)
    val anchors = remember { mutableStateListOf<Long>() }
    var scrub by remember { mutableStateOf<Float?>(null) }
    DemoPhone(playing = playing, script = {
        anchors.clear()
        scrub = null
        delay(900)
        moveTo("pills", fx = WritePillFx)
        tap { anchors += position }
        delay(1_600)
        val anchor = anchors.last().toFloat() / DemoDurationMs
        val from = anchor + 0.14f
        val to = anchor - 0.05f
        moveTo(barX(from), BarY)
        scrub = from
        drag(barX(anchor), BarY, ms = 1_100) { p -> scrub = from + (anchor - from) * p }
        // On the anchor the bar holds and its bubble reads Note.
        delay(1_100)
        drag(barX(to), BarY, ms = 500) { p -> scrub = anchor + (to - anchor) * p }
        delay(200)
        scrub = null
        lift()
        delay(1_600)
    }) {
        DemoNowPlaying(
            playing = playing,
            positionMs = { position },
            noteAnchorsMs = anchors.sorted(),
            onWrite = { anchors += position },
            actedScrub = { scrub },
        )
    }
}

/** The edge band's dot layers, as Now Playing's background keeps them. */
private const val DemoEdgeBandLayers = 12

private fun lerpDp(start: Dp, end: Dp, fraction: Float): Dp = start + (end - start) * fraction.coerceIn(0f, 1f)

/** Every slot of the column between [rest] and [focus]; the lyric frame switches halfway. */
private fun lerpPose(rest: StageColumnPose, focus: StageColumnPose, f: Float): StageColumnPose = StageColumnPose(
    topBar = lerpDp(rest.topBar, focus.topBar, f),
    cover = lerpDp(rest.cover, focus.cover, f),
    coverGap = lerpDp(rest.coverGap, focus.coverGap, f),
    tabs = lerpDp(rest.tabs, focus.tabs, f),
    tabGap = lerpDp(rest.tabGap, focus.tabGap, f),
    lyricSlot = lerpDp(rest.lyricSlot, focus.lyricSlot, f),
    lyricFrame = if (f < 0.5f) rest.lyricFrame else focus.lyricFrame,
    controls = lerpDp(rest.controls, focus.controls, f),
    hero = lerpDp(rest.hero, focus.hero, f),
    accessory = lerpDp(rest.accessory, focus.accessory, f),
    lower = rest.lower + (focus.lower - rest.lower) * f,
    upper = rest.upper + (focus.upper - rest.upper) * f,
)

/** The phone's x for [fraction] along the wave progress bar. */
private fun barX(fraction: Float): Float = BarStartX + (BarEndX - BarStartX) * fraction.coerceIn(0f, 1f)

/** The song the demo plays. */
@Composable
private fun DemoNowPlayingTrack(): Track = demoTrack(0, DemoAlbums[0], demoCoverUrl(DemoAlbums[0]))

/** The demo's playhead: moves while [playing], a quarter second at a time like the player's. */
@Composable
private fun rememberDemoPlayhead(playing: Boolean): State<Long> {
    val position = remember { mutableLongStateOf(DemoStartMs) }
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        while (true) {
            delay(PlayheadTickMs)
            position.longValue = (position.longValue + PlayheadTickMs) % DemoDurationMs
        }
    }
    return position
}

/** Press / release on [this] as the demo touch goes down and up (the cover's press dip). */
private fun MutableInteractionSource.pressedAt(): (Boolean) -> Unit {
    var press: PressInteraction.Press? = null
    return { down ->
        if (down) {
            press = PressInteraction.Press(Offset.Zero).also { tryEmit(it) }
        } else {
            press?.let { tryEmit(PressInteraction.Release(it)) }
            press = null
        }
    }
}

/**
 * CompactPlayingContent at rest, for a phone of the demo screen's size:
 * the same pieces in the same slots, with the heights the real budget gives.
 */
@Composable
private fun DemoNowPlaying(
    playing: Boolean,
    positionMs: () -> Long,
    playMode: PlayMode = PlayMode.RepeatAll,
    onSetPlayMode: (PlayMode) -> Unit = {},
    playModeSplit: MutableState<Boolean>? = null,
    noteAnchorsMs: List<Long> = emptyList(),
    coverSource: MutableInteractionSource = remember { MutableInteractionSource() },
    onCoverLongPress: (() -> Unit)? = null,
    onWrite: () -> Unit = {},
    actedScrub: (() -> Float?)? = null,
    focused: Boolean = false,
    onCoverClick: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    // Focus as Now Playing does it (PR #120): the column moves from the
    // budget's rest pose to its focus pose on the spatial spring — the top
    // bar, the rating column, the tabs and the pills step away, the cover
    // grows and the column narrows to it, centred — and the retreating
    // pieces fade on the effects spring. The edge band bursts out around it.
    val focus by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = YoinMotion.slowSpatialSpec(role = YoinMotionRole.Expressive),
        label = "tipFocus",
    )
    val chrome by animateFloatAsState(
        targetValue = if (focused) 0f else 1f,
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "tipFocusChrome",
    )
    val band = remember { EdgeBandState() }
    val bandDots = remember { DotLayers(DemoEdgeBandLayers) }
    val clock = remember { mutableFloatStateOf(0f) }
    // The band's clock (ms) runs only while focused; entering focus bursts it.
    LaunchedEffect(focused) {
        if (!focused) return@LaunchedEffect
        band.enter(clock.floatValue)
        val base = clock.floatValue
        val start = withFrameMillis { it }
        while (true) {
            withFrameMillis { t -> clock.floatValue = base + (t - start) }
        }
    }
    var coverBounds by remember { mutableStateOf(Rect.Zero) }
    var stageOrigin by remember { mutableStateOf(Offset.Zero) }
    val album = DemoAlbums[0]
    val cover = demoCoverUrl(album)
    val lyrics = demoLyrics()
    var starred by remember { mutableStateOf(true) }
    val state = remember(playing, playMode, cover, lyrics, starred) {
        NowPlayingUiState.Playing(
            songTitle = DemoTrackTitles[0],
            artist = album.artist,
            albumName = album.name,
            coverArtUrl = cover,
            isPlaying = playing,
            durationMs = DemoDurationMs,
            songId = "tip-song",
            rating = 8.2f,
            isStarred = starred,
            lyrics = lyrics,
            showLyricsTranslation = false,
            lyricsActionInFlight = null,
            lyricsLoading = false,
            queue = emptyList(),
            currentQueueIndex = 0,
            playMode = playMode,
            albumId = null,
            artistId = null,
            activityContext = ActivityContext.Album(albumId = "tip-album", albumName = album.name),
        )
    }
    val bars = WindowInsets.systemBars.asPaddingValues()
    val direction = LocalLayoutDirection.current
    val horizontalPadding = 24.dp
    val heroSpec = YoinMotion.slowSpatialSpec<Rect>(
        role = YoinMotionRole.Expressive,
        expressiveScheme = MaterialTheme.motionScheme,
    )
    val playSource = remember { MutableInteractionSource() }
    val nextSource = remember { MutableInteractionSource() }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(colors.surfaceContainer, colors.surface)))
            .padding(
                PaddingValues(
                    start = bars.calculateStartPadding(direction),
                    top = bars.calculateTopPadding(),
                    end = bars.calculateEndPadding(direction),
                    bottom = stageColumnBottomInset(bars.calculateBottomPadding()),
                ),
            ),
    ) {
        val metrics = rememberStageColumnMetrics(maxWidth, DemoControlSize, castVisible = false)
        val budget = remember(maxWidth, maxHeight, metrics) {
            resolveStageColumnBudget(maxWidth, maxHeight, metrics)
        }
        val pose = lerpPose(budget.rest, budget.focus ?: budget.rest, focus)
        val liveWidth = lerpDp(maxWidth, budget.focusStageWidth, focus)
        val palette = remember(colors) {
            intArrayOf(colors.primary.toArgb(), colors.tertiary.toArgb(), colors.secondary.toArgb(), colors.primaryContainer.toArgb())
        }
        val dark = colors.surface.luminance() < 0.5f
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .onGloballyPositioned { stageOrigin = it.positionInRoot() },
        ) {
            val t = clock.floatValue
            if (focus > 0.01f && band.alive(t)) {
                drawEdgeBand(
                    state = band,
                    dots = bandDots,
                    nowMs = t,
                    focus = focus,
                    playing = if (playing) 1f else 0f,
                    cover = coverBounds.translate(-stageOrigin),
                    lyrics = null,
                    palette = palette,
                    primary = colors.primary,
                    dark = dark,
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(liveWidth)
                .offset(x = (maxWidth - liveWidth) / 2),
        ) {
            StageTopBar(
                state = state,
                stageMode = NowPlayingStageMode.Compact,
                detailProgress = 0f,
                dismissFraction = { 0f },
                onBack = {},
                onEnterImmersive = {},
                onAlbumClick = {},
                onArtistClick = {},
                onPlaylistClick = {},
                height = pose.topBar,
                modifier = Modifier
                    .padding(horizontal = horizontalPadding)
                    .graphicsLayer { alpha = chrome },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(pose.cover)
                    .padding(horizontal = horizontalPadding),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .onGloballyPositioned { coverBounds = it.boundsInRoot() },
                ) {
                    AlbumCover(
                        songId = state.songId,
                        coverArtUrl = state.coverArtUrl,
                        interactionSource = coverSource,
                        modifier = Modifier
                            .fillMaxHeight()
                            .demoTarget("cover")
                            .then(
                                if (onCoverLongPress != null) {
                                    Modifier.demoLongPress(coverSource, onCoverLongPress)
                                } else {
                                    Modifier
                                },
                            )
                            .then(
                                if (onCoverClick != null) {
                                    Modifier.clickable(
                                        interactionSource = coverSource,
                                        indication = null,
                                        onClick = onCoverClick,
                                    )
                                } else {
                                    Modifier
                                },
                            ),
                    )
                }
                Spacer(modifier = Modifier.width(12.dp * (1f - focus)))
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .width(56.dp * (1f - focus))
                        .fillMaxHeight()
                        .clipToBounds()
                        .wrapContentWidth(Alignment.Start, unbounded = true)
                        .width(56.dp)
                        .graphicsLayer { alpha = chrome },
                ) {
                    NowPlayingRatingBar(
                        songId = state.songId,
                        rating = state.rating,
                        onRatingCommit = {},
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    FavoriteButton(isStarred = starred, onClick = { starred = !starred })
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
            Spacer(modifier = Modifier.height(pose.coverGap))
            if (pose.tabs > 0.dp) StageTabs(
                selected = NowPlayingDetailPage.Lyrics,
                detailProgress = 0f,
                height = pose.tabs,
                onSelect = {},
                modifier = Modifier
                    .padding(horizontal = horizontalPadding)
                    .graphicsLayer { alpha = chrome },
            )
            Spacer(modifier = Modifier.height(pose.tabGap))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = horizontalPadding),
            ) {
                if (pose.lyricFrame == LyricFrame.OneLine) {
                    // 16:9: one tappable current line, as the real column folds to.
                    OneLineLyricRow(
                        state = state,
                        positionMs = positionMs,
                        onExpand = {},
                        onClaimIdleHint = { false },
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .fillMaxWidth()
                            .heightIn(max = OneLineLyricRowHeight),
                    )
                } else {
                    LyricsDisplay(
                        lyrics = state.lyrics,
                        positionMs = positionMs,
                        trackKey = state.songId,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Box(modifier = Modifier.fillMaxWidth().height(pose.controls)) {
                val position = positionMs()
                PlaybackControls(
                    isPlaying = playing,
                    onTogglePlayPause = {},
                    onSkipNext = {},
                    onSkipPrevious = {},
                    positionMs = position,
                    durationMs = DemoDurationMs,
                    progress = position.toFloat() / DemoDurationMs,
                    buffered = 1f,
                    onSeek = {},
                    playInteractionSource = playSource,
                    nextInteractionSource = nextSource,
                    playPressed = false,
                    playMode = playMode,
                    onSetPlayMode = onSetPlayMode,
                    controlSize = DemoControlSize,
                    noteAnchorsMs = noteAnchorsMs,
                    live = playing,
                    playModeSplitState = playModeSplit,
                    playModeModifier = Modifier.demoTarget("mode"),
                    actedScrub = actedScrub,
                    modifier = Modifier.padding(horizontal = horizontalPadding),
                )
            }
            CompactBottomHero(
                state = state,
                heroBoundsSpec = heroSpec,
                titleStretchScale = if (playing) 1.03f else 0.97f,
                artistStretchScale = if (playing) 1.02f else 0.98f,
                titleRouteInteraction = null,
                artistRouteInteraction = null,
                sharedTransitionScope = null,
                animatedVisibilityScope = null,
                height = pose.hero,
                alpha = 1f,
                oneLine = budget.heroOneLine,
                modifier = Modifier.padding(horizontal = horizontalPadding),
            )
            Box(
                modifier = Modifier.fillMaxWidth().height(pose.accessory),
                contentAlignment = Alignment.BottomStart,
            ) {
                BottomPills(
                    onQueueClick = {},
                    onDevicesClick = {},
                    onWriteClick = onWrite,
                    modifier = Modifier
                        .padding(horizontal = horizontalPadding)
                        .graphicsLayer { alpha = chrome }
                        .demoTarget("pills"),
                )
            }
        }
    }
}

/** A song sheet as Now Playing and an album page raise it: the real sheet body in a bottom sheet on the phone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BoxScope.DemoSongSheet(
    open: Boolean,
    onDismiss: () -> Unit,
    track: Track,
    coverUrl: String?,
    groups: List<List<TrackMenuRow>>,
    quickActions: List<TrackMenuQuickAction> = emptyList(),
    playerSection: (@Composable () -> Unit)? = null,
    scrollState: ScrollState = rememberScrollState(),
    rowModifier: (TrackMenuRow) -> Modifier = { Modifier },
    onRowClick: (TrackMenuRow) -> Unit = {},
) {
    AnimatedVisibility(
        visible = open,
        enter = YoinMotion.fadeIn(YoinMotionRole.Standard),
        exit = YoinMotion.fadeOut(YoinMotionRole.Standard),
        modifier = Modifier.matchParentSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BottomSheetDefaults.ScrimColor)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
    }
    AnimatedVisibility(
        visible = open,
        enter = YoinMotion.slideInVertically(YoinMotionRole.Expressive) { it },
        exit = YoinMotion.slideOutVertically(YoinMotionRole.Standard) { it },
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        Surface(
            shape = BottomSheetDefaults.ExpandedShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            // M3's modal sheet stops under the status bar.
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(top = SheetTopGap),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BottomSheetDefaults.DragHandle()
                val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                TrackMenuSheetBody(
                    track = track,
                    coverUrl = coverUrl,
                    groups = groups,
                    quickActions = quickActions,
                    onQuickActionClick = { if (!it.keepsOpen) onDismiss() },
                    onRowClick = { row ->
                        onRowClick(row)
                        onDismiss()
                    },
                    bottomPadding = 16.dp + navBottom,
                    playerSection = playerSection,
                    scrollState = scrollState,
                    rowModifier = rowModifier,
                )
            }
        }
    }
}

/**
 * The sheet's top row for a song, as TrackMenuSheet builds it: Play next / Add to
 * queue / Favorite / Share, or for the playing song Favorite / Add to playlist / Share.
 */
@Composable
internal fun demoSongSheetQuickActions(nowPlaying: Boolean): List<TrackMenuQuickAction> {
    val playNext = stringResource(R.string.detail_menu_play_next)
    val addToQueue = stringResource(R.string.detail_menu_add_to_queue)
    val addToPlaylist = stringResource(R.string.detail_menu_add_to_playlist)
    val favorite = stringResource(R.string.track_menu_favorite)
    val share = stringResource(R.string.track_menu_share)
    val favoriteAction = TrackMenuQuickAction(favorite, { rememberFavoriteSymbolPainter(favorite = false) }, keepsOpen = true) {}
    val shareAction = TrackMenuQuickAction(share, { rememberVectorPainter(YoinSymbols.Share) }, keepsOpen = false) {}
    return remember(nowPlaying, playNext) {
        if (nowPlaying) {
            listOf(
                favoriteAction,
                TrackMenuQuickAction(addToPlaylist, { rememberVectorPainter(YoinSymbols.Playlist) }, keepsOpen = false) {},
                shareAction,
            )
        } else {
            listOf(
                TrackMenuQuickAction(playNext, { rememberVectorPainter(YoinSymbols.SkipNext) }, keepsOpen = true) {},
                TrackMenuQuickAction(addToQueue, { rememberVectorPainter(YoinSymbols.Queue) }, keepsOpen = true) {},
                favoriteAction,
                shareAction,
            )
        }
    }
}

/** The sheet's list rows for a song, as TrackMenuSheet lists them (Go to album only away from the album). */
@Composable
internal fun demoSongSheetRows(withGoToAlbum: Boolean, nowPlaying: Boolean = false): List<List<TrackMenuRow>> {
    val addToPlaylist = stringResource(R.string.detail_menu_add_to_playlist)
    val goToAlbum = stringResource(R.string.track_menu_go_to_album)
    val scrapbook = stringResource(R.string.track_menu_view_in_scrapbook)
    val goToArtist = stringResource(R.string.track_menu_go_to_artist)
    return remember(withGoToAlbum, nowPlaying, goToAlbum) {
        listOf(
            if (nowPlaying) emptyList() else listOf(TrackMenuRow(addToPlaylist, YoinSymbols.Playlist) {}),
            listOfNotNull(
                if (withGoToAlbum) TrackMenuRow(goToAlbum, YoinSymbols.Album) {} else null,
                TrackMenuRow(scrapbook, YoinSymbols.Note) {},
                TrackMenuRow(goToArtist, YoinSymbols.Artist) {},
            ),
        )
    }
}

@Composable
private fun demoLyrics(): List<LyricLine> = remember {
    listOf(
        LyricLine(startMs = 0, text = "Streetlights hum a slower song"),
        LyricLine(startMs = 52_000, text = "I keep the window open wide"),
        LyricLine(startMs = 60_000, text = "The harbour folds into the dark"),
        LyricLine(startMs = 68_000, text = "And every light is passing by"),
        LyricLine(startMs = 76_000, text = "Hold the evening, let it stay"),
        LyricLine(startMs = 84_000, text = "Slow light, slow light"),
        LyricLine(startMs = 92_000, text = "Carry me the long way home"),
        LyricLine(startMs = 100_000, text = "Over water, under stone"),
    )
}

private val DemoControlSize: Dp = 56.dp

/** Air between the status bar and a full-height sheet. */
private val SheetTopGap: Dp = 24.dp

/** Write's centre across the pill row (Queue, Devices, Write, labelled, from the start). */
private const val WritePillFx = 0.72f

/** Where the wave progress bar runs on the phone (fractions of it), beside the previous button. */
private const val BarStartX = 0.255f
private const val BarEndX = 0.93f
private const val BarY = 0.79f

private const val DemoDurationMs = 214_000L
private const val DemoStartMs = 58_000L
private const val PlayheadTickMs = 250L
