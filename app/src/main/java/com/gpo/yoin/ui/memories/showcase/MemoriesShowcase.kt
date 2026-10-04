package com.gpo.yoin.ui.memories.showcase

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.max
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.ExpressiveMediaArtwork
import com.gpo.yoin.ui.component.rememberExpressiveBackdropColors
import com.gpo.yoin.ui.experience.DeckIndicatorTransitionState
import com.gpo.yoin.ui.experience.RevealState
import com.gpo.yoin.ui.memories.MemoryEntry
import com.gpo.yoin.ui.memories.MemoryScoreKind
import com.gpo.yoin.ui.memories.award.GrooveAwardTarget
import com.gpo.yoin.ui.memories.award.MemoriesAwardEffects
import com.gpo.yoin.ui.memories.award.MemoriesAwardInputs
import com.gpo.yoin.ui.memories.award.MemoriesAwardLifecycle
import com.gpo.yoin.ui.memories.award.pInCardState
import com.gpo.yoin.ui.memories.award.pagerNearCard
import com.gpo.yoin.ui.memories.award.revealInForAward
import com.gpo.yoin.ui.memories.copy.MemoryDates
import com.gpo.yoin.ui.memories.emblem.GrooveEmblem
import com.gpo.yoin.ui.memories.emblem.GrooveKind
import com.gpo.yoin.ui.memories.emblem.GrooveModel
import com.gpo.yoin.ui.memories.emblem.GrooveSurface
import com.gpo.yoin.ui.memories.emblem.GrooveTiltState
import com.gpo.yoin.ui.memories.emblem.rememberGrooveAwardState
import com.gpo.yoin.ui.memories.emblem.rememberGrooveReducedMotion
import com.gpo.yoin.ui.memories.emblem.rememberGrooveTilt
import com.gpo.yoin.ui.memories.memoriesAuroraBackground
import com.gpo.yoin.ui.navigation.back.BackMotionTokens
import com.gpo.yoin.ui.theme.YoinArtworkShapes
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.launch

/*
 * Memories showcase v4, the card state (P5a): a HorizontalPager deck of memory cards under one shared top
 * bar. ShellOverlayUp; the page's back, its retreat to Home (q, RevealState) and its gesture router live on
 * the Memories root (MemoriesScreen / MemoriesGestures). Here: the cards, the bar, the award lifecycle's
 * inputs and the tilt. The diary layer and the card ⇄ diary morph arrive in P5b; p (MemoriesDiaryState) is
 * already read where the morph will read it (pill width, bar slots, the card's rubber band).
 */

/** Debug / preview stand-ins: fixed palettes and drawn covers instead of extraction and network art. */
@Immutable
class MemoriesShowcaseFixtures(
    /** Palette by [MemoryEntry.stableId]. */
    val palettes: Map<String, MemoryPalette>,
    val cover: @Composable (memory: MemoryEntry, modifier: Modifier) -> Unit,
)

/**
 * The deck. [pagerState] belongs to the caller (deck advance hangs its edge pull on [pagerConnection]).
 * [onBarPlaced] reports the bar so the gesture router can split the bar zone from the card body.
 * [today] fixes the date grammar (the harness pins the prototype's day); null = today in the system zone.
 */
@Composable
internal fun MemoriesShowcase(
    memories: List<MemoryEntry>,
    pagerState: PagerState,
    reveal: RevealState,
    diary: MemoriesDiaryState,
    awards: MemoriesAwardLifecycle,
    onHome: () -> Unit,
    onOpenAlbum: (MemoryEntry) -> Unit,
    modifier: Modifier = Modifier,
    onOpenDiary: (MemoryEntry) -> Unit = {},
    onBarPlaced: (LayoutCoordinates) -> Unit = {},
    pagerConnection: NestedScrollConnection? = null,
    edgeHint: DeckIndicatorTransitionState? = null,
    auroraVisible: Boolean = true,
    today: LocalDate? = null,
    reducedMotion: Boolean = rememberGrooveReducedMotion(),
    fixtures: MemoriesShowcaseFixtures? = null,
) {
    if (memories.isEmpty()) return
    val zone = remember { ZoneId.systemDefault() }
    val day = today ?: remember(zone) { LocalDate.now(zone) }
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val scope = rememberCoroutineScope()
    val palettes = memories.map { memory ->
        key(memory.stableId) { rememberMemoryPalette(memory, fixtures?.palettes?.get(memory.stableId)) }
    }
    val morphPx = with(LocalDensity.current) { BackMotionTokens.MemoriesDiaryMorphDistance.toPx() }

    // One sensor for the deck, read only by the current card's emblem, and only at rest in the card state.
    val tiltActive by remember(reveal, diary, pagerState) {
        derivedStateOf {
            reveal.fraction <= RestEpsilon && pInCardState(diary.fraction) && !pagerState.isScrollInProgress
        }
    }
    val tilt = rememberGrooveTilt(active = tiltActive, reducedMotion = reducedMotion)

    val currentMemories by rememberUpdatedState(memories)
    MemoriesAwardEffects(
        lifecycle = awards,
        inputs = {
            val down = awards.fingerDown
            // with the finger off the glass the pager is heading to its target (a fling, a dot tap)
            val card = if (down) pagerState.currentPage else pagerState.targetPage
            MemoriesAwardInputs(
                cardKey = currentMemories.getOrNull(card)?.stableId,
                fingerDown = down,
                nearCard = pagerNearCard(pagerState.currentPage + pagerState.currentPageOffsetFraction, card),
                revealIn = revealInForAward(reveal.fraction),
                cardState = pInCardState(diary.fraction),
            )
        },
        settledKey = {
            if (pagerState.isScrollInProgress) null else currentMemories.getOrNull(pagerState.currentPage)?.stableId
        },
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)),
    ) {
        val metrics = memoryCardMetrics(maxWidth, maxHeight)
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        DeckAurora(palettes = palettes, pagerState = pagerState, visible = auroraVisible)
        HorizontalPager(
            state = pagerState,
            key = { page -> memories[page].stableId },
            modifier = Modifier
                .fillMaxSize()
                .then(if (pagerConnection != null) Modifier.nestedScroll(pagerConnection) else Modifier),
        ) { page ->
            val memory = memories[page]
            ShowcasePage(
                memory = memory,
                page = page,
                palette = palettes[page],
                dark = dark,
                metrics = metrics,
                statusTop = statusTop,
                navBottom = navBottom,
                dotCount = memories.size,
                pagerState = pagerState,
                diary = diary,
                awards = awards,
                tilt = tilt,
                reducedMotion = reducedMotion,
                lastHeard = memory.lastHeardAt?.let { heard ->
                    MemoryDates.lastHeard(MemoryDates.localDate(heard, zone), day)
                },
                morphPx = morphPx,
                fixtures = fixtures,
                onOpenDiary = { onOpenDiary(memory) },
                onOpenAlbum = { onOpenAlbum(memory) },
                onCloseDiary = { diary.launchAnimateTo(scope, 0f) },
            )
        }
        MemoriesTopBar(
            dotColors = palettes.map { it.tones(dark).dot },
            dotLabels = memories.mapIndexed { i, memory -> "Memory ${i + 1}: ${memory.title}" },
            position = { pagerState.currentPage + pagerState.currentPageOffsetFraction },
            diaryProgress = { diary.fraction },
            onHome = onHome,
            onDot = { i -> scope.launch { pagerState.animateScrollToPage(i) } },
            edgeHint = edgeHint,
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.statusBars)
                .onPlaced(onBarPlaced),
        )
    }
}

/** The ambient wash in the current card's palette; a page change re-tints it on the effects spring. */
@Composable
private fun DeckAurora(palettes: List<MemoryPalette>, pagerState: PagerState, visible: Boolean) {
    val current = palettes[pagerState.currentPage.coerceIn(0, palettes.lastIndex)]
    val spec = YoinMotion.defaultEffectsSpec<Color>(role = YoinMotionRole.Standard)
    val base by animateColorAsState(current.base, spec, label = "memoriesAuroraBase")
    val accent by animateColorAsState(current.accent, spec, label = "memoriesAuroraAccent")
    Box(Modifier.fillMaxSize().memoriesAuroraBackground(baseColor = base, accentColor = accent, visible = visible))
}

@Composable
private fun ShowcasePage(
    memory: MemoryEntry,
    page: Int,
    palette: MemoryPalette,
    dark: Boolean,
    metrics: MemoryCardMetrics,
    statusTop: Dp,
    navBottom: Dp,
    dotCount: Int,
    pagerState: PagerState,
    diary: MemoriesDiaryState,
    awards: MemoriesAwardLifecycle,
    tilt: GrooveTiltState,
    reducedMotion: Boolean,
    lastHeard: String?,
    morphPx: Float,
    fixtures: MemoriesShowcaseFixtures?,
    onOpenDiary: () -> Unit,
    onOpenAlbum: () -> Unit,
    onCloseDiary: () -> Unit,
) {
    val tones = remember(palette, dark) { palette.tones(dark) }
    val isCurrent by remember(pagerState, page) { derivedStateOf { pagerState.settledPage == page } }
    val cover: @Composable (Modifier) -> Unit = { m ->
        if (fixtures != null) {
            fixtures.cover(memory, m)
        } else {
            ExpressiveMediaArtwork(
                model = memory.coverArtUrl,
                contentDescription = null,
                modifier = m,
                shape = YoinArtworkShapes.Hero,
                fallbackIcon = YoinSymbols.Album,
                tonalElevation = MemoryCardTokens.FlatElevation,
                shadowElevation = MemoryCardTokens.FlatElevation,
            )
        }
    }
    Box(Modifier.fillMaxSize()) {
        MemoryPageBarSlots(
            album = memory.title,
            artistLine = memory.supportingText,
            artistShort = memory.supportingText.artistOnly(),
            lastHeard = lastHeard,
            dotCount = dotCount,
            relative = { pagerState.currentPage - page + pagerState.currentPageOffsetFraction },
            diaryProgress = { diary.fraction },
            onCloseDiary = onCloseDiary,
            cover = cover,
            modifier = Modifier.padding(top = statusTop),
        )
        MemoryCardFace(
            memory = memory,
            tones = tones,
            metrics = metrics,
            onOpenDiary = onOpenDiary,
            onOpenAlbum = onOpenAlbum,
            cover = cover,
            emblem = { m ->
                CardEmblem(
                    memory = memory,
                    palette = palette,
                    size = metrics.seal,
                    awards = awards,
                    tilt = tilt,
                    isCurrent = isCurrent,
                    reducedMotion = reducedMotion,
                    modifier = m,
                )
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .then(if (metrics.wide) Modifier.widthIn(max = MemoryCardTokens.Column) else Modifier)
                .padding(
                    top = statusTop + MemoriesTopBarTokens.Height,
                    bottom = max(metrics.bottomPadding, navBottom),
                )
                // the card's own pull-down: p below 0 moves the card face, nothing else
                .graphicsLayer { translationY = cardRubberBandPx(diary.fraction, morphPx) },
        )
    }
}

/** The card's pull-down offset: p below 0 is the rubber band's morph travel (prototype `over`). */
internal fun cardRubberBandPx(p: Float, morphDistancePx: Float): Float = -minOf(0f, p) * morphDistancePx

@Composable
private fun CardEmblem(
    memory: MemoryEntry,
    palette: MemoryPalette,
    size: Dp,
    awards: MemoriesAwardLifecycle,
    tilt: GrooveTiltState,
    isCurrent: Boolean,
    reducedMotion: Boolean,
    modifier: Modifier,
) {
    // The award keys on everything but the palette (which springs in once the cover resolves): a palette
    // hand-off must never restart a running award.
    val base = remember(
        memory.scoreKind,
        memory.scoreText,
        memory.tracks,
        memory.ratedTrackCount,
        memory.totalTrackCount,
    ) {
        memory.grooveModel(MemoryPaletteSamples.M1)
    }
    val model = remember(base, palette) { base.copy(palette = palette) }
    val award = rememberGrooveAwardState(
        model = base,
        size = size,
        surface = GrooveSurface.Cover,
        reducedMotion = reducedMotion,
        tag = "${memory.title} · card",
    )
    DisposableEffect(awards, memory.stableId, award) {
        val target = GrooveAwardTarget(award)
        awards.register(memory.stableId, target)
        onDispose { awards.unregister(memory.stableId, target) }
    }
    val current by rememberUpdatedState(isCurrent)
    GrooveEmblem(
        model = model,
        size = size,
        surface = GrooveSurface.Cover,
        modifier = modifier,
        tilt = { if (current) tilt.offset else Offset.Zero },
        award = award,
        ambientMotion = isCurrent,
        reducedMotion = reducedMotion,
    )
}

/**
 * The emblem's model: the label's kind and score (the card's own one-decimal text, so the emblem and the
 * card can never disagree), and which tracks are rated, in album order.
 */
internal fun MemoryEntry.grooveModel(palette: MemoryPalette): GrooveModel {
    val kind = when (scoreKind) {
        MemoryScoreKind.ALBUM_RATING -> GrooveKind.Album
        MemoryScoreKind.AVERAGE_TRACK_RATING -> GrooveKind.Average
        MemoryScoreKind.NONE -> GrooveKind.Unrated
    }
    val score = scoreText.toDoubleOrNull()?.takeIf { kind != GrooveKind.Unrated }
    val rated = if (tracks.isNotEmpty()) {
        tracks.map { track -> track.rating != null }
    } else {
        List(totalTrackCount.coerceAtLeast(0)) { index -> index < ratedTrackCount }
    }
    return GrooveModel(
        kind = if (score == null) GrooveKind.Unrated else kind,
        score = score,
        trackRated = rated,
        palette = palette,
    )
}

/** "Artist · 2019" → "Artist" (the bar's artist line drops the year first). */
internal fun String.artistOnly(): String {
    val cut = lastIndexOf(" · ")
    if (cut < 0) return this
    val tail = substring(cut + 3)
    return if (tail.isNotEmpty() && tail.all(Char::isDigit)) substring(0, cut) else this
}

/** The album's palette: the fixture's, or one built from the cover's extracted backdrop colours. */
@Composable
private fun rememberMemoryPalette(memory: MemoryEntry, fixture: MemoryPalette?): MemoryPalette {
    if (fixture != null) return fixture
    val colors = rememberExpressiveBackdropColors(
        model = memory.coverArtUrl,
        fallbackBaseColor = MaterialTheme.colorScheme.primary,
        fallbackAccentColor = MaterialTheme.colorScheme.tertiary,
    )
    return remember(colors.baseColor, colors.accentColor) {
        MemoryPalette.fromBackdrop(colors.baseColor, colors.accentColor)
    }
}

private const val RestEpsilon = 0.001f
