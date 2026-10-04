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
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
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
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.layout.onGloballyPositioned
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import android.os.SystemClock
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
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
import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.ui.component.TrackLibraryButton
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.player.CastState
import com.gpo.yoin.player.PlayMode
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.symbols.rememberFavoriteSymbolPainter
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
import com.gpo.yoin.ui.experience.ProvidePreviewWindow
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
    // Back-preview scale, read in the draw phase only (a gesture frame must
    // not recompose the player).
    contentScale: () -> Float = { 1f },
    // +1 = forward skip (next / auto-advance), −1 = back — forwarded to the
    // single-column body for its directional cover ride-in.
    skipDirection: Int = 1,
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
    onAddCurrentToLibrary: () -> Unit = {},
    onAddCurrentToPlaylist: () -> Unit,
    onSkipToQueueItem: (Int) -> Unit,
    onCyclePlayMode: () -> Unit = {},
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
    // Which frame this window gives Now Playing (断点交接 §3.4): the phone
    // column (also inside the side panel), the enlarged phone, the two-column
    // player or the tabletop split. The host decides; this only draws it.
    presentation: NowPlayingPresentation = NowPlayingPresentation.Phone,
    enlarged: NowPlayingEnlargedSpec? = null,
    topBarAction: (@Composable () -> Unit)? = null,
    onClaimLyricIdleHint: () -> Boolean = { false },
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
                    onAddCurrentToLibrary = onAddCurrentToLibrary,
                    onAddCurrentToPlaylist = onAddCurrentToPlaylist,
                    onSkipToQueueItem = onSkipToQueueItem,
                    onCyclePlayMode = onCyclePlayMode,
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
                    skipDirection = skipDirection,
                    presentation = presentation,
                    enlarged = enlarged,
                    topBarAction = topBarAction,
                    onClaimLyricIdleHint = onClaimLyricIdleHint,
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
                imageVector = YoinSymbols.ChevronDown,
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
                imageVector = YoinSymbols.ChevronDown,
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
    val onCyclePlayMode: () -> Unit,
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
    // Back-preview scale, read in the draw phase only (a gesture frame must
    // not recompose the player).
    contentScale: () -> Float = { 1f },
    // +1 = forward skip (next / auto-advance), −1 = back — forwarded to the
    // single-column body for its directional cover ride-in.
    skipDirection: Int = 1,
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
    onAddCurrentToLibrary: () -> Unit = {},
    onAddCurrentToPlaylist: () -> Unit,
    onSkipToQueueItem: (Int) -> Unit,
    onCyclePlayMode: () -> Unit = {},
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
    presentation: NowPlayingPresentation = NowPlayingPresentation.Phone,
    enlarged: NowPlayingEnlargedSpec? = null,
    topBarAction: (@Composable () -> Unit)? = null,
    onClaimLyricIdleHint: () -> Boolean = { false },
    modifier: Modifier = Modifier,
) {
    // Animate posture/size swaps (fold ↔ unfold, enter/leave tabletop). Official
    // adaptive guidance: a posture change is just a state change — animate it with
    // AnimatedContent (fade + slight scale, spring). Deliberately NO shared elements
    // / lookahead here: a posture change is the most lookahead-hostile moment (the
    // hinge resizes the window mid-measure) and that path crashes the shell
    // ButtonGroup on a real foldable. A plain fade+scale needs no lookahead pass.
    AnimatedContent(
        // The host resolved the frame (NowPlayingPresentation): the two-column
        // player only where the window/pane is Wide AND tall; the hinge split
        // on Tabletop; everything else — phone, side panel, enlarged phone —
        // is the single column (the panel ⇄ enlarged switch is the host's
        // container animation, not a posture swap).
        targetState = when (presentation) {
            NowPlayingPresentation.DualPane,
            NowPlayingPresentation.Tabletop,
            NowPlayingPresentation.Landscape,
            -> presentation
            NowPlayingPresentation.Phone,
            NowPlayingPresentation.Panel,
            NowPlayingPresentation.Enlarged,
            -> NowPlayingPresentation.Phone
        },
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
    ) { body ->
    // Posture swaps animate with no finger down — vote High for their
    // duration or the fold/unfold spring paces at ARR-Normal (60Hz).
    val posturing = transition.currentState != transition.targetState
    when (body) {
        // Two-column player: Wide (≥ 840 window or pane) and tall only.
        NowPlayingPresentation.DualPane -> WidePlayingContent(
            state = state,
            skipDirection = skipDirection,
            contentScale = contentScale,
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
            onAddCurrentToLibrary = onAddCurrentToLibrary,
            onAddCurrentToPlaylist = onAddCurrentToPlaylist,
            onSkipToQueueItem = onSkipToQueueItem,
            onCyclePlayMode = onCyclePlayMode,
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
            topBarAction = topBarAction,
            modifier = modifier.voteHighFrameRate(posturing),
        )
        // Landscape handset (LandscapeNP): cover left, the phone's column on
        // the right.
        NowPlayingPresentation.Landscape -> LandscapePlayingContent(
            state = state,
            skipDirection = skipDirection,
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
            onAddCurrentToLibrary = onAddCurrentToLibrary,
            onAddCurrentToPlaylist = onAddCurrentToPlaylist,
            onSkipToQueueItem = onSkipToQueueItem,
            onCyclePlayMode = onCyclePlayMode,
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
            contentScale = contentScale,
            onStageBack = onStageBack,
            modifier = modifier.voteHighFrameRate(posturing),
        )
        NowPlayingPresentation.Tabletop -> TabletopPlayingContent(
            state = state,
            skipDirection = skipDirection,
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
            onAddCurrentToLibrary = onAddCurrentToLibrary,
            onAddCurrentToPlaylist = onAddCurrentToPlaylist,
            onSkipToQueueItem = onSkipToQueueItem,
            onCyclePlayMode = onCyclePlayMode,
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
        NowPlayingPresentation.Phone,
        NowPlayingPresentation.Panel,
        NowPlayingPresentation.Enlarged,
        -> CompactPlayingContent(
            state = state,
            positionMs = positionMs,
            bufferedMs = bufferedMs,
            contentScale = contentScale,
            skipDirection = skipDirection,
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
            onAddCurrentToLibrary = onAddCurrentToLibrary,
            onAddCurrentToPlaylist = onAddCurrentToPlaylist,
            onSkipToQueueItem = onSkipToQueueItem,
            onCyclePlayMode = onCyclePlayMode,
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
            enlarged = if (presentation == NowPlayingPresentation.Enlarged) enlarged else null,
            topBarAction = topBarAction,
            onClaimLyricIdleHint = onClaimLyricIdleHint,
            modifier = modifier.voteHighFrameRate(posturing),
        )
    }
    }
}

/**
 * The single-column player — phones, outer foldable screens, narrow split-screen,
 * the Medium side panel (a phone page in a phone-width container) and, with
 * [enlarged], the enlarged phone of a Medium window (断点交接 §3.4). Short
 * windows fold the lyric window to one tappable line (§3.1).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactPlayingContent(
    state: NowPlayingUiState.Playing,
    // 4Hz playhead readers; invoked only by TickingPlaybackControls / lyrics leaves.
    positionMs: () -> Long,
    bufferedMs: () -> Long,
    // Predictive-back collapse preview: the STAGE recedes to this scale; the
    // controls, title/artist and pills stay fixed. 1f = inert.
    // Back-preview scale, read in the draw phase only (a gesture frame must
    // not recompose the player).
    contentScale: () -> Float = { 1f },
    // +1 = forward skip (next / auto-advance), −1 = back — the cover ride-in's
    // travel direction on track change.
    skipDirection: Int = 1,
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
    onAddCurrentToLibrary: () -> Unit = {},
    onAddCurrentToPlaylist: () -> Unit,
    onSkipToQueueItem: (Int) -> Unit,
    onCyclePlayMode: () -> Unit = {},
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
    // Medium's "enlarged phone" (断点交接 §3.4): the same column, centred and
    // scaled up. Null = a phone (or the side panel, which IS a phone).
    enlarged: NowPlayingEnlargedSpec? = null,
    // Corner button at the top bar's end (panel → full screen, and back).
    topBarAction: (@Composable () -> Unit)? = null,
    // Claims today's single "tap to expand" hint (once a day, §3.1).
    onClaimLyricIdleHint: () -> Boolean = { false },
    modifier: Modifier = Modifier,
) {
    val lyricsSearchBarState = rememberSearchBarState()
    val motionProfile = LocalMotionProfile.current
    val heroStretchSpec = if (motionProfile == MotionProfile.Full) {
        YoinMotion.slowSpatialSpec<Float>(role = YoinMotionRole.Expressive)
    } else {
        YoinMotion.fastSpatialSpec<Float>(role = YoinMotionRole.Expressive)
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
        onCyclePlayMode = onCyclePlayMode,
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

    // Auto-immersive (断点交接 §3.2): playing + Lyrics + synced lyrics + 5s
    // without a touch → ONLY the four lyric tools step away. A touch, a manual
    // lyrics scroll (recenter lit) or a pause brings them straight back.
    val interactionClock = remember { longArrayOf(0L) }
    var lyricToolsIdle by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .voteHighFrameRate(stageMoving)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        interactionClock[0] = SystemClock.uptimeMillis()
                        if (lyricToolsIdle) lyricToolsIdle = false
                    }
                }
            }
            .padding(WindowInsets.systemBars.asPaddingValues()),
    ) {
        val horizontalPadding = 24.dp
        // Enlarged phone: the column is centred at its own width; every width
        // below reads stageWidth. A phone keeps reading maxWidth, unchanged.
        val stageMaxWidth = enlarged?.let { minOf(maxWidth, it.columnWidth + horizontalPadding * 2) } ?: maxWidth
        val ratingColumn = enlarged?.ratingColumn ?: 56.dp
        val stageControlSize = enlarged?.controlSize ?: 56.dp
        // Height budget (adaptive-principles §6, NowPlayingBudget.kt): measured
        // content + compressible gaps. Short windows spend the air under the
        // cover first, then the upper gaps, then fold the lyrics to ONE
        // tappable line (the 16:9 gate, §3.1), and only then shrink the cover.
        val castVisible = state.serviceFeatures.supportsYoinCast && castState !is CastState.NotAvailable
        val lastLyricFrame = remember { arrayOfNulls<LyricFrame>(1) }
        val firstMetrics = rememberStageColumnMetrics(stageMaxWidth, stageControlSize, castVisible)
        val firstBudget = remember(maxWidth, maxHeight, firstMetrics, enlarged) {
            resolveStageColumnBudget(maxWidth, maxHeight, firstMetrics, enlarged, lastLyricFrame[0])
        }
        // A height-bound enlarged cover pulls the stage in with it, which can
        // move the time labels under the wave: measure again at that width.
        val metrics = rememberStageColumnMetrics(firstBudget.stageWidth, stageControlSize, castVisible)
        val budget = if (metrics == firstMetrics) {
            firstBudget
        } else {
            remember(maxWidth, maxHeight, metrics, enlarged) {
                resolveStageColumnBudget(maxWidth, maxHeight, metrics, enlarged, lastLyricFrame[0])
            }
        }
        SideEffect { lastLyricFrame[0] = budget.rest.lyricFrame }
        val restPose = budget.rest
        // Focus (tap the cover) exists only where the cover visibly grows;
        // elsewhere the cover is a plain image and a stale Immersive drops
        // back to Compact.
        val focusOffered = budget.focusOffered
        val focusPose = budget.focus ?: restPose
        LaunchedEffect(focusOffered, stageMode) {
            if (!focusOffered && stageMode == NowPlayingStageMode.Immersive) {
                onStageModeChange(NowPlayingStageMode.Compact)
            }
        }
        val lyricOneLine = restPose.lyricFrame == LyricFrame.OneLine
        // In focus the lyric slot keeps its list where four lines still fit
        // (tablet portrait), else it cross-fades to the one-line preview.
        val focusKeepsList = focusPose.lyricFrame == LyricFrame.List
        val stageWidth = budget.stageWidth
        val stageOffsetX = (maxWidth - stageWidth) / 2
        // Enlarged tablet: the lyric tools ride at the end of the tab row even
        // at rest (TabletPortraitNP).
        val toolsInCompactTabs = enlarged != null && !lyricOneLine
        // Rest ⇄ focus by immersive, then ⇄ the Expanded layout by detail.
        fun stage(rest: Dp, focus: Dp, expanded: Dp): Dp =
            lerpDp(lerpDp(rest, focus, immersiveProgress), expanded, detailProgress)
        val visibleCoverHeight = lerpDp(restPose.cover, focusPose.cover, immersiveProgress)
        val coverRowHeight = stage(restPose.cover, focusPose.cover, 0.dp)
        val coverRowAlpha = compactProgress
        val topBarHeight = stage(restPose.topBar, focusPose.topBar, ExpandedTopBarHeight)
        val tabHeight = stage(restPose.tabs, focusPose.tabs, ExpandedTabRowHeight)
        val tabSpacerHeight = stage(restPose.tabGap, focusPose.tabGap, ExpandedTabGap)
        val ratingRetreatProgress = maxOf(immersiveProgress, detailProgress)
        val ratingGap = lerpDp(12.dp, 0.dp, ratingRetreatProgress)
        val ratingSlotWidth = lerpDp(ratingColumn, 0.dp, ratingRetreatProgress)
        val lyricsPageSelected = detailPage == NowPlayingDetailPage.Lyrics
        val idleEligible = state.isPlaying && hasSyncedLyrics && lyricsAutoScroll && lyricsPageSelected &&
            (stageMode == NowPlayingStageMode.Expanded || toolsInCompactTabs)
        LaunchedEffect(idleEligible) {
            lyricToolsIdle = false
            if (!idleEligible) return@LaunchedEffect
            interactionClock[0] = SystemClock.uptimeMillis()
            while (true) {
                val elapsed = SystemClock.uptimeMillis() - interactionClock[0]
                if (elapsed >= LyricToolsIdleMs) {
                    if (!lyricToolsIdle) lyricToolsIdle = true
                    delay(LyricToolsIdlePollMs)
                } else {
                    delay(LyricToolsIdleMs - elapsed)
                }
            }
        }
        val lyricToolsAlpha by animateFloatAsState(
            targetValue = if (lyricToolsIdle) 0f else 1f,
            animationSpec = YoinMotion.defaultEffectsSpec(role = YoinMotionRole.Standard),
            label = "lyricToolsAlpha",
        )
        // Bottom tools collapse their slot when they move up (16:9) or step
        // away (idle): the title sinks to where the tools' bottom edge was and
        // the lyrics grow into the space.
        val accessoryToolsCollapsed = stageMode == NowPlayingStageMode.Expanded && lyricsPageSelected &&
            (lyricOneLine || lyricToolsIdle)
        // The Expanded page's accessory (lyric tools / Ask / Note) animates
        // between its own heights; at rest the slot is the budget's pill slot.
        val expandedAccessoryTargetHeight = when {
            detailPage == NowPlayingDetailPage.About && askState is AskBarState.Focused -> 276.dp
            accessoryToolsCollapsed -> 0.dp
            else -> ExpandedAccessoryHeight
        }
        val expandedAccessoryHeight by animateDpAsState(
            targetValue = expandedAccessoryTargetHeight,
            animationSpec = YoinMotion.defaultSpatialSpec(role = YoinMotionRole.Expressive),
            label = "nowPlayingBottomAccessoryHeight",
        )
        val bottomAccessoryHeight = stage(restPose.accessory, focusPose.accessory, expandedAccessoryHeight)
        val controlsHeight = stage(restPose.controls, focusPose.controls, 0.dp)
        val coverSpacerHeight = stage(restPose.coverGap, focusPose.coverGap, ExpandedCoverGap)
        // The title keeps its resting slot in Expanded.
        val heroHeight = stage(restPose.hero, focusPose.hero, restPose.hero)
        // Add back the space the surrounding slots will release at Expanded.
        // Round each slot separately, exactly as their layout modifiers do, so
        // the lazy list receives identical pixel constraints on every frame.
        val lyricsViewportGrowthPx = with(LocalDensity.current) {
            topBarHeight.roundToPx() - ExpandedTopBarHeight.roundToPx() +
                coverRowHeight.roundToPx() + controlsHeight.roundToPx() +
                coverSpacerHeight.roundToPx() - ExpandedCoverGap.roundToPx() +
                tabHeight.roundToPx() - ExpandedTabRowHeight.roundToPx() +
                tabSpacerHeight.roundToPx() - ExpandedTabGap.roundToPx() +
                heroHeight.roundToPx() - restPose.hero.roundToPx() +
                bottomAccessoryHeight.roundToPx() - expandedAccessoryHeight.roundToPx()
        }
        // The cover as drawn (the budget's row IS the cover square).
        val compactCoverSize = visibleCoverHeight
        val lyricTools: @Composable (iconSize: Dp) -> Unit = { iconSize ->
            LyricsActionBar(
                searchModifier = Modifier.onGloballyPositioned {
                    lyricsSearchBarState.collapsedCoords = it
                },
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
                iconSize = iconSize,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(stageWidth)
                .align(Alignment.TopCenter),
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
                // The docked cover goes to focus where focus exists, else
                // back to the resting player.
                onEnterImmersive = {
                    onDetailPageChange(NowPlayingDetailPage.Lyrics)
                    onStageModeChange(
                        if (focusOffered) NowPlayingStageMode.Immersive else NowPlayingStageMode.Compact,
                    )
                },
                onAlbumClick = navigationActions.onAlbumClick,
                onArtistClick = navigationActions.onArtistClick,
                onPlaylistClick = navigationActions.onPlaylistClick,
                trailingAction = topBarAction,
                height = topBarHeight,
                modifier = Modifier.padding(horizontal = horizontalPadding),
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    // Collapse-preview recede: ONLY the stage (cover / tabs / lyrics)
                    // steps back; the controls, title/artist and pills below stay put.
                    .graphicsLayer {
                        val scale = contentScale()
                        scaleX = scale
                        scaleY = scale
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
                                    revealDirection = skipDirection,
                                    sharedTransitionScope = sharedTransitionScope,
                                    animatedVisibilityScope = animatedVisibilityScope,
                                    sharedTransitionEnabled = detailProgress <= HiddenLayerVisibilityThreshold,
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
                                        .then(
                                            // Tap = focus the artwork, only where it
                                            // visibly grows; else a plain image.
                                            if (focusOffered || stageMode == NowPlayingStageMode.Immersive) {
                                                Modifier.noRippleClickable(
                                                    interactionSource = coverClickSource,
                                                    onClickLabel = if (stageMode == NowPlayingStageMode.Immersive) {
                                                        CoverFocusExitLabel
                                                    } else {
                                                        CoverFocusLabel
                                                    },
                                                    onClick = {
                                                        if (stageMode == NowPlayingStageMode.Immersive) {
                                                            onStageModeChange(NowPlayingStageMode.Compact)
                                                        } else {
                                                            onDetailPageChange(NowPlayingDetailPage.Lyrics)
                                                            onStageModeChange(NowPlayingStageMode.Immersive)
                                                        }
                                                    },
                                                )
                                            } else {
                                                Modifier
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
                                        .width(ratingColumn)
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

                                    if (state.serviceFeatures.supportsFavorites) {
                                        FavoriteButton(
                                            isStarred = state.isStarred,
                                            actionLabel = if (state.isStarred) {
                                                state.serviceFeatures.removeLabel
                                            } else {
                                                state.serviceFeatures.saveLabel
                                            },
                                            onClick = onToggleFavorite,
                                            onLongClick = onAddCurrentToPlaylist,
                                        )
                                    }
                                    if (Capability.LIBRARY_ADD in state.serviceFeatures.capabilities) {
                                        TrackLibraryButton(
                                            membership = state.libraryMembership,
                                            isWorking = state.libraryActionInFlight,
                                            onClick = onAddCurrentToLibrary,
                                        )
                                    }

                                    // Room for the heart's tap-bounce (scales to
                                    // 1.25×) so its overflow isn't cut by the
                                    // rating slot's clipToBounds at this edge.
                                    Spacer(modifier = Modifier.height(6.dp))
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(coverSpacerHeight))

                    val tabsSelected = NowPlayingDetailPage.entries[pagerState.targetPage]
                    StageTabs(
                        selected = tabsSelected,
                        detailProgress = detailProgress,
                        height = tabHeight,
                        onSelect = { page ->
                            pagerScope.launch { pagerState.settleToPage(page.ordinal) }
                        },
                        // 16:9: the expanded page keeps TEXT tabs, tools at their end.
                        textOnly = lyricOneLine,
                        tools = if (
                            tabsSelected == NowPlayingDetailPage.Lyrics &&
                            (lyricOneLine || toolsInCompactTabs)
                        ) {
                            {
                                Box(
                                    modifier = Modifier.graphicsLayer {
                                        // Top tools fade IN PLACE when idle — nothing moves.
                                        alpha = lyricToolsAlpha *
                                            if (lyricOneLine) detailProgress else 1f
                                    },
                                ) {
                                    lyricTools(if (lyricOneLine) ShortTabToolSize else EnlargedTabToolSize)
                                }
                            }
                        } else {
                            null
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
                            // The one-line row is not a pager page: swipe only
                            // once the lyric page has expanded.
                            userScrollEnabled = stageMode != NowPlayingStageMode.Immersive &&
                                !(lyricOneLine && stageMode == NowPlayingStageMode.Compact),
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
                                    immersiveProgress = if (focusKeepsList) 0f else immersiveProgress,
                                    onRetryFetchSongInfo = onRetryFetchSongInfo,
                                    modifier = pageModifier.graphicsLayer {
                                        alpha = if (lyricOneLine) 0f else compactProgress
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
                                        lyricsViewportGrowthPx = lyricsViewportGrowthPx,
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

                        if (lyricOneLine && detailProgress < 1f - HiddenLayerVisibilityThreshold) {
                            // 16:9: the whole row IS the expand button (no
                            // separate key — it sat too far from the lyric).
                            OneLineLyricRow(
                                state = state,
                                positionMs = positionMs,
                                onExpand = {
                                    onDetailPageChange(NowPlayingDetailPage.Lyrics)
                                    onStageModeChange(NowPlayingStageMode.Expanded)
                                },
                                onClaimIdleHint = onClaimLyricIdleHint,
                                // Centred in its slot (the budget gives it ≥ 36,
                                // the text needs 32), focus included.
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .fillMaxWidth()
                                    .heightIn(max = OneLineLyricRowHeight)
                                    .padding(horizontal = horizontalPadding)
                                    .graphicsLayer { alpha = compactProgress },
                            )
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
                            playMode = state.playMode,
                            onCyclePlayMode = playbackActions.onCyclePlayMode,
                            controlSize = stageControlSize,
                            modifier = Modifier
                                .padding(horizontal = horizontalPadding)
                                .graphicsLayer {
                                    translationY = 48.dp.toPx() * detailProgress
                                },
                        )
                    }

                    CompactBottomHero(
                        state = state,
                        heroBoundsSpec = YoinMotion.slowSpatialSpec<Rect>(
                            role = YoinMotionRole.Expressive,
                            expressiveScheme = MaterialTheme.motionScheme,
                        ),
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        titleStretchScale = titleStretchScale,
                        artistStretchScale = artistStretchScale,
                        titleRouteInteraction = titleRouteInteraction,
                        artistRouteInteraction = artistRouteInteraction,
                        height = heroHeight,
                        alpha = 1f,
                        oneLine = budget.heroOneLine,
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
                                supportsYoinCast = state.serviceFeatures.supportsYoinCast,
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
                                            // 16:9 moved the tools up beside the tabs.
                                            NowPlayingDetailPage.Lyrics -> if (!lyricOneLine) {
                                                Box(modifier = Modifier.graphicsLayer { alpha = lyricToolsAlpha }) {
                                                    lyricTools(52.dp)
                                                }
                                            }
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

        // Lyrics takes ownership as soon as its stage starts moving. Keeping
        // the opening shared cover enabled would bypass the hero's alpha/clip
        // and leave a full-size cover in the shared overlay until it settles.
        CoverTransitionOverlay(
            coverArtUrl = state.coverArtUrl,
            progress = detailProgress,
            startX = stageOffsetX + horizontalPadding,
            startY = lerpDp(restPose.topBar, focusPose.topBar, immersiveProgress),
            startSize = compactCoverSize,
            endX = stageOffsetX + horizontalPadding + 56.dp,
            // The docked cover's 48dp slot is centred in the 56dp bar.
            endY = (ExpandedTopBarHeight - DockedCoverSlot) / 2,
            endSize = 44.dp,
        )

        LyricsSearchSheet(
            searchBarState = lyricsSearchBarState,
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
 * Two-column player: the Wide (≥ 840dp, tall) Full state — reached from the
 * side panel with its corner toggle ([topBarAction]), or directly when no
 * panel fits (断点交接 §3.4). LEFT is passive (square cover + horizontal
 * rating with the favorite pinned at the row end + title/artist); RIGHT is
 * the always-expanded detail (tabs + Lyrics/About/Note pager + transport +
 * pills). The right column is inherently "expanded", so there is no
 * Compact↔Expanded reshape, no CoverTransitionOverlay, and no drag-to-dismiss
 * (gated off in NowPlayingOverlayHost). State here is LOCAL: the single- and
 * two-column bodies are mutually exclusive in the dispatcher, so each owns its
 * copies.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WidePlayingContent(
    state: NowPlayingUiState.Playing,
    skipDirection: Int,
    // Full → panel predictive-back preview: the two-column body (below the
    // top bar) recedes uniformly, the same language as the single column's
    // stage scale.
    // Back-preview scale, read in the draw phase only (a gesture frame must
    // not recompose the player).
    contentScale: () -> Float = { 1f },
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
    onAddCurrentToLibrary: () -> Unit = {},
    onAddCurrentToPlaylist: () -> Unit,
    onSkipToQueueItem: (Int) -> Unit,
    onCyclePlayMode: () -> Unit = {},
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
    // The panel's corner toggle (back to the side panel), at the end of the
    // top bar; null where no panel exists.
    topBarAction: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val lyricsSearchBarState = rememberSearchBarState()
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
    // Auto-immersive (断点交接 §3.2), same rule as the single column: the
    // lyric tools step away after 5s without a touch while synced lyrics play.
    val interactionClock = remember { longArrayOf(0L) }
    var lyricToolsIdle by remember { mutableStateOf(false) }
    val idleEligible = state.isPlaying && hasSyncedLyrics && lyricsAutoScroll &&
        detailPage == NowPlayingDetailPage.Lyrics
    LaunchedEffect(idleEligible) {
        lyricToolsIdle = false
        if (!idleEligible) return@LaunchedEffect
        interactionClock[0] = SystemClock.uptimeMillis()
        while (true) {
            val elapsed = SystemClock.uptimeMillis() - interactionClock[0]
            if (elapsed >= LyricToolsIdleMs) {
                if (!lyricToolsIdle) lyricToolsIdle = true
                delay(LyricToolsIdlePollMs)
            } else {
                delay(LyricToolsIdleMs - elapsed)
            }
        }
    }
    val lyricToolsAlpha by animateFloatAsState(
        targetValue = if (lyricToolsIdle) 0f else 1f,
        animationSpec = YoinMotion.defaultEffectsSpec(role = YoinMotionRole.Standard),
        label = "wideLyricToolsAlpha",
    )
    // Small by default; the Ask Gemini bar grows when focused (matches Compact).
    val bottomAccessoryTargetHeight = when {
        detailPage == NowPlayingDetailPage.About && askState is AskBarState.Focused -> 276.dp
        detailPage == NowPlayingDetailPage.Lyrics && lyricToolsIdle -> 0.dp
        else -> 68.dp
    }
    val bottomAccessoryHeight by animateDpAsState(
        targetValue = bottomAccessoryTargetHeight,
        animationSpec = YoinMotion.defaultSpatialSpec(role = YoinMotionRole.Expressive),
        label = "wideBottomAccessoryHeight",
    )

    // Tapping the cover = FOCUS, the same shared Immersive stage as the
    // single column (it survives Panel ⇄ Full and track changes): the rating
    // and title retreat, the inner gaps close and the cover grows — offered
    // only where it visibly does (NowPlayingBudget.kt).
    val coverInteraction = remember { MutableInteractionSource() }
    val castVisible = state.serviceFeatures.supportsYoinCast && castState !is CastState.NotAvailable
    val density = LocalDensity.current
    val navBottom = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    val dualMetrics = rememberDualPaneMetrics(castVisible = castVisible, navBottom = navBottom)

    BoxWithConstraints(
        // Edge-to-edge: the parent NowPlayingScreen gradient fills behind the
        // system bars. The old blanket padding(systemBars) inset the whole Box and
        // exposed the light window background as a WHITE band in the nav-bar
        // region. Inset only the TOP + sides on the content; the bottom stays
        // full-bleed so the gradient reaches the nav-bar edge (the button group
        // gets its own navigationBarsPadding to stay tappable).
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        interactionClock[0] = SystemClock.uptimeMillis()
                        if (lyricToolsIdle) lyricToolsIdle = false
                    }
                }
            },
    ) {
        val layoutDirection = LocalLayoutDirection.current
        val frameInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
        val topInset = with(density) { frameInsets.getTop(density).toDp() }
        val sideInsets = with(density) {
            (frameInsets.getLeft(density, layoutDirection) + frameInsets.getRight(density, layoutDirection)).toDp()
        }
        // The left stack is ALWAYS laid out at DualPaneStackWidth: the cover
        // takes the height the measured stack leaves, never the stack's width.
        val budget = remember(maxWidth, maxHeight, topInset, sideInsets, dualMetrics) {
            resolveDualPaneBudget(
                rowWidth = maxWidth - sideInsets - WideFramePadding * 2,
                contentHeight = maxHeight - topInset,
                barHeight = WideTopBarHeight,
                metrics = dualMetrics,
            )
        }
        val focusOffered = budget.focusOffered
        LaunchedEffect(focusOffered, stageMode) {
            if (!focusOffered && stageMode == NowPlayingStageMode.Immersive) {
                onStageModeChange(NowPlayingStageMode.Compact)
            }
        }
        val restPose = budget.rest
        val focusPose = budget.focus ?: restPose
        val focus = if (focusOffered) stageProgress?.immersive ?: 0f else 0f
        val titleInBar = budget.titleForm == DualPaneTitleForm.TopBar
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(frameInsets)
                .padding(
                    start = WideFramePadding,
                    end = WideFramePadding,
                    top = budget.frameTop,
                    bottom = budget.frameBottom,
                ),
        ) {
            // Full-width "NOW PLAYING" row spanning both columns.
            WideTopBar(
                state = state,
                dismissFraction = dismissFraction,
                // "Title - Artist" under the label: while focused, or always
                // when the window is too short for the title block.
                subline = { if (titleInBar) 1f else focus },
                onBack = onDismiss,
                onAlbumClick = onAlbumClick,
                onArtistClick = onArtistClick,
                onPlaylistClick = onPlaylistClick,
                modifier = Modifier.fillMaxWidth(),
                trailingAction = topBarAction,
            )
            Spacer(modifier = Modifier.height(budget.barGap))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .graphicsLayer {
                        val scale = contentScale()
                        scaleX = scale
                        scaleY = scale
                    },
            ) {
                // LEFT — passive: cover + rating/favorite, transport, title/artist,
                // with the Queue/Devices/Write pills pinned at the bottom. The
                // identity block is centred in the space above the pills via two
                // weight spacers. The slot follows a focused cover (no empty
                // bands); everything under the cover stays 312 wide.
                val slotWidth = lerpDp(restPose.slot, focusPose.slot, focus)
                val coverSize = lerpDp(restPose.cover, focusPose.cover, focus)
                // Rating + title retreat (fade + height collapse) in focus.
                // Clip OUTSIDE the collapsing layout, or the fading row draws
                // over the transport while it folds.
                val retreat = Modifier
                    .clipToBounds()
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        val h = (placeable.height * (1f - focus)).roundToInt().coerceAtLeast(0)
                        layout(placeable.width, h) { placeable.place(0, 0) }
                    }
                    .graphicsLayer { alpha = (1f - focus).coerceIn(0f, 1f) }
                Column(
                    modifier = Modifier
                        .width(slotWidth + WideColumnGap)
                        .fillMaxHeight()
                        .padding(end = WideColumnGap),
                    // One start edge for the cover and everything under it —
                    // a focused cover grows to the end, the stack stays put.
                    horizontalAlignment = Alignment.Start,
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    AlbumCover(
                        songId = state.songId,
                        coverArtUrl = state.coverArtUrl,
                        revealDirection = skipDirection,
                        // NO shared element in Wide. A fillMaxWidth shared cover
                        // resolves to an UNBOUNDED width in the shared-transition
                        // lookahead and propagates Constraints.Infinity into the
                        // shell bar's measurement — which crashes. Drop the
                        // mini→cover morph here; the cover simply appears.
                        sharedTransitionScope = null,
                        animatedVisibilityScope = null,
                        interactionSource = coverInteraction,
                        modifier = Modifier
                            .size(coverSize)
                            .then(
                                if (focusOffered || stageMode == NowPlayingStageMode.Immersive) {
                                    Modifier.noRippleClickable(
                                        interactionSource = coverInteraction,
                                        onClickLabel = if (stageMode == NowPlayingStageMode.Immersive) {
                                            CoverFocusExitLabel
                                        } else {
                                            CoverFocusLabel
                                        },
                                    ) {
                                        onStageModeChange(
                                            if (stageMode == NowPlayingStageMode.Immersive) {
                                                NowPlayingStageMode.Compact
                                            } else {
                                                NowPlayingStageMode.Immersive
                                            },
                                        )
                                    }
                                } else {
                                    Modifier
                                },
                            ),
                    )
                    Spacer(modifier = Modifier.height(lerpDp(restPose.coverToRating, focusPose.coverToRating, focus)))
                    // Always-visible full rating slider; the favorite is
                    // pinned at the trailing end.
                    Row(
                        modifier = Modifier
                            .width(DualPaneStackWidth)
                            .then(retreat),
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
                        if (state.serviceFeatures.supportsFavorites) {
                            FavoriteButton(
                                isStarred = state.isStarred,
                                actionLabel = if (state.isStarred) {
                                    state.serviceFeatures.removeLabel
                                } else {
                                    state.serviceFeatures.saveLabel
                                },
                                onClick = onToggleFavorite,
                                onLongClick = onAddCurrentToPlaylist,
                            )
                        }
                        if (Capability.LIBRARY_ADD in state.serviceFeatures.capabilities) {
                            TrackLibraryButton(
                                membership = state.libraryMembership,
                                isWorking = state.libraryActionInFlight,
                                onClick = onAddCurrentToLibrary,
                            )
                        }
                        // Room for the heart's tap-bounce (scales to 1.25×):
                        // it's pinned at the trailing edge, so its right
                        // overflow would be cut by the retreat's clip.
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Spacer(
                        modifier = Modifier.height(
                            lerpDp(restPose.ratingToTransport, focusPose.ratingToTransport, focus),
                        ),
                    )
                    TickingPlaybackControls(
                        noteAnchorsMs = remember(notesState) {
                            notesState.mapNotNull { it.positionMs }.sorted()
                        },
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
                        playMode = state.playMode,
                        onCyclePlayMode = onCyclePlayMode,
                        modifier = Modifier.width(DualPaneStackWidth),
                    )
                    if (!titleInBar) {
                        Spacer(
                            modifier = Modifier.height(
                                lerpDp(restPose.transportToTitle, focusPose.transportToTitle, focus),
                            ),
                        )
                        // Title/artist sit right above the pills, as on every
                        // Now Playing (TabletNP). Same press language as
                        // Compact: route stretch, no ripple.
                        Column(
                            modifier = Modifier
                                .width(DualPaneStackWidth)
                                .then(retreat),
                        ) {
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
                            Spacer(modifier = Modifier.height(2.dp))
                            val wideTitleRoute = albumId?.let { id ->
                                rememberNowPlayingRouteInteraction(
                                    onNavigate = { onAlbumClick(id) },
                                )
                            }
                            val titleRouteModifier = wideTitleRoute?.let { route ->
                                Modifier
                                    .graphicsLayer {
                                        scaleX = route.scaleX
                                        transformOrigin = TransformOrigin(0f, 0.5f)
                                    }
                                    .noRippleClickable(
                                        interactionSource = route.interactionSource,
                                        onClick = route.onClick,
                                    )
                            } ?: Modifier
                            // A short window sets the title on one marquee
                            // line (decided per window, never per song).
                            if (budget.titleForm == DualPaneTitleForm.TwoLines) {
                                Text(
                                    text = state.songTitle,
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = titleRouteModifier,
                                )
                            } else {
                                NowPlayingMarqueeTitle(
                                    text = state.songTitle,
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    stretchScale = 1f,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .then(titleRouteModifier),
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    BottomPills(
                        supportsYoinCast = state.serviceFeatures.supportsYoinCast,
                        onQueueClick = { showQueue = true },
                        onDevicesClick = { showDevicesSheet = true },
                        onWriteClick = { showWriteSheet = true },
                        castState = castState,
                        onCastClick = onCastClick,
                        // Pinned at the bottom of the left column; clears the nav bar
                        // since the content runs edge-to-edge.
                        modifier = Modifier
                            .width(DualPaneStackWidth)
                            .navigationBarsPadding(),
                    )
                }

                // RIGHT — lyrics-only: small tab indicator + detail pager + action bar.
                Column(
                    modifier = Modifier
                        // Lyrics take whatever the controls-sized left column leaves.
                        .weight(1f)
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
                                            modifier = Modifier.graphicsLayer { alpha = lyricToolsAlpha },
                                            searchModifier = Modifier.onGloballyPositioned {
                                                lyricsSearchBarState.collapsedCoords = it
                                            },
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
        searchBarState = lyricsSearchBarState,
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
 * Landscape handset (LandscapeNP, 断点交接 §3.5): the cover and a horizontal
 * rating on the left, the phone's column on the right — header, ONE tappable
 * current lyric line (the 16:9 rule: no tabs at rest), transport, title and
 * artist on one row, pills. A tap on the line runs the SAME Expanded stage as
 * portrait (the overlay host owns it and its back layer): the transport and
 * pills fold away and the page takes the column, text tabs + tools on top.
 * A tap on the cover is focus (the rating row retreats, the cover takes the
 * height). The column clears a camera hole on its far edge.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LandscapePlayingContent(
    state: NowPlayingUiState.Playing,
    skipDirection: Int,
    positionMs: () -> Long,
    bufferedMs: () -> Long,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeek: (Float) -> Unit,
    onSeekToMs: (Long) -> Unit,
    lyricsSearchState: LyricsSearchState,
    onOpenLyricsSearch: () -> Unit,
    onLyricsSearchQueryChange: (String) -> Unit,
    onSearchLyrics: (String) -> Unit,
    onApplyLyricsSearchResult: (LyricsSearchResultUi) -> Unit,
    onDismissLyricsSearch: () -> Unit,
    onTranslateLyrics: () -> Unit,
    onApplyLyrics: (String) -> Unit,
    onRatingChange: (Float) -> Unit,
    onToggleFavorite: () -> Unit,
    onAddCurrentToLibrary: () -> Unit = {},
    onAddCurrentToPlaylist: () -> Unit,
    onSkipToQueueItem: (Int) -> Unit,
    onCyclePlayMode: () -> Unit,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    onDismiss: () -> Unit,
    dismissFraction: () -> Float,
    aboutUiState: AboutUiState,
    onRetryFetchSongInfo: () -> Unit,
    askState: AskBarState,
    onAboutOpened: () -> Unit,
    onAskQuestion: (String) -> Unit,
    onAskBarFocused: () -> Unit,
    onAskBarCollapseRequested: () -> Unit,
    onDismissAskError: () -> Unit,
    stageMode: NowPlayingStageMode,
    stageProgress: NowPlayingStageProgress?,
    detailPage: NowPlayingDetailPage,
    onStageModeChange: (NowPlayingStageMode) -> Unit,
    onDetailPageChange: (NowPlayingDetailPage) -> Unit,
    notesState: List<SongNote>,
    onSaveNote: (String, Long?) -> Unit,
    noteSortMode: NoteSortMode,
    onNoteSortModeChange: (NoteSortMode) -> Unit,
    onDeleteNote: (String) -> Unit,
    devicesState: DevicesSheetState,
    onRefreshDevices: () -> Unit,
    onSelectDevice: (YoinDevice) -> Unit,
    castState: CastState,
    onCastClick: () -> Unit,
    contentScale: () -> Float = { 1f },
    // The header arrow in Expanded steps the stage back, like system back.
    onStageBack: () -> Boolean = { false },
    modifier: Modifier = Modifier,
) {
    val lyricsSearchBarState = rememberSearchBarState()
    var showQueue by remember { mutableStateOf(false) }
    var showDevicesSheet by remember(state.songId) { mutableStateOf(false) }
    var showWriteSheet by remember(state.songId) { mutableStateOf(false) }
    var showApplyDialog by remember(state.songId) { mutableStateOf(false) }
    var lyricsAutoScroll by remember(state.songId) { mutableStateOf(true) }
    var lyricsRecenterTick by remember(state.songId) { mutableIntStateOf(0) }
    val hasSyncedLyrics = remember(state.lyrics) { state.lyrics.any { it.startMs != null } }
    val playInteractionSource = rememberNowPlayingButtonGroupInteractionSource()
    val nextInteractionSource = rememberNowPlayingButtonGroupInteractionSource()
    val playPressed by playInteractionSource.collectIsPressedAsState()
    val nextPressed by nextInteractionSource.collectIsPressedAsState()
    val resolvedStageProgress = stageProgress ?: rememberNowPlayingStageProgress(stageMode)
    val detailProgress = resolvedStageProgress.detail
    val expanded = stageMode == NowPlayingStageMode.Expanded

    val pagerState = rememberPagerState(initialPage = detailPage.ordinal, pageCount = { 3 })
    val pagerScope = rememberCoroutineScope()
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

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            // The cover side also clears the camera cutout (a cover under the
            // hole reads as damage); lyrics may run under a cutout on the
            // other edge, as on any phone.
            .windowInsetsPadding(
                WindowInsets.systemBars.union(WindowInsets.displayCutout.only(WindowInsetsSides.Start)),
            )
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        // The cover takes the height the rating row leaves, capped so the
        // right column keeps a phone's width. Tap = focus (the shared
        // Immersive stage): the rating row retreats and the cover takes the
        // whole height — offered only where it visibly grows.
        val coverBudget = remember(maxWidth, maxHeight) { resolveLandscapeCover(maxWidth, maxHeight) }
        val focusOffered = coverBudget.focus != null
        LaunchedEffect(focusOffered, stageMode) {
            if (!focusOffered && stageMode == NowPlayingStageMode.Immersive) {
                onStageModeChange(NowPlayingStageMode.Compact)
            }
        }
        val focus = if (focusOffered) resolvedStageProgress.immersive else 0f
        val coverSide = lerpDp(coverBudget.rest, coverBudget.focus ?: coverBudget.rest, focus)
        val coverInteraction = remember { MutableInteractionSource() }
        val endCutout = WindowInsets.displayCutout.only(WindowInsetsSides.End).asPaddingValues()
            .calculateEndPadding(LocalLayoutDirection.current)
        val rightColumnWidth = maxWidth - coverSide - LandscapeColumnGap - endCutout
        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .width(coverSide)
                    .fillMaxHeight()
                    .graphicsLayer {
                        val scale = contentScale()
                        scaleX = scale
                        scaleY = scale
                    },
            ) {
                AlbumCover(
                    songId = state.songId,
                    coverArtUrl = state.coverArtUrl,
                    revealDirection = skipDirection,
                    // Lookahead-safe: no shared element outside the phone column.
                    sharedTransitionScope = null,
                    animatedVisibilityScope = null,
                    interactionSource = coverInteraction,
                    modifier = Modifier
                        .size(coverSide)
                        .then(
                            if (focusOffered || stageMode == NowPlayingStageMode.Immersive) {
                                Modifier.noRippleClickable(
                                    interactionSource = coverInteraction,
                                    onClickLabel = if (stageMode == NowPlayingStageMode.Immersive) {
                                        CoverFocusExitLabel
                                    } else {
                                        CoverFocusLabel
                                    },
                                ) {
                                    if (stageMode == NowPlayingStageMode.Immersive) {
                                        onStageModeChange(NowPlayingStageMode.Compact)
                                    } else {
                                        onDetailPageChange(NowPlayingDetailPage.Lyrics)
                                        onStageModeChange(NowPlayingStageMode.Immersive)
                                    }
                                }
                            } else {
                                Modifier
                            },
                        ),
                )
                Spacer(modifier = Modifier.height(lerpDp(LandscapeRatingGap, 0.dp, focus)))
                // The rating row folds down and away in focus.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(lerpDp(LandscapeRatingRowHeight, 0.dp, focus))
                        .clipToBounds()
                        .graphicsLayer {
                            alpha = (1f - focus).coerceIn(0f, 1f)
                            translationY = LandscapeRatingRowHeight.toPx() * focus
                        },
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
                    if (state.serviceFeatures.supportsFavorites) {
                        Spacer(modifier = Modifier.width(12.dp))
                        FavoriteButton(
                            isStarred = state.isStarred,
                            actionLabel = if (state.isStarred) {
                                state.serviceFeatures.removeLabel
                            } else {
                                state.serviceFeatures.saveLabel
                            },
                            onClick = onToggleFavorite,
                            onLongClick = onAddCurrentToPlaylist,
                        )
                    }
                    if (Capability.LIBRARY_ADD in state.serviceFeatures.capabilities) {
                        Spacer(modifier = Modifier.width(12.dp))
                        TrackLibraryButton(
                            membership = state.libraryMembership,
                            isWorking = state.libraryActionInFlight,
                            onClick = onAddCurrentToLibrary,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(LandscapeColumnGap))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    // The controls clear a camera hole on the far edge too
                    // (the play-mode button sits at the row's end).
                    .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.End)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        // Expanded: the explicit back steps the stage back, the
                        // same destination as system back (AGENTS.md).
                        onClick = { if (!expanded || !onStageBack()) onDismiss() },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = YoinSymbols.ChevronDown,
                            contentDescription = if (expanded) "Back to Now Playing" else "Close Now Playing",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.graphicsLayer {
                                rotationZ = 180f * dismissFraction() + 90f * detailProgress
                            },
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    PlayingFromLabel(
                        activityContext = state.activityContext,
                        fallbackAlbumName = state.albumName,
                        onAlbumClick = onAlbumClick,
                        onArtistClick = onArtistClick,
                        onPlaylistClick = onPlaylistClick,
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                // Text tabs + lyric tools exist only on the expanded page: at
                // rest the column shows ONE tappable lyric line (the 16:9 rule).
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(lerpDp(0.dp, LandscapeExpandedTabRow, detailProgress))
                        .clipToBounds()
                        .graphicsLayer { alpha = detailProgress },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CompactTextTabs(
                        selected = NowPlayingDetailPage.entries[pagerState.targetPage],
                        onSelect = { page ->
                            pagerScope.launch { pagerState.settleToPage(page.ordinal) }
                        },
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (expanded &&
                        NowPlayingDetailPage.entries[pagerState.targetPage] == NowPlayingDetailPage.Lyrics
                    ) {
                        LyricsActionBar(
                            searchModifier = Modifier.onGloballyPositioned {
                                lyricsSearchBarState.collapsedCoords = it
                            },
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
                            iconSize = ShortTabToolSize,
                            modifier = Modifier.graphicsLayer { alpha = detailProgress },
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .edgeFade(start = 0.dp, end = 12.dp),
                ) {
                    HorizontalPager(
                        state = pagerState,
                        beyondViewportPageCount = 1,
                        userScrollEnabled = expanded,
                        modifier = Modifier.fillMaxSize(),
                    ) { page ->
                        val detailPageEntry = NowPlayingDetailPage.entries[page]
                        if (expanded || detailProgress > HiddenLayerVisibilityThreshold) {
                            ExpandedDetailPage(
                                page = detailPageEntry,
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
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer { alpha = detailProgress },
                            )
                        }
                    }
                    if (!expanded || detailProgress < 1f - HiddenLayerVisibilityThreshold) {
                        // The current line, centred; the whole row opens the
                        // lyrics page. It stays in focus too.
                        OneLineLyricRow(
                            state = state,
                            positionMs = positionMs,
                            onExpand = {
                                onDetailPageChange(NowPlayingDetailPage.Lyrics)
                                onStageModeChange(NowPlayingStageMode.Expanded)
                            },
                            onClaimIdleHint = { false },
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .fillMaxWidth()
                                .heightIn(max = OneLineLyricRowHeight)
                                .graphicsLayer { alpha = 1f - detailProgress },
                        )
                    }
                }
                // Transport + pills fold away while the page is expanded. The
                // slot is the measured transport: a column under 360dp moves
                // the time labels below the wave, and they must not be cut.
                val transportSlot = playbackControlsHeight(
                    controlSize = LandscapeControlSize,
                    width = rightColumnWidth,
                    labelLine = with(LocalDensity.current) {
                        MaterialTheme.typography.labelLarge.lineHeight.toDp()
                    },
                ) + LandscapeTransportTopPad
                StageHeightSlot(
                    height = lerpDp(transportSlot, 0.dp, detailProgress),
                    alpha = 1f - detailProgress,
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
                        playMode = state.playMode,
                        onCyclePlayMode = onCyclePlayMode,
                        controlSize = LandscapeControlSize,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = LandscapeTransportTopPad),
                    )
                }
                // Title and artist share one row (vertical space is the
                // scarce axis here).
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(HeroOneLineGap),
                ) {
                    NowPlayingMarqueeTitle(
                        text = state.songTitle,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        stretchScale = 1f,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .alignByBaseline(),
                    )
                    Text(
                        text = state.artist,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .maxWidthFraction(HeroOneLineArtistShare)
                            .alignByBaseline(),
                    )
                }
                StageHeightSlot(
                    height = when {
                        expanded && detailPage == NowPlayingDetailPage.About -> 68.dp
                        else -> lerpDp(LandscapePillsHeight, 0.dp, detailProgress)
                    },
                    alpha = 1f,
                    modifier = Modifier.imePadding(),
                ) {
                    if (expanded && detailPage == NowPlayingDetailPage.About) {
                        AskGeminiBar(
                            askState = askState,
                            onSubmit = onAskQuestion,
                            onFocus = onAskBarFocused,
                            onCollapseRequest = onAskBarCollapseRequested,
                            onDismissError = onDismissAskError,
                        )
                    } else {
                        BottomPills(
                            supportsYoinCast = state.serviceFeatures.supportsYoinCast,
                            onQueueClick = { showQueue = true },
                            onDevicesClick = { showDevicesSheet = true },
                            onWriteClick = { showWriteSheet = true },
                            castState = castState,
                            onCastClick = onCastClick,
                            pillHeight = 40.dp,
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .graphicsLayer { alpha = 1f - detailProgress },
                        )
                    }
                }
            }
        }
    }

    LyricsSearchSheet(
        searchBarState = lyricsSearchBarState,
        state = lyricsSearchState,
        onQueryChange = onLyricsSearchQueryChange,
        onSearch = onSearchLyrics,
        onSelect = onApplyLyricsSearchResult,
        onDismiss = onDismissLyricsSearch,
    )
    if (showApplyDialog) {
        LyricsApplyDialog(
            initialText = remember(state.songId, state.lyrics) { state.lyrics.toEditableLyricsText() },
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

private val LandscapeRatingRowHeight = 48.dp
private val LandscapeRatingGap = 12.dp
private val LandscapeColumnGap = 28.dp
private val LandscapeControlSize = 48.dp
private val LandscapeTransportTopPad = 6.dp
private val LandscapePillsHeight = 46.dp
private val LandscapeExpandedTabRow = 44.dp

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
    skipDirection: Int,
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
    onAddCurrentToLibrary: () -> Unit = {},
    onAddCurrentToPlaylist: () -> Unit,
    onSkipToQueueItem: (Int) -> Unit,
    onCyclePlayMode: () -> Unit = {},
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
                        revealDirection = skipDirection,
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
                                // Pinned to designed geometry: the formula assumes
                                // fontScale 1 — dividing it back out stops the user's
                                // fontScale double-applying inside the fixed tabletop
                                // pane (lyrics keep the user's scale; they scroll).
                                fontSize = (24f - 9f * lyricsEmphasis).sp / density.fontScale,
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
                                fontSize = (16f - 4f * lyricsEmphasis).sp / density.fontScale,
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
                                trackKey = state.songId,
                                queueIndex = state.currentQueueIndex,
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
                        imageVector = YoinSymbols.ChevronDown,
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
                if (Capability.LIBRARY_ADD in state.serviceFeatures.capabilities) {
                    TrackLibraryButton(
                        membership = state.libraryMembership,
                        isWorking = state.libraryActionInFlight,
                        onClick = onAddCurrentToLibrary,
                        modifier = Modifier.align(Alignment.End),
                    )
                }
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
                    playMode = state.playMode,
                    onCyclePlayMode = onCyclePlayMode,
                    controlSize = 72.dp,
                    lyricsExpanded = lyricsExpanded,
                    onExpandLyrics = { lyricsExpanded = !lyricsExpanded },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(20.dp))
                BottomPills(
                    supportsYoinCast = state.serviceFeatures.supportsYoinCast,
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
    // 0..1 alpha of the "Title - Artist" subline (focus, or a title folded
    // into the bar on a short window).
    subline: () -> Float,
    onBack: () -> Unit,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onPlaylistClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    // The panel's corner toggle, mirrored from [StageTopBar]: at the row end
    // after an 8dp gap so Panel ⇄ Full keeps the button in one place.
    trailingAction: (@Composable () -> Unit)? = null,
) {
    Row(
        // heightIn (not a fixed height) so the bar can grow by one line for the
        // zoom-revealed "Title - Artist" subline, then collapse back at rest.
        modifier = modifier.heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = YoinSymbols.ChevronDown,
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
            val zoom = subline()
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
        if (trailingAction != null) {
            Spacer(modifier = Modifier.width(8.dp))
            trailingAction()
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
    trailingAction: (@Composable () -> Unit)? = null,
    // The height budget may tighten the bar to its 48dp touch target.
    height: Dp = ExpandedTopBarHeight,
) {
    val dockProgress = detailProgress
    val dockCoverAlpha = if (dockProgress >= 1f - HiddenLayerVisibilityThreshold) 1f else 0f
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = YoinSymbols.ChevronDown,
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
            height = DockedCoverSlot,
            width = lerpDp(0.dp, DockedCoverSlot, dockProgress),
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
        if (trailingAction != null) {
            Spacer(modifier = Modifier.width(8.dp))
            trailingAction()
        }
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
    // 16:9 (断点交接 §3.1): the expanded page keeps the TEXT tabs instead of
    // swapping to the big button group, with the lyric tools at the row's end.
    textOnly: Boolean = false,
    tools: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clipToBounds(),
    ) {
        if (textOnly) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompactTextTabs(selected = selected, onSelect = onSelect)
                Spacer(modifier = Modifier.weight(1f))
                tools?.invoke()
            }
            return@Box
        }
        // Conditional composition, not just alpha: an alpha-0 layer still
        // hit-tests, so the invisible big buttons were swallowing collapsed-tab
        // clicks (and vice versa).
        if (detailProgress < 1f) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = (1f - detailProgress).coerceIn(0f, 1f)
                        translationY = -6.dp.toPx() * detailProgress
                    },
                verticalAlignment = if (tools != null) Alignment.CenterVertically else Alignment.Top,
            ) {
                CompactTextTabs(selected = selected, onSelect = onSelect)
                if (tools != null) {
                    Spacer(modifier = Modifier.weight(1f))
                    tools()
                }
            }
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
                trackKey = state.songId,
                queueIndex = state.currentQueueIndex,
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

/**
 * The 16:9 lyric entry (断点交接 §3.1): the current line, bold primary, one
 * line, and the whole row is the button that expands the lyrics page.
 *
 * When a line holds for [LyricIdleHintDelayMs] (intro, break, a long note)
 * the row cross-fades — at most once a day — to an animated unfold symbol
 * with "Tap to expand", shown only while the symbol moves, then fades back.
 */
@Composable
private fun OneLineLyricRow(
    state: NowPlayingUiState.Playing,
    positionMs: () -> Long,
    onExpand: () -> Unit,
    onClaimIdleHint: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val currentPositionMs by rememberUpdatedState(positionMs)
    val activeLine by remember(state.lyrics) {
        derivedStateOf {
            val position = currentPositionMs()
            state.lyrics.indexOfLast { line -> line.startMs?.let { position >= it } == true }
        }
    }
    val hasSyncedLyrics = remember(state.lyrics) { state.lyrics.any { it.startMs != null } }
    val claimHint by rememberUpdatedState(onClaimIdleHint)
    var hintShowing by remember(state.songId) { mutableStateOf(false) }
    LaunchedEffect(activeLine, state.isPlaying, state.songId, hasSyncedLyrics) {
        hintShowing = false
        if (!state.isPlaying || !hasSyncedLyrics) return@LaunchedEffect
        delay(LyricIdleHintDelayMs)
        if (claimHint()) {
            hintShowing = true
            // Fixed hold: under a 0× animator scale the symbol snaps still and
            // the text simply stays ~2s.
            delay(LyricIdleHintShowMs)
            hintShowing = false
        }
    }
    val hintAlpha by animateFloatAsState(
        targetValue = if (hintShowing) 1f else 0f,
        animationSpec = YoinMotion.defaultEffectsSpec(role = YoinMotionRole.Standard),
        label = "lyricIdleHint",
    )
    val haptics = rememberYoinHaptics()
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .semantics(mergeDescendants = true) { role = Role.Button }
            .noRippleClickable(interactionSource = interaction) {
                haptics.performClick()
                onExpand()
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        OneLineLyricPreview(
            state = state,
            positionMs = positionMs,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = 1f - hintAlpha },
        )
        if (hintAlpha > 0.01f) {
            Row(
                modifier = Modifier.graphicsLayer { alpha = hintAlpha },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UnfoldHintSymbol(
                    playing = hintShowing,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Tap to expand",
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * `ic_yoin_unfold_more` drawn live: the two chevrons press ~1.8dp toward the
 * centre line, then spring ~2.6dp outward and settle (spatial springs).
 * Yoin Symbols 0.1.0 only ships a static `UnfoldMore` (no motion painter);
 * two paths on a Canvas are the whole symbol, so it stays drawn here (§3.1)
 * until the library gains an animated twin.
 */
@Composable
private fun UnfoldHintSymbol(
    playing: Boolean,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    val displacement = remember { Animatable(0f) }
    val pressSpec = YoinMotion.fastSpatialSpec<Float>(role = YoinMotionRole.Expressive)
    val bounceSpec = YoinMotion.defaultSpatialSpec<Float>(role = YoinMotionRole.Expressive)
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        displacement.snapTo(0f)
        displacement.animateTo(-UnfoldPressDp, pressSpec)
        displacement.animateTo(UnfoldBounceDp, bounceSpec)
        displacement.animateTo(0f, bounceSpec)
    }
    Canvas(modifier = modifier) {
        val unit = size.width / 24f
        // Negative = toward the centre line: the upper chevron moves down,
        // the lower one up.
        val d = displacement.value.dp.toPx()
        val stroke = Stroke(
            width = 1.5f * unit,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        val upper = Path().apply {
            moveTo(8f * unit, 9.1f * unit - d)
            lineTo(12f * unit, 5.1f * unit - d)
            lineTo(16f * unit, 9.1f * unit - d)
        }
        val lower = Path().apply {
            moveTo(8f * unit, 14.9f * unit + d)
            lineTo(12f * unit, 18.9f * unit + d)
            lineTo(16f * unit, 14.9f * unit + d)
        }
        drawPath(upper, color, style = stroke)
        drawPath(lower, color, style = stroke)
    }
}

private const val UnfoldPressDp = 1.8f
private const val UnfoldBounceDp = 2.6f

/** How long the hint (and its moving symbol) stays before the lyric returns. */
private const val LyricIdleHintShowMs = 1_800L

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
    lyricsViewportGrowthPx: Int = 0,
) {
    when (page) {
        NowPlayingDetailPage.Lyrics -> LyricsFullscreenPane(
            viewportGrowthPx = lyricsViewportGrowthPx,
            trackKey = state.songId,
            queueIndex = state.currentQueueIndex,
            songTitle = state.songTitle,
            artist = state.artist,
            upNext = state.upNextLyrics,
            durationMs = state.durationMs,
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
    playMode: PlayMode = PlayMode.RepeatAll,
    onCyclePlayMode: () -> Unit = {},
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
        playMode = playMode,
        onCyclePlayMode = onCyclePlayMode,
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
    // Short windows (the height budget's one-row hero): title, then the
    // artist on the same baseline.
    oneLine: Boolean = false,
) {
    StageHeightSlot(height = height, alpha = alpha, modifier = modifier) {
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
                }
            } else {
                Modifier
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
                }
            } else {
                Modifier
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
            val artistStyle = MaterialTheme.typography.titleMedium.copy(
                fontSize = MaterialTheme.typography.titleMedium.fontSize * 0.9f,
            )
            val artistText: @Composable (Modifier) -> Unit = { textModifier ->
                Text(
                    text = state.artist,
                    style = artistStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = textModifier.graphicsLayer {
                        scaleX = artistStretchScale
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    },
                )
            }
            if (oneLine) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(HeroOneLineGap),
                ) {
                    // The artist is measured first (capped), the title takes
                    // the rest and marquees when it overflows.
                    Box(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .alignByBaseline()
                            .then(titleModifier)
                            .then(titleClickModifier),
                    ) {
                        NowPlayingMarqueeTitle(
                            text = state.songTitle,
                            style = MaterialTheme.typography.displaySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            stretchScale = titleStretchScale,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .maxWidthFraction(HeroOneLineArtistShare)
                            .alignByBaseline()
                            .then(artistModifier)
                            .then(artistClickModifier),
                    ) {
                        artistText(Modifier)
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(artistModifier)
                            .then(artistClickModifier),
                    ) {
                        artistText(Modifier.fillMaxWidth())
                    }
                    Spacer(modifier = Modifier.height(2.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
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
        fallbackIcon = YoinSymbols.PlayArrow,
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

/** The one tappable lyric line that replaces tabs + lyric window on 16:9 screens. */
private val OneLineLyricRowHeight = 44.dp

/** The Expanded stage's fixed geometry (the budget's third endpoint). */
private val ExpandedTopBarHeight = 56.dp
private val ExpandedCoverGap = 8.dp
private val ExpandedTabRowHeight = 52.dp
private val ExpandedTabGap = 12.dp
private val ExpandedAccessoryHeight = 68.dp
private val DockedCoverSlot = 48.dp

/** TalkBack actions of the focusable cover. */
private const val CoverFocusLabel = "Focus artwork"

/** Caps a child at [fraction] of the width offered to it, keeping it no wider than its content. */
private fun Modifier.maxWidthFraction(fraction: Float): Modifier = layout { measurable, constraints ->
    val cap = if (constraints.hasBoundedWidth) (constraints.maxWidth * fraction).roundToInt() else constraints.maxWidth
    val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = cap))
    layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
}

/** The one-row hero: gap after the title; the artist takes at most this share. */
private val HeroOneLineGap = 10.dp
private const val HeroOneLineArtistShare = 0.4f
private const val CoverFocusExitLabel = "Show lyrics and rating"

/** Enlarged phone: the tab row grows to hold the lyric tools at its end. */
private val EnlargedTabRowHeight = 44.dp
private val EnlargedTabToolSize = 44.dp
private val ShortTabToolSize = 40.dp

/** Auto-immersive: the lyric tools step away after this long without a touch. */
private const val LyricToolsIdleMs = 5_000L
private const val LyricToolsIdlePollMs = 200L

private val WideColumnGap = 24.dp
private val WideFramePadding = 24.dp
private val WideTopBarHeight = 48.dp
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
    actionLabel: String = if (isStarred) "Remove from favorites" else "Add to favorites",
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
                painter = rememberFavoriteSymbolPainter(favorite = isStarred),
                contentDescription = actionLabel,
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
    sharedTransitionEnabled: Boolean = true,
    revealDirection: Int = 1,
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
            rememberActiveOnlySharedContentConfig(
                animatedVisibilityScope = animatedVisibilityScope,
                enabled = sharedTransitionEnabled,
            )
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
        reveal = com.gpo.yoin.ui.component.ArtworkReveal.DotDissolve,
        revealDirection = revealDirection,
        contentDescription = "Album cover",
        modifier = finalModifier,
        shape = YoinArtworkShapes.NowPlayingCover,
        fallbackIcon = YoinSymbols.PlayArrow,
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
            // A title that fits wraps its text, so a one-row hero can set the
            // artist right after it; the caller's modifier decides the width.
            modifier = if (shouldMarquee) {
                Modifier
                    .fillMaxWidth()
                    .clipToBounds()
                    .horizontalFadeMask(edgeWidth = 28.dp)
            } else {
                Modifier
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
    playMode = PlayMode.RepeatAll,
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

/** One breakpoint of the player (断点交接 §3 previews: 16:9, panel, enlarged, TabletNP). */
@Composable
private fun NowPlayingBreakpointPreview(
    widthDp: Int,
    heightDp: Int,
    presentation: NowPlayingPresentation,
) {
    YoinTheme {
        ProvidePreviewWindow(widthDp = widthDp, heightDp = heightDp) {
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
                presentation = presentation,
                enlarged = nowPlayingEnlargedSpec(widthDp.dp),
            )
        }
    }
}

@Preview(name = "16:9 · one lyric line", widthDp = 375, heightDp = 667, showBackground = true)
@Composable
private fun NowPlayingShortPhonePreview() {
    NowPlayingBreakpointPreview(375, 667, NowPlayingPresentation.Phone)
}

@Preview(name = "Side panel (tablet portrait)", widthDp = 400, heightDp = 1280, showBackground = true)
@Composable
private fun NowPlayingPanelPreview() {
    NowPlayingBreakpointPreview(400, 1280, NowPlayingPresentation.Panel)
}

@Preview(name = "Enlarged phone · tablet portrait", widthDp = 800, heightDp = 1280, showBackground = true)
@Composable
private fun NowPlayingEnlargedTabletPreview() {
    NowPlayingBreakpointPreview(800, 1280, NowPlayingPresentation.Enlarged)
}

@Preview(name = "Enlarged phone · fold", widthDp = 690, heightDp = 840, showBackground = true)
@Composable
private fun NowPlayingEnlargedFoldPreview() {
    NowPlayingBreakpointPreview(690, 840, NowPlayingPresentation.Enlarged)
}

/** A 1/2 split of a landscape tablet: no panel fits, so the enlarged phone with a lyric window kept. */
@Preview(name = "Enlarged · split half", widthDp = 640, heightDp = 800, showBackground = true)
@Composable
private fun NowPlayingEnlargedSplitHalfPreview() {
    NowPlayingBreakpointPreview(640, 800, NowPlayingPresentation.Enlarged)
}

@Preview(name = "TabletNP · two columns", widthDp = 1280, heightDp = 800, showBackground = true)
@Composable
private fun NowPlayingDualPanePreview() {
    NowPlayingBreakpointPreview(1280, 800, NowPlayingPresentation.DualPane)
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
