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
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.ExpressiveMediaArtwork
import com.gpo.yoin.ui.component.rememberExpressiveBackdropColors
import com.gpo.yoin.ui.experience.DeckIndicatorTransitionState
import com.gpo.yoin.ui.experience.RevealState
import com.gpo.yoin.ui.memories.MemoryEntry
import com.gpo.yoin.ui.memories.MemoryScoreKind
import com.gpo.yoin.ui.memories.award.GrooveAwardTarget
import com.gpo.yoin.ui.memories.award.GrooveDiaryEmblemTarget
import com.gpo.yoin.ui.memories.award.MemoriesAwardEffects
import com.gpo.yoin.ui.memories.award.MemoriesAwardInputs
import com.gpo.yoin.ui.memories.award.MemoriesAwardLifecycle
import com.gpo.yoin.ui.memories.award.pInCardState
import com.gpo.yoin.ui.memories.award.pInDiaryState
import com.gpo.yoin.ui.memories.award.pagerNearCard
import com.gpo.yoin.ui.memories.award.revealInForAward
import com.gpo.yoin.ui.memories.copy.MemoryDates
import com.gpo.yoin.ui.memories.emblem.GrooveEmblem
import com.gpo.yoin.ui.memories.emblem.GrooveKind
import com.gpo.yoin.ui.memories.emblem.GrooveModel
import com.gpo.yoin.ui.memories.emblem.GrooveSurface
import com.gpo.yoin.ui.memories.emblem.GrooveTiltState
import com.gpo.yoin.ui.memories.emblem.rememberGrooveAmbientReduced
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/*
 * Memories showcase v4: a HorizontalPager deck of memories under one shared top bar, each page two states —
 * the card ([MemoryCardFace]) and, beneath it, the diary ([MemoryDiary]) — joined by the shared-element
 * morph ([MemoriesMorph], driven by p = MemoriesDiaryState). ShellOverlayUp; the page's back (both levels),
 * its retreat to Home (q, RevealState) and its gesture router live on the Memories root (MemoriesScreen /
 * MemoriesGestures). Here: the cards, the diaries, the bar, the deck-level diary controller (frozen collapse,
 * the pull past the top, the router's probe), the award lifecycle's inputs and the tilt.
 *
 * p is one value for the deck: a page swipe with the diary open lands on the next card's diary, read from
 * its top (each page keeps its own scroll, reset when it stops being the settled page).
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
 *
 * [router] gets the deck's diary probe (at-end, the pull's finger-down) for the diary zone, and the spread's
 * page split; [awardBlocked] holds the award while a system-back preview drives q. [diaryHost] is the
 * diary's playback highlight, writing and NeoDB (the ViewModel in the app, a fixture in the harness).
 * [bottomInset]: chrome the host keeps over the page's bottom (the shell bar while the detail column is
 * open); the content stays clear of it.
 *
 * The tier comes from this composable's own container (BoxWithConstraints, [memoriesLayoutFor]): the two
 * states on a phone and (enlarged) on a Medium, the spread ([MemorySpreadPage]) on an Expanded container.
 *
 * [reducedMotion] is the user's setting and drives the choreography (morph, award); [ambientReduced] also
 * folds in adaptive pressure and only quiets the tilt and the ripple (MemoriesMotionPolicy).
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
    ambientReduced: Boolean = rememberGrooveAmbientReduced(reducedMotion),
    fixtures: MemoriesShowcaseFixtures? = null,
    diaryHost: MemoriesDiaryHost = NoDiaryHost,
    router: MemoriesGestureRouter? = null,
    awardBlocked: () -> Boolean = { false },
    bottomInset: Dp = 0.dp,
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
    val deck = rememberMemoriesDiaryDeck(diary, scope)
    val spreadDeck = remember { MemoriesSpreadDeck() }
    val diaryHaptics = rememberDiaryHaptics()
    SideEffect {
        deck.reduced = reducedMotion
        deck.morphDistancePx = morphPx
        deck.currentPage = { pagerState.currentPage }
        deck.onThresholdCrossed = diaryHaptics::clockTick
        spreadDeck.currentPage = { pagerState.currentPage }
    }
    // Frozen collapse: once the sunk text is invisible (p < .05) the scroll returns to its top, unseen; the
    // frozen timing holds until p reaches 0 or 1, so the emblem's flight never jumps when the scroll resets.
    LaunchedEffect(deck) {
        snapshotFlow { deck.p }.collect { p ->
            val current = deck.current()
            if (p < MemoriesMorphTokens.FrozenResetBelow && current != null && current.scroll.value > 0) {
                deck.frozenHold = deck.frozen
                current.scroll.scrollTo(0)
            }
            if (p <= 0f || p >= 1f) deck.frozenHold = false
        }
    }
    // The bar cover's alpha chases its hand-over target on the fast effects spring (prototype barFollow).
    val chaseSpec = YoinMotion.fastEffectsSpec<Float>(role = YoinMotionRole.Expressive)
    LaunchedEffect(deck, reducedMotion, chaseSpec) {
        if (reducedMotion) return@LaunchedEffect
        snapshotFlow { barCoverTarget(deck.fp) }
            .collectLatest { target -> deck.barChase.animateTo(target, chaseSpec) }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            // a phone in landscape keeps its cutout band and a side navigation bar clear
            .windowInsetsPadding(
                WindowInsets.displayCutout.union(WindowInsets.navigationBars).only(WindowInsetsSides.Horizontal),
            )
            .onPlaced { router?.onShowcasePlaced(it) },
    ) {
        val layout = remember(maxWidth, maxHeight) { memoriesLayoutFor(maxWidth, maxHeight) }
        val type = remember(layout) { MemoriesTypeScale.of(layout) }
        val spread = layout.isSpread
        val spreadNow by rememberUpdatedState(spread)
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val navBottom = max(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(), bottomInset)
        val leftPagePx = with(LocalDensity.current) { layout.leftPage.toPx() }
        SideEffect { router?.spreadLeftPagePx = if (spread) leftPagePx else null }
        // the router's "diary at its end": the open diary's on the two states, the right page's in a spread
        DisposableEffect(router, deck, spreadDeck, spread) {
            val probe: MemoriesDiaryProbe = if (spread) spreadDeck else deck
            router?.diaryProbe = probe
            onDispose { if (router?.diaryProbe === probe) router.diaryProbe = null }
        }

        // One sensor for the deck, read only by the current card's emblem, and only at rest in the card state
        // (a spread has no diary state: its exhibit is always the card's).
        val tiltActive by remember(reveal, diary, pagerState) {
            derivedStateOf {
                reveal.fraction <= RestEpsilon && (spreadNow || pInCardState(diary.fraction)) &&
                    !pagerState.isScrollInProgress
            }
        }
        val tilt = rememberGrooveTilt(active = tiltActive, reducedMotion = ambientReduced)

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
                    cardState = spreadNow || (pInCardState(diary.fraction) && !diary.isSettling),
                    diaryState = !spreadNow && pInDiaryState(diary.fraction, diary.isSettling),
                    blocked = awardBlocked(),
                )
            },
            settledKey = {
                if (pagerState.isScrollInProgress) null else currentMemories.getOrNull(pagerState.currentPage)?.stableId
            },
        )

        CompositionLocalProvider(LocalMemoriesType provides type) {
            val metrics = rememberCardMetrics(memories, layout, maxWidth, maxHeight, statusTop, navBottom)
            val spreadFit = if (spread) {
                rememberSpreadDeckFit(memories, layout, maxHeight, statusTop, navBottom)
            } else {
                null
            }
            DeckAurora(palettes = palettes, pagerState = pagerState, visible = auroraVisible)
            HorizontalPager(
                state = pagerState,
                key = { page -> memories[page].stableId },
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (pagerConnection != null) Modifier.nestedScroll(pagerConnection) else Modifier),
            ) { page ->
                val memory = memories[page]
                val lastHeard = memory.lastHeardAt?.let { heard ->
                    MemoryDates.lastHeard(MemoryDates.localDate(heard, zone), day)
                }
                if (spreadFit != null) {
                    val tones = remember(palettes[page], dark) { palettes[page].tones(dark) }
                    val isCurrent by remember(pagerState, page) { derivedStateOf { pagerState.settledPage == page } }
                    MemorySpreadPage(
                        memory = memory,
                        page = page,
                        tones = tones,
                        layout = layout,
                        deckFit = spreadFit,
                        statusTop = statusTop,
                        navBottom = navBottom,
                        viewHeight = maxHeight - statusTop - layout.barHeight,
                        dotCount = memories.size,
                        pagerState = pagerState,
                        spreadDeck = spreadDeck,
                        lastHeard = lastHeard,
                        today = day,
                        zone = zone,
                        host = diaryHost,
                        haptics = diaryHaptics,
                        cover = { m -> MemoryCoverArt(memory, fixtures, m) },
                        emblem = { m ->
                            CardEmblem(
                                memory = memory,
                                palette = palettes[page],
                                size = spreadFit.fit.seal,
                                awards = awards,
                                tilt = tilt,
                                isCurrent = isCurrent,
                                reducedMotion = reducedMotion,
                                ambientReduced = ambientReduced,
                                captionAlpha = { 1f },
                                modifier = m,
                            )
                        },
                        onOpenAlbum = { onOpenAlbum(memory) },
                    )
                } else {
                    ShowcasePage(
                        memory = memory,
                        page = page,
                        palette = palettes[page],
                        dark = dark,
                        layout = layout,
                        metrics = metrics,
                        statusTop = statusTop,
                        navBottom = navBottom,
                        dotCount = memories.size,
                        pagerState = pagerState,
                        diary = diary,
                        deck = deck,
                        awards = awards,
                        tilt = tilt,
                        reducedMotion = reducedMotion,
                        ambientReduced = ambientReduced,
                        lastHeard = lastHeard,
                        today = day,
                        zone = zone,
                        host = diaryHost,
                        haptics = diaryHaptics,
                        fixtures = fixtures,
                        onOpenDiary = {
                            // the award stops (and counts); a card still due it skips its nod: the 48 lands uncut
                            awards.onDiaryOpening(memory.stableId)
                            diary.launchAnimateTo(scope, 1f)
                            onOpenDiary(memory)
                        },
                        onOpenAlbum = { onOpenAlbum(memory) },
                        onCloseDiary = { diary.launchAnimateTo(scope, 0f) },
                    )
                }
            }
            MemoriesTopBar(
                dotColors = palettes.map { it.tones(dark).dot },
                dotLabels = memories.mapIndexed { i, memory -> "Memory ${i + 1} of ${memories.size}: ${memory.title}" },
                position = { pagerState.currentPage + pagerState.currentPageOffsetFraction },
                // the pill makes room for the bar cover: it follows the current page's cover flight (a spread
                // has no diary state, so the pill keeps its label there)
                diaryProgress = { if (spreadNow) 0f else deck.fp },
                onHome = onHome,
                onDot = { i -> scope.launch { pagerState.animateScrollToPage(i) } },
                edgeHint = edgeHint,
                insets = layout.bar,
                currentDot = pagerState.settledPage,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .onPlaced(onBarPlaced),
            )
        }
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

/** The album cover, bare: the fixture's drawn cover in the harness, the network art in the app. */
@Composable
internal fun MemoryCoverArt(memory: MemoryEntry, fixtures: MemoriesShowcaseFixtures?, modifier: Modifier) {
    if (fixtures != null) {
        fixtures.cover(memory, modifier)
    } else {
        ExpressiveMediaArtwork(
            model = memory.coverArtUrl,
            contentDescription = null,
            modifier = modifier,
            shape = YoinArtworkShapes.Hero,
            fallbackIcon = YoinSymbols.Album,
            tonalElevation = MemoryCardTokens.FlatElevation,
            shadowElevation = MemoryCardTokens.FlatElevation,
        )
    }
}

@Composable
private fun ShowcasePage(
    memory: MemoryEntry,
    page: Int,
    palette: MemoryPalette,
    dark: Boolean,
    layout: MemoriesLayout,
    metrics: MemoryCardMetrics,
    statusTop: Dp,
    navBottom: Dp,
    dotCount: Int,
    pagerState: PagerState,
    diary: MemoriesDiaryState,
    deck: MemoriesDiaryDeck,
    awards: MemoriesAwardLifecycle,
    tilt: GrooveTiltState,
    reducedMotion: Boolean,
    ambientReduced: Boolean,
    lastHeard: String?,
    today: LocalDate,
    zone: ZoneId,
    host: MemoriesDiaryHost,
    haptics: DiaryHaptics,
    fixtures: MemoriesShowcaseFixtures?,
    onOpenDiary: () -> Unit,
    onOpenAlbum: () -> Unit,
    onCloseDiary: () -> Unit,
) {
    val tones = remember(palette, dark) { palette.tones(dark) }
    val type = LocalMemoriesType.current
    val isCurrent by remember(pagerState, page) { derivedStateOf { pagerState.settledPage == page } }
    // each flips once per crossing, never per frame
    val diaryOpen by remember(diary) { derivedStateOf { diary.fraction >= 0.5f } }
    val diaryAtRest by remember(pagerState, diary, page) {
        derivedStateOf { pagerState.settledPage == page && !pagerState.isScrollInProgress && diary.fraction >= 1f }
    }
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    val morph = rememberMemoryPageMorph(page, deck, scroll, density)
    val titleKind = memory.cardTitleKind()
    val barTitleStyle = barTitleStyle()
    val measurer = rememberTextMeasurer()
    val barTitleWidth = remember(memory.title, barTitleStyle, measurer) {
        measurer.measure(memory.title, barTitleStyle, softWrap = false, maxLines = 1).size.width.toFloat()
    }
    SideEffect {
        with(density) {
            morph.relative = { pagerState.currentPage - page + pagerState.currentPageOffsetFraction }
            morph.titleKind = titleKind
            morph.cardTitleFontPx = cardTitleSize(titleKind, type).toPx()
            morph.diaryTitleFontPx = diaryTitleSize(titleKind, type).toPx()
            morph.statusTopPx = statusTop.toPx()
            morph.barTitleWidthPx = barTitleWidth
            morph.barTitleFontPx = barTitleStyle.fontSize.toPx()
            morph.barInsets = layout.bar
        }
    }
    // a page that is no longer the settled one goes back to the top of its diary (the next visit reads from it)
    LaunchedEffect(pagerState, page, scroll) {
        snapshotFlow { pagerState.settledPage }.collect { settled ->
            if (settled != page && scroll.value != 0) scroll.scrollTo(0)
        }
    }
    val cover: @Composable (Modifier) -> Unit = { m -> MemoryCoverArt(memory, fixtures, m) }
    val wide = layout.tier == MemoriesTier.Medium
    Box(
        Modifier
            .fillMaxSize()
            .onPlaced(morph::onPage)
            // TalkBack reads the settled page only (a neighbour mid-swipe is inert); its pane title names the
            // state, so opening or closing the diary is announced
            .then(
                if (isCurrent) {
                    Modifier.semantics {
                        paneTitle = if (diaryOpen) "Diary, ${memory.title}" else "Memory, ${memory.title}"
                    }
                } else {
                    Modifier.clearAndSetSemantics { }
                },
            ),
    ) {
        // the diary lies under the card; the card's pieces fly over it into the bar
        MemoryDiary(
            memory = memory,
            tones = tones,
            morph = morph,
            deck = deck,
            scroll = scroll,
            today = today,
            zone = zone,
            interactive = diaryOpen && isCurrent,
            settled = diaryAtRest,
            host = host,
            haptics = haptics,
            navBottom = navBottom,
            emblem = { m ->
                DiaryEmblem(
                    memory = memory,
                    palette = palette,
                    awards = awards,
                    reducedMotion = reducedMotion,
                    modifier = m,
                )
            },
            onOpenAlbum = onOpenAlbum,
            column = if (wide) layout.diaryColumn else Dp.Unspecified,
            modifier = Modifier.padding(top = statusTop + MemoriesTopBarTokens.Height),
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
                    ambientReduced = ambientReduced,
                    captionAlpha = { morph.sealCaptionAlpha() },
                    modifier = m,
                )
            },
            morph = morph,
            interactive = !diaryOpen,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .then(if (wide) Modifier.widthIn(max = MemoryCardTokens.Column) else Modifier)
                .padding(
                    top = statusTop + MemoriesTopBarTokens.Height,
                    bottom = max(metrics.bottomPadding, navBottom),
                ),
        )
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
            morph = morph,
            reducedMotion = reducedMotion,
            settled = { pagerState.settledPage == page && !pagerState.isScrollInProgress },
            insets = layout.bar,
            modifier = Modifier.padding(top = statusTop),
        )
    }
}

/** The card's pull-down offset: p below 0 is the rubber band's morph travel (prototype `over`). */
internal fun cardRubberBandPx(p: Float, morphDistancePx: Float): Float = -minOf(0f, p) * morphDistancePx

@Composable
internal fun CardEmblem(
    memory: MemoryEntry,
    palette: MemoryPalette,
    size: Dp,
    awards: MemoriesAwardLifecycle,
    tilt: GrooveTiltState,
    isCurrent: Boolean,
    reducedMotion: Boolean,
    ambientReduced: Boolean,
    captionAlpha: () -> Float,
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
        // the ripple is ambient: adaptive pressure may quiet it, the award above never
        reducedMotion = ambientReduced,
        captionAlpha = captionAlpha,
    )
}

/**
 * The diary title's emblem: the groove drawn natively at 48dp (score only), not the 96 scaled down. Its tilt
 * colour is frozen at rest (no motion in the reader's peripheral vision); it waits uncut for its card's award
 * and only nods in the diary ([MemoriesAwardLifecycle.registerDiary]). An exhibit, not a control.
 */
@Composable
private fun DiaryEmblem(
    memory: MemoryEntry,
    palette: MemoryPalette,
    awards: MemoriesAwardLifecycle,
    reducedMotion: Boolean,
    modifier: Modifier,
) {
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
        size = DiaryEmblemSize,
        surface = GrooveSurface.Bar,
        reducedMotion = reducedMotion,
        tag = "${memory.title} · diary",
    )
    DisposableEffect(awards, memory.stableId, award) {
        val target = GrooveDiaryEmblemTarget(award)
        awards.registerDiary(memory.stableId, target)
        onDispose { awards.unregisterDiary(memory.stableId, target) }
    }
    GrooveEmblem(
        model = model,
        size = DiaryEmblemSize,
        surface = GrooveSurface.Bar,
        modifier = modifier,
        award = award,
        ambientMotion = false,
        reducedMotion = reducedMotion,
    )
}

/** No playback, no writing, no NeoDB (previews). */
private object NoDiaryHost : MemoriesDiaryHost

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
