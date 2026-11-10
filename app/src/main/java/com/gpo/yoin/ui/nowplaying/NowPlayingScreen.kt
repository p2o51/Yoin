package com.gpo.yoin.ui.nowplaying
import android.os.SystemClock
import com.gpo.yoin.ui.component.AudioFormatTagRow
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.gpo.yoin.R
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.ui.common.asString
import com.gpo.yoin.data.model.YoinDevice
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.player.CastState
import com.gpo.yoin.player.PlayMode
import com.gpo.yoin.player.SpatialPlayback
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.CastButton
import com.gpo.yoin.ui.component.DevicesSheet
import com.gpo.yoin.ui.component.ExpressiveMediaArtwork
import com.gpo.yoin.ui.component.FavoriteGlyphIcon
import com.gpo.yoin.ui.component.LyricsDisplay
import com.gpo.yoin.ui.component.NoteDraftState
import com.gpo.yoin.ui.component.NoteSaveRequest
import com.gpo.yoin.ui.component.NoteSortMode
import com.gpo.yoin.ui.component.NoteWriteBar
import com.gpo.yoin.ui.component.NoteWriteBarDefaults
import com.gpo.yoin.ui.component.QueueEditActions
import com.gpo.yoin.ui.component.QueueSheet
import com.gpo.yoin.ui.component.SongInfoDisplay
import com.gpo.yoin.ui.component.WaveProgressBar
import com.gpo.yoin.ui.component.edgeFade
import com.gpo.yoin.ui.component.horizontalFadeMask
import com.gpo.yoin.ui.component.ignoreParentHorizontalPadding
import com.gpo.yoin.ui.component.imeAboveNavigationBarInsets
import com.gpo.yoin.ui.component.minimumTouchTarget
import com.gpo.yoin.ui.component.noRippleClickable
import com.gpo.yoin.ui.component.rememberNoteDraftState
import com.gpo.yoin.ui.component.rememberNoteWriteBarState
import com.gpo.yoin.ui.experience.LayoutMode
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.LocalYoinWindowInfo
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.experience.ProvidePreviewWindow
import com.gpo.yoin.ui.experience.ReportMotionPressure
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.experience.voteHighFrameRate
import com.gpo.yoin.ui.navigation.NowPlayingHeroTextResize
import com.gpo.yoin.ui.navigation.nowPlayingCoverSharedKey
import com.gpo.yoin.ui.navigation.rememberActiveOnlySharedContentConfig
import com.gpo.yoin.ui.nowplaying.compact.NoteCompactPane
import com.gpo.yoin.ui.theme.ContinuousRoundedCornerShape
import com.gpo.yoin.ui.theme.GoogleSansFlexRounded
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinArtworkShapes
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotionSpeed
import com.gpo.yoin.ui.theme.YoinTheme
import com.gpo.yoin.ui.theme.withTabularFigures
import kotlin.math.abs
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
    backPreview: StageBackPreview = StageBackPreview.Rest,
    // +1 = forward skip (next / auto-advance), −1 = back — forwarded to the
    // single-column body for its directional cover ride-in.
    skipDirection: Int = 1,
    onTogglePlayPause: () -> Unit,
    // Opens the Spotify app (no song) from the "can't reach Spotify" page.
    onOpenSpotify: () -> Unit = {},
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
    onLyricsMessage: (String) -> Unit = {},
    // Once per press on the rating bar, with the value it lifts at (NowPlayingRatingBar).
    onRatingChange: (Float) -> Unit,
    onToggleFavorite: () -> Unit,
    onAddCurrentToLibrary: () -> Unit = {},
    onAddCurrentToPlaylist: () -> Unit,
    onSkipToQueueItem: (Int) -> Unit,
    queueEditor: QueueEditActions = QueueEditActions.None,
    onSetPlayMode: (PlayMode) -> Unit = {},
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
    onSaveNote: (NoteSaveRequest) -> Unit = {},
    onDeleteNote: (String) -> Unit = {},
    // The Note page's "对齐到现在": re-anchor a note to a new song moment
    // (NowPlayingViewModel.realignNote). Null leaves the menu with delete only.
    onRealignNote: ((SongNote, Long) -> Unit)? = null,
    // The one note draft. Held at this root by default (survives a recycled
    // pager page, Expanded → Compact, a layout swap and a song change); a
    // host that passes the view model's (NowPlayingViewModel.noteDraft) also
    // keeps it across closing Now Playing.
    noteDraft: NoteDraftState = rememberNoteDraftState(),
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
    // Delete with undo: a deleted note leaves every pane (and the seek-bar
    // notches) at once, is offered back in its place on the Note page, and
    // is deleted for real only when the undo window closes — or when Now
    // Playing leaves the screen.
    val noteDeletion = remember { NoteDeletionState() }
    val latestDeleteNote by rememberUpdatedState(onDeleteNote)
    val latestNotes by rememberUpdatedState(notesState)
    LaunchedEffect(noteDeletion.pending) {
        // The window closes NoteUndoWindowMs on — later if a finger is on the list.
        noteDeletion.runUndoWindow { latestDeleteNote(it) }
    }
    DisposableEffect(noteDeletion) {
        onDispose { noteDeletion.commit(latestDeleteNote) }
    }
    LaunchedEffect(notesState) { noteDeletion.prune(notesState) }
    val visibleNotes = remember(notesState, noteDeletion.hiddenIds) { noteDeletion.visible(notesState) }
    val requestNoteDelete: (String) -> Unit = remember(noteDeletion) {
        { id -> latestNotes.firstOrNull { it.id == id }?.let { noteDeletion.delete(it, latestDeleteNote) } }
    }
    val scheme = MaterialTheme.colorScheme
    // Dark focus: the room in this screen's own dark scheme (owner
    // 2026-11-03), with the same field motion and dots. From the cover's
    // resolved scheme, so a song change crosses the room over on the field's
    // own fade, not in the theme wash's few steps.
    val roomScheme = LocalCoverDarkScheme.current ?: scheme
    // Its other hues are the cover's own swatches where it has them.
    val roomSwatches = LocalCoverField.current?.swatches
    val darkRoom = remember(roomScheme, roomSwatches) {
        darkRoomOf(
            primary = roomScheme.primary.toArgb(),
            secondary = roomScheme.secondary.toArgb(),
            tertiary = roomScheme.tertiary.toArgb(),
            coverSwatches = roomSwatches ?: IntArray(0),
        )
    }
    // Light focus: the same seeds, toned pale (owner 2026-11-05).
    val lightRoom = remember(roomScheme, roomSwatches) {
        lightRoomOf(
            primary = roomScheme.primary.toArgb(),
            secondary = roomScheme.secondary.toArgb(),
            tertiary = roomScheme.tertiary.toArgb(),
            coverSwatches = roomSwatches ?: IntArray(0),
        )
    }
    val background = scheme.background
    // The room's frame: the songs before / after this one, or (none there)
    // this cover's own secondary / tertiary container, never a grey edge.
    val neighbours = LocalCoverNeighbours.current
    val roomDark = background.luminance() < 0.5f
    val neighbourTop = neighbours.previous?.of(roomDark) ?: scheme.secondaryContainer
    val neighbourBottom = neighbours.next?.of(roomDark) ?: scheme.tertiaryContainer

    // Reactions for the Now Playing background (see nowPlayingAuroraBackground):
    //  • Gemini thinking (long wait) → a slow aurora wash blooms while Loading
    //    (an Ask Gemini question, or About's automatic first fetch).
    //  • play/pause + skip → a brief one-shot pulse; the trigger reads only the
    //    playing flag, song id and queue position so position ticks don't fire
    //    it. Which pulse (breath, ring, the light along the lyrics, or none) is
    //    resolveTransportPulse's call. Skip also crossfades the whole palette
    //    via the theme.
    // The About page's own first fetch is Gemini thinking too (owner
    // 2026-11-04), so it gets the same wash as a typed question.
    val auroraActive = askState is AskBarState.Loading || aboutUiState is AboutUiState.Loading
    // Gemini not set up: About keeps its own "no key" text; the background
    // adds a soft pink glow as a gentle nudge, at most every three days.
    val hintContext = LocalContext.current
    val keyMissing = aboutUiState is AboutUiState.ApiKeyMissing
    var keyHintActive by remember { mutableStateOf(false) }
    LaunchedEffect(keyMissing) {
        if (!keyMissing || !GeminiKeyHint.claim(hintContext)) return@LaunchedEffect
        keyHintActive = true
        try {
            delay(GEMINI_KEY_HINT_HOLD_MS)
        } finally {
            keyHintActive = false
        }
    }
    val playingState = uiState as? NowPlayingUiState.Playing
    val pulseTrigger = playingState?.let { TransportPulseKey(it.songId, it.isPlaying, it.currentQueueIndex) }
    val isPlayingNow = playingState?.isPlaying == true

    // The transport button (PLAY/PAUSE, deep in the shared PlaybackControls)
    // publishes its press state + stamped taps here so the background can answer
    // the finger: gather while held, ripple/sink from the tapped button on
    // commit. The lyrics column and the outro hand-over publish here too.
    val transportSignal = remember { NowPlayingTransportSignal() }

    // Action lights (ActionGlow.kt) for the actions decided up here; the
    // controls (heart, rating, play mode, wave bar) fire their own.
    val latestOnSaveNote by rememberUpdatedState(onSaveNote)
    val latestOnSelectDevice by rememberUpdatedState(onSelectDevice)
    val latestOnApplySearchResult by rememberUpdatedState(onApplyLyricsSearchResult)
    val latestOnApplyLyrics by rememberUpdatedState(onApplyLyrics)
    val latestRequestNoteDelete by rememberUpdatedState(requestNoteDelete)
    val glowSaveNote: (NoteSaveRequest) -> Unit = remember(transportSignal) {
        { request ->
            val signal = transportSignal
            val from = signal.anchorOf(GlowAnchor.Write)
                ?: signal.lyricsAreaRoot?.let { Rect(Offset(it.center.x, it.bottom), 1f) }
            signal.glow(ActionGlowKind.NoteSave, from, targetY = signal.currentLineRootY ?: Float.NaN)
            latestOnSaveNote(request)
        }
    }
    val glowDeleteNote: (String) -> Unit = remember(transportSignal) {
        { id ->
            val signal = transportSignal
            signal.glow(
                ActionGlowKind.NoteDelete,
                signal.lyricsAreaRoot ?: signal.anchorOf(GlowAnchor.Write),
                targetY = signal.currentLineRootY ?: Float.NaN,
            )
            latestRequestNoteDelete(id)
        }
    }
    val glowSelectDevice: (YoinDevice) -> Unit = remember(transportSignal) {
        { device ->
            transportSignal.glow(ActionGlowKind.DeviceHandoff, transportSignal.anchorOf(GlowAnchor.Devices))
            latestOnSelectDevice(device)
        }
    }
    val revealLyrics = remember(transportSignal) {
        { transportSignal.glow(ActionGlowKind.LyricsReveal, null, areaRoot = transportSignal.lyricsAreaRoot) }
    }
    // Applying lyrics (a search result, the editor) is asynchronous and can
    // fail: the reveal waits for the new lines to arrive on this song.
    var lyricsRevealOwedAt by remember { mutableLongStateOf(0L) }
    val glowApplySearchResult: (LyricsSearchResultUi) -> Unit = remember {
        { result ->
            lyricsRevealOwedAt = SystemClock.uptimeMillis()
            latestOnApplySearchResult(result)
        }
    }
    val glowApplyLyrics: (String) -> Unit = remember {
        { text ->
            lyricsRevealOwedAt = SystemClock.uptimeMillis()
            latestOnApplyLyrics(text)
        }
    }
    val revealSongId = (uiState as? NowPlayingUiState.Playing)?.songId
    val revealLines = (uiState as? NowPlayingUiState.Playing)?.lyrics
    LaunchedEffect(revealSongId) { lyricsRevealOwedAt = 0L }
    LaunchedEffect(revealLines) {
        val owed = lyricsRevealOwedAt
        if (owed == 0L || revealLines.isNullOrEmpty()) return@LaunchedEffect
        lyricsRevealOwedAt = 0L
        if (SystemClock.uptimeMillis() - owed <= LyricsRevealWaitMs) revealLyrics()
    }
    // A translation: the slow sweep while it is fetched, each line lights once when it lands.
    val translating = (uiState as? NowPlayingUiState.Playing)?.lyricsActionInFlight == LyricsAction.Translate
    val translationShown = (uiState as? NowPlayingUiState.Playing)?.showLyricsTranslation == true
    val latestTranslationShown by rememberUpdatedState(translationShown)
    var wasTranslating by remember { mutableStateOf(false) }
    LaunchedEffect(translating) {
        transportSignal.lyricsWaiting = translating
        if (wasTranslating && !translating && latestTranslationShown) revealLyrics()
        wasTranslating = translating
    }
    // Every user seek (lyric line, note row, wave bar) tells the background
    // first: the player may buffer at the new spot, and that dip is the
    // seek's, not a play/pause (NowPlayingTransportSignal.recordSeek).
    val latestOnSeek by rememberUpdatedState(onSeek)
    val latestOnSeekToMs by rememberUpdatedState(onSeekToMs)
    val seekSettled: (Float) -> Unit = remember(transportSignal) {
        { fraction ->
            transportSignal.recordSeek()
            latestOnSeek(fraction)
        }
    }
    val seekToMsSettled: (Long) -> Unit = remember(transportSignal) {
        { targetMs ->
            transportSignal.recordSeek()
            // A lyric line / note row: a faint band lights at its height.
            transportSignal.recordLineTap()
            latestOnSeekToMs(targetMs)
        }
    }

    // Focus's edge band swells once per lyric line (EdgeDotBand.kt).
    val bandLyrics = playingState?.lyrics
    val latestPositionMs by rememberUpdatedState(positionMs)
    // Focus's colour journey enters from the side the song came from, latched
    // per song: skipDirection is −1 while a skip back's new song composes.
    val journeyFromRight = remember(playingState?.songId) { skipDirection > 0 }
    val bandLine by remember(bandLyrics) {
        derivedStateOf { bandLyrics?.currentLyricLine(latestPositionMs(), showTranslation = false)?.first ?: -1 }
    }
    LaunchedEffect(bandLine) {
        if (bandLine >= 0) transportSignal.lyricLineChanged()
    }

    ReportMotionPressure(
        tag = "now-playing",
        isHighPressure = uiState is NowPlayingUiState.Playing &&
            uiState.isPlaying &&
            hasAudioSpectrum,
    )

    ProvideYoinMotionRole(role = YoinMotionRole.Expressive) {
      CompositionLocalProvider(
        LocalNowPlayingTransportSignal provides transportSignal,
        LocalNoteDeletion provides noteDeletion,
        LocalNoteRealign provides onRealignNote,
      ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .nowPlayingAuroraBackground(
                    background = background,
                    darkTop = scheme.surfaceContainer,
                    accentColors = listOf(scheme.primary, scheme.secondary, scheme.tertiary),
                    neighbourTop = neighbourTop,
                    neighbourBottom = neighbourBottom,
                    auroraActive = auroraActive,
                    pulseTrigger = pulseTrigger,
                    isPlaying = isPlayingNow,
                    pressActive = transportSignal.playHeld,
                    gatherFocalRoot = transportSignal.gatherAnchorRoot,
                    burstFocalRoot = null,
                    transportSignal = transportSignal,
                    coverField = LocalCoverField.current,
                    // Focus = the immersive stage (tap the cover); read in draw.
                    focusProgress = { stageProgress?.immersive ?: 0f },
                    darkRoom = darkRoom,
                    lightRoom = lightRoom,
                    songProgress = {
                        val d = playingState?.durationMs ?: 0L
                        if (d > 0L) (latestPositionMs().toFloat() / d).coerceIn(0f, 1f) else 0f
                    },
                    journeyFromRight = journeyFromRight,
                    keyHintActive = keyHintActive,
                    // The current lyric line is drawn in primary.
                    lyricColor = scheme.primary,
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
                    onOpenSpotify = onOpenSpotify,
                    dismissFraction = dismissFraction,
                )
                is NowPlayingUiState.Playing -> PlayingContent(
                    state = uiState,
                    positionMs = positionMs,
                    bufferedMs = bufferedMs,
                    onTogglePlayPause = onTogglePlayPause,
                    onSkipNext = onSkipNext,
                    onSkipPrevious = onSkipPrevious,
                    onSeek = seekSettled,
                    onSeekToMs = seekToMsSettled,
                    lyricsSearchState = lyricsSearchState,
                    onOpenLyricsSearch = onOpenLyricsSearch,
                    onLyricsSearchQueryChange = onLyricsSearchQueryChange,
                    onSearchLyrics = onSearchLyrics,
                    onApplyLyricsSearchResult = glowApplySearchResult,
                    onDismissLyricsSearch = onDismissLyricsSearch,
                    onTranslateLyrics = onTranslateLyrics,
                    onApplyLyrics = glowApplyLyrics,
                    onLyricsMessage = onLyricsMessage,
                    onRatingChange = onRatingChange,
                    onToggleFavorite = onToggleFavorite,
                    onAddCurrentToLibrary = onAddCurrentToLibrary,
                    onAddCurrentToPlaylist = onAddCurrentToPlaylist,
                    onSkipToQueueItem = onSkipToQueueItem,
                    queueEditor = queueEditor,
                    onSetPlayMode = onSetPlayMode,
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
                    notesState = visibleNotes,
                    onSaveNote = glowSaveNote,
                    noteSortMode = noteSortMode,
                    onNoteSortModeChange = { noteSortMode = it },
                    noteDraft = noteDraft,
                    onDeleteNote = glowDeleteNote,
                    devicesState = devicesState,
                    onRefreshDevices = onRefreshDevices,
                    onSelectDevice = glowSelectDevice,
                    castState = castState,
                    onCastClick = onCastClick,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    backPreview = backPreview,
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
            text = stringResource(R.string.np_empty_nothing_playing),
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
                contentDescription = stringResource(R.string.np_cd_launching_collapse),
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
                text = state.hint.asString(),
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
    onOpenSpotify: () -> Unit = {},
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
                contentDescription = stringResource(R.string.np_cd_connect_error_collapse),
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
            text = state.message.asString(),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (state.opensSpotify) {
            // The way out sits on the page itself: the shell snackbar that also
            // offers it is hidden behind Now Playing and gone after a few seconds.
            Spacer(modifier = Modifier.height(20.dp))
            FilledTonalButton(onClick = onOpenSpotify) {
                Text(text = stringResource(R.string.shell_snackbar_open_spotify))
            }
        }
    }
}

private data class NowPlayingPlaybackActions(
    val onTogglePlayPause: () -> Unit,
    val onSkipNext: () -> Unit,
    val onSkipPrevious: () -> Unit,
    val onSeek: (Float) -> Unit,
    val onSetPlayMode: (PlayMode) -> Unit,
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
    backPreview: StageBackPreview = StageBackPreview.Rest,
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
    onLyricsMessage: (String) -> Unit = {},
    onRatingChange: (Float) -> Unit,
    onToggleFavorite: () -> Unit,
    onAddCurrentToLibrary: () -> Unit = {},
    onAddCurrentToPlaylist: () -> Unit,
    onSkipToQueueItem: (Int) -> Unit,
    queueEditor: QueueEditActions = QueueEditActions.None,
    onSetPlayMode: (PlayMode) -> Unit = {},
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
    onSaveNote: (NoteSaveRequest) -> Unit = {},
    noteSortMode: NoteSortMode = NoteSortMode.Timeline,
    onNoteSortModeChange: (NoteSortMode) -> Unit = {},
    noteDraft: NoteDraftState,
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
    // Entering focus answers with a confirm tick (owner 2026-11-04), however
    // it was entered (the cover, the docked cover in the top bar).
    val focusHaptics = rememberYoinHaptics()
    val lastStageMode = remember { arrayOf(stageMode) }
    LaunchedEffect(stageMode) {
        if (stageMode == NowPlayingStageMode.Immersive && lastStageMode[0] != NowPlayingStageMode.Immersive) {
            focusHaptics.performConfirm()
        }
        lastStageMode[0] = stageMode
    }
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
            backPreview = backPreview,
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
            onLyricsMessage = onLyricsMessage,
            onRatingChange = onRatingChange,
            onToggleFavorite = onToggleFavorite,
            onAddCurrentToLibrary = onAddCurrentToLibrary,
            onAddCurrentToPlaylist = onAddCurrentToPlaylist,
            onSkipToQueueItem = onSkipToQueueItem,
            queueEditor = queueEditor,
            onSetPlayMode = onSetPlayMode,
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
            noteDraft = noteDraft,
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
            onLyricsMessage = onLyricsMessage,
            onRatingChange = onRatingChange,
            onToggleFavorite = onToggleFavorite,
            onAddCurrentToLibrary = onAddCurrentToLibrary,
            onAddCurrentToPlaylist = onAddCurrentToPlaylist,
            onSkipToQueueItem = onSkipToQueueItem,
            queueEditor = queueEditor,
            onSetPlayMode = onSetPlayMode,
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
            noteDraft = noteDraft,
            onDeleteNote = onDeleteNote,
            devicesState = devicesState,
            onRefreshDevices = onRefreshDevices,
            onSelectDevice = onSelectDevice,
            castState = castState,
            onCastClick = onCastClick,
            backPreview = backPreview,
            onStageBack = onStageBack,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
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
            queueEditor = queueEditor,
            onSetPlayMode = onSetPlayMode,
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
            backPreview = backPreview,
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
            onLyricsMessage = onLyricsMessage,
            onRatingChange = onRatingChange,
            onToggleFavorite = onToggleFavorite,
            onAddCurrentToLibrary = onAddCurrentToLibrary,
            onAddCurrentToPlaylist = onAddCurrentToPlaylist,
            onSkipToQueueItem = onSkipToQueueItem,
            queueEditor = queueEditor,
            onSetPlayMode = onSetPlayMode,
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
            noteDraft = noteDraft,
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

/** System bars around the single column, with a floor under the pills ([stageColumnBottomInset]). */
@Composable
private fun stageColumnInsets(): PaddingValues {
    val bars = WindowInsets.systemBars.asPaddingValues()
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = bars.calculateStartPadding(direction),
        top = bars.calculateTopPadding(),
        end = bars.calculateEndPadding(direction),
        bottom = stageColumnBottomInset(bars.calculateBottomPadding()),
    )
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
    backPreview: StageBackPreview = StageBackPreview.Rest,
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
    onLyricsMessage: (String) -> Unit = {},
    onRatingChange: (Float) -> Unit,
    onToggleFavorite: () -> Unit,
    onAddCurrentToLibrary: () -> Unit = {},
    onAddCurrentToPlaylist: () -> Unit,
    onSkipToQueueItem: (Int) -> Unit,
    queueEditor: QueueEditActions = QueueEditActions.None,
    onSetPlayMode: (PlayMode) -> Unit = {},
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
    onSaveNote: (NoteSaveRequest) -> Unit = {},
    noteSortMode: NoteSortMode = NoteSortMode.Timeline,
    onNoteSortModeChange: (NoteSortMode) -> Unit = {},
    noteDraft: NoteDraftState,
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
    // The Note page's write bar; the Write pill asks it for the keyboard.
    val noteBar = rememberNoteWriteBarState(noteDraft)
    ExpirePendingNoteWrite(noteBar)
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
        onSetPlayMode = onSetPlayMode,
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
    // The transport row under the expanded page is 0 dp tall and alpha 0: its
    // wave and glide stop until the stage starts to collapse (flips only at
    // the reshape's ends, not per frame).
    val stageControlsLive by remember(resolvedStageProgress) {
        derivedStateOf { resolvedStageProgress.compact > HiddenLayerVisibilityThreshold }
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
    // ONE driver per direction (see SyncDetailPageWithPager).
    SyncDetailPageWithPager(
        pagerState = pagerState,
        detailPage = detailPage,
        onDetailPageChange = onDetailPageChange,
        onAboutOpened = onAboutOpened,
    )
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

    // Auto-immersive (断点交接 §3.2): playing + Lyrics + synced lyrics +
    // LyricToolsIdleMs without a touch → ONLY the four lyric tools step away.
    // A touch, a manual lyrics scroll (recenter lit), a pause or select mode
    // brings them straight back.
    val lyricToolsIdle = rememberLyricToolsIdleState()
    // Focus at ease (owner 2026-11-05): FocusIdleMs without a touch while the
    // lyrics play, the transport and its times step away and the lyrics
    // spread down into their place; a touch brings them back.
    val focusIdle = rememberLyricToolsIdleState()
    val lyricsSelection = rememberLyricsSelectionState(state.songId, state.lyrics)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .voteHighFrameRate(stageMoving)
            .lyricToolsTouchTracker(lyricToolsIdle)
            .lyricToolsTouchTracker(focusIdle)
            .padding(stageColumnInsets()),
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
        // A short resting window can still offer the list in focus: the
        // one-line row hands over to the list as the cover focuses.
        val focusOpensList = lyricOneLine && focusKeepsList
        val stageWidth = budget.stageWidth
        // Focus narrows the column to the cover, centred: the cover keeps the
        // same margin left, top and right, and everything under it lines up
        // with its edges (owner 2026-11-04).
        val liveStageWidth = lerpDp(
            lerpDp(stageWidth, budget.focusStageWidth, immersiveProgress),
            stageWidth,
            detailProgress,
        )
        val stageOffsetX = (maxWidth - liveStageWidth) / 2
        // Short-window focus (owner 2026-11-05, prototype v3): the transport
        // folds to one row and keeps the full width with the title; only the
        // lyrics narrow to the centred cover, their dotted lines running on
        // under the transport (drawn over them) before they fade.
        val compactFocusProgress = if (budget.compactFocus) {
            (immersiveProgress * (1f - detailProgress)).coerceIn(0f, 1f)
        } else {
            0f
        }
        val focusIdleEligible = stageMode == NowPlayingStageMode.Immersive && focusKeepsList &&
            state.isPlaying && hasSyncedLyrics
        LaunchedEffect(focusIdleEligible) { focusIdle.track(focusIdleEligible, FocusIdleMs) }
        val focusIdleProgress by animateFloatAsState(
            targetValue = if (focusIdleEligible && focusIdle.idle) 1f else 0f,
            animationSpec = YoinMotion.slowSpatialSpec(role = YoinMotionRole.Standard),
            label = "npFocusIdle",
        )
        // Capped by the focus itself: leaving focus at ease, the stacked hero,
        // the lift and the run-on unwind on the focus's own clock, as one move
        // with the cover and the transport, instead of trailing it on their
        // slower idle clock (the Fold outer screen's two-row title read as a
        // jump on the way out, owner 2026-11-05).
        val focusIdleShown =
            (minOf(focusIdleProgress, immersiveProgress) * (1f - detailProgress)).coerceIn(0f, 1f)
        val focusLyricInset = budget.focusLyricInset * compactFocusProgress
        // At ease a one-row hero stacks: the artist lifts over the title into
        // the transport's room, the progress bar moving up with it (owner
        // 2026-11-05 「显示按键的时候还是要一行」).
        val heroLiftFull = if (budget.focusHeroStacked) {
            (metrics.hero - metrics.heroOneLine).coerceAtLeast(0.dp)
        } else {
            0.dp
        }
        val heroLift = heroLiftFull * focusIdleShown
        // At ease the run-on reaches down through the transport's first row,
        // which steps away; the progress bar stays (「按键没了也应该显示进度条」).
        val focusLyricOverflow = lerpDp(
            budget.focusLyricOverflow * compactFocusProgress,
            (minOf(transportTopRowHeight(stageControlSize), focusPose.controls) - heroLiftFull)
                .coerceAtLeast(0.dp),
            focusIdleShown,
        )
        val focusLyricOverflowPx = with(LocalDensity.current) { focusLyricOverflow.roundToPx() }
        val focusRunOnFade = RunOnFadeShare + (RunOnFadeShareAtEase - RunOnFadeShare) * focusIdleShown
        // Enlarged tablet: the lyric tools ride at the end of the tab row even
        // at rest (TabletPortraitNP).
        val toolsInCompactTabs = enlarged != null && !lyricOneLine
        // Rest ⇄ focus by immersive, then ⇄ the Expanded layout by detail.
        fun stage(rest: Dp, focus: Dp, expanded: Dp): Dp =
            lerpDp(lerpDp(rest, focus, immersiveProgress), expanded, detailProgress)
        // Short focus (owner 2026-10-30): where the regular focus can't grow the
        // cover (Fold outer screen, 16:9), a tap still focuses it — the heart and
        // rating bar fold away, PREVIOUS glides up into the top transport row and
        // the progress bar takes its own row; the freed height goes to the cover,
        // centred. The current lyric line stays, as in every focus. The title and
        // artist stay too, as in every focus (owner 2026-11-03 "标题和歌手不动";
        // 2026-11-04 they had vanished on the Fold 8 outer screen).
        // Where the regular focus IS offered on a short window, the cover stays
        // its size, centred, and the freed height goes to the lyrics (owner
        // 2026-11-05; compactFocusProgress above).
        var shortFocus by remember { mutableStateOf(false) }
        val shortFocusHaptics = rememberYoinHaptics()
        val shortArrangement = shortFocus
        val shortFocusProgress by animateFloatAsState(
            targetValue = if (shortArrangement) 1f else 0f,
            animationSpec = YoinMotion.defaultSpatialSpec(role = YoinMotionRole.Expressive),
            label = "npShortFocus",
        )
        // The same compact transport height the budget sized the focus with.
        val shortFocusControls = metrics.compactTransport ?: shortFocusControlsHeight(stageControlSize)
        val shortFocusFreed = (restPose.controls - shortFocusControls).coerceAtLeast(0.dp)
        // Where the regular focus is offered, the budget already sized it for
        // the compact transport and three lyric lines: take its cover as is.
        val shortFocusCover = if (focusOffered) {
            focusPose.cover
        } else {
            minOf(restPose.cover + shortFocusFreed, stageWidth - horizontalPadding * 2)
        }
        val shortFocusAvailable = !focusOffered && shortFocusCover - restPose.cover >= ShortFocusMinGrowth
        LaunchedEffect(shortFocusAvailable, stageMode) {
            if (!shortFocusAvailable || stageMode != NowPlayingStageMode.Compact) shortFocus = false
        }
        val visibleCoverHeight = lerpDp(
            lerpDp(restPose.cover, focusPose.cover, immersiveProgress),
            shortFocusCover,
            shortFocusProgress,
        )
        val coverRowHeight = lerpDp(stage(restPose.cover, focusPose.cover, 0.dp), shortFocusCover, shortFocusProgress)
        val coverRowAlpha = compactProgress
        val topBarHeight = stage(restPose.topBar, focusPose.topBar, ExpandedTopBarHeight)
        val tabHeight = stage(restPose.tabs, focusPose.tabs, ExpandedTabRowHeight)
        val tabSpacerHeight = stage(restPose.tabGap, focusPose.tabGap, ExpandedTabGap)
        val ratingRetreatProgress = maxOf(immersiveProgress, detailProgress, shortFocusProgress)
        val ratingGap = lerpDp(12.dp, 0.dp, ratingRetreatProgress)
        // A height-capped cover (Fold outer, 16:9) leaves a band between it and the
        // rating bar: the spine (书脊, NowPlayingSpine) reads the song there, one
        // fact at a time — the cached About credits and, on Subsonic, the
        // server's facts (owner 2026-10-30 / 2026-11-03).
        val spineBand = stageWidth - horizontalPadding * 2 - 12.dp - ratingColumn - restPose.cover
        val showSpine = spineBand >= SpineMinWidth
        val ratingSlotWidth = lerpDp(ratingColumn, 0.dp, ratingRetreatProgress)
        val lyricsPageSelected = detailPage == NowPlayingDetailPage.Lyrics
        val idleEligible = lyricToolsIdleEligible(
            isPlaying = state.isPlaying,
            hasSyncedLyrics = hasSyncedLyrics,
            autoScroll = lyricsAutoScroll,
            lyricsPageSelected = lyricsPageSelected,
            toolsOnScreen = stageMode == NowPlayingStageMode.Expanded || toolsInCompactTabs,
            selectingLines = lyricsSelection.active,
        )
        LaunchedEffect(idleEligible) { lyricToolsIdle.track(idleEligible) }
        // Select mode lives on the expanded Lyrics page; leaving it leaves the mode.
        LyricsSelectionHost(
            state = state,
            selection = lyricsSelection,
            lyricsPageOnScreen = stageMode == NowPlayingStageMode.Expanded &&
                pagerState.targetPage == NowPlayingDetailPage.Lyrics.ordinal,
            onMessage = onLyricsMessage,
        )
        val lyricToolsAlpha by animateFloatAsState(
            targetValue = if (lyricToolsIdle.idle) 0f else 1f,
            animationSpec = YoinMotion.defaultEffectsSpec(role = YoinMotionRole.Standard),
            label = "lyricToolsAlpha",
        )
        // Bottom tools collapse their slot when they move up (16:9) or step
        // away (idle): the title sinks to where the tools' bottom edge was and
        // the lyrics grow into the space.
        val toolsSlotCollapsesWhenIdle = stageMode == NowPlayingStageMode.Expanded && lyricsPageSelected &&
            !lyricOneLine
        val accessoryToolsCollapsed = stageMode == NowPlayingStageMode.Expanded && lyricsPageSelected &&
            (lyricOneLine || lyricToolsIdle.idle)
        // The Expanded page's accessory (lyric tools / Ask / Note) animates
        // between its own heights; at rest the slot is the budget's pill slot.
        // Keyboard up over the expanded Note page: the title steps aside (into
        // the top bar), so the write bar sits right on the keyboard.
        val noteOwnsBottom = noteComposerOwnsBottom(stageMode, detailPage, isImeUp())
        val noteHeroYield by animateFloatAsState(
            targetValue = if (noteOwnsBottom) 1f else 0f,
            animationSpec = YoinMotion.defaultSpatialSpec(role = YoinMotionRole.Expressive),
            label = "noteHeroYield",
        )
        val expandedAccessoryTargetHeight = when {
            detailPage == NowPlayingDetailPage.About && askState is AskBarState.Focused -> 276.dp
            // The write bar: its capsule at rest (= the slot), its card's own
            // height while writing.
            detailPage == NowPlayingDetailPage.Note ->
                noteBar.height(LocalDensity.current) + NoteWriteBarDefaults.TopGap
            accessoryToolsCollapsed -> 0.dp
            else -> ExpandedAccessoryHeight
        }
        val expandedAccessoryHeight by animateDpAsState(
            targetValue = expandedAccessoryTargetHeight,
            animationSpec = YoinMotion.defaultSpatialSpec(role = YoinMotionRole.Expressive),
            label = "nowPlayingBottomAccessoryHeight",
        )
        val bottomAccessoryHeight = stage(restPose.accessory, focusPose.accessory, expandedAccessoryHeight)
        val controlsHeight =
            lerpDp(stage(restPose.controls, focusPose.controls, 0.dp), shortFocusControls, shortFocusProgress) -
                heroLift
        val coverSpacerHeight = stage(restPose.coverGap, focusPose.coverGap, ExpandedCoverGap)
        // The title keeps its resting slot in Expanded — unless the Note
        // composer owns the bottom edge (keyboard up).
        val expandedHeroHeight = lerpDp(restPose.hero, 0.dp, noteHeroYield.coerceIn(0f, 1f))
        // The short arrangement keeps the title row exactly as the focus has it.
        val heroHeight = stage(restPose.hero, focusPose.hero, expandedHeroHeight) + heroLift
        // Add back the space the surrounding slots will release at Expanded.
        // Round each slot separately, exactly as their layout modifiers do, so
        // the lazy list receives identical pixel constraints on every frame.
        // Less the run-on the pager already measures (runOnBelow).
        val lyricsViewportGrowthPx = with(LocalDensity.current) {
            topBarHeight.roundToPx() - ExpandedTopBarHeight.roundToPx() +
                coverRowHeight.roundToPx() + controlsHeight.roundToPx() +
                coverSpacerHeight.roundToPx() - ExpandedCoverGap.roundToPx() +
                tabHeight.roundToPx() - ExpandedTabRowHeight.roundToPx() +
                tabSpacerHeight.roundToPx() - ExpandedTabGap.roundToPx() +
                heroHeight.roundToPx() - expandedHeroHeight.roundToPx() +
                bottomAccessoryHeight.roundToPx() - expandedAccessoryHeight.roundToPx() -
                focusLyricOverflowPx
        }
        // The cover as drawn (the budget's row IS the cover square).
        val compactCoverSize = visibleCoverHeight
        val lyricTools: @Composable (iconSize: Dp) -> Unit = { iconSize ->
            LyricsTools(
                state = state,
                selection = lyricsSelection,
                searchBarState = lyricsSearchBarState,
                canRecenter = !lyricsAutoScroll && hasSyncedLyrics,
                onSearchClick = lyricsActions.onOpenLyricsSearch,
                onTranslateClick = lyricsActions.onTranslateLyrics,
                onRecenterClick = {
                    lyricsAutoScroll = true
                    lyricsRecenterTick += 1
                },
                onEditLyricsClick = { showApplyDialog = true },
                onMessage = onLyricsMessage,
                // Enlarged tablet at rest: the tools ride the tab row, but
                // select mode lives on the expanded page.
                onBeforeSelect = {
                    if (stageMode != NowPlayingStageMode.Expanded) {
                        onStageModeChange(NowPlayingStageMode.Expanded)
                    }
                },
                iconSize = iconSize,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(liveStageWidth)
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
                // Clean focus: the bar steps up and away; its slot keeps only
                // a little air above the grown cover (owner 2026-11-03). The
                // row keeps its touch height and the slot clips it.
                height = maxOf(topBarHeight, TopBarTouchHeight),
                // Writing with the keyboard up: the title that stepped aside
                // reads here, beside the docked cover.
                trackTitleFraction = if (noteOwnsBottom || noteHeroYield > 0f) {
                    { noteHeroYield }
                } else {
                    null
                },
                modifier = Modifier
                    .height(topBarHeight)
                    .clipToBounds()
                    .wrapContentHeight(Alignment.Bottom, unbounded = true)
                    .padding(horizontal = horizontalPadding)
                    .focusRetreat { resolvedStageProgress.immersive * (1f - resolvedStageProgress.detail) },
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    // Collapse-preview recede of the whole stage below the top bar.
                    .backPreviewTransform(backPreview),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        // Idle tools collapse their slot and the title sinks onto
                        // it: the first tap where title + tools stood only wakes
                        // the tools (never the title's album route).
                        .lyricToolsWakeGuard(
                            state = lyricToolsIdle,
                            enabled = toolsSlotCollapsesWhenIdle,
                            band = restPose.hero + ExpandedAccessoryHeight,
                        ),
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
                            val coverMenu = rememberCoverMenuState()
                            // Always a square: size by the slot HEIGHT (AlbumCover adds
                            // aspectRatio(1f)) instead of weighting the WIDTH. On wide
                            // viewports a width-weighted box exceeded the height-capped
                            // slot, so ContentScale.Crop cut the square art top/bottom.
                            // The weighted wrapper keeps the rating slot's position.
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                // Focus centres the cover in its column.
                                contentAlignment = BiasAlignment(
                                    horizontalBias = -1f + maxOf(shortFocusProgress, immersiveProgress),
                                    verticalBias = -1f,
                                ),
                            ) {
                                if (showSpine) {
                                    val spineLabels = rememberSpineLabels()
                                    val aboutEntries = (aboutUiState as? AboutUiState.Ready)?.entries.orEmpty()
                                    val spineItems = remember(
                                        aboutEntries,
                                        state.lyricCredits,
                                        state.spineFacts,
                                        state.songId,
                                        spineLabels,
                                    ) {
                                        spineItems(
                                            entries = aboutEntries,
                                            credits = state.lyricCredits,
                                            facts = state.spineFacts,
                                            albumName = state.albumName,
                                            artist = state.artist,
                                            title = state.songTitle,
                                            labels = spineLabels,
                                        )
                                    }
                                    NowPlayingSpine(
                                        items = spineItems,
                                        songKey = state.songId,
                                        length = restPose.cover,
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .width(spineBand)
                                            .height(restPose.cover)
                                            .graphicsLayer { alpha = 1f - ratingRetreatProgress },
                                    )
                                }
                                AlbumCover(
                                    songId = state.songId,
                                    coverArtUrl = state.coverArtUrl,
                                    revealDirection = skipDirection,
                                    sharedTransitionScope = sharedTransitionScope,
                                    animatedVisibilityScope = animatedVisibilityScope,
                                    sharedTransitionEnabled = detailProgress <= HiddenLayerVisibilityThreshold,
                                    interactionSource = coverClickSource,
                                    menu = coverMenu,
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
                                        // Tap = focus the artwork, only where it visibly
                                        // grows; long-press = the song menu, always.
                                        .nowPlayingCoverGestures(
                                            interactionSource = coverClickSource,
                                            menu = coverMenu,
                                            onClickLabel = if (stageMode == NowPlayingStageMode.Immersive) {
                                                stringResource(R.string.np_cd_show_lyrics_rating)
                                            } else {
                                                stringResource(R.string.np_cd_focus_artwork)
                                            },
                                            onClick = if (focusOffered || stageMode == NowPlayingStageMode.Immersive) {
                                                {
                                                    if (stageMode == NowPlayingStageMode.Immersive) {
                                                        onStageModeChange(NowPlayingStageMode.Compact)
                                                    } else {
                                                        onDetailPageChange(NowPlayingDetailPage.Lyrics)
                                                        onStageModeChange(NowPlayingStageMode.Immersive)
                                                    }
                                                }
                                            } else if (shortFocusAvailable) {
                                                {
                                                    if (!shortFocus) shortFocusHaptics.performConfirm()
                                                    shortFocus = !shortFocus
                                                }
                                            } else {
                                                null
                                            },
                                        )
                                        .reportFocusCover(),
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
                                            // Focus breaks it into dots instead (below).
                                            alpha = (1f - maxOf(detailProgress, shortFocusProgress)).coerceIn(0f, 1f)
                                            translationX = 72.dp.toPx() * ratingRetreatProgress
                                        }
                                        .dotDissolve { immersiveProgress },
                                ) {
                                    NowPlayingRatingBar(
                                        songId = state.songId,
                                        rating = state.rating,
                                        onRatingCommit = onRatingChange,
                                        modifier = Modifier.weight(1f),
                                    )

                                    Spacer(modifier = Modifier.height(8.dp))

                                    if (state.serviceFeatures.supportsFavorites) {
                                        FavoriteButton(
                                            isStarred = state.isStarred,
                                            quietFlips = state.favoriteQuietFlips,
                                            actionLabel = stringResource(
                                                if (state.isStarred) {
                                                    state.serviceFeatures.removeLabel
                                                } else {
                                                    state.serviceFeatures.saveLabel
                                                },
                                            ),
                                            onClick = onToggleFavorite,
                                            onLongClick = onAddCurrentToPlaylist,
                                        )
                                    }
                                    if (Capability.LIBRARY_ADD in state.serviceFeatures.capabilities) {
                                        NowPlayingLibraryButton(
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
                                    modifier = Modifier
                                        .graphicsLayer {
                                            // Top tools fade IN PLACE when idle — nothing moves.
                                            alpha = lyricToolsAlpha *
                                                if (lyricOneLine) detailProgress else 1f
                                        }
                                        // …but stay where a blind tap lands: while
                                        // they are away it only wakes them.
                                        .lyricToolsInPlaceWakeGuard(lyricToolsIdle),
                                ) {
                                    lyricTools(if (lyricOneLine) ShortTabToolSize else EnlargedTabToolSize)
                                }
                            }
                        } else {
                            null
                        },
                        modifier = Modifier
                            .padding(horizontal = horizontalPadding)
                            .dotDissolve { immersiveProgress },
                    )

                    Spacer(modifier = Modifier.height(tabSpacerHeight))

                    val pagerClickSource = remember { MutableInteractionSource() }
                    // Select mode: picked lines' containers reach into the page
                    // margin, so the swipe fade steps aside (and the swipe with it).
                    val pagerEdgeFade = if (lyricsSelection.active) 0.dp else horizontalPadding
                    // The lyrics lead when their page is current and visible:
                    // the expanded page, or the small window at rest (not the
                    // one-line row). In focus the cover leads, list or not: a
                    // song change flows in from the tapped side (owner 2026-11-03).
                    val lyricsLead = detailPage == NowPlayingDetailPage.Lyrics &&
                        (
                            stageMode == NowPlayingStageMode.Expanded ||
                                !lyricOneLine && stageMode != NowPlayingStageMode.Immersive
                            )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .runOnBelow(focusLyricOverflowPx)
                            .reportLyricsSurface(
                                primary = lyricsLead,
                                landingFromTop = if (stageMode == NowPlayingStageMode.Expanded) {
                                    LyricsPageLandingFromTop
                                } else {
                                    LyricsWindowLandingFromTop
                                },
                                clipLight = false,
                            )
                            .edgeFade(start = pagerEdgeFade, end = pagerEdgeFade)
                            .fadeRunOn(focusLyricOverflowPx, focusRunOnFade),
                    ) {
                        HorizontalPager(
                            state = pagerState,
                            beyondViewportPageCount = 1,
                            // The one-line row is not a pager page: swipe only
                            // once the lyric page has expanded.
                            userScrollEnabled = stageMode != NowPlayingStageMode.Immersive &&
                                !(lyricOneLine && stageMode == NowPlayingStageMode.Compact) &&
                                !lyricsSelection.active,
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
                                    // Short-window focus: the lyrics line up with the cover.
                                    // A list shown only in focus keeps the focus's width
                                    // all the way (it fades with the focus instead): a
                                    // width that eases re-wraps the lines mid-exit, which
                                    // read as a one-frame jump (QA 2026-11-05).
                                    pageInset = if (focusOpensList) budget.focusLyricInset else focusLyricInset,
                                    // The run-on under the transport: looser dots
                                    // while it shows (owner 2026-11-05).
                                    listCoveredPx = { focusLyricOverflowPx },
                                    listCoveredShown = { 1f - focusIdleShown },
                                    state = state,
                                    positionMs = positionMs,
                                    aboutUiState = aboutUiState,
                                    notes = notesState,
                                    immersiveProgress = if (focusKeepsList) 0f else immersiveProgress,
                                    onRetryFetchSongInfo = onRetryFetchSongInfo,
                                    listFocus = if (focusKeepsList) {
                                        { resolvedStageProgress.immersive }
                                    } else {
                                        { 0f }
                                    },
                                    // Shown only in focus (one resting line): it
                                    // opens pinned, so it never glides up a line
                                    // as the anchor would move mid-focus.
                                    listAnchorFocus = if (focusOpensList) {
                                        { 1f }
                                    } else if (focusKeepsList) {
                                        { resolvedStageProgress.immersive }
                                    } else {
                                        { 0f }
                                    },
                                    // Focus prints the lines a size up, in proportion
                                    // with the title and transport (owner 2026-11-04).
                                    // The focus-only list holds the focus size, as it
                                    // holds the width: a shrinking font re-wraps the
                                    // lines mid-exit (QA 2026-11-05).
                                    lyricScale = if (focusOpensList) {
                                        FocusLyricScale
                                    } else if (focusKeepsList) {
                                        1f + (FocusLyricScale - 1f) * immersiveProgress
                                    } else {
                                        1f
                                    },
                                    upNext = if (focusKeepsList && stageMode == NowPlayingStageMode.Immersive) {
                                        state.upNextLyrics
                                    } else {
                                        null
                                    },
                                    endOverflow = horizontalPadding,
                                    // Progress read in the layer, not captured: the
                                    // modifier stays the same across reshape frames,
                                    // so the hidden compact page can skip them.
                                    modifier = pageModifier.graphicsLayer {
                                        val compact = resolvedStageProgress.compact
                                        alpha = when {
                                            focusOpensList -> resolvedStageProgress.immersive * compact
                                            lyricOneLine -> 0f
                                            else -> compact
                                        }
                                        translationY = 12.dp.toPx() * resolvedStageProgress.detail
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
                                        lyricsSelection = lyricsSelection,
                                        onLyricsUserScroll = { lyricsAutoScroll = false },
                                        onSeekToMs = { targetMs ->
                                            lyricsAutoScroll = true
                                            lyricsRecenterTick += 1
                                            lyricsActions.onSeekToMs(targetMs)
                                        },
                                        onRetryCanonical = onRetryFetchSongInfo,
                                        onDeleteNote = onDeleteNote,
                                        noteDraft = noteDraft,
                                        noteWriting = noteBar.open,
                                        lyricsEndOverflow = horizontalPadding,
                                        modifier = pageModifier.graphicsLayer {
                                            // Fade + grow the lyrics IN WITH the
                                            // reshape (fully visible by ~70%),
                                            // instead of a late upward slide that
                                            // read as "expand first, lyrics after".
                                            val detail = resolvedStageProgress.detail
                                            alpha = ((detail - 0.1f) / 0.6f)
                                                .coerceIn(0f, 1f)
                                            val sc = 0.96f + 0.04f * detail
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
                                    // Centred in the slot, not in the run-on below it.
                                    .offset { IntOffset(0, -focusLyricOverflowPx / 2) }
                                    .fillMaxWidth()
                                    .heightIn(max = OneLineLyricRowHeight)
                                    .padding(horizontal = horizontalPadding)
                                    .graphicsLayer {
                                        alpha = if (focusOpensList) {
                                            compactProgress * (1f - resolvedStageProgress.immersive)
                                        } else {
                                            compactProgress
                                        }
                                    },
                            )
                        }

                        if (stageMode == NowPlayingStageMode.Immersive) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    // Not the run-on: the transport's gaps stay the transport's.
                                    .padding(bottom = focusLyricOverflow)
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
                            playMode = state.playMode,
                            onSetPlayMode = playbackActions.onSetPlayMode,
                            controlSize = stageControlSize,
                            live = stageControlsLive,
                            prevToTopRow = maxOf(shortFocusProgress, compactFocusProgress),
                            atEase = focusIdleShown,
                            modifier = Modifier
                                // At ease, the first tap on the hidden
                                // transport only brings it back.
                                .lyricToolsInPlaceWakeGuard(focusIdle)
                                .padding(horizontal = horizontalPadding)
                                // At ease the slot gives the hero's artist line
                                // from its bottom: the progress bar moves up with
                                // it, the stepped-away first row clipping above.
                                .wrapContentHeight(align = Alignment.Top, unbounded = true)
                                .graphicsLayer {
                                    translationY = 48.dp.toPx() * detailProgress - heroLift.toPx()
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
                        alpha = 1f - noteHeroYield.coerceIn(0f, 1f),
                        oneLine = budget.heroOneLine,
                        // Focus stacks the one-row hero back (owner 2026-11-05).
                        stackable = budget.focusHeroStacked,
                        stacking = if (budget.focusHeroStacked) {
                            focusIdleShown
                        } else {
                            0f
                        },
                        // Stacked, it lines up with the cover's edges like the
                        // lyrics (owner 2026-11-05 「跟上面那个封面平齐」).
                        stackInset = budget.focusLyricInset,
                        stackLift = heroLift,
                        modifier = Modifier.padding(horizontal = horizontalPadding),
                    )

                    StageHeightSlot(
                        height = bottomAccessoryHeight,
                        alpha = 1f,
                        // Edge-to-edge (no adjustResize) means we must consume the IME
                        // inset ourselves, or the keyboard covers the Ask Gemini bar.
                        // Applied here (outside the slot's fixed height) so the whole
                        // accessory lifts above the keyboard; 0 when the IME is hidden.
                        // The column already cleared the nav bar (padding, not
                        // consumed), and the IME inset spans it: lift by the
                        // keyboard minus the bar, or the bar counts twice.
                        modifier = Modifier.windowInsetsPadding(
                            imeAboveNavigationBarInsets(WindowInsets.ime, WindowInsets.navigationBars),
                        ),
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
                playingOn = state.playingOn,
                                onQueueClick = { showQueue = true },
                                onDevicesClick = { showDevicesSheet = true },
                                onWriteClick = {
                                    noteBar.requestWriting()
                                    onDetailPageChange(NowPlayingDetailPage.Note)
                                    onStageModeChange(NowPlayingStageMode.Expanded)
                                },
                                castState = castState,
                                onCastClick = onCastClick,
                                // Pure crossfade with the accessory pager — no
                                // downward translation. The slot clipToBounds sits
                                // at the bottom safe-area edge, so sliding the pills
                                // DOWN clipped them mid-fade and exposed the near-
                                // white gradient bottom under the nav bar.
                                // Padding (was on the wrapper Box) keeps the pills
                                // at the 24dp inset now that the Box is edge-to-edge.
                                // Clean focus: the pills break into dots and
                                // stop taking taps (owner 2026-11-03).
                                modifier = Modifier
                                    .padding(horizontal = horizontalPadding)
                                    .graphicsLayer {
                                        alpha = compactProgress
                                    }
                                    .dotDissolve { immersiveProgress }
                                    .retreatInput { resolvedStageProgress.immersive },
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
                                                asked = aboutUiState.askedQuestions(),
                                            )
                                            NowPlayingDetailPage.Note -> NoteWriteBar(
                                                state = noteBar,
                                                current = state.noteTarget(),
                                                positionMs = positionMs,
                                                onSave = onSaveNote,
                                                // The keyboard comes once the page
                                                // has landed, not mid-reshape.
                                                focusGate = stageMode == NowPlayingStageMode.Expanded &&
                                                    pagerState.settledPage == NowPlayingDetailPage.Note.ordinal &&
                                                    detailProgress >= NoteWriteFocusStageProgress,
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .padding(top = NoteWriteBarDefaults.TopGap),
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

        // Lyrics takes ownership as soon as its stage starts moving. Keeping
        // the opening shared cover enabled would bypass the hero's alpha/clip
        // and leave a full-size cover in the shared overlay until it settles.
        CoverTransitionOverlay(
            coverArtUrl = state.coverArtUrl,
            progress = detailProgress,
            startX = stageOffsetX + horizontalPadding,
            startY = lerpDp(restPose.topBar, focusPose.topBar, immersiveProgress),
            startSize = compactCoverSize,
            requestSize = maxOf(restPose.cover, focusPose.cover),
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
                upcoming = state.upcomingQueue,
                activityContext = state.activityContext,
                isPlaying = state.isPlaying,
                edit = state.queueEdit,
                shuffling = state.playMode.shuffle,
                onItemClick = { index ->
                    onSkipToQueueItem(index)
                    showQueue = false
                },
                editor = queueEditor,
                onDismiss = { showQueue = false },
            )
        }
    }

    if (showDevicesSheet) {
        ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
            DevicesSheet(
                providerId = devicesState.providerId,
                devices = devicesState.devices.localizedForSheet(),
                loading = devicesState.loading,
                busyDeviceId = devicesState.busyDeviceId,
                errorMessage = devicesState.errorMessage?.asString(),
                onRefresh = onRefreshDevices,
                onSelect = onSelectDevice,
                onDismiss = { showDevicesSheet = false },
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
    backPreview: StageBackPreview = StageBackPreview.Rest,
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
    onLyricsMessage: (String) -> Unit = {},
    onRatingChange: (Float) -> Unit,
    onToggleFavorite: () -> Unit,
    onAddCurrentToLibrary: () -> Unit = {},
    onAddCurrentToPlaylist: () -> Unit,
    onSkipToQueueItem: (Int) -> Unit,
    queueEditor: QueueEditActions = QueueEditActions.None,
    onSetPlayMode: (PlayMode) -> Unit = {},
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
    onSaveNote: (NoteSaveRequest) -> Unit = {},
    noteSortMode: NoteSortMode = NoteSortMode.Timeline,
    onNoteSortModeChange: (NoteSortMode) -> Unit = {},
    noteDraft: NoteDraftState,
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
    // The Note page's write bar; the Write pill asks it for the keyboard.
    val noteBar = rememberNoteWriteBarState(noteDraft)
    ExpirePendingNoteWrite(noteBar)
    var lyricsAutoScroll by remember(state.songId) { mutableStateOf(true) }
    var lyricsRecenterTick by remember(state.songId) { mutableIntStateOf(0) }

    val playInteractionSource = rememberNowPlayingButtonGroupInteractionSource()
    val nextInteractionSource = rememberNowPlayingButtonGroupInteractionSource()
    val playPressed by playInteractionSource.collectIsPressedAsState()

    val pagerState = rememberPagerState(
        initialPage = detailPage.ordinal,
        pageCount = { 3 },
    )
    val pagerScope = rememberCoroutineScope()
    // ONE driver per direction (see SyncDetailPageWithPager).
    SyncDetailPageWithPager(
        pagerState = pagerState,
        detailPage = detailPage,
        onDetailPageChange = onDetailPageChange,
        onAboutOpened = onAboutOpened,
    )

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
    // lyric tools step away after LyricToolsIdleMs without a touch while
    // synced lyrics play.
    val lyricToolsIdle = rememberLyricToolsIdleState()
    val lyricsSelection = rememberLyricsSelectionState(state.songId, state.lyrics)
    val idleEligible = lyricToolsIdleEligible(
        isPlaying = state.isPlaying,
        hasSyncedLyrics = hasSyncedLyrics,
        autoScroll = lyricsAutoScroll,
        lyricsPageSelected = detailPage == NowPlayingDetailPage.Lyrics,
        toolsOnScreen = true,
        selectingLines = lyricsSelection.active,
    )
    LaunchedEffect(idleEligible) { lyricToolsIdle.track(idleEligible) }
    LyricsSelectionHost(
        state = state,
        selection = lyricsSelection,
        lyricsPageOnScreen = pagerState.targetPage == NowPlayingDetailPage.Lyrics.ordinal,
        onMessage = onLyricsMessage,
    )
    val lyricToolsAlpha by animateFloatAsState(
        targetValue = if (lyricToolsIdle.idle) 0f else 1f,
        animationSpec = YoinMotion.defaultEffectsSpec(role = YoinMotionRole.Standard),
        label = "wideLyricToolsAlpha",
    )
    // Focus at ease, as in the single column and landscape (owner 2026-11-05;
    // the tablet kept its buttons, owner 2026-11-08 「聚焦模式 3 秒之后按键还是
    // 显示的」): FocusIdleMs without a touch while synced lyrics play, the
    // transport's buttons and times step away and the progress bar stays.
    val focusIdle = rememberLyricToolsIdleState()
    val wideFocusIdleEligible = stageMode == NowPlayingStageMode.Immersive &&
        state.isPlaying && hasSyncedLyrics && !lyricsSelection.active
    LaunchedEffect(wideFocusIdleEligible) { focusIdle.track(wideFocusIdleEligible, FocusIdleMs) }
    val focusIdleProgress by animateFloatAsState(
        targetValue = if (wideFocusIdleEligible && focusIdle.idle) 1f else 0f,
        animationSpec = YoinMotion.slowSpatialSpec(role = YoinMotionRole.Standard),
        label = "wideFocusIdle",
    )
    // Small by default; the Ask Gemini bar grows when focused (matches Compact).
    val bottomAccessoryTargetHeight = when {
        detailPage == NowPlayingDetailPage.About && askState is AskBarState.Focused -> 276.dp
        // Focus: the lyric tools step away with the tabs and the lyrics grow.
        stageMode == NowPlayingStageMode.Immersive && detailPage == NowPlayingDetailPage.Lyrics -> 0.dp
        detailPage == NowPlayingDetailPage.Lyrics && lyricToolsIdle.idle -> 0.dp
        detailPage == NowPlayingDetailPage.Note ->
            noteBar.height(LocalDensity.current) + NoteWriteBarDefaults.TopGap
        else -> WideAccessoryHeight
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
    val coverMenu = rememberCoverMenuState()
    val density = LocalDensity.current
    val navBottom = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    val dualMetrics = rememberDualPaneMetrics(navBottom = navBottom)

    BoxWithConstraints(
        // Edge-to-edge: the parent NowPlayingScreen gradient fills behind the
        // system bars. The old blanket padding(systemBars) inset the whole Box and
        // exposed the light window background as a WHITE band in the nav-bar
        // region. Inset only the TOP + sides on the content; the bottom stays
        // full-bleed so the gradient reaches the nav-bar edge (the button group
        // gets its own navigationBarsPadding to stay tappable).
        modifier = modifier
            .fillMaxSize()
            .lyricToolsTouchTracker(lyricToolsIdle)
            .lyricToolsTouchTracker(focusIdle),
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
        // Focus (owner 2026-11-03), as in the single column: the top bar,
        // the tabs and the lyric tools step away and the lyrics print as
        // halftone. A short window keeps its bar, which carries the title.
        // Read in the draw phase and by the input guards, so through state.
        val focusGate by rememberUpdatedState(focusOffered)
        val barStays by rememberUpdatedState(titleInBar)
        val focusNow: () -> Float = { if (focusGate) stageProgress?.immersive ?: 0f else 0f }
        val barRetreat: () -> Float = { if (barStays) 0f else focusNow() }
        val inFocus = stageMode == NowPlayingStageMode.Immersive
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
                // "Title - Artist" under the label when the window is too
                // short for the title block (focus keeps the title in place).
                subline = { if (titleInBar) 1f else 0f },
                // A short window keeps this bar in focus: its back steps out
                // of focus first, like the system back.
                onBack = {
                    if (stageMode != NowPlayingStageMode.Immersive || !onStageBack()) onDismiss()
                },
                onAlbumClick = onAlbumClick,
                onArtistClick = onArtistClick,
                onPlaylistClick = onPlaylistClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRetreat(barRetreat),
                // The queue / devices / write pills, as icons next to the
                // panel toggle (owner 2026-11-06: 「左下角的三个按钮移到右上角」).
                trailingAction = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TopBarActionIcons(
                            supportsYoinCast = state.serviceFeatures.supportsYoinCast,
                            onQueueClick = { showQueue = true },
                            onDevicesClick = { showDevicesSheet = true },
                            onWriteClick = {
                                noteBar.requestWriting()
                                onDetailPageChange(NowPlayingDetailPage.Note)
                            },
                            castState = castState,
                            onCastClick = onCastClick,
                            playingOn = state.playingOn,
                        )
                        if (topBarAction != null) {
                            Spacer(modifier = Modifier.width(8.dp))
                            topBarAction()
                        }
                    }
                },
            )
            Spacer(modifier = Modifier.height(budget.barGap))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .backPreviewTransform(backPreview),
            ) {
                // LEFT — passive (owner 2026-11-06, prototype v10): the cover
                // with the standard rating column at its right, the transport
                // under them (its play-mode button and progress end where the
                // rating ends), the title and artist set at the bottom, level
                // with the lyric tools. The queue / devices / write pills sit
                // in the top bar. Focus: the rating retreats, the cover grows.
                val slotWidth = lerpDp(restPose.slot, focusPose.slot, focus)
                val coverSize = lerpDp(restPose.cover, focusPose.cover, focus)
                val ratingSide = lerpDp(restPose.ratingSide, focusPose.ratingSide, focus)
                Column(
                    modifier = Modifier
                        .width(slotWidth + WideColumnGap)
                        .fillMaxHeight()
                        .padding(end = WideColumnGap)
                        // Clears the nav bar like the right column's tools.
                        .navigationBarsPadding(),
                    horizontalAlignment = Alignment.Start,
                ) {
                    Row(modifier = Modifier.height(coverSize)) {
                        AlbumCover(
                            songId = state.songId,
                            coverArtUrl = state.coverArtUrl,
                            revealDirection = skipDirection,
                            // The pill's cover grows into this one (PillMorph.Cover)
                            // when the two columns open or close over the bar. It must
                            // stay BOUNDED: a fillMaxWidth shared cover resolved to an
                            // unbounded width in the shared-transition lookahead and
                            // propagated Constraints.Infinity into the shell bar's
                            // measurement, which crashed. The explicit size(coverSize)
                            // below is that bound — keep it.
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope,
                            interactionSource = coverInteraction,
                            menu = coverMenu,
                            modifier = Modifier
                                .size(coverSize)
                                .nowPlayingCoverGestures(
                                    interactionSource = coverInteraction,
                                    menu = coverMenu,
                                    onClickLabel = if (stageMode == NowPlayingStageMode.Immersive) {
                                        stringResource(R.string.np_cd_show_lyrics_rating_wide)
                                    } else {
                                        stringResource(R.string.np_cd_focus_artwork_wide)
                                    },
                                    onClick = if (focusOffered || stageMode == NowPlayingStageMode.Immersive) {
                                        {
                                            if (stageMode == NowPlayingStageMode.Immersive) {
                                                onStageModeChange(NowPlayingStageMode.Compact)
                                            } else {
                                                // Focus shows the lyrics, as in the single column.
                                                onDetailPageChange(NowPlayingDetailPage.Lyrics)
                                                onStageModeChange(NowPlayingStageMode.Immersive)
                                            }
                                        }
                                    } else {
                                        null
                                    },
                                )
                                .reportFocusCover(),
                        )
                        // The standard rating column (the bar, the heart under
                        // it) beside the cover; it retreats in focus.
                        Box(
                            modifier = Modifier
                                .width(ratingSide)
                                .fillMaxHeight()
                                .clipToBounds(),
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .padding(start = DualPaneRatingSide - DualPaneRatingColumn)
                                    .width(DualPaneRatingColumn)
                                    .fillMaxHeight()
                                    .graphicsLayer {
                                        alpha = (1f - focus).coerceIn(0f, 1f)
                                        translationX = 24.dp.toPx() * focus
                                    }
                                    .retreatInput(focusNow),
                            ) {
                                NowPlayingRatingBar(
                                    songId = state.songId,
                                    rating = state.rating,
                                    onRatingCommit = onRatingChange,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                LandscapeFavoriteButtons(
                                    state = state,
                                    onToggleFavorite = onToggleFavorite,
                                    onAddCurrentToPlaylist = onAddCurrentToPlaylist,
                                    onAddCurrentToLibrary = onAddCurrentToLibrary,
                                )
                                // Room for the heart's tap-bounce (scales to 1.25×).
                                Spacer(modifier = Modifier.height(6.dp))
                            }
                        }
                    }
                    Spacer(
                        modifier = Modifier.height(
                            lerpDp(restPose.coverToTransport, focusPose.coverToTransport, focus),
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
                        playMode = state.playMode,
                        onSetPlayMode = onSetPlayMode,
                        // Capped by the focus itself, so leaving focus at
                        // ease brings the buttons back with the cover.
                        atEase = minOf(focusIdleProgress, focus),
                        modifier = Modifier
                            .width(slotWidth)
                            // At ease, the first tap on the hidden buttons
                            // only brings them back.
                            .lyricToolsInPlaceWakeGuard(focusIdle),
                    )
                    // The title sinks to the bottom (owner 2026-11-06 「文字优先靠下」).
                    Spacer(modifier = Modifier.weight(1f))
                    if (!titleInBar) {
                        Spacer(modifier = Modifier.height(restPose.transportToTitle))
                        // Same press language as Compact: route stretch, no ripple.
                        // The title stays in focus (owner 2026-11-03).
                        Column(
                            modifier = Modifier.width(slotWidth),
                        ) {
                            val wideArtistRoute = artistId?.let { id ->
                                rememberNowPlayingRouteInteraction(
                                    onNavigate = { onArtistClick(id) },
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = state.artist,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false).then(
                                        wideArtistRoute?.let { route ->
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
                                    ),
                                )
                                NowPlayingFormatTags(state)
                            }
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
                }

                // RIGHT — lyrics-only: small tab indicator + detail pager + action bar.
                Column(
                    modifier = Modifier
                        // Lyrics take whatever the controls-sized left column leaves.
                        .weight(1f)
                        .fillMaxHeight()
                        // Idle tools collapse their slot and the lyrics grow into
                        // it: the first tap there only wakes the tools.
                        .lyricToolsWakeGuard(
                            state = lyricToolsIdle,
                            enabled = detailPage == NowPlayingDetailPage.Lyrics && !inFocus,
                            band = WideAccessoryHeight + navBottom,
                        ),
                ) {
                    // Small text indicator (collapsed-card feel), not the big button
                    // group. Inset to align with the lyric text below.
                    CompactTextTabs(
                        selected = NowPlayingDetailPage.entries[pagerState.targetPage],
                        onSelect = { page ->
                            pagerScope.launch { pagerState.settleToPage(page.ordinal) }
                        },
                        modifier = Modifier
                            .padding(start = 24.dp)
                            .focusRetreat(focusNow),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .reportLyricsSurface(
                                primary = detailPage == NowPlayingDetailPage.Lyrics,
                                landingFromTop = LyricsPageLandingFromTop,
                                clipLight = true,
                            )
                            // Soft-mask the leading/trailing edges so a page fades
                            // instead of hard-cutting during the horizontal swipe
                            // (matches the Compact lyrics pager).
                            // Select mode: picked lines reach into the margin,
                            // so the swipe fade (and the swipe) step aside.
                            .edgeFade(
                                start = if (lyricsSelection.active) 0.dp else 24.dp,
                                end = if (lyricsSelection.active) 0.dp else 24.dp,
                            ),
                    ) {
                        HorizontalPager(
                            state = pagerState,
                            beyondViewportPageCount = 1,
                            // Focus holds the Lyrics page (its tabs have stepped away).
                            userScrollEnabled = !lyricsSelection.active && !inFocus,
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
                            lyricsSelection = lyricsSelection,
                            onLyricsUserScroll = { lyricsAutoScroll = false },
                            onSeekToMs = { targetMs ->
                                lyricsAutoScroll = true
                                lyricsRecenterTick += 1
                                onSeekToMs(targetMs)
                            },
                            onRetryCanonical = onRetryFetchSongInfo,
                            onDeleteNote = onDeleteNote,
                            noteDraft = noteDraft,
                            noteWriting = noteBar.open,
                            lyricsFocus = focusNow,
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
                                        NowPlayingDetailPage.Lyrics -> LyricsTools(
                                            state = state,
                                            selection = lyricsSelection,
                                            modifier = Modifier
                                                .graphicsLayer { alpha = lyricToolsAlpha }
                                                .focusRetreat(focusNow),
                                            searchBarState = lyricsSearchBarState,
                                            canRecenter = !lyricsAutoScroll && hasSyncedLyrics,
                                            onSearchClick = onOpenLyricsSearch,
                                            onTranslateClick = onTranslateLyrics,
                                            onRecenterClick = {
                                                lyricsAutoScroll = true
                                                lyricsRecenterTick += 1
                                            },
                                            onEditLyricsClick = { showApplyDialog = true },
                                            onMessage = onLyricsMessage,
                                        )
                                        NowPlayingDetailPage.About -> AskGeminiBar(
                                            askState = askState,
                                            onSubmit = onAskQuestion,
                                            onFocus = onAskBarFocused,
                                            onCollapseRequest = onAskBarCollapseRequested,
                                            onDismissError = onDismissAskError,
                                            asked = aboutUiState.askedQuestions(),
                                        )
                                        NowPlayingDetailPage.Note -> NoteWriteBar(
                                            state = noteBar,
                                            current = state.noteTarget(),
                                            positionMs = positionMs,
                                            onSave = onSaveNote,
                                            focusGate = pagerState.settledPage == NowPlayingDetailPage.Note.ordinal,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(top = NoteWriteBarDefaults.TopGap),
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
                upcoming = state.upcomingQueue,
                activityContext = state.activityContext,
                isPlaying = state.isPlaying,
                edit = state.queueEdit,
                shuffling = state.playMode.shuffle,
                onItemClick = { index ->
                    onSkipToQueueItem(index)
                    showQueue = false
                },
                editor = queueEditor,
                onDismiss = { showQueue = false },
            )
        }
    }

    if (showDevicesSheet) {
        ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
            DevicesSheet(
                providerId = devicesState.providerId,
                devices = devicesState.devices.localizedForSheet(),
                loading = devicesState.loading,
                busyDeviceId = devicesState.busyDeviceId,
                errorMessage = devicesState.errorMessage?.asString(),
                onRefresh = onRefreshDevices,
                onSelect = onSelectDevice,
                onDismiss = { showDevicesSheet = false },
            )
        }
    }
}

/**
 * Landscape handset (LandscapeNP, owner 2026-11-06, prototype v10). Two forms
 * by width ([resolveLandscapeLayout]):
 * - wide (a phone on its side): the cover fills the height with the standard
 *   rating column at its right; the right column carries the header (the
 *   queue / devices / write icons at its end), the 歌词 / 关于 / 笔记 tabs, a
 *   few solid lyric lines, the two-row transport and title + artist on one row.
 * - stacked (16:9, the Fold outer screen): the transport and the title sit
 *   under the cover in the left column; the right column is header, tabs and
 *   a whole column of lyrics.
 * Expanded (a tab, or a tap on the lyrics): the transport steps away and the
 * header's end slot carries the page's own actions — the lyric tools, Ask
 * Gemini, the write bar (「右上角那格」); stacked, the rating drops under the
 * cover and the left column narrows to it so the page gets the room.
 * Focus (a tap on the cover): the header, the tabs and the rating retreat;
 * the lyrics print through dots with the current line on the third row, the
 * transport folds to one row, and FocusIdleMs without a touch hides its
 * buttons and times (the progress bar stays) while the lyrics spread down.
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
    onLyricsMessage: (String) -> Unit = {},
    onRatingChange: (Float) -> Unit,
    onToggleFavorite: () -> Unit,
    onAddCurrentToLibrary: () -> Unit = {},
    onAddCurrentToPlaylist: () -> Unit,
    onSkipToQueueItem: (Int) -> Unit,
    queueEditor: QueueEditActions = QueueEditActions.None,
    onSetPlayMode: (PlayMode) -> Unit,
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
    onSaveNote: (NoteSaveRequest) -> Unit,
    noteSortMode: NoteSortMode,
    onNoteSortModeChange: (NoteSortMode) -> Unit,
    noteDraft: NoteDraftState,
    onDeleteNote: (String) -> Unit,
    devicesState: DevicesSheetState,
    onRefreshDevices: () -> Unit,
    onSelectDevice: (YoinDevice) -> Unit,
    castState: CastState,
    onCastClick: () -> Unit,
    backPreview: StageBackPreview = StageBackPreview.Rest,
    // The header arrow in Expanded steps the stage back, like system back.
    onStageBack: () -> Boolean = { false },
    // The edge capsule's upright pill grows into the cover (PillMorph.Cover).
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier,
) {
    val lyricsSearchBarState = rememberSearchBarState()
    var showQueue by remember { mutableStateOf(false) }
    var showDevicesSheet by remember(state.songId) { mutableStateOf(false) }
    // The Note page's write bar; the Write icon asks it for the keyboard.
    val noteBar = rememberNoteWriteBarState(noteDraft)
    ExpirePendingNoteWrite(noteBar)
    var showApplyDialog by remember(state.songId) { mutableStateOf(false) }
    var lyricsAutoScroll by remember(state.songId) { mutableStateOf(true) }
    var lyricsRecenterTick by remember(state.songId) { mutableIntStateOf(0) }
    val hasSyncedLyrics = remember(state.lyrics) { state.lyrics.any { it.startMs != null } }
    val lyricsSelection = rememberLyricsSelectionState(state.songId, state.lyrics)
    val playInteractionSource = rememberNowPlayingButtonGroupInteractionSource()
    val nextInteractionSource = rememberNowPlayingButtonGroupInteractionSource()
    val playPressed by playInteractionSource.collectIsPressedAsState()
    val resolvedStageProgress = stageProgress ?: rememberNowPlayingStageProgress(stageMode)
    val detailProgress = resolvedStageProgress.detail
    val focus = resolvedStageProgress.immersive
    val expanded = stageMode == NowPlayingStageMode.Expanded
    val inFocus = stageMode == NowPlayingStageMode.Immersive
    // Settled in focus or in the page: the layers that stepped away leave
    // the tree, so they never take a tap meant for what took their place.
    val focusSettled = inFocus && focus >= 1f - HiddenLayerVisibilityThreshold
    val expandedSettled = expanded && detailProgress >= 1f - HiddenLayerVisibilityThreshold

    val pagerState = rememberPagerState(initialPage = detailPage.ordinal, pageCount = { 3 })
    val pagerScope = rememberCoroutineScope()
    // ONE driver per direction (see SyncDetailPageWithPager).
    SyncDetailPageWithPager(
        pagerState = pagerState,
        detailPage = detailPage,
        onDetailPageChange = onDetailPageChange,
        onAboutOpened = onAboutOpened,
    )
    // The lyric tools (expanded Lyrics page, header end) step away after
    // LyricToolsIdleMs without a touch, as everywhere (断点交接 §3.2).
    val lyricToolsIdle = rememberLyricToolsIdleState()
    // Focus at ease (owner 2026-11-05, as in portrait): FocusIdleMs without a
    // touch while synced lyrics play, the transport's buttons and times step
    // away (the progress bar stays) and the lyrics spread down.
    val focusIdle = rememberLyricToolsIdleState()
    val idleEligible = lyricToolsIdleEligible(
        isPlaying = state.isPlaying,
        hasSyncedLyrics = hasSyncedLyrics,
        autoScroll = lyricsAutoScroll,
        lyricsPageSelected = detailPage == NowPlayingDetailPage.Lyrics,
        toolsOnScreen = expanded,
        selectingLines = lyricsSelection.active,
    )
    LaunchedEffect(idleEligible) { lyricToolsIdle.track(idleEligible) }
    val lyricToolsAlpha by animateFloatAsState(
        targetValue = if (lyricToolsIdle.idle) 0f else 1f,
        animationSpec = YoinMotion.defaultEffectsSpec(role = YoinMotionRole.Standard),
        label = "landscapeLyricToolsAlpha",
    )
    val focusIdleEligible = inFocus && state.isPlaying && hasSyncedLyrics
    LaunchedEffect(focusIdleEligible) { focusIdle.track(focusIdleEligible, FocusIdleMs) }
    val focusIdleProgress by animateFloatAsState(
        targetValue = if (focusIdleEligible && focusIdle.idle) 1f else 0f,
        animationSpec = YoinMotion.slowSpatialSpec(role = YoinMotionRole.Standard),
        label = "landscapeFocusIdle",
    )
    val atEase = (focusIdleProgress * (1f - detailProgress)).coerceIn(0f, 1f)
    LyricsSelectionHost(
        state = state,
        selection = lyricsSelection,
        lyricsPageOnScreen = expanded && pagerState.targetPage == NowPlayingDetailPage.Lyrics.ordinal,
        onMessage = onLyricsMessage,
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .lyricToolsTouchTracker(lyricToolsIdle)
            .lyricToolsTouchTracker(focusIdle)
            // The cover side also clears the camera cutout (a cover under the
            // hole reads as damage); the far edge clears it below.
            .windowInsetsPadding(
                WindowInsets.systemBars.union(WindowInsets.displayCutout.only(WindowInsetsSides.Start)),
            )
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        val density = LocalDensity.current
        val endCutout = WindowInsets.displayCutout.only(WindowInsetsSides.End).asPaddingValues()
            .calculateEndPadding(LocalLayoutDirection.current)
        val frameWidth = maxWidth - endCutout
        val frameHeight = maxHeight
        val labelLine = with(density) { MaterialTheme.typography.labelLarge.lineHeight.toDp() }
        val geometry = remember(frameWidth, frameHeight, labelLine) {
            resolveLandscapeLayout(frameWidth, frameHeight, labelLine)
        }
        val stacked = geometry.stacked

        // Rest ⇄ focus by immersive, then ⇄ the Expanded layout by detail.
        fun stage(rest: Dp, focused: Dp, expandedValue: Dp): Dp =
            lerpDp(lerpDp(rest, focused, focus), expandedValue, detailProgress)

        val focusTransport = shortFocusControlsHeight(LandscapeControlSizeToken)
        val transportHeight = lerpDp(geometry.restTransport, focusTransport, focus)
        val titleTop = frameHeight - LandscapeTitleRow
        // Focus: one transport row + the progress row right above the title.
        val focusTransportTop = titleTop - LandscapeTransportToTitle - focusTransport
        // At ease the buttons step away: only the progress row stays.
        val focusProgressTop = titleTop - LandscapeTransportToTitle - LandscapeFocusProgressRow

        // ── Cover ──
        val coverSide = stage(geometry.restCover, geometry.focusCover, geometry.restCover)
        val focusCoverX = if (stacked) (geometry.leftWidth - geometry.focusCover) / 2 else 0.dp
        // Stacked at ease: the cover centres in the room over the progress row.
        val focusCoverY = if (stacked) {
            lerpDp(
                0.dp,
                ((focusProgressTop - LandscapeCoverToTransport - geometry.focusCover) / 2).coerceAtLeast(0.dp),
                atEase,
            )
        } else {
            (frameHeight - geometry.focusCover) / 2
        }
        val coverX = stage(0.dp, focusCoverX, 0.dp)
        val coverY = stage(0.dp, focusCoverY, 0.dp)

        // ── Columns ──
        val columnX = stage(geometry.restColumnX, geometry.focusColumnX, geometry.expandedColumnX)
        val columnWidth = frameWidth - columnX
        // Stacked: the transport and the title live in the left column, which
        // narrows to the cover while a page is open.
        val transportX = if (stacked) 0.dp else columnX
        val transportWidth = if (stacked) geometry.leftWidth else columnWidth
        val transportTop = if (stacked) {
            lerpDp(geometry.restCover + LandscapeCoverToTransport, focusTransportTop, focus)
        } else {
            lerpDp(titleTop - LandscapeTransportGap - geometry.restTransport, focusTransportTop, focus)
        }
        val titleX = if (stacked) 0.dp else columnX
        val titleWidth = if (stacked) geometry.leftWidth else columnWidth

        // The lyric area: under the header and the tabs at rest and expanded,
        // the whole column in focus (down to the transport — at ease, to the
        // progress row).
        val lyricsTop = stage(LandscapeLyricsTop, 0.dp, LandscapeLyricsTop)
        val lyricsBottom = if (stacked) {
            frameHeight
        } else {
            stage(
                titleTop - LandscapeTransportGap - geometry.restTransport - LandscapeLyricsToTransport,
                lerpDp(
                    focusTransportTop - LandscapeLyricsToTransport,
                    focusProgressTop - LandscapeLyricsToTransport / 2,
                    atEase,
                ),
                titleTop - LandscapeExpandedLyricsToTitle,
            )
        }

        val coverInteraction = remember { MutableInteractionSource() }
        val coverMenu = rememberCoverMenuState()
        Box(
            modifier = Modifier
                .offset(x = coverX, y = coverY)
                .size(coverSide)
                .backPreviewTransform(backPreview),
        ) {
            AlbumCover(
                songId = state.songId,
                coverArtUrl = state.coverArtUrl,
                revealDirection = skipDirection,
                // The pill's cover grows into this one. Bounded: the Box above
                // sizes it, so the shared-transition lookahead never meets an
                // unbounded constraint.
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                interactionSource = coverInteraction,
                menu = coverMenu,
                modifier = Modifier
                    .fillMaxSize()
                    .nowPlayingCoverGestures(
                        interactionSource = coverInteraction,
                        menu = coverMenu,
                        onClickLabel = if (inFocus) {
                            stringResource(R.string.np_cd_show_lyrics_rating_landscape)
                        } else {
                            stringResource(R.string.np_cd_focus_artwork_landscape)
                        },
                        onClick = {
                            if (inFocus) {
                                onStageModeChange(NowPlayingStageMode.Compact)
                            } else {
                                onDetailPageChange(NowPlayingDetailPage.Lyrics)
                                onStageModeChange(NowPlayingStageMode.Immersive)
                            }
                        },
                    )
                    .reportFocusCover(),
            )
        }

        // The standard rating column (portrait's: the bar, the heart under
        // it) at the cover's right (owner 2026-11-06 「按照原本的标准」). Focus
        // breaks it into dots; stacked, an open page moves it under the cover.
        val ratingAway = if (stacked) detailProgress else 0f
        if (!focusSettled && !(stacked && expandedSettled)) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .offset(x = geometry.restCover + LandscapeRatingGap, y = 0.dp)
                    .width(LandscapeRatingColumn)
                    .height(geometry.restCover)
                    .graphicsLayer {
                        alpha = (1f - ratingAway).coerceIn(0f, 1f)
                        translationX = LandscapeRatingRetreat.toPx() * maxOf(focus, ratingAway)
                    }
                    .dotDissolve { resolvedStageProgress.immersive }
                    .retreatInput {
                        maxOf(resolvedStageProgress.immersive, if (stacked) resolvedStageProgress.detail else 0f)
                    },
            ) {
                NowPlayingRatingBar(
                    songId = state.songId,
                    rating = state.rating,
                    onRatingCommit = onRatingChange,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.height(8.dp))
                LandscapeFavoriteButtons(
                    state = state,
                    onToggleFavorite = onToggleFavorite,
                    onAddCurrentToPlaylist = onAddCurrentToPlaylist,
                    onAddCurrentToLibrary = onAddCurrentToLibrary,
                )
                // Room for the heart's tap-bounce (scales to 1.25×).
                Spacer(modifier = Modifier.height(6.dp))
            }
        }
        if (stacked && (expanded || detailProgress > HiddenLayerVisibilityThreshold)) {
            // Stacked, a page open: the rating lies under the cover.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .offset(x = 0.dp, y = geometry.restCover + LandscapeRatingGap)
                    .width(geometry.restCover)
                    .height(LandscapeRatingRow)
                    .graphicsLayer {
                        alpha = resolvedStageProgress.detail
                        translationY = LandscapeRatingRow.toPx() * (1f - resolvedStageProgress.detail) / 2
                    }
                    .retreatInput { 1f - resolvedStageProgress.detail },
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(LandscapeRatingRow),
                ) {
                    NowPlayingRatingBar(
                        songId = state.songId,
                        rating = state.rating,
                        onRatingCommit = onRatingChange,
                        orientation = Orientation.Horizontal,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                LandscapeFavoriteButtons(
                    state = state,
                    onToggleFavorite = onToggleFavorite,
                    onAddCurrentToPlaylist = onAddCurrentToPlaylist,
                    onAddCurrentToLibrary = onAddCurrentToLibrary,
                )
            }
        }

        // ── Lyrics / the detail pager ──
        val pagerClickSource = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                // Room past the column edges for a selected lyric line's
                // container (the pager clips at its bounds); every page pads
                // it back, so the content stays on the column.
                .offset(x = columnX - LyricsRowInset, y = lyricsTop)
                .width(columnWidth + LyricsRowInset * 2)
                .height((lyricsBottom - lyricsTop).coerceAtLeast(0.dp))
                .reportLyricsSurface(
                    primary = detailPage == NowPlayingDetailPage.Lyrics && !inFocus,
                    landingFromTop = if (expanded) LyricsPageLandingFromTop else LyricsWindowLandingFromTop,
                    clipLight = true,
                )
                .edgeFade(
                    start = if (lyricsSelection.active) 0.dp else LyricsRowInset,
                    end = if (lyricsSelection.active) 0.dp else LyricsRowInset,
                ),
        ) {
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                userScrollEnabled = !inFocus && !lyricsSelection.active,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val pageEntry = NowPlayingDetailPage.entries[page]
                val pageModifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = LyricsRowInset)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // About / Notes run to the column's foot: let them
                        // fade there rather than cut (lyrics fade their own).
                        .then(
                            if (pageEntry == NowPlayingDetailPage.Lyrics) {
                                Modifier
                            } else {
                                Modifier.edgeFade(bottom = LandscapePageBottomFade)
                            },
                        )
                        // At rest a tap on the lyrics (or the page) opens it.
                        .tapWithoutConsumingDrag(enabled = stageMode == NowPlayingStageMode.Compact) {
                            onStageModeChange(NowPlayingStageMode.Expanded)
                        },
                ) {
                    if (!expandedSettled) {
                        CompactDetailPage(
                            page = pageEntry,
                            state = state,
                            positionMs = positionMs,
                            aboutUiState = aboutUiState,
                            notes = notesState,
                            immersiveProgress = 0f,
                            onRetryFetchSongInfo = onRetryFetchSongInfo,
                            listFocus = { resolvedStageProgress.immersive },
                            // Two sung lines above: the current one sits on the
                            // third row (owner 2026-11-06).
                            focusLinesAbove = LandscapeFocusLinesAbove,
                            lyricScale = 1f + (LandscapeFocusLyricScale - 1f) * focus,
                            upNext = if (inFocus) state.upNextLyrics else null,
                            endOverflow = LyricsRowInset,
                            modifier = pageModifier.graphicsLayer {
                                alpha = resolvedStageProgress.compact
                                translationY = 12.dp.toPx() * resolvedStageProgress.detail
                            },
                        )
                    }
                    if (expanded || detailProgress > HiddenLayerVisibilityThreshold) {
                        ExpandedDetailPage(
                            page = pageEntry,
                            state = state,
                            positionMs = positionMs,
                            aboutUiState = aboutUiState,
                            notes = notesState,
                            noteSortMode = noteSortMode,
                            onNoteSortModeChange = onNoteSortModeChange,
                            lyricsAutoScroll = lyricsAutoScroll,
                            lyricsRecenterTick = lyricsRecenterTick,
                            lyricsSelection = lyricsSelection,
                            onLyricsUserScroll = { lyricsAutoScroll = false },
                            onSeekToMs = { targetMs ->
                                lyricsAutoScroll = true
                                lyricsRecenterTick += 1
                                onSeekToMs(targetMs)
                            },
                            onRetryCanonical = onRetryFetchSongInfo,
                            onDeleteNote = onDeleteNote,
                            noteDraft = noteDraft,
                            noteWriting = noteBar.open,
                            // The title stays on screen beside / under the cover.
                            lyricsTitleCard = false,
                            modifier = pageModifier.graphicsLayer {
                                val detail = resolvedStageProgress.detail
                                alpha = ((detail - 0.1f) / 0.6f).coerceIn(0f, 1f)
                            },
                        )
                    }
                }
            }
            if (inFocus) {
                // Focus: a tap on the lyrics opens the lyrics page.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(interactionSource = pagerClickSource, indication = null) {
                            onDetailPageChange(NowPlayingDetailPage.Lyrics)
                            onStageModeChange(NowPlayingStageMode.Expanded)
                        },
                )
            }
        }

        // ── Transport ── two rows at rest, one row + the progress row in
        // focus; it steps away while a page is open.
        if (!expandedSettled) {
            Box(
                modifier = Modifier
                    .offset(x = transportX, y = transportTop)
                    .width(transportWidth)
                    .height(transportHeight)
                    .graphicsLayer {
                        alpha = resolvedStageProgress.compact
                        translationY = 12.dp.toPx() * resolvedStageProgress.detail
                    },
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
                    playMode = state.playMode,
                    onSetPlayMode = onSetPlayMode,
                    controlSize = LandscapeControlSizeToken,
                    prevToTopRow = focus,
                    atEase = atEase,
                    modifier = Modifier
                        .fillMaxWidth()
                        // At ease, the first tap on the hidden buttons only
                        // brings them back.
                        .lyricToolsInPlaceWakeGuard(focusIdle)
                        .wrapContentHeight(align = Alignment.Top, unbounded = true),
                )
            }
        }

        // ── Title + artist, one row at the bottom (vertical space is the
        // scarce axis here). Stacked it sits under the cover; with a page
        // open the column narrows to the cover, so the artist drops under
        // the title in the room the transport left.
        val stackedTitleBlock = if (stacked) detailProgress else 0f
        if (stackedTitleBlock > HiddenLayerVisibilityThreshold) {
            // Up to two title lines, from under the horizontal rating to the
            // column's foot.
            val blockTop = maxOf(
                frameHeight - LandscapeStackedTitleBlock,
                geometry.restCover + LandscapeRatingGap + LandscapeRatingRow + LandscapeRatingGap,
            )
            Column(
                modifier = Modifier
                    .offset(x = 0.dp, y = blockTop)
                    .width(geometry.restCover)
                    .height((frameHeight - blockTop).coerceAtLeast(0.dp))
                    .graphicsLayer { alpha = stackedTitleBlock },
                verticalArrangement = Arrangement.Bottom,
            ) {
                Text(
                    text = state.songTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.artist,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (stackedTitleBlock < 1f - HiddenLayerVisibilityThreshold) {
            Row(
                modifier = Modifier
                    .offset(x = titleX, y = titleTop)
                    .width(titleWidth)
                    .height(LandscapeTitleRow)
                    .graphicsLayer { alpha = 1f - stackedTitleBlock },
                horizontalArrangement = Arrangement.spacedBy(HeroOneLineGap),
                verticalAlignment = Alignment.Bottom,
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
        }

        // ── Header + tabs (last: the header's end slot may drop over the page,
        // the Ask Gemini bar growing when focused). Both retreat in focus.
        if (!focusSettled) {
            CompactTextTabs(
                selected = NowPlayingDetailPage.entries[pagerState.targetPage],
                onSelect = { page ->
                    pagerScope.launch { pagerState.settleToPage(page.ordinal) }
                    // At rest a tab opens its page (the pager carries the page).
                    if (!expanded) onStageModeChange(NowPlayingStageMode.Expanded)
                },
                modifier = Modifier
                    .offset(x = columnX, y = LandscapeHeaderHeight)
                    .height(LandscapeTabRow)
                    .wrapContentHeight(align = Alignment.CenterVertically)
                    .focusRetreat { resolvedStageProgress.immersive },
            )
            Row(
                modifier = Modifier
                    .offset(x = columnX, y = 0.dp)
                    .width(columnWidth)
                    .height(LandscapeHeaderHeight)
                    // The controls clear a camera hole on the far edge too.
                    .focusRetreat { resolvedStageProgress.immersive },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    // Expanded: the explicit back steps the stage back, the
                    // same destination as system back (AGENTS.md).
                    onClick = { if (!expanded || !onStageBack()) onDismiss() },
                    modifier = Modifier.size(LandscapeHeaderHeight),
                ) {
                    Icon(
                        imageVector = YoinSymbols.ChevronDown,
                        contentDescription = if (expanded) {
                            stringResource(R.string.np_cd_landscape_back_now_playing)
                        } else {
                            stringResource(R.string.np_cd_landscape_close_now_playing)
                        },
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
                    fitLines = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                // 「右上角那格」(owner 2026-11-06): at rest the queue / devices /
                // write icons; with a page open, that page's own actions.
                val pageActionWidth = (columnWidth * LandscapePageActionShare)
                    .coerceIn(LandscapePageActionMin, LandscapePageActionMax)
                val noteBarHeight by animateDpAsState(
                    targetValue = noteBar.height(density),
                    animationSpec = YoinMotion.defaultSpatialSpec(role = YoinMotionRole.Expressive),
                    label = "landscapeNoteBarHeight",
                )
                Box(contentAlignment = Alignment.CenterEnd) {
                    if (!expandedSettled) {
                        TopBarActionIcons(
                            supportsYoinCast = state.serviceFeatures.supportsYoinCast,
                            onQueueClick = { showQueue = true },
                            onDevicesClick = { showDevicesSheet = true },
                            onWriteClick = {
                                noteBar.requestWriting()
                                onDetailPageChange(NowPlayingDetailPage.Note)
                                onStageModeChange(NowPlayingStageMode.Expanded)
                            },
                            castState = castState,
                            onCastClick = onCastClick,
                            playingOn = state.playingOn,
                            size = LandscapeHeaderHeight,
                            modifier = Modifier
                                .graphicsLayer { alpha = resolvedStageProgress.compact }
                                .retreatInput { resolvedStageProgress.detail },
                        )
                    }
                    if (expanded || detailProgress > HiddenLayerVisibilityThreshold) {
                        val actionsModifier = Modifier.graphicsLayer { alpha = resolvedStageProgress.detail }
                        when (detailPage) {
                            NowPlayingDetailPage.Lyrics -> LyricsTools(
                                state = state,
                                selection = lyricsSelection,
                                searchBarState = lyricsSearchBarState,
                                canRecenter = !lyricsAutoScroll && hasSyncedLyrics,
                                onSearchClick = onOpenLyricsSearch,
                                onTranslateClick = onTranslateLyrics,
                                onRecenterClick = {
                                    lyricsAutoScroll = true
                                    lyricsRecenterTick += 1
                                },
                                onEditLyricsClick = { showApplyDialog = true },
                                onMessage = onLyricsMessage,
                                iconSize = ShortTabToolSize,
                                modifier = actionsModifier
                                    // Idle: they fade in place; a blind tap
                                    // there only brings them back.
                                    .graphicsLayer { alpha = lyricToolsAlpha }
                                    .lyricToolsInPlaceWakeGuard(lyricToolsIdle),
                            )
                            NowPlayingDetailPage.About -> AskGeminiBar(
                                askState = askState,
                                onSubmit = onAskQuestion,
                                onFocus = onAskBarFocused,
                                onCollapseRequest = onAskBarCollapseRequested,
                                onDismissError = onDismissAskError,
                                asked = aboutUiState.askedQuestions(),
                                // Taller than the header: it hangs from the
                                // header's top and grows down over the page.
                                modifier = actionsModifier
                                    .width(pageActionWidth)
                                    .wrapContentHeight(align = Alignment.Top, unbounded = true)
                                    .offset(y = LandscapePageActionLift),
                            )
                            NowPlayingDetailPage.Note -> Box(
                                modifier = actionsModifier
                                    .width(pageActionWidth)
                                    .wrapContentHeight(align = Alignment.Top, unbounded = true)
                                    .offset(y = LandscapePageActionLift)
                                    .height(noteBarHeight),
                            ) {
                                // One line with 记下 at its end; up here the
                                // keyboard leaves it in view.
                                NoteWriteBar(
                                    state = noteBar,
                                    current = state.noteTarget(),
                                    positionMs = positionMs,
                                    onSave = onSaveNote,
                                    inline = true,
                                    focusGate = pagerState.settledPage == NowPlayingDetailPage.Note.ordinal &&
                                        detailProgress >= NoteWriteFocusStageProgress,
                                    modifier = Modifier.fillMaxSize(),
                                )
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
                upcoming = state.upcomingQueue,
                activityContext = state.activityContext,
                isPlaying = state.isPlaying,
                edit = state.queueEdit,
                shuffling = state.playMode.shuffle,
                onItemClick = { index ->
                    onSkipToQueueItem(index)
                    showQueue = false
                },
                editor = queueEditor,
                onDismiss = { showQueue = false },
            )
        }
    }
    if (showDevicesSheet) {
        ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
            DevicesSheet(
                providerId = devicesState.providerId,
                devices = devicesState.devices.localizedForSheet(),
                loading = devicesState.loading,
                busyDeviceId = devicesState.busyDeviceId,
                errorMessage = devicesState.errorMessage?.asString(),
                onRefresh = onRefreshDevices,
                onSelect = onSelectDevice,
                onDismiss = { showDevicesSheet = false },
            )
        }
    }
}

/** The heart (and the library add, where the service has one) beside a rating bar. */
@Composable
private fun LandscapeFavoriteButtons(
    state: NowPlayingUiState.Playing,
    onToggleFavorite: () -> Unit,
    onAddCurrentToPlaylist: () -> Unit,
    onAddCurrentToLibrary: () -> Unit,
) {
    if (state.serviceFeatures.supportsFavorites) {
        FavoriteButton(
            isStarred = state.isStarred,
            quietFlips = state.favoriteQuietFlips,
            actionLabel = stringResource(
                if (state.isStarred) {
                    state.serviceFeatures.removeLabel
                } else {
                    state.serviceFeatures.saveLabel
                },
            ),
            onClick = onToggleFavorite,
            onLongClick = onAddCurrentToPlaylist,
        )
    }
    if (Capability.LIBRARY_ADD in state.serviceFeatures.capabilities) {
        NowPlayingLibraryButton(
            membership = state.libraryMembership,
            isWorking = state.libraryActionInFlight,
            onClick = onAddCurrentToLibrary,
        )
    }
}

private val LandscapeHeaderHeight = 40.dp
private val LandscapeTabRow = 40.dp
private val LandscapeLyricsTop = LandscapeHeaderHeight + LandscapeTabRow + 6.dp
private val LandscapeRatingRow = 48.dp
private val LandscapeRatingRetreat = 24.dp

/** Wide: the gap between the transport and the title row, and over the transport. */
private val LandscapeTransportGap = 6.dp
private val LandscapeLyricsToTransport = 8.dp

/** Focus: the progress row under the one-row transport (shortFocusControlsHeight). */
private val LandscapePageBottomFade = 24.dp
private val LandscapeStackedTitleBlock = 96.dp
private val LandscapeExpandedLyricsToTitle = 16.dp
private val LandscapeFocusProgressRow = 36.dp
private const val LandscapeFocusLinesAbove = 2
private const val LandscapeFocusLyricScale = 1.35f

/** The header's end slot with a page open (Ask Gemini, the write bar). */
private const val LandscapePageActionShare = 0.56f
private val LandscapePageActionMin = 200.dp
private val LandscapePageActionMax = 320.dp
private val LandscapePageActionLift = (-8).dp

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
    queueEditor: QueueEditActions = QueueEditActions.None,
    onSetPlayMode: (PlayMode) -> Unit = {},
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
    onSaveNote: (NoteSaveRequest) -> Unit = {},
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
                    val tabletopCoverInteraction = remember { MutableInteractionSource() }
                    val tabletopCoverMenu = rememberCoverMenuState()
                    AlbumCover(
                        songId = state.songId,
                        coverArtUrl = state.coverArtUrl,
                        revealDirection = skipDirection,
                        // No shared element in Tabletop (same crash-avoidance reason
                        // as Wide — see WidePlayingContent).
                        sharedTransitionScope = null,
                        animatedVisibilityScope = null,
                        menu = tabletopCoverMenu,
                        // 16dp breathing room on all four sides.
                        modifier = Modifier
                            .padding(16.dp)
                            .size(coverSize)
                            .nowPlayingCoverGestures(
                                interactionSource = tabletopCoverInteraction,
                                menu = tabletopCoverMenu,
                                onClick = null,
                            )
                            .reportFocusCover(),
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = state.artist,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontSize = (16f - 4f * lyricsEmphasis).sp / density.fontScale,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false).then(
                                    tabletopArtistRoute?.let { route ->
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
                                ),
                            )
                            NowPlayingFormatTags(state)
                        }
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
                        contentDescription = stringResource(R.string.np_cd_tabletop_close),
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
                    NowPlayingLibraryButton(
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
                    playMode = state.playMode,
                    onSetPlayMode = onSetPlayMode,
                    controlSize = 72.dp,
                    lyricsExpanded = lyricsExpanded,
                    onExpandLyrics = { lyricsExpanded = !lyricsExpanded },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(20.dp))
                BottomPills(
                    supportsYoinCast = state.serviceFeatures.supportsYoinCast,
                playingOn = state.playingOn,
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
                upcoming = state.upcomingQueue,
                activityContext = state.activityContext,
                isPlaying = state.isPlaying,
                edit = state.queueEdit,
                shuffling = state.playMode.shuffle,
                onItemClick = { index ->
                    onSkipToQueueItem(index)
                    showQueue = false
                },
                editor = queueEditor,
                onDismiss = { showQueue = false },
            )
        }
    }

    if (showDevicesSheet) {
        ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
            DevicesSheet(
                providerId = devicesState.providerId,
                devices = devicesState.devices.localizedForSheet(),
                loading = devicesState.loading,
                busyDeviceId = devicesState.busyDeviceId,
                errorMessage = devicesState.errorMessage?.asString(),
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
                contentDescription = stringResource(R.string.np_cd_wide_close),
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
internal fun StageTopBar(
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
    // Writing a note with the keyboard up: the song's title and artist take
    // the "playing from" slot beside the docked cover (0 → 1, read at draw
    // time). Null = never.
    trackTitleFraction: (() -> Float)? = null,
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
                    stringResource(R.string.np_cd_stage_close_now_playing)
                } else {
                    stringResource(R.string.np_cd_stage_back_now_playing)
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
        Box(
            modifier = Modifier
                .weight(1f)
                .graphicsLayer {
                    translationX = 10.dp.toPx() * dockProgress
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            PlayingFromLabel(
                activityContext = state.activityContext,
                fallbackAlbumName = state.albumName,
                onAlbumClick = onAlbumClick,
                onArtistClick = onArtistClick,
                onPlaylistClick = onPlaylistClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = 1f - (trackTitleFraction?.invoke() ?: 0f) },
            )
            if (trackTitleFraction != null) {
                Column(
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = trackTitleFraction() }
                        // Over the faded label: its album / artist links must
                        // not answer taps nobody can see.
                        .pointerInput(Unit) { detectTapGestures { } },
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = state.songTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = state.artist,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
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
    // The size the bitmap is decoded at. It must not follow [startSize] when
    // that moves (lyrics opened from focus step back while the cover grows
    // back to its focus size): a new request size every frame restarts the
    // load and the flight shows its empty placeholder.
    requestSize: Dp = startSize,
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
    val requestSizePx = with(LocalDensity.current) { requestSize.roundToPx() }

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
                is ActivityContext.Album -> stringResource(R.string.np_dock_playing_from_album)
                is ActivityContext.Playlist -> stringResource(R.string.np_dock_playing_from_playlist)
                is ActivityContext.Artist -> stringResource(R.string.np_dock_playing_from_artist)
                is ActivityContext.LikedSongs,
                ActivityContext.None,
                -> stringResource(R.string.np_dock_now_playing)
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
internal fun StageTabs(
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
    val lyricsLabel = stringResource(R.string.np_text_tab_lyrics)
    val aboutLabel = stringResource(R.string.np_text_tab_about)
    val noteLabel = stringResource(R.string.np_text_tab_note)
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
                text = when (page) {
                    NowPlayingDetailPage.Lyrics -> lyricsLabel
                    NowPlayingDetailPage.About -> aboutLabel
                    NowPlayingDetailPage.Note -> noteLabel
                },
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
    // Focus with the list kept (owner 2026-11-03): the lines print through
    // dots and the outro plays here (last line held, next song's first line);
    // read in draw. 0 = the resting page.
    listFocus: () -> Float = { 0f },
    // The lyric list's type scale (focus: up to [FocusLyricScale]).
    lyricScale: Float = 1f,
    upNext: UpNextLyrics? = null,
    // The page's end padding up to the screen edge: the held last line may
    // run on into it (the lines still wrap at the page edge).
    endOverflow: Dp = 0.dp,
    // Extra inset on both sides (short-window focus: the cover's edges).
    pageInset: Dp = 0.dp,
    // The current line's anchor (LyricsDisplay.anchorFocus); follows [listFocus].
    listAnchorFocus: () -> Float = listFocus,
    // The list's bottom px under the transport and how much it shows
    // (LyricsDisplay.coveredPx / coveredShown).
    listCoveredPx: () -> Int = { 0 },
    listCoveredShown: () -> Float = { 0f },
    // Focus: sung lines kept above the current one (LyricsDisplay.focusLinesAbove).
    focusLinesAbove: Int = 1,
) {
    val insetModifier = modifier.padding(horizontal = pageInset)
    when (page) {
        NowPlayingDetailPage.Lyrics -> Box(
            // Clipped top and bottom only: the held last line may run on past
            // the page's end edge.
            modifier = insetModifier.drawWithContent {
                clipRect(
                    left = -endOverflow.toPx(),
                    top = 0f,
                    right = size.width + endOverflow.toPx(),
                    bottom = size.height,
                ) { this@drawWithContent.drawContent() }
            },
        ) {
            LyricsDisplay(
                lyrics = state.lyrics,
                positionMs = positionMs,
                loading = state.lyricsLoading,
                trackKey = state.songId,
                queueIndex = state.currentQueueIndex,
                focus = listFocus,
                anchorFocus = listAnchorFocus,
                coveredPx = listCoveredPx,
                coveredShown = listCoveredShown,
                focusLinesAbove = focusLinesAbove,
                fontScale = lyricScale,
                durationMs = state.durationMs,
                upNext = upNext,
                endOverflow = endOverflow,
                modifier = Modifier
                    .fillMaxSize()
                    .ignoreParentHorizontalPadding(start = { 0.dp }, end = { endOverflow })
                    .graphicsLayer { translationY = -8.dp.toPx() * immersiveProgress }
                    // Focus: the list breaks into dots as the one line takes over.
                    .dotDissolve { immersiveProgress },
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
            credits = state.lyricCredits,
            facts = state.spineFacts,
            songKey = "${state.songTitle}\u0000${state.artist}\u0000${state.albumName}",
            modifier = insetModifier,
        )
        NowPlayingDetailPage.Note -> NoteCompactPane(
            notes = notes,
            positionMs = positionMs,
            modifier = insetModifier,
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
    val activeLine by remember(state.lyrics, state.showLyricsTranslation) {
        derivedStateOf {
            state.lyrics.currentLyricLine(
                positionMs = currentPositionMs(),
                showTranslation = state.showLyricsTranslation,
            )
        }
    }
    val (lineIndex, lyricText) = activeLine
    val displayText = when {
        state.lyricsLoading -> stringResource(R.string.np_lyric_loading)
        lyricText.isNotBlank() -> lyricText
        else -> state.songTitle
    }
    // Same voice as the active line of the multi-line view (LyricsDisplay):
    // bodyLarge in the rounded cut at SemiBold, so shrinking to one line
    // keeps the line the user was reading, only alone.
    val style = MaterialTheme.typography.bodyLarge.copy(
        fontFamily = GoogleSansFlexRounded,
        fontWeight = FontWeight.SemiBold,
    )
    AnimatedContent(
        // Keyed by line, not just text: a repeated line ("oh, oh") still
        // reads as the lyric moving on. The song id makes a song change move
        // too when both songs sit on line 0.
        targetState = OneLineLyricFrame(state.songId, lineIndex, displayText, state.lyricsLoading),
        contentKey = { Triple(it.songId, it.lineIndex, it.text) },
        transitionSpec = { oneLineLyricLanding() using SizeTransform(clip = false) },
        contentAlignment = Alignment.TopStart,
        label = "oneLineLyric",
        modifier = modifier,
    ) { (_, _, text, loading) ->
        Text(
            text = text,
            style = style,
            color = if (loading) {
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

private data class OneLineLyricFrame(
    val songId: String,
    val lineIndex: Int,
    val text: String,
    val loading: Boolean,
)

/**
 * The next line lands: it grows from 94% at its left edge, the same landing
 * the active line does in the multi-line view, while the old line settles
 * back and fades. Movement rides the spatial spring, alpha the effects spring.
 */
internal fun oneLineLyricLanding(): ContentTransform {
    val leftEdge = TransformOrigin(0f, 0.5f)
    val enter = YoinMotion.scaleIn(
        role = YoinMotionRole.Expressive,
        initialScale = 0.94f,
        transformOrigin = leftEdge,
    ) + YoinMotion.slideInVertically(role = YoinMotionRole.Expressive) { it / 6 } +
        YoinMotion.fadeIn(role = YoinMotionRole.Standard)
    val exit = YoinMotion.scaleOut(
        role = YoinMotionRole.Standard,
        targetScale = 0.94f,
        transformOrigin = leftEdge,
    ) + YoinMotion.fadeOut(role = YoinMotionRole.Standard, speed = YoinMotionSpeed.Fast)
    return enter togetherWith exit
}

/**
 * The 16:9 lyric entry (断点交接 §3.1): the current line, rounded primary, one
 * line, and the whole row is the button that expands the lyrics page.
 *
 * When a line holds for [LyricIdleHintDelayMs] (intro, break, a long note)
 * the row cross-fades, at most once a day, to an animated unfold symbol,
 * shown only while the symbol moves, then fades back.
 */
@Composable
internal fun OneLineLyricRow(
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
    onDeleteNote: (String) -> Unit,
    noteDraft: NoteDraftState,
    modifier: Modifier = Modifier,
    // The write bar below the page is open: the note list holds still and
    // shows where the draft will land.
    noteWriting: Boolean = false,
    lyricsViewportGrowthPx: Int = 0,
    lyricsSelection: LyricsSelectionState? = null,
    // The page's end padding up to the screen edge (single column): the held
    // last line may run on into it.
    lyricsEndOverflow: Dp = 0.dp,
    // Two-column focus (0..1, draw phase): the lyrics print as halftone.
    lyricsFocus: () -> Float = { 0f },
    // The title card over the lyrics; off where the layout keeps the title in view.
    lyricsTitleCard: Boolean = true,
) {
    val haptics = rememberYoinHaptics()
    when (page) {
        NowPlayingDetailPage.Lyrics -> LyricsFullscreenPane(
            selecting = lyricsSelection?.active == true,
            selectedLines = lyricsSelection?.selected.orEmpty(),
            onToggleLine = { index ->
                haptics.performTick()
                lyricsSelection?.toggle(index)
            },
            viewportGrowthPx = lyricsViewportGrowthPx,
            trackKey = state.songId,
            queueIndex = state.currentQueueIndex,
            songTitle = state.songTitle.takeIf { lyricsTitleCard },
            artist = state.artist.takeIf { lyricsTitleCard },
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
            endOverflow = lyricsEndOverflow,
            focus = lyricsFocus,
            modifier = modifier,
        )
        NowPlayingDetailPage.About -> AboutFullscreenPane(
            aboutUiState = aboutUiState,
            onRetryCanonical = onRetryCanonical,
            credits = state.lyricCredits,
            facts = state.spineFacts,
            modifier = modifier,
        )
        NowPlayingDetailPage.Note -> {
            val deletion = LocalNoteDeletion.current
            NoteFullscreenPane(
                notes = notes,
                sortMode = noteSortMode,
                onSortModeChange = onNoteSortModeChange,
                positionMs = positionMs,
                onSeekToMs = onSeekToMs,
                draftState = noteDraft,
                current = state.noteTarget(),
                writing = noteWriting,
                onDelete = onDeleteNote,
                deleted = deletion?.pending?.takeIf { it.isForSong(state.songId) },
                onUndoDelete = { deletion?.undo() },
                onRealign = LocalNoteRealign.current,
                // The undo window waits for a finger on the list to lift.
                onListHeldChange = { held -> deletion?.onListHeldChange(held) },
                modifier = modifier,
            )
        }
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
    playMode: PlayMode = PlayMode.RepeatAll,
    onSetPlayMode: (PlayMode) -> Unit = {},
    controlSize: Dp = 56.dp,
    lyricsExpanded: Boolean = false,
    onExpandLyrics: (() -> Unit)? = null,
    noteAnchorsMs: List<Long> = emptyList(),
    modifier: Modifier = Modifier,
    live: Boolean = true,
    prevToTopRow: Float = 0f,
    atEase: Float = 0f,
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
        playMode = playMode,
        onSetPlayMode = onSetPlayMode,
        controlSize = controlSize,
        lyricsExpanded = lyricsExpanded,
        onExpandLyrics = onExpandLyrics,
        modifier = modifier,
        live = live,
        prevToTopRow = prevToTopRow,
        atEase = atEase,
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
internal fun CompactBottomHero(
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
    // [oneLine] windows whose focus sets the identity as everywhere else,
    // artist over title (owner 2026-11-05): one layout for the row and the
    // stack, so the title (and its marquee) carries on through the glide.
    stackable: Boolean = false,
    // [stackable] only, 0 = the row .. 1 = the stack.
    stacking: Float = 0f,
    // [stackable]: the stack's inset on each side (the cover's edges).
    stackInset: Dp = 0.dp,
    // [stackable]: how far the slot has grown at its top for the artist's
    // line; the title stays put under it.
    stackLift: Dp = 0.dp,
) {
    // Reopened before its close finished, Now Playing keeps this composition,
    // and the one-row hero's reused title/artist bounds then settle on the
    // pill's rect instead of morphing (the pill's small title is drawn there,
    // the hero title is gone until the transition ends). Each open gets fresh
    // shared-bounds nodes, as a first open does.
    val heroOpenCount = remember { intArrayOf(0) }
    val heroOpening = animatedVisibilityScope?.transition?.targetState == EnterExitState.Visible
    val heroOpenGeneration = remember(heroOpening) {
        if (heroOpening) ++heroOpenCount[0] else heroOpenCount[0]
    }
    StageHeightSlot(height = height, alpha = alpha, modifier = modifier) {
        key(heroOpenGeneration) {
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
                            resizeMode = NowPlayingHeroTextResize,
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
                            resizeMode = NowPlayingHeroTextResize,
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
            if (oneLine && stackable) {
                HeroRowToStack(
                    stacking = stacking.coerceIn(0f, 1f),
                    stackInset = stackInset,
                    stackLift = stackLift,
                    // The title's stretch and press scale, which the artist
                    // rides in the row (see the row below).
                    titleGrowth = {
                        (titleStretchScale * (titleRouteInteraction?.scaleX ?: 1f) - 1f).coerceAtLeast(0f)
                    },
                    title = { titleClip ->
                        Box(modifier = titleClip.then(titleModifier).then(titleClickModifier)) {
                            NowPlayingMarqueeTitle(
                                text = state.songTitle,
                                style = MaterialTheme.typography.displaySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                stretchScale = titleStretchScale,
                            )
                        }
                    },
                    artist = {
                        Box(modifier = Modifier.then(artistModifier).then(artistClickModifier)) {
                            artistText(Modifier)
                        }
                    },
                    tags = { NowPlayingFormatTags(state) },
                )
            } else if (oneLine) {
                // The title's stretch (playing) and press scale grow it past its
                // layout box from the start edge; the artist rides that growth
                // so the gap holds instead of the title running onto the artist
                // (owner 2026-10-29, Fold outer screen). It rides only into the
                // row's free end: a long (marquee) title fills the row, so there
                // the title's growth is clipped at its own slot instead and the
                // artist stays put, never pushed off the edge (owner 2026-10-30).
                var titleWidthPx by remember { mutableIntStateOf(0) }
                var artistWidthPx by remember { mutableIntStateOf(0) }
                var rowWidthPx by remember { mutableIntStateOf(0) }
                val gapPx = with(LocalDensity.current) { HeroOneLineGap.toPx() }
                val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
                fun titleGrowthPx(): Float {
                    val grown = titleStretchScale * (titleRouteInteraction?.scaleX ?: 1f) - 1f
                    return titleWidthPx * grown.coerceAtLeast(0f)
                }
                fun rowFreePx(): Float = (rowWidthPx - titleWidthPx - gapPx - artistWidthPx).coerceAtLeast(0f)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { rowWidthPx = it.width },
                    horizontalArrangement = Arrangement.spacedBy(HeroOneLineGap),
                ) {
                    // The artist is measured first (capped), the title takes
                    // the rest and marquees when it overflows.
                    Box(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .alignByBaseline()
                            .onSizeChanged { titleWidthPx = it.width }
                            // Only the end edge clips (glyph tops and the start stay whole).
                            .drawWithContent {
                                if (!ltr) {
                                    drawContent()
                                    return@drawWithContent
                                }
                                val end = size.width + minOf(titleGrowthPx(), rowFreePx())
                                clipRect(
                                    left = -size.width,
                                    top = -size.height,
                                    right = end,
                                    bottom = size.height * 2f,
                                ) {
                                    this@drawWithContent.drawContent()
                                }
                            }
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
                            .onSizeChanged { artistWidthPx = it.width }
                            .graphicsLayer {
                                if (ltr) translationX = minOf(titleGrowthPx(), rowFreePx())
                            }
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
                            .then(artistModifier),
                    ) {
                        // The format tags ride the artist line: no height of their own.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.weight(1f, fill = false).then(artistClickModifier)) {
                                artistText(Modifier)
                            }
                            NowPlayingFormatTags(state)
                        }
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
}

/**
 * The one-row hero ([stacking] 0: title, then the artist on its baseline)
 * gliding into the stacked one ([stacking] 1: the artist and its format
 * tags over the title, as on every other Now Playing). Both texts keep
 * their size; only their places and the title's width move, and the tags
 * fade in with the stack. The moves are staggered so the artist never
 * crosses the title: it lifts (and the title drops) first, then it slides
 * to the start; leaving, it slides back first and then settles.
 */
@Composable
private fun HeroRowToStack(
    stacking: Float,
    stackInset: Dp,
    stackLift: Dp,
    titleGrowth: () -> Float,
    title: @Composable (Modifier) -> Unit,
    artist: @Composable () -> Unit,
    tags: @Composable () -> Unit,
) {
    // From layout to draw: the artist's ride on the title's growth, and
    // whether the title overflows the row (it marquees there).
    val ridePx = remember { floatArrayOf(0f) }
    val titleScrolls = remember { booleanArrayOf(false) }
    val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val titleClip = Modifier
        .graphicsLayer {
            // A scrolling title takes its new width at the glide's middle,
            // which starts its marquee over: it fades through there, so the
            // restart never shows as a jump.
            if (titleScrolls[0]) alpha = abs(1f - 2f * stacking)
        }
        .drawWithContent {
            if (!ltr) {
                drawContent()
                return@drawWithContent
            }
            // The row clips the grown title at the artist; the stack lets it run.
            val end = size.width + ridePx[0] + size.width * stacking
            clipRect(left = -size.width, top = -size.height, right = end, bottom = size.height * 2f) {
                this@drawWithContent.drawContent()
            }
        }
    Layout(
        contents = listOf(
            { title(titleClip) },
            artist,
            { Box(Modifier.graphicsLayer { alpha = stacking }) { tags() } },
        ),
        modifier = Modifier.fillMaxSize(),
    ) { (titleM, artistM, tagsM), constraints ->
        // The box keeps the row's full width throughout; the stack insets
        // inside it, so the row's title never changes width (or restarts its
        // marquee) until it fades through the glide's middle.
        val width = constraints.maxWidth
        val inset = stackInset.roundToPx().coerceIn(0, width / 4)
        val stackWidth = width - inset * 2
        val gap = HeroOneLineGap.roundToPx()
        val loose = Constraints(maxWidth = stackWidth)
        val tagsP = tagsM.first().measure(loose)
        // The row caps the artist at its share and the title takes the rest;
        // the stack gives the artist the line beside its tags and the title
        // the whole width.
        // Vertical first, then across.
        val lift = (stacking / HeroStackSplit).coerceIn(0f, 1f)
        val across = ((stacking - (1f - HeroStackSplit)) / HeroStackSplit).coerceIn(0f, 1f)
        val artistRowMax = (width * HeroOneLineArtistShare).roundToInt()
        // The artist widens only as it slides to the start, never past the edge.
        val artistMax = lerpPx(artistRowMax, (stackWidth - tagsP.width).coerceAtLeast(0), across)
        val artistP = artistM.first().measure(Constraints(maxWidth = artistMax))
        // What the row would give it (no intrinsics: the shared bounds wrap it).
        val artistRowW = minOf(artistP.width, artistRowMax)
        val titleRowMax = (width - gap - artistRowW).coerceAtLeast(0)
        val titleP = titleM.first().measure(
            Constraints(maxWidth = if (stacking < 0.5f) titleRowMax else stackWidth),
        )
        titleScrolls[0] = titleP.width >= titleRowMax

        fun baseline(p: Placeable): Int =
            p[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: p.height
        // Row: the title at the top, the artist after it on its baseline,
        // riding the title's growth into the row's free end.
        val titleRowW = minOf(titleP.width, titleRowMax)
        val rowFree = (width - titleRowW - gap - artistRowW).coerceAtLeast(0)
        val ride = if (ltr) minOf(titleRowW * titleGrowth(), rowFree.toFloat()) else 0f
        ridePx[0] = ride
        val rowArtistX = titleRowW + gap + ride.roundToInt()
        val rowArtistY = baseline(titleP) - baseline(artistP)
        // Stack: the artist line (centred with its tags), 2dp, the title.
        val artistLine = maxOf(artistP.height, tagsP.height)
        val stackArtistY = (artistLine - artistP.height) / 2
        // The slot grows at its top by [stackLift] as the artist lifts: the
        // title holds its place on screen.
        val titleY = stackLift.roundToPx()
        val artistX = lerpPx(rowArtistX, inset, across)
        val artistY = lerpPx(titleY + rowArtistY, stackArtistY, lift)
        layout(width, constraints.maxHeight) {
            titleP.placeRelative(lerpPx(0, inset, lift), titleY)
            artistP.placeRelative(artistX, artistY)
            tagsP.placeRelative(
                artistX + artistP.width,
                artistY + (artistP.height - tagsP.height) / 2,
            )
        }
    }
}

private fun lerpPx(start: Int, end: Int, fraction: Float): Int =
    (start + (end - start) * fraction).roundToInt()

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
        contentDescription = stringResource(R.string.np_cd_plain_album_cover),
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

private fun lerpDp(start: Dp, end: Dp, fraction: Float): Dp =
    start + (end - start) * fraction.coerceIn(0f, 1f)

// The active line's index (0 before the first timestamp, -1 with no lyrics)
// and the text to show for it.
private fun List<LyricLine>.currentLyricLine(
    positionMs: Long,
    showTranslation: Boolean,
): Pair<Int, String> {
    if (isEmpty()) return -1 to ""
    val index = indexOfLast { line ->
        line.startMs?.let { positionMs >= it } == true
    }.coerceAtLeast(0)
    val active = this[index]
    val text = if (showTranslation && !active.translation.isNullOrBlank()) {
        active.translation.orEmpty()
    } else {
        active.text
    }
    return index to text
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
internal val OneLineLyricRowHeight = 44.dp

/** The Expanded stage's fixed geometry (the budget's third endpoint). */
private val ExpandedTopBarHeight = 56.dp

/** The top bar's touch height; in focus its slot shrinks below it and clips. */
private val TopBarTouchHeight = 48.dp
private val ExpandedCoverGap = 8.dp
private val ExpandedTabRowHeight = 52.dp
private val ExpandedTabGap = 12.dp
private val ExpandedAccessoryHeight = 68.dp

/** The wide layout's right-column action bar (lyric tools / Ask Gemini) at rest. */
private val WideAccessoryHeight = 68.dp
private val DockedCoverSlot = 48.dp

/** Caps a child at [fraction] of the width offered to it, keeping it no wider than its content. */
private fun Modifier.maxWidthFraction(fraction: Float): Modifier = layout { measurable, constraints ->
    val cap = if (constraints.hasBoundedWidth) (constraints.maxWidth * fraction).roundToInt() else constraints.maxWidth
    val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = cap))
    layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
}

/** The one-row hero: gap after the title; the artist takes at most this share. */
private val HeroOneLineGap = 12.dp

/** [HeroRowToStack]: each move's share of the glide (they overlap by 2x - 1). */
private const val HeroStackSplit = 0.6f
private const val HeroOneLineArtistShare = 0.4f

/** Enlarged phone: the tab row grows to hold the lyric tools at its end. */
private val EnlargedTabRowHeight = 44.dp
private val EnlargedTabToolSize = 44.dp
private val ShortTabToolSize = 40.dp

private val WideColumnGap = 24.dp
private val WideFramePadding = 24.dp

/** BottomPills' default pill height, as the two-pane top bar sets them. */

/** The standard rating column beside the two-pane cover (the side minus its gap). */
private val DualPaneRatingColumn = 56.dp
private val WideTopBarHeight = 48.dp
private const val HiddenLayerVisibilityThreshold = 0.01f

internal data class NowPlayingRouteInteraction(
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
    /**
     * One line while kind + name fit the width, else kind over name — the
     * landscape header, whose width the end slot's actions decide.
     */
    fitLines: Boolean = false,
) {
    val kindLabel: String?
    val nameLabel: String
    val clickAction: (() -> Unit)?
    when (activityContext) {
        is ActivityContext.Album -> {
            kindLabel = stringResource(R.string.np_from_album)
            nameLabel = activityContext.albumName
            clickAction = { onAlbumClick(activityContext.albumId) }
        }
        is ActivityContext.Playlist -> {
            kindLabel = stringResource(R.string.np_from_playlist)
            nameLabel = activityContext.playlistName
            clickAction = { onPlaylistClick(activityContext.playlistId) }
        }
        is ActivityContext.Artist -> {
            kindLabel = stringResource(R.string.np_from_artist)
            nameLabel = activityContext.artistName
            clickAction = { onArtistClick(activityContext.artistId) }
        }
        is ActivityContext.LikedSongs,
        ActivityContext.None,
        -> {
            kindLabel = null
            nameLabel = stringResource(R.string.np_from_now_playing)
            clickAction = null
        }
    }

    if (fitLines && kindLabel != null) {
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val kindStyle = MaterialTheme.typography.labelSmall
        val nameStyle = MaterialTheme.typography.labelMedium
        BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.CenterStart) {
            val oneLinePx = remember(kindLabel, nameLabel, kindStyle, nameStyle, density) {
                measurer.measure(kindLabel, kindStyle, maxLines = 1).size.width +
                    measurer.measure(nameLabel, nameStyle, maxLines = 1).size.width +
                    with(density) { (6.dp + 8.dp).roundToPx() }
            }
            PlayingFromLabel(
                activityContext = activityContext,
                fallbackAlbumName = fallbackAlbumName,
                onAlbumClick = onAlbumClick,
                onArtistClick = onArtistClick,
                onPlaylistClick = onPlaylistClick,
                singleLine = oneLinePx <= constraints.maxWidth,
            )
        }
        return
    }

    val routeInteraction = clickAction?.let { action ->
        rememberNowPlayingRouteInteraction(onNavigate = action)
    }
    val interactionModifier = Modifier
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
    val columnModifier = modifier.then(interactionModifier)

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

/** Short focus is offered only where folding the rest away grows the cover by at least this. */
private val ShortFocusMinGrowth = 24.dp

/** The spine shows only in a band at least this wide. */
private val SpineMinWidth = 24.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FavoriteButton(
    isStarred: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    actionLabel: String = stringResource(
        if (isStarred) R.string.np_cd_remove_favorite else R.string.np_cd_add_favorite,
    ),
    modifier: Modifier = Modifier,
    // NowPlayingUiState.Playing.favoriteQuietFlips: late answers from Spotify crossfade, no beat.
    quietFlips: Int = 0,
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
        // Action lights (ActionGlow.kt): the heart fills or empties with a light from itself.
        val glowSignal = LocalNowPlayingTransportSignal.current
        val glowBounds = remember { GlowBounds() }
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
                .trackGlowBounds(glowBounds)
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
                        glowSignal?.glow(
                            if (isStarred) ActionGlowKind.FavoriteOff else ActionGlowKind.FavoriteOn,
                            glowBounds.rootBounds,
                        )
                        onClick()
                    },
                    onLongClick = onLongClick?.let { longClick ->
                        {
                            haptics.performLongPress()
                            glowSignal?.glow(ActionGlowKind.GatherInto, glowBounds.rootBounds)
                            longClick()
                        }
                    },
                    role = Role.Button,
                ),
            contentAlignment = Alignment.Center,
        ) {
            FavoriteGlyphIcon(
                favorite = isStarred,
                quietFlips = quietFlips,
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
    // The long-press song sheet (see nowPlayingCoverGestures).
    menu: CoverMenuState? = null,
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

    val artwork: @Composable (Modifier) -> Unit = { artworkModifier ->
        ExpressiveMediaArtwork(
            model = coverArtUrl,
            reveal = com.gpo.yoin.ui.component.ArtworkReveal.DotDissolve,
            revealDirection = revealDirection,
            contentDescription = stringResource(R.string.np_cd_album_cover),
            modifier = artworkModifier,
            shape = YoinArtworkShapes.NowPlayingCover,
            fallbackIcon = YoinSymbols.PlayArrow,
            interactionSource = interactionSource,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = null,
        )
    }
    artwork(finalModifier)
    // The song sheet is its own window; it only needs to be composed here while open.
    if (menu != null) NowPlayingCoverMenu(songId = songId, menu = menu)
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
 * What is playing, as format tags after the artist ("FLAC 24/96", "Opus 192",
 * "5.1"), then "Spatial" while the system spatializes it; nothing when nothing
 * is known (Spotify, Apple Music). They fade in and out on the effects spring
 * as the decoder reports.
 */
@Composable
private fun NowPlayingFormatTags(state: NowPlayingUiState.Playing) {
    AnimatedVisibility(
        visible = state.formatTags.isNotEmpty(),
        enter = YoinMotion.fadeIn(role = YoinMotionRole.Standard),
        exit = YoinMotion.fadeOut(role = YoinMotionRole.Standard),
    ) {
        AudioFormatTagRow(
            tags = state.formatTags,
            transcodedFrom = state.transcodedFrom,
            accentTag = if (state.spatialPlayback == SpatialPlayback.Spatialized) {
                stringResource(R.string.np_format_spatial)
            } else {
                null
            },
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/**
 * Measures the content [runOnPx] taller than its slot but reports the slot:
 * the extra runs on under the rows that follow (drawn over it).
 */
private fun Modifier.runOnBelow(runOnPx: Int): Modifier = if (runOnPx <= 0) {
    this
} else {
    layout { measurable, constraints ->
        val placeable = measurable.measure(
            if (constraints.hasBoundedHeight) {
                constraints.copy(
                    minHeight = constraints.minHeight + runOnPx,
                    maxHeight = constraints.maxHeight + runOnPx,
                )
            } else {
                constraints
            },
        )
        val height = (placeable.height - runOnPx).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(placeable.width, height) { placeable.place(0, 0) }
    }
}

/**
 * Fades the [runOnBelow] extra out (its last [share]), on the content's own
 * alpha. Sits inside an offscreen layer (edgeFade's).
 */
private fun Modifier.fadeRunOn(runOnPx: Int, share: Float = RunOnFadeShare): Modifier = if (runOnPx <= 0) {
    this
} else {
    drawWithContent {
        drawContent()
        val fade = runOnPx * share
        val top = size.height - fade
        drawRect(
            brush = Brush.verticalGradient(
                colors = RunOnFadeStops,
                startY = top,
                endY = size.height,
            ),
            topLeft = Offset(0f, top),
            size = Size(size.width, fade),
            blendMode = BlendMode.DstIn,
        )
    }
}

// Short enough that the dotted lines still read behind the buttons.
private const val RunOnFadeShare = 0.45f

// At ease the lines are the content there: only the last stretch fades.
private const val RunOnFadeShareAtEase = 0.3f

/** Focus at ease: this long without a touch, the transport steps away. */
private const val FocusIdleMs = 4_000L

// Smoothstep from opaque to clear: no visible onset line.
private val RunOnFadeStops = listOf(1f, 0.896f, 0.648f, 0.352f, 0.104f, 0f).map { Color.Black.copy(alpha = it) }

/** New lyrics landing this long after an apply still get its reveal. */
private const val LyricsRevealWaitMs = 15_000L
