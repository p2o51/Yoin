package com.gpo.yoin.ui.nowplaying
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.experience.voteHighFrameRate

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.togetherWith
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.automirrored.rounded.StickyNote2
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupScope
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.gpo.yoin.data.model.YoinDevice
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.player.CastState
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.ui.component.NoteSortMode
import com.gpo.yoin.ui.component.CastButton
import com.gpo.yoin.ui.component.DevicesSheet
import com.gpo.yoin.ui.component.WriteNoteSheet
import com.gpo.yoin.ui.component.edgeFade
import com.gpo.yoin.ui.component.ExpressiveMediaArtwork
import com.gpo.yoin.ui.component.horizontalFadeMask
import com.gpo.yoin.ui.component.LyricsDisplay
import com.gpo.yoin.ui.component.noRippleClickable
import com.gpo.yoin.ui.component.SongInfoDisplay
import com.gpo.yoin.ui.nowplaying.compact.NoteCompactPane
import com.gpo.yoin.ui.component.QueueSheet
import com.gpo.yoin.ui.component.RatingSlider
import com.gpo.yoin.ui.component.WaveProgressBar
import com.gpo.yoin.ui.component.minimumTouchTarget
import com.gpo.yoin.ui.navigation.rememberActiveOnlySharedContentConfig
import com.gpo.yoin.ui.navigation.nowPlayingCoverSharedKey
import com.gpo.yoin.ui.experience.LayoutMode
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.LocalYoinWindowInfo
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.experience.ReportMotionPressure
import com.gpo.yoin.ui.theme.ContinuousRoundedCornerShape
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinArtworkShapes
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotionSpeed
import com.gpo.yoin.ui.theme.YoinTheme
import com.gpo.yoin.ui.theme.withTabularFigures
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Full-screen Now Playing overlay.
 *
 * All state is hoisted — this composable is purely presentational.
 * Accepts optional shared-transition scopes for the cover-art / title / artist morph.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun NowPlayingScreen(
    uiState: NowPlayingUiState,
    // 4Hz playhead readers. Lambdas (not values) on purpose: the tick is read
    // only inside the leaves that render position (wave progress bar, time
    // labels, lyrics highlight), so a position tick never recomposes this
    // screen or the layout bodies below it.
    positionMs: () -> Long,
    bufferedMs: () -> Long,
    // True while the audio session is producing FFT frames. Replaces the raw
    // VisualizerData param — the frames themselves updated 10–30Hz and their
    // ONLY consumer here was this presence check.
    hasAudioSpectrum: Boolean,
    // Predictive-back collapse preview: the stage CONTENT recedes to this scale
    // over the aurora (full-screen on the outer Box), so the peek never reveals
    // the shell behind. 1f = inert.
    contentScale: Float = 1f,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeek: (Float) -> Unit,
    onSeekToMs: (Long) -> Unit = {},
    lyricsSearchState: LyricsSearchState = LyricsSearchState(),
    onOpenLyricsSearch: () -> Unit = {},
    onLyricsSearchQueryChange: (String) -> Unit = {},
    onSearchLyrics: (String) -> Unit = {},
    onApplyLyricsSearchResult: (LyricsSearchResultUi) -> Unit = {},
    onDismissLyricsSearch: () -> Unit = {},
    onTranslateLyrics: () -> Unit = {},
    onApplyLyrics: (String) -> Unit = {},
    onRatingChange: (Float) -> Unit,
    onToggleFavorite: () -> Unit,
    onAddCurrentToPlaylist: () -> Unit,
    onSkipToQueueItem: (Int) -> Unit,
    onToggleShuffle: () -> Unit = {},
    onAlbumClick: (String) -> Unit = {},
    onArtistClick: (String) -> Unit = {},
    onPlaylistClick: (String) -> Unit = {},
    onDismiss: () -> Unit = {},
    dismissFraction: () -> Float = { 0f },
    aboutUiState: AboutUiState = AboutUiState.Idle,
    onRetryFetchSongInfo: () -> Unit = {},
    askState: AskBarState = AskBarState.Idle,
    onAboutOpened: () -> Unit = {},
    onAskQuestion: (String) -> Unit = {},
    onAskBarFocused: () -> Unit = {},
    onAskBarCollapseRequested: () -> Unit = {},
    onDismissAskError: () -> Unit = {},
    stageMode: NowPlayingStageMode = NowPlayingStageMode.Compact,
    stageProgress: NowPlayingStageProgress? = null,
    detailPage: NowPlayingDetailPage = NowPlayingDetailPage.Lyrics,
    onStageModeChange: (NowPlayingStageMode) -> Unit = {},
    onStageBack: () -> Boolean = { false },
    onDetailPageChange: (NowPlayingDetailPage) -> Unit = {},
    notesState: List<SongNote> = emptyList(),
    onSaveNote: (String, Long?) -> Unit = { _, _ -> },
    onDeleteNote: (String) -> Unit = {},
    devicesState: DevicesSheetState = DevicesSheetState(),
    onRefreshDevices: () -> Unit = {},
    onSelectDevice: (YoinDevice) -> Unit = {},
    castState: CastState = CastState.NotAvailable,
    onCastClick: () -> Unit = {},
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier,
) {
    // Note ordering is a reading preference, not per-song state — held here
    // so the compact pane, expanded pane, and every window layout share one
    // choice. Saveable so rotation keeps it.
    var noteSortMode by rememberSaveable { mutableStateOf(NoteSortMode.Timeline) }
    val scheme = MaterialTheme.colorScheme
    val surfaceContainer = scheme.surfaceContainer
    val background = scheme.background

    // Reactions for the Now Playing background (see nowPlayingAuroraBackground):
    //  • Gemini thinking (long wait) → a slow aurora wash blooms while Loading.
    //  • play/pause + skip → a brief one-shot bloom; the trigger reads only the
    //    playing flag + song id so position ticks don't fire it. Skip also
    //    crossfades the whole palette via the theme.
    val auroraActive = askState is AskBarState.Loading
    val playingState = uiState as? NowPlayingUiState.Playing
    val pulseTrigger = playingState?.let { it.isPlaying to it.songId }
    val isPlayingNow = playingState?.isPlaying == true

    // The transport button (PLAY/PAUSE, deep in the shared PlaybackControls)
    // publishes its press state + centre here so the background can answer the
    // finger: gather while held, ripple/sink from the button on commit.
    val transportSignal = remember { NowPlayingTransportSignal() }

    ReportMotionPressure(
        tag = "now-playing",
        isHighPressure = uiState is NowPlayingUiState.Playing &&
            uiState.isPlaying &&
            hasAudioSpectrum,
    )

    ProvideYoinMotionRole(role = YoinMotionRole.Expressive) {
      CompositionLocalProvider(LocalNowPlayingTransportSignal provides transportSignal) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .nowPlayingAuroraBackground(
                    baseTop = surfaceContainer,
                    baseBottom = background,
                    auroraColors = listOf(
                        scheme.primary,
                        scheme.tertiary,
                        scheme.secondary,
                        scheme.primaryContainer,
                    ),
                    auroraActive = auroraActive,
                    playColor = scheme.primary,
                    pauseColor = scheme.tertiary,
                    pulseTrigger = pulseTrigger,
                    isPlaying = isPlayingNow,
                    pressActive = transportSignal.playHeld,
                    gatherFocalRoot = transportSignal.gatherAnchorRoot,
                    burstFocalRoot = transportSignal.burstFocalRoot,
                ),
        ) {
            when (uiState) {
                is NowPlayingUiState.Idle -> IdleContent()
                is NowPlayingUiState.Launching -> LaunchingContent(
                    state = uiState,
                    onDismiss = onDismiss,
                    dismissFraction = dismissFraction,
                )
                is NowPlayingUiState.ConnectError -> ConnectErrorContent(
                    state = uiState,
                    onDismiss = onDismiss,
                    dismissFraction = dismissFraction,
                )
                is NowPlayingUiState.Playing -> PlayingContent(
                    state = uiState,
                    positionMs = positionMs,
                    bufferedMs = bufferedMs,
                    onTogglePlayPause = onTogglePlayPause,
                    onSkipNext = onSkipNext,
                    onSkipPrevious = onSkipPrevious,
                    onSeek = onSeek,
                    onSeekToMs = onSeekToMs,
                    lyricsSearchState = lyricsSearchState,
                    onOpenLyricsSearch = onOpenLyricsSearch,
                    onLyricsSearchQueryChange = onLyricsSearchQueryChange,
                    onSearchLyrics = onSearchLyrics,
                    onApplyLyricsSearchResult = onApplyLyricsSearchResult,
                    onDismissLyricsSearch = onDismissLyricsSearch,
                    onTranslateLyrics = onTranslateLyrics,
                    onApplyLyrics = onApplyLyrics,
                    onRatingChange = onRatingChange,
                    onToggleFavorite = onToggleFavorite,
                    onAddCurrentToPlaylist = onAddCurrentToPlaylist,
                    onSkipToQueueItem = onSkipToQueueItem,
                    onToggleShuffle = onToggleShuffle,
                    onAlbumClick = onAlbumClick,
                    onArtistClick = onArtistClick,
                    onPlaylistClick = onPlaylistClick,
                    onDismiss = onDismiss,
                    dismissFraction = dismissFraction,
                    aboutUiState = aboutUiState,
                    onRetryFetchSongInfo = onRetryFetchSongInfo,
                    askState = askState,
                    onAboutOpened = onAboutOpened,
                    onAskQuestion = onAskQuestion,
                    onAskBarFocused = onAskBarFocused,
                    onAskBarCollapseRequested = onAskBarCollapseRequested,
                    onDismissAskError = onDismissAskError,
                    stageMode = stageMode,
                    stageProgress = stageProgress,
                    detailPage = detailPage,
                    onStageModeChange = onStageModeChange,
                    onStageBack = onStageBack,
                    onDetailPageChange = onDetailPageChange,
                    notesState = notesState,
                    onSaveNote = onSaveNote,
                    noteSortMode = noteSortMode,
                    onNoteSortModeChange = { noteSortMode = it },
                    onDeleteNote = onDeleteNote,
                    devicesState = devicesState,
                    onRefreshDevices = onRefreshDevices,
                    onSelectDevice = onSelectDevice,
                    castState = castState,
                    onCastClick = onCastClick,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    contentScale = contentScale,
                )
            }
        }
      }
    }
}

@Composable
private fun IdleContent(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Nothing playing",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Backend is still negotiating (Spotify App Remote most commonly). Show the
 * track the user tapped as "about to play", but do NOT render a playing
 * state — no progress, no spinning controls. Dismiss collapses Now Playing.
 */
@Composable
private fun LaunchingContent(
    state: NowPlayingUiState.Launching,
    onDismiss: () -> Unit,
    dismissFraction: () -> Float = { 0f },
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.Start),
        ) {
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = "Collapse",
                modifier = Modifier.graphicsLayer { rotationZ = 180f * dismissFraction() },
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Box(
            modifier = Modifier
                .size(240.dp)
                .clip(YoinArtworkShapes.NowPlayingCover)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            if (state.coverArtUrl != null) {
                AsyncImage(
                    model = state.coverArtUrl,
                    contentDescription = state.songTitle,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = state.songTitle,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = state.artist,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(32.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
            Text(
                text = state.hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Backend refused / lost the connection mid-launch. Show the failing track
 * with the user-facing error message. Shell snackbar also surfaces the
 * actionable recovery (open Settings / install Spotify / reconnect); this
 * screen just tells the user what they were trying to play and why it
 * didn't work.
 */
@Composable
private fun ConnectErrorContent(
    state: NowPlayingUiState.ConnectError,
    onDismiss: () -> Unit,
    dismissFraction: () -> Float = { 0f },
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.Start),
        ) {
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = "Collapse",
                modifier = Modifier.graphicsLayer { rotationZ = 180f * dismissFraction() },
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Box(
            modifier = Modifier
                .size(200.dp)
                .clip(YoinArtworkShapes.NowPlayingCover)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            if (state.coverArtUrl != null) {
                AsyncImage(
                    model = state.coverArtUrl,
                    contentDescription = state.songTitle,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(modifier = Modifier.height(28.dp))
        Text(
            text = state.songTitle,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = state.artist,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = state.message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

private data class NowPlayingPlaybackActions(
    val onTogglePlayPause: () -> Unit,
    val onSkipNext: () -> Unit,
    val onSkipPrevious: () -> Unit,
    val onSeek: (Float) -> Unit,
    val onToggleShuffle: () -> Unit,
)

private data class NowPlayingLyricsActions(
    val onSeekToMs: (Long) -> Unit,
    val onOpenLyricsSearch: () -> Unit,
    val onLyricsSearchQueryChange: (String) -> Unit,
    val onSearchLyrics: (String) -> Unit,
    val onApplyLyricsSearchResult: (LyricsSearchResultUi) -> Unit,
    val onDismissLyricsSearch: () -> Unit,
    val onTranslateLyrics: () -> Unit,
    val onApplyLyrics: (String) -> Unit,
)

private data class NowPlayingNavigationActions(
    val onAlbumClick: (String) -> Unit,
    val onArtistClick: (String) -> Unit,
    val onPlaylistClick: (String) -> Unit,
)

@OptIn(ExperimentalSharedTransitionApi::class)
/**
 * Render-target dispatch for the playing state. [LayoutMode] (orthogonal to
 * [NowPlayingStageMode]) selects WHICH content composable draws; the stage
 * state machine is untouched. Wide / Tabletop targets arrive in later phases —
 * until then every mode renders the unchanged [CompactPlayingContent].
 */
@Composable
private fun PlayingContent(
    state: NowPlayingUiState.Playing,
    // 4Hz playhead readers — threaded down untouched; only leaves invoke them.
    positionMs: () -> Long,
    bufferedMs: () -> Long,
    // Predictive-back collapse preview: the STAGE (cover / tabs / lyrics) recedes to
    // this scale while the top bar, controls, title/artist and pills stay fixed as a
    // stable frame. 1f = inert.
    contentScale: Float = 1f,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeek: (Float) -> Unit,
    onSeekToMs: (Long) -> Unit = {},
    lyricsSearchState: LyricsSearchState = LyricsSearchState(),
    onOpenLyricsSearch: () -> Unit = {},
    onLyricsSearchQueryChange: (String) -> Unit = {},
    onSearchLyrics: (String) -> Unit = {},
    onApplyLyricsSearchResult: (LyricsSearchResultUi) -> Unit = {},
    onDismissLyricsSearch: () -> Unit = {},
    onTranslateLyrics: () -> Unit = {},
    onApplyLyrics: (String) -> Unit = {},
    onRatingChange: (Float) -> Unit,
    onToggleFavorite: () -> Unit,
    onAddCurrentToPlaylist: () -> Unit,
    onSkipToQueueItem: (Int) -> Unit,
    onToggleShuffle: () -> Unit = {},
    onAlbumClick: (String) -> Unit = {},
    onArtistClick: (String) -> Unit = {},
    onPlaylistClick: (String) -> Unit = {},
    onDismiss: () -> Unit = {},
    dismissFraction: () -> Float = { 0f },
    aboutUiState: AboutUiState = AboutUiState.Idle,
    onRetryFetchSongInfo: () -> Unit = {},
    askState: AskBarState = AskBarState.Idle,
    onAboutOpened: () -> Unit = {},
    onAskQuestion: (String) -> Unit = {},
    onAskBarFocused: () -> Unit = {},
    onAskBarCollapseRequested: () -> Unit = {},
    onDismissAskError: () -> Unit = {},
    stageMode: NowPlayingStageMode = NowPlayingStageMode.Compact,
    stageProgress: NowPlayingStageProgress? = null,
    detailPage: NowPlayingDetailPage = NowPlayingDetailPage.Lyrics,
    onStageModeChange: (NowPlayingStageMode) -> Unit = {},
    onStageBack: () -> Boolean = { false },
    onDetailPageChange: (NowPlayingDetailPage) -> Unit = {},
    notesState: List<SongNote> = emptyList(),
    onSaveNote: (String, Long?) -> Unit = { _, _ -> },
    noteSortMode: NoteSortMode = NoteSortMode.Timeline,
    onNoteSortModeChange: (NoteSortMode) -> Unit = {},
    onDeleteNote: (String) -> Unit = {},
    devicesState: DevicesSheetState = DevicesSheetState(),
    onRefreshDevices: () -> Unit = {},
    onSelectDevice: (YoinDevice) -> Unit = {},
    castState: CastState = CastState.NotAvailable,
    onCastClick: () -> Unit = {},
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier,
) {
    // Animate posture/size swaps (fold ↔ unfold, enter/leave tabletop). Official
    // adaptive guidance: a posture change is just a state change — animate it with
    // AnimatedContent (fade + slight scale, spring). Deliberately NO shared elements
    // / lookahead here: a posture change is the most lookahead-hostile moment (the
    // hinge resizes the window mid-measure) and that path crashes the shell
    // ButtonGroup on a real foldable. A plain fade+scale needs no lookahead pass.
    AnimatedContent(
        targetState = LocalYoinWindowInfo.current.layoutMode,
        transitionSpec = {
            // Expressive (overshooting) spring on the scale so the posture swap
            // bounces; a 0.88 start/target gives the spring real travel. Fades stay
            // on the fast non-bouncy effects spec so opacity resolves before the
            // spring settles (bounce lands on an opaque surface, not mid-fade).
            // Still NO shared elements / lookahead — safe on the foldable hinge.
            (
                YoinMotion.fadeIn(role = YoinMotionRole.Standard, speed = YoinMotionSpeed.Fast) +
                    YoinMotion.scaleIn(role = YoinMotionRole.Expressive, initialScale = 0.88f)
            ).togetherWith(
                YoinMotion.fadeOut(role = YoinMotionRole.Standard, speed = YoinMotionSpeed.Fast) +
                    YoinMotion.scaleOut(role = YoinMotionRole.Expressive, targetScale = 0.88f),
            )
        },
        label = "nowPlayingPosture",
    ) { layoutMode ->
    // Posture swaps animate with no finger down — vote High for their
    // duration or the fold/unfold spring paces at ARR-Normal (60Hz).
    val posturing = transition.currentState != transition.targetState
    when (layoutMode) {
        // Dual-pane from Medium up (isDualPaneNowPlaying, scheme §5 option A):
        // pane-relative LayoutMode made a true Wide reading rare, so the
        // two-column player keys off Medium+. Tabletop keeps its hinge layout.
        LayoutMode.Wide, LayoutMode.Medium -> WidePlayingContent(
            state = state,
            positionMs = positionMs,
            bufferedMs = bufferedMs,
            onTogglePlayPause = onTogglePlayPause,
            onSkipNext = onSkipNext,
            onSkipPrevious = onSkipPrevious,
            onSeek = onSeek,
            onSeekToMs = onSeekToMs,
            lyricsSearchState = lyricsSearchState,
            onOpenLyricsSearch = onOpenLyricsSearch,
            onLyricsSearchQueryChange = onLyricsSearchQueryChange,
            onSearchLyrics = onSearchLyrics,
            onApplyLyricsSearchResult = onApplyLyricsSearchResult,
            onDismissLyricsSearch = onDismissLyricsSearch,
            onTranslateLyrics = onTranslateLyrics,
            onApplyLyrics = onApplyLyrics,
            onRatingChange = onRatingChange,
            onToggleFavorite = onToggleFavorite,
            onAddCurrentToPlaylist = onAddCurrentToPlaylist,
            onSkipToQueueItem = onSkipToQueueItem,
            onToggleShuffle = onToggleShuffle,
            onAlbumClick = onAlbumClick,
            onArtistClick = onArtistClick,
            onPlaylistClick = onPlaylistClick,
            onDismiss = onDismiss,
            dismissFraction = dismissFraction,
            aboutUiState = aboutUiState,
            onRetryFetchSongInfo = onRetryFetchSongInfo,
            askState = askState,
            onAboutOpened = onAboutOpened,
            onAskQuestion = onAskQuestion,
            onAskBarFocused = onAskBarFocused,
            onAskBarCollapseRequested = onAskBarCollapseRequested,
            onDismissAskError = onDismissAskError,
            stageMode = stageMode,
            stageProgress = stageProgress,
            detailPage = detailPage,
            onStageModeChange = onStageModeChange,
            onStageBack = onStageBack,
            onDetailPageChange = onDetailPageChange,
            notesState = notesState,
            onSaveNote = onSaveNote,
            noteSortMode = noteSortMode,
            onNoteSortModeChange = onNoteSortModeChange,
            onDeleteNote = onDeleteNote,
            devicesState = devicesState,
            onRefreshDevices = onRefreshDevices,
            onSelectDevice = onSelectDevice,
            castState = castState,
            onCastClick = onCastClick,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            modifier = modifier.voteHighFrameRate(posturing),
        )
        LayoutMode.Tabletop -> TabletopPlayingContent(
            state = state,
            positionMs = positionMs,
            bufferedMs = bufferedMs,
            onTogglePlayPause = onTogglePlayPause,
            onSkipNext = onSkipNext,
            onSkipPrevious = onSkipPrevious,
            onSeek = onSeek,
            onSeekToMs = onSeekToMs,
            lyricsSearchState = lyricsSearchState,
            onOpenLyricsSearch = onOpenLyricsSearch,
            onLyricsSearchQueryChange = onLyricsSearchQueryChange,
            onSearchLyrics = onSearchLyrics,
            onApplyLyricsSearchResult = onApplyLyricsSearchResult,
            onDismissLyricsSearch = onDismissLyricsSearch,
            onTranslateLyrics = onTranslateLyrics,
            onApplyLyrics = onApplyLyrics,
            onRatingChange = onRatingChange,
            onToggleFavorite = onToggleFavorite,
            onAddCurrentToPlaylist = onAddCurrentToPlaylist,
            onSkipToQueueItem = onSkipToQueueItem,
            onToggleShuffle = onToggleShuffle,
            onAlbumClick = onAlbumClick,
            onArtistClick = onArtistClick,
            onPlaylistClick = onPlaylistClick,
            onDismiss = onDismiss,
            dismissFraction = dismissFraction,
            aboutUiState = aboutUiState,
            onRetryFetchSongInfo = onRetryFetchSongInfo,
            askState = askState,
            onAboutOpened = onAboutOpened,
            onAskQuestion = onAskQuestion,
            onAskBarFocused = onAskBarFocused,
            onAskBarCollapseRequested = onAskBarCollapseRequested,
            onDismissAskError = onDismissAskError,
            stageMode = stageMode,
            stageProgress = stageProgress,
            detailPage = detailPage,
            onStageModeChange = onStageModeChange,
            onStageBack = onStageBack,
            onDetailPageChange = onDetailPageChange,
            notesState = notesState,
            onSaveNote = onSaveNote,
            onDeleteNote = onDeleteNote,
            devicesState = devicesState,
            onRefreshDevices = onRefreshDevices,
            onSelectDevice = onSelectDevice,
            castState = castState,
            onCastClick = onCastClick,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            modifier = modifier.voteHighFrameRate(posturing),
        )
        LayoutMode.Compact -> CompactPlayingContent(
            state = state,
            positionMs = positionMs,
            bufferedMs = bufferedMs,
            contentScale = contentScale,
            onTogglePlayPause = onTogglePlayPause,
            onSkipNext = onSkipNext,
            onSkipPrevious = onSkipPrevious,
            onSeek = onSeek,
            onSeekToMs = onSeekToMs,
            lyricsSearchState = lyricsSearchState,
            onOpenLyricsSearch = onOpenLyricsSearch,
            onLyricsSearchQueryChange = onLyricsSearchQueryChange,
            onSearchLyrics = onSearchLyrics,
            onApplyLyricsSearchResult = onApplyLyricsSearchResult,
            onDismissLyricsSearch = onDismissLyricsSearch,
            onTranslateLyrics = onTranslateLyrics,
            onApplyLyrics = onApplyLyrics,
            onRatingChange = onRatingChange,
            onToggleFavorite = onToggleFavorite,
            onAddCurrentToPlaylist = onAddCurrentToPlaylist,
            onSkipToQueueItem = onSkipToQueueItem,
            onToggleShuffle = onToggleShuffle,
            onAlbumClick = onAlbumClick,
            onArtistClick = onArtistClick,
            onPlaylistClick = onPlaylistClick,
            onDismiss = onDismiss,
            dismissFraction = dismissFraction,
            aboutUiState = aboutUiState,
            onRetryFetchSongInfo = onRetryFetchSongInfo,
            askState = askState,
            onAboutOpened = onAboutOpened,
            onAskQuestion = onAskQuestion,
            onAskBarFocused = onAskBarFocused,
            onAskBarCollapseRequested = onAskBarCollapseRequested,
            onDismissAskError = onDismissAskError,
            stageMode = stageMode,
            stageProgress = stageProgress,
            detailPage = detailPage,
            onStageModeChange = onStageModeChange,
            onStageBack = onStageBack,
            onDetailPageChange = onDetailPageChange,
            notesState = notesState,
            onSaveNote = onSaveNote,
            noteSortMode = noteSortMode,
            onNoteSortModeChange = onNoteSortModeChange,
            onDeleteNote = onDeleteNote,
            devicesState = devicesState,
            onRefreshDevices = onRefreshDevices,
            onSelectDevice = onSelectDevice,
            castState = castState,
            onCastClick = onCastClick,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            modifier = modifier.voteHighFrameRate(posturing),
        )
    }
    }
}

/**
 * The single-column player — phones, outer foldable screens, narrow split-screen.
 * This is the original [PlayingContent] body, unchanged; since the Medium flip
 * (isDualPaneNowPlaying) it renders ONLY for [LayoutMode.Compact].
 */
@Composable
private fun CompactPlayingContent(
    state: NowPlayingUiState.Playing,
    // 4Hz playhead readers; invoked only by TickingPlaybackControls / lyrics leaves.
    positionMs: () -> Long,
    bufferedMs: () -> Long,
    // Predictive-back collapse preview: the STAGE recedes to this scale; the
    // controls, title/artist and pills stay fixed. 1f = inert.
    contentScale: Float = 1f,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeek: (Float) -> Unit,
    onSeekToMs: (Long) -> Unit = {},
    lyricsSearchState: LyricsSearchState = LyricsSearchState(),
    onOpenLyricsSearch: () -> Unit = {},
    onLyricsSearchQueryChange: (String) -> Unit = {},
    onSearchLyrics: (String) -> Unit = {},
    onApplyLyricsSearchResult: (LyricsSearchResultUi) -> Unit = {},
    onDismissLyricsSearch: () -> Unit = {},
    onTranslateLyrics: () -> Unit = {},
    onApplyLyrics: (String) -> Unit = {},
    onRatingChange: (Float) -> Unit,
    onToggleFavorite: () -> Unit,
    onAddCurrentToPlaylist: () -> Unit,
    onSkipToQueueItem: (Int) -> Unit,
    onToggleShuffle: () -> Unit = {},
    onAlbumClick: (String) -> Unit = {},
    onArtistClick: (String) -> Unit = {},
    onPlaylistClick: (String) -> Unit = {},
    onDismiss: () -> Unit = {},
    dismissFraction: () -> Float = { 0f },
    aboutUiState: AboutUiState = AboutUiState.Idle,
    onRetryFetchSongInfo: () -> Unit = {},
    askState: AskBarState = AskBarState.Idle,
    onAboutOpened: () -> Unit = {},
    onAskQuestion: (String) -> Unit = {},
    onAskBarFocused: () -> Unit = {},
    onAskBarCollapseRequested: () -> Unit = {},
    onDismissAskError: () -> Unit = {},
    stageMode: NowPlayingStageMode = NowPlayingStageMode.Compact,
    stageProgress: NowPlayingStageProgress? = null,
    detailPage: NowPlayingDetailPage = NowPlayingDetailPage.Lyrics,
    onStageModeChange: (NowPlayingStageMode) -> Unit = {},
    onStageBack: () -> Boolean = { false },
    onDetailPageChange: (NowPlayingDetailPage) -> Unit = {},
    notesState: List<SongNote> = emptyList(),
    onSaveNote: (String, Long?) -> Unit = { _, _ -> },
    noteSortMode: NoteSortMode = NoteSortMode.Timeline,
    onNoteSortModeChange: (NoteSortMode) -> Unit = {},
    onDeleteNote: (String) -> Unit = {},
    devicesState: DevicesSheetState = DevicesSheetState(),
    onRefreshDevices: () -> Unit = {},
    onSelectDevice: (YoinDevice) -> Unit = {},
    castState: CastState = CastState.NotAvailable,
    onCastClick: () -> Unit = {},
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier,
) {
    val motionProfile = LocalMotionProfile.current
    val heroStretchSpec = if (motionProfile == MotionProfile.Full) {
        YoinMotion.slowSpatialSpec<Float>(role = YoinMotionRole.Expressive)
    } else {
        YoinMotion.fastSpatialSpec<Float>(role = YoinMotionRole.Expressive)
    }
    val heroBoundsSpec = YoinMotion.slowSpatialSpec<Rect>(
        role = YoinMotionRole.Expressive,
        expressiveScheme = MaterialTheme.motionScheme,
    )

    var showQueue by remember { mutableStateOf(false) }
    var showDevicesSheet by remember(state.songId) { mutableStateOf(false) }
    var showWriteSheet by remember(state.songId) { mutableStateOf(false) }
    val playInteractionSource = rememberNowPlayingButtonGroupInteractionSource()
    val nextInteractionSource = rememberNowPlayingButtonGroupInteractionSource()
    val playPressed by playInteractionSource.collectIsPressedAsState()
    val nextPressed by nextInteractionSource.collectIsPressedAsState()
    val transportPressed = playPressed || nextPressed
    val playbackActions = NowPlayingPlaybackActions(
        onTogglePlayPause = onTogglePlayPause,
        onSkipNext = onSkipNext,
        onSkipPrevious = onSkipPrevious,
        onSeek = onSeek,
        onToggleShuffle = onToggleShuffle,
    )
    val lyricsActions = NowPlayingLyricsActions(
        onSeekToMs = onSeekToMs,
        onOpenLyricsSearch = onOpenLyricsSearch,
        onLyricsSearchQueryChange = onLyricsSearchQueryChange,
        onSearchLyrics = onSearchLyrics,
        onApplyLyricsSearchResult = onApplyLyricsSearchResult,
        onDismissLyricsSearch = onDismissLyricsSearch,
        onTranslateLyrics = onTranslateLyrics,
        onApplyLyrics = onApplyLyrics,
    )
    val navigationActions = NowPlayingNavigationActions(
        onAlbumClick = onAlbumClick,
        onArtistClick = onArtistClick,
        onPlaylistClick = onPlaylistClick,
    )

    val resolvedStageProgress = stageProgress ?: rememberNowPlayingStageProgress(stageMode)
    val detailProgress = resolvedStageProgress.detail
    val immersiveProgress = resolvedStageProgress.immersive
    val compactProgress = resolvedStageProgress.compact
    // Stage reshapes (Compact ⇄ Expanded) settle after the finger lifts —
    // vote High while any stage value is moving.
    val stageMoving by remember(resolvedStageProgress) {
        derivedStateOf { resolvedStageProgress.isMoving }
    }

    // The Now Playing overlay enters/exits via a shared-element cover morph
    // (mini <-> full) owned by [animatedVisibilityScope]. The
    // CoverTransitionOverlay proxy below is drawn LAST (on top) and only owns the
    // lyrics-stage reshape. If both are live at once — e.g. you expand Lyrics
    // while the entrance morph is still settling — the proxy paints over the
    // morphing shared element and visibly "grows up". Gate the proxy on the
    // entrance/exit transition being settled so exactly one cover is ever in
    // flight. A null scope (detail-Activity host with no shared element) reads as
    // settled — there is nothing to collide with there.
    val entranceSettled = animatedVisibilityScope?.transition?.let { transition ->
        transition.currentState == transition.targetState
    } ?: true

    val titleStretchScale by animateFloatAsState(
        targetValue = when {
            motionProfile == MotionProfile.AdaptiveReduced && transportPressed -> 1.02f
            motionProfile == MotionProfile.AdaptiveReduced && state.isPlaying -> 1.01f
            motionProfile == MotionProfile.AdaptiveReduced -> 0.99f
            transportPressed -> 1.05f
            state.isPlaying -> 1.03f
            else -> 0.97f
        },
        animationSpec = heroStretchSpec,
        label = "titleStretch",
    )
    val artistStretchScale by animateFloatAsState(
        targetValue = when {
            motionProfile == MotionProfile.AdaptiveReduced && transportPressed -> 1.015f
            motionProfile == MotionProfile.AdaptiveReduced && state.isPlaying -> 1.008f
            motionProfile == MotionProfile.AdaptiveReduced -> 0.992f
            transportPressed -> 1.04f
            state.isPlaying -> 1.02f
            else -> 0.98f
        },
        animationSpec = heroStretchSpec,
        label = "artistStretch",
    )
    val titleRouteInteraction = state.albumId?.let { albumId ->
        rememberNowPlayingRouteInteraction(
            onNavigate = { navigationActions.onAlbumClick(albumId) },
        )
    }
    val artistRouteInteraction = state.artistId?.let { artistId ->
        rememberNowPlayingRouteInteraction(
            onNavigate = { navigationActions.onArtistClick(artistId) },
        )
    }
    var lyricsAutoScroll by remember(state.songId) { mutableStateOf(true) }
    var lyricsRecenterTick by remember(state.songId) { mutableIntStateOf(0) }
    val hasSyncedLyrics = remember(state.lyrics) { state.lyrics.any { it.startMs != null } }
    var showApplyDialog by remember(state.songId) { mutableStateOf(false) }
    val pagerState = rememberPagerState(
        initialPage = detailPage.ordinal,
        pageCount = { 3 },
    )
    val pagerScope = rememberCoroutineScope()
    // ONE driver per direction, no write-back hijack: the old shape synced the
    // VM off pagerState.currentPage, so a 2-page tab jump (Lyrics→Note) wrote
    // About back to the VM as the pager swept across it, whose effect then
    // re-targeted the animation mid-flight — the pager stopped on (or between)
    // the wrong page. Clicks/external writes animate; the VM syncs only from
    // SETTLED pages; settled writes re-enter as no-ops (target already met).
    LaunchedEffect(detailPage) {
        if (detailPage.ordinal != pagerState.targetPage) {
            pagerState.settleToPage(detailPage.ordinal)
        }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { settled ->
            val page = NowPlayingDetailPage.entries[settled]
            if (page != detailPage) onDetailPageChange(page)
            if (page == NowPlayingDetailPage.About) onAboutOpened()
        }
    }
    // The bottom accessory strip mirrors the detail pager one-way. It must
    // NOT share pagerState: a PagerState supports a single attached pager,
    // and a second attachment steals the remeasurement slot, freezing the
    // first pager's drag handling entirely.
    val accessoryPagerState = rememberPagerState(
        initialPage = detailPage.ordinal,
        pageCount = { 3 },
    )
    LaunchedEffect(pagerState, accessoryPagerState) {
        snapshotFlow { pagerState.currentPage + pagerState.currentPageOffsetFraction }
            .collect { position ->
                val page = position.roundToInt()
                    .coerceIn(0, NowPlayingDetailPage.entries.lastIndex)
                accessoryPagerState.scrollToPage(
                    page = page,
                    pageOffsetFraction = (position - page).coerceIn(-0.5f, 0.5f),
                )
            }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .voteHighFrameRate(stageMoving)
            .padding(WindowInsets.systemBars.asPaddingValues()),
    ) {
        val horizontalPadding = 24.dp
        val compactCoverHeight = (maxWidth - 108.dp).coerceIn(168.dp, 312.dp)
        val immersiveCoverHeight = (maxWidth - horizontalPadding * 2)
            .coerceAtLeast(compactCoverHeight)
            .coerceAtMost(420.dp)
        val visibleCoverHeight = lerpDp(compactCoverHeight, immersiveCoverHeight, immersiveProgress)
        val coverRowHeight = lerpDp(visibleCoverHeight, 0.dp, detailProgress)
        val coverRowAlpha = compactProgress
        val compactTabHeight = lerpDp(30.dp, 0.dp, immersiveProgress)
        val tabHeight = lerpDp(compactTabHeight, 52.dp, detailProgress)
        val tabSpacerHeight = lerpDp(lerpDp(4.dp, 0.dp, immersiveProgress), 12.dp, detailProgress)
        val ratingRetreatProgress = maxOf(immersiveProgress, detailProgress)
        val ratingGap = lerpDp(12.dp, 0.dp, ratingRetreatProgress)
        val ratingSlotWidth = lerpDp(56.dp, 0.dp, ratingRetreatProgress)
        val bottomAccessoryTargetHeight = when {
            detailPage == NowPlayingDetailPage.About && askState is AskBarState.Focused -> 276.dp
            else -> 68.dp
        }
        val bottomAccessoryHeight by animateDpAsState(
            targetValue = bottomAccessoryTargetHeight,
            animationSpec = YoinMotion.defaultSpatialSpec(role = YoinMotionRole.Expressive),
            label = "nowPlayingBottomAccessoryHeight",
        )
        val controlsHeight = lerpDp(148.dp, 0.dp, detailProgress)
        val heroHeight = 86.dp
        val compactCoverSize = (maxWidth - horizontalPadding * 2 - 12.dp - 56.dp)
            .coerceAtLeast(0.dp)
            .coerceAtMost(compactCoverHeight)

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Top,
        ) {
            StageTopBar(
                state = state,
                stageMode = stageMode,
                detailProgress = detailProgress,
                dismissFraction = dismissFraction,
                onBack = {
                    when (stageMode) {
                        NowPlayingStageMode.Expanded -> if (!onStageBack()) {
                            onDismiss()
                        }
                        NowPlayingStageMode.Compact,
                        NowPlayingStageMode.Immersive,
                        -> onDismiss()
                    }
                },
                onEnterImmersive = {
                    onDetailPageChange(NowPlayingDetailPage.Lyrics)
                    onStageModeChange(NowPlayingStageMode.Immersive)
                },
                onAlbumClick = navigationActions.onAlbumClick,
                onArtistClick = navigationActions.onArtistClick,
                onPlaylistClick = navigationActions.onPlaylistClick,
                modifier = Modifier.padding(horizontal = horizontalPadding),
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    // Collapse-preview recede: ONLY the stage (cover / tabs / lyrics)
                    // steps back; the controls, title/artist and pills below stay put.
                    .graphicsLayer {
                        scaleX = contentScale
                        scaleY = contentScale
                    },
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.Start,
                ) {
                    StageHeightSlot(
                        height = coverRowHeight,
                        alpha = coverRowAlpha,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = horizontalPadding),
                            verticalAlignment = Alignment.Top,
                        ) {
                            val coverClickSource = remember { MutableInteractionSource() }
                            // Always a square: size by the slot HEIGHT (AlbumCover adds
                            // aspectRatio(1f)) instead of weighting the WIDTH. On wide
                            // viewports a width-weighted box exceeded the height-capped
                            // slot, so ContentScale.Crop cut the square art top/bottom.
                            // The weighted wrapper keeps the rating slot's position.
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                contentAlignment = Alignment.TopStart,
                            ) {
                                AlbumCover(
                                    songId = state.songId,
                                    coverArtUrl = state.coverArtUrl,
                                    sharedTransitionScope = sharedTransitionScope,
                                    animatedVisibilityScope = animatedVisibilityScope,
                                    interactionSource = coverClickSource,
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .graphicsLayer {
                                            // Binary gate only: the CoverTransitionOverlay proxy carries
                                            // the morph, and the parent slot already fades with
                                            // compactProgress — a fractional alpha here double-fades.
                                            alpha = if (detailProgress > HiddenLayerVisibilityThreshold) 0f else 1f
                                            translationY = -36.dp.toPx() * detailProgress
                                            val coverScale = 1f - 0.08f * detailProgress
                                            scaleX = coverScale
                                            scaleY = coverScale
                                            transformOrigin = TransformOrigin(0.5f, 0f)
                                        }
                                        .noRippleClickable(
                                            interactionSource = coverClickSource,
                                            onClick = {
                                                if (stageMode == NowPlayingStageMode.Immersive) {
                                                    onStageModeChange(NowPlayingStageMode.Compact)
                                                } else {
                                                    onDetailPageChange(NowPlayingDetailPage.Lyrics)
                                                    onStageModeChange(NowPlayingStageMode.Immersive)
                                                }
                                            },
                                        ),
                                )
                            }

                            Spacer(modifier = Modifier.width(ratingGap))

                            Box(
                                modifier = Modifier
                                    .width(ratingSlotWidth)
                                    .fillMaxHeight()
                                    .clipToBounds(),
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .width(56.dp)
                                        .fillMaxHeight()
                                        .graphicsLayer {
                                            alpha = (1f - ratingRetreatProgress).coerceIn(0f, 1f)
                                            translationX = 72.dp.toPx() * ratingRetreatProgress
                                        },
                                ) {
                                    RatingSlider(
                                        rating = state.rating,
                                        onRatingChange = onRatingChange,
                                        modifier = Modifier.weight(1f),
                                    )

                                    Spacer(modifier = Modifier.height(8.dp))

                                    FavoriteButton(
                                        isStarred = state.isStarred,
                                        onClick = onToggleFavorite,
                                        onLongClick = onAddCurrentToPlaylist,
                                    )

                                    // Room for the heart's tap-bounce (scales to
                                    // 1.25×) so its overflow isn't cut by the
                                    // rating slot's clipToBounds at this edge.
                                    Spacer(modifier = Modifier.height(6.dp))
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(lerpDp(16.dp, 8.dp, detailProgress)))

                    StageTabs(
                        selected = NowPlayingDetailPage.entries[pagerState.targetPage],
                        detailProgress = detailProgress,
                        height = tabHeight,
                        onSelect = { page ->
                            pagerScope.launch { pagerState.settleToPage(page.ordinal) }
                        },
                        modifier = Modifier.padding(horizontal = horizontalPadding),
                    )

                    Spacer(modifier = Modifier.height(tabSpacerHeight))

                    val pagerClickSource = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .edgeFade(start = horizontalPadding, end = horizontalPadding),
                    ) {
                        HorizontalPager(
                            state = pagerState,
                            beyondViewportPageCount = 1,
                            userScrollEnabled = stageMode != NowPlayingStageMode.Immersive,
                            modifier = Modifier.fillMaxSize(),
                        ) { page ->
                            val pageModifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = horizontalPadding)
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .tapWithoutConsumingDrag(
                                        enabled = stageMode != NowPlayingStageMode.Expanded,
                                    ) {
                                        onStageModeChange(NowPlayingStageMode.Expanded)
                                    },
                            ) {
                                CompactDetailPage(
                                    page = NowPlayingDetailPage.entries[page],
                                    state = state,
                                    positionMs = positionMs,
                                    aboutUiState = aboutUiState,
                                    notes = notesState,
                                    immersiveProgress = immersiveProgress,
                                    onRetryFetchSongInfo = onRetryFetchSongInfo,
                                    modifier = pageModifier.graphicsLayer {
                                        alpha = compactProgress
                                        translationY = 12.dp.toPx() * detailProgress
                                    },
                                )
                                if (
                                    stageMode == NowPlayingStageMode.Expanded ||
                                    detailProgress > HiddenLayerVisibilityThreshold
                                ) {
                                    ExpandedDetailPage(
                                        page = NowPlayingDetailPage.entries[page],
                                        state = state,
                                        positionMs = positionMs,
                                        aboutUiState = aboutUiState,
                                        notes = notesState,
                                        noteSortMode = noteSortMode,
                                        onNoteSortModeChange = onNoteSortModeChange,
                                        lyricsAutoScroll = lyricsAutoScroll,
                                        lyricsRecenterTick = lyricsRecenterTick,
                                        onLyricsUserScroll = { lyricsAutoScroll = false },
                                        onSeekToMs = { targetMs ->
                                            lyricsAutoScroll = true
                                            lyricsRecenterTick += 1
                                            lyricsActions.onSeekToMs(targetMs)
                                        },
                                        onRetryCanonical = onRetryFetchSongInfo,
                                        onSaveNote = onSaveNote,
                                        onDeleteNote = onDeleteNote,
                                        modifier = pageModifier.graphicsLayer {
                                            // Fade + grow the lyrics IN WITH the
                                            // reshape (fully visible by ~70%),
                                            // instead of a late upward slide that
                                            // read as "expand first, lyrics after".
                                            alpha = ((detailProgress - 0.1f) / 0.6f)
                                                .coerceIn(0f, 1f)
                                            val sc = 0.96f + 0.04f * detailProgress
                                            scaleX = sc
                                            scaleY = sc
                                            transformOrigin = TransformOrigin(0.5f, 0.5f)
                                        },
                                    )
                                }
                            }
                        }

                        if (stageMode == NowPlayingStageMode.Immersive) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clickable(
                                        interactionSource = pagerClickSource,
                                        indication = null,
                                    ) {
                                        onDetailPageChange(NowPlayingDetailPage.Lyrics)
                                        onStageModeChange(NowPlayingStageMode.Expanded)
                                    },
                            )
                        }
                    }

                    StageHeightSlot(
                        height = controlsHeight,
                        alpha = compactProgress,
                    ) {
                        TickingPlaybackControls(
                            noteAnchorsMs = remember(notesState) { notesState.mapNotNull { it.positionMs }.sorted() },
                            isPlaying = state.isPlaying,
                            onTogglePlayPause = playbackActions.onTogglePlayPause,
                            onSkipNext = playbackActions.onSkipNext,
                            onSkipPrevious = playbackActions.onSkipPrevious,
                            positionMs = positionMs,
                            bufferedMs = bufferedMs,
                            durationMs = state.durationMs,
                            onSeek = playbackActions.onSeek,
                            playInteractionSource = playInteractionSource,
                            nextInteractionSource = nextInteractionSource,
                            playPressed = playPressed,
                            nextPressed = nextPressed,
                            shuffleEnabled = state.shuffleEnabled,
                            onToggleShuffle = playbackActions.onToggleShuffle,
                            modifier = Modifier
                                .padding(horizontal = horizontalPadding)
                                .graphicsLayer {
                                    translationY = 48.dp.toPx() * detailProgress
                                },
                        )
                    }

                    CompactBottomHero(
                        state = state,
                        heroBoundsSpec = heroBoundsSpec,
                        titleStretchScale = titleStretchScale,
                        artistStretchScale = artistStretchScale,
                        titleRouteInteraction = titleRouteInteraction,
                        artistRouteInteraction = artistRouteInteraction,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        height = heroHeight,
                        alpha = 1f,
                        modifier = Modifier.padding(horizontal = horizontalPadding),
                    )

                    StageHeightSlot(
                        height = bottomAccessoryHeight,
                        alpha = 1f,
                        // Edge-to-edge (no adjustResize) means we must consume the IME
                        // inset ourselves, or the keyboard covers the Ask Gemini bar.
                        // Applied here (outside the slot's fixed height) so the whole
                        // accessory lifts above the keyboard; 0 when the IME is hidden.
                        modifier = Modifier.imePadding(),
                    ) {
                        Box(
                            // Edge-to-edge like the lyrics pager: the accessory
                            // pager slides full-width and the screen edges are
                            // soft-masked (not inset). The 24dp content inset moves
                            // onto the pills and onto each pager page instead.
                            modifier = Modifier
                                .fillMaxSize()
                                .edgeFade(start = horizontalPadding, end = horizontalPadding),
                            contentAlignment = Alignment.BottomStart,
                        ) {
                            BottomPills(
                                onQueueClick = { showQueue = true },
                                onDevicesClick = { showDevicesSheet = true },
                                onWriteClick = { showWriteSheet = true },
                                castState = castState,
                                onCastClick = onCastClick,
                                // Pure crossfade with the accessory pager — no
                                // downward translation. The slot clipToBounds sits
                                // at the bottom safe-area edge, so sliding the pills
                                // DOWN clipped them mid-fade and exposed the near-
                                // white gradient bottom under the nav bar.
                                // Padding (was on the wrapper Box) keeps the pills
                                // at the 24dp inset now that the Box is edge-to-edge.
                                modifier = Modifier
                                    .padding(horizontal = horizontalPadding)
                                    .graphicsLayer {
                                        alpha = compactProgress
                                    },
                            )
                            // Only compose the accessory action-bar pager while
                            // it's at least partly visible. Otherwise, when
                            // collapsed (alpha 0) it still sits ON TOP of the
                            // BottomPills and intercepts taps — so Queue/Devices
                            // taps would hit the invisible lyrics action bar.
                            if (
                                stageMode == NowPlayingStageMode.Expanded ||
                                detailProgress > HiddenLayerVisibilityThreshold
                            ) {
                                HorizontalPager(
                                    state = accessoryPagerState,
                                    userScrollEnabled = false,
                                    beyondViewportPageCount = 1,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            alpha = detailProgress
                                        },
                                ) { page ->
                                    Box(
                                        // Per-page 24dp inset (mirrors the main
                                        // pager's pageModifier) so the action bars
                                        // keep their margin while the pager itself
                                        // slides edge-to-edge under the edge mask.
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = horizontalPadding),
                                        contentAlignment = Alignment.BottomStart,
                                    ) {
                                        when (NowPlayingDetailPage.entries[page]) {
                                            NowPlayingDetailPage.Lyrics -> LyricsActionBar(
                                                actionInFlight = state.lyricsActionInFlight,
                                                canTranslate = state.lyrics.isNotEmpty(),
                                                canRecenter = !lyricsAutoScroll && hasSyncedLyrics,
                                                onSearchClick = lyricsActions.onOpenLyricsSearch,
                                                onTranslateClick = lyricsActions.onTranslateLyrics,
                                                onApplyClick = { showApplyDialog = true },
                                                onRecenterClick = {
                                                    lyricsAutoScroll = true
                                                    lyricsRecenterTick += 1
                                                },
                                            )
                                            NowPlayingDetailPage.About -> AskGeminiBar(
                                                askState = askState,
                                                onSubmit = onAskQuestion,
                                                onFocus = onAskBarFocused,
                                                onCollapseRequest = onAskBarCollapseRequested,
                                                onDismissError = onDismissAskError,
                                            )
                                            NowPlayingDetailPage.Note -> Spacer(modifier = Modifier.height(56.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (entranceSettled) {
            CoverTransitionOverlay(
                coverArtUrl = state.coverArtUrl,
                progress = detailProgress,
                startX = horizontalPadding,
                startY = 56.dp,
                startSize = compactCoverSize,
                endX = horizontalPadding + 56.dp,
                endY = 0.dp,
                endSize = 44.dp,
            )
        }

        LyricsSearchSheet(
            state = lyricsSearchState,
            onQueryChange = lyricsActions.onLyricsSearchQueryChange,
            onSearch = lyricsActions.onSearchLyrics,
            onSelect = lyricsActions.onApplyLyricsSearchResult,
            onDismiss = lyricsActions.onDismissLyricsSearch,
        )

        if (showApplyDialog) {
            LyricsApplyDialog(
                initialText = remember(state.songId, state.lyrics) {
                    state.lyrics.toEditableLyricsText()
                },
                onDismiss = { showApplyDialog = false },
                onApply = { rawLyrics ->
                    showApplyDialog = false
                    lyricsActions.onApplyLyrics(rawLyrics)
                },
            )
        }
    }

    // Queue bottom sheet
    if (showQueue) {
        ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
            QueueSheet(
                queue = state.queue,
                currentIndex = state.currentQueueIndex,
                onItemClick = { index ->
                    onSkipToQueueItem(index)
                    showQueue = false
                },
                onDismiss = { showQueue = false },
            )
        }
    }

    if (showDevicesSheet) {
        ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
            DevicesSheet(
                providerId = devicesState.providerId,
                devices = devicesState.devices,
                loading = devicesState.loading,
                busyDeviceId = devicesState.busyDeviceId,
                errorMessage = devicesState.errorMessage,
                onRefresh = onRefreshDevices,
                onSelect = onSelectDevice,
                onDismiss = { showDevicesSheet = false },
            )
        }
    }

    if (showWriteSheet) {
        ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
            WriteNoteSheet(
                onSave = onSaveNote,
                positionMs = positionMs,
                trackTitle = state.songTitle,
                onDismiss = { showWriteSheet = false },
            )
        }
    }
}

/**
 * Two-column player for Medium+ windows (isDualPaneNowPlaying: foldable inner
 * screen / tablet / a >=600dp embedded pane). LEFT is passive (square cover +
 * horizontal rating with the favorite pinned at the row end + title/artist);
 * RIGHT is the always-expanded detail (tabs + Lyrics/About/Note pager +
 * transport + pills). The right column is inherently "expanded", so there is no
 * Compact↔Expanded reshape, no CoverTransitionOverlay, and no drag-to-dismiss
 * (gated off in NowPlayingOverlayHost). State here is LOCAL: the single- and
 * two-column bodies are mutually exclusive in the dispatcher, so each owns its
 * copies.
 */
@Composable
private fun WidePlayingContent(
    state: NowPlayingUiState.Playing,
    // 4Hz playhead readers; invoked only by TickingPlaybackControls / lyrics leaves.
    positionMs: () -> Long,
    bufferedMs: () -> Long,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeek: (Float) -> Unit,
    onSeekToMs: (Long) -> Unit = {},
    lyricsSearchState: LyricsSearchState = LyricsSearchState(),
    onOpenLyricsSearch: () -> Unit = {},
    onLyricsSearchQueryChange: (String) -> Unit = {},
    onSearchLyrics: (String) -> Unit = {},
    onApplyLyricsSearchResult: (LyricsSearchResultUi) -> Unit = {},
    onDismissLyricsSearch: () -> Unit = {},
    onTranslateLyrics: () -> Unit = {},
    onApplyLyrics: (String) -> Unit = {},
    onRatingChange: (Float) -> Unit,
    onToggleFavorite: () -> Unit,
    onAddCurrentToPlaylist: () -> Unit,
    onSkipToQueueItem: (Int) -> Unit,
    onToggleShuffle: () -> Unit = {},
    onAlbumClick: (String) -> Unit = {},
    onArtistClick: (String) -> Unit = {},
    onPlaylistClick: (String) -> Unit = {},
    onDismiss: () -> Unit = {},
    dismissFraction: () -> Float = { 0f },
    aboutUiState: AboutUiState = AboutUiState.Idle,
    onRetryFetchSongInfo: () -> Unit = {},
    askState: AskBarState = AskBarState.Idle,
    onAboutOpened: () -> Unit = {},
    onAskQuestion: (String) -> Unit = {},
    onAskBarFocused: () -> Unit = {},
    onAskBarCollapseRequested: () -> Unit = {},
    onDismissAskError: () -> Unit = {},
    stageMode: NowPlayingStageMode = NowPlayingStageMode.Compact,
    stageProgress: NowPlayingStageProgress? = null,
    detailPage: NowPlayingDetailPage = NowPlayingDetailPage.Lyrics,
    onStageModeChange: (NowPlayingStageMode) -> Unit = {},
    onStageBack: () -> Boolean = { false },
    onDetailPageChange: (NowPlayingDetailPage) -> Unit = {},
    notesState: List<SongNote> = emptyList(),
    onSaveNote: (String, Long?) -> Unit = { _, _ -> },
    noteSortMode: NoteSortMode = NoteSortMode.Timeline,
    onNoteSortModeChange: (NoteSortMode) -> Unit = {},
    onDeleteNote: (String) -> Unit = {},
    devicesState: DevicesSheetState = DevicesSheetState(),
    onRefreshDevices: () -> Unit = {},
    onSelectDevice: (YoinDevice) -> Unit = {},
    castState: CastState = CastState.NotAvailable,
    onCastClick: () -> Unit = {},
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier,
) {
    val albumId = state.albumId
    val artistId = state.artistId

    var showQueue by remember { mutableStateOf(false) }
    var showDevicesSheet by remember(state.songId) { mutableStateOf(false) }
    var showWriteSheet by remember(state.songId) { mutableStateOf(false) }
    var lyricsAutoScroll by remember(state.songId) { mutableStateOf(true) }
    var lyricsRecenterTick by remember(state.songId) { mutableIntStateOf(0) }

    val playInteractionSource = rememberNowPlayingButtonGroupInteractionSource()
    val nextInteractionSource = rememberNowPlayingButtonGroupInteractionSource()
    val playPressed by playInteractionSource.collectIsPressedAsState()
    val nextPressed by nextInteractionSource.collectIsPressedAsState()

    val pagerState = rememberPagerState(
        initialPage = detailPage.ordinal,
        pageCount = { 3 },
    )
    val pagerScope = rememberCoroutineScope()
    // ONE driver per direction, no write-back hijack: the old shape synced the
    // VM off pagerState.currentPage, so a 2-page tab jump (Lyrics→Note) wrote
    // About back to the VM as the pager swept across it, whose effect then
    // re-targeted the animation mid-flight — the pager stopped on (or between)
    // the wrong page. Clicks/external writes animate; the VM syncs only from
    // SETTLED pages; settled writes re-enter as no-ops (target already met).
    LaunchedEffect(detailPage) {
        if (detailPage.ordinal != pagerState.targetPage) {
            pagerState.settleToPage(detailPage.ordinal)
        }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { settled ->
            val page = NowPlayingDetailPage.entries[settled]
            if (page != detailPage) onDetailPageChange(page)
            if (page == NowPlayingDetailPage.About) onAboutOpened()
        }
    }

    // Contextual action bar at the right-column bottom (search/translate/recenter
    // for Lyrics, Ask Gemini for About). It mirrors the detail pager one-way via a
    // SECOND PagerState — a PagerState only supports one attached pager, so reusing
    // pagerState would freeze the main pager's drag. (Same constraint as Compact.)
    val hasSyncedLyrics = remember(state.lyrics) { state.lyrics.any { it.startMs != null } }
    var showApplyDialog by remember(state.songId) { mutableStateOf(false) }
    val accessoryPagerState = rememberPagerState(
        initialPage = detailPage.ordinal,
        pageCount = { 3 },
    )
    LaunchedEffect(pagerState, accessoryPagerState) {
        snapshotFlow { pagerState.currentPage + pagerState.currentPageOffsetFraction }
            .collect { position ->
                val page = position.roundToInt()
                    .coerceIn(0, NowPlayingDetailPage.entries.lastIndex)
                accessoryPagerState.scrollToPage(
                    page = page,
                    pageOffsetFraction = (position - page).coerceIn(-0.5f, 0.5f),
                )
            }
    }
    // Small by default; the Ask Gemini bar grows when focused (matches Compact).
    val bottomAccessoryTargetHeight = when {
        detailPage == NowPlayingDetailPage.About && askState is AskBarState.Focused -> 276.dp
        else -> 68.dp
    }
    val bottomAccessoryHeight by animateDpAsState(
        targetValue = bottomAccessoryTargetHeight,
        animationSpec = YoinMotion.defaultSpatialSpec(role = YoinMotionRole.Expressive),
        label = "wideBottomAccessoryHeight",
    )

    // Tapping the cover enlarges it (mirrors the small-screen immersive logic:
    // tap = zoom the artwork). The rating bar below is always a full slider — no
    // collapse. Both reset per song.
    val coverInteraction = remember { MutableInteractionSource() }
    var coverZoomed by remember(state.songId) { mutableStateOf(false) }
    val coverZoom by animateFloatAsState(
        targetValue = if (coverZoomed) 1f else 0f,
        animationSpec = YoinMotion.spatialSpring(),
        label = "wideCoverZoom",
    )

    Box(
        // Edge-to-edge: the parent NowPlayingScreen gradient fills behind the
        // system bars. The old blanket padding(systemBars) inset the whole Box and
        // exposed the light window background as a WHITE band in the nav-bar
        // region. Inset only the TOP + sides on the content; the bottom stays
        // full-bleed so the gradient reaches the nav-bar edge (the button group
        // gets its own navigationBarsPadding to stay tappable).
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(
                    WindowInsets.systemBars.only(
                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                    ),
                )
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            // Full-width "NOW PLAYING" row spanning both columns.
            WideTopBar(
                state = state,
                dismissFraction = dismissFraction,
                coverZoom = { coverZoom },
                onBack = onDismiss,
                onAlbumClick = onAlbumClick,
                onArtistClick = onArtistClick,
                onPlaylistClick = onPlaylistClick,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                // LEFT — passive: cover + rating/favorite + title/artist, with the
                // Queue/Devices/Write pills pinned at the bottom. The identity block
                // is centred in the space above the pills via two weight spacers.
                Column(
                    modifier = Modifier
                        // Tapping the cover widens the whole left column (1:1 → 1.5:1);
                        // the column-filling cover grows with it instead of resizing in
                        // place. The right column stays weight 1f.
                        .weight(1f + 0.5f * coverZoom)
                        .fillMaxHeight()
                        .padding(end = 24.dp),
                ) {
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        // Cap the cover height so the rating / title / transport / pills
                        // below always fit. On zoom the identity block (rating +
                        // title/artist) retreats, so the reserve shrinks and the cover
                        // claims that freed height — that, plus the wider column, is how
                        // it actually grows on the near-square inner display.
                        val reservedForRest = lerpDp(450.dp, 274.dp, coverZoom)
                        val coverSize = maxWidth
                            .coerceAtMost((maxHeight - reservedForRest).coerceAtLeast(140.dp))
                        Column(
                            // Constrain the whole left stack to the cover's width and
                            // centre it, so the cover, rating bar, title, transport and
                            // pills all share ONE width (no cover-vs-rating mismatch).
                            modifier = Modifier
                                .width(coverSize)
                                .fillMaxHeight()
                                .align(Alignment.TopCenter),
                        ) {
                            Spacer(modifier = Modifier.weight(1f))
                            AlbumCover(
                                songId = state.songId,
                                coverArtUrl = state.coverArtUrl,
                                // NO shared element in Wide. A fillMaxWidth shared cover
                                // resolves to an UNBOUNDED width in the shared-transition
                                // lookahead and propagates Constraints.Infinity into the
                                // shell ButtonGroup's height(IntrinsicSize.Max) intrinsic
                                // measurement (YoinButtonGroup.kt) — which M3's ButtonGroup
                                // cannot take and crashes on. Drop the mini→cover morph here;
                                // the cover simply appears. (Compact keeps the morph because
                                // its cover is a fixed dp size, so its bounds stay finite.)
                                sharedTransitionScope = null,
                                animatedVisibilityScope = null,
                                interactionSource = coverInteraction,
                                modifier = Modifier
                                    .size(coverSize)
                                    .noRippleClickable(interactionSource = coverInteraction) {
                                        coverZoomed = !coverZoomed
                                    },
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            // Rating + title/artist retreat (fade + height collapse) when
                            // the cover is zoomed, freeing the room the square cover needs
                            // to grow. Transport + pills below stay put.
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .graphicsLayer { alpha = (1f - coverZoom).coerceIn(0f, 1f) }
                                    .layout { measurable, constraints ->
                                        val placeable = measurable.measure(constraints)
                                        val h = (placeable.height * (1f - coverZoom))
                                            .roundToInt()
                                            .coerceAtLeast(0)
                                        layout(placeable.width, h) { placeable.place(0, 0) }
                                    }
                                    .clipToBounds(),
                            ) {
                                // Always-visible full rating slider; the favorite is
                                // pinned at the trailing end.
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp),
                                    ) {
                                        RatingSlider(
                                            rating = state.rating,
                                            onRatingChange = onRatingChange,
                                            orientation = Orientation.Horizontal,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    FavoriteButton(
                                        isStarred = state.isStarred,
                                        onClick = onToggleFavorite,
                                        onLongClick = onAddCurrentToPlaylist,
                                    )
                                    // Room for the heart's tap-bounce (scales to 1.25×):
                                    // it's pinned at the trailing edge, so its right
                                    // overflow would be cut by the Column's clipToBounds.
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                                // Same press language as Compact: route stretch
                                // (grow on press, dip on release), no ripple.
                                val wideTitleRoute = albumId?.let { id ->
                                    rememberNowPlayingRouteInteraction(
                                        onNavigate = { onAlbumClick(id) },
                                    )
                                }
                                Text(
                                    text = state.songTitle,
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = wideTitleRoute?.let { route ->
                                        Modifier
                                            .graphicsLayer {
                                                scaleX = route.scaleX
                                                transformOrigin = TransformOrigin(0f, 0.5f)
                                            }
                                            .noRippleClickable(
                                                interactionSource = route.interactionSource,
                                                onClick = route.onClick,
                                            )
                                    } ?: Modifier,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                val wideArtistRoute = artistId?.let { id ->
                                    rememberNowPlayingRouteInteraction(
                                        onNavigate = { onArtistClick(id) },
                                    )
                                }
                                Text(
                                    text = state.artist,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = wideArtistRoute?.let { route ->
                                        Modifier
                                            .graphicsLayer {
                                                scaleX = route.scaleX
                                                transformOrigin = TransformOrigin(0f, 0.5f)
                                            }
                                            .noRippleClickable(
                                                interactionSource = route.interactionSource,
                                                onClick = route.onClick,
                                            )
                                    } ?: Modifier,
                                )
                            }
                    Spacer(modifier = Modifier.height(16.dp))
                    // Transport + progress live in the left column now (the right
                    // column is lyrics-only with a small tab indicator).
                    TickingPlaybackControls(
                        noteAnchorsMs = remember(notesState) { notesState.mapNotNull { it.positionMs }.sorted() },
                        isPlaying = state.isPlaying,
                        onTogglePlayPause = onTogglePlayPause,
                        onSkipNext = onSkipNext,
                        onSkipPrevious = onSkipPrevious,
                        positionMs = positionMs,
                        bufferedMs = bufferedMs,
                        durationMs = state.durationMs,
                        onSeek = onSeek,
                        playInteractionSource = playInteractionSource,
                        nextInteractionSource = nextInteractionSource,
                        playPressed = playPressed,
                        nextPressed = nextPressed,
                        shuffleEnabled = state.shuffleEnabled,
                        onToggleShuffle = onToggleShuffle,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    BottomPills(
                        onQueueClick = { showQueue = true },
                        onDevicesClick = { showDevicesSheet = true },
                        onWriteClick = { showWriteSheet = true },
                        castState = castState,
                        onCastClick = onCastClick,
                        // Pinned at the bottom of the left column; clears the nav bar
                        // since the content runs edge-to-edge.
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding(),
                    )
                        }
                    }
                }

                // RIGHT — lyrics-only: small tab indicator + detail pager + action bar.
                Column(
                    modifier = Modifier
                        // Mirror of the left weight: rests at 1.5 (so columns are 1:1.5,
                        // lyrics-wide) and animates to 1 on cover zoom (→ 1.5:1).
                        .weight(1.5f - 0.5f * coverZoom)
                        .fillMaxHeight(),
                ) {
                    // Small text indicator (collapsed-card feel), not the big button
                    // group. Inset to align with the lyric text below.
                    CompactTextTabs(
                        selected = NowPlayingDetailPage.entries[pagerState.targetPage],
                        onSelect = { page ->
                            pagerScope.launch { pagerState.settleToPage(page.ordinal) }
                        },
                        modifier = Modifier.padding(start = 24.dp),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            // Soft-mask the leading/trailing edges so a page fades
                            // instead of hard-cutting during the horizontal swipe
                            // (matches the Compact lyrics pager).
                            .edgeFade(start = 24.dp, end = 24.dp),
                    ) {
                        HorizontalPager(
                            state = pagerState,
                            beyondViewportPageCount = 1,
                            modifier = Modifier.fillMaxSize(),
                        ) { page ->
                        ExpandedDetailPage(
                            page = NowPlayingDetailPage.entries[page],
                            state = state,
                            positionMs = positionMs,
                            aboutUiState = aboutUiState,
                            notes = notesState,
                            noteSortMode = noteSortMode,
                            onNoteSortModeChange = onNoteSortModeChange,
                            lyricsAutoScroll = lyricsAutoScroll,
                            lyricsRecenterTick = lyricsRecenterTick,
                            onLyricsUserScroll = { lyricsAutoScroll = false },
                            onSeekToMs = { targetMs ->
                                lyricsAutoScroll = true
                                lyricsRecenterTick += 1
                                onSeekToMs(targetMs)
                            },
                            onRetryCanonical = onRetryFetchSongInfo,
                            onSaveNote = onSaveNote,
                            onDeleteNote = onDeleteNote,
                            // Inset the content by the same amount the edgeFade masks,
                            // so the fade lands in the gap — never on the lyric text
                            // (matches the Compact pager's pageModifier).
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 24.dp),
                        )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    // Contextual action bar, pinned at the right-column bottom and
                    // following the current tab. Edge-to-edge masked like the detail
                    // pager; the nav-bar padding lives here (the bottom-most element).
                    StageHeightSlot(
                        height = bottomAccessoryHeight,
                        alpha = 1f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .imePadding(),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .edgeFade(start = 24.dp, end = 24.dp),
                            contentAlignment = Alignment.BottomStart,
                        ) {
                            HorizontalPager(
                                state = accessoryPagerState,
                                userScrollEnabled = false,
                                beyondViewportPageCount = 1,
                                modifier = Modifier.fillMaxSize(),
                            ) { page ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 24.dp),
                                    contentAlignment = Alignment.BottomStart,
                                ) {
                                    when (NowPlayingDetailPage.entries[page]) {
                                        NowPlayingDetailPage.Lyrics -> LyricsActionBar(
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
                                        )
                                        NowPlayingDetailPage.About -> AskGeminiBar(
                                            askState = askState,
                                            onSubmit = onAskQuestion,
                                            onFocus = onAskBarFocused,
                                            onCollapseRequest = onAskBarCollapseRequested,
                                            onDismissError = onDismissAskError,
                                        )
                                        NowPlayingDetailPage.Note ->
                                            Spacer(modifier = Modifier.height(56.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    LyricsSearchSheet(
        state = lyricsSearchState,
        onQueryChange = onLyricsSearchQueryChange,
        onSearch = onSearchLyrics,
        onSelect = onApplyLyricsSearchResult,
        onDismiss = onDismissLyricsSearch,
    )

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

    if (showQueue) {
        ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
            QueueSheet(
                queue = state.queue,
                currentIndex = state.currentQueueIndex,
                onItemClick = { index ->
                    onSkipToQueueItem(index)
                    showQueue = false
                },
                onDismiss = { showQueue = false },
            )
        }
    }

    if (showDevicesSheet) {
        ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
            DevicesSheet(
                providerId = devicesState.providerId,
                devices = devicesState.devices,
                loading = devicesState.loading,
                busyDeviceId = devicesState.busyDeviceId,
                errorMessage = devicesState.errorMessage,
                onRefresh = onRefreshDevices,
                onSelect = onSelectDevice,
                onDismiss = { showDevicesSheet = false },
            )
        }
    }

    if (showWriteSheet) {
        ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
            WriteNoteSheet(
                onSave = onSaveNote,
                positionMs = positionMs,
                trackTitle = state.songTitle,
                onDismiss = { showWriteSheet = false },
            )
        }
    }
}

/**
 * Kickstand (tabletop) player. The foldable is half-open on a HORIZONTAL hinge, so
 * the window splits into an upright TOP half (the "display" — cover + title/artist)
 * and a flat BOTTOM half (the "control deck" — rating + transport + pills). The
 * hinge rectangle from [LocalYoinWindowInfo] positions the split so nothing critical
 * sits under the fold. Like Wide there is no Compact↔Expanded reshape and no
 * drag-to-dismiss; state is LOCAL (Compact / Wide / Tabletop are mutually exclusive
 * in the dispatcher, so each owns its own copies).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun TabletopPlayingContent(
    state: NowPlayingUiState.Playing,
    // 4Hz playhead readers; invoked only by TickingPlaybackControls / lyrics leaves.
    positionMs: () -> Long,
    bufferedMs: () -> Long,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeek: (Float) -> Unit,
    onSeekToMs: (Long) -> Unit = {},
    lyricsSearchState: LyricsSearchState = LyricsSearchState(),
    onOpenLyricsSearch: () -> Unit = {},
    onLyricsSearchQueryChange: (String) -> Unit = {},
    onSearchLyrics: (String) -> Unit = {},
    onApplyLyricsSearchResult: (LyricsSearchResultUi) -> Unit = {},
    onDismissLyricsSearch: () -> Unit = {},
    onTranslateLyrics: () -> Unit = {},
    onApplyLyrics: (String) -> Unit = {},
    onRatingChange: (Float) -> Unit,
    onToggleFavorite: () -> Unit,
    onAddCurrentToPlaylist: () -> Unit,
    onSkipToQueueItem: (Int) -> Unit,
    onToggleShuffle: () -> Unit = {},
    onAlbumClick: (String) -> Unit = {},
    onArtistClick: (String) -> Unit = {},
    onPlaylistClick: (String) -> Unit = {},
    onDismiss: () -> Unit = {},
    dismissFraction: () -> Float = { 0f },
    aboutUiState: AboutUiState = AboutUiState.Idle,
    onRetryFetchSongInfo: () -> Unit = {},
    askState: AskBarState = AskBarState.Idle,
    onAboutOpened: () -> Unit = {},
    onAskQuestion: (String) -> Unit = {},
    onAskBarFocused: () -> Unit = {},
    onAskBarCollapseRequested: () -> Unit = {},
    onDismissAskError: () -> Unit = {},
    stageMode: NowPlayingStageMode = NowPlayingStageMode.Compact,
    stageProgress: NowPlayingStageProgress? = null,
    detailPage: NowPlayingDetailPage = NowPlayingDetailPage.Lyrics,
    onStageModeChange: (NowPlayingStageMode) -> Unit = {},
    onStageBack: () -> Boolean = { false },
    onDetailPageChange: (NowPlayingDetailPage) -> Unit = {},
    notesState: List<SongNote> = emptyList(),
    onSaveNote: (String, Long?) -> Unit = { _, _ -> },
    onDeleteNote: (String) -> Unit = {},
    devicesState: DevicesSheetState = DevicesSheetState(),
    onRefreshDevices: () -> Unit = {},
    onSelectDevice: (YoinDevice) -> Unit = {},
    castState: CastState = CastState.NotAvailable,
    onCastClick: () -> Unit = {},
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier,
) {
    val albumId = state.albumId
    val artistId = state.artistId

    var showQueue by remember { mutableStateOf(false) }
    var showDevicesSheet by remember(state.songId) { mutableStateOf(false) }

    val playInteractionSource = rememberNowPlayingButtonGroupInteractionSource()
    val nextInteractionSource = rememberNowPlayingButtonGroupInteractionSource()
    val playPressed by playInteractionSource.collectIsPressedAsState()
    val nextPressed by nextInteractionSource.collectIsPressedAsState()

    // The expand button shifts emphasis from the identity to the lyrics: the lyric
    // font grows and the title/artist shrink, in place — the control deck never
    // moves. One float drives both.
    var lyricsExpanded by remember(state.songId) { mutableStateOf(false) }
    val lyricsEmphasis by animateFloatAsState(
        targetValue = if (lyricsExpanded) 1f else 0f,
        animationSpec = YoinMotion.slowSpatialSpring(),
        label = "tabletopLyricsEmphasis",
    )
    // The lyric enlarge/shrink runs long after the tap — vote High while the
    // emphasis spring is between its endpoints.
    val emphasisMoving by remember {
        derivedStateOf { lyricsEmphasis > 0.001f && lyricsEmphasis < 0.999f }
    }

    val hinge = LocalYoinWindowInfo.current.hingeBounds
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .voteHighFrameRate(emphasisMoving),
    ) {
        val totalHeight = maxHeight
        val paneWidth = maxWidth
        // Split on the physical hinge: the top pane ends at the hinge top, the fold
        // gap stays empty, the control deck takes the rest. Fall back to a centred
        // 50/50 split if the hinge bounds are ever missing.
        val topHeight = if (hinge != null) {
            with(density) { hinge.top.toDp() }.coerceIn(0.dp, totalHeight)
        } else {
            totalHeight / 2
        }
        val hingeGap = if (hinge != null) {
            with(density) { (hinge.bottom - hinge.top).toDp() }.coerceAtLeast(0.dp)
        } else {
            0.dp
        }
        Column(modifier = Modifier.fillMaxSize()) {
            // TOP — display: cover + title/artist, upright above the hinge.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(topHeight),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(
                            WindowInsets.systemBars.only(
                                WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                            ),
                        )
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val coverSize = minOf(paneWidth * 0.42f - 32.dp, topHeight - 104.dp)
                        .coerceAtLeast(96.dp)
                    AlbumCover(
                        songId = state.songId,
                        coverArtUrl = state.coverArtUrl,
                        // No shared element in Tabletop (same crash-avoidance reason
                        // as Wide — see WidePlayingContent).
                        sharedTransitionScope = null,
                        animatedVisibilityScope = null,
                        // 16dp breathing room on all four sides.
                        modifier = Modifier
                            .padding(16.dp)
                            .size(coverSize),
                    )
                    Spacer(modifier = Modifier.width(20.dp))
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    ) {
                        // "Playing from <album/playlist>" eyebrow, same affordance as
                        // the other layouts' top bars (hinge has no top bar of its own).
                        PlayingFromLabel(
                            activityContext = state.activityContext,
                            fallbackAlbumName = state.albumName,
                            onAlbumClick = onAlbumClick,
                            onArtistClick = onArtistClick,
                            onPlaylistClick = onPlaylistClick,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        // Route stretch, same press language as Compact/Wide.
                        val tabletopTitleRoute = albumId?.let { id ->
                            rememberNowPlayingRouteInteraction(
                                onNavigate = { onAlbumClick(id) },
                            )
                        }
                        Text(
                            text = state.songTitle,
                            style = MaterialTheme.typography.headlineSmall.copy(
                                // Wider travel (24→15 / 16→12 / 0.95→1.70 lyric scale):
                                // the old 24→17 barely registered on the tabletop pane.
                                fontSize = (24f - 9f * lyricsEmphasis).sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = tabletopTitleRoute?.let { route ->
                                Modifier
                                    .graphicsLayer {
                                        scaleX = route.scaleX
                                        transformOrigin = TransformOrigin(0f, 0.5f)
                                    }
                                    .noRippleClickable(
                                        interactionSource = route.interactionSource,
                                        onClick = route.onClick,
                                    )
                            } ?: Modifier,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        val tabletopArtistRoute = artistId?.let { id ->
                            rememberNowPlayingRouteInteraction(
                                onNavigate = { onArtistClick(id) },
                            )
                        }
                        Text(
                            text = state.artist,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontSize = (16f - 4f * lyricsEmphasis).sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = tabletopArtistRoute?.let { route ->
                                Modifier
                                    .graphicsLayer {
                                        scaleX = route.scaleX
                                        transformOrigin = TransformOrigin(0f, 0.5f)
                                    }
                                    .noRippleClickable(
                                        interactionSource = route.interactionSource,
                                        onClick = route.onClick,
                                    )
                            } ?: Modifier,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        // Collapsed: a small 3-line peek. Expanded: a bigger font that
                        // FILLS the column. Both the height (≈3 lines → fill) and the
                        // font size (small → big) lerp on lyricsEmphasis.
                        BoxWithConstraints(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            // Collapsed: the short 3-line box sits centered in the
                            // free space (not crammed under the artist). Expanded: it
                            // fills, so the alignment is moot.
                            contentAlignment = Alignment.Center,
                        ) {
                            val collapsedLyricsHeight = 104.dp
                            val lyricsHeight = lerpDp(
                                collapsedLyricsHeight,
                                maxHeight,
                                lyricsEmphasis,
                            )
                            LyricsDisplay(
                                lyrics = state.lyrics,
                                positionMs = positionMs,
                                loading = state.lyricsLoading,
                                fontScale = 0.95f + 0.75f * lyricsEmphasis,
                                modifier = Modifier
                                    .height(lyricsHeight)
                                    .fillMaxWidth(),
                            )
                        }
                    }
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .windowInsetsPadding(
                            WindowInsets.systemBars.only(
                                WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                            ),
                        )
                        .padding(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = "Close",
                        modifier = Modifier.graphicsLayer {
                            rotationZ = 180f * dismissFraction()
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(hingeGap))

            // BOTTOM — control deck: rating + transport + pills, flat below the hinge.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .windowInsetsPadding(
                        WindowInsets.systemBars.only(
                            WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal,
                        ),
                    )
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.Bottom,
            ) {
                TickingPlaybackControls(
                    noteAnchorsMs = remember(notesState) { notesState.mapNotNull { it.positionMs }.sorted() },
                    isPlaying = state.isPlaying,
                    onTogglePlayPause = onTogglePlayPause,
                    onSkipNext = onSkipNext,
                    onSkipPrevious = onSkipPrevious,
                    positionMs = positionMs,
                    bufferedMs = bufferedMs,
                    durationMs = state.durationMs,
                    onSeek = onSeek,
                    playInteractionSource = playInteractionSource,
                    nextInteractionSource = nextInteractionSource,
                    playPressed = playPressed,
                    nextPressed = nextPressed,
                    shuffleEnabled = state.shuffleEnabled,
                    onToggleShuffle = onToggleShuffle,
                    controlSize = 72.dp,
                    lyricsExpanded = lyricsExpanded,
                    onExpandLyrics = { lyricsExpanded = !lyricsExpanded },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(20.dp))
                BottomPills(
                    onQueueClick = { showQueue = true },
                    onDevicesClick = { showDevicesSheet = true },
                    onWriteClick = {},
                    castState = castState,
                    onCastClick = onCastClick,
                    showWrite = false,
                    pillHeight = 72.dp,
                    forceCapsule = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (showQueue) {
        ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
            QueueSheet(
                queue = state.queue,
                currentIndex = state.currentQueueIndex,
                onItemClick = { index ->
                    onSkipToQueueItem(index)
                    showQueue = false
                },
                onDismiss = { showQueue = false },
            )
        }
    }

    if (showDevicesSheet) {
        ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
            DevicesSheet(
                providerId = devicesState.providerId,
                devices = devicesState.devices,
                loading = devicesState.loading,
                busyDeviceId = devicesState.busyDeviceId,
                errorMessage = devicesState.errorMessage,
                onRefresh = onRefreshDevices,
                onSelect = onSelectDevice,
                onDismiss = { showDevicesSheet = false },
            )
        }
    }
}

/**
 * The wide-layout "NOW PLAYING" header row: a collapse chevron (rotates with the
 * drag-to-dismiss progress) plus a label, spanning both columns. Unlike
 * [StageTopBar] it has no docked-cover / reshape machinery — Wide has no stage
 * reshape, so it is just a back affordance.
 */
@Composable
private fun WideTopBar(
    state: NowPlayingUiState.Playing,
    dismissFraction: () -> Float,
    coverZoom: () -> Float,
    onBack: () -> Unit,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        // heightIn (not a fixed height) so the bar can grow by one line for the
        // zoom-revealed "Title - Artist" subline, then collapse back at rest.
        modifier = modifier.heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = "Close",
                modifier = Modifier.graphicsLayer { rotationZ = 180f * dismissFraction() },
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            // Single-line "PLAYING FROM <kind> <name>" that uses the full bar
            // width (Wide has plenty); falls back to "NOW PLAYING" with no context.
            PlayingFromLabel(
                activityContext = state.activityContext,
                fallbackAlbumName = state.albumName,
                onAlbumClick = onAlbumClick,
                onArtistClick = onArtistClick,
                onPlaylistClick = onPlaylistClick,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            // When the cover is tapped to fill (title/artist in the left column
            // fade out), hand the identity to this subline directly under the
            // label — fading IN on the same 0..1 curve the left block fades OUT.
            val zoom = coverZoom()
            if (zoom > 0f) {
                Text(
                    text = "${state.songTitle} - ${state.artist}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp)
                        .graphicsLayer { alpha = zoom.coerceIn(0f, 1f) },
                )
            }
        }
    }
}

@Composable
private fun StageTopBar(
    state: NowPlayingUiState.Playing,
    stageMode: NowPlayingStageMode,
    detailProgress: Float,
    dismissFraction: () -> Float,
    onBack: () -> Unit,
    onEnterImmersive: () -> Unit,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dockProgress = detailProgress
    val dockCoverAlpha = if (dockProgress >= 1f - HiddenLayerVisibilityThreshold) 1f else 0f
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = if (stageMode == NowPlayingStageMode.Compact) {
                    "Close Now Playing"
                } else {
                    "Back to Now Playing"
                },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(28.dp)
                    .graphicsLayer {
                        rotationZ = 180f * dismissFraction() + 90f * dockProgress
                    },
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        StageHeightSlot(
            height = 48.dp,
            width = lerpDp(0.dp, 48.dp, dockProgress),
            alpha = dockProgress,
        ) {
            val interactionSource = remember { MutableInteractionSource() }
            DockedAlbumCover(
                coverArtUrl = state.coverArtUrl,
                interactionSource = interactionSource,
                modifier = Modifier
                    .size(44.dp)
                    .graphicsLayer {
                        alpha = dockCoverAlpha
                        translationY = 18.dp.toPx() * (1f - dockProgress)
                        val coverScale = 0.78f + 0.22f * dockProgress
                        scaleX = coverScale
                        scaleY = coverScale
                    }
                    .noRippleClickable(
                        interactionSource = interactionSource,
                        onClick = onEnterImmersive,
                    ),
            )
        }
        PlayingFromLabel(
            activityContext = state.activityContext,
            fallbackAlbumName = state.albumName,
            onAlbumClick = onAlbumClick,
            onArtistClick = onArtistClick,
            onPlaylistClick = onPlaylistClick,
            modifier = Modifier
                .weight(1f)
                .graphicsLayer {
                    translationX = 10.dp.toPx() * dockProgress
                },
        )
    }
}

@Composable
private fun CoverTransitionOverlay(
    coverArtUrl: String?,
    progress: Float,
    startX: Dp,
    startY: Dp,
    startSize: Dp,
    endX: Dp,
    endY: Dp,
    endSize: Dp,
    // Start radius tracks the resting hero cover (favourite-button radius, 22dp)
    // so there is no corner-size pop when the flight proxy takes over on expand;
    // the proxy itself stays a plain rounded corner (continuous smoothing is
    // scoped to the static hero). End radius matches the docked cover (Small, 8dp).
    startCornerRadius: Dp = 22.dp,
    endCornerRadius: Dp = 8.dp,
    modifier: Modifier = Modifier,
) {
    if (
        progress <= HiddenLayerVisibilityThreshold ||
        progress >= 1f - HiddenLayerVisibilityThreshold
    ) {
        return
    }
    val clampedProgress = progress.coerceIn(0f, 1f)
    val interactionSource = remember { MutableInteractionSource() }
    // Decode the bitmap ONCE at the flight's large end (fixed requestSizePx):
    // letting Coil resolve at the live, shrinking size resamples high-contrast
    // artwork against the clip edge into shimmering stair-steps.
    val requestSizePx = with(LocalDensity.current) { startSize.roundToPx() }

    // Draw-only transform: the box stays a FIXED startSize and is moved/shrunk
    // entirely in graphicsLayer (translation + scale). The previous per-frame
    // .offset(lerpDp)+.size(lerpDp) forced a full re-layout of the artwork
    // subtree every spring frame — the dominant jank source on expand. The
    // rounded clip lives in this same layer with a corner radius counter-scaled
    // by `s` so the visual radius stays on-spec while the layer is scaled.
    PlainAlbumCover(
        coverArtUrl = coverArtUrl,
        interactionSource = interactionSource,
        shape = RectangleShape,
        border = null,
        filterQuality = FilterQuality.Medium,
        requestSizePx = requestSizePx,
        modifier = modifier
            .offset(x = startX, y = startY)
            .size(startSize)
            .graphicsLayer {
                val p = clampedProgress
                val s = 1f + (endSize.toPx() / startSize.toPx() - 1f) * p
                translationX = (endX.toPx() - startX.toPx()) * p
                translationY = (endY.toPx() - startY.toPx()) * p
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(0f, 0f)
                clip = true
                val visualRadiusPx =
                    startCornerRadius.toPx() + (endCornerRadius.toPx() - startCornerRadius.toPx()) * p
                // Continuous curvature matching the static covers at both
                // endpoints — a circular clip here would pop at hand-off.
                val corner = CornerSize(visualRadiusPx / s)
                shape = ContinuousRoundedCornerShape(corner, corner, corner, corner)
                alpha = 1f
            },
    )
}

@Composable
private fun DockedHeaderText(
    state: NowPlayingUiState.Playing,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(vertical = 2.dp, horizontal = 4.dp)) {
        Text(
            text = when (state.activityContext) {
                is ActivityContext.Album -> "PLAYING FROM ALBUM"
                is ActivityContext.Playlist -> "PLAYING FROM PLAYLIST"
                is ActivityContext.Artist,
                is ActivityContext.LikedSongs,
                ActivityContext.None,
                -> "NOW PLAYING"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = state.songTitle,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StageTabs(
    selected: NowPlayingDetailPage,
    detailProgress: Float,
    height: Dp,
    onSelect: (NowPlayingDetailPage) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clipToBounds(),
    ) {
        // Conditional composition, not just alpha: an alpha-0 layer still
        // hit-tests, so the invisible big buttons were swallowing collapsed-tab
        // clicks (and vice versa).
        if (detailProgress < 1f) {
            CompactTextTabs(
                selected = selected,
                onSelect = onSelect,
                modifier = Modifier.graphicsLayer {
                    alpha = (1f - detailProgress).coerceIn(0f, 1f)
                    translationY = -6.dp.toPx() * detailProgress
                },
            )
        }
        if (detailProgress > 0f) {
            FullscreenTabGroup(
                selected = selected,
                onSelect = onSelect,
                modifier = Modifier.graphicsLayer {
                    alpha = detailProgress
                    translationY = 8.dp.toPx() * (1f - detailProgress)
                },
            )
        }
    }
}

@Composable
private fun CompactTextTabs(
    selected: NowPlayingDetailPage,
    onSelect: (NowPlayingDetailPage) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NowPlayingDetailPage.entries.forEach { page ->
            val isSelected = page == selected
            // Same language as the title/artist rows: a left-anchored
            // text-width stretch (press dips, selection widens) instead of a
            // background indicator — no bounded ripple rectangle either.
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val stretch by animateFloatAsState(
                targetValue = when {
                    pressed -> 0.92f
                    isSelected -> 1.08f
                    else -> 1f
                },
                animationSpec = YoinMotion.defaultSpatialSpec(role = YoinMotionRole.Expressive),
                label = "tabStretch",
            )
            Text(
                text = page.label,
                style = MaterialTheme.typography.labelLarge.let {
                    if (isSelected) it.copy(fontWeight = FontWeight.Bold) else it
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .graphicsLayer {
                        alpha = if (isSelected) 1f else 0.5f
                        scaleX = stretch
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    }
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                    ) { onSelect(page) },
            )
        }
    }
}

@Composable
private fun CompactDetailPage(
    page: NowPlayingDetailPage,
    state: NowPlayingUiState.Playing,
    positionMs: () -> Long,
    aboutUiState: AboutUiState,
    notes: List<SongNote>,
    immersiveProgress: Float,
    onRetryFetchSongInfo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (page) {
        NowPlayingDetailPage.Lyrics -> Box(modifier = modifier.clipToBounds()) {
            LyricsDisplay(
                lyrics = state.lyrics,
                positionMs = positionMs,
                loading = state.lyricsLoading,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = (1f - immersiveProgress).coerceIn(0f, 1f)
                        translationY = -8.dp.toPx() * immersiveProgress
                    },
            )
            OneLineLyricPreview(
                state = state,
                positionMs = positionMs,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = immersiveProgress.coerceIn(0f, 1f)
                        translationY = 8.dp.toPx() * (1f - immersiveProgress)
                    },
            )
        }
        NowPlayingDetailPage.About -> SongInfoDisplay(
            aboutUiState = aboutUiState,
            onRetry = onRetryFetchSongInfo,
            modifier = modifier,
        )
        NowPlayingDetailPage.Note -> NoteCompactPane(
            notes = notes,
            positionMs = positionMs,
            modifier = modifier,
        )
    }
}

@Composable
internal fun OneLineLyricPreview(
    state: NowPlayingUiState.Playing,
    positionMs: () -> Long,
    modifier: Modifier = Modifier,
) {
    // derivedStateOf absorbs the 4Hz position tick: the text recomputes per
    // tick, but this composable only recomposes when the resolved line changes.
    val currentPositionMs by rememberUpdatedState(positionMs)
    val lyricText by remember(state.lyrics, state.showLyricsTranslation) {
        derivedStateOf {
            state.lyrics.currentLyricText(
                positionMs = currentPositionMs(),
                showTranslation = state.showLyricsTranslation,
            )
        }
    }
    val displayText = when {
        state.lyricsLoading -> "Loading lyrics"
        lyricText.isNotBlank() -> lyricText
        else -> state.songTitle
    }
    Box(
        modifier = modifier,
        contentAlignment = Alignment.TopStart,
    ) {
        Text(
            text = displayText,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
            color = if (state.lyricsLoading) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        )
    }
}

@Composable
private fun ExpandedDetailPage(
    page: NowPlayingDetailPage,
    state: NowPlayingUiState.Playing,
    positionMs: () -> Long,
    aboutUiState: AboutUiState,
    notes: List<SongNote>,
    noteSortMode: NoteSortMode,
    onNoteSortModeChange: (NoteSortMode) -> Unit,
    lyricsAutoScroll: Boolean,
    lyricsRecenterTick: Int,
    onLyricsUserScroll: () -> Unit,
    onSeekToMs: (Long) -> Unit,
    onRetryCanonical: () -> Unit,
    onSaveNote: (String, Long?) -> Unit,
    onDeleteNote: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (page) {
        NowPlayingDetailPage.Lyrics -> LyricsFullscreenPane(
            lyrics = state.lyrics,
            positionMs = positionMs,
            loading = state.lyricsLoading,
            showTranslation = state.showLyricsTranslation,
            autoScrollEnabled = lyricsAutoScroll,
            recenterRequestKey = lyricsRecenterTick,
            onUserScroll = onLyricsUserScroll,
            onSeekToMs = onSeekToMs,
            modifier = modifier,
        )
        NowPlayingDetailPage.About -> AboutFullscreenPane(
            aboutUiState = aboutUiState,
            onRetryCanonical = onRetryCanonical,
            modifier = modifier,
        )
        NowPlayingDetailPage.Note -> NoteFullscreenPane(
            notes = notes,
            sortMode = noteSortMode,
            onSortModeChange = onNoteSortModeChange,
            positionMs = positionMs,
            onSeekToMs = onSeekToMs,
            onSave = onSaveNote,
            onDelete = onDeleteNote,
            autoFocusComposer = false,
            modifier = modifier,
        )
    }
}

/**
 * Thin wrapper around [PlaybackControls] that owns the 4Hz playhead reads.
 * [positionMs]/[bufferedMs] are invoked HERE — a dedicated restartable scope —
 * so each position tick recomposes only this transport row, never the
 * enclosing Compact/Wide/Tabletop layout body. Progress/buffered fractions
 * are derived here too, so the callers stay entirely position-free.
 */
@Composable
private fun TickingPlaybackControls(
    isPlaying: Boolean,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    positionMs: () -> Long,
    bufferedMs: () -> Long,
    durationMs: Long,
    onSeek: (Float) -> Unit,
    playInteractionSource: MutableInteractionSource,
    nextInteractionSource: MutableInteractionSource,
    playPressed: Boolean,
    nextPressed: Boolean,
    shuffleEnabled: Boolean = false,
    onToggleShuffle: () -> Unit = {},
    controlSize: Dp = 56.dp,
    lyricsExpanded: Boolean = false,
    onExpandLyrics: (() -> Unit)? = null,
    noteAnchorsMs: List<Long> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val position = positionMs()
    val progress = if (durationMs > 0) {
        (position.toFloat() / durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    val buffered = if (durationMs > 0) {
        (bufferedMs().toFloat() / durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    PlaybackControls(
        noteAnchorsMs = noteAnchorsMs,
        isPlaying = isPlaying,
        onTogglePlayPause = onTogglePlayPause,
        onSkipNext = onSkipNext,
        onSkipPrevious = onSkipPrevious,
        positionMs = position,
        durationMs = durationMs,
        progress = progress,
        buffered = buffered,
        onSeek = onSeek,
        playInteractionSource = playInteractionSource,
        nextInteractionSource = nextInteractionSource,
        playPressed = playPressed,
        nextPressed = nextPressed,
        shuffleEnabled = shuffleEnabled,
        onToggleShuffle = onToggleShuffle,
        controlSize = controlSize,
        lyricsExpanded = lyricsExpanded,
        onExpandLyrics = onExpandLyrics,
        modifier = modifier,
    )
}

@Composable
private fun StageHeightSlot(
    height: Dp,
    alpha: Float,
    modifier: Modifier = Modifier,
    width: Dp? = null,
    content: @Composable () -> Unit,
) {
    val sizedModifier = if (width != null) {
        modifier
            .width(width)
            .height(height)
    } else {
        modifier
            .fillMaxWidth()
            .height(height)
    }
    Box(
        modifier = sizedModifier
            .clipToBounds()
            .graphicsLayer { this.alpha = alpha.coerceIn(0f, 1f) },
    ) {
        content()
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun CompactBottomHero(
    state: NowPlayingUiState.Playing,
    heroBoundsSpec: androidx.compose.animation.core.FiniteAnimationSpec<Rect>,
    titleStretchScale: Float,
    artistStretchScale: Float,
    titleRouteInteraction: NowPlayingRouteInteraction?,
    artistRouteInteraction: NowPlayingRouteInteraction?,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    height: Dp,
    alpha: Float,
    modifier: Modifier = Modifier,
) {
    StageHeightSlot(height = height, alpha = alpha, modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            val titleModifier = if (
                sharedTransitionScope != null &&
                animatedVisibilityScope != null
            ) {
                val sharedContentConfig =
                    rememberActiveOnlySharedContentConfig(
                        animatedVisibilityScope = animatedVisibilityScope,
                    )
                with(sharedTransitionScope) {
                    Modifier
                        .sharedBounds(
                            sharedContentState = rememberSharedContentState(
                                key = "np_title",
                                config = sharedContentConfig,
                            ),
                            animatedVisibilityScope = animatedVisibilityScope,
                            boundsTransform = { _, _ -> heroBoundsSpec },
                        )
                        .fillMaxWidth()
                }
            } else {
                Modifier.fillMaxWidth()
            }
            val titleClickModifier = titleRouteInteraction?.let { routeInteraction ->
                Modifier
                    .graphicsLayer {
                        scaleX = routeInteraction.scaleX
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    }
                    .noRippleClickable(
                        interactionSource = routeInteraction.interactionSource,
                        onClick = routeInteraction.onClick,
                    )
            } ?: Modifier
            val artistModifier = if (
                sharedTransitionScope != null &&
                animatedVisibilityScope != null
            ) {
                val sharedContentConfig =
                    rememberActiveOnlySharedContentConfig(
                        animatedVisibilityScope = animatedVisibilityScope,
                    )
                with(sharedTransitionScope) {
                    Modifier
                        .sharedBounds(
                            sharedContentState = rememberSharedContentState(
                                key = "np_artist",
                                config = sharedContentConfig,
                            ),
                            animatedVisibilityScope = animatedVisibilityScope,
                            boundsTransform = { _, _ -> heroBoundsSpec },
                        )
                        .fillMaxWidth()
                }
            } else {
                Modifier.fillMaxWidth()
            }
            val artistClickModifier = artistRouteInteraction?.let { routeInteraction ->
                Modifier
                    .graphicsLayer {
                        scaleX = routeInteraction.scaleX
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    }
                    .noRippleClickable(
                        interactionSource = routeInteraction.interactionSource,
                        onClick = routeInteraction.onClick,
                    )
            } ?: Modifier
            Box(
                modifier = Modifier
                    .then(artistModifier)
                    .then(artistClickModifier),
            ) {
                Text(
                    text = state.artist,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = MaterialTheme.typography.titleMedium.fontSize * 0.9f,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            scaleX = artistStretchScale
                            transformOrigin = TransformOrigin(0f, 0.5f)
                        },
                )
            }
            Spacer(modifier = Modifier.height(2.dp))

            Box(
                modifier = Modifier
                    .then(titleModifier)
                    .then(titleClickModifier),
            ) {
                NowPlayingMarqueeTitle(
                    text = state.songTitle,
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    stretchScale = titleStretchScale,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun DockedAlbumCover(
    coverArtUrl: String?,
    interactionSource: MutableInteractionSource?,
    modifier: Modifier = Modifier,
) {
    PlainAlbumCover(
        coverArtUrl = coverArtUrl,
        interactionSource = interactionSource,
        modifier = modifier,
        shape = YoinArtworkShapes.NowPlayingCoverDocked,
    )
}

@Composable
internal fun PlainAlbumCover(
    coverArtUrl: String?,
    interactionSource: MutableInteractionSource?,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape,
    border: BorderStroke? = null,
    filterQuality: FilterQuality = FilterQuality.Low,
    requestSizePx: Int? = null,
) {
    ExpressiveMediaArtwork(
        model = coverArtUrl,
        contentDescription = "Album cover",
        modifier = modifier,
        shape = shape,
        fallbackIcon = Icons.Rounded.PlayArrow,
        interactionSource = interactionSource,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = border,
        filterQuality = filterQuality,
        requestSizePx = requestSizePx,
    )
}

private val NowPlayingDetailPage.label: String
    get() = when (this) {
        NowPlayingDetailPage.Lyrics -> "Lyrics"
        NowPlayingDetailPage.About -> "About"
        NowPlayingDetailPage.Note -> "Note"
    }

private fun lerpDp(start: Dp, end: Dp, fraction: Float): Dp =
    start + (end - start) * fraction.coerceIn(0f, 1f)

private fun List<LyricLine>.currentLyricText(
    positionMs: Long,
    showTranslation: Boolean,
): String {
    if (isEmpty()) return ""
    val active = lastOrNull { line ->
        line.startMs?.let { positionMs >= it } == true
    } ?: first()
    return if (showTranslation && !active.translation.isNullOrBlank()) {
        active.translation.orEmpty()
    } else {
        active.text
    }
}

private fun Modifier.tapWithoutConsumingDrag(
    enabled: Boolean = true,
    onTap: () -> Unit,
): Modifier = if (!enabled) {
    this
} else {
    composed {
        val latestOnTap by rememberUpdatedState(onTap)
        pointerInput(Unit) {
            detectTapGestures {
                latestOnTap()
            }
        }
    }
}

private const val NowPlayingRouteNavigationDelayMs = 72L
private const val HiddenLayerVisibilityThreshold = 0.01f

private data class NowPlayingRouteInteraction(
    val interactionSource: MutableInteractionSource,
    val scaleX: Float,
    val onClick: () -> Unit,
)

@Composable
private fun rememberNowPlayingRouteInteraction(
    onNavigate: () -> Unit,
    pressedScale: Float = 1.08f,
    releaseScale: Float = 0.93f,
): NowPlayingRouteInteraction {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val latestOnNavigate by rememberUpdatedState(onNavigate)
    val scope = rememberCoroutineScope()
    var releasePulse by remember { mutableIntStateOf(0) }
    var releaseActive by remember { mutableStateOf(false) }
    var navigationPending by remember { mutableStateOf(false) }

    LaunchedEffect(releasePulse) {
        if (releasePulse == 0) return@LaunchedEffect
        releaseActive = true
        delay(NowPlayingRouteNavigationDelayMs)
        releaseActive = false
    }

    val scaleX by animateFloatAsState(
        targetValue = when {
            isPressed -> pressedScale
            releaseActive -> releaseScale
            else -> 1f
        },
        animationSpec = if (isPressed || releaseActive) {
            YoinMotion.fastSpatialSpec<Float>(role = YoinMotionRole.Expressive)
        } else {
            YoinMotion.defaultSpatialSpec<Float>(role = YoinMotionRole.Expressive)
        },
        label = "nowPlayingRouteScaleX",
    )

    val onClick = {
        if (!navigationPending) {
            releasePulse++
            navigationPending = true
            scope.launch {
                try {
                    delay(NowPlayingRouteNavigationDelayMs)
                    latestOnNavigate()
                } finally {
                    navigationPending = false
                }
            }
        }
    }

    return NowPlayingRouteInteraction(
        interactionSource = interactionSource,
        scaleX = scaleX,
        onClick = onClick,
    )
}

@Composable
private fun PlayingFromLabel(
    activityContext: ActivityContext,
    fallbackAlbumName: String,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    singleLine: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val kindLabel: String?
    val nameLabel: String
    val clickAction: (() -> Unit)?
    when (activityContext) {
        is ActivityContext.Album -> {
            kindLabel = "PLAYING FROM ALBUM"
            nameLabel = activityContext.albumName
            clickAction = { onAlbumClick(activityContext.albumId) }
        }
        is ActivityContext.Playlist -> {
            kindLabel = "PLAYING FROM PLAYLIST"
            nameLabel = activityContext.playlistName
            clickAction = { onPlaylistClick(activityContext.playlistId) }
        }
        is ActivityContext.Artist,
        is ActivityContext.LikedSongs,
        ActivityContext.None,
        -> {
            kindLabel = null
            nameLabel = "NOW PLAYING"
            clickAction = null
        }
    }

    val routeInteraction = clickAction?.let { action ->
        rememberNowPlayingRouteInteraction(onNavigate = action)
    }
    val columnModifier = modifier
        .then(
            routeInteraction?.let { interaction ->
                Modifier
                    .graphicsLayer {
                        scaleX = interaction.scaleX
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    }
                    .noRippleClickable(
                        interactionSource = interaction.interactionSource,
                        onClick = interaction.onClick,
                    )
            } ?: Modifier
        )
        .padding(vertical = 2.dp, horizontal = 4.dp)

    if (singleLine && kindLabel != null) {
        // Wide: kind + name on ONE horizontal line using the full bar width, so
        // the label never wraps to a second row.
        Row(
            modifier = columnModifier,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = kindLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = nameLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    } else {
        Column(modifier = columnModifier) {
            if (kindLabel != null) {
                Text(
                    text = kindLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = nameLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = nameLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FavoriteButton(
    isStarred: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
        val haptics = rememberYoinHaptics()
        val heartColor by animateColorAsState(
            targetValue = if (isStarred) {
                MaterialTheme.colorScheme.onTertiary
            } else {
                MaterialTheme.colorScheme.onTertiaryContainer
            },
            animationSpec = YoinMotion.defaultEffectsSpec(),
            label = "heartColor",
        )
        val heartContainerColor by animateColorAsState(
            targetValue = if (isStarred) {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.tertiaryContainer
            },
            animationSpec = YoinMotion.defaultEffectsSpec(),
            label = "heartContainerColor",
        )

        // Drop FilledIconButton's single-click overload — a secondary
        // pointerInput layered on top breaks the ripple on some API levels.
        // Replicate its look (44dp circle, filled-tonal palette) with a Box
        // and put both click + long-click on the same combinedClickable so
        // gesture dispatch stays on one clickable node.
        val interactionSource = remember { MutableInteractionSource() }
        var tapPulse by remember { mutableIntStateOf(0) }
        val bounce = remember { Animatable(1f) }
        val bounceSpec = YoinMotion.defaultSpatialSpec<Float>()
        // tapPulse drives a short squish-and-spring-back. Peak is higher
        // when transitioning into starred — the "fill" moment — so the
        // feedback reads as a heart pop rather than a generic tap.
        LaunchedEffect(tapPulse) {
            if (tapPulse == 0) return@LaunchedEffect
            val peak = if (isStarred) 1.25f else 1.15f
            bounce.animateTo(peak, tween(durationMillis = 90))
            bounce.animateTo(1f, bounceSpec)
        }
        Box(
            modifier = modifier
                .size(44.dp)
                .minimumTouchTarget()
                .graphicsLayer {
                    scaleX = bounce.value
                    scaleY = bounce.value
                }
                .clip(CircleShape)
                .background(heartContainerColor)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = ripple(),
                    onClick = {
                        tapPulse++
                        if (!isStarred) {
                            haptics.performConfirm()
                        } else {
                            haptics.performTick()
                        }
                        onClick()
                    },
                    onLongClick = onLongClick?.let { longClick ->
                        {
                            haptics.performLongPress()
                            longClick()
                        }
                    },
                    role = Role.Button,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isStarred) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = if (isStarred) "Remove from favorites" else "Add to favorites",
                tint = heartColor,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun AlbumCover(
    songId: String,
    coverArtUrl: String?,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier,
    // Press dip: the biggest tap target on the screen shouldn't be the only
    // silent one — forwarded to ExpressiveMediaArtwork's elasticPress.
    interactionSource: MutableInteractionSource? = null,
) {
    val baseModifier = modifier
        .aspectRatio(1f)
    val coverBoundsSpec = YoinMotion.slowSpatialSpec<Rect>(
        role = YoinMotionRole.Expressive,
        expressiveScheme = MaterialTheme.motionScheme,
    )

    val finalModifier = if (
        sharedTransitionScope != null &&
        animatedVisibilityScope != null
    ) {
        val sharedContentConfig =
            rememberActiveOnlySharedContentConfig(animatedVisibilityScope = animatedVisibilityScope)
        with(sharedTransitionScope) {
            baseModifier.sharedBounds(
                sharedContentState = rememberSharedContentState(
                    key = nowPlayingCoverSharedKey(songId),
                    config = sharedContentConfig,
                ),
                animatedVisibilityScope = animatedVisibilityScope,
                boundsTransform = { _, _ -> coverBoundsSpec },
            )
        }
    } else {
        baseModifier
    }

    ExpressiveMediaArtwork(
        model = coverArtUrl,
        contentDescription = "Album cover",
        modifier = finalModifier,
        shape = YoinArtworkShapes.NowPlayingCover,
        fallbackIcon = Icons.Rounded.PlayArrow,
        interactionSource = interactionSource,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = null,
    )
}

@Composable
private fun NowPlayingMarqueeTitle(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    color: androidx.compose.ui.graphics.Color,
    stretchScale: Float,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val textMeasurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val availableWidthPx = with(density) { maxWidth.roundToPx() }
        val shouldMarquee = remember(text, style, availableWidthPx) {
            if (availableWidthPx <= 0) {
                false
            } else {
                textMeasurer.measure(
                    text = AnnotatedString(text),
                    style = style,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    constraints = Constraints(maxWidth = Constraints.Infinity),
                ).size.width > availableWidthPx
            }
        }

        Box(
            modifier = if (shouldMarquee) {
                Modifier
                    .fillMaxWidth()
                    .clipToBounds()
                    .horizontalFadeMask(edgeWidth = 28.dp)
            } else {
                Modifier.fillMaxWidth()
            },
        ) {
            Text(
                text = text,
                style = style,
                color = color,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = stretchScale
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    }
                    .then(
                        if (shouldMarquee) {
                            Modifier.basicMarquee(
                                iterations = Int.MAX_VALUE,
                                repeatDelayMillis = 2000,
                                initialDelayMillis = 1500,
                            )
                        } else {
                            Modifier
                        },
                    ),
            )
        }
    }
}

// ── Previews ────────────────────────────────────────────────────────────

private val previewPlayingState = NowPlayingUiState.Playing(
    songTitle = "Starlight",
    artist = "Muse",
    albumName = "Black Holes and Revelations",
    coverArtUrl = null,
    isPlaying = true,
    durationMs = 240_000L,
    songId = "1",
    rating = 7.4f,
    isStarred = true,
    lyrics = listOf(
        LyricLine(startMs = 0, text = "Far away…"),
        LyricLine(startMs = 60_000, text = "This ship is taking me far away"),
        LyricLine(startMs = 120_000, text = "Far away from the memories"),
        LyricLine(startMs = 180_000, text = "Of the people who care if I live or die"),
    ),
    showLyricsTranslation = false,
    lyricsActionInFlight = null,
    lyricsLoading = false,
    queue = listOf(
        QueueItem("1", "Starlight", "Muse", null),
        QueueItem("2", "Supermassive Black Hole", "Muse", null),
        QueueItem("3", "Map of the Problematique", "Muse", null),
    ),
    currentQueueIndex = 0,
    shuffleEnabled = false,
    albumId = null,
    artistId = null,
    activityContext = ActivityContext.None,
)

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F, showSystemUi = true)
@Composable
private fun NowPlayingScreenPlayingPreview() {
    YoinTheme {
        NowPlayingScreen(
            uiState = previewPlayingState,
            positionMs = { 125_000L },
            bufferedMs = { 180_000L },
            hasAudioSpectrum = true,
            onTogglePlayPause = {},
            onSkipNext = {},
            onSkipPrevious = {},
            onSeek = {},
            onRatingChange = {},
            onToggleFavorite = {},
            onAddCurrentToPlaylist = {},
            onSkipToQueueItem = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F, showSystemUi = true)
@Composable
private fun NowPlayingScreenIdlePreview() {
    YoinTheme {
        NowPlayingScreen(
            uiState = NowPlayingUiState.Idle,
            positionMs = { 0L },
            bufferedMs = { 0L },
            hasAudioSpectrum = false,
            onTogglePlayPause = {},
            onSkipNext = {},
            onSkipPrevious = {},
            onSeek = {},
            onRatingChange = {},
            onToggleFavorite = {},
            onAddCurrentToPlaylist = {},
            onSkipToQueueItem = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun PlaybackControlsPreview() {
    YoinTheme {
        val playInteractionSource = remember { MutableInteractionSource() }
        val nextInteractionSource = remember { MutableInteractionSource() }
        PlaybackControls(
            isPlaying = false,
            onTogglePlayPause = {},
            onSkipNext = {},
            onSkipPrevious = {},
            positionMs = 96_000L,
            durationMs = 240_000L,
            progress = 0.4f,
            buffered = 0.7f,
            onSeek = {},
            playInteractionSource = playInteractionSource,
            nextInteractionSource = nextInteractionSource,
            playPressed = false,
            nextPressed = false,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun AlbumCoverPreview() {
    YoinTheme {
        AlbumCover(
            songId = "preview-song",
            coverArtUrl = null,
            modifier = Modifier.size(300.dp),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun FavoriteButtonPreview() {
    YoinTheme {
        FavoriteButton(
            isStarred = true,
            onClick = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun BottomPillsPreview() {
    YoinTheme {
        BottomPills(
            onQueueClick = {},
            onDevicesClick = {},
            onWriteClick = {},
        )
    }
}

/**
 * Animate to [target] and pin the landing: the stage reshape remeasures the
 * pager mid-flight, which can strand animateScrollToPage between pages —
 * finish with an exact snap when that happens.
 */
private suspend fun PagerState.settleToPage(target: Int) {
    if (currentPage == target && currentPageOffsetFraction == 0f) return
    animateScrollToPage(target)
    if (currentPage != target || currentPageOffsetFraction != 0f) {
        scrollToPage(target)
    }
}
