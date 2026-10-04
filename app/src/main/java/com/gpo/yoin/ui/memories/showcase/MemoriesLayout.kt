package com.gpo.yoin.ui.memories.showcase

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/*
 * Memories' container budget (twostate4 `layoutFor` / `medCov` / `sealFor` / `fitCover` / `placeSpread` /
 * `balanceMedium`). A page reads only its own container's size — the BoxWithConstraints it is laid out in —
 * never the device's (adaptive principle 1), so a 1280 window with the detail column open lays Memories out
 * as the Medium it is.
 *
 *  · Phone (W < 600): the two states (card ⇄ diary).
 *  · Medium (600 ≤ W < 900, or a spread whose right column would be under 360, or whose left page can't hold
 *    Medium's cover): owner choice tablet-portrait A — the phone's two states enlarged. The card sits in a
 *    480 column (cover clamp(H − 580, 256, 360), 168 short), the diary in a min(640, W − 32) column, tablet
 *    type sizes, the liner capped at 520. The gap between the exhibit and the teaser is capped at 120; the
 *    rest goes 1:1 to the top air and the bottom ([balanceMedium]).
 *  · Spread (everything else): two pages, no morph. The left page is the exhibit — the cover with its emblem,
 *    Yoin's title, paragraph and question, the album line, Go to album; the right page is the diary from your
 *    own entry on. No divider; ≥ 64dp between them. The cover comes down a height ladder (tighter spacing
 *    first, then a smaller cover) when the real text needs it ([fitSpreadCover]); the whole deck uses one
 *    cover, and every card's cover starts at the same y.
 *
 * The Medium floors are the phone's values at the same height and the spread never offers less than Medium,
 * so a window dragged across 600 or 900 never shrinks the exhibit.
 *
 * Deviation (PLAN §7 Q2, not in the prototype): phone landscape — height under 480 with room for two pages —
 * is the spread's structure with a lower cover floor (88) and a smaller emblem, and Yoin's paragraph opens the
 * right page instead of sitting under the title (a 400dp-tall left page can't hold it and a cover).
 *
 * Everything here is pure (MemoriesLayoutTest, golden layout-*.json from the prototype). The text budgets
 * that feed [balanceMedium] and [fitSpreadCover] are measured with TextMeasurer (MemoriesSpread.kt,
 * rememberMemoriesBudget), adaptive principle 6.
 */

internal enum class MemoriesTier { Phone, Medium, Spread }

/** One container's budget: the tier and the budget's first offer for the exhibit. Sizes in dp. */
@Immutable
internal data class MemoriesLayout(
    val tier: MemoriesTier,
    /** Shorter than 760: the 16:9 rhythm (smaller exhibit, tighter spacing). */
    val short: Boolean,
    /** Phone landscape (height < 480) — the deviation; always a spread. */
    val landscape: Boolean,
    val cover: Dp,
    val seal: Dp,
    /** The Home pill's start and the dots' end inset (prototype `pl` / `dr`). */
    val bar: MemoriesBarInsets,
    val barHeight: Dp,
    /** Spread: the left page's width (prototype `lp`) and the right column's measure. */
    val leftPage: Dp = 0.dp,
    val measure: Dp = 0.dp,
    /** Medium: the diary column. */
    val diaryColumn: Dp = 0.dp,
    /** The card's top air (phone / Medium, before [balanceMedium]). */
    val air1: Dp = 0.dp,
    /** Spread: the height ladder's floor. */
    val minCover: Dp = 0.dp,
) {
    val isSpread: Boolean get() = tier == MemoriesTier.Spread

    /** The card ⇄ diary two states (phone and Medium). */
    val twoState: Boolean get() = tier != MemoriesTier.Spread
}

/** Where the bar's controls sit (prototype `--pl` / `--dr`); the per-page slots follow the pill. */
@Immutable
internal data class MemoriesBarInsets(val pillStart: Dp, val dotsEnd: Dp) {
    /** Slot A ("Memories / Last heard"): 100dp past the pill's start. */
    val slotAStart: Dp get() = pillStart + 100.dp

    /** The diary's 40dp bar cover: 48dp past the pill's start (the pill is 36 there). */
    val barCoverStart: Dp get() = pillStart + 48.dp

    /** Slot B (album + ⌄): 14dp after the bar cover (header breathing). */
    val slotBStart: Dp get() = pillStart + 102.dp

    companion object {
        val Phone = MemoriesBarInsets(16.dp, 12.dp)
        val Medium = MemoriesBarInsets(24.dp, 18.dp)
        val Spread = MemoriesBarInsets(32.dp, 26.dp)
    }
}

/** The budget's numbers (prototype layoutFor). Layout constants, not motion tokens. */
internal object MemoriesLayoutTokens {
    const val MediumFrom = 600f
    const val SpreadFrom = 900f
    const val ShortBelow = 760f

    /** Under this height the window is a phone in landscape (adaptive principle 1: height first). */
    const val LandscapeBelow = 480f

    const val PhoneCover = 256f
    const val PhoneCoverShort = 168f
    const val PhoneSeal = 96f
    const val PhoneSealShort = 72f
    const val PhoneAir1 = 59f
    const val PhoneAir1Short = 24f

    const val MediumCoverMax = 360f
    const val MediumCoverOffset = 580f
    const val MediumDiaryColumn = 640f
    const val MediumDiaryMargin = 32f
    const val MediumSealRatio = 0.375f
    const val MediumAir1Max = 120f
    const val MediumAir1Min = 24f
    const val MediumAir1Ratio = 0.2f

    /** Medium: the exhibit-to-teaser gap is capped here; the slack above it is shared top / bottom. */
    const val MediumAir2Max = 120f

    const val SealMin = 96f
    const val SealMinLadder = 72f
    const val SealMax = 124f

    const val SpreadLeftRatio = 0.46f
    const val SpreadLeftMin = 400f
    const val SpreadLeftMax = 600f

    /** Right page: 40 in from the left page, ≥ 24 at its end, 104 in all beside the measure. */
    const val SpreadGutters = 104f
    const val SpreadMeasureMin = 360f
    const val SpreadMeasureMax = 560f
    const val SpreadCoverMin = 300f
    const val SpreadCoverOfLeft = 96f
    const val SpreadCoverOfHeight = 386f
    const val SpreadSealRatio = 0.4f
    const val SpreadMinCover = 200f

    // the phone-landscape deviation
    const val LandscapeLeftMin = 280f
    const val LandscapeMeasureMin = 240f
    const val LandscapeMinCover = 88f
    const val LandscapeSealRatio = 0.55f
    const val LandscapeSealMin = 48f
    const val LandscapeSealMax = 72f

    val BarHeight: Dp = 64.dp
}

private fun clamp(x: Float, lo: Float, hi: Float): Float = min(hi, max(lo, x))

/** JavaScript's Math.round: half up (also for the .5 the prototype's sizes hit). */
private fun jsRound(x: Float): Float = floor(x + 0.5f)

/** Medium's cover at a height: the phone's at the same height up to 360. */
internal fun mediumCover(height: Float): Float = with(MemoriesLayoutTokens) {
    clamp(
        height - MediumCoverOffset,
        if (height < ShortBelow) PhoneCoverShort else PhoneCover,
        MediumCoverMax,
    )
}

/** The budget for a container of [width] × [height] (prototype layoutFor with tablet-portrait A). */
internal fun memoriesLayoutFor(width: Dp, height: Dp): MemoriesLayout = with(MemoriesLayoutTokens) {
    val w = width.value
    val h = height.value
    val short = h < ShortBelow
    if (h < LandscapeBelow && w >= MediumFrom) return landscapeSpread(w)
    if (w < MediumFrom) {
        return MemoriesLayout(
            tier = MemoriesTier.Phone,
            short = short,
            landscape = false,
            cover = (if (short) PhoneCoverShort else PhoneCover).dp,
            seal = (if (short) PhoneSealShort else PhoneSeal).dp,
            bar = MemoriesBarInsets.Phone,
            barHeight = BarHeight,
            air1 = (if (short) PhoneAir1Short else PhoneAir1).dp,
        )
    }
    val lp = clamp(jsRound(SpreadLeftRatio * w), SpreadLeftMin, SpreadLeftMax)
    val meas = w - lp - SpreadGutters
    val medium = mediumCover(h)
    val spreadCover = max(
        PhoneCoverShort,
        minOf(max(SpreadCoverMin, medium), lp - SpreadCoverOfLeft, h - SpreadCoverOfHeight),
    )
    // a spread whose left page can't hold Medium's cover at this height stays Medium: the bigger tier never
    // shows a smaller exhibit
    if (w < SpreadFrom || meas < SpreadMeasureMin || spreadCover < medium) {
        return MemoriesLayout(
            tier = MemoriesTier.Medium,
            short = short,
            landscape = false,
            cover = medium.dp,
            seal = jsRound(clamp(medium * MediumSealRatio, if (short) PhoneSealShort else SealMin, SealMax)).dp,
            bar = MemoriesBarInsets.Medium,
            barHeight = BarHeight,
            diaryColumn = min(MediumDiaryColumn, w - MediumDiaryMargin).dp,
            air1 = (
                if (short) {
                    MediumAir1Min
                } else {
                    clamp(jsRound((h - ShortBelow) * MediumAir1Ratio), MediumAir1Min, MediumAir1Max)
                }
                ).dp,
        )
    }
    return MemoriesLayout(
        tier = MemoriesTier.Spread,
        short = false,
        landscape = false,
        cover = spreadCover.dp,
        seal = jsRound(clamp(spreadCover * SpreadSealRatio, SealMin, SealMax)).dp,
        bar = MemoriesBarInsets.Spread,
        barHeight = BarHeight,
        leftPage = lp.dp,
        measure = clamp(meas, SpreadMeasureMin, SpreadMeasureMax).dp,
        minCover = SpreadMinCover.dp,
    )
}

/** Phone landscape: the spread's structure with a lower floor (deviation, see the file header). */
private fun landscapeSpread(w: Float): MemoriesLayout = with(MemoriesLayoutTokens) {
    val lp = clamp(jsRound(SpreadLeftRatio * w), LandscapeLeftMin, SpreadLeftMax)
    // the phone's short cover is the offer (crossing 600 never shrinks it); the ladder takes it down to 88
    val cover = PhoneCoverShort
    val layout = MemoriesLayout(
        tier = MemoriesTier.Spread,
        short = true,
        landscape = true,
        cover = cover.dp,
        seal = PhoneSealShort.dp,
        bar = MemoriesBarInsets.Spread,
        barHeight = BarHeight,
        leftPage = lp.dp,
        measure = clamp(w - lp - SpreadGutters, LandscapeMeasureMin, SpreadMeasureMax).dp,
        minCover = LandscapeMinCover.dp,
    )
    return layout.copy(seal = memoriesSealFor(layout, layout.cover))
}

/**
 * The emblem for a cover on the height ladder (prototype sealFor): the two states keep the budget's; the
 * spread follows the cover at .4, and below the budget's offer it may go under 96 (to 72), so a smaller
 * cover never carries a relatively bigger emblem. Landscape (deviation): .55 of the cover, 48–72.
 */
internal fun memoriesSealFor(layout: MemoriesLayout, cover: Dp): Dp = with(MemoriesLayoutTokens) {
    val c = cover.value
    when {
        layout.twoState -> layout.seal
        layout.landscape -> clamp(jsRound(c * LandscapeSealRatio), LandscapeSealMin, LandscapeSealMax).dp
        else -> jsRound(clamp(c * SpreadSealRatio, if (c < layout.cover.value) SealMinLadder else SealMin, SealMax)).dp
    }
}

// ---------------------------------------------------------------- Medium: balance the air

/**
 * Prototype balanceMedium: the gap between the exhibit and the teaser (air2) is capped at 120dp; the slack
 * above that — taken from the card with the LEAST, so every card keeps the same cover top and the same
 * button row — goes 1:1 to the top air and the bottom. [slacks] = each card's air2 at the budget's air1 and
 * bottom (dp). Returns (air1, bottom).
 */
internal fun balanceMedium(air1: Float, bottom: Float, slacks: List<Float>): Pair<Float, Float> {
    val least = slacks.minOrNull() ?: return air1 to bottom
    val extra = max(0f, least - MemoriesLayoutTokens.MediumAir2Max)
    return jsRound(air1 + extra / 2f) to jsRound(bottom + extra / 2f)
}

// ---------------------------------------------------------------- Spread: the height ladder

/** The spread's spacing steps (prototype `.ts-tight` / `.ts-tight2`): tighter spacing before a smaller cover. */
internal enum class SpreadTightness { Normal, Tight, Tight2 }

/** The spread left page's spacing at each step (dp). Layout constants, not motion tokens. */
@Immutable
internal data class SpreadSpacing(
    val padTop: Float,
    val padBottom: Float,
    val citeTop: Float,
    val paragraphTop: Float,
    val albumTop: Float,
    val goTop: Float,
) {
    companion object {
        fun of(t: SpreadTightness): SpreadSpacing = when (t) {
            SpreadTightness.Normal -> SpreadSpacing(24f, 56f, 40f, 14f, 18f, 30f)
            SpreadTightness.Tight -> SpreadSpacing(16f, 24f, 26f, 14f, 14f, 22f)
            SpreadTightness.Tight2 -> SpreadSpacing(12f, 24f, 20f, 10f, 10f, 14f)
        }
    }
}

/** What the ladder settled on: one cover for the whole deck, its emblem, the spacing, and the cover's top. */
@Immutable
internal data class SpreadFit(
    val cover: Dp,
    val seal: Dp,
    val tightness: SpreadTightness,
    /** From the left page's top (the bar's bottom) to every card's cover top (prototype `--htop`). */
    val coverTop: Dp,
    /** The ladder took the cover under the budget's offer. */
    val laddered: Boolean,
) {
    val spacing: SpreadSpacing get() = SpreadSpacing.of(tightness)
}

/**
 * Prototype fitCover + the spread's anchoring. [extentOf] is the TALLEST card's exhibit — cover top to the
 * bottom of Go to album — for a cover and a spacing step (dp, without the page's paddings); [room] the left
 * page's height under the bar; [navBottom] keeps the bottom padding clear of the navigation bar.
 *
 * First the spacing tightens, then the cover steps down (never below the floor); if the floor still
 * overflows, one more spacing step. Then every card's cover starts at the same y: the tallest card's
 * content, centred ([SpreadFit.coverTop]), so a swipe never moves the exhibit up or down.
 */
internal fun fitSpreadCover(
    layout: MemoriesLayout,
    room: Float,
    navBottom: Float = 0f,
    extentOf: (cover: Float, seal: Float, tightness: SpreadTightness) -> Float,
): SpreadFit {
    fun pads(t: SpreadTightness): Pair<Float, Float> {
        val s = SpreadSpacing.of(t)
        return s.padTop to max(s.padBottom, navBottom)
    }
    fun need(c: Float, t: SpreadTightness): Float {
        val (top, bottom) = pads(t)
        return top + extentOf(c, memoriesSealFor(layout, c.dp).value, t) + bottom
    }
    val offer = layout.cover.value
    val minCover = layout.minCover.value
    var cover = offer
    var tight = SpreadTightness.Normal
    var over = need(cover, tight) - room
    if (over > OverflowSlack) {
        tight = SpreadTightness.Tight
        over = need(cover, tight) - room
    }
    var steps = 0
    while (steps < LadderSteps && over > OverflowSlack && cover > minCover) {
        cover = max(minCover, floor(cover - over - 1f))
        over = need(cover, tight) - room
        steps++
    }
    if (over > OverflowSlack) tight = SpreadTightness.Tight2
    val (top, bottom) = pads(tight)
    val extent = extentOf(cover, memoriesSealFor(layout, cover.dp).value, tight)
    val coverTop = jsRound(top + max(0f, (room - top - bottom - extent) / 2f))
    return SpreadFit(
        cover = cover.dp,
        seal = memoriesSealFor(layout, cover.dp),
        tightness = tight,
        coverTop = coverTop.dp,
        laddered = cover != offer,
    )
}

private const val OverflowSlack = 0.5f
private const val LadderSteps = 4

/** One card's citation, measured at one spacing step (dp): what [spreadExtent] stacks under the cover. */
@Immutable
internal data class SpreadCiteHeights(
    val title: Float,
    /** Yoin's paragraph and question; null when there is none (or, landscape, when it opens the right page). */
    val paragraph: Float?,
    /** The album row: the album name and the artist line (fallback A: the artist line only). */
    val album: Float,
    /** Fallback A: the title already is the album name, so the row sits closer. */
    val albumOnlyArtist: Boolean,
)

/** Cover top → the bottom of Go to album for one card (the emblem's overhang is the exhibit's margin). */
internal fun spreadExtent(cover: Float, seal: Float, tightness: SpreadTightness, cite: SpreadCiteHeights): Float {
    val s = SpreadSpacing.of(tightness)
    val albumTop = if (cite.albumOnlyArtist && tightness == SpreadTightness.Normal) SpreadAlbumOnlyTop else s.albumTop
    return cover + seal * SealOverhangBottom + s.citeTop + cite.title +
        (cite.paragraph?.let { s.paragraphTop + it } ?: 0f) +
        albumTop + cite.album + s.goTop + SpreadGoHeight
}

/** The ladder over a deck: the tallest card decides, one cover for all ([fitSpreadCover]). */
internal fun fitSpreadDeck(
    layout: MemoriesLayout,
    room: Float,
    navBottom: Float,
    cards: List<(SpreadTightness) -> SpreadCiteHeights>,
): SpreadFit = fitSpreadCover(layout, room, navBottom) { c, s, t ->
    cards.maxOfOrNull { cite -> spreadExtent(c, s, t, cite(t)) } ?: (c + s * SealOverhangBottom)
}

private const val SealOverhangBottom = 0.24f
private const val SpreadAlbumOnlyTop = 8f
internal const val SpreadGoHeight = 48f

/**
 * Prototype placeSpread: where the right page's content starts, from the right page's top (dp). A page that
 * fits (content ≤ view − 160) sits centred on THIS card's optical centre line — (cover top + Go to album's
 * bottom) / 2; a longer one starts level with the cover's top; never closer than 24 to the bar.
 */
internal fun spreadRightTop(contentHeight: Float, viewHeight: Float, coverTop: Float, goBottom: Float): Float {
    val fits = contentHeight <= viewHeight - SpreadCentreClearance
    val top = if (fits) jsRound((coverTop + goBottom) / 2f - contentHeight / 2f) else coverTop
    return max(SpreadRightMinTop, top)
}

/** A right page shorter than the view by at least this much is centred. */
internal const val SpreadCentreClearance = 160f
internal const val SpreadRightMinTop = 24f

// ---------------------------------------------------------------- type sizes

/**
 * The type sizes that change between the phone and the large tiers (prototype `.ts-T-medium` / `.ts-lg`).
 * The card's sizes enlarge only on a Medium that is not short; the diary's on every large tier.
 */
@Immutable
internal data class MemoriesTypeScale(
    val cardTitleAi: TextUnit,
    val cardTitleMotif: TextUnit,
    val cardAlbum: TextUnit,
    val cardArtist: TextUnit,
    val diaryTitleAi: TextUnit,
    val diaryTitleMotif: TextUnit,
    val paragraph: TextUnit,
    val review: TextUnit,
    val reviewLine: Float,
    val reviewShort: TextUnit,
    val reviewShortLine: Float,
    val note: TextUnit,
    val stamp: TextUnit,
    val trackNumber: TextUnit,
    val linerColumn: Dp,
    val noteLine: Dp,
    /** The liner (score column included) stops here on a wide column; unbounded on the phone. */
    val linerMaxWidth: Dp,
    val separatorTop: Dp,
    val separatorBottom: Dp,
    val stat: TextUnit,
    /** The diary's side padding (the Medium column has its own 16). */
    val diarySide: Dp,
    /** A large tier (tablet sizes in the diary). */
    val large: Boolean,
) {
    companion object {
        val Phone = MemoriesTypeScale(
            cardTitleAi = 26.sp,
            cardTitleMotif = 24.sp,
            cardAlbum = 17.sp,
            cardArtist = 13.sp,
            diaryTitleAi = 22.sp,
            diaryTitleMotif = 21.sp,
            paragraph = 16.sp,
            review = 16.sp,
            reviewLine = 1.85f,
            reviewShort = 26.sp,
            reviewShortLine = 1.5f,
            note = 15.5.sp,
            stamp = 12.5.sp,
            trackNumber = 13.sp,
            linerColumn = 40.dp,
            noteLine = 25.6.dp,
            linerMaxWidth = Dp.Infinity,
            separatorTop = 30.dp,
            separatorBottom = 24.dp,
            stat = 34.sp,
            diarySide = 24.dp,
            large = false,
        )

        fun of(layout: MemoriesLayout): MemoriesTypeScale {
            val phoneCard = Phone.copy(cardTitleAi = if (layout.short) 24.sp else 26.sp)
            if (layout.tier == MemoriesTier.Phone) return phoneCard
            val bigCard = layout.tier == MemoriesTier.Medium && !layout.short
            return phoneCard.copy(
                cardTitleAi = if (bigCard) 30.sp else phoneCard.cardTitleAi,
                cardTitleMotif = if (bigCard) 27.sp else phoneCard.cardTitleMotif,
                cardAlbum = if (bigCard) 19.sp else phoneCard.cardAlbum,
                cardArtist = if (bigCard) 14.sp else phoneCard.cardArtist,
                diaryTitleAi = 24.sp,
                diaryTitleMotif = 23.sp,
                paragraph = 17.sp,
                review = 17.5.sp,
                reviewLine = 1.9f,
                reviewShort = 30.sp,
                reviewShortLine = 1.45f,
                note = 16.5.sp,
                stamp = 13.sp,
                linerColumn = 44.dp,
                noteLine = 27.dp,
                linerMaxWidth = 520.dp,
                separatorTop = 34.dp,
                separatorBottom = 26.dp,
                stat = 38.sp,
                diarySide = if (layout.tier == MemoriesTier.Medium) 16.dp else 0.dp,
                large = true,
            )
        }
    }
}

/** The showcase's type sizes for the tier it is laid out in (the phone's outside a showcase: previews). */
internal val LocalMemoriesType = staticCompositionLocalOf { MemoriesTypeScale.Phone }
