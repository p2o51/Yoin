package com.gpo.yoin.ui.detail

import android.os.SystemClock
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.component.YoinMarkHub
import com.gpo.yoin.ui.component.YoinMarkViewBox
import com.gpo.yoin.ui.component.YoinPageWidths
import com.gpo.yoin.ui.component.drawYoinArm
import com.gpo.yoin.ui.nowplaying.dotDissolve
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * The album page's cover → spectrum bar (专辑色谱顶栏, owner 2026-10-09; spec
 * artifact 1k9mD3vWBCBVZvDso35Csf). One rule for every size: when the cover
 * leaves view it turns into the bar; when it comes back the bar turns back
 * into the cover. Three things ride ONE progress (0 = cover, 1 = bar):
 *
 *   - the spectrum: a copy of the cover cut into strips that sort themselves
 *     into the tone-locked bands of [AlbumSpectrum], filling the header;
 *   - the butterfly: the two Yoin blocks behind the cover stay where they
 *     are and break up into fine dots as the cover leaves (the focus mode's
 *     dot dissolve; owner 2026-11-06, detail-art prototype v7);
 *   - the cover itself: only moves and shrinks, to a stamp at the bar's end.
 *
 * Who drives the progress depends on how the cover leaves: the pull-up
 * (RevealState) on Compact and landscape handsets, the page scroll on Medium.
 * Wide full windows keep the cover in their identity column and never form
 * the bar. Everything per-frame is read in layout / draw only.
 */

/**
 * Which layout the bar is morphing out of. [Short] is a Compact-width window
 * too short for the portrait hero ([albumHeroFitsPortrait]): the cover moves
 * to the start with the meta stacked beside it.
 */
internal enum class AlbumBarLayout { Compact, Short, Landscape, Medium }

/** A placement of the Yoin mark: box, layer scale + translationY about its centre, horizontal squash about the hub. */
@Immutable
internal data class MarkPose(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val scale: Float,
    val translationY: Float,
    val squashX: Float,
) {
    fun offsetBy(dx: Float, dy: Float) = copy(left = left + dx, top = top + dy)
}

internal fun lerp(a: MarkPose, b: MarkPose, t: Float) = MarkPose(
    left = lerpF(a.left, b.left, t),
    top = lerpF(a.top, b.top, t),
    width = lerpF(a.width, b.width, t),
    height = lerpF(a.height, b.height, t),
    scale = lerpF(a.scale, b.scale, t),
    translationY = lerpF(a.translationY, b.translationY, t),
    squashX = lerpF(a.squashX, b.squashX, t),
)

/**
 * Where each piece sits, in the album content box's px (origin top-left).
 * [cover] is the resting cover (Medium: at scroll 0); [bar] is page 0's header.
 */
@Immutable
internal data class AlbumBarGeometry(
    val layout: AlbumBarLayout,
    val bar: Rect,
    val cover: Rect,
    val stamp: Rect,
    val butterflyFrom: MarkPose,
)

// The resting Compact backdrop (was AlbumArrowBackground): the two lower arms
// scaled up about the cover band and squashed wide, so they bleed off the edges.
// 1.35 wide (was 1.8): on the swipe to the scrapbook the end wing runs on into
// that page whole, and only a little way (owner 2026-11-06).
private const val CompactArrowScale = 1.8f
private const val CompactArrowOffsetYFraction = -0.1f
private const val CompactArrowSquashX = 1.35f

// Landscape / Medium: the whole mark hugging the cover, 1.3 × its side, hanging below its top edge.
private const val HuggingMarkScale = 1.3f

// How far the arms reach from the mark's centre, as a fraction of its side (view box x 41…940 of 1024).
private const val HuggingArmReach = 0.46f
private val HuggingEdgeClearance = 8.dp

internal val AlbumBarStampSize = 48.dp
internal val AlbumBarLandscapeStampSize = 44.dp
private val AlbumBarStampEndInset = 16.dp

/** Compact hero cover side for a page [width] px wide. */
internal fun albumHeroCoverSidePx(width: Float, density: Density): Float =
    min(width * 0.74f, with(density) { 300.dp.toPx() })

/** Compact hero band: the cover plus the 56dp band behind it. */
internal fun albumHeroBandPx(width: Float, density: Density): Float =
    albumHeroCoverSidePx(width, density) + with(density) { 56.dp.toPx() }

// Short portrait (cover beside the meta): the cover starts on the titles' 16dp
// line, takes over 60% of the width, and sits under the header like landscape.
// Big on purpose: the row (and the meta beside it, narrower and so taller) pushes
// the flowing titles down until the last lines run under the bar, as on a
// portrait phone, instead of ending in a gap above it (owner 2026-10-28). The
// meta keeps at least [AlbumShortMetaMinWidth] so "Yesterday" and the stamp fit.
internal val AlbumShortCoverStart = 16.dp
internal val AlbumShortCoverMaxSide = 260.dp
internal val AlbumShortMetaMinWidth = 112.dp
// Cover start + gap to the meta + the page's end padding.
private val AlbumShortHeroHorizontalChrome = 48.dp
private const val AlbumShortCoverWidthFraction = 0.62f

/** Short-portrait cover side for a page [width] px wide. */
internal fun albumShortCoverSidePx(width: Float, density: Density): Float = with(density) {
    val besideMeta = width - (AlbumShortHeroHorizontalChrome + AlbumShortMetaMinWidth).toPx()
    minOf(width * AlbumShortCoverWidthFraction, besideMeta, AlbumShortCoverMaxSide.toPx())
}

// What the portrait hero needs besides the cover band, so at least one line of
// the flowing track titles shows above the bar. Chrome: status bar, header row,
// page dots, bottom bar + nav (≈ 24 + 62 + 25 + 104). Text: Last Play | score,
// comment, gaps, track count and one title line (≈ 146 + 36 + 26 + 40), which
// grows with the font scale.
private const val AlbumPortraitChromeReserveDp = 220f
private const val AlbumPortraitTextReserveDp = 250f

/**
 * The album page's height breakpoint on Compact width: whether a [widthDp] ×
 * [heightDp] container keeps the portrait hero (cover over meta over the
 * flowing titles) or turns it sideways ([AlbumBarLayout.Short]).
 *
 * Not a size-class line: the Compact hero's height grows with its width (the
 * cover is 0.74 × width, capped at 300), so what decides is the height left
 * once the cover band is taken. 390 × 844 keeps 499dp (portrait); a near-3:2
 * outer screen such as Fold 8's (~420 × 640) keeps ~284dp (short).
 */
internal fun albumHeroFitsPortrait(widthDp: Float, heightDp: Float, fontScale: Float = 1f): Boolean {
    val band = min(widthDp * 0.74f, 300f) + 56f
    val needed = AlbumPortraitChromeReserveDp + AlbumPortraitTextReserveDp * fontScale
    return heightDp - band >= needed
}

/**
 * The geometry for one layout. [titleRowTop] / [titleRowHeight] place the stamp
 * level with the title; [startInset] / [endInset] are the landscape capsule band
 * and cutout (logical start / end). Computed start-based, mirrored for RTL.
 */
internal fun albumBarGeometry(
    layout: AlbumBarLayout,
    width: Float,
    height: Float,
    headerHeight: Float,
    titleRowTop: Float,
    titleRowHeight: Float,
    startInset: Float,
    endInset: Float,
    rtl: Boolean,
    density: Density,
    // False in a detail column: its sides are not screen edges, so the Compact
    // blocks hug the cover whole instead of bleeding off the sides.
    pageEdgesAreScreenEdges: Boolean = true,
): AlbumBarGeometry = with(density) {
    val bar = Rect(0f, 0f, width, headerHeight)
    val stampSide = (if (layout == AlbumBarLayout.Landscape) AlbumBarLandscapeStampSize else AlbumBarStampSize).toPx()
    val stampStart = width - endInset - AlbumBarStampEndInset.toPx() - stampSide
    // Level with the title row where it settles: lifted into the bar (albumBarTitleLift).
    val stampTop = titleRowTop + (titleRowHeight - stampSide) / 2f - AlbumBarTitleLift.toPx()
    val coverStart: Float
    val coverTop: Float
    val coverSide: Float
    val fromPose: MarkPose
    when (layout) {
        AlbumBarLayout.Compact -> {
            coverSide = albumHeroCoverSidePx(width, density)
            val band = albumHeroBandPx(width, density)
            coverStart = (width - coverSide) / 2f
            coverTop = headerHeight + (band - coverSide) / 2f
            fromPose = if (pageEdgesAreScreenEdges) {
                MarkPose(
                    left = 0f,
                    top = headerHeight,
                    width = width,
                    height = band,
                    scale = CompactArrowScale,
                    translationY = CompactArrowOffsetYFraction * band,
                    squashX = CompactArrowSquashX,
                )
            } else {
                huggingPose(coverStart, coverTop, coverSide, HuggingMarkScale, HuggingEdgeClearance.toPx())
            }
        }
        AlbumBarLayout.Short, AlbumBarLayout.Landscape, AlbumBarLayout.Medium -> {
            if (layout == AlbumBarLayout.Short) {
                coverSide = albumShortCoverSidePx(width, density)
                coverStart = AlbumShortCoverStart.toPx()
                coverTop = headerHeight + 4.dp.toPx()
            } else if (layout == AlbumBarLayout.Landscape) {
                coverSide = min(AlbumLandscapeCoverSide.toPx(), height - headerHeight - 24.dp.toPx())
                coverStart = startInset + AlbumLandscapeCoverInset.toPx()
                coverTop = headerHeight + 4.dp.toPx()
            } else {
                coverSide = AlbumMediumHeroCoverSide.toPx()
                val column = min(width, YoinPageWidths.Feed.toPx())
                coverStart = (width - column) / 2f + 16.dp.toPx()
                coverTop = headerHeight + AlbumTrackListTopPadding.toPx()
            }
            // Short sits its cover on the 16dp line, too close to the edge for the
            // 8dp clearance to leave the mark its size (only a sliver showed past
            // the cover); on a screen edge its near arm may run to the edge itself.
            val clearance = if (layout == AlbumBarLayout.Short && pageEdgesAreScreenEdges) 0f else HuggingEdgeClearance.toPx()
            fromPose = huggingPose(coverStart, coverTop, coverSide, HuggingMarkScale, clearance)
        }
    }
    fun mirror(x: Float, w: Float) = if (rtl) width - x - w else x
    AlbumBarGeometry(
        layout = layout,
        bar = bar,
        cover = Rect(Offset(mirror(coverStart, coverSide), coverTop), Size(coverSide, coverSide)),
        stamp = Rect(Offset(mirror(stampStart, stampSide), stampTop), Size(stampSide, stampSide)),
        butterflyFrom = fromPose.copy(left = mirror(fromPose.left, fromPose.width)),
    )
}

/**
 * The whole mark hugging a cover: [scale] × its side, hanging below its top edge,
 * shrunk if needed so the near arm stays [clearance] clear of the page's start
 * edge (the arms reach ~46% of the mark's side out from its centre).
 */
private fun huggingPose(
    coverStart: Float,
    coverTop: Float,
    coverSide: Float,
    scale: Float,
    clearance: Float,
): MarkPose {
    val centreFromStart = coverStart + coverSide / 2f
    val mark = min(coverSide * scale, (centreFromStart - clearance) / HuggingArmReach)
    return MarkPose(
        left = centreFromStart - mark / 2f,
        top = coverTop + coverSide / 2f - mark / 2f + (mark - coverSide) / 2f,
        width = mark,
        height = mark,
        scale = 1f,
        translationY = 0f,
        squashX = 1f,
    )
}

/** Header text / icons hand over to white over this much of the morph. */
internal fun albumBarTextProgress(progress: Float): Float = smoothStep(0.25f, 0.7f, progress)

/**
 * As the bar forms, the header's title cluster rises [AlbumBarTitleLift] into it
 * and settles a touch smaller (top-start pivot, so the meta line under the title
 * comes up a little further), so the dense header reads as moving up into the
 * bar instead of only changing colour under it (owner 2026-11-07). Follows the
 * pull-up / scroll frame by frame; the stamp is placed level with where it lands.
 */
internal val AlbumBarTitleLift = 6.dp
internal const val AlbumBarTitleDockedScale = 0.9f

/** 0 = the header at rest, 1 = lifted into the formed bar. */
internal fun albumBarTitleLift(progress: Float): Float = smoothStep(0f, 1f, progress)

/** The whole header's content (title row and page dots) rises with the lift. Draw only. */
internal fun Modifier.albumBarHeaderLift(lift: () -> Float): Modifier = graphicsLayer {
    translationY = -AlbumBarTitleLift.toPx() * lift().coerceIn(0f, 1f)
}

/** The title and meta settle smaller about their top-start corner. Draw only. */
internal fun Modifier.albumBarTitleSettle(lift: () -> Float, rtl: Boolean): Modifier = graphicsLayer {
    val s = 1f - (1f - AlbumBarTitleDockedScale) * lift().coerceIn(0f, 1f)
    scaleX = s
    scaleY = s
    transformOrigin = TransformOrigin(if (rtl) 1f else 0f, 0f)
}

/** The bar counts as formed (status bar icons, stamp tap) past half way. */
internal const val AlbumBarDockedThreshold = 0.5f

/**
 * The cover's rect at [progress]: from where it rests in the page ([coverNow],
 * which moves with the scroll on Medium) to the stamp. Only moves and scales.
 */
internal fun albumBarCoverRect(geometry: AlbumBarGeometry, coverNow: Rect, progress: Float): Rect {
    val p = smoothStep(0f, 1f, progress)
    val side = lerpF(coverNow.width, geometry.stamp.width, p)
    return Rect(
        Offset(lerpF(coverNow.left, geometry.stamp.left, p), lerpF(coverNow.top, geometry.stamp.top, p)),
        Size(side, side),
    )
}

/**
 * The spectrum and the butterfly, drawn behind the page content and the header
 * text. [progress], [coverNow] and [pageShift] (page 0's horizontal offset, px)
 * are read while drawing only.
 */
@Composable
internal fun AlbumSpectrumLayer(
    geometry: () -> AlbumBarGeometry?,
    source: AlbumSpectrumSource,
    previous: AlbumSpectrum?,
    swap: () -> Float,
    blockA: Color,
    blockB: Color,
    surface: Color,
    reduced: Boolean,
    rtl: Boolean,
    progress: () -> Float,
    coverNow: () -> Rect,
    pageShift: () -> Float,
    modifier: Modifier = Modifier,
    // The Playlist page has its album sleeves instead of the two blocks
    // (PlaylistSleeves.kt); only the spectrum and the cover fly.
    butterfly: Boolean = true,
) {
    // A small tick as each band lands in the bar on the way up (owner 2026-11-07, as iOS: one per band).
    if (!reduced) {
        val haptics = rememberYoinHaptics()
        val currentProgress by rememberUpdatedState(progress)
        val currentGeometry by rememberUpdatedState(geometry)
        LaunchedEffect(source, haptics) {
            var landed = albumSpectrumLanded(source.spectrum, currentProgress())
            var lastTick = 0L
            snapshotFlow { albumSpectrumLanded(source.spectrum, currentProgress()) }.collect { now ->
                if (now > landed && currentGeometry() != null) {
                    val at = SystemClock.uptimeMillis()
                    // The bands can land a frame apart: one tick per frame or so, never a buzz.
                    if (at - lastTick >= SpectrumTickGapMillis) {
                        haptics.performFrequentTick()
                        lastTick = at
                    }
                }
                landed = now
            }
        }
    }
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val pageWidth = constraints.maxWidth.toFloat()
        if (butterfly) {
            // Two pages wide, reaching past page 0's end edge: the end wing runs
            // on into the scrapbook page whole as the pager carries it (owner
            // 2026-11-06: no straight cut at the page edge), and the dissolve's
            // offscreen layer has room for it.
            Spacer(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .wrapContentWidth(Alignment.Start, unbounded = true)
                    .requiredWidth(with(LocalDensity.current) { (pageWidth * 2f).toDp() })
                    .fillMaxHeight()
                    .graphicsLayer {
                        // Start-aligned, the layer already reaches past page 0's
                        // end edge in either direction (RTL: to the left).
                        translationX = -pageShift()
                        // Reduced motion: no dots, the blocks just fade.
                        if (reduced) alpha = 1f - albumButterflyLeaving(progress(), abs(pageShift()) / pageWidth)
                    }
                    .dotDissolve {
                        if (reduced) 0f else albumButterflyLeaving(progress(), abs(pageShift()) / pageWidth)
                    }
                    .drawBehind {
                        val g = geometry() ?: return@drawBehind
                        withTransform({ translate(left = if (rtl) pageWidth else 0f) }) {
                            drawButterfly(g, coverNow(), blockA, blockB, surface)
                        }
                    },
            )
        }
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    val g = geometry() ?: return@drawBehind
                    val p = progress().coerceIn(0f, 1f)
                    val shift = pageShift()
                    if (abs(shift) >= size.width) return@drawBehind
                    withTransform({ translate(left = -shift) }) {
                        drawSpectrum(g, source, previous, swap(), coverNow(), p, reduced, rtl)
                    }
                },
        )
    }
}

private const val SpectrumTickGapMillis = 22L

/** How many of [spectrum]'s bands sit in the bar at [progress] (each lands as its flight in [drawSpectrum] ends). */
internal fun albumSpectrumLanded(spectrum: AlbumSpectrum, progress: Float): Int {
    val p = progress.coerceIn(0f, 1f)
    val n = spectrum.columns
    if (n == 0 || p <= 0.001f) return 0
    var landed = 0
    for (x in 0 until n) if (p >= 0.6f + 0.22f * spectrum.target[x] / n) landed++
    return landed
}

/**
 * How far the butterfly has left (0 = whole, 1 = gone): with the pull-up, and on
 * the swipe to the scrapbook as that page comes to rest, so the wing that ran on
 * into it never stays behind there.
 */
internal fun albumButterflyLeaving(progress: Float, page: Float): Float =
    maxOf(smoothStep(0f, 0.7f, progress.coerceIn(0f, 1f)), smoothStep(0.45f, 1f, page.coerceIn(0f, 1f)))

/** The two blocks at rest, whole: below the header (the page's top edge), free to run past the page's sides. */
private fun DrawScope.drawButterfly(
    geometry: AlbumBarGeometry,
    coverNow: Rect,
    blockA: Color,
    blockB: Color,
    surface: Color,
) {
    // The resting pose follows the cover (Medium scrolls it).
    val pose = geometry.butterflyFrom.offsetBy(coverNow.left - geometry.cover.left, coverNow.top - geometry.cover.top)
    val line = surface.copy(alpha = 0.65f)
    clipRect(left = -size.width, top = geometry.bar.bottom, right = size.width * 2f, bottom = size.height) {
        withTransform({
            translate(pose.left, pose.top)
            val s = min(pose.width, pose.height) / YoinMarkViewBox
            translate(top = pose.translationY)
            scale(pose.scale, pose.scale, pivot = Offset(pose.width / 2f, pose.height / 2f))
            translate((pose.width - YoinMarkViewBox * s) / 2f, (pose.height - YoinMarkViewBox * s) / 2f)
            scale(s, s, pivot = Offset.Zero)
            scale(pose.squashX, 1f, pivot = YoinMarkHub)
        }) {
            drawYoinArm(0, blockA, line)
            drawYoinArm(2, blockB, line)
        }
    }
}

private fun DrawScope.drawSpectrum(
    geometry: AlbumBarGeometry,
    source: AlbumSpectrumSource,
    previous: AlbumSpectrum?,
    swap: Float,
    coverNow: Rect,
    progress: Float,
    reduced: Boolean,
    rtl: Boolean,
) {
    if (progress <= 0.001f) return
    val spectrum = source.spectrum
    val n = spectrum.columns
    val bar = geometry.bar
    val slotWidth = bar.width / n
    fun slotColor(x: Int): Color {
        val now = spectrum.color[x]
        if (previous == null || swap >= 1f) return now
        return lerp(previous.colorAtSlot(spectrum.target[x]), now, swap)
    }
    fun slotLeft(x: Int): Float {
        val slot = if (rtl) n - 1 - spectrum.target[x] else spectrum.target[x]
        return bar.left + slot * slotWidth
    }
    if (reduced) {
        // No flight: the bands fade in where they end up.
        for (x in 0 until n) {
            drawRect(slotColor(x), Offset(slotLeft(x), bar.top), Size(slotWidth + 1.5f, bar.height), alpha = progress)
        }
        return
    }
    val image = source.image
    for (x in 0 until n) {
        val lag = 0.22f * spectrum.target[x] / n
        val q = smoothStep(0.06f + lag, 0.6f + lag, progress)
        val w = lerpF(coverNow.width / n, slotWidth, q) + 1.5f
        val left = lerpF(coverNow.left + x * coverNow.width / n, slotLeft(x), q)
        val top = lerpF(coverNow.top, bar.top, q)
        val h = lerpF(coverNow.height, bar.height, q)
        val flat = smoothStep(0f, 0.55f, q)
        if (image != null && flat < 0.999f) {
            val srcLeft = x * image.width / n
            val srcRight = (x + 1) * image.width / n
            drawImage(
                image = image,
                srcOffset = IntOffset(srcLeft, 0),
                srcSize = IntSize((srcRight - srcLeft).coerceAtLeast(1), image.height),
                dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                dstSize = IntSize(ceil(w).toInt(), ceil(h).toInt()),
                alpha = 1f - flat,
            )
        }
        if (flat > 0f) drawRect(slotColor(x), Offset(left, top), Size(w, h), alpha = flat)
    }
}

/**
 * The cover on its flight: measured once at its resting size and moved and
 * scaled in its layer (no relayout per frame), its corner kept at the right
 * on-screen radius. Fills the content box; only the cover itself takes touches
 * (hit testing follows the layer, so a click on [content] lands on the stamp).
 */
@Composable
internal fun AlbumFlyingCover(
    geometry: () -> AlbumBarGeometry?,
    progress: () -> Float,
    coverNow: () -> Rect,
    pageShift: () -> Float,
    restingCorner: Dp,
    stampCorner: Dp,
    modifier: Modifier = Modifier,
    // Reduced motion: no flight — the cover fades out where it rests and the stamp fades in.
    reduced: Boolean = false,
    // Phone / short hero: on the swipe to the scrapbook the cover flies to the
    // page's cover instead of sliding away with page 0 (owner 2026-10-29) — from
    // the middle of the hero or, pulled up, from the stamp (owner 2026-11-06).
    pageFlight: AlbumPageFlight? = null,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val restingPx = with(density) { restingCorner.toPx() }
    val stampPx = with(density) { stampCorner.toPx() }
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val g = geometry()
        val side = g?.cover?.width?.roundToInt()?.coerceAtLeast(1) ?: 1
        val placeable = measurables.single().measure(Constraints.fixed(side, side))
        layout(constraints.maxWidth, constraints.maxHeight) {
            if (g == null) return@layout
            placeable.placeWithLayer(0, 0) {
                // Read the geometry again here, not the measure pass's copy: the stamp
                // follows the title row, which is placed after this cover, and when the
                // page arrives through Loading the measure pass is never re-run, so the
                // copy keeps a zero title row and the stamp sat half off the top.
                val now = geometry() ?: g
                val p = progress().coerceIn(0f, 1f)
                val r = if (reduced) {
                    if (p < 0.5f) coverNow() else now.stamp
                } else {
                    albumBarCoverRect(now, coverNow(), p)
                }
                if (reduced) alpha = abs(1f - 2f * p)
                val target = pageFlight?.coverTarget
                // From wherever the pull-up holds it (the hero's middle or the
                // stamp) to the scrapbook's cover, with the pager; back the same way.
                val toPage = if (target == null || !pageFlight.flies()) 0f else pageFlight.t()
                val flown = if (target == null || toPage <= 0f) {
                    r.translate(-pageShift(), 0f)
                } else {
                    lerpRect(r, target, toPage)
                }
                // Landed: the page's own cover (tilted, under its emblem) takes over.
                if (target != null && toPage >= 0.999f) alpha = 0f
                val scale = flown.width / side
                // Scale and tilt about the centre, so the tilt lands as the page holds it.
                transformOrigin = TransformOrigin.Center
                translationX = flown.left - side * (1f - scale) / 2f
                translationY = flown.top - side * (1f - scale) / 2f
                scaleX = scale
                scaleY = scale
                rotationZ = if (target == null) 0f else pageFlight.coverTargetTilt * toPage
                val corner = if (reduced) {
                    if (p < 0.5f) restingPx else stampPx
                } else {
                    lerpF(restingPx, stampPx, smoothStep(0f, 1f, p))
                }
                shape = RoundedCornerShape(corner / scale)
                clip = true
            }
        }
    }
}

/**
 * Status bar icons follow the bar in a full window: light over the formed
 * spectrum, the theme's default otherwise. A detail column leaves them alone
 * (the status bar belongs to the whole window, half of it over the shell), and
 * so does an open Now Playing: it covers the page, so the icons go back to the
 * theme's default with it (else a light Now Playing keeps the bar's white icons).
 */
@Composable
internal fun AlbumBarStatusBarEffect(enabled: Boolean, docked: () -> Boolean, darkTheme: Boolean) {
    if (!enabled) return
    val view = LocalView.current
    val window = view.context.findActivityOrNull()?.window ?: return
    val controller = remember(window, view) { WindowCompat.getInsetsController(window, view) }
    val currentDocked by rememberUpdatedState(docked)
    LaunchedEffect(controller, darkTheme) {
        snapshotFlow { currentDocked() }.collect { isDocked ->
            controller.isAppearanceLightStatusBars = !darkTheme && !isDocked
        }
    }
    DisposableEffect(controller, darkTheme) {
        onDispose { controller.isAppearanceLightStatusBars = !darkTheme }
    }
}

internal fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t
