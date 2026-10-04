package com.gpo.yoin.ui.memories.showcase

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import com.gpo.yoin.ui.component.SeamTop
import com.gpo.yoin.ui.component.seamDissolveViewport
import com.gpo.yoin.ui.component.verticalEdgeFadeOnScroll
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.memories.MemoryEntityType
import com.gpo.yoin.ui.memories.MemoryEntry
import com.gpo.yoin.ui.memories.copy.MemoryProseLanguage
import com.gpo.yoin.ui.memories.copy.MemoryTitleKind
import com.gpo.yoin.ui.theme.GoogleSansFlex
import com.gpo.yoin.ui.theme.YoinArtworkShapes
import com.gpo.yoin.ui.theme.YoinSerifTitle
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * The spread (twostate4 `.ts-L-spread`, tablet-yoin-left): an Expanded container lays each memory out as two
 * pages over one ground, no divider and no morph.
 *
 *  · The LEFT page is the exhibit with everything Yoin wrote, in reading order: the cover with its groove
 *    emblem, the title, Yoin's paragraph and question, the album line, Go to album (unsigned). Every card's
 *    cover starts at the same y and the whole deck shares one cover (the height ladder, [fitSpreadDeck]).
 *  · The RIGHT page is the diary from your own entry on (the review or today's blank page), then the liner
 *    and the ending (the two numerals; Go to album lives on the left). A page that fits sits centred on the
 *    left page's optical line ((cover top + Go to album's bottom) / 2); a longer one starts level with the
 *    cover and scrolls. ≥ 64dp between the left content and the right text.
 *
 * Gestures (the one router): the left page is card body — a swipe up goes Home (112dp / 600dp/s); the right
 * page only scrolls the diary, and a NEW upward drag that starts at its end goes Home on the same rule
 * (pull past the end; a fling that reaches the end only stops); the bar pushed up goes Home (56dp). Back has
 * one level (Home): there is no card ⇄ diary state here. p keeps its value (a diary left open in portrait is
 * still open after a rotation back), it is just not read.
 *
 * Phone landscape (the deviation, MemoriesLayout): Yoin's paragraph opens the right page instead.
 */

/** The spread's per-page right scrolls; the router's probe for "the diary rests at its end". */
@Stable
internal class MemoriesSpreadDeck : MemoriesDiaryProbe {
    /** The pager's current page (a snapshot read). */
    var currentPage: () -> Int = { 0 }

    private val scrolls = mutableStateMapOf<Int, ScrollState>()

    fun register(page: Int, scroll: ScrollState) {
        scrolls[page] = scroll
    }

    fun unregister(page: Int, scroll: ScrollState) {
        if (scrolls[page] === scroll) scrolls.remove(page)
    }

    /** The current page's right scroll (the harness's long start scrolls it). */
    fun current(): ScrollState? = scrolls[currentPage()]

    override fun onFingerDown() = Unit

    /** A right page that doesn't scroll is always at its end: a swipe up there goes Home too. */
    override fun atEnd(): Boolean {
        val s = current() ?: return false
        return !s.isScrollInProgress && s.value >= s.maxValue - 1
    }
}

// ---------------------------------------------------------------- the deck's fit (measured text budget)

/** The ladder's result plus each card's Go to album bottom (dp from the left page's top), by stable id. */
@Immutable
internal class SpreadDeckFit(val fit: SpreadFit, private val goBottoms: Map<String, Float>) {
    fun goBottom(memory: MemoryEntry): Dp = (goBottoms[memory.stableId] ?: fit.coverTop.value).dp
}

/**
 * Measures every card's citation with TextMeasurer at each spacing step (adaptive principle 6) and walks
 * the height ladder once for the whole deck. [height] is the showcase container's; the left page starts
 * under the status bar and the 64dp bar.
 */
@Composable
internal fun rememberSpreadDeckFit(
    memories: List<MemoryEntry>,
    layout: MemoriesLayout,
    height: Dp,
    statusTop: Dp,
    navBottom: Dp,
): SpreadDeckFit {
    val measurer = rememberTextMeasurer(cacheSize = 96)
    val density = LocalDensity.current
    val titleStyles = SpreadTightness.entries.associateWith { t ->
        MemoryTitleKind.entries.associateWith { kind -> spreadTitleStyle(kind, t) }
    }
    val paragraphStyles = SpreadTightness.entries.associateWith { t -> spreadParagraphStyle(t) }
    val albumStyles = SpreadTightness.entries.associateWith { t -> spreadAlbumStyle(t) }
    val artistStyle = spreadArtistStyle()
    val question = yoinQuestionSpan(MaterialTheme.colorScheme.onSurface)
    val styles = Triple(titleStyles, paragraphStyles, albumStyles)
    return remember(memories, layout, height, statusTop, navBottom, density, styles) {
        with(density) {
            val content = (layout.leftPage - SpreadPagePadding * 2).toPx()
            val citeWidth = min(content, SpreadCiteMax.toPx()).roundToInt().coerceAtLeast(1)
            val albumWidth = min(content, SpreadAlbumMax.toPx()).roundToInt().coerceAtLeast(1)
            fun h(text: AnnotatedString, style: TextStyle, width: Int, maxLines: Int = Int.MAX_VALUE): Float =
                measurer.measure(
                    text = text,
                    style = style,
                    maxLines = maxLines,
                    overflow = TextOverflow.Clip,
                    constraints = Constraints(maxWidth = width),
                ).size.height.toDp().value
            val cards = memories.map { memory ->
                val kind = memory.cardTitleKind()
                val title = AnnotatedString(memory.memoryTitle?.takeIf(String::isNotBlank) ?: memory.title)
                val paragraph = YoinParagraph.of(memory)
                    ?.takeIf { !layout.landscape }
                    ?.let { yoinParagraphString(it, question) }
                val onlyArtist = kind == MemoryTitleKind.ALBUM
                val byStep = SpreadTightness.entries.associateWith { t ->
                    val artist = h(AnnotatedString(memory.supportingText), artistStyle, albumWidth)
                    SpreadCiteHeights(
                        // an album-name title takes two lines at most (the rest runs as a marquee)
                        title = h(
                            title,
                            titleStyles.getValue(t).getValue(kind),
                            citeWidth,
                            maxLines = if (kind == MemoryTitleKind.ALBUM) 2 else Int.MAX_VALUE,
                        ),
                        paragraph = paragraph?.let { h(it, paragraphStyles.getValue(t), citeWidth) },
                        album = if (onlyArtist) {
                            artist
                        } else {
                            h(AnnotatedString(memory.title), albumStyles.getValue(t), albumWidth, maxLines = 2) +
                                AlbumArtistGap.value + artist
                        },
                        albumOnlyArtist = onlyArtist,
                    )
                }
                memory.stableId to byStep
            }
            val room = (height - statusTop - layout.barHeight).value
            val citations = cards.map { (_, byStep) -> { t: SpreadTightness -> byStep.getValue(t) } }
            val fit = fitSpreadDeck(layout, room, navBottom.value, citations)
            val goBottoms = cards.associate { (id, byStep) ->
                id to fit.coverTop.value +
                    spreadExtent(fit.cover.value, fit.seal.value, fit.tightness, byStep.getValue(fit.tightness))
            }
            SpreadDeckFit(fit, goBottoms)
        }
    }
}

// ---------------------------------------------------------------- one page

/**
 * One memory as a spread. [viewHeight]: the right page's viewport (under the bar). [isCurrent]: the settled
 * page (only it takes input and speaks to TalkBack).
 */
@Composable
internal fun MemorySpreadPage(
    memory: MemoryEntry,
    page: Int,
    tones: MemoryPaletteTones,
    layout: MemoriesLayout,
    deckFit: SpreadDeckFit,
    statusTop: Dp,
    navBottom: Dp,
    viewHeight: Dp,
    dotCount: Int,
    pagerState: PagerState,
    spreadDeck: MemoriesSpreadDeck,
    lastHeard: String?,
    today: LocalDate,
    zone: ZoneId,
    host: MemoriesDiaryHost,
    haptics: DiaryHaptics,
    cover: @Composable (Modifier) -> Unit,
    emblem: @Composable (Modifier) -> Unit,
    onOpenAlbum: () -> Unit,
) {
    val isCurrent by remember(pagerState, page) { derivedStateOf { pagerState.settledPage == page } }
    val atRest by remember(pagerState, page) {
        derivedStateOf { pagerState.settledPage == page && !pagerState.isScrollInProgress }
    }
    val scroll = rememberScrollState()
    DisposableEffect(spreadDeck, page, scroll) {
        spreadDeck.register(page, scroll)
        onDispose { spreadDeck.unregister(page, scroll) }
    }
    // a page that is no longer the settled one goes back to the top of its diary
    LaunchedEffect(pagerState, page, scroll) {
        snapshotFlow { pagerState.settledPage }.collect { settled ->
            if (settled != page && scroll.value != 0) scroll.scrollTo(0)
        }
    }
    // Yoin's paragraph is held while this page is the one being read: a review saved on the right page
    // drops it from the refreshed card, but the left page doesn't reflow under the reader
    val paragraph = rememberHeldParagraph(memory) { pagerState.settledPage != page }
    val top = statusTop + layout.barHeight
    Box(
        Modifier
            .fillMaxSize()
            .then(
                if (isCurrent) {
                    Modifier.semantics { paneTitle = "Memory, ${memory.title}" }
                } else {
                    Modifier.clearAndSetSemantics { }
                },
            ),
    ) {
        SpreadExhibit(
            memory = memory,
            tones = tones,
            fit = deckFit.fit,
            paragraph = paragraph.takeIf { !layout.landscape },
            cover = cover,
            emblem = emblem,
            interactive = isCurrent,
            marqueeRunning = atRest,
            onOpenAlbum = onOpenAlbum,
            modifier = Modifier
                .width(layout.leftPage)
                .fillMaxHeight()
                .padding(top = top),
        )
        SpreadDiary(
            memory = memory,
            tones = tones,
            layout = layout,
            coverTop = deckFit.fit.coverTop,
            goBottom = deckFit.goBottom(memory),
            viewHeight = viewHeight,
            scroll = scroll,
            interactive = isCurrent,
            settled = atRest,
            paragraph = paragraph.takeIf { layout.landscape },
            today = today,
            zone = zone,
            host = host,
            haptics = haptics,
            navBottom = navBottom,
            modifier = Modifier
                .fillMaxSize()
                .padding(start = layout.leftPage, top = top),
        )
        MemoryPageBarSlots(
            album = memory.title,
            artistLine = memory.supportingText,
            artistShort = memory.supportingText.artistOnly(),
            lastHeard = lastHeard,
            dotCount = dotCount,
            relative = { pagerState.currentPage - page + pagerState.currentPageOffsetFraction },
            // no diary state in a spread: the bar keeps slot A, the pill keeps its label
            diaryProgress = { 0f },
            onCloseDiary = {},
            cover = cover,
            insets = layout.bar,
            modifier = Modifier.padding(top = statusTop),
        )
    }
}

/** The left page: the exhibit and the citation, centred, every card's cover at the deck's one y. */
@Composable
private fun SpreadExhibit(
    memory: MemoryEntry,
    tones: MemoryPaletteTones,
    fit: SpreadFit,
    paragraph: YoinParagraph?,
    cover: @Composable (Modifier) -> Unit,
    emblem: @Composable (Modifier) -> Unit,
    interactive: Boolean,
    marqueeRunning: Boolean,
    onOpenAlbum: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = fit.spacing
    val kind = memory.cardTitleKind()
    val haptics = rememberYoinHaptics()
    Column(
        modifier = modifier.padding(start = SpreadPagePadding, end = SpreadPagePadding, top = fit.coverTop),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MemoryExhibitShow(
            cover = fit.cover,
            seal = fit.seal,
            coverContent = { m -> cover(m.clip(YoinArtworkShapes.Hero)) },
            emblem = emblem,
        )
        Spacer(Modifier.height(spacing.citeTop.dp))
        val titleModifier = Modifier.widthIn(max = SpreadCiteMax).semantics(mergeDescendants = true) { heading() }
        if (kind == MemoryTitleKind.ALBUM) {
            // the title is the album name: two lines at most, the rest runs as a marquee (never an ellipsis)
            TwoLineMarqueeText(
                text = memory.title,
                style = spreadTitleStyle(kind, fit.tightness),
                color = MaterialTheme.colorScheme.onSurface,
                running = marqueeRunning,
                modifier = titleModifier,
            )
        } else {
            Text(
                text = memory.memoryTitle?.takeIf(String::isNotBlank) ?: memory.title,
                style = spreadTitleStyle(kind, fit.tightness),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = titleModifier,
            )
        }
        if (paragraph != null) {
            Spacer(Modifier.height(spacing.paragraphTop.dp))
            YoinParagraphText(
                paragraph = paragraph,
                size = spreadParagraphSize(fit.tightness),
                centred = true,
                modifier = Modifier.widthIn(max = SpreadCiteMax),
            )
        }
        val onlyArtist = kind == MemoryTitleKind.ALBUM
        val albumTop = if (onlyArtist && fit.tightness == SpreadTightness.Normal) 8f else spacing.albumTop
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(top = albumTop.dp)
                .widthIn(max = SpreadAlbumMax)
                .semantics(mergeDescendants = true) { },
        ) {
            if (!onlyArtist) {
                TwoLineMarqueeText(
                    text = memory.title,
                    style = spreadAlbumStyle(fit.tightness),
                    color = MaterialTheme.colorScheme.onSurface,
                    running = marqueeRunning,
                )
                Spacer(Modifier.height(AlbumArtistGap))
            }
            Text(
                text = memory.supportingText,
                style = spreadArtistStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (memory.entityType == MemoryEntityType.ALBUM) {
            Spacer(Modifier.height(spacing.goTop.dp))
            Box(
                modifier = Modifier
                    .height(SpreadGoHeight.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(tones.button)
                    .then(
                        if (interactive) {
                            Modifier.clickable(role = Role.Button) {
                                haptics.performClick()
                                onOpenAlbum()
                            }
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                // no arrow (owner, v4)
                Text(
                    text = "Go to album",
                    style = cardText(GoogleSansFlex, FontWeight.SemiBold, 15.sp, 1f),
                    color = tones.onButton,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/**
 * The right page: the diary from your entry on, in the measure (40 in from the left page, ≥ 24 at the end),
 * placed by [spreadRightTop]. Only this column scrolls; the tide line and the 40dp fade as on the phone.
 */
@Composable
private fun SpreadDiary(
    memory: MemoryEntry,
    tones: MemoryPaletteTones,
    layout: MemoriesLayout,
    coverTop: Dp,
    goBottom: Dp,
    viewHeight: Dp,
    scroll: ScrollState,
    interactive: Boolean,
    settled: Boolean,
    paragraph: YoinParagraph?,
    today: LocalDate,
    zone: ZoneId,
    host: MemoriesDiaryHost,
    haptics: DiaryHaptics,
    navBottom: Dp,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val end = max(SpreadRightEndMin, maxWidth - SpreadRightStart - layout.measure)
        val bottom = SpreadRightBottom + navBottom
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .seamDissolveViewport(top = SeamTop.Chrome) { scroll.value.toFloat() }
                .verticalEdgeFadeOnScroll(scroll, bottom = MemoryDiaryTokens.BottomFade)
                .verticalScroll(scroll, enabled = interactive, overscrollEffect = null)
                .spreadRightPlacement(viewHeight, coverTop, goBottom, bottom)
                .padding(start = SpreadRightStart, end = end, bottom = bottom),
        ) {
            if (paragraph != null) {
                YoinParagraphText(
                    paragraph = paragraph,
                    modifier = Modifier.padding(bottom = SpreadLandscapeParagraphGap),
                )
            }
            DiaryBlocks(
                memory = memory,
                tones = tones,
                today = today,
                zone = zone,
                interactive = interactive,
                settled = settled,
                host = host,
                haptics = haptics,
                onOpenAlbum = {},
                firstBlock = 0,
                block = { Modifier },
                optical = null,
                showGo = false,
            )
        }
    }
}

/**
 * Places the right page's content at [spreadRightTop] of its own height (without the [bottom] padding):
 * centred on the left page's optical line when it fits the [viewHeight], else level with the cover.
 */
private fun Modifier.spreadRightPlacement(viewHeight: Dp, coverTop: Dp, goBottom: Dp, bottom: Dp): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minHeight = 0))
        val content = placeable.height.toDp() - bottom
        val top = spreadRightTop(content.value, viewHeight.value, coverTop.value, goBottom.value).dp.roundToPx()
        // never report less than the viewport's min height: a smaller size would be centred in it again
        val height = maxOf(placeable.height + top, constraints.minHeight)
        layout(placeable.width, height) { placeable.place(0, top) }
    }

// ---------------------------------------------------------------- spread type (prototype `.ts-cite`)

/** The left page's citation: Yoin's title 30 (motif / album 27), one step down when the ladder tightens. */
@Composable
internal fun spreadTitleStyle(kind: MemoryTitleKind, tightness: SpreadTightness): TextStyle {
    val tight = tightness != SpreadTightness.Normal
    return when (kind) {
        MemoryTitleKind.AI ->
            cardText(YoinSerifTitle, FontWeight.SemiBold, if (tight) 27.sp else 30.sp, 1.36f, heading = true)
        MemoryTitleKind.MOTIF ->
            cardText(ShowcaseType.rounded(600), FontWeight.SemiBold, if (tight) 25.sp else 27.sp, 1.3f, heading = true)
        MemoryTitleKind.ALBUM ->
            cardText(GoogleSansFlex, FontWeight.SemiBold, if (tight) 25.sp else 27.sp, 1.3f, heading = true)
    }
}

/** Yoin's paragraph on the left page: 17, 16 at the last spacing step. */
internal fun spreadParagraphSize(tightness: SpreadTightness): TextUnit =
    if (tightness == SpreadTightness.Tight2) 16.sp else 17.sp

@Composable
private fun spreadParagraphStyle(tightness: SpreadTightness): TextStyle =
    diaryText(ShowcaseType.rounded(400), FontWeight.Normal, spreadParagraphSize(tightness), 1.6f, heading = true)
        .copy(textAlign = TextAlign.Center)

@Composable
private fun spreadAlbumStyle(tightness: SpreadTightness): TextStyle =
    cardText(GoogleSansFlex, FontWeight.SemiBold, if (tightness == SpreadTightness.Normal) 20.sp else 18.sp, 1.3f)

@Composable
private fun spreadArtistStyle(): TextStyle = cardText(GoogleSansFlex, FontWeight.Medium, 14.sp, 1.35f)

/** The question's span inside Yoin's paragraph (on-surface, 500). */
internal fun yoinQuestionSpan(color: Color): SpanStyle =
    SpanStyle(fontFamily = ShowcaseType.rounded(500), fontWeight = FontWeight.Medium, color = color)

/** Yoin's paragraph as one string: the narration, then the question in the same breath. */
internal fun yoinParagraphString(paragraph: YoinParagraph, question: SpanStyle): AnnotatedString =
    buildAnnotatedString {
        paragraph.narration?.let { append(it) }
        val spaced = paragraph.language != MemoryProseLanguage.ZH
        if (paragraph.narration != null && paragraph.question != null && spaced) append(' ')
        paragraph.question?.let { q -> withStyle(question) { append(q) } }
    }

/** The left page's side padding; its citation and album row measures. Layout constants. */
private val SpreadPagePadding: Dp = 32.dp
private val SpreadCiteMax: Dp = 420.dp
private val SpreadAlbumMax: Dp = 400.dp
private val AlbumArtistGap: Dp = 2.dp

/** The right page: 40 in from the left page, ≥ 24 at its end, 44 under the ending. */
private val SpreadRightStart: Dp = 40.dp
private val SpreadRightEndMin: Dp = 24.dp
private val SpreadRightBottom: Dp = 44.dp

/** Landscape: Yoin's paragraph opens the right page, this far above your entry. */
private val SpreadLandscapeParagraphGap: Dp = 28.dp
