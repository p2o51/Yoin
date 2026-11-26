package com.gpo.yoin.ui.home

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.LocalSeamSideEdges
import com.gpo.yoin.ui.component.SeamDissolveTokens
import com.gpo.yoin.ui.component.SeamSideEdges
import com.gpo.yoin.ui.component.SideSeamLines
import com.gpo.yoin.ui.component.ignoreParentHorizontalPadding
import com.gpo.yoin.ui.component.seamStretch
import com.gpo.yoin.ui.component.tideBase
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.home.edit.LocalHomeEditCardScope
import com.gpo.yoin.ui.home.edit.homeEditShelfClip
import com.gpo.yoin.ui.theme.YoinMotion
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.launch

// Large-screen Home, stage 1 (owner 2026-11-16): a shelf in HALF a two-column
// row pages instead of scrolling freely. Each page holds a whole number of
// cards — the gaps stretch so they fill the column exactly — a swipe moves one
// page and snaps, so at rest nothing is cut. Mid-swipe both edges of the
// column take the tide line (潮线, SeamTide.kt) turned sideways, as a mask:
// back wave then front wave cut the cards, the line coming in from outside
// the edge as the pager leaves a page and gone again once it settles.
// Full-width rows keep today's free, bleeding shelf.

/** Where a section is laid out: a full-width row, or half of a two-column row. */
@Immutable
internal data class HomeColumn(val half: Boolean)

/** The column the section being composed sits in. Full width unless a two-column row says otherwise. */
internal val LocalHomeColumn = compositionLocalOf { HomeColumn(half = false) }

/** Numbers of the half-column pager. */
internal object HomeShelfPagerTokens {
    /** The narrowest gap between two cards on a page; the page's spare width widens it. */
    val MinGap = com.gpo.yoin.shared.home.HomeShelfPagerRules.MinGap.dp

    /** A release this fast (dp/s) turns the page even short of halfway. */
    val FlingVelocity = com.gpo.yoin.shared.home.HomeShelfPagerRules.FlingVelocity.dp

    /** Page dots. */
    val Dot = 6.dp
    val DotGap = 6.dp

    /** The ‹ › buttons beside the dots (trackpad and mouse). */
    val Button = 32.dp
    val ButtonGlyph = 18.dp

    /** How far into the column text fades past the front line (seamTextAlpha's band). */
    val TextFade = 10.dp

    /** Scroll-speed follower's rate (1/s), as the seam flow's afterglow. */
    const val SpeedFollow = 14f
}

// The page arithmetic is :shared's (com.gpo.yoin.shared.home, golden home-grid.json); these keep the app's names.

/** A page's layout ([com.gpo.yoin.shared.home.ShelfPageGeometry]). */
internal typealias ShelfPageGeometry = com.gpo.yoin.shared.home.ShelfPageGeometry

/** How [cardPx]-wide cards page across a [pagePx]-wide column ([com.gpo.yoin.shared.home.shelfPageGeometry]). Pure. */
internal fun shelfPageGeometry(pagePx: Float, cardPx: Float, minGapPx: Float): ShelfPageGeometry =
    com.gpo.yoin.shared.home.shelfPageGeometry(pagePx, cardPx, minGapPx)

/** A card's width when [perPage] of them, [HomeShelfPagerTokens.MinGap] apart, fill a [pageWidth] page. */
internal fun homeShelfCardWidth(pageWidth: Dp, perPage: Int): Dp =
    com.gpo.yoin.shared.home.homeShelfCardWidthDp(pageWidth.value, perPage).dp

/** Pages of [cardCount] cards after [leadPages] one-page lead items. Pure. */
internal fun shelfPageCount(cardCount: Int, perPage: Int, leadPages: Int = 0): Int =
    com.gpo.yoin.shared.home.shelfPageCount(cardCount, perPage, leadPages)

/** The trailing filler that makes the last page whole, px ([com.gpo.yoin.shared.home.shelfFillerPx]). Pure. */
internal fun shelfFillerPx(cardCount: Int, geometry: ShelfPageGeometry): Float =
    com.gpo.yoin.shared.home.shelfFillerPx(cardCount, geometry)

/** The shelf's scroll from its start, px ([com.gpo.yoin.shared.home.shelfScrollPx]). Pure. */
internal fun shelfScrollPx(index: Int, offsetPx: Int, geometry: ShelfPageGeometry, leadPages: Int = 0): Float =
    com.gpo.yoin.shared.home.shelfScrollPx(index, offsetPx, geometry, leadPages)

/** How far [scrollPx] is from the nearest page boundary, px (0 = settled on a page). Pure. */
internal fun shelfOffPagePx(scrollPx: Float, stridePx: Float): Float = com.gpo.yoin.shared.home.shelfOffPagePx(scrollPx, stridePx)

/** The page a release lands on ([com.gpo.yoin.shared.home.shelfReleasePage]). Pure. */
internal fun shelfReleasePage(
    scrollPx: Float,
    velocityPx: Float,
    stridePx: Float,
    startPage: Int,
    pageCount: Int,
    flingPx: Float,
): Int = com.gpo.yoin.shared.home.shelfReleasePage(scrollPx, velocityPx, stridePx, startPage, pageCount, flingPx)

/** The tide's reveal at [offPagePx] from a settled page: 0 settled, 1 from [revealPx] on. Pure. */
internal fun shelfTideReveal(offPagePx: Float, revealPx: Float): Float = com.gpo.yoin.shared.home.shelfTideReveal(offPagePx, revealPx)

/**
 * One half-column shelf's pager: the list, its page geometry (set as the
 * column is measured), the page it rests on and the scroll-speed follower
 * behind the tide's stretch.
 */
@Stable
internal class HomeShelfPagerState(val list: LazyListState) {
    var geometry: ShelfPageGeometry? by mutableStateOf(null)
        internal set
    var pageCount: Int by mutableIntStateOf(0)
        internal set
    var leadPages: Int by mutableIntStateOf(0)
        internal set

    /** The page the last settle landed on: a swipe moves one page from here. */
    var restPage: Int by mutableIntStateOf(0)
        internal set

    /** The list's left edge and width in root px (for the text fade's lines). */
    internal var rootLeft = 0f
    internal var widthPx = 0f

    /** Smoothed horizontal scroll speed, dp/s. */
    var speedDp: Float by mutableFloatStateOf(0f)
        internal set

    /** Scroll from the start, px; 0 before the geometry is known. */
    val scrollPx: Float
        get() {
            val g = geometry ?: return 0f
            return shelfScrollPx(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, g, leadPages)
        }

    /** Fractional page under the column's left edge. */
    val position: Float
        get() {
            val stride = geometry?.stridePx ?: return 0f
            return if (stride <= 0f) 0f else scrollPx / stride
        }

    val currentPage: Int get() = position.roundToInt().coerceIn(0, (pageCount - 1).coerceAtLeast(0))
}

/** The fling that turns exactly one page from where the swipe began, on the Expressive spatial spring. */
private class ShelfPageFling(
    private val pager: HomeShelfPagerState,
    private val spec: AnimationSpec<Float>,
    private val flingPx: Float,
) : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        val g = pager.geometry ?: return initialVelocity
        val target = shelfReleasePage(
            scrollPx = pager.scrollPx,
            velocityPx = initialVelocity,
            stridePx = g.stridePx,
            startPage = pager.restPage,
            pageCount = pager.pageCount,
            flingPx = flingPx,
        )
        val distance = target * g.stridePx - pager.scrollPx
        var travelled = 0f
        animate(0f, distance, initialVelocity, spec) { value, _ ->
            val delta = value - travelled
            travelled += scrollBy(delta)
        }
        pager.restPage = target
        return 0f
    }
}

/**
 * A titled Home shelf: the free, full-bleed shelf of a full-width row, or in
 * [LocalHomeColumn] half the paged one. [cardWidth] is each card's width
 * (all cards one width); [cardCount] how many there are. [leadPage]: the
 * shelf opens with one item a whole page wide ([content] gets the page width
 * to size it), as Recently Added's track grid. [content] emits the items; in
 * the half column the filler that completes the last page follows them.
 */
@Composable
internal fun HomeShelf(
    title: String,
    frame: HomeFeedFrame,
    scrollEnabled: Boolean,
    cardWidth: Dp,
    cardCount: Int,
    modifier: Modifier = Modifier,
    // Half column only: cards sized so exactly this many fill a page ([homeShelfCardWidth]); [cardWidth] ignored.
    perPage: Int? = null,
    leadPage: Boolean = false,
    fullGap: Dp = 14.dp,
    // A full row pages too (whole pages, the tide edge) instead of scrolling freely: 「歌词一句」 on the grid.
    pagedFull: Boolean = false,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.(pageWidth: Dp?) -> Unit,
) {
    val column = LocalHomeColumn.current
    val editCardScope = LocalHomeEditCardScope.current
    // Two columns (HomeGridUnits.kt): the shared 40dp title row, the cards right under it.
    val grid = LocalHomeGridHeight.current
    val titleGap = if (grid != null) 0.dp else 12.dp
    if (!column.half && !pagedFull) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(titleGap)) {
            if (grid != null) HomeGridTitleRow(title = title) else HomeSectionTitle(text = title)
            val sidePadding = remember(frame) { FeedFrameSidePadding(frame) }
            LazyRow(
                state = state,
                modifier = Modifier
                    .fillMaxWidth()
                    .ignoreParentHorizontalPadding(start = { frame.start }, end = { frame.end })
                    .homeEditShelfClip(editCardScope, escapeStart = { frame.start }, escapeEnd = { frame.end }),
                contentPadding = sidePadding,
                horizontalArrangement = Arrangement.spacedBy(fullGap),
                verticalAlignment = verticalAlignment,
                userScrollEnabled = scrollEnabled,
            ) { content(null) }
        }
        return
    }
    val pager = remember(state) { HomeShelfPagerState(state) }
    val leadPages = if (leadPage) 1 else 0
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(titleGap)) {
        HomeShelfTitleRow(title = title, pager = pager, controls = scrollEnabled, grid = grid != null)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val density = LocalDensity.current
            val pageWidth = maxWidth
            val card = if (perPage != null) homeShelfCardWidth(pageWidth, perPage) else cardWidth
            // On the grid the columns are ⌊(W + 12) / (cover + 12)⌋ (homeGridColumns), the gaps stretched.
            val minGap = if (grid != null) HomeGridTokens.ColumnGap else HomeShelfPagerTokens.MinGap
            val geometry = remember(pageWidth, card, density, minGap) {
                with(density) {
                    shelfPageGeometry(pageWidth.toPx(), card.toPx(), minGap.toPx())
                }
            }
            val pages = shelfPageCount(cardCount, geometry.perPage, leadPages)
            pager.geometry = geometry
            pager.pageCount = pages
            pager.leadPages = leadPages
            val filler = with(density) { shelfFillerPx(cardCount, geometry).toDp() }
            val gap = with(density) { geometry.gapPx.toDp() }
            val spec = YoinMotion.spatialSpring<Float>()
            val flingPx = with(density) { HomeShelfPagerTokens.FlingVelocity.toPx() }
            val fling = remember(pager, spec, flingPx) { ShelfPageFling(pager, spec, flingPx) }
            // A resize (the column, the span handle) keeps the page it was on.
            LaunchedEffect(geometry, pages) {
                val page = pager.restPage.coerceIn(0, (pages - 1).coerceAtLeast(0))
                pager.restPage = page
                val delta = page * geometry.stridePx - pager.scrollPx
                if (abs(delta) > .5f && !state.isScrollInProgress) state.scrollBy(delta)
            }
            ShelfSpeedFollower(pager)
            val reduced by rememberUpdatedState(LocalMotionProfile.current == MotionProfile.AdaptiveReduced)
            // While it pages, text near either edge fades under the line (seamFade reads the pager).
            val sideEdges = remember(pager, density) {
                SeamSideEdges {
                    val g = pager.geometry ?: return@SeamSideEdges null
                    with(density) {
                        val reveal = shelfTideReveal(
                            shelfOffPagePx(pager.scrollPx, g.stridePx),
                            SeamDissolveTokens.RevealDistance.toPx(),
                        )
                        if (reveal <= 0f || pager.widthPx <= 0f) return@SeamSideEdges null
                        // Text is gone up to the front line's crest, then fades in over the band.
                        val line = tideBase(SeamDissolveTokens.TideRest.toPx(), SeamDissolveTokens.TideHidden.toPx(), reveal) +
                            SeamDissolveTokens.TideAmplitude.toPx()
                        SideSeamLines(
                            left = pager.rootLeft + line,
                            right = pager.rootLeft + pager.widthPx - line,
                            band = HomeShelfPagerTokens.TextFade.toPx(),
                        )
                    }
                }
            }
            CompositionLocalProvider(LocalSeamSideEdges provides sideEdges) {
            LazyRow(
                state = state,
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned {
                        pager.rootLeft = it.positionInRoot().x
                        pager.widthPx = it.size.width.toFloat()
                    }
                    .shelfTideEdges(pager) { reduced },
                contentPadding = PaddingValues(0.dp),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = verticalAlignment,
                userScrollEnabled = scrollEnabled,
                flingBehavior = fling,
            ) {
                content(pageWidth)
                if (filler > 0.dp) item(key = ShelfFillerKey) { Spacer(Modifier.width(filler)) }
            }
            }
        }
    }
}

private const val ShelfFillerKey = "home-shelf-filler"

/** Follows the list's horizontal speed (dp/s) while it moves; decays to 0 once it rests. */
@Composable
private fun ShelfSpeedFollower(pager: HomeShelfPagerState) {
    val density = LocalDensity.current
    LaunchedEffect(pager, density) {
        snapshotFlow { pager.list.isScrollInProgress }.collect { moving ->
            if (!moving && pager.speedDp == 0f) return@collect
            var last = pager.scrollPx
            var lastNanos = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val dt = ((now - lastNanos) / 1e9f).coerceAtLeast(1e-4f)
                val px = pager.scrollPx
                val instant = with(density) { (abs(px - last) / dt).toDp().value }
                val k = 1f - exp(-HomeShelfPagerTokens.SpeedFollow * dt)
                pager.speedDp += (instant - pager.speedDp) * k
                last = px
                lastNanos = now
                if (!pager.list.isScrollInProgress && pager.speedDp < 1f) {
                    pager.speedDp = 0f
                    break
                }
            }
        }
    }
}

/**
 * The section title with, in a paged shelf, its page dots and ‹ › at the
 * row's end. The buttons turn one page on the same spring as a swipe.
 */
@Composable
private fun HomeShelfTitleRow(title: String, pager: HomeShelfPagerState, controls: Boolean, grid: Boolean = false) {
    val scope = rememberCoroutineScope()
    val spec = YoinMotion.spatialSpring<Float>()
    val pages = pager.pageCount
    // On the grid: the shared 40dp title row, the dots and ‹ › on its centre line.
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (grid) Modifier.height(HomeGridTokens.TitleRow) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomeSectionTitle(text = title, modifier = Modifier.weight(1f))
        // Editing (no scrolling): the title row's end is the edit badges'.
        if (pages <= 1 || !controls) return@Row
        val current by remember(pager) { derivedStateOf { pager.currentPage } }
        ShelfPageDots(count = pages, current = current)
        Spacer(Modifier.width(8.dp))
        fun turn(delta: Int) {
            val g = pager.geometry ?: return
            val target = (pager.restPage + delta).coerceIn(0, pages - 1)
            pager.restPage = target
            scope.launch { pager.list.animateScrollBy(target * g.stridePx - pager.scrollPx, spec) }
        }
        ShelfPageButton(
            enabled = current > 0,
            glyph = YoinSymbols.ChevronLeft,
            label = stringResource(R.string.home_shelf_previous_page),
            onClick = { turn(-1) },
        )
        ShelfPageButton(
            enabled = current < pages - 1,
            glyph = YoinSymbols.ChevronRight,
            label = stringResource(R.string.home_shelf_next_page),
            onClick = { turn(1) },
        )
    }
}

@Composable
private fun ShelfPageDots(count: Int, current: Int) {
    val colors = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(HomeShelfPagerTokens.DotGap)) {
        repeat(count) { index ->
            val on by animateFloatAsState(
                targetValue = if (index == current) 1f else 0f,
                animationSpec = YoinMotion.effectsSpring(),
                label = "shelf-dot",
            )
            Box(
                Modifier
                    .size(HomeShelfPagerTokens.Dot)
                    .background(
                        androidx.compose.ui.graphics.lerp(colors.outlineVariant, colors.primary, on),
                        CircleShape,
                    ),
            )
        }
    }
}

@Composable
private fun ShelfPageButton(
    enabled: Boolean,
    glyph: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(HomeShelfPagerTokens.Button),
        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
    ) {
        Icon(glyph, contentDescription = label, modifier = Modifier.size(HomeShelfPagerTokens.ButtonGlyph))
    }
}

/**
 * The tide line on both edges of a paging shelf, turned sideways and used as
 * a mask: the back wave (half clear, further in) then the front wave cut the
 * cards. Its reveal follows how far the pager is from a settled page — the
 * line comes in from [SeamDissolveTokens.TideHidden] outside the edge to
 * [SeamDissolveTokens.TideRest] inside over the first
 * [SeamDissolveTokens.RevealDistance] — so a settled page has no line at all.
 * Height follows the scroll speed (seamStretch), phase the scroll travelled;
 * reduced motion holds both still. A layer only while it cuts.
 */
private fun Modifier.shelfTideEdges(pager: HomeShelfPagerState, reduced: () -> Boolean): Modifier =
    drawWithContent {
        val g = pager.geometry
        val reveal = if (g == null) {
            0f
        } else {
            shelfTideReveal(shelfOffPagePx(pager.scrollPx, g.stridePx), SeamDissolveTokens.RevealDistance.toPx())
        }
        if (reveal <= 0f || size.width <= 0f) {
            drawContent()
            return@drawWithContent
        }
        // The waves cut with DstOut: the list draws into a layer of its own while they do.
        drawIntoCanvas { it.saveLayer(Rect(0f, 0f, size.width, size.height), ShelfLayerPaint) }
        drawContent()
        drawShelfTide(pager, reveal, reduced())
        drawIntoCanvas { it.restore() }
    }

private val ShelfLayerPaint = Paint()

private fun DrawScope.drawShelfTide(pager: HomeShelfPagerState, reveal: Float, reduced: Boolean) {
    val stretch = if (reduced) 0f else seamStretch(pager.speedDp)
    val amplitude = (SeamDissolveTokens.TideAmplitude + SeamDissolveTokens.TideAmplitudeStretched * stretch).toPx()
    val phase = if (reduced) 0f else pager.scrollPx / SeamDissolveTokens.TidePhaseTravel.toPx()
    val base = tideBase(SeamDissolveTokens.TideRest.toPx(), SeamDissolveTokens.TideHidden.toPx(), reveal)
    val path = Path()
    for (right in listOf(false, true)) {
        // Back wave first, half clear and further in; then the front line.
        path.setSideTideWave(
            width = size.width,
            height = size.height,
            base = base + SeamDissolveTokens.TideBackDrop.toPx(),
            amplitude = amplitude * SeamDissolveTokens.TideBackAmplitude,
            wavelength = SeamDissolveTokens.TideBackWavelength.toPx(),
            phase = .37f - phase * .7f,
            right = right,
            margin = 20.dp.toPx(),
            step = SeamDissolveTokens.TideStep.toPx(),
        )
        drawPath(path, Color.Black, alpha = SeamDissolveTokens.TideBackAlpha, blendMode = BlendMode.DstOut)
        path.setSideTideWave(
            width = size.width,
            height = size.height,
            base = base,
            amplitude = amplitude,
            wavelength = SeamDissolveTokens.TideFrontWavelength.toPx(),
            phase = phase,
            right = right,
            margin = 20.dp.toPx(),
            step = SeamDissolveTokens.TideStep.toPx(),
        )
        drawPath(path, Color.Black, blendMode = BlendMode.DstOut)
    }
}

/**
 * The tide's wave (SeamTide's law: a sine plus its half-wavelength harmonic)
 * along the left or [right] edge, [base] px in from it, closed out past the edge.
 */
internal fun Path.setSideTideWave(
    width: Float,
    height: Float,
    base: Float,
    amplitude: Float,
    wavelength: Float,
    phase: Float,
    right: Boolean,
    margin: Float,
    step: Float,
) {
    rewind()
    val outside = if (right) width + margin else -margin
    moveTo(outside, -margin)
    var y = -margin
    while (true) {
        val d = base + amplitude * (
            sin(((y / wavelength) + phase) * Tau) +
                SeamDissolveTokens.TideHarmonic * sin(((y / (wavelength * .5f)) - phase * 1.7f + .3f) * Tau)
            )
        lineTo(if (right) width - d else d, y)
        if (y >= height + margin) break
        y = minOf(y + step, height + margin)
    }
    lineTo(outside, height + margin)
    close()
}

private const val Tau = (PI * 2).toFloat()
