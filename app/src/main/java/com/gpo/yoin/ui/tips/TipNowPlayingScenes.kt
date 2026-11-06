package com.gpo.yoin.ui.tips

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
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
 * Tap the cover → Focus: the top bar, the rating column, the tabs and the
 * pills step aside and the cover grows; tap it again and they come back.
 * (The real Focus is Now Playing's immersive stage; here the same pieces
 * retreat on the same springs.)
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

/** How much the demo cover grows in Focus: into the rating column it frees. */
private const val FocusCoverScale = 1.12f

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
    // Focus: the chrome fades on the effects spring, the cover grows on the spatial one.
    val chrome by animateFloatAsState(
        targetValue = if (focused) 0f else 1f,
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "tipFocusChrome",
    )
    val coverScale by animateFloatAsState(
        targetValue = if (focused) FocusCoverScale else 1f,
        animationSpec = YoinMotion.defaultSpatialSpec(),
        label = "tipFocusCover",
    )
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
        val pose = budget.rest
        Column(modifier = Modifier.fillMaxSize()) {
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
                        .zIndex(1f)
                        .graphicsLayer {
                            scaleX = coverScale
                            scaleY = coverScale
                            transformOrigin = TransformOrigin(0f, 0f)
                        },
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
                Spacer(modifier = Modifier.width(12.dp))
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .width(56.dp)
                        .fillMaxHeight()
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
