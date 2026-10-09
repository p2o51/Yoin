package com.gpo.yoin.ui.component

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScrollModifierNode
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.TraversableNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.findNearestAncestor
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.node.requireView
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.theme.YoinMotion
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/*
 * Soft seams where scrolling content meets Yoin's own chrome (dissolve-final;
 * top seams re-decided 2026-10-04). Text, icons and numbers never become dots.
 *
 *  - TOP, under fixed chrome (Library's chips, a detail page's docked band):
 *    the user's choice in Settings › Motion ([SeamTopStyle]), text fading
 *    over its last max(10dp, 0.75 × its size) in every style:
 *     - Tide line (the default): as on Home's status bar (SeamTide.kt). Two
 *       waves cut the content away as a mask — whatever is really behind
 *       shows through, so it is right on accent-tinted detail pages too —
 *       and content sinks under the water line; text fades below the line.
 *     - Dots: the original curve C — graphics are whole until ~11dp from the
 *       seam at rest and gone exactly at it.
 *     - Cookie wave (SeamCookie.kt): each graphic's top edge is one wave lip
 *       that morphs Cookie → sine → SoftBurst over its exit.
 *  - BOTTOM, around the floating bar: a field. Graphics (covers, avatars,
 *    thumbnails, tinted cards) break into a halftone print anchored to the
 *    item: they start to open 20dp above the bar, finish the approach behind
 *    it, and run on under and beside it as a still lattice to the screen's
 *    bottom edge. Text passes under the bar untouched.
 *
 * Both breathe with scroll speed (静紧动松: taller waves / a wider field while
 * flung, back within the afterglow); the field settles onto its lattice at
 * rest (静整动乱, §1.7). Mark the scroll container with [seamDissolveViewport],
 * then opt in per element with [seamDissolve] (graphics) and [seamFade]
 * (text). Both are no-ops outside a viewport, so shared components carry them
 * everywhere.
 */

internal object SeamDissolveTokens {
    /**
     * Curve C band G (the Dots top seam): the dots go from whole print to
     * nothing over it. At rest / fully stretched. Also scales the flow's lag
     * cap ([FlowLag] × this), which every seam's afterglow reads.
     */
    val Band = 12.dp
    val BandStretched = 34.dp

    /** The front's per-item bend and per-dot jitter, F. */
    val Front = 3.dp
    val FrontStretched = 8.dp

    /** Text band T: text fades over its last T (or 0.75 × its size, if longer). */
    val TextBand = 10.dp
    val TextBandStretched = 26.dp
    const val TextSizeFraction = .75f

    /** Text is fully gone over the first 5% of its fade. */
    const val TextLead = .05f

    /** Whole-print dot radius, in cells: just over the staggered lattice's covering radius (0.577). */
    const val DotRadius = .64f
    const val DotGamma = .8f

    /** Scroll it takes for the top seam to come in from nothing at rest (and the field to shrink at the end). */
    val RevealDistance = 40.dp

    /** Same screen frequency as the cover-swap [ArtworkHalftone]. */
    val Pitch = 6.dp

    /** Most the halftone flow may trail the content, as a fraction of G ([Band]). */
    const val FlowLag = .5f

    /** Stretch = smoothstep((speed − onset) / span): nothing below 150dp/s, full from 1450dp/s. */
    const val StretchOnsetSpeed = 150f
    const val StretchSpanSpeed = 1300f

    /** The speed follower snaps to rest below this, with no new scroll (dp/s). */
    const val RestSpeed = 2f

    // ── Bottom field (§1.2) ──
    /** Where the approach starts: this far above the bar's top edge. */
    val FieldStart = 20.dp

    /** The rest of the approach is hidden behind the bar's top edge. */
    val FieldHidden = 28.dp

    /** A fling lifts the start this much further. */
    val FieldStretch = 20.dp

    /** The pure field's coverage (k0 = √(coverage × 0.866 / π) ≈ 0.332 cells). */
    const val FieldCoverage = .40f
    const val FieldApproachRadius = .62f

    /** The pure field thins to this share of k0 at the screen's bottom edge. */
    const val FieldThinning = .9f
    val FieldFront = 3.dp
    val FieldFrontNear = 10.dp
    val FieldFlowScale = 24.dp

    /** 退色: how far the dots ease toward the page colour at the bar's top and at the screen's edge. */
    const val FieldFadeAtBar = .16f
    const val FieldFadeAtEdge = .32f
    val FieldFadeOvershoot = 20.dp

    /** 按亮度让位: similar dots vanish 1.5dp from the bar and are whole again 7dp further out. */
    val YieldReach = 7.dp
    val YieldInset = 1.5.dp
    const val YieldFullDelta = 6f
    const val YieldNoneDelta = 18f

    /** Items whose bottom is within this of the window's bottom edge watch the field. */
    val FieldWatch = 240.dp

    // ── The tide line (§1.3, SeamTide.kt): Home's status bar, and the chrome seam's default ──
    /** The line rests this far below the status bar (or the chrome's seam) once the page has scrolled. */
    val TideRest = 2.dp

    /** Before the first 40dp of scroll the line hides this far above the screen. */
    val TideHidden = 8.dp
    val TideAmplitude = 2.4.dp
    val TideAmplitudeStretched = 3.2.dp
    val TideFrontWavelength = 72.dp
    val TideBackWavelength = 116.dp

    /** The back wave sits this much lower, a little taller and half clear. */
    val TideBackDrop = 6.dp
    const val TideBackAmplitude = 1.1f
    const val TideBackAlpha = .55f

    /** Scroll that moves the waves by one wavelength unit of phase. */
    val TidePhaseTravel = 88.dp
    const val TideHarmonic = .18f
    val TideStep = 3.dp

    // ── 曲奇浪口: the Cookie wave top seam (SeamCookie.kt) ──
    /** Band G at rest / fully stretched; below [CookieMinBand] (revealing) there is no lip. */
    val CookieBand = 12.dp
    val CookieBandStretched = 22.dp
    val CookieMinBand = .05.dp

    /** The front's mean depth below the seam, as a fraction of G. */
    const val CookieRest = .45f

    /** Lobes of about this width, at least [CookieMinLobes] per item. */
    val CookieLobe = 30.dp
    const val CookieMinLobes = 2

    /** Wave height at rest / fully stretched, capped at these fractions of G and of a lobe. */
    val CookieAmplitude = 4.dp
    val CookieAmplitudeStretched = 6.dp
    const val CookieAmplitudeOfBand = .34f
    const val CookieAmplitudeOfLobe = .2f

    /** The exit progress q starts this far before the item's top reaches the seam. */
    val CookieEntry = 12.dp

    /** The envelope E(q): in over q 0..[CookieEnvelopeIn], out over [CookieEnvelopeOut]..1. */
    const val CookieEnvelopeIn = .1f
    const val CookieEnvelopeOut = .9f

    /** Character p: Cookie at the start, SoftBurst at the end, morphing over q [CookieMorphFrom]..[CookieMorphTo]. */
    const val CookieCharacterFrom = .5f
    const val CookieCharacterTo = 1.8f
    const val CookieMorphFrom = .2f
    const val CookieMorphTo = .8f

    /** No tip sharper than this radius; ε lifts the notches off a cusp. */
    val CookieTipRadius = 4.dp
    const val CookieNotch = .08f

    /** Phase: a quarter roll per lobe over the exit, the afterglow's lag (in G) and a coherent row ripple × D. */
    const val CookieRoll = .25f
    const val CookieLagPhase = .5f
    const val CookieRipple = .12f
    val CookieRippleWavelength = 260.dp
    val CookieRippleTravel = 480.dp

    /** Squares' shoulders: .2G deep at the side edges, [CookieShoulderReach] wide, in over q 0..[CookieShoulderIn]. */
    const val CookieShoulder = .2f
    const val CookieShoulderIn = .15f
    val CookieShoulderReach = 10.dp

    /** The lip flattens as the item's own bottom edge comes within this of the rest line. */
    val CookieGuard = 16.dp

    /** The square silhouette's corner. */
    val CookieCorner = 4.dp

    /**
     * Where a square box's round item is told apart, as a fraction of its
     * width on both axes: outside a circle (its 45° edge is at .146w), inside
     * a square pressed to .97 and its 4dp / 8dp corner.
     */
    const val CookieProbe = .1f

    /** The front's anti-aliased half width. */
    val CookieEdge = .6.dp

    /** Tip veil: this far toward the page colour at the seam, gone at [CookieVeilDepth] × G. */
    const val CookieVeil = .5f
    const val CookieVeilDepth = .55f
}

/**
 * The seams' disorder D (静整动乱, dissolve-final §1.7): 0 at rest — every dot
 * on its lattice site — rising toward 1 while scrolling. The ONLY place D is
 * computed; the shader and the Path fallback both read it, and the rollback
 * switch ([YoinMotion.SeamSettleAtRest] = false → the previous round, D ≡ 1)
 * changes only this function.
 */
internal fun seamDisorder(
    speedDpPerSec: Float,
    settleAtRest: Boolean = YoinMotion.SeamSettleAtRest,
): Float = if (settleAtRest) {
    1f - exp(-max(0f, speedDpPerSec) / YoinMotion.SeamDisorderSpeed)
} else {
    1f
}

/** 静紧动松: 0 at rest and below 150dp/s, 1 from 1450dp/s. */
internal fun seamStretch(speedDpPerSec: Float): Float {
    val x = ((speedDpPerSec - SeamDissolveTokens.StretchOnsetSpeed) / SeamDissolveTokens.StretchSpanSpeed)
        .coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

/** Band growth with scroll: nothing at rest at the top, full after the reveal distance. */
internal fun seamReveal(scrolledPx: Float, revealDistancePx: Float): Float =
    (scrolledPx / revealDistancePx).coerceIn(0f, 1f)

/** Text alpha at [x] through its fade (0 at the seam): 1 − (1 − x′)², gone over the first 5%. */
internal fun seamTextAlpha(x: Float): Float {
    if (x <= SeamDissolveTokens.TextLead) return 0f
    val t = ((x - SeamDissolveTokens.TextLead) / (1f - SeamDissolveTokens.TextLead)).coerceAtMost(1f)
    return 1f - (1f - t) * (1f - t)
}

/**
 * The seams' scroll followers, one per viewport: the flow's afterglow lag and
 * the scroll speed behind stretch and disorder. Hoist it (pass it to
 * [seamDissolveViewport]) when something else draws on the same beat — Home's
 * tide line reads the same followers instead of running its own.
 */
@Stable
internal class SeamFlow {
    /** How far the halftone flow trails the content, in px. */
    var lagPx by mutableFloatStateOf(0f)
        internal set

    /** First-order follower of the scroll speed, dp/s (shares the afterglow's ω). */
    var speedDp by mutableFloatStateOf(0f)
        internal set

    /** Scroll travelled (px, content-up positive), for phases that follow the scroll. */
    var travelPx by mutableFloatStateOf(0f)
        internal set

    val stretch: Float get() = seamStretch(speedDp)
    val disorder: Float get() = seamDisorder(speedDp)

    /**
     * While true the followers ignore scrolling (Home's edit-mode fold): the
     * deltas still pass through, but feed no lag, speed or travel.
     */
    var held by mutableStateOf(false)

    internal fun reset() {
        lagPx = 0f
        speedDp = 0f
    }
}

/** The page colour behind a viewport, as a vertical gradient over the window (one colour = flat). */
@Immutable
internal class SeamBackground(val colors: List<Color>) {
    fun at(fraction: Float): Color {
        if (colors.size == 1) return colors[0]
        val position = fraction.coerceIn(0f, 1f) * (colors.size - 1)
        val index = floor(position).toInt().coerceAtMost(colors.size - 2)
        return lerp(colors[index], colors[index + 1], position - index)
    }

    override fun equals(other: Any?): Boolean = other is SeamBackground && other.colors == colors
    override fun hashCode(): Int = colors.hashCode()
}

/** What a viewport does at its top edge. */
internal enum class SeamTop {
    /**
     * Fixed chrome above (Library's chips, a detail page's docked band): the
     * top seam in the user's style ([SeamTopStyle]) — the tide line cut by the
     * viewport (the default), curve C dots, or the Cookie wave; text fades.
     */
    Chrome,

    /** Graphics pass under something else (Home's status-bar tide); only text fades. */
    FadeText,

    /** Nothing at the top. */
    None,
}

/**
 * Marks a scroll container. The top seam is this element's top edge (put it
 * on the scroll container itself), moved down by [topInset]. [scrolledPx] is
 * how far the content has scrolled from rest; it only needs to be exact up
 * to [SeamDissolveTokens.RevealDistance], so lazy states report their first
 * item's offset and saturate after it.
 *
 * The bottom field joins when [remainingPx] and [background] are given and
 * the window has a floating bar ([SeamBarField]): [remainingPx] is how far
 * the content can still scroll (the field's approach shrinks over the last
 * reveal distance), [background] the page colour the field eases toward.
 */
internal fun Modifier.seamDissolveViewport(
    top: SeamTop = SeamTop.Chrome,
    topInset: Dp = 0.dp,
    flow: SeamFlow? = null,
    background: SeamBackground? = null,
    remainingPx: (() -> Float)? = null,
    // Pins the chrome seam to one look instead of the user's choice — only for
    // previews of the styles themselves (Settings › Scroll edge, the landing).
    style: SeamTopStyle? = null,
    scrolledPx: () -> Float,
): Modifier = this then SeamViewportElement(top, topInset, flow, background, remainingPx, style, scrolledPx)

private data class SeamViewportElement(
    val top: SeamTop,
    val topInset: Dp,
    val flow: SeamFlow?,
    val background: SeamBackground?,
    val remainingPx: (() -> Float)?,
    val style: SeamTopStyle?,
    val scrolledPx: () -> Float,
) : ModifierNodeElement<SeamViewportNode>() {
    override fun create() = SeamViewportNode(top, topInset, flow, background, remainingPx, style, scrolledPx)
    override fun update(node: SeamViewportNode) {
        node.top = top
        node.styleOverride = style
        node.topInset = topInset
        node.useFlow(flow)
        node.background = background
        node.remainingPx = remainingPx
        node.scrolledPx = scrolledPx
    }
    override fun InspectorInfo.inspectableProperties() {
        name = "seamDissolveViewport"
    }
}

/**
 * Also owns the followers ([SeamFlow]). The dots' flow trails the content by
 * an amount proportional to scroll speed and, when the content stops, coasts
 * on at the speed it had and decays at the rate of
 * [YoinMotion.SeamFlowSettleStiffness]; the speed follower decays on the same
 * ω, so stretch, disorder and afterglow all end together. At rest nothing moves.
 */
private class SeamViewportNode(
    top: SeamTop,
    topInset: Dp,
    flow: SeamFlow?,
    background: SeamBackground?,
    var remainingPx: (() -> Float)?,
    style: SeamTopStyle?,
    var scrolledPx: () -> Float,
) : DelegatingNode(),
    TraversableNode,
    LayoutAwareModifierNode,
    DrawModifierNode,
    CompositionLocalConsumerModifierNode {
    override val traverseKey: Any get() = SeamViewportKey
    var coordinates: LayoutCoordinates? = null
        private set

    var top by mutableStateOf(top)
    var styleOverride by mutableStateOf(style)
    var topInset by mutableStateOf(topInset)
    var background by mutableStateOf(background)
    private var ownFlow: SeamFlow? = null
    var flow: SeamFlow = flow ?: SeamFlow().also { ownFlow = it }
        private set

    private var pendingDelta = 0f
    private var pendingTravel = 0f
    private var follower: Job? = null
    private val tidePath = Path()
    private val tidePaint = Paint()

    init {
        delegate(
            nestedScrollModifierNode(
                object : NestedScrollConnection {
                    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                        onContentScrolled(consumed.y)
                        return Offset.Zero
                    }
                },
                null,
            ),
        )
    }

    fun useFlow(hoisted: SeamFlow?) {
        val next = hoisted ?: ownFlow ?: SeamFlow().also { ownFlow = it }
        if (next !== flow) {
            flow.reset()
            flow = next
        }
    }

    val fieldEnabled: Boolean get() = remainingPx != null && background != null

    /** The window's bar, when this viewport takes the bottom field. */
    fun barField(): SeamBarField? = if (fieldEnabled && isAttached) currentValueOf(LocalSeamBarField) else null

    fun topInsetPx(): Float = with(requireDensity()) { topInset.toPx() }

    /**
     * The look of this viewport's chrome seam; null when it has none. Snapshot
     * reads (the user can change it in Settings at any time) — call it from
     * draw or layer blocks only.
     */
    val topStyle: SeamTopStyle? get() = if (top == SeamTop.Chrome) styleOverride ?: SeamTopPreference.style else null

    /** This node draws the tide line at its top and text fades below the line's rest. A snapshot read. */
    val tideActive: Boolean get() = topStyle == SeamTopStyle.Tide

    /** Where the seam line sits down the window (0..1), for the page colour behind it. */
    fun seamRootFraction(): Float {
        val coordinates = coordinates?.takeIf { it.isAttached } ?: return 0f
        val rootHeight = coordinates.findRootCoordinates().size.height
        if (rootHeight <= 0) return 0f
        return (coordinates.positionInRoot().y + topInsetPx()) / rootHeight
    }

    override fun onAttach() {
        // The View's context, not LocalContext: only the application context
        // is read, once per process.
        SeamTopPreference.ensureLoaded(requireView().context)
    }

    override fun onPlaced(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
    }

    // The tide as a mask, not paint: the band is drawn into a small layer and
    // the waves cut it (the back one half clear), so whatever is really behind
    // — Library's gradient, a detail page's accent wash — shows through.
    // Same as painting the page colour, without having to know it.
    override fun ContentDrawScope.draw() {
        if (!tideActive) {
            drawContent()
            return
        }
        val reveal = seamReveal(scrolledPx(), SeamDissolveTokens.RevealDistance.toPx())
        val reduced = reducedMotion()
        val amplitude = tideAmplitudePx(flow, reduced)
        val inset = topInset.toPx()
        // Unlike Home, nothing paints above the line here, so a crest over the
        // viewport top would leave a straight cut against the chrome: the rest
        // sits a crest lower. Hidden clears the back wave too, so reveal 0 is
        // no cut at all and the first scroll pixel adds none.
        val base = tideBase(
            restPx = inset + SeamDissolveTokens.TideRest.toPx() + tideCrestPx(amplitude),
            hiddenPx = max(SeamDissolveTokens.TideHidden.toPx(), tideDepthPx(amplitude) + 1f) - inset,
            reveal = reveal,
        )
        val depth = min(size.height, ceil(base + tideDepthPx(amplitude) + 1f))
        if (reveal <= 0f || depth <= 0f) {
            drawContent()
            return
        }
        // Below the band the content draws straight through (sideways bleed kept).
        val outside = max(size.width, size.height)
        clipRect(-outside, depth, size.width + outside, size.height + outside) {
            this@draw.drawContent()
        }
        drawIntoCanvas { it.saveLayer(Rect(0f, 0f, size.width, depth), tidePaint) }
        clipRect(0f, 0f, size.width, depth) { this@draw.drawContent() }
        drawTideWaves(tidePath, base, amplitude, tidePhase(flow, reduced)) { back ->
            drawPath(
                path = tidePath,
                color = Color.Black,
                alpha = if (back) SeamDissolveTokens.TideBackAlpha else 1f,
                blendMode = BlendMode.DstOut,
            )
        }
        drawIntoCanvas { it.restore() }
    }

    override fun onDetach() {
        coordinates = null
        pendingDelta = 0f
        pendingTravel = 0f
        follower = null
        flow.reset()
    }

    /** Power saving or "remove animations": no afterglow, no stretch, no disorder. */
    fun reducedMotion(): Boolean =
        currentValueOf(LocalMotionProfile) == MotionProfile.AdaptiveReduced ||
            coroutineScope.coroutineContext[MotionDurationScale]?.scaleFactor == 0f

    private fun onContentScrolled(delta: Float) {
        if (delta == 0f || !isAttached) return
        if (reducedMotion() || flow.held) return
        pendingDelta += delta
        pendingTravel += abs(delta)
        flow.travelPx -= delta
        if (follower?.isActive != true) follower = coroutineScope.launch { follow() }
    }

    // First-order followers on the frame clock, and the lag's and speed's
    // only owner: the flow trails the content by speed ÷ ω (capped), so when
    // the content stops it coasts on at exactly the speed it had and decays to
    // rest on the same curve — no hand-off, no stalled frame at the stop.
    private suspend fun follow() {
        val omega = sqrt(YoinMotion.SeamFlowSettleStiffness)
        var last = 0L
        while (true) {
            val now = withFrameNanos { it }
            // The first frame has no previous one: assume a 60Hz step, as the prototype does.
            val seconds = if (last == 0L) 1f / 60f else ((now - last) / 1e9f).coerceIn(.001f, .05f)
            last = now
            val delta = pendingDelta
            val travel = pendingTravel
            pendingDelta = 0f
            pendingTravel = 0f
            val density = requireDensity()
            val decay = exp(-omega * seconds)
            var speed = flow.speedDp + (travel / density.density / seconds - flow.speedDp) * (1f - decay)
            if (travel == 0f && speed < SeamDissolveTokens.RestSpeed) speed = 0f
            val cap = with(density) { bandAt(seamStretch(speed)).toPx() } * SeamDissolveTokens.FlowLag
            var lag = ((flow.lagPx - delta) * decay).coerceIn(-cap, cap)
            // Far below a pixel of dot travel, so the final snap is invisible.
            if (delta == 0f && abs(lag) < .05f) lag = 0f
            flow.lagPx = lag
            flow.speedDp = speed
            if (lag == 0f && speed == 0f) return
        }
    }
}

private fun bandAt(stretch: Float): Dp =
    lerp(SeamDissolveTokens.Band, SeamDissolveTokens.BandStretched, stretch)

private fun lerp(start: Dp, stop: Dp, fraction: Float): Dp = start + (stop - start) * fraction

private object SeamViewportKey
private object SeamHostKey

internal fun LazyGridState.seamScrolledPx(): Float =
    if (firstVisibleItemIndex > 0) Float.POSITIVE_INFINITY else firstVisibleItemScrollOffset.toFloat()

internal fun LazyListState.seamScrolledPx(): Float =
    if (firstVisibleItemIndex > 0) Float.POSITIVE_INFINITY else firstVisibleItemScrollOffset.toFloat()

/** How far the list can still scroll toward its end; only exact once the last item is laid out. */
internal fun LazyListState.seamRemainingPx(): Float {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return 0f
    if (last.index < info.totalItemsCount - 1) return Float.POSITIVE_INFINITY
    val end = last.offset + last.size
    return max(0f, (end - (info.viewportEndOffset - info.afterContentPadding)).toFloat())
}

internal fun LazyGridState.seamRemainingPx(): Float {
    val info = layoutInfo
    val items = info.visibleItemsInfo
    if (items.isEmpty()) return 0f
    if (items.none { it.index == info.totalItemsCount - 1 }) return Float.POSITIVE_INFINITY
    val end = items.maxOf { it.offset.y + it.size.height }
    return max(0f, (end - (info.viewportEndOffset - info.afterContentPadding)).toFloat())
}

internal fun ScrollState.seamRemainingPx(): Float = (maxValue - value).toFloat()

/** Artwork: dissolves into a halftone print in the bottom field, and takes the chrome seam's dots or lip. */
internal fun Modifier.seamDissolve(): Modifier = this then SeamPrintElement then SeamDissolveElement

/**
 * Text: fades out as it rises into a top seam (below the tide line under
 * fixed chrome), never breaks into dots, and passes under the bar untouched.
 * [fontSize] lengthens the fade for display type (0.75 × size), so a large
 * title fades instead of looking sliced.
 */
internal fun Modifier.seamFade(fontSize: TextUnit = TextUnit.Unspecified): Modifier =
    this then SeamFadeElement(fontSize)

private data object SeamPrintElement : ModifierNodeElement<SeamPrintNode>() {
    override fun create() = SeamPrintNode()
    override fun update(node: SeamPrintNode) = Unit
    override fun InspectorInfo.inspectableProperties() {
        name = "seamPrint"
    }
}

private data object SeamDissolveElement : ModifierNodeElement<SeamDissolveNode>() {
    override fun create() = SeamDissolveNode()
    override fun update(node: SeamDissolveNode) = Unit
    override fun InspectorInfo.inspectableProperties() {
        name = "seamDissolve"
    }
}

private data class SeamFadeElement(val fontSize: TextUnit) : ModifierNodeElement<SeamFadeNode>() {
    override fun create() = SeamFadeNode(fontSize)
    override fun update(node: SeamFadeNode) {
        if (node.fontSize != fontSize) {
            node.fontSize = fontSize
            node.invalidateDraw()
        }
    }
    override fun InspectorInfo.inspectableProperties() {
        name = "seamFade"
    }
}

/**
 * Tracks this element's place against the seams from placement callbacks,
 * so a scroll only redraws the few elements actually near one. Everything
 * else draws straight through, reading no state.
 */
private abstract class SeamNode :
    Modifier.Node(),
    DrawModifierNode,
    GlobalPositionAwareModifierNode {

    /** This element's top relative to the top seam line, px; NaN until placed. */
    protected var offsetFromSeam = Float.NaN
        private set

    /** This element's top-left in root (window) coordinates. */
    protected var rootLeft = 0f
        private set
    protected var rootTop = 0f
        private set
    protected var nearTop = false
        private set
    protected var nearField = false
        private set
    private var viewportLookedUp = false
    private var cachedViewport: SeamViewportNode? = null

    // Lazy items are subcomposed children of the container's layout node, so
    // the viewport is an ancestor in the node tree. Re-resolved per attach.
    protected fun viewport(): SeamViewportNode? {
        if (!viewportLookedUp) {
            cachedViewport = findNearestAncestor(SeamViewportKey) as? SeamViewportNode
            viewportLookedUp = true
        }
        return cachedViewport
    }

    /** How far below the top seam this element can still be touched by it, px. */
    open fun Density.topReach(): Float = 0f

    /** Whether this element takes the top seam at all, for the viewport's mode. */
    open fun takesTop(top: SeamTop): Boolean = false

    /** Whether this element takes the bottom field. */
    open val takesField: Boolean get() = false

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val viewport = viewport() ?: return
        val seam = viewport.coordinates ?: return
        if (!seam.isAttached || !coordinates.isAttached) return
        val position = coordinates.positionInRoot()
        val offset = seam.localPositionOf(coordinates, Offset.Zero).y - viewport.topInsetPx()
        if (offset == offsetFromSeam && position.x == rootLeft && position.y == rootTop) return
        val density = requireDensity()
        val top = takesTop(viewport.top) && offset < density.topReach()
        val field = takesField && viewport.fieldEnabled && run {
            val bottom = position.y + coordinates.size.height
            val rootHeight = coordinates.findRootCoordinates().size.height
            bottom > rootHeight - with(density) { SeamDissolveTokens.FieldWatch.toPx() }
        }
        val wasNear = nearTop || nearField
        offsetFromSeam = offset
        rootLeft = position.x
        rootTop = position.y
        nearTop = top
        nearField = field
        onNearChanged(top, field)
        if (wasNear || top || field) onMovedNear()
    }

    open fun onNearChanged(top: Boolean, field: Boolean) = Unit

    /** This element moved while near a seam (or just left one): redraw what depends on its place. */
    open fun onMovedNear() = invalidateDraw()

    override fun onDetach() {
        offsetFromSeam = Float.NaN
        nearTop = false
        nearField = false
        viewportLookedUp = false
        cachedViewport = null
    }
}

/**
 * Graphics. At the chrome seam they take the user's style: under the tide
 * the viewport masks them (nothing to do here); Dots break them into the
 * curve C print; the Cookie wave cuts each one's top edge into its lip. A
 * dissolving element nested inside another one (a cover on a tinted card)
 * defers to it: the card breaks up as one print. Text inside a dissolving
 * element ([seamFade]) is lifted out of the print and drawn on top of it, so
 * it never becomes dots.
 */
private class SeamDissolveNode :
    SeamNode(),
    LayoutModifierNode {
    // Varies the dissolve front between items; stable for the node's life.
    private val seed = (System.identityHashCode(this) % 1000) / 100f
    private var shader: Any? = null
    private var cookieShader: Any? = null

    // API 33+: the halftone is a RenderEffect on the content's own placement
    // layer, set from the layer block. A scroll only updates the effect (a
    // layer property): the content is not re-recorded, so its offscreen image
    // is kept and only the shader pass runs. Bumped whenever the element moves
    // near a seam; every other input is snapshot state read in the block.
    private val placed = mutableIntStateOf(0)
    private val shaderMode: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !SeamDissolveDebug.forcePathFallback
    private val mask = Path()
    private val dot = FloatArray(3)
    private val topSeam = SeamHalftone.Top()
    private val tail = SeamHalftone.Tail()
    private val lip = SeamCookie.Lip()
    private val cut = Path()
    private val fadePaint = Paint()
    private val lipPaint = Paint()

    /** This element's own print (the node just before it in the chain). */
    private var print: SeamPrintNode? = null

    /** Inside another dissolving element (a cover on a tinted card): that one breaks up for both. */
    private val deferred: Boolean get() = print?.outer != null

    override val takesField: Boolean get() = !deferred

    // Every style is tracked alike, so switching it in Settings repaints items
    // at rest: the style itself is read in the draw / layer block.
    override fun takesTop(top: SeamTop): Boolean = !deferred && top == SeamTop.Chrome

    override fun Density.topReach(): Float =
        (SeamDissolveTokens.BandStretched + SeamDissolveTokens.FrontStretched + SeamDissolveTokens.Pitch * 2).toPx()

    override fun onAttach() {
        print = findNearestAncestor(SeamHostKey) as? SeamPrintNode
    }

    override fun onNearChanged(top: Boolean, field: Boolean) {
        val print = print ?: return
        print.topViewport = viewport()
        if (print.liftForTop != top) print.liftForTop = top
        if (print.liftForField != field) print.liftForField = field
    }

    override fun onMovedNear() {
        if (shaderMode) placed.intValue++ else invalidateDraw()
    }

    override fun onDetach() {
        super.onDetach()
        print = null
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0, layerBlock = effectBlock)
        }
    }

    private val effectBlock: GraphicsLayerScope.() -> Unit = {
        val effect = if (shaderMode) shaderEffect(size) else null
        renderEffect = effect
        // Clipped to the element while the effect is on: a runtime-shader
        // effect may paint anywhere, so unclipped its output would grow to the
        // parent's clip — a viewport-sized offscreen image and shader pass per item.
        clip = effect != null
    }

    // Runs inside the placement layer (a layout node's own drawing belongs to
    // its content), so the lifted text is drawn by [SeamPrintNode] outside it.
    override fun ContentDrawScope.draw() {
        if (deferred || shaderMode) {
            drawContent()
        } else {
            val viewport = viewport()
            val top = if (viewport != null && nearTop) configureTop(viewport, size) else null
            val cookie = if (viewport != null && nearTop) configureCookie(viewport, size) else null
            val field = if (viewport != null && nearField) configureTail(viewport, size) else null
            when {
                top == null && cookie == null && field == null -> drawContent()
                // Wholly above the seam: already gone. (The viewport clips here too.)
                (top != null || cookie != null) && field == null && offsetFromSeam + size.height <= 0f -> Unit
                cookie != null -> drawWithLip(cookie, field, viewport?.flow?.disorder ?: 0f)
                else -> drawWithPath(
                    SeamHalftone.lattice(size.width, SeamDissolveTokens.Pitch.toPx()),
                    top,
                    field,
                    viewport?.flow?.disorder ?: 0f,
                )
            }
        }
    }

    /** Curve C, when the chrome seam is in the Dots style. */
    private fun Density.configureTop(viewport: SeamViewportNode, size: Size): SeamHalftone.Top? {
        if (viewport.topStyle != SeamTopStyle.Dots) return null
        val reveal = seamReveal(viewport.scrolledPx(), SeamDissolveTokens.RevealDistance.toPx())
        val stretch = viewport.flow.stretch
        val band = bandAt(stretch).toPx() * reveal
        if (band < .5f) return null
        topSeam.seam = -offsetFromSeam
        topSeam.band = band
        topSeam.front = lerp(SeamDissolveTokens.Front, SeamDissolveTokens.FrontStretched, stretch).toPx() * reveal
        topSeam.flowShift = viewport.flow.lagPx / band
        // Wholly below the seam's reach: untouched.
        return if (topSeam.seam + topSeam.solid + SeamDissolveTokens.Pitch.toPx() < 0f) null else topSeam
    }

    /** The lip, when the chrome seam is in the Cookie style. */
    private fun Density.configureCookie(viewport: SeamViewportNode, size: Size): SeamCookie.Lip? {
        if (viewport.topStyle != SeamTopStyle.Cookie) return null
        val reduced = viewport.reducedMotion()
        val flow = viewport.flow
        val touched = with(SeamCookie) {
            configure(
                lip = lip,
                seam = -offsetFromSeam,
                width = size.width,
                height = size.height,
                centreX = rootLeft + size.width / 2f,
                stretch = flow.stretch,
                disorder = flow.disorder,
                lagPx = flow.lagPx,
                travelPx = flow.travelPx,
                reveal = seamReveal(viewport.scrolledPx(), SeamDissolveTokens.RevealDistance.toPx()),
                reduced = reduced,
            )
        }
        if (!touched) return null
        val page = viewport.background
        lip.page = page?.at(viewport.seamRootFraction()) ?: Color.Transparent
        if (page == null) lip.veil = 0f
        return lip
    }

    private fun Density.configureTail(viewport: SeamViewportNode, size: Size): SeamHalftone.Tail? {
        val bar = viewport.barField() ?: return null
        val reveal = bar.reveal
        if (reveal <= .001f) return null
        val bounds = bar.bounds
        if (bounds.isEmpty || bar.rootHeight <= 0f) return null
        val remaining = viewport.remainingPx?.invoke() ?: return null
        val rest = seamReveal(remaining, SeamDissolveTokens.RevealDistance.toPx())
        val stretch = viewport.flow.stretch
        val hidden = SeamDissolveTokens.FieldHidden.toPx()
        val stretchPx = SeamDissolveTokens.FieldStretch.toPx() * stretch
        val length = (SeamDissolveTokens.FieldStart.toPx() + hidden + stretchPx) * rest
        val yb = bounds.top + hidden
        val y0 = yb - length
        val guard = bounds.top - (SeamDissolveTokens.YieldReach + SeamDissolveTokens.YieldInset).toPx() - 1.dp.toPx()
        // Wholly above the field and the bar's guard rows: untouched.
        val lattice = SeamHalftone.lattice(size.width, SeamDissolveTokens.Pitch.toPx())
        if (rootTop + size.height < min(y0, guard) - lattice.rowHeight * 2f) return null
        val page = viewport.background ?: return null
        val pageAtBar = page.at(bounds.top / bar.rootHeight)
        val k0 = SeamHalftone.radiusForCoverage(SeamDissolveTokens.FieldCoverage)
        tail.y0 = y0 - rootTop
        tail.yb = yb - rootTop
        tail.len = length
        tail.end = bar.rootHeight - rootTop
        tail.guard = guard - rootTop
        tail.k0 = k0
        tail.k1 = k0 * SeamDissolveTokens.FieldThinning
        tail.front = SeamDissolveTokens.FieldFront.toPx()
        tail.frontNear = SeamDissolveTokens.FieldFrontNear.toPx()
        tail.flowShift = viewport.flow.lagPx / SeamDissolveTokens.FieldFlowScale.toPx()
        tail.reveal = reveal
        tail.barLeft = bounds.left - rootLeft
        tail.barTop = bounds.top - rootTop
        tail.barRight = bounds.right - rootLeft
        tail.barBottom = bounds.bottom - rootTop
        tail.barRadius = bar.cornerRadius
        tail.barLum = if (bar.containerColor != Color.Unspecified) {
            SeamHalftone.lightness(bar.containerColor)
        } else {
            SeamHalftone.lightness(pageAtBar)
        }
        tail.bgLum = SeamHalftone.lightness(pageAtBar)
        tail.yieldReach = SeamDissolveTokens.YieldReach.toPx()
        tail.yieldInset = SeamDissolveTokens.YieldInset.toPx()
        tail.fadeEnd = bar.rootHeight + SeamDissolveTokens.FieldFadeOvershoot.toPx() - rootTop
        tailPage = lerp(pageAtBar, page.at(1f), .5f)
        return tail
    }

    private var tailPage = Color.Transparent

    /** The halftone (and the lip) as a RenderEffect for this element's layer; null when no seam touches it. */
    private fun GraphicsLayerScope.shaderEffect(size: Size): RenderEffect? {
        placed.intValue
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        if (deferred || size.isEmpty()) return null
        val viewport = viewport() ?: return null
        val top = if (nearTop) configureTop(viewport, size) else null
        val cookie = if (nearTop) configureCookie(viewport, size) else null
        val field = if (nearField) configureTail(viewport, size) else null
        if (top == null && cookie == null && field == null) return null
        val lattice = SeamHalftone.lattice(size.width, SeamDissolveTokens.Pitch.toPx())
        val halftone = if (top != null || field != null) {
            runtimeEffect(lattice, size, top, field, viewport.flow.disorder)
        } else {
            null
        }
        if (cookie == null) return halftone?.asComposeRenderEffect()
        val lipEffect = cookieEffect(size, cookie)
        // The lip cuts the print, when an item is in both seams at once.
        val effect = if (halftone != null) {
            android.graphics.RenderEffect.createChainEffect(lipEffect, halftone)
        } else {
            lipEffect
        }
        return effect.asComposeRenderEffect()
    }

    // One pass on the GPU: each pixel finds the dots that can reach it.
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun runtimeEffect(
        lattice: SeamHalftone.Lattice,
        size: Size,
        top: SeamHalftone.Top?,
        field: SeamHalftone.Tail?,
        disorder: Float,
    ): android.graphics.RenderEffect {
        val runtimeShader = shader as? RuntimeShader ?: RuntimeShader(SeamHalftone.AGSL).also { shader = it }
        runtimeShader.setFloatUniform("size", size.width, size.height)
        runtimeShader.setFloatUniform("cell", lattice.cellWidth, lattice.rowHeight)
        runtimeShader.setFloatUniform("seed", seed)
        runtimeShader.setFloatUniform("disorder", disorder)
        runtimeShader.setFloatUniform("minRadius", 1f)
        runtimeShader.setFloatUniform("topOn", if (top != null) 1f else 0f)
        runtimeShader.setFloatUniform("seam", top?.seam ?: 0f)
        runtimeShader.setFloatUniform("band", top?.band ?: 1f)
        runtimeShader.setFloatUniform("front", top?.front ?: 0f)
        runtimeShader.setFloatUniform("topFlow", top?.flowShift ?: 0f)
        val tail = field ?: NoTail
        runtimeShader.setFloatUniform("tailOn", if (field != null) 1f else 0f)
        runtimeShader.setFloatUniform("tailY0", tail.y0)
        runtimeShader.setFloatUniform("tailYb", tail.yb)
        runtimeShader.setFloatUniform("tailLen", tail.len)
        runtimeShader.setFloatUniform("tailEnd", tail.end)
        runtimeShader.setFloatUniform("tailGuard", tail.guard)
        runtimeShader.setFloatUniform("tailK", tail.k0, tail.k1)
        runtimeShader.setFloatUniform("tailFront", tail.front, max(1f, tail.frontNear))
        runtimeShader.setFloatUniform("tailFlow", tail.flowShift)
        runtimeShader.setFloatUniform("tailReveal", tail.reveal)
        runtimeShader.setFloatUniform("bar", tail.barLeft, tail.barTop, tail.barRight, tail.barBottom)
        runtimeShader.setFloatUniform("barRadius", tail.barRadius)
        runtimeShader.setFloatUniform("lums", tail.barLum, tail.bgLum)
        runtimeShader.setFloatUniform("yieldBand", max(1f, tail.yieldReach), tail.yieldInset)
        runtimeShader.setFloatUniform("fadeEnd", tail.fadeEnd)
        runtimeShader.setColorUniform("page", tailPage.toArgb())
        // The effect captures the uniforms as they are now.
        return android.graphics.RenderEffect.createRuntimeShaderEffect(runtimeShader, "content")
    }

    // The lip on the GPU: per band pixel one cos, one pow and the content's eval.
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun Density.cookieEffect(size: Size, lip: SeamCookie.Lip): android.graphics.RenderEffect {
        val runtimeShader = cookieShader as? RuntimeShader
            ?: RuntimeShader(SeamCookie.AGSL).also { cookieShader = it }
        runtimeShader.setFloatUniform("size", size.width, size.height)
        runtimeShader.setFloatUniform("seam", lip.seam)
        runtimeShader.setFloatUniform("rest", lip.rest)
        runtimeShader.setFloatUniform("lobes", lip.lobes)
        runtimeShader.setFloatUniform("amplitude", lip.amplitude)
        runtimeShader.setFloatUniform("character", lip.character)
        runtimeShader.setFloatUniform("phase", lip.phase)
        runtimeShader.setFloatUniform("shoulder", lip.shoulder)
        runtimeShader.setFloatUniform("shape", lip.shoulderReach, lip.guard, lip.corner, lip.edge)
        val probe = size.width * SeamDissolveTokens.CookieProbe
        runtimeShader.setFloatUniform("probe", probe, probe)
        runtimeShader.setFloatUniform("roundable", if (SeamCookie.roundable(size.width, size.height)) 1f else 0f)
        runtimeShader.setFloatUniform("veil", lip.veil, max(1f, lip.veilDepth))
        runtimeShader.setFloatUniform("reach", lip.reach)
        runtimeShader.setColorUniform("page", lip.page.toArgb())
        return android.graphics.RenderEffect.createRuntimeShaderEffect(runtimeShader, "content")
    }

    // Pre-33 lip: the same front as the shader, cut away with DstOut, and the
    // tip veil as a SrcAtop ramp onto the item's own pixels. It cannot read
    // the content, so every item keeps its shoulders; square boxes guard
    // with the ellipse (SeamCookie.buildCut).
    private fun ContentDrawScope.drawWithLip(lip: SeamCookie.Lip, field: SeamHalftone.Tail?, disorder: Float) {
        val outside = max(size.width, size.height)
        drawIntoCanvas { it.saveLayer(Rect(0f, 0f, size.width, size.height), lipPaint) }
        if (field != null) {
            drawWithPath(SeamHalftone.lattice(size.width, SeamDissolveTokens.Pitch.toPx()), null, field, disorder)
        } else {
            drawContent()
        }
        if (lip.veil > 0f) {
            drawRect(
                brush = Brush.verticalGradient(
                    colorStops = Array(LipVeilStops) { index ->
                        val u = index / (LipVeilStops - 1f)
                        u to lip.page.copy(alpha = SeamCookie.veil(lip, u * lip.veilDepth))
                    },
                    startY = lip.seam,
                    endY = lip.seam + lip.veilDepth,
                ),
                topLeft = Offset(0f, lip.seam),
                size = Size(size.width, lip.veilDepth),
                blendMode = BlendMode.SrcAtop,
            )
        }
        SeamCookie.buildCut(cut, lip, size.width, size.height, 1.dp.toPx(), outside)
        drawPath(cut, Color.Black, blendMode = BlendMode.DstOut)
        drawIntoCanvas { it.restore() }
    }

    // Pre-33 fallback: the same geometry as the shader, as a Path mask. It
    // cannot read the content's pixels, so it skips the lightness yield.
    private fun ContentDrawScope.drawWithPath(
        lattice: SeamHalftone.Lattice,
        top: SeamHalftone.Top?,
        field: SeamHalftone.Tail?,
        disorder: Float,
    ) {
        mask.rewind()
        val rows = ceil(size.height / lattice.rowHeight).toInt()
        val reach = lattice.rowHeight * 2f
        // Rows wholly clear of every seam merge into solid rects.
        val solidAbove = top?.let { it.seam + it.solid + reach } ?: Float.NEGATIVE_INFINITY
        val solidBelow = field?.let { min(it.y0, it.guard) - reach } ?: Float.POSITIVE_INFINITY
        val clearFrom = max(0f, solidAbove)
        val clearTo = min(size.height, solidBelow)
        if (clearTo > clearFrom) mask.addRect(Rect(0f, clearFrom, size.width, clearTo))
        val firstRow = if (top != null) floor(top.seam / lattice.rowHeight - 1.5f).toInt().coerceAtLeast(-1) else -1
        for (row in firstRow..rows) {
            // Rows whose dots lie wholly inside the solid rect add nothing.
            val centre = (row + .5f) * lattice.rowHeight
            if (centre - lattice.fullRadius >= clearFrom && centre + lattice.fullRadius <= clearTo) continue
            for (column in -1..lattice.columns) {
                SeamHalftone.dot(
                    lattice, size.width, size.height, column, row, seed, disorder, top, field, Float.NaN, dot,
                )
                val radius = dot[2]
                if (radius < 1f) continue
                // Wholly under the opaque bar: skipped, once the bar is fully there.
                if (field != null && field.reveal >= 1f &&
                    field.barDistance(dot[0], dot[1]) < -radius - .5.dp.toPx()
                ) {
                    continue
                }
                mask.addOval(Rect(dot[0] - radius, dot[1] - radius, dot[0] + radius, dot[1] + radius))
            }
        }
        if (field == null) {
            clipPath(mask) { this@drawWithPath.drawContent() }
            return
        }
        // 退色 over the dots only: the ramp is composited onto the item's own pixels.
        val bounds = Rect(0f, 0f, size.width, size.height)
        drawIntoCanvas { it.saveLayer(bounds, fadePaint) }
        clipPath(mask) {
            this@drawWithPath.drawContent()
            val from = field.y0
            val to = field.fadeEnd
            if (to > from) {
                val atBar = ((field.barTop - from) / (to - from)).coerceIn(0f, 1f)
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to tailPage.copy(alpha = 0f),
                        atBar to tailPage.copy(alpha = SeamDissolveTokens.FieldFadeAtBar * field.reveal),
                        1f to tailPage.copy(alpha = SeamDissolveTokens.FieldFadeAtEdge * field.reveal),
                        startY = from,
                        endY = to,
                    ),
                    topLeft = Offset(0f, max(0f, from)),
                    blendMode = BlendMode.SrcAtop,
                )
            }
        }
        drawIntoCanvas { it.restore() }
    }
}

/**
 * The outer half of a dissolving element, ahead of its placement layer: text
 * inside the print ([seamFade]) is lifted out of it and drawn here, whole, on
 * top of the dissolving layer — so text never becomes dots. Also the anchor
 * that nested prints and lifted text find by traversal.
 */
private class SeamPrintNode :
    Modifier.Node(),
    DrawModifierNode,
    TraversableNode {
    override val traverseKey: Any get() = SeamHostKey

    /** The print this one sits inside, if any: it defers to it. */
    var outer: SeamPrintNode? = null
        private set

    /** The element is near the chrome seam / the bottom field. */
    var liftForTop by mutableStateOf(false)
    var liftForField by mutableStateOf(false)

    /**
     * The print is breaking up: the text in it draws through [lifted]. Under
     * the tide it stays whole, so its text stays in it. Snapshot reads — read
     * in draw by both sides.
     */
    val lifting: Boolean get() = liftForField || (liftForTop && (topViewport?.topStyle ?: SeamTopPreference.style).liftsText)

    /** The viewport whose chrome seam sets [liftForTop] (a style preview pins its own look). */
    var topViewport: SeamViewportNode? = null
    val lifted = LinkedHashSet<SeamFadeNode>()

    override fun onAttach() {
        outer = findNearestAncestor(SeamHostKey) as? SeamPrintNode
    }

    override fun onDetach() {
        outer = null
        topViewport = null
        liftForTop = false
        liftForField = false
    }

    override fun ContentDrawScope.draw() {
        val lift = lifting
        drawContent()
        if (lift) drawLifted()
    }

    /** Text lifted out of this print, drawn whole on top of it at its own place. */
    private fun ContentDrawScope.drawLifted() {
        if (lifted.isEmpty()) return
        val own = requireLayoutCoordinates()
        for (text in lifted) {
            val textLayer = text.liftedLayer ?: continue
            if (!text.isAttached) continue
            val offset = own.localPositionOf(text.requireLayoutCoordinates(), Offset.Zero)
            translate(offset.x, offset.y) { drawLayer(textLayer) }
        }
    }
}

private class SeamFadeNode(var fontSize: TextUnit) : SeamNode() {
    private val paint = Paint()
    private var host: SeamPrintNode? = null
    var liftedLayer: GraphicsLayer? = null
        private set

    override fun takesTop(top: SeamTop): Boolean = top != SeamTop.None

    override fun Density.topReach(): Float =
        max(SeamDissolveTokens.TextBandStretched.toPx(), fontPx() * SeamDissolveTokens.TextSizeFraction) +
            SeamDissolveTokens.Pitch.toPx()

    private fun Density.fontPx(): Float = if (fontSize != TextUnit.Unspecified) fontSize.toPx() else 0f

    override fun onAttach() {
        // Lift out of the outermost print: a nested dissolving element defers to its own host.
        var print = findNearestAncestor(SeamHostKey) as? SeamPrintNode
        while (print?.outer != null) print = print.outer
        host = print?.also { it.lifted += this }
        // The layer exists before the print first draws lifted text: the print
        // can draw ahead of this node in the same frame, and a print that drew
        // no layer for it is not redrawn when the layer appears, so the text
        // would stay lifted out and never drawn (seen after Home's strip unfold).
        if (host != null && liftedLayer == null) liftedLayer = requireGraphicsContext().createGraphicsLayer()
        // The print draws in its own layer, outside the placement layer this text
        // lives in, so it is not redrawn when text joins or leaves it: text that
        // attaches under a print already lifting would stay undrawn, and text that
        // leaves would linger in the print's last recording.
        host?.invalidateDraw()
    }

    override fun onDetach() {
        super.onDetach()
        host?.lifted?.remove(this)
        host?.invalidateDraw()
        host = null
        liftedLayer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        liftedLayer = null
    }

    override fun ContentDrawScope.draw() {
        // Everything that needs the density is resolved here, outside any
        // recording: inside GraphicsLayer.record the scope's density is
        // redirected to the recording and must not be queried.
        val fade = fade()
        val host = host
        if (host != null && host.lifting) {
            // The print beneath is dissolving: draw into our own layer, which
            // the print draws whole on top of itself.
            val target = liftedLayer ?: requireGraphicsContext().createGraphicsLayer().also { liftedLayer = it }
            target.record { this@draw.drawFaded(fade) }
            return
        }
        drawFaded(fade)
    }

    /** Where this text fades into the top seam, in its own px; null when it is untouched. */
    private fun ContentDrawScope.fade(): TextFade? {
        val viewport = viewport()
        val offset = offsetFromSeam
        if (viewport == null || !nearTop || offset.isNaN()) return null
        val reveal = seamReveal(viewport.scrolledPx(), SeamDissolveTokens.RevealDistance.toPx())
        val stretch = viewport.flow.stretch
        val band = lerp(SeamDissolveTokens.TextBand, SeamDissolveTokens.TextBandStretched, stretch).toPx()
        val length = max(band, fontPx() * SeamDissolveTokens.TextSizeFraction) * reveal
        // Under the tide, text fades below the line, as on Home — moving down
        // with its reveal to the rest.
        val seam = -offset + if (viewport.tideActive) tideTextSeamPx(reveal) else 0f
        if (length < .5f || seam + length <= 0f) return null
        // A little slack so ascenders and marquee edges fade instead of clipping.
        val slack = 4.dp.toPx()
        return TextFade(Rect(-slack, -slack, size.width + slack, size.height + slack), seam, length)
    }

    private fun ContentDrawScope.drawFaded(fade: TextFade?) {
        if (fade == null) {
            drawContent()
            return
        }
        drawIntoCanvas { it.saveLayer(fade.bounds, paint) }
        drawContent()
        drawRect(
            brush = Brush.verticalGradient(
                colorStops = TextFadeStops,
                startY = fade.seam,
                endY = fade.seam + fade.length,
            ),
            topLeft = fade.bounds.topLeft,
            size = fade.bounds.size,
            blendMode = BlendMode.DstIn,
        )
        drawIntoCanvas { it.restore() }
    }
}

private class TextFade(val bounds: Rect, val seam: Float, val length: Float)

/** Stops of the Path fallback's tip veil (the shader evaluates it exactly). */
private const val LipVeilStops = 10

private val NoTail = SeamHalftone.Tail()

private val TextFadeStops: Array<Pair<Float, Color>> =
    floatArrayOf(0f, .05f, .12f, .2f, .3f, .42f, .56f, .72f, .86f, 1f)
        .map { x -> x to Color.Black.copy(alpha = seamTextAlpha(x)) }
        .toTypedArray()

/** Hooks for the visual QA harnesses (androidTest). */
internal object SeamDissolveDebug {
    /** Draw with the pre-33 Path mask even where the shader is available, to compare the two. */
    @Volatile
    var forcePathFallback: Boolean = false
}
