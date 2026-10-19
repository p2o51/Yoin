package com.gpo.yoin.ui.tips

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.BarEditLeftSlot
import com.gpo.yoin.ui.component.BarEditPose
import com.gpo.yoin.ui.component.ExpressiveMediaArtwork
import com.gpo.yoin.ui.component.ExpressivePageBackground
import com.gpo.yoin.ui.component.YoinButtonGroup
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.home.HomeGreetingTitle
import com.gpo.yoin.ui.home.HomeHeaderTitleBreathing
import com.gpo.yoin.ui.home.HomeSection
import com.gpo.yoin.ui.home.HomeWidgetGridSection
import com.gpo.yoin.ui.home.MemoryBubbleController
import com.gpo.yoin.ui.home.MemoryBubbleOverlay
import com.gpo.yoin.ui.home.edit.HomeEditCardScope
import com.gpo.yoin.ui.home.edit.HomeEditClock
import com.gpo.yoin.ui.home.edit.HomeEditFeedback
import com.gpo.yoin.ui.home.edit.HomeEditMotion
import com.gpo.yoin.ui.home.edit.HomeEditSafeArea
import com.gpo.yoin.ui.home.edit.HomeWiggleStyle
import com.gpo.yoin.ui.home.edit.LocalHomeEditCardScope
import com.gpo.yoin.ui.home.edit.homeEditHeaderIcon
import com.gpo.yoin.ui.home.edit.rememberHomeEditSpecs
import com.gpo.yoin.ui.home.memoryBubbleFreeSpan
import com.gpo.yoin.ui.home.memoryBubblePage
import com.gpo.yoin.ui.home.memoryBubbleTitle
import com.gpo.yoin.ui.home.titleRes
import com.gpo.yoin.ui.memories.MemoryEntityType
import com.gpo.yoin.ui.memories.MemoryEntry
import com.gpo.yoin.ui.memories.copy.MemoryExcerptAttribution
import com.gpo.yoin.ui.memories.copy.MemoryExcerptCandidate
import com.gpo.yoin.ui.memories.copy.MemoryExcerptKind
import com.gpo.yoin.ui.memories.MemoryScoreKind
import com.gpo.yoin.ui.memories.award.MemoriesAwardLifecycle
import com.gpo.yoin.ui.memories.emblem.rememberGrooveTilt
import com.gpo.yoin.ui.memories.memoriesAuroraBackground
import com.gpo.yoin.ui.memories.showcase.CardEmblem
import com.gpo.yoin.ui.memories.showcase.MemoriesTopBar
import com.gpo.yoin.ui.memories.showcase.MemoriesTopBarTokens
import com.gpo.yoin.ui.memories.showcase.MemoryCardFace
import com.gpo.yoin.ui.memories.showcase.MemoryPaletteSamples
import com.gpo.yoin.ui.memories.showcase.memoryCardMetrics
import com.gpo.yoin.ui.navigation.YoinSection
import com.gpo.yoin.ui.theme.YoinArtworkShapes
import com.gpo.yoin.ui.theme.YoinMotion
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Home on the demo phone: the real header (greeting, "Home" → "Edit Home",
 * the Memories arrow), the real Jump Back In grid with its real edit-mode
 * wiggle, and the real bar — idle Home | Library, or [Undo] [Done] in edit.
 */

/** Long-press Home → the cards wiggle and the bar offers Done; Done ends it. */
@Composable
internal fun EditHomeDemo(playing: Boolean) {
    var editing by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    val spatial = YoinMotion.spatialSpring<Float>()
    val reduced = LocalMotionProfile.current == MotionProfile.AdaptiveReduced
    val scope = rememberCoroutineScope()
    val specs = rememberHomeEditSpecs(reduced)
    val editingNow by androidx.compose.runtime.rememberUpdatedState(editing)
    val reducedNow by androidx.compose.runtime.rememberUpdatedState(reduced)
    val motion = remember(specs) {
        HomeEditMotion(
            scope = scope,
            specs = specs,
            style = HomeWiggleStyle(),
            reduced = { reducedNow },
            progress = { progress.value },
            editing = { editingNow },
            feedback = HomeEditFeedback.None,
        )
    }
    val cardScope = remember(motion) {
        HomeEditCardScope(
            section = HomeSection.JumpBackIn,
            motion = motion,
            interactive = { !editingNow },
            enterEdit = { editing = true },
            blockTopInBox = { DemoBlockTopPx },
            blockCoordinates = { null },
            safeArea = { HomeEditSafeArea(top = 0f, bottom = DemoSafeBottomPx) },
            liftGain = { 1f },
        )
    }
    LaunchedEffect(motion) {
        motion.displayed = listOf(HomeSection.JumpBackIn)
        motion.runEntryEffects()
    }
    LaunchedEffect(editing) {
        motion.setEnvelope()
        if (editing) motion.touch()
        progress.animateTo(if (editing) 1f else 0f, spatial)
    }
    HomeEditClock(motion)
    DemoPhone(playing = playing, script = {
        editing = false
        delay(300)
        moveTo("jbi", fx = 0.17f, fy = 0.3f)
        hold { editing = true }
        lift()
        delay(2_200)
        moveTo("bar", fx = 0.75f)
        tap { editing = false }
        delay(600)
    }) {
        DemoHomePage(
            editProgress = { progress.value },
            editing = editing,
            onEnterEdit = { editing = true },
            onDone = { editing = false },
            cardScope = cardScope,
        )
    }
}

/** Pull Home down → Memories comes down over it; its Home pill sends it back up. */
@Composable
internal fun PullDemo(playing: Boolean) {
    // 0 = Home, 1 = Memories open: the one value the pull, the release and the pill drive.
    val reveal = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val spatial = YoinMotion.spatialSpring<Float>()
    val heightPx = with(LocalDensity.current) { DemoScreenHeight.toPx() }
    val open: () -> Unit = { scope.launch { reveal.animateTo(1f, spatial) } }
    val close: () -> Unit = { scope.launch { reveal.animateTo(0f, spatial) } }
    DemoPhone(playing = playing, script = {
        reveal.snapTo(0f)
        moveTo("header", fy = 1.2f)
        drag(at("header", fy = 1.2f).x, 0.62f, ms = 750) { p -> scope.launch { reveal.snapTo(p * PullTravel) } }
        reveal.animateTo(1f, spatial)
        lift()
        delay(1_800)
        moveTo("memoriesHome", fx = 0.16f)
        tap { close() }
        delay(900)
    }) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta ->
                        scope.launch { reveal.snapTo((reveal.value + delta / heightPx).coerceIn(0f, 1f)) }
                    },
                    onDragStopped = { if (reveal.value > OpenAt) open() else close() },
                ),
        ) {
            DemoHomePage(
                editProgress = { 0f },
                editing = false,
                onEnterEdit = {},
                onDone = {},
                cardScope = null,
                hint = { (reveal.value / OpenAt).coerceIn(0f, 1f) },
            )
            DemoMemoriesPage(
                onHome = close,
                modifier = Modifier.graphicsLayer {
                    translationY = -size.height * (1f - reveal.value)
                },
            )
        }
    }
}

@Composable
private fun BoxScope.DemoHomePage(
    editProgress: () -> Float,
    editing: Boolean,
    onEnterEdit: () -> Unit,
    onDone: () -> Unit,
    cardScope: HomeEditCardScope?,
    hint: () -> Float = { 0f },
) {
    val cards = demoWidgetCards()
    val bubble = remember { MemoryBubbleController() }
    ExpressivePageBackground(modifier = Modifier.matchParentSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // HomeContentHeader's row, with the Settings gear in the corner.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .memoryBubblePage(bubble)
                    .statusBarsPadding()
                    .padding(top = 12.dp)
                    .heightIn(min = 48.dp)
                    .demoTarget("header"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HomeGreetingTitle(
                    editProgress = editProgress,
                    onEnterEdit = onEnterEdit,
                    modifier = Modifier
                        .memoryBubbleTitle(bubble)
                        .padding(end = HomeHeaderTitleBreathing),
                )
                Box(modifier = Modifier.weight(1f).memoryBubbleFreeSpan(bubble))
                Spacer(modifier = Modifier.width(2.dp))
                IconButton(onClick = {}, modifier = Modifier.homeEditHeaderIcon(editProgress)) {
                    Icon(
                        imageVector = YoinSymbols.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            CompositionLocalProvider(LocalHomeEditCardScope provides cardScope) {
                HomeWidgetGridSection(
                    title = stringResource(HomeSection.JumpBackIn.titleRes),
                    cards = cards,
                    extractBackdropColors = true,
                    onCardClick = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .demoTarget("jbi")
                        .demoLongPress { if (cardScope != null) onEnterEdit() },
                )
            }
        }
        MemoryBubbleOverlay(
            pill = null,
            controller = bubble,
            hintProgress = hint,
            revealProgress = { 1f },
            scrolledPx = { 0f },
            // Quiet: the arrow only, no speech.
            covered = true,
            extractBackdropColors = false,
            onOpenMemoryFocus = {},
            onNavigateToMemories = {},
            modifier = Modifier.matchParentSize(),
            editProgress = editProgress,
            editing = editing,
        )
        YoinButtonGroup(
            selectedSection = YoinSection.HOME,
            currentTrackId = null,
            currentTrackTitle = null,
            currentTrackArtist = null,
            currentTrackCoverArtUrl = null,
            isPlaybackReady = false,
            connectionErrorMessage = null,
            onHomeClick = {},
            onNowPlayingClick = {},
            onLibraryClick = {},
            editPose = remember(editProgress, onDone) {
                BarEditPose(
                    progress = editProgress,
                    leftSlot = { BarEditLeftSlot.UndoDisabled },
                    onLeftSlotClick = {},
                    onDone = onDone,
                )
            },
            editing = editing,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .demoTarget("bar"),
        )
    }
}

/** The Memories deck's first card on its ambient wash, under the deck's top bar. */
@Composable
private fun DemoMemoriesPage(onHome: () -> Unit, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val palette = MemoryPaletteSamples.M1
    val album = DemoAlbums[3]
    val cover = demoCoverUrl(album)
    val memory = remember(cover) {
        MemoryEntry(
            stableId = "tip-memory",
            sourceActivityId = 0L,
            entityType = MemoryEntityType.ALBUM,
            entityId = "tip-album",
            entityProvider = MediaId.PROVIDER_SUBSONIC,
            title = album.name,
            supportingText = album.artist,
            supportingYear = "2024",
            metaText = null,
            coverArtUrl = cover,
            timestamp = 0L,
            scoreText = "8.6",
            scoreKind = MemoryScoreKind.ALBUM_RATING,
            scoreSupportingText = null,
            footerText = null,
            memoryTitle = "Night walks in the rain",
            excerptCandidates = listOf(
                MemoryExcerptCandidate(
                    "The second side is the one I keep coming back to.",
                    MemoryExcerptAttribution(MemoryExcerptKind.REVIEW, "Oct 12"),
                ),
            ),
            playbackSongs = emptyList(),
            tracks = emptyList(),
        )
    }
    val metrics = remember { memoryCardMetrics(ScreenWidth, DemoScreenHeight) }
    val awards = remember { MemoriesAwardLifecycle() }
    val tilt = rememberGrooveTilt(active = false, reducedMotion = true)
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .memoriesAuroraBackground(baseColor = palette.base, accentColor = palette.accent, visible = true),
    ) {
        MemoryCardFace(
            memory = memory,
            tones = palette.tones(dark = dark),
            metrics = metrics,
            onOpenDiary = {},
            onOpenAlbum = {},
            cover = { m ->
                ExpressiveMediaArtwork(
                    model = cover,
                    contentDescription = null,
                    modifier = m,
                    shape = YoinArtworkShapes.Hero,
                    fallbackIcon = YoinSymbols.Album,
                    tonalElevation = 0.dp,
                )
            },
            emblem = { m ->
                CardEmblem(
                    memory = memory,
                    palette = palette,
                    size = metrics.seal,
                    awards = awards,
                    tilt = tilt,
                    isCurrent = false,
                    reducedMotion = true,
                    captionAlpha = { 1f },
                    modifier = m,
                )
            },
            interactive = false,
            marqueeRunning = false,
            modifier = Modifier.padding(top = statusTop + MemoriesTopBarTokens.Height),
        )
        MemoriesTopBar(
            dotColors = listOf(palette.accent, MemoryPaletteSamples.M3.accent, MemoryPaletteSamples.M5.accent),
            dotLabels = listOf(album.name, DemoAlbums[0].name, DemoAlbums[2].name),
            position = { 0f },
            diaryProgress = { 0f },
            onHome = onHome,
            onDot = {},
            currentDot = 0,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .demoTarget("memoriesHome"),
        )
    }
}

/** How far the demo's pull takes Memories before it lets go (the rest is the settle). */
private const val PullTravel = 0.45f

/** A user's pull past this opens Memories; short of it, Home springs back. */
private const val OpenAt = 0.25f

/** The wiggle's edge band reads the block as well inside the screen. */
private const val DemoBlockTopPx = 400f
private const val DemoSafeBottomPx = 100_000f

private val DemoScreenHeight = ScreenWidth * (300f / 168f)
