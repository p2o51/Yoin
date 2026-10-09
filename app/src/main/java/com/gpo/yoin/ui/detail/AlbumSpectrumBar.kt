package com.gpo.yoin.ui.detail

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
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
import com.gpo.yoin.ui.component.YoinMarkHub
import com.gpo.yoin.ui.component.YoinMarkViewBox
import com.gpo.yoin.ui.component.YoinPageWidths
import com.gpo.yoin.ui.component.drawYoinArm
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
 *   - the butterfly: the two Yoin blocks behind the cover fly up with the
 *     finger, take the bands' colours and melt into the bar;
 *   - the cover itself: only moves and shrinks, to a stamp at the bar's end.
 *
 * Who drives the progress depends on how the cover leaves: the pull-up
 * (RevealState) on Compact and landscape handsets, the page scroll on Medium.
 * Wide full windows keep the cover in their identity column and never form
 * the bar. Everything per-frame is read in layout / draw only.
 */

/** Which layout the bar is morphing out of. */
internal enum class AlbumBarLayout { Compact, Landscape, Medium }

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
    val butterflyTo: MarkPose,
)

// The resting Compact backdrop (was AlbumArrowBackground): the two lower arms
// scaled up about the cover band and squashed wide, so they bleed off the edges.
private const val CompactArrowScale = 1.8f
private const val CompactArrowOffsetYFraction = -0.1f
private const val CompactArrowSquashX = 1.8f

// Landscape / Medium: the whole mark hugging the cover, 1.3 × its side, hanging below its top edge.
private const val HuggingMarkScale = 1.3f

// How far the arms reach from the mark's centre, as a fraction of its side (view box x 41…940 of 1024).
private const val HuggingArmReach = 0.46f
private val HuggingEdgeClearance = 8.dp

// The butterfly's end pose: scaled and squashed until the arms fill the bar.
private const val BarArrowScale = 2.9f
private const val BarArrowSquashX = 2.4f
private const val LandscapeBarArrowScale = 3.2f

internal val AlbumBarStampSize = 48.dp
internal val AlbumBarLandscapeStampSize = 44.dp
private val AlbumBarStampEndInset = 16.dp

/** Compact hero cover side for a page [width] px wide. */
internal fun albumHeroCoverSidePx(width: Float, density: Density): Float =
    min(width * 0.74f, with(density) { 300.dp.toPx() })

/** Compact hero band: the cover plus the 56dp band behind it. */
internal fun albumHeroBandPx(width: Float, density: Density): Float =
    albumHeroCoverSidePx(width, density) + with(density) { 56.dp.toPx() }

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
    val stampTop = titleRowTop + (titleRowHeight - stampSide) / 2f
    val toPose = MarkPose(
        left = 0f,
        top = 0f,
        width = width,
        height = headerHeight,
        scale = if (layout == AlbumBarLayout.Landscape) LandscapeBarArrowScale else BarArrowScale,
        translationY = if (layout == AlbumBarLayout.Landscape) 2.dp.toPx() else 4.dp.toPx(),
        squashX = if (layout == AlbumBarLayout.Landscape) LandscapeBarArrowScale else BarArrowSquashX,
    )
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
        AlbumBarLayout.Landscape, AlbumBarLayout.Medium -> {
            if (layout == AlbumBarLayout.Landscape) {
                coverSide = min(AlbumLandscapeCoverSide.toPx(), height - headerHeight - 24.dp.toPx())
                coverStart = startInset + AlbumLandscapeCoverInset.toPx()
                coverTop = headerHeight + 4.dp.toPx()
            } else {
                coverSide = AlbumMediumHeroCoverSide.toPx()
                val column = min(width, YoinPageWidths.Feed.toPx())
                coverStart = (width - column) / 2f + 16.dp.toPx()
                coverTop = headerHeight + AlbumTrackListTopPadding.toPx()
            }
            fromPose = huggingPose(coverStart, coverTop, coverSide, HuggingMarkScale, HuggingEdgeClearance.toPx())
        }
    }
    fun mirror(x: Float, w: Float) = if (rtl) width - x - w else x
    AlbumBarGeometry(
        layout = layout,
        bar = bar,
        cover = Rect(Offset(mirror(coverStart, coverSide), coverTop), Size(coverSide, coverSide)),
        stamp = Rect(Offset(mirror(stampStart, stampSide), stampTop), Size(stampSide, stampSide)),
        butterflyFrom = fromPose.copy(left = mirror(fromPose.left, fromPose.width)),
        butterflyTo = toPose,
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
) {
    Spacer(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                val g = geometry() ?: return@drawBehind
                val p = progress().coerceIn(0f, 1f)
                val shift = pageShift()
                if (abs(shift) >= size.width) return@drawBehind
                withTransform({ translate(left = -shift) }) {
                    val now = coverNow()
                    drawButterfly(g, source.spectrum, now, p, blockA, blockB, surface, reduced)
                    drawSpectrum(g, source, previous, swap(), now, p, reduced, rtl)
                }
            },
    )
}

private fun DrawScope.drawButterfly(
    geometry: AlbumBarGeometry,
    spectrum: AlbumSpectrum,
    coverNow: Rect,
    progress: Float,
    blockA: Color,
    blockB: Color,
    surface: Color,
    reduced: Boolean,
) {
    val fade = if (reduced) progress else smoothStep(0.62f, 0.98f, progress)
    if (fade >= 1f) return
    val travel = if (reduced) 0f else smoothStep(0f, 1f, progress)
    // The resting pose follows the cover (Medium scrolls it).
    val from = geometry.butterflyFrom.offsetBy(coverNow.left - geometry.cover.left, coverNow.top - geometry.cover.top)
    val pose = lerp(from, geometry.butterflyTo, travel)
    val tint = if (reduced) 0f else smoothStep(0.15f, 0.7f, progress)
    val armA = lerp(blockA, spectrum.colorAt(0.22f), tint)
    val armB = lerp(blockB, spectrum.colorAt(0.78f), tint)
    val line = lerp(surface.copy(alpha = 0.65f), Color.White.copy(alpha = 0.25f), travel)
    // At rest the blocks stay below the header (the page's top edge); flying, they may enter it.
    val clipTop = geometry.bar.bottom * (1f - smoothStep(0.05f, 0.35f, travel))
    val clipBottom = lerpF(size.height, geometry.bar.bottom, smoothStep(0.2f, 0.85f, travel))
    clipRect(top = clipTop, bottom = clipBottom) {
        withTransform({
            translate(pose.left, pose.top)
            val s = min(pose.width, pose.height) / YoinMarkViewBox
            translate(top = pose.translationY)
            scale(pose.scale, pose.scale, pivot = Offset(pose.width / 2f, pose.height / 2f))
            translate((pose.width - YoinMarkViewBox * s) / 2f, (pose.height - YoinMarkViewBox * s) / 2f)
            scale(s, s, pivot = Offset.Zero)
            scale(pose.squashX, 1f, pivot = YoinMarkHub)
        }) {
            drawYoinArm(0, armA, line, alpha = 1f - fade)
            drawYoinArm(2, armB, line, alpha = 1f - fade)
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
                val p = progress().coerceIn(0f, 1f)
                val r = if (reduced) {
                    if (p < 0.5f) coverNow() else g.stamp
                } else {
                    albumBarCoverRect(g, coverNow(), p)
                }
                if (reduced) alpha = abs(1f - 2f * p)
                val scale = r.width / side
                transformOrigin = TransformOrigin(0f, 0f)
                translationX = r.left - pageShift()
                translationY = r.top
                scaleX = scale
                scaleY = scale
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
 * (the status bar belongs to the whole window, half of it over the shell).
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
