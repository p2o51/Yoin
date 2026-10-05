package com.gpo.yoin.ui.home.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.gpo.yoin.ui.experience.smoothstep
import kotlin.math.min

// The edit-mode plate. V1 is what ships (owner, 2026-10-05, H1: 「底板往外扩
// （手机 12dp，平板最多 24dp），块与块的间距从 18dp 拉到 32dp；货架收在底板里，
// 边缘渐隐」 — the clipped shelf is an accepted edit-mode-only exception to the
// no-mid-page-truncation rule). Production never provides the local, so it
// renders [HomePlateVariant.V1]; the other variants stay for the debug QA
// harness (MemoriesScreenshotActivity `--es plate …`) to compare against —
// V0 is the pre-decision plate byte for byte. Every per-frame value here is
// read in layout or draw.

/** Which edit-mode plate Home draws. */
internal enum class HomePlateVariant {
    /** The pre-2026-10-05 plate: an 8dp × 6dp outset; shelves still bleed to the screen edge. */
    V0,

    /**
     * Shipped. A roomier plate: it reaches out to 4dp short of the page margin
     * (at most [HomePlateTrial.V1MaxOutsetH]), the feed's item spacing grows
     * 18 → 32dp with P so the plate can take a 12dp vertical outset, and
     * bleeding shelves are clipped to the plate with a smoothstep edge fade
     * while editing. A block lifted by the entering long-press stays under the
     * finger while the spacing grows ([HomePlateSpacingAnchor]).
     */
    V1,

    /**
     * Inset content: the plate stays on the page margins and each block's
     * content is laid out [HomePlateTrial.V2Inset] narrower on each side
     * inside it; shelves end at the plate's inner edge with an edge fade.
     */
    V2,

    /** No plate: no fill; edit mode reads from the wiggle, the badges and a subtle tonal title row. */
    V3,
}

internal val LocalHomePlateVariant = staticCompositionLocalOf { HomePlateVariant.V1 }

/** The plates' numbers: V1's ship; V2's and V3's are only for the QA harness's comparison. */
internal object HomePlateTrial {
    /** V1: the feed's item spacing while editing. */
    val V1EditSpacing = 32.dp

    /** V1: the plate's vertical outset at full edit spacing. */
    val V1OutsetV = 12.dp

    /** V1: the plate stops this far short of the page margin… */
    val V1MarginKeep = 4.dp

    /** …and never reaches out further than this (a 56dp tablet margin would make a slab). */
    val V1MaxOutsetH = 24.dp

    /** V2: how far each block's content draws in from the page margins while editing. */
    val V2Inset = 12.dp

    /** V1 / V2: the clipped shelf fades over this much inside the plate's edges. */
    val ShelfFade = 12.dp

    /** V3: the tonal title row reaches this far above and below the title line. */
    val V3TitleRowPad = 6.dp
}

/**
 * One plate variant, resolved for the page: [progress] is the spacing's
 * progress (P, or [HomePlateSpacingAnchor]'s sample of it while a lifted block
 * is held), [pageMargin] the feed's live (narrower) side margin.
 * [V0][HomePlateVariant.V0] answers with the pre-decision plate's constants.
 */
@Stable
internal class HomePlateLook(
    val variant: HomePlateVariant,
    private val progress: () -> Float = { 0f },
    private val pageMargin: () -> Dp = { 16.dp },
) {
    /** The feed's item spacing for its resting [rest]: V1 grows it with P. Measure-time read. */
    fun spacing(rest: Dp): Dp = if (variant == HomePlateVariant.V1) spacingAt(rest, progress()) else rest

    /** The feed's item spacing for its resting [rest] at spacing progress [p] (V1; [rest] otherwise). Pure. */
    fun spacingAt(rest: Dp, p: Float): Dp = if (variant == HomePlateVariant.V1) {
        lerp(rest, maxOf(rest, HomePlateTrial.V1EditSpacing), smoothstep(0f, 1f, p.coerceIn(0f, 1f)))
    } else {
        rest
    }

    /** True while V1's spacing is between its two rests — feed placement lands at once then. */
    fun spacingMoving(): Boolean {
        if (variant != HomePlateVariant.V1) return false
        val p = progress()
        return p > SpacingRestEpsilon && p < 1f - SpacingRestEpsilon
    }

    /**
     * The plate's horizontal outset past its block (the feed's content width).
     * V2's plate sits exactly on the block — its content moves in instead.
     */
    fun plateOutsetH(): Dp = when (variant) {
        HomePlateVariant.V0, HomePlateVariant.V3 -> HomeEditTokens.PlateOutsetH
        HomePlateVariant.V1 -> (pageMargin() - HomePlateTrial.V1MarginKeep)
            .coerceIn(HomeEditTokens.PlateOutsetH, HomePlateTrial.V1MaxOutsetH)
        HomePlateVariant.V2 -> 0.dp
    }

    /** The plate's vertical outset for the feed's resting item spacing [rest]. */
    fun outsetV(rest: Dp): Dp = when (variant) {
        HomePlateVariant.V1 ->
            min(HomePlateTrial.V1OutsetV.value, (spacing(rest).value - HomeEditTokens.PlateMinGap.value) / 2f)
                .coerceAtLeast(0f).dp
        else -> plateOutsetVDp(rest.value).dp
    }

    /**
     * V2: how far a block's content draws in on each side at its [ripple]
     * (read only in V2, so other variants add no state read); 0 otherwise.
     */
    fun contentInset(ripple: () -> Float): Dp = if (variant == HomePlateVariant.V2) {
        HomePlateTrial.V2Inset * smoothstep(0f, 1f, ripple().coerceIn(0f, 1f))
    } else {
        0.dp
    }

    /** V1 / V2 clip a bleeding shelf to the plate while editing. */
    val clipsShelves: Boolean get() = variant == HomePlateVariant.V1 || variant == HomePlateVariant.V2

    /** V3 draws no plate fill at rest, only the tonal title row. */
    val titleRowOnly: Boolean get() = variant == HomePlateVariant.V3

    companion object {
        /** The shipped plate (V1) at rest, page margin 16dp: what previews and tests get unless they pass their own. */
        val Default = HomePlateLook(HomePlateVariant.V1)

        /** The pre-decision plate (V0): constant outsets, no spacing change. The QA harness's comparison. */
        val Legacy = HomePlateLook(HomePlateVariant.V0)

        private const val SpacingRestEpsilon = .0005f
    }
}

/**
 * The feed's vertical arrangement under V1: spaced by
 * [HomePlateLook.spacing], read by the list in its measure pass (a P frame
 * re-lays the feed, never recomposes it).
 */
@Stable
internal class HomePlateSpacing(private val look: HomePlateLook, private val rest: Dp) : Arrangement.Vertical {
    override val spacing: Dp get() = look.spacing(rest)

    // Arrangement.spacedBy's own placement (no alignment), at this frame's spacing.
    override fun Density.arrange(totalSize: Int, sizes: IntArray, outPositions: IntArray) {
        val gap = spacing.roundToPx()
        var occupied = 0
        sizes.forEachIndexed { index, size ->
            outPositions[index] = minOf(occupied, totalSize - size)
            val space = minOf(gap, totalSize - outPositions[index] - size)
            occupied = outPositions[index] + size + space
        }
    }

    override fun equals(other: Any?): Boolean = other is HomePlateSpacing && other.look === look && other.rest == rest

    override fun hashCode(): Int = System.identityHashCode(look) * 31 + rest.hashCode()
}

/**
 * Keeps a block lifted by the entering long-press under the finger while V1's
 * spacing grows. A LazyColumn holds its FIRST visible item in place, so every
 * gap above the held block that grows 18 → 32dp would push the block that much
 * further down from under the finger (14dp under Activities at the top of the
 * page, 28dp under Jump Back In).
 *
 * While [hold] runs (Home runs it from the entering lift until the finger
 * lifts, a carry starts or P comes to rest), each frame callback samples P for
 * the spacing and, in the same callback, scrolls the feed forward by exactly
 * what the gaps between the first visible item and the held block grow, so the
 * measure that follows lays the held block out where it was: the blocks above
 * spread upward and the ones below downward, in one motion with the plates.
 * Spacing and scroll come from one sample, so they agree whatever order the
 * frame's callbacks run in (at worst the spacing trails P by a frame). Outside
 * a hold the spacing reads P directly; the hand-back is seamless because the
 * sample is P's value of at most a frame before.
 */
@Stable
internal class HomePlateSpacingAnchor {
    private var sampled by mutableFloatStateOf(Float.NaN)

    /** True while a hold runs: its scroll keeps the layout, it isn't the reader's (seams ignore it). */
    var holding: Boolean by mutableStateOf(false)
        private set

    /** The spacing's progress for P [live]: the hold's sample while one runs. A layout-time read. */
    fun progress(live: Float): Float = sampled.let { if (it.isNaN()) live else it }

    /**
     * Holds until cancelled. Each frame it samples [live] (P) and scrolls the
     * feed by [scrollBy] (px, positive = forward) the growth of [spacingPx]
     * (the spacing in whole px at a progress, as the list rounds it) across
     * [gapsAbove] gaps.
     */
    suspend fun hold(
        live: () -> Float,
        spacingPx: (progress: Float) -> Int,
        gapsAbove: () -> Int,
        scrollBy: (px: Float) -> Unit,
    ) {
        try {
            sampled = live()
            holding = true
            while (true) {
                withFrameNanos {
                    val from = spacingPx(sampled)
                    val next = live()
                    // The sample first: a scroll that remeasures at once must see the new spacing.
                    sampled = next
                    val scroll = anchorScrollPx(from = from, to = spacingPx(next), gaps = gapsAbove())
                    if (scroll != 0f) scrollBy(scroll)
                }
            }
        } finally {
            sampled = Float.NaN
            holding = false
        }
    }
}

/**
 * The forward scroll that keeps a block [gaps] gaps below the list's first
 * visible item in place while the spacing goes [from] → [to] px.
 */
internal fun anchorScrollPx(from: Int, to: Int, gaps: Int): Float = if (gaps > 0) ((to - from) * gaps).toFloat() else 0f

/**
 * A bleeding shelf, clipped to its block's plate while editing (plates V1 /
 * V2): past the plate the shelf fades out over
 * [HomePlateTrial.ShelfFade] inside its edges, smoothstep-eased, by as much as
 * the block's ripple has come in. [escapeStart] / [escapeEnd] are how far the
 * shelf reaches past its block's content on each side (the feed margins it
 * bleeds over). Without a [scope], or in V0 / V3, nothing changes. A DstIn
 * mask on an offscreen layer, only while there is something to mask.
 */
internal fun Modifier.homeEditShelfClip(
    scope: HomeEditCardScope?,
    escapeStart: () -> Dp,
    escapeEnd: () -> Dp,
): Modifier {
    if (scope == null || !scope.plate.clipsShelves) return this
    val look = scope.plate
    fun strength(): Float = smoothstep(0f, 1f, scope.motion.ripple(scope.section).coerceIn(0f, 1f))
    return this
        .graphicsLayer {
            compositingStrategy = if (strength() > 0f) CompositingStrategy.Offscreen else CompositingStrategy.Auto
        }
        .drawWithContent {
            drawContent()
            val ripple = scope.motion.ripple(scope.section)
            val m = smoothstep(0f, 1f, ripple.coerceIn(0f, 1f))
            if (m <= 0f || size.width <= 0f) return@drawWithContent
            // The plate in shelf px: the block's content ± the plate's outset
            // past it (V2: the content's own inset).
            val outset = (look.plateOutsetH() + look.contentInset { ripple }).toPx()
            val left = escapeStart().toPx() - outset
            val right = size.width - escapeEnd().toPx() + outset
            val fade = HomePlateTrial.ShelfFade.toPx()
            drawRect(
                brush = Brush.horizontalGradient(
                    colorStops = plateMaskStops(left, right, fade, size.width, outside = 1f - m),
                    startX = 0f,
                    endX = size.width,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
}

/**
 * Mask stops across a [width]-wide shelf: [outside] alpha beyond the plate
 * ([left]..[right]), 1 inside it, eased up over [fade] from each plate edge.
 * Stops are clamped into 0..1 and kept in order.
 */
internal fun plateMaskStops(
    left: Float,
    right: Float,
    fade: Float,
    width: Float,
    outside: Float,
): Array<Pair<Float, Color>> {
    val ramp = fade.coerceAtMost(((right - left) / 2f).coerceAtLeast(0f))
    val stops = mutableListOf(0f to outside)
    for (i in 0..MaskRampSteps) {
        val t = i / MaskRampSteps.toFloat()
        stops += (left + ramp * t) to (outside + (1f - outside) * smoothstep(0f, 1f, t))
    }
    for (i in MaskRampSteps downTo 0) {
        val t = i / MaskRampSteps.toFloat()
        stops += (right - ramp * t) to (outside + (1f - outside) * smoothstep(0f, 1f, t))
    }
    stops += width to outside
    var last = 0f
    return stops.map { (x, alpha) ->
        val fraction = (x / width).coerceIn(last, 1f)
        last = fraction
        fraction to Color.Black.copy(alpha = alpha.coerceIn(0f, 1f))
    }.toTypedArray()
}

private const val MaskRampSteps = 6
