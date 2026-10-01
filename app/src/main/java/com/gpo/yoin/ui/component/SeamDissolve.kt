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
 * Soft seams where scrolling content meets Yoin's own chrome (dissolve-final).
 * Nothing is painted over a seam: each item carries its own mask. Graphics
 * (covers, avatars, thumbnails, tinted cards) break into a halftone print
 * anchored to the item; text, icons and numbers never become dots.
 *
 *  - TOP, under fixed chrome (Library's chips, a detail page's docked band):
 *    curve C — graphics are whole until ~11dp from the seam at rest and gone
 *    exactly at it; text fades over its last max(10dp, 0.75 × its size).
 *  - BOTTOM, around the floating bar: a field. Graphics start to open 20dp
 *    above the bar, finish the approach behind it, and run on under and
 *    beside it as a still lattice to the screen's bottom edge. Text passes
 *    under the bar untouched.
 *
 * Both breathe with scroll speed (静紧动松: wider while flung, back within the
 * afterglow) and both settle onto the lattice at rest (静整动乱, §1.7).
 * Mark the scroll container with [seamDissolveViewport], then opt in per
 * element with [seamDissolve] (graphics) and [seamFade] (text). Both are no-ops
 * outside a viewport, so shared components carry them everywhere.
 */

internal object SeamDissolveTokens {
    /** Curve C band G: the dots go from whole print to nothing over it. At rest / fully stretched. */
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

    /** Scroll it takes for the top band to grow from nothing at rest (and the field to shrink at the end). */
    val RevealDistance = 40.dp

    /** Same screen frequency as the cover-swap [ArtworkHalftone]. */
    val Pitch = 6.dp

    /** Most the halftone flow may trail the content, as a fraction of G. */
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

    // ── Home's tide line (§1.3, SeamTide.kt) ──
    /** The line rests this far below the status bar once the page has scrolled. */
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
    /** Fixed chrome above: graphics dissolve (curve C), text fades. */
    Dissolve,

    /** Graphics pass under something else (Home's tide); only text fades. */
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
    top: SeamTop = SeamTop.Dissolve,
    topInset: Dp = 0.dp,
    flow: SeamFlow? = null,
    background: SeamBackground? = null,
    remainingPx: (() -> Float)? = null,
    scrolledPx: () -> Float,
): Modifier = this then SeamViewportElement(top, topInset, flow, background, remainingPx, scrolledPx)

private data class SeamViewportElement(
    val top: SeamTop,
    val topInset: Dp,
    val flow: SeamFlow?,
    val background: SeamBackground?,
    val remainingPx: (() -> Float)?,
    val scrolledPx: () -> Float,
) : ModifierNodeElement<SeamViewportNode>() {
    override fun create() = SeamViewportNode(top, topInset, flow, background, remainingPx, scrolledPx)
    override fun update(node: SeamViewportNode) {
        node.top = top
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
    var scrolledPx: () -> Float,
) : DelegatingNode(),
    TraversableNode,
    LayoutAwareModifierNode,
    CompositionLocalConsumerModifierNode {
    override val traverseKey: Any get() = SeamViewportKey
    var coordinates: LayoutCoordinates? = null
        private set

    var top by mutableStateOf(top)
    var topInset by mutableStateOf(topInset)
    var background by mutableStateOf(background)
    private var ownFlow: SeamFlow? = null
    var flow: SeamFlow = flow ?: SeamFlow().also { ownFlow = it }
        private set

    private var pendingDelta = 0f
    private var pendingTravel = 0f
    private var follower: Job? = null

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

    override fun onPlaced(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
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
        if (reducedMotion()) return
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

/** Artwork: dissolves into a halftone print at the seams. */
internal fun Modifier.seamDissolve(): Modifier = this then SeamPrintElement then SeamDissolveElement

/**
 * Text: fades out as it rises into a top seam, never breaks into dots, and
 * passes under the bar untouched. [fontSize] lengthens the fade for display
 * type (0.75 × size), so a large title fades instead of looking sliced.
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
    abstract fun Density.topReach(): Float

    /** Whether this element takes the top seam at all, for the viewport's mode. */
    abstract fun takesTop(top: SeamTop): Boolean

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
        onNearChanged(top || field)
        if (wasNear || top || field) onMovedNear()
    }

    open fun onNearChanged(near: Boolean) = Unit

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
 * Graphics. A dissolving element nested inside another one (a cover on a
 * tinted card) defers to it: the card breaks up as one print. Text inside a
 * dissolving element ([seamFade]) is lifted out of the print and drawn on top
 * of it, so it never becomes dots.
 */
private class SeamDissolveNode :
    SeamNode(),
    LayoutModifierNode {
    // Varies the dissolve front between items; stable for the node's life.
    private val seed = (System.identityHashCode(this) % 1000) / 100f
    private var shader: Any? = null

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
    private val fadePaint = Paint()

    /** This element's own print (the node just before it in the chain). */
    private var print: SeamPrintNode? = null

    /** Inside another dissolving element (a cover on a tinted card): that one breaks up for both. */
    private val deferred: Boolean get() = print?.outer != null

    override val takesField: Boolean get() = !deferred

    override fun takesTop(top: SeamTop): Boolean = !deferred && top == SeamTop.Dissolve

    override fun Density.topReach(): Float =
        (SeamDissolveTokens.BandStretched + SeamDissolveTokens.FrontStretched + SeamDissolveTokens.Pitch * 2).toPx()

    override fun onAttach() {
        print = findNearestAncestor(SeamHostKey) as? SeamPrintNode
    }

    override fun onNearChanged(near: Boolean) {
        val print = print ?: return
        if (print.lifting != near) print.lifting = near
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
            val field = if (viewport != null && nearField) configureTail(viewport, size) else null
            when {
                top == null && field == null -> drawContent()
                // Wholly above the seam: already gone. (The viewport clips here too.)
                top != null && field == null && offsetFromSeam + size.height <= 0f -> Unit
                else -> drawWithPath(
                    SeamHalftone.lattice(size.width, SeamDissolveTokens.Pitch.toPx()),
                    top,
                    field,
                    viewport?.flow?.disorder ?: 0f,
                )
            }
        }
    }

    private fun Density.configureTop(viewport: SeamViewportNode, size: Size): SeamHalftone.Top? {
        if (viewport.top != SeamTop.Dissolve) return null
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

    /** The halftone as a RenderEffect for this element's layer; null when no seam touches it. */
    private fun GraphicsLayerScope.shaderEffect(size: Size): RenderEffect? {
        placed.intValue
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        if (deferred || size.isEmpty()) return null
        val viewport = viewport() ?: return null
        val top = if (nearTop) configureTop(viewport, size) else null
        val field = if (nearField) configureTail(viewport, size) else null
        if (top == null && field == null) return null
        val lattice = SeamHalftone.lattice(size.width, SeamDissolveTokens.Pitch.toPx())
        return runtimeEffect(lattice, size, top, field, viewport.flow.disorder)
    }

    // One pass on the GPU: each pixel finds the dots that can reach it.
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun runtimeEffect(
        lattice: SeamHalftone.Lattice,
        size: Size,
        top: SeamHalftone.Top?,
        field: SeamHalftone.Tail?,
        disorder: Float,
    ): RenderEffect {
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
        return android.graphics.RenderEffect
            .createRuntimeShaderEffect(runtimeShader, "content")
            .asComposeRenderEffect()
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

    /** The element is near a seam: the text in it draws through [lifted]. Read in draw by both sides. */
    var lifting by mutableStateOf(false)
    val lifted = LinkedHashSet<SeamFadeNode>()

    override fun onAttach() {
        outer = findNearestAncestor(SeamHostKey) as? SeamPrintNode
    }

    override fun onDetach() {
        outer = null
        lifting = false
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
    }

    override fun onDetach() {
        super.onDetach()
        host?.lifted?.remove(this)
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
        val seam = -offset
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

private val TextFadeStops: Array<Pair<Float, Color>> =
    floatArrayOf(0f, .05f, .12f, .2f, .3f, .42f, .56f, .72f, .86f, 1f)
        .map { x -> x to Color.Black.copy(alpha = seamTextAlpha(x)) }
        .toTypedArray()

private val NoTail = SeamHalftone.Tail()

/** Hooks for the visual QA harnesses (androidTest). */
internal object SeamDissolveDebug {
    /** Draw with the pre-33 Path mask even where the shader is available, to compare the two. */
    @Volatile
    var forcePathFallback: Boolean = false
}
