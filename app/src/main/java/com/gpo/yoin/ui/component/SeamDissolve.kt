package com.gpo.yoin.ui.component

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScrollModifierNode
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.TraversableNode
import androidx.compose.ui.node.findNearestAncestor
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.theme.YoinMotion
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/*
 * Soft seam where scrolling content meets fixed chrome above it (Library's
 * filter chips over its grids and lists). Nothing is painted over the seam:
 * each item carries its own mask. Artwork breaks into a halftone print that
 * travels with the artwork and is gone exactly at the seam line; text only
 * fades. Mark the scroll container with [seamDissolveViewport], then opt in
 * per element with [seamDissolve] (graphics) and [seamFade] (text) — both are
 * no-ops outside a viewport, so shared components can carry them everywhere.
 */

internal object SeamDissolveTokens {
    /** Height over which artwork goes from whole print to nothing. */
    val Band = 36.dp

    /** Scroll it takes for the band to grow from nothing at rest. */
    val RevealDistance = 40.dp

    /** Same screen frequency as the cover-swap [ArtworkHalftone]. */
    val Pitch = 6.dp

    /** Text is gone at the seam and whole again this far into the band. */
    const val TextFadeFraction = .8f

    /** Most the halftone flow may trail the content, as a fraction of [Band]. */
    const val FlowLag = .5f
}

/**
 * The seam is this element's top edge (put it on the scroll container itself).
 * [scrolledPx] is how far the content has scrolled from rest; it only needs to
 * be exact up to [SeamDissolveTokens.RevealDistance], so lazy states report
 * their first item's offset and saturate after it.
 */
internal fun Modifier.seamDissolveViewport(scrolledPx: () -> Float): Modifier =
    this then SeamViewportElement(scrolledPx)

private data class SeamViewportElement(val scrolledPx: () -> Float) : ModifierNodeElement<SeamViewportNode>() {
    override fun create() = SeamViewportNode(scrolledPx)
    override fun update(node: SeamViewportNode) {
        node.scrolledPx = scrolledPx
    }
    override fun InspectorInfo.inspectableProperties() {
        name = "seamDissolveViewport"
    }
}

/**
 * Also owns the halftone's afterglow. The dots' flow follows the content with
 * a little inertia — it trails by an amount proportional to scroll speed and,
 * when the content stops, coasts on at the speed it had and decays at the
 * rate of [YoinMotion.SeamFlowSettleStiffness]. At rest it is perfectly still.
 */
private class SeamViewportNode(var scrolledPx: () -> Float) :
    DelegatingNode(),
    TraversableNode,
    LayoutAwareModifierNode,
    CompositionLocalConsumerModifierNode {
    override val traverseKey: Any get() = SeamViewportKey
    var coordinates: LayoutCoordinates? = null
        private set

    /** How far the flow trails the content, in px; read by items in draw. */
    var flowLagPx by mutableFloatStateOf(0f)
        private set
    private var pendingDelta = 0f
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

    override fun onPlaced(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
    }

    override fun onDetach() {
        coordinates = null
        flowLagPx = 0f
        pendingDelta = 0f
        follower = null
    }

    private fun onContentScrolled(delta: Float) {
        if (delta == 0f || !isAttached) return
        if (currentValueOf(LocalMotionProfile) == MotionProfile.AdaptiveReduced) return
        if (coroutineScope.coroutineContext[MotionDurationScale]?.scaleFactor == 0f) return
        pendingDelta += delta
        if (follower?.isActive != true) follower = coroutineScope.launch { follow() }
    }

    // A first-order follower on the frame clock, and the lag's only owner:
    // the flow trails the content by speed ÷ ω (capped), so when the content
    // stops it coasts on at exactly the speed it had and decays to rest on the
    // same curve — no hand-off, no stalled frame at the stop.
    private suspend fun follow() {
        val omega = sqrt(YoinMotion.SeamFlowSettleStiffness)
        var last = 0L
        while (true) {
            val now = withFrameNanos { it }
            val seconds = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(.05f)
            last = now
            val delta = pendingDelta
            pendingDelta = 0f
            val cap = flowLagCapPx()
            val next = ((flowLagPx - delta) * exp(-omega * seconds)).coerceIn(-cap, cap)
            // Far below a pixel of dot travel, so the final snap is invisible.
            if (delta == 0f && abs(next) < .05f) {
                flowLagPx = 0f
                return
            }
            flowLagPx = next
        }
    }

    private fun flowLagCapPx(): Float = with(requireDensity()) {
        SeamDissolveTokens.Band.toPx() * SeamDissolveTokens.FlowLag
    }
}

private object SeamViewportKey

internal fun LazyGridState.seamScrolledPx(): Float =
    if (firstVisibleItemIndex > 0) Float.POSITIVE_INFINITY else firstVisibleItemScrollOffset.toFloat()

internal fun LazyListState.seamScrolledPx(): Float =
    if (firstVisibleItemIndex > 0) Float.POSITIVE_INFINITY else firstVisibleItemScrollOffset.toFloat()

/** Artwork: dissolves into a halftone print as it rises into the seam. */
internal fun Modifier.seamDissolve(): Modifier = this then SeamDissolveElement

/** Text: fades out as it rises into the seam, never breaks into dots. */
internal fun Modifier.seamFade(): Modifier = this then SeamFadeElement

private data object SeamDissolveElement : ModifierNodeElement<SeamDissolveNode>() {
    override fun create() = SeamDissolveNode()
    override fun update(node: SeamDissolveNode) = Unit
    override fun InspectorInfo.inspectableProperties() {
        name = "seamDissolve"
    }
}

private data object SeamFadeElement : ModifierNodeElement<SeamFadeNode>() {
    override fun create() = SeamFadeNode()
    override fun update(node: SeamFadeNode) = Unit
    override fun InspectorInfo.inspectableProperties() {
        name = "seamFade"
    }
}

/**
 * Tracks this element's distance to the seam from placement callbacks, so a
 * scroll only redraws the few elements actually inside the band. Everything
 * else draws straight through.
 */
private abstract class SeamNode :
    Modifier.Node(),
    DrawModifierNode,
    GlobalPositionAwareModifierNode {

    /** This element's top relative to the seam line, in px; NaN until placed. */
    private var offsetFromSeam = Float.NaN
    private var viewportLookedUp = false
    private var cachedViewport: SeamViewportNode? = null

    // Lazy items are subcomposed children of the container's layout node, so
    // the viewport is an ancestor in the node tree. Re-resolved per attach.
    private fun viewport(): SeamViewportNode? {
        if (!viewportLookedUp) {
            cachedViewport = findNearestAncestor(SeamViewportKey) as? SeamViewportNode
            viewportLookedUp = true
        }
        return cachedViewport
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val seam = viewport()?.coordinates ?: return
        if (!seam.isAttached || !coordinates.isAttached) return
        val offset = seam.localPositionOf(coordinates, Offset.Zero).y
        if (offset == offsetFromSeam) return
        val reach = reachPx()
        val wasNear = offsetFromSeam < reach
        offsetFromSeam = offset
        if (wasNear || offset < reach) invalidateDraw()
    }

    override fun onDetach() {
        offsetFromSeam = Float.NaN
        viewportLookedUp = false
        cachedViewport = null
    }

    private fun reachPx(): Float = with(requireDensity()) {
        SeamDissolveTokens.Band.toPx() * SeamHalftone.SolidFrom + SeamDissolveTokens.Pitch.toPx()
    }

    final override fun ContentDrawScope.draw() {
        val viewport = viewport()
        val offset = offsetFromSeam
        if (viewport == null || offset.isNaN() || offset >= reachPx()) {
            drawContent()
            return
        }
        val band = seamBandPx(
            scrolledPx = viewport.scrolledPx(),
            revealDistancePx = SeamDissolveTokens.RevealDistance.toPx(),
            bandPx = SeamDissolveTokens.Band.toPx(),
        )
        if (band < .5f) {
            drawContent()
            return
        }
        // Wholly above the seam: already gone. (The viewport clips here too.)
        if (offset + size.height <= 0f) return
        drawMasked(seam = -offset, band = band, flowShift = viewport.flowLagPx / band)
    }

    /**
     * [seam] is the seam line in this element's own coordinates; [flowShift]
     * is the flow's lag behind the content, in bands.
     */
    abstract fun ContentDrawScope.drawMasked(seam: Float, band: Float, flowShift: Float)
}

/** Band height for a scroll position: nothing at rest, full after the reveal distance. */
internal fun seamBandPx(scrolledPx: Float, revealDistancePx: Float, bandPx: Float): Float =
    bandPx * (scrolledPx / revealDistancePx).coerceIn(0f, 1f)

private class SeamDissolveNode : SeamNode() {
    // Varies the dissolve front between items; stable for the node's life.
    private val seed = (System.identityHashCode(this) % 1000) / 100f
    private var layer: GraphicsLayer? = null
    private var shader: Any? = null
    private val mask = Path()
    private val dot = FloatArray(3)

    override fun onDetach() {
        super.onDetach()
        layer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        layer = null
    }

    override fun ContentDrawScope.drawMasked(seam: Float, band: Float, flowShift: Float) {
        val lattice = SeamHalftone.lattice(size.width, SeamDissolveTokens.Pitch.toPx())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            drawWithShader(lattice, seam, band, flowShift)
        } else {
            drawWithPath(lattice, seam, band, flowShift)
        }
    }

    // One pass on the GPU: each pixel finds the dots that can reach it.
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun ContentDrawScope.drawWithShader(
        lattice: SeamHalftone.Lattice,
        seam: Float,
        band: Float,
        flowShift: Float,
    ) {
        val runtimeShader = shader as? RuntimeShader ?: RuntimeShader(SeamHalftone.AGSL).also { shader = it }
        val target = layer ?: requireGraphicsContext().createGraphicsLayer().also { layer = it }
        runtimeShader.setFloatUniform("size", size.width, size.height)
        runtimeShader.setFloatUniform("cell", lattice.cellWidth, lattice.rowHeight)
        runtimeShader.setFloatUniform("seam", seam)
        runtimeShader.setFloatUniform("band", band)
        runtimeShader.setFloatUniform("seed", seed)
        runtimeShader.setFloatUniform("flowShift", flowShift)
        target.renderEffect = android.graphics.RenderEffect
            .createRuntimeShaderEffect(runtimeShader, "content")
            .asComposeRenderEffect()
        target.record { this@drawWithShader.drawContent() }
        drawLayer(target)
    }

    // Pre-33 fallback, the same geometry as ArtworkHalftone's Path mask.
    private fun ContentDrawScope.drawWithPath(
        lattice: SeamHalftone.Lattice,
        seam: Float,
        band: Float,
        flowShift: Float,
    ) {
        mask.rewind()
        val solidFrom = seam + band * SeamHalftone.SolidFrom
        if (solidFrom < size.height) {
            mask.addRect(Rect(0f, max(0f, solidFrom), size.width, size.height))
        }
        val firstRow = floor(seam / lattice.rowHeight - 1.5f).toInt().coerceAtLeast(-1)
        val lastRow = ceil(solidFrom / lattice.rowHeight).toInt()
            .coerceAtMost(ceil(size.height / lattice.rowHeight).toInt())
        for (row in firstRow..lastRow) {
            for (column in -1..lattice.columns) {
                SeamHalftone.dot(lattice, size.width, size.height, column, row, seam, band, seed, flowShift, dot)
                val radius = dot[2]
                if (radius < 1f) continue
                mask.addOval(Rect(dot[0] - radius, dot[1] - radius, dot[0] + radius, dot[1] + radius))
            }
        }
        clipPath(mask) { this@drawWithPath.drawContent() }
    }
}

private class SeamFadeNode : SeamNode() {
    private val paint = Paint()

    override fun ContentDrawScope.drawMasked(seam: Float, band: Float, flowShift: Float) {
        val end = seam + band * SeamDissolveTokens.TextFadeFraction
        if (end <= 0f) {
            drawContent()
            return
        }
        // A little slack so ascenders and marquee edges fade instead of clipping.
        val slack = 4.dp.toPx()
        val bounds = Rect(-slack, -slack, size.width + slack, size.height + slack)
        drawIntoCanvas { it.saveLayer(bounds, paint) }
        drawContent()
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                .25f to Color.Black.copy(alpha = .06f),
                1f to Color.Black,
                startY = seam,
                endY = end,
            ),
            topLeft = bounds.topLeft,
            size = bounds.size,
            blendMode = BlendMode.DstIn,
        )
        drawIntoCanvas { it.restore() }
    }
}

/**
 * Halftone geometry shared by the AGSL mask and the Path fallback. A staggered
 * lattice anchored to the element; each dot's radius follows how far the dot
 * sits below the seam (zero at the seam, whole at [SolidFrom] × band). Inside
 * the band dots swirl along ArtworkHalftone's flow field, phased by position
 * plus the viewport's flow lag, never by time: once the afterglow settles the
 * print is still.
 */
internal object SeamHalftone {
    const val SolidFrom = 1.34f

    class Lattice(val columns: Int, val cellWidth: Float, val rowHeight: Float, val fullRadius: Float)

    fun lattice(width: Float, pitch: Float): Lattice {
        val columns = max(4, (width / pitch).roundToInt())
        val cellWidth = width / columns
        val rowHeight = cellWidth * .866f
        return Lattice(columns, cellWidth, rowHeight, hypot(cellWidth, rowHeight) * .53f)
    }

    /** Writes the dot's displaced centre (x, y) and radius into [out]; radius 0 = gone. */
    fun dot(
        lattice: Lattice,
        width: Float,
        height: Float,
        column: Int,
        row: Int,
        seam: Float,
        band: Float,
        seed: Float,
        flowShift: Float,
        out: FloatArray,
    ) {
        val stagger = if (row and 1 == 0) 0f else .5f
        val lx = (column + .5f + stagger) * lattice.cellWidth
        val ly = (row + .5f) * lattice.rowHeight
        val u = lx / width
        val v = ly / height
        val below = ly - seam
        val near = smoothstep((below / (band * .35f)).coerceIn(0f, 1f))
        val bend = .10f * sin((u * .9f + seed * .37f) * TAU) + .05f * sin((u * 2.3f - seed * .21f + .3f) * TAU)
        val jitter = (hash(column + seed * 31f, row + seed * 7f) - .5f) * .14f
        val p = below / band + (bend + jitter) * near
        val local = ((p - .1f) / .9f).coerceIn(0f, 1f)
        if (local <= 0f) {
            out[0] = lx
            out[1] = ly
            out[2] = 0f
            return
        }
        val envelope = sin(local * PI.toFloat())
        // Only the swirl follows the lagging flow; size stays tied to the seam.
        val flow = p + flowShift
        val flowX = sin((v * 1.4f + u * .45f - flow * .8f) * TAU)
        val flowY = sin((v * .7f - u * 1.3f + flow * .65f) * TAU)
        out[0] = lx + lattice.cellWidth * .42f * flowX * envelope
        out[1] = ly + lattice.rowHeight * .38f * flowY * envelope
        out[2] = lattice.fullRadius * local.pow(1.1f)
    }

    private fun smoothstep(value: Float): Float = value * value * (3f - 2f * value)

    private fun hash(x: Float, y: Float): Float {
        val value = sin(x * 127.1f + y * 311.7f) * 43758.547f
        return value - floor(value)
    }

    private const val TAU = (PI * 2).toFloat()

    // Mirrors [dot] per pixel: the 4×4 lattice neighbourhood covers the widest
    // dot (full radius + flow displacement), and dots union by max coverage.
    val AGSL = """
        uniform shader content;
        uniform float2 size;
        uniform float2 cell;
        uniform float seam;
        uniform float band;
        uniform float seed;
        uniform float flowShift;

        const float TAU = 6.2831853;
        const float PI = 3.1415927;

        float hash(float2 p) {
            return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.547);
        }

        half4 main(float2 xy) {
            float solidFrom = seam + band * $SolidFrom;
            if (xy.y >= solidFrom) return content.eval(xy);
            if (xy.y < seam - cell.y) return half4(0.0);
            float fullRadius = length(cell) * 0.53;
            float coverage = 0.0;
            float rowBase = floor(xy.y / cell.y - 0.5);
            for (int i = -1; i <= 2; i++) {
                float row = rowBase + float(i);
                float stagger = mod(row, 2.0) >= 1.0 ? 0.5 : 0.0;
                float ly = (row + 0.5) * cell.y;
                float v = ly / size.y;
                float below = ly - seam;
                float near = smoothstep(0.0, 1.0, clamp(below / (band * 0.35), 0.0, 1.0));
                float columnBase = floor(xy.x / cell.x - 0.5 - stagger);
                for (int j = -1; j <= 2; j++) {
                    float column = columnBase + float(j);
                    float lx = (column + 0.5 + stagger) * cell.x;
                    float u = lx / size.x;
                    float bend = 0.10 * sin((u * 0.9 + seed * 0.37) * TAU)
                        + 0.05 * sin((u * 2.3 - seed * 0.21 + 0.3) * TAU);
                    float jitter = (hash(float2(column + seed * 31.0, row + seed * 7.0)) - 0.5) * 0.14;
                    float p = below / band + (bend + jitter) * near;
                    float local = clamp((p - 0.1) / 0.9, 0.0, 1.0);
                    float radius = fullRadius * pow(local, 1.1);
                    if (radius >= 1.0) {
                        float envelope = sin(local * PI);
                        float flow = p + flowShift;
                        float flowX = sin((v * 1.4 + u * 0.45 - flow * 0.8) * TAU);
                        float flowY = sin((v * 0.7 - u * 1.3 + flow * 0.65) * TAU);
                        float2 centre = float2(
                            lx + cell.x * 0.42 * flowX * envelope,
                            ly + cell.y * 0.38 * flowY * envelope
                        );
                        coverage = max(coverage, clamp(radius - distance(xy, centre) + 0.5, 0.0, 1.0));
                    }
                }
            }
            return content.eval(xy) * coverage;
        }
    """.trimIndent()
}
