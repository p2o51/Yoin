package com.gpo.yoin.ui.nowplaying
import com.gpo.yoin.ui.experience.rememberYoinHaptics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
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
import com.gpo.yoin.player.VisualizerData
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
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.experience.ReportMotionPressure
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinShapeTokens
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
    visualizerData: VisualizerData,
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
    stageBackProgress: () -> Float = { 0f },
    detailPage: NowPlayingDetailPage = NowPlayingDetailPage.Lyrics,
    onStageModeChange: (NowPlayingStageMode) -> Unit = {},
    onStageBack: () -> Boolean = { false },
    onDetailPageChange: (NowPlayingDetailPage) -> Unit = {},
    notesState: List<SongNote> = emptyList(),
    onSaveNote: (String) -> Unit = {},
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
    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer
    val background = MaterialTheme.colorScheme.background

    ReportMotionPressure(
        tag = "now-playing",
        isHighPressure = uiState is NowPlayingUiState.Playing &&
            uiState.isPlaying &&
            visualizerData.fft.isNotEmpty(),
    )

    ProvideYoinMotionRole(role = YoinMotionRole.Expressive) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(surfaceContainer, background),
                    ),
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
                    stageBackProgress = stageBackProgress,
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
                )
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
                .clip(RoundedCornerShape(28.dp))
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
                .clip(RoundedCornerShape(28.dp))
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
@Composable
private fun PlayingContent(
    state: NowPlayingUiState.Playing,
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
    stageBackProgress: () -> Float = { 0f },
    detailPage: NowPlayingDetailPage = NowPlayingDetailPage.Lyrics,
    onStageModeChange: (NowPlayingStageMode) -> Unit = {},
    onStageBack: () -> Boolean = { false },
    onDetailPageChange: (NowPlayingDetailPage) -> Unit = {},
    notesState: List<SongNote> = emptyList(),
    onSaveNote: (String) -> Unit = {},
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
    val progress = if (state.durationMs > 0) {
        (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    val buffered = if (state.durationMs > 0) {
        (state.bufferedMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }

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

    val stageTransition = updateTransition(targetState = stageMode, label = "nowPlayingStage")
    val rawDetailProgress by stageTransition.animateFloat(
        transitionSpec = { YoinMotion.defaultSpatialSpec(role = YoinMotionRole.Expressive) },
        label = "detailProgress",
    ) { stage ->
        if (stage == NowPlayingStageMode.Expanded) 1f else 0f
    }
    val rawImmersiveProgress by stageTransition.animateFloat(
        transitionSpec = { YoinMotion.defaultSpatialSpec(role = YoinMotionRole.Expressive) },
        label = "immersiveProgress",
    ) { stage ->
        if (stage == NowPlayingStageMode.Immersive) 1f else 0f
    }
    val backProgress = stageBackProgress().coerceIn(0f, 1f)
    val detailProgress = (rawDetailProgress * (1f - backProgress)).coerceIn(0f, 1f)
    val immersiveProgress = (rawImmersiveProgress * (1f - backProgress)).coerceIn(0f, 1f)
    val compactProgress = (1f - detailProgress).coerceIn(0f, 1f)

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

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
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

            Box(modifier = Modifier.weight(1f)) {
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
                            AlbumCover(
                                songId = state.songId,
                                coverArtUrl = state.coverArtUrl,
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = animatedVisibilityScope,
                                modifier = Modifier
                                    .weight(1f)
                                    .graphicsLayer {
                                        alpha = if (detailProgress > HiddenLayerVisibilityThreshold) {
                                            0f
                                        } else {
                                            1f
                                        }
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
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(lerpDp(16.dp, 8.dp, detailProgress)))

                    StageTabs(
                        selected = NowPlayingDetailPage.entries[pagerState.currentPage],
                        detailProgress = detailProgress,
                        height = tabHeight,
                        onSelect = { page ->
                            onDetailPageChange(page)
                            pagerScope.launch { pagerState.animateScrollToPage(page.ordinal) }
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
                                        aboutUiState = aboutUiState,
                                        notes = notesState,
                                        lyricsAutoScroll = lyricsAutoScroll,
                                        lyricsRecenterTick = lyricsRecenterTick,
                                        onLyricsUserScroll = { lyricsAutoScroll = false },
                                        onSeekToMs = { positionMs ->
                                            lyricsAutoScroll = true
                                            lyricsRecenterTick += 1
                                            lyricsActions.onSeekToMs(positionMs)
                                        },
                                        onRetryCanonical = onRetryFetchSongInfo,
                                        onSaveNote = onSaveNote,
                                        onDeleteNote = onDeleteNote,
                                        modifier = pageModifier.graphicsLayer {
                                            alpha = detailProgress
                                            translationY = 16.dp.toPx() * (1f - detailProgress)
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
                        PlaybackControls(
                            isPlaying = state.isPlaying,
                            onTogglePlayPause = playbackActions.onTogglePlayPause,
                            onSkipNext = playbackActions.onSkipNext,
                            onSkipPrevious = playbackActions.onSkipPrevious,
                            positionMs = state.positionMs,
                            durationMs = state.durationMs,
                            progress = progress,
                            buffered = buffered,
                            onSeek = playbackActions.onSeek,
                            playInteractionSource = playInteractionSource,
                            nextInteractionSource = nextInteractionSource,
                            playPressed = playPressed,
                            nextPressed = nextPressed,
                            shuffleEnabled = state.shuffleEnabled,
                            onToggleShuffle = playbackActions.onToggleShuffle,
                            modifier = Modifier.padding(horizontal = horizontalPadding),
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
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = horizontalPadding),
                            contentAlignment = Alignment.BottomStart,
                        ) {
                            BottomPills(
                                onQueueClick = { showQueue = true },
                                onDevicesClick = { showDevicesSheet = true },
                                onWriteClick = { showWriteSheet = true },
                                castState = castState,
                                onCastClick = onCastClick,
                                modifier = Modifier.graphicsLayer {
                                    alpha = compactProgress
                                },
                            )
                            HorizontalPager(
                                state = pagerState,
                                userScrollEnabled = false,
                                beyondViewportPageCount = 1,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        alpha = detailProgress
                                    },
                            ) { page ->
                                Box(
                                    modifier = Modifier.fillMaxSize(),
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

        if (lyricsSearchState.isOpen) {
            LyricsSearchSheet(
                state = lyricsSearchState,
                onQueryChange = lyricsActions.onLyricsSearchQueryChange,
                onSearch = lyricsActions.onSearchLyrics,
                onSelect = lyricsActions.onApplyLyricsSearchResult,
                onDismiss = lyricsActions.onDismissLyricsSearch,
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
                onDismiss = { showWriteSheet = false },
            )
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

    PlainAlbumCover(
        coverArtUrl = coverArtUrl,
        interactionSource = interactionSource,
        modifier = modifier
            .offset(
                x = lerpDp(startX, endX, clampedProgress),
                y = lerpDp(startY, endY, clampedProgress),
            )
            .size(lerpDp(startSize, endSize, clampedProgress))
            .graphicsLayer {
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
        CompactTextTabs(
            selected = selected,
            onSelect = onSelect,
            modifier = Modifier.graphicsLayer {
                alpha = (1f - detailProgress).coerceIn(0f, 1f)
                translationY = -6.dp.toPx() * detailProgress
            },
        )
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
            Text(
                text = page.label,
                style = MaterialTheme.typography.labelLarge.let {
                    if (isSelected) it.copy(fontWeight = FontWeight.Bold) else it
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .graphicsLayer { alpha = if (isSelected) 1f else 0.5f }
                    .clickable { onSelect(page) },
            )
        }
    }
}

@Composable
private fun CompactDetailPage(
    page: NowPlayingDetailPage,
    state: NowPlayingUiState.Playing,
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
                positionMs = state.positionMs,
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
            modifier = modifier,
        )
    }
}

@Composable
private fun OneLineLyricPreview(
    state: NowPlayingUiState.Playing,
    modifier: Modifier = Modifier,
) {
    val lyricText = remember(state.lyrics, state.positionMs, state.showLyricsTranslation) {
        state.lyrics.currentLyricText(
            positionMs = state.positionMs,
            showTranslation = state.showLyricsTranslation,
        )
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
    aboutUiState: AboutUiState,
    notes: List<SongNote>,
    lyricsAutoScroll: Boolean,
    lyricsRecenterTick: Int,
    onLyricsUserScroll: () -> Unit,
    onSeekToMs: (Long) -> Unit,
    onRetryCanonical: () -> Unit,
    onSaveNote: (String) -> Unit,
    onDeleteNote: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (page) {
        NowPlayingDetailPage.Lyrics -> LyricsFullscreenPane(
            lyrics = state.lyrics,
            positionMs = state.positionMs,
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
            onSave = onSaveNote,
            onDelete = onDeleteNote,
            autoFocusComposer = false,
            modifier = modifier,
        )
    }
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
        shape = YoinShapeTokens.Medium,
    )
}

@Composable
private fun PlainAlbumCover(
    coverArtUrl: String?,
    interactionSource: MutableInteractionSource?,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = YoinShapeTokens.Large,
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
        border = null,
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
    pointerInput(onTap) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            val up = waitForUpOrCancellation()
            if (up != null) {
                onTap()
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteButton(
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
private fun AlbumCover(
    songId: String,
    coverArtUrl: String?,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier,
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
        shape = YoinShapeTokens.Large,
        fallbackIcon = Icons.Rounded.PlayArrow,
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
    positionMs = 125_000L,
    durationMs = 240_000L,
    bufferedMs = 180_000L,
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

private val previewVisualizerData = VisualizerData(
    fft = FloatArray(32) { i ->
        val t = i.toFloat() / 32
        (kotlin.math.sin(t * Math.PI * 2).toFloat() * 0.4f + 0.5f)
            .coerceIn(0f, 1f)
    },
)

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F, showSystemUi = true)
@Composable
private fun NowPlayingScreenPlayingPreview() {
    YoinTheme {
        NowPlayingScreen(
            uiState = previewPlayingState,
            visualizerData = previewVisualizerData,
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
            visualizerData = VisualizerData.Empty,
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
