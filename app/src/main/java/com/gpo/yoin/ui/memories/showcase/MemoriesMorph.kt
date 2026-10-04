package com.gpo.yoin.ui.memories.showcase

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.memories.copy.MemoryTitleKind
import com.gpo.yoin.ui.theme.YoinArtworkShapes
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/*
 * The card ⇄ diary shared-element morph (twostate4 `renderPages` / `pair` / `barFollow` / `renderV`, v4.1).
 * p (MemoriesDiaryState) is the only driver; nothing here owns a value of its own except the bar cover's
 * alpha chase. Every element is laid out complete in both states and only TRANSFORMED here (translation,
 * scale, alpha, a per-frame corner): the card and the diary are never resized or cut while p moves, which
 * is what lets back progress scrub the morph (doctrine invariant 3, "a transform preview of complete
 * layouts"). Anchors are measured in placement, per page, and read only in layer blocks.
 *
 *  · the COVER shrinks and flies into the bar's leading 40dp slot (size leads position), 8 → 4dp corners,
 *    handing over by alpha to the bar's own cover between fp .62 and .9;
 *  · the TITLE holds until p .2, then flies to the diary title (crossfading its twin over .42–.78); the
 *    96dp EMBLEM travels with it to the native 48 right of the diary title (caption first, hand-over .6–.95);
 *  · the album row lifts 36 and fades by p .32; the excerpt and the actions drop 56 and fade;
 *  · the diary's blocks rise, staggered, behind the title.
 *
 * Frozen collapse: closing a diary whose title has already scrolled under the bar never rewinds the text.
 * The text sinks and fades in place first, the cover waits in the bar (fp = p / .55) and the card's title
 * and emblem fade in where they live; the scroll returns to 0 unseen at p < .05.
 *
 * Reduced motion: the same p, alpha only — the card is gone by p .5 and the diary starts there, so two
 * layers of text are never on screen at once.
 */

/**
 * The morph's choreography windows. These time a shared-element flight against p; they are NOT back
 * thresholds (those live in BackMotionTokens) and never decide where a gesture lands.
 */
internal object MemoriesMorphTokens {
    /** The title (and the emblem with it) waits in place until p .2: a slow pull never crosses the cover. */
    const val TitleHold = 0.2f
    const val TitleSwapFrom = 0.42f
    const val TitleSwapTo = 0.78f

    /** Fallback A: the album-name title flies to the bar's album name and is gone by here. */
    const val AlbumTitleFadeFrom = 0.36f
    const val AlbumTitleFadeTo = 0.72f

    /** The flying 96 hands over to the native 48 over this window of its travel. */
    const val EmblemSwapFrom = 0.6f
    const val EmblemSwapTo = 0.95f

    /** The 96's caption leaves first (by this much of its travel). */
    const val CaptionFadeTo = 0.4f

    /** The flying cover hands over to the bar's 40dp cover inside [CoverFloorFrom]…1 / [CoverSwapFrom]…[CoverSwapTo]. */
    const val CoverSwapFrom = 0.62f
    const val CoverSwapTo = 0.9f
    const val CoverFloorFrom = 0.75f

    /** The page's own cover belongs to the bar once it flies: its alpha meets the bar's parallax this fast. */
    const val CoverParallaxLead = 5f

    val CoverCornerCard = 8.dp
    val CoverCornerBar = 4.dp

    val AlbumRowLift = 36.dp
    const val AlbumRowFadeTo = 0.32f
    val TeaserDrop = 56.dp
    const val TeaserFadeTo = 0.3f
    const val ExcerptFadeTo = 0.26f

    /** Diary blocks: alpha over [BlockFadeFrom] + k·[BlockFadeStep] … [BlockFadeTo], k ≤ [BlockMaxIndex]. */
    const val BlockFadeFrom = 0.26f
    const val BlockFadeStep = 0.04f
    const val BlockFadeTo = 0.92f
    val BlockStagger = 28.dp
    const val BlockMaxIndex = 6

    /** The blocks trail the title's remaining travel (held until p .2, hence ÷ .8) plus this. */
    val BlockLagExtra = 36.dp

    /** Frozen: the cover waits in the bar until the sinking text is gone (fp = p / this). */
    const val FrozenCoverSpan = 0.55f
    const val FrozenTextFadeFrom = 0.55f
    const val FrozenTextFadeStep = 0.02f
    const val FrozenTextFadeTo = 0.95f
    const val FrozenCardFadeFrom = 0.3f
    const val FrozenCardFadeTo = 0.55f

    /** Frozen: the scroll returns to its top, unseen, below this p. */
    const val FrozenResetBelow = 0.05f

    /** The bar's slot A ("Memories / Last heard") leaves; slot B (album name + ⌄) arrives. */
    const val SlotAFadeTo = 0.45f
    val SlotALift = 6.dp
    const val SlotBFadeFrom = 0.55f
    const val SlotBFadeTo = 0.92f
    val SlotBDrop = 10.dp

    /** The Home pill narrows 88 → 36 (and its label fades) over the cover's flight. */
    const val PillNarrowTo = 0.6f
    const val PillLabelFadeTo = 0.4f

    /** Reduced motion: the card is gone by here and the diary starts here. */
    const val ReducedSwap = 0.5f
}

// ---------------------------------------------------------------- pure choreography (MemoriesMorphTest)

/** How far the title (and the emblem) has travelled: held until p .2; never while frozen. */
internal fun morphTitleTravel(p: Float, frozen: Boolean): Float =
    if (frozen) 0f else ((p - MemoriesMorphTokens.TitleHold) / (1f - MemoriesMorphTokens.TitleHold)).coerceIn(0f, 1f)

/** The cover's flight progress fp: p, or, frozen, p / .55 (it waits in the bar while the text sinks). */
internal fun morphCoverProgress(p: Float, frozen: Boolean): Float =
    if (frozen) (p / MemoriesMorphTokens.FrozenCoverSpan).coerceIn(0f, 1f) else p.coerceIn(0f, 1f)

/**
 * The bar cover's shown alpha: the chase held inside its hand-over window, so it is 0 below fp .62 (it never
 * flies at 2.4×) and the page's cover is gone exactly at fp 1.
 */
internal fun barCoverShown(chase: Float, fp: Float): Float {
    val lo = smoothstep(MemoriesMorphTokens.CoverFloorFrom, 1f, fp)
    val hi = smoothstep(MemoriesMorphTokens.CoverSwapFrom, MemoriesMorphTokens.CoverSwapTo, fp)
    return max(lo, min(hi, chase))
}

/** The bar cover's chase target. */
internal fun barCoverTarget(fp: Float): Float =
    smoothstep(MemoriesMorphTokens.CoverSwapFrom, MemoriesMorphTokens.CoverSwapTo, fp)

/** A flying cover's pose: translation from its rest centre, uniform scale, and the corner as SHOWN (px). */
@Immutable
internal data class CoverPose(val dx: Float, val dy: Float, val scale: Float, val cornerPx: Float)

/**
 * The cover at fp: its size leads its position (1 − (1 − fp)²), it rides the bar's parallax as it joins it,
 * and its shown corner goes card → bar.
 */
internal fun morphCoverPose(
    fp: Float,
    coverCenter: Offset,
    barCenter: Offset,
    coverPx: Float,
    barCoverPx: Float,
    parallaxPx: Float,
    cardCornerPx: Float,
    barCornerPx: Float,
): CoverPose {
    val lead = 1f - (1f - fp) * (1f - fp)
    val scale = lerpF(1f, if (coverPx > 0f) barCoverPx / coverPx else 1f, lead)
    return CoverPose(
        dx = (barCenter.x - coverCenter.x) * fp + parallaxPx * fp,
        dy = (barCenter.y - coverCenter.y) * fp,
        scale = scale,
        cornerPx = lerpF(cardCornerPx, barCornerPx, fp),
    )
}

/** A line of text as the morph reads it: its box's centre x, top y and font size, in page px. */
@Immutable
internal data class TextAnchor(val cx: Float, val y: Float, val fontPx: Float)

@Immutable
internal data class TextLayerPose(val dx: Float, val dy: Float, val scale: Float, val alpha: Float)

/**
 * Prototype `pair`: both copies travel one path (centre x, top y, scaled from the top centre) and hand over
 * by alpha inside [swapFrom]…[swapTo] of [t]. First = the card's copy, second = the diary's.
 */
internal fun morphTextPair(
    card: TextAnchor,
    diary: TextAnchor,
    t: Float,
    swapFrom: Float = MemoriesMorphTokens.TitleSwapFrom,
    swapTo: Float = MemoriesMorphTokens.TitleSwapTo,
): Pair<TextLayerPose, TextLayerPose> {
    val cx = lerpF(card.cx, diary.cx, t)
    val y = lerpF(card.y, diary.y, t)
    val k = smoothstep(swapFrom, swapTo, t)
    val a = TextLayerPose(
        dx = cx - card.cx,
        dy = y - card.y,
        scale = lerpF(1f, ratio(diary.fontPx, card.fontPx), t),
        alpha = 1f - k,
    )
    val b = TextLayerPose(
        dx = cx - diary.cx,
        dy = y - diary.y,
        scale = lerpF(ratio(card.fontPx, diary.fontPx), 1f, t),
        alpha = k,
    )
    return a to b
}

/** The 96 and the native 48 on one flight ([te] = the title's travel): poses relative to each one's rest. */
@Immutable
internal data class EmblemPose(
    val sealDx: Float,
    val sealDy: Float,
    val sealScale: Float,
    val sealAlpha: Float,
    val demDx: Float,
    val demDy: Float,
    val demScale: Float,
    val demAlpha: Float,
    val captionAlpha: Float,
)

internal fun morphEmblemPose(
    te: Float,
    sealCenter: Offset,
    demCenter: Offset,
    sealPx: Float,
    demPx: Float,
): EmblemPose {
    val s = lerpF(1f, ratio(demPx, sealPx), te)
    val ex = (demCenter.x - sealCenter.x) * te
    val ey = (demCenter.y - sealCenter.y) * te
    val k = smoothstep(MemoriesMorphTokens.EmblemSwapFrom, MemoriesMorphTokens.EmblemSwapTo, te)
    val landed = te >= 1f
    return EmblemPose(
        sealDx = ex,
        sealDy = ey,
        sealScale = s,
        sealAlpha = 1f - k,
        demDx = if (landed) 0f else ex + sealCenter.x - demCenter.x,
        demDy = if (landed) 0f else ey + sealCenter.y - demCenter.y,
        demScale = if (landed) 1f else ratio(sealPx * s, demPx),
        demAlpha = k,
        captionAlpha = 1f - smoothstep(0f, MemoriesMorphTokens.CaptionFadeTo, te),
    )
}

/** A diary block's rise: (1 − p)(lag + 28k); none while frozen (the whole text sinks as one instead). */
internal fun diaryBlockOffset(p: Float, k: Int, lagPx: Float, staggerPx: Float, frozen: Boolean): Float {
    if (frozen || p >= 1f) return 0f
    return (1f - p.coerceIn(0f, 1f)) * (lagPx + min(k, MemoriesMorphTokens.BlockMaxIndex) * staggerPx)
}

internal fun diaryBlockAlpha(p: Float, k: Int, frozen: Boolean): Float {
    val kk = min(k, MemoriesMorphTokens.BlockMaxIndex)
    return if (frozen) {
        smoothstep(
            MemoriesMorphTokens.FrozenTextFadeFrom + kk * MemoriesMorphTokens.FrozenTextFadeStep,
            MemoriesMorphTokens.FrozenTextFadeTo,
            p,
        )
    } else {
        smoothstep(
            MemoriesMorphTokens.BlockFadeFrom + kk * MemoriesMorphTokens.BlockFadeStep,
            MemoriesMorphTokens.BlockFadeTo,
            p,
        )
    }
}

/** Frozen: the text sinks as one block from where it is, (1 − p)·lag. */
internal fun diaryFrozenSink(p: Float, lagPx: Float, frozen: Boolean): Float =
    if (frozen && p < 1f) (1f - p.coerceIn(0f, 1f)) * lagPx else 0f

/** The blocks' lag: the title's remaining travel (÷ .8, it is held until .2) + 36dp (+ its height without one). */
internal fun diaryBlockLag(cardTitleTop: Float, diaryTop: Float, extraPx: Float, cardTitleHeight: Float?): Float =
    max(0f, cardTitleTop - diaryTop) / (1f - MemoriesMorphTokens.TitleHold) + extraPx + (cardTitleHeight ?: 0f)

/** Reduced motion: the card fades out by p .5, the diary in from p .5 — never both at once. */
internal fun reducedCardAlpha(p: Float): Float = 1f - smoothstep(0f, MemoriesMorphTokens.ReducedSwap, p)

internal fun reducedDiaryAlpha(p: Float): Float = smoothstep(MemoriesMorphTokens.ReducedSwap, 1f, p)

private fun lerpF(a: Float, b: Float, t: Float): Float = a + (b - a) * t

private fun ratio(a: Float, b: Float): Float = if (b > 0f) a / b else 1f

// ---------------------------------------------------------------- the deck: p, the frozen collapse, the pull

/**
 * The deck-level side of the diary: p (the one [MemoriesDiaryState]), which page is current, the frozen
 * collapse bookkeeping, the bar cover's alpha chase, and the diary's nested scroll — a pull past the top of
 * the text feeds p ([connection]) — plus the router's [MemoriesDiaryProbe]. Pages register their
 * [MemoryPageMorph]; everything per-frame is read in layer blocks.
 */
@Stable
internal class MemoriesDiaryDeck(
    val diary: MemoriesDiaryState,
    private val scope: CoroutineScope,
) : MemoriesDiaryProbe {
    /** Reduced motion: alpha only. */
    var reduced by mutableStateOf(false)

    /** p's travel in px (BackMotionTokens.MemoriesDiaryMorphDistance): the card's rubber band and the overshoot. */
    var morphDistancePx = 0f

    /** The pager's current page (a snapshot read). */
    var currentPage: () -> Int = { 0 }

    private val pages = mutableStateMapOf<Int, MemoryPageMorph>()

    /** The frozen timing outlives the invisible scroll reset until p reaches 0 or 1. */
    var frozenHold by mutableStateOf(false)

    /** The bar cover's alpha, chasing [barCoverTarget] on the fast effects spring (driven by the showcase). */
    val barChase = Animatable(0f)

    val p: Float get() = diary.fraction

    fun current(): MemoryPageMorph? = pages[currentPage()]

    fun register(morph: MemoryPageMorph) {
        pages[morph.page] = morph
    }

    fun unregister(morph: MemoryPageMorph) {
        if (pages[morph.page] === morph) pages.remove(morph.page)
    }

    /** The current diary's title is under the bar (prototype `so ≥ thr`), or the hold keeps that timing. */
    val frozen: Boolean
        get() {
            if (frozenHold) return true
            val page = current() ?: return false
            val so = page.scroll.value
            val thr = page.anchors?.freezeAtPx ?: return false
            return so > 0 && so >= thr
        }

    /** The current page's cover flight progress. */
    val fp: Float get() = morphCoverProgress(p, frozen)

    fun barShown(fp: Float): Float = barCoverShown(barChase.value, fp)

    // ---------------------------------------------------------------- the pull (prototype `vert` mode)

    private var scrolledAtDown = false
    private var pulling = false

    /** The live pull's p .5 line (CLOCK_TICK when a release would start to close the diary, and back). */
    private var closeLine: ThresholdCrossing? = null

    /** A pull past the top of the text crossed p .5 (either way): the deck's CLOCK_TICK. */
    var onThresholdCrossed: (what: String) -> Unit = {}

    override fun onFingerDown() {
        scrolledAtDown = (current()?.scroll?.value ?: 0) > 0
        pulling = false
    }

    override fun atEnd(): Boolean {
        val page = current() ?: return false
        val s = page.scroll
        return p >= FullyOpen && !diary.isSettling && !s.isScrollInProgress && s.value >= s.maxValue - 1
    }

    private fun ensurePull() {
        if (pulling) return
        pulling = true
        // every pull that starts in the diary text has the 24dp half-speed band; one that started
        // scrolled stays in the diary when released inside it (chooseDiaryReleaseTarget)
        diary.startPull(banded = true, fromScrolled = scrolledAtDown)
        closeLine = ThresholdCrossing(DiaryCloseThreshold, 1f - diary.fraction)
    }

    private fun pull(deltaY: Float): Float {
        val consumed = diary.pullBy(deltaY)
        if (closeLine?.update(1f - diary.fraction) == true) {
            val past = closeLine?.isPast == true
            onThresholdCrossed(if (past) "past p .5, release closes the diary" else "back over p .5")
        }
        return consumed
    }

    /**
     * One coordinate, as the prototype's v: while p < 1 the finger moves p (either way) before the text;
     * at p 1 the text scrolls, and only what is left past its top (finger down) reaches p. A fling that
     * reaches the top only stops: non-touch deltas never reach p.
     */
    val connection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || available.y == 0f) return Offset.Zero
            if (diary.fraction >= 1f) return Offset.Zero
            ensurePull()
            return Offset(0f, pull(available.y))
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
            ensurePull()
            return Offset(0f, pull(available.y))
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (!pulling) return Velocity.Zero
            pulling = false
            closeLine = null
            if (diary.fraction >= 1f) return Velocity.Zero
            val velocity = available.y
            scope.launch { diary.releasePull(velocity) }
            return available
        }
    }

    private companion object {
        const val FullyOpen = 0.999f
    }
}

// ---------------------------------------------------------------- one page: anchors and layers

/** Everything the morph needs from one page's layout, in page px at rest (scroll 0, no transforms). */
@Immutable
internal data class MemoryMorphAnchors(
    val coverCenter: Offset,
    val coverPx: Float,
    val sealCenter: Offset,
    val sealPx: Float,
    val cardTitle: TextAnchor,
    val cardTitleHeight: Float,
    /** The diary's title (Yoin's: AI or motif); null for fallback A, whose title flies to the bar. */
    val diaryTitle: TextAnchor?,
    val demCenter: Offset?,
    val barCoverCenter: Offset,
    val barTitle: TextAnchor,
    /** Scroll past which the diary title is under the bar: a collapse from there is frozen. */
    val freezeAtPx: Float,
    val lagPx: Float,
)

/**
 * One page's morph: its anchors (measured in placement, transform-free) and the layer blocks every element
 * applies. Positions are taken as (outer node in page) + (element in that node's inner coordinates), so the
 * card's rubber band, the diary's scroll and its frozen sink never leak into the anchors.
 */
@Stable
internal class MemoryPageMorph(
    val page: Int,
    val deck: MemoriesDiaryDeck,
    val scroll: ScrollState,
    private val density: Density,
) {
    var anchors: MemoryMorphAnchors? by mutableStateOf(null)
        private set

    /** Pager position relative to this page (rel), read in layers. */
    var relative: () -> Float = { 0f }

    var titleKind: MemoryTitleKind = MemoryTitleKind.AI
    var cardTitleFontPx = 0f
    var diaryTitleFontPx = 0f
    var statusTopPx = 0f
    var barTitleWidthPx = 0f
    var barTitleFontPx = 0f

    /** Where the bar's slots sit for this container's tier (the bar cover and slot B follow the pill). */
    var barInsets: MemoriesBarInsets = MemoriesBarInsets.Phone

    private var pageWidthPx = 0f
    private var page0: LayoutCoordinates? = null
    private var cardOuter: LayoutCoordinates? = null
    private var cardInner: LayoutCoordinates? = null
    private var cover: LayoutCoordinates? = null
    private var seal: LayoutCoordinates? = null
    private var cardTitle: LayoutCoordinates? = null
    private var viewport: LayoutCoordinates? = null
    private var diaryInner: LayoutCoordinates? = null
    private var diaryTitle: LayoutCoordinates? = null
    private var dem: LayoutCoordinates? = null
    private var head: LayoutCoordinates? = null

    // per-frame reads (layer blocks only)
    val isCurrent: Boolean get() = deck.currentPage() == page
    val pc: Float get() = deck.p.coerceIn(0f, 1f)
    val frozen: Boolean get() = isCurrent && deck.frozen
    val fp: Float get() = if (isCurrent) deck.fp else pc
    private val so: Float get() = if (isCurrent) scroll.value.toFloat() else 0f
    private val parallaxPx: Float get() = parallaxPx(relative(), pageWidthPx)
    private val visibility: Float get() = barVisibility(relative())

    // ------------------------------------------------------------ measurement (onPlaced sinks)

    fun onPage(c: LayoutCoordinates) {
        val resized = page0?.size != c.size
        page0 = c
        pageWidthPx = c.size.width.toFloat()
        if (resized) remeasure()
    }

    fun onCardOuter(c: LayoutCoordinates) = set { cardOuter = c }

    fun onCardInner(c: LayoutCoordinates) = set { cardInner = c }

    fun onCover(c: LayoutCoordinates) = set { cover = c }

    fun onSeal(c: LayoutCoordinates) = set { seal = c }

    fun onCardTitle(c: LayoutCoordinates) = set { cardTitle = c }

    fun onViewport(c: LayoutCoordinates) = set { viewport = c }

    /** Re-placed every scroll frame: kept, but only a first placement re-measures. */
    fun onDiaryInner(c: LayoutCoordinates) {
        val first = diaryInner == null
        diaryInner = c
        if (first) remeasure()
    }

    fun onDiaryTitle(c: LayoutCoordinates?) = set { diaryTitle = c }

    fun onDem(c: LayoutCoordinates) = set { dem = c }

    fun onHead(c: LayoutCoordinates) = set { head = c }

    private inline fun set(block: () -> Unit) {
        block()
        remeasure()
    }

    private fun remeasure() {
        val page = page0?.takeIf { it.isAttached } ?: return
        fun at(outer: LayoutCoordinates?, inner: LayoutCoordinates?, el: LayoutCoordinates?): Offset? {
            if (outer == null || inner == null || el == null) return null
            if (!outer.isAttached || !inner.isAttached || !el.isAttached) return null
            return page.localPositionOf(outer, Offset.Zero) + inner.localPositionOf(el, Offset.Zero)
        }
        val coverC = cover ?: return
        val sealC = seal ?: return
        val titleC = cardTitle ?: return
        val coverAt = at(cardOuter, cardInner, coverC) ?: return
        val sealAt = at(cardOuter, cardInner, sealC) ?: return
        val titleAt = at(cardOuter, cardInner, titleC) ?: return
        val headC = head ?: return
        val headAt = at(viewport, diaryInner, headC) ?: return
        val viewportTop = viewport?.takeIf { it.isAttached }?.let { page.localPositionOf(it, Offset.Zero).y } ?: return
        val dTitleC = diaryTitle?.takeIf { it.isAttached }
        val dTitleAt = dTitleC?.let { at(viewport, diaryInner, it) }
        val demC = dem
        val demAt = demC?.let { at(viewport, diaryInner, it) }
        with(density) {
            val cardTitleAnchor = TextAnchor(titleAt.x + titleC.size.width / 2f, titleAt.y, cardTitleFontPx)
            val diaryTitleAnchor = if (dTitleAt != null) {
                TextAnchor(dTitleAt.x + dTitleC.size.width / 2f, dTitleAt.y, diaryTitleFontPx)
            } else {
                null
            }
            // the scroll past which the title (or, without one, the head) is under the bar
            val freezeAt = if (dTitleAt != null) {
                dTitleAt.y + dTitleC.size.height - viewportTop
            } else {
                headAt.y + headC.size.height - viewportTop
            }
            val barTop = statusTopPx
            val slotBStart = barInsets.slotBStart.toPx()
            val barLines = BarTitleLineHeight.toPx() + BarArtistLineHeight.toPx()
            val next = MemoryMorphAnchors(
                coverCenter = coverAt + Offset(coverC.size.width / 2f, coverC.size.height / 2f),
                coverPx = coverC.size.width.toFloat(),
                sealCenter = sealAt + Offset(sealC.size.width / 2f, sealC.size.height / 2f),
                sealPx = sealC.size.width.toFloat(),
                cardTitle = cardTitleAnchor,
                cardTitleHeight = titleC.size.height.toFloat(),
                diaryTitle = diaryTitleAnchor,
                demCenter = if (demAt != null) {
                    demAt + Offset(demC.size.width / 2f, demC.size.height / 2f)
                } else {
                    null
                },
                barCoverCenter = Offset(
                    (barInsets.barCoverStart + MemoriesTopBarTokens.BarCover / 2).toPx(),
                    barTop + MemoriesTopBarTokens.Height.toPx() / 2f,
                ),
                barTitle = TextAnchor(
                    cx = slotBStart + barTitleWidthPx / 2f,
                    y = barTop + (MemoriesTopBarTokens.Height.toPx() - barLines) / 2f,
                    fontPx = barTitleFontPx,
                ),
                freezeAtPx = freezeAt,
                lagPx = diaryBlockLag(
                    cardTitleTop = cardTitleAnchor.y,
                    diaryTop = diaryTitleAnchor?.y ?: headAt.y,
                    extraPx = MemoriesMorphTokens.BlockLagExtra.toPx(),
                    cardTitleHeight = if (diaryTitleAnchor == null) titleC.size.height.toFloat() else null,
                ),
            )
            if (next != anchors) anchors = next
        }
    }

    // ------------------------------------------------------------ layer blocks

    private fun GraphicsLayerScope.identity() {
        translationX = 0f
        translationY = 0f
        scaleX = 1f
        scaleY = 1f
        alpha = 1f
    }

    /** The card's cover: flies into the bar slot; hands over to the bar's cover by alpha. */
    fun GraphicsLayerScope.cardCover() {
        transformOrigin = TransformOrigin.Center
        clip = true
        val a = anchors
        val f = fp
        if (deck.reduced || a == null || f <= 0f) {
            identity()
            shape = YoinArtworkShapes.Hero
            return
        }
        val pose = coverPose(a, f)
        translationX = pose.dx
        translationY = pose.dy
        scaleX = pose.scale
        scaleY = pose.scale
        shape = RoundedCornerShape(pose.cornerPx / pose.scale)
        val ownAlpha = lerpF(1f, visibility, min(1f, f * MemoriesMorphTokens.CoverParallaxLead))
        alpha = ownAlpha * (1f - deck.barShown(f))
    }

    /** The bar's 40dp cover (its parent already rides the bar's parallax and visibility). */
    fun GraphicsLayerScope.barCover() {
        transformOrigin = TransformOrigin.Center
        clip = true
        val a = anchors
        val f = fp
        if (deck.reduced) {
            identity()
            alpha = reducedDiaryAlpha(pc)
            shape = YoinArtworkShapes.ThumbAnimated
            return
        }
        alpha = deck.barShown(f)
        if (a == null || f >= 1f) {
            translationX = 0f
            translationY = 0f
            scaleX = 1f
            scaleY = 1f
            shape = YoinArtworkShapes.ThumbAnimated
            return
        }
        val pose = coverPose(a, f)
        val par = parallaxPx
        translationX = a.coverCenter.x + pose.dx - (a.barCoverCenter.x + par)
        translationY = a.coverCenter.y + pose.dy - a.barCoverCenter.y
        val barPx = MemoriesTopBarTokens.BarCover.toPx()
        val k = a.coverPx * pose.scale / barPx
        scaleX = k
        scaleY = k
        shape = RoundedCornerShape(pose.cornerPx / k)
    }

    private fun GraphicsLayerScope.coverPose(a: MemoryMorphAnchors, f: Float): CoverPose = morphCoverPose(
        fp = f,
        coverCenter = a.coverCenter,
        barCenter = a.barCoverCenter,
        coverPx = a.coverPx,
        barCoverPx = MemoriesTopBarTokens.BarCover.toPx(),
        parallaxPx = parallaxPx,
        cardCornerPx = MemoriesMorphTokens.CoverCornerCard.toPx(),
        barCornerPx = MemoriesMorphTokens.CoverCornerBar.toPx(),
    )

    /** The card's title: holds, then flies to the diary title (or, fallback A, the bar's album name). */
    fun GraphicsLayerScope.cardTitle() {
        transformOrigin = TransformOrigin(0.5f, 0f)
        val a = anchors
        if (deck.reduced || a == null) {
            identity()
            return
        }
        val p = pc
        val dTitle = a.diaryTitle
        when {
            frozen -> {
                identity()
                alpha = 1f - smoothstep(MemoriesMorphTokens.FrozenCardFadeFrom, MemoriesMorphTokens.FrozenCardFadeTo, p)
            }
            dTitle != null -> {
                val travel = morphTitleTravel(p, false)
                apply(morphTextPair(a.cardTitle, dTitle.copy(y = dTitle.y - so), travel).first)
            }
            titleKind == MemoryTitleKind.ALBUM -> {
                val tt = morphTitleTravel(p, false)
                val pose = morphTextPair(
                    a.cardTitle,
                    a.barTitle.copy(cx = a.barTitle.cx + parallaxPx),
                    tt,
                    MemoriesMorphTokens.AlbumTitleFadeFrom,
                    MemoriesMorphTokens.AlbumTitleFadeTo,
                ).first
                apply(pose)
            }
            else -> {
                identity()
                translationY = -MemoriesMorphTokens.AlbumRowLift.toPx() * p
                alpha = 1f - smoothstep(0f, MemoriesMorphTokens.AlbumRowFadeTo, p)
            }
        }
    }

    /** The diary's title: the card title's twin on the same flight; frozen, it fades in place. */
    fun GraphicsLayerScope.diaryTitle() {
        transformOrigin = TransformOrigin(0.5f, 0f)
        val a = anchors
        val dTitle = a?.diaryTitle
        if (deck.reduced || a == null || dTitle == null) {
            identity()
            return
        }
        val p = pc
        if (frozen) {
            identity()
            alpha = smoothstep(MemoriesMorphTokens.FrozenTextFadeFrom, MemoriesMorphTokens.FrozenTextFadeTo, p)
            return
        }
        apply(morphTextPair(a.cardTitle, dTitle.copy(y = dTitle.y - so), morphTitleTravel(p, false)).second)
    }

    private fun GraphicsLayerScope.apply(pose: TextLayerPose) {
        translationX = pose.dx
        translationY = pose.dy
        scaleX = pose.scale
        scaleY = pose.scale
        alpha = pose.alpha
    }

    /** The 96 on the cover: travels with the title to the 48 by the diary title, then hands over. */
    fun GraphicsLayerScope.cardSeal() {
        transformOrigin = TransformOrigin.Center
        val pose = emblemPose()
        when {
            deck.reduced || anchors == null -> identity()
            frozen -> {
                identity()
                alpha = frozenCardAlpha(pc)
            }
            pose == null -> {
                identity()
                alpha = frozenCardAlpha(pc)
            }
            else -> {
                translationX = pose.sealDx
                translationY = pose.sealDy
                scaleX = pose.sealScale
                scaleY = pose.sealScale
                alpha = pose.sealAlpha
            }
        }
    }

    /** Frozen: the card's title and 96 wait on the card and fade in once the sinking text is gone. */
    private fun frozenCardAlpha(p: Float): Float =
        1f - smoothstep(MemoriesMorphTokens.FrozenCardFadeFrom, MemoriesMorphTokens.FrozenCardFadeTo, p)

    /** The 96's caption alpha (it leaves first). */
    fun sealCaptionAlpha(): Float = if (deck.reduced || frozen) 1f else emblemPose()?.captionAlpha ?: 1f

    /** The diary's native 48, by the title. */
    fun GraphicsLayerScope.diaryEmblem() {
        transformOrigin = TransformOrigin.Center
        val pose = emblemPose()
        when {
            deck.reduced || anchors == null -> identity()
            frozen || pose == null -> {
                identity()
                alpha = smoothstep(MemoriesMorphTokens.FrozenTextFadeFrom, MemoriesMorphTokens.FrozenTextFadeTo, pc)
            }
            else -> {
                translationX = pose.demDx
                translationY = pose.demDy
                scaleX = pose.demScale
                scaleY = pose.demScale
                alpha = pose.demAlpha
            }
        }
    }

    private fun emblemPose(): EmblemPose? {
        val a = anchors ?: return null
        val dem = a.demCenter ?: return null
        return morphEmblemPose(
            te = morphTitleTravel(pc, frozen),
            sealCenter = a.sealCenter,
            demCenter = dem.copy(y = dem.y - so),
            sealPx = a.sealPx,
            demPx = with(density) { DiaryEmblemSize.toPx() },
        )
    }

    /** The album row (and fallback A's artist line): lifts 36 and is gone by p .32. */
    fun GraphicsLayerScope.albumRow() {
        identity()
        if (deck.reduced) return
        translationY = -MemoriesMorphTokens.AlbumRowLift.toPx() * pc
        alpha = 1f - smoothstep(0f, MemoriesMorphTokens.AlbumRowFadeTo, pc)
    }

    /** The excerpt: leaves with the actions, a little sooner. */
    fun GraphicsLayerScope.excerpt() {
        identity()
        if (deck.reduced) return
        translationY = MemoriesMorphTokens.TeaserDrop.toPx() * pc
        alpha = 1f - smoothstep(0f, MemoriesMorphTokens.ExcerptFadeTo, pc)
    }

    /** Diary / Go to album and the swipe cue: drop 56 and fade. */
    fun GraphicsLayerScope.teaser() {
        identity()
        if (deck.reduced) return
        translationY = MemoriesMorphTokens.TeaserDrop.toPx() * pc
        alpha = 1f - smoothstep(0f, MemoriesMorphTokens.TeaserFadeTo, pc)
    }

    /** The card face as a whole: the rubber band below p 0; reduced motion fades it out by p .5. */
    fun GraphicsLayerScope.cardFace() {
        if (deck.reduced) {
            translationY = 0f
            alpha = reducedCardAlpha(pc)
        } else {
            translationY = cardRubberBandPx(deck.p, deck.morphDistancePx)
            alpha = 1f
        }
    }

    /** The diary viewport: nothing drawn at the card; reduced motion fades it in from p .5. */
    fun GraphicsLayerScope.diaryViewport() {
        alpha = when {
            deck.p <= HiddenBelow -> 0f
            deck.reduced -> reducedDiaryAlpha(pc)
            else -> 1f
        }
    }

    /** The diary text as one: the frozen sink, and the open spring's overshoot (p > 1 scrolls it a little). */
    fun GraphicsLayerScope.diaryBody() {
        if (deck.reduced) {
            translationY = 0f
            return
        }
        val a = anchors
        translationY = diaryFrozenSink(deck.p, a?.lagPx ?: 0f, frozen) - max(0f, deck.p - 1f) * deck.morphDistancePx
    }

    /** The k-th diary block rises (staggered) and fades in behind the title. */
    fun GraphicsLayerScope.diaryBlock(k: Int) {
        identity()
        if (deck.reduced) return
        val p = pc
        val fz = frozen
        translationY = diaryBlockOffset(p, k, anchors?.lagPx ?: 0f, MemoriesMorphTokens.BlockStagger.toPx(), fz)
        alpha = diaryBlockAlpha(p, k, fz)
    }

    private companion object {
        const val HiddenBelow = 0.0005f
    }
}

/** The diary title's native emblem size (not the 96 scaled down). */
internal val DiaryEmblemSize = 48.dp

/** The bar's album and artist lines (15 × 1.3, 12 × 1.35): slot B's column height, for fallback A's target. */
private val BarTitleLineHeight = 19.5.dp
private val BarArtistLineHeight = 16.2.dp

@Composable
internal fun rememberMemoryPageMorph(
    page: Int,
    deck: MemoriesDiaryDeck,
    scroll: ScrollState,
    density: Density,
): MemoryPageMorph {
    val morph = remember(page, deck, scroll, density) { MemoryPageMorph(page, deck, scroll, density) }
    DisposableEffect(morph) {
        deck.register(morph)
        onDispose { deck.unregister(morph) }
    }
    return morph
}

@Composable
internal fun rememberMemoriesDiaryDeck(diary: MemoriesDiaryState, scope: CoroutineScope): MemoriesDiaryDeck =
    remember(diary, scope) { MemoriesDiaryDeck(diary, scope) }
