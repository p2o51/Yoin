package com.gpo.yoin.ui.landing

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.activity.BackEventCompat
import com.gpo.yoin.ui.experience.CenteredBarBottomMargin
import com.gpo.yoin.ui.experience.CenteredBarHeight
import com.gpo.yoin.ui.experience.CenteredBarHorizontalMargin
import com.gpo.yoin.ui.experience.CenteredBarMaxWidth
import com.gpo.yoin.ui.experience.FloatingBarHeight
import com.gpo.yoin.ui.experience.PortraitBarVerticalMargin
import com.gpo.yoin.ui.navigation.back.BackMotionTokens
import com.gpo.yoin.ui.nowplaying.StageBackPreview
import com.gpo.yoin.ui.theme.GoogleSansFlexRounded
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// The landing stage: one progress t (from step → to step) drives the floating
// window, the mascot, the speech bubble and the pill; one more (the hand-off)
// turns the pill into the shell's bar. Every per-frame value is read in a
// layout or layer block — a step change never recomposes the stage.

/** The single motion owner of the landing. */
@Stable
internal class LandingMotion(private val scope: CoroutineScope) {
    var from by mutableStateOf(LandingStep.Hello)
        private set
    var to by mutableStateOf(LandingStep.Hello)
        private set

    /** from → to, 0..1 (springs may overshoot a little). */
    val t = Animatable(1f)

    /** The hand-off into the shell: window into the pill, pill into the bar, mascot to the corner. */
    val handoff = Animatable(0f)

    /** Mascot flight to the greeting corner during the hand-off (later and slower than [handoff]). */
    val fly = Animatable(0f)

    /** The bubble's own pop when its line changes. */
    val bubblePop = Animatable(1f)

    /** The pill rising in at first launch. */
    val pillIn = Animatable(1f)

    /** System back's preview of the current step (the AOSP pose), owned by the landing's back handler. */
    val preview = StageBackPreview()

    private var job: Job? = null

    val settled: Boolean get() = from == to

    /** The step whose line the bubble shows: it swaps at the transition's midpoint. */
    val bubbleStep: LandingStep by derivedStateOf { if (from == to || t.value >= 0.5f) to else from }

    fun go(target: LandingStep, spec: AnimationSpec<Float>) {
        if (target == to && settled) return
        val showing = if (settled || t.value >= 0.5f) to else from
        job?.cancel()
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            t.snapTo(0f)
            from = showing
            to = target
            t.animateTo(1f, spec)
            from = to
        }
    }

    fun snap(step: LandingStep) {
        job?.cancel()
        from = step
        to = step
        scope.launch(start = CoroutineStart.UNDISPATCHED) { t.snapTo(1f) }
    }

    /** Pops the bubble from its tail when the line changes. */
    fun popBubble(spec: AnimationSpec<Float>) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            bubblePop.snapTo(BubblePopFrom)
            bubblePop.animateTo(1f, spec)
        }
    }

    private companion object {
        const val BubblePopFrom = 0.82f
    }
}

@Composable
internal fun rememberLandingMotion(): LandingMotion {
    val scope = rememberCoroutineScope()
    return remember(scope) { LandingMotion(scope) }
}

/** Which way the bubble's tail points (at the mascot). */
internal enum class BubbleTail { Up, Left }

/** The bubble's tail, written by the stage while placing and read by the bubble while drawing. */
@Stable
internal class BubbleTailState {
    var side by mutableStateOf(BubbleTail.Up)
    var offsetPx by mutableFloatStateOf(0f)
}

/** Layout inputs the stage hands the window right before measuring it (same pass, plain fields). */
internal class LandingWindowSpec {
    var sceneWidth = 0
    var minHeight = 0
    var helloHeight = 0
}

/** The rects the stage interpolates between, for one window size. px. */
internal class LandingFrame(
    val width: Float,
    val height: Float,
    val top: Float,
    val wide: Boolean,
    val medium: Boolean,
    val colX: Float,
    val colW: Float,
    val pillH: Float,
    val pillTop: Float,
    val helloPillX: Float,
    val helloPillW: Float,
    val barX: Float,
    val barW: Float,
    val barH: Float,
    val barTop: Float,
    val gap: Float,
    val dp: Float,
)

internal fun landingFrame(
    density: Density,
    width: Int,
    height: Int,
    topInset: Int,
    bottomInset: Int,
    centeredBar: Boolean,
    twoColumns: Boolean,
): LandingFrame = with(density) {
    val w = width.toFloat()
    val h = height.toFloat()
    val dp = 1.dp.toPx()
    val medium = !twoColumns && centeredBar
    val colW: Float
    val colX: Float
    if (twoColumns) {
        colW = min(520.dp.toPx(), w * 0.44f)
        colX = w - colW - max(48.dp.toPx(), w * 0.06f)
    } else {
        colW = min(w - 32.dp.toPx(), 560.dp.toPx())
        colX = (w - colW) / 2f
    }
    val pillH = (if (centeredBar) CenteredBarHeight else FloatingBarHeight).toPx()
    val bottomMargin = (if (centeredBar) CenteredBarBottomMargin else PortraitBarVerticalMargin).toPx()
    val pillTop = h - bottomInset - bottomMargin - pillH
    val helloPillW = if (centeredBar || twoColumns) min(380.dp.toPx(), w - 32.dp.toPx()) else w - 32.dp.toPx()
    val barW = if (centeredBar) min(w - CenteredBarHorizontalMargin.toPx() * 2, CenteredBarMaxWidth.toPx()) else w - 32.dp.toPx()
    LandingFrame(
        width = w,
        height = h,
        top = topInset.toFloat(),
        wide = twoColumns,
        medium = medium,
        colX = colX,
        colW = colW,
        pillH = pillH,
        pillTop = pillTop,
        helloPillX = (w - helloPillW) / 2f,
        helloPillW = helloPillW,
        barX = (w - barW) / 2f,
        barW = barW,
        barH = pillH,
        barTop = pillTop,
        gap = 12.dp.toPx(),
        dp = dp,
    )
}

/**
 * Lays the landing out: [window] (the floating window with the step's scene), [mascot], [bubble] and [pill].
 * Window, mascot and bubble take the back preview's pose together; the pill is chrome and stays put.
 */
@Composable
internal fun LandingStage(
    motion: LandingMotion,
    windowSpec: LandingWindowSpec,
    tail: BubbleTailState,
    topInset: Int,
    bottomInset: Int,
    imeBottom: () -> Int,
    centeredBar: Boolean,
    twoColumns: Boolean,
    window: @Composable () -> Unit,
    mascot: @Composable () -> Unit,
    bubble: @Composable () -> Unit,
    pill: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val memory = remember { StageMemory() }
    Layout(
        contents = listOf(window, mascot, bubble, pill),
        modifier = modifier,
    ) { measurables, constraints ->
        val windowM = measurables[0].first()
        val mascotM = measurables[1].first()
        val bubbleM = measurables[2].first()
        val pillM = measurables[3].first()
        val f = landingFrame(this, constraints.maxWidth, constraints.maxHeight, topInset, bottomInset, centeredBar, twoColumns)
        val a = motion.from
        val b = motion.to
        val t = motion.t.value
        val hv = motion.handoff.value.coerceIn(0f, 1f)
        val dp = f.dp
        // The window stands on the pill, or on the keyboard when one is up (the pill stays behind it). Read
        // here, in layout, so the keyboard's slide only re-places.
        val floor = min(f.pillTop - f.gap, f.height - imeBottom() - f.gap)

        // ---- window ----
        val baseSize = (if (f.medium) 120f else 88f) * dp
        val rowHeight = max(baseSize * 0.8f, memory.flowBubbleHeight)
        val maxH = if (f.wide) {
            floor - (f.top + 28f * dp)
        } else {
            floor - (f.top + 18f * dp + rowHeight + 14f * dp)
        }.coerceAtLeast(f.pillH)
        val minH = when {
            f.wide -> min(maxH, max(380f * dp, maxH * 0.62f))
            f.medium -> min(maxH, max(360f * dp, maxH * 0.55f))
            else -> 0f
        }
        windowSpec.sceneWidth = f.colW.toInt()
        windowSpec.minHeight = minH.toInt()
        windowSpec.helloHeight = f.pillH.toInt()
        fun pillX(step: LandingStep) = if (step == LandingStep.Hello) f.helloPillX else f.colX
        fun pillW(step: LandingStep) = if (step == LandingStep.Hello) f.helloPillW else f.colW
        var winW = lerp(pillW(a), pillW(b), t)
        var winX = lerp(pillX(a), pillX(b), t)
        val windowP = windowM.measure(Constraints(winW.toInt(), winW.toInt(), 0, maxH.toInt()))
        val winH = windowP.height.toFloat()
        fun winTop(step: LandingStep) = if (step == LandingStep.Hello) f.pillTop else floor - winH
        var winY = lerp(winTop(a), winTop(b), t)
        var winAlpha = when {
            a == LandingStep.Hello && b == LandingStep.Hello -> 0f
            a == LandingStep.Hello -> (t * 6f).coerceIn(0f, 1f)
            b == LandingStep.Hello -> ((1f - t) * 6f).coerceIn(0f, 1f)
            else -> 1f
        }
        if (hv > 0f) {
            winX = lerp(winX, f.barX, hv)
            winW = lerp(winW, f.barW, hv)
            winY = lerp(winY, f.barTop, hv)
            winAlpha *= (1f - hv * 1.6f).coerceIn(0f, 1f)
        }
        if (a != LandingStep.Hello || b != LandingStep.Hello) memory.lastWindowTop = winY

        // ---- bubble width; an up-tail bubble is measured first ----
        // Its width doesn't depend on the mascot, and the hello pose centres the mascot + bubble group on the
        // bubble's height — measuring it first means the very first frame is already in place.
        val shown = motion.bubbleStep
        fun measureBubble(mascotSize: Float): Placeable {
            val bubbleMax = when {
                !upTail(shown, f) -> f.colW - mascotSize - 6f * dp
                f.wide && shown != LandingStep.Hello -> min(360f * dp, f.width * 0.40f)
                else -> min((if (f.wide || f.medium) 420f else 340f) * dp, f.width - 48f * dp)
            }
            val placeable = bubbleM.measure(Constraints(maxWidth = bubbleMax.toInt().coerceAtLeast(1)))
            val height = placeable.height.toFloat()
            if (shown == LandingStep.Hello) {
                if (memory.helloBubbleHeight != height) memory.helloBubbleHeight = height
            } else if (memory.flowBubbleHeight != height) {
                memory.flowBubbleHeight = height
            }
            return placeable
        }
        val earlyBubble = if (upTail(shown, f)) measureBubble(0f) else null

        // ---- mascot ----
        val helloSize = if (f.wide || f.medium) 300f * dp else min(f.width * 0.62f, 250f * dp)
        val helloGroup = helloSize * 0.8f + 15f * dp + memory.helloBubbleHeight
        val helloCy = (f.top + f.pillTop) / 2f * 0.94f - helloGroup / 2f + helloSize * 0.4f
        fun pose(step: LandingStep): MascotPose {
            if (step == LandingStep.Hello) return MascotPose(f.width / 2f, helloCy, helloSize, BubbleTail.Up)
            if (f.wide) {
                // Left column. A keyboard lifts the floor: Yoin and its bubble shrink into the room above it
                // (ratio 1 = no keyboard, the resting pose).
                val ratio = ((floor - f.top) / (f.pillTop - f.gap - f.top)).coerceIn(0f, 1f)
                return MascotPose(
                    cx = f.colX / 2f,
                    cy = f.top + (f.height * 0.40f - f.top) * ratio,
                    size = 220f * dp * ratio.coerceAtLeast(0.6f),
                    tail = BubbleTail.Up,
                )
            }
            val top = floor - winH
            val room = top - 14f * dp - (f.top + 18f * dp)
            val grown = (room * 0.42f).coerceIn(baseSize, (if (f.medium) 170f else 132f) * dp)
            val size = if (grown - baseSize > 4f * dp && memory.flowBubbleHeight <= room) grown else baseSize
            val base = top - 14f * dp
            return MascotPose(f.colX + size / 2f - 6f * dp, base - size * 0.4f, size, BubbleTail.Left)
        }
        val pa = pose(a)
        val pb = pose(b)
        var cx = lerp(pa.cx, pb.cx, t)
        var cy = lerp(pa.cy, pb.cy, t)
        var size = lerp(pa.size, pb.size, t)
        if (hv > 0f) {
            val fly = motion.fly.value
            cx = lerp(cx, 44f * dp, fly)
            cy = lerp(cy, f.top + 32f * dp, fly)
            size = lerp(size, 56f * dp, fly)
        }
        val mascotP = mascotM.measure(Constraints.fixed(size.toInt().coerceAtLeast(1), (size / MascotAspect).toInt().coerceAtLeast(1)))

        // ---- bubble ----
        val shownPose = if (shown == b) pb else pa
        val bubbleP = earlyBubble ?: measureBubble(shownPose.size)
        fun anchor(p: MascotPose): Offset = if (p.tail == BubbleTail.Up) {
            Offset(p.cx, p.cy + p.size * 0.42f + 4f * dp)
        } else {
            Offset(p.cx + p.size * 0.5f + 2f * dp, p.cy - p.size * 0.05f)
        }
        val anchorA = anchor(pa)
        val anchorB = anchor(pb)
        val ax = lerp(anchorA.x, anchorB.x, t)
        var ay = lerp(anchorA.y, anchorB.y, t)
        val tailH = 11f * dp
        val bubbleX: Float
        val bubbleY: Float
        if (shownPose.tail == BubbleTail.Up) {
            bubbleX = (ax - bubbleP.width / 2f).coerceIn(16f * dp, max(16f * dp, f.width - 16f * dp - bubbleP.width))
            bubbleY = ay + tailH
            tail.side = BubbleTail.Up
            tail.offsetPx = ax - bubbleX
        } else {
            val base = floor - winH - 14f * dp
            ay = min(ay, base - bubbleP.height + 26f * dp)
            bubbleX = ax + tailH
            bubbleY = ay - 26f * dp
            tail.side = BubbleTail.Left
            tail.offsetPx = 26f * dp
        }
        val tc = t.coerceIn(0f, 1f)
        val dip = if (motion.settled) 1f else 1f - 0.55f * sin(PI.toFloat() * tc)
        val bubbleScale = (motion.bubblePop.value * dip * (1f - hv * 2.2f).coerceIn(0f, 1f)).coerceAtLeast(0f)
        val tailOrigin = if (shownPose.tail == BubbleTail.Up) {
            TransformOrigin((ax - bubbleX) / bubbleP.width.coerceAtLeast(1), -tailH / bubbleP.height.coerceAtLeast(1))
        } else {
            TransformOrigin(-tailH / bubbleP.width.coerceAtLeast(1), 26f * dp / bubbleP.height.coerceAtLeast(1))
        }

        // ---- pill ----
        var pillX = lerp(pillX(a), pillX(b), t)
        var pillW = lerp(pillW(a), pillW(b), t)
        var pillY = f.pillTop
        if (hv > 0f) {
            pillX = lerp(pillX, f.barX, hv)
            pillW = lerp(pillW, f.barW, hv)
            pillY = lerp(pillY, f.barTop, hv)
        }
        val pillP = pillM.measure(Constraints.fixed(pillW.toInt(), f.pillH.toInt()))
        val pillRise = (1f - motion.pillIn.value) * 120f * dp

        layout(constraints.maxWidth, constraints.maxHeight) {
            val preview = motion.preview
            fun Placeable.PlacementScope.placePreviewed(p: Placeable, x: Float, y: Float, z: Float, block: androidx.compose.ui.graphics.GraphicsLayerScope.() -> Unit) {
                p.placeWithLayer(x.toInt(), y.toInt(), z) {
                    block()
                    applyStagePreview(preview, f, x, y, p.width.toFloat(), p.height.toFloat())
                }
            }
            placePreviewed(windowP, winX, winY, 0f) { alpha = winAlpha }
            placePreviewed(mascotP, cx - size / 2f, cy - size / MascotAspect / 2f, 1f) {}
            placePreviewed(bubbleP, bubbleX, bubbleY, 2f) {
                transformOrigin = tailOrigin
                scaleX = bubbleScale
                scaleY = bubbleScale
                alpha = (bubbleScale * 2.5f).coerceIn(0f, 1f)
            }
            pillP.placeWithLayer(pillX.toInt(), (pillY + pillRise).toInt(), 3f) {
                alpha = (motion.pillIn.value * 1.4f).coerceIn(0f, 1f)
            }
        }
    }
}

/**
 * Measured heights the next pass lays out with. State, so a bubble that changes height (a new step's text, a
 * fresh session) re-lays the stage out even when nothing is animating.
 */
private class StageMemory {
    var helloBubbleHeight by mutableFloatStateOf(0f)
    var flowBubbleHeight by mutableFloatStateOf(0f)
    var lastWindowTop = 0f
}

private class MascotPose(val cx: Float, val cy: Float, val size: Float, val tail: BubbleTail)

/** Whether [step]'s bubble hangs below the mascot (hello, and every step in two columns) — see the poses. */
private fun upTail(step: LandingStep, f: LandingFrame) = step == LandingStep.Hello || f.wide

/**
 * The back preview's AOSP pose (scale toward 0.9, shift toward the swipe's edge, a decelerated vertical follow,
 * within the 8dp margin), applied to one child as if the whole stage were one page scaled about the window's
 * centre. Same math as [com.gpo.yoin.ui.nowplaying.backPreviewTransform], spread over three layers.
 */
private fun androidx.compose.ui.graphics.GraphicsLayerScope.applyStagePreview(
    preview: StageBackPreview,
    f: LandingFrame,
    x: Float,
    y: Float,
    w: Float,
    h: Float,
) {
    val p = preview.progress.value
    if (p <= 0f) return
    val scale = 1f - (1f - BackMotionTokens.PopPageScaleTarget) * p
    val margin = StageMarginDp * f.dp
    val dx = if (preview.swipeEdge == BackEventCompat.EDGE_LEFT) max(0f, f.width * (1f - scale) / 2f - margin) * p else 0f
    val rawDy = preview.touchYDelta
    val dy = if (rawDy != 0f) {
        val half = f.height / 2f
        val ratio = min(half, abs(rawDy)) / half
        val decelerated = 1f - (1f - ratio) * (1f - ratio)
        max(0f, f.height * (1f - scale) / 2f - margin) * decelerated * (if (rawDy < 0f) -1f else 1f)
    } else {
        0f
    }
    // Scale about the screen centre = scale about the child's centre + move its centre toward the screen's.
    val ccx = x + w / 2f
    val ccy = y + h / 2f
    scaleX *= scale
    scaleY *= scale
    translationX += (ccx - f.width / 2f) * (scale - 1f) + dx
    translationY += (ccy - f.height / 2f) * (scale - 1f) + dy
}

private const val StageMarginDp = 8f

/**
 * The floating window: the current step's scene (and the previous one while they cross). Its height follows
 * the two scenes' heights by t; the scenes slide 28dp along the axis of travel and cross-fade.
 */
@Composable
internal fun LandingWindow(
    motion: LandingMotion,
    spec: LandingWindowSpec,
    shape: Shape,
    color: Color,
    scene: @Composable (LandingStep) -> Unit,
    modifier: Modifier = Modifier,
) {
    val holder = rememberSaveableStateHolder()
    val from = motion.from
    val to = motion.to
    val steps = if (from == to) listOf(to) else listOf(from, to)
    val forward = to.ordinal >= from.ordinal
    Layout(
        content = {
            steps.forEach { step ->
                key(step) {
                    Box(
                        propagateMinConstraints = true,
                        modifier = if (step != to) Modifier.blockPointers() else Modifier,
                    ) {
                        if (step != LandingStep.Hello) holder.SaveableStateProvider(step.name) { scene(step) }
                    }
                }
            }
        },
        modifier = modifier
            .graphicsLayer {
                this.shape = shape
                clip = true
            }
            .background(color),
    ) { measurables, constraints ->
        val sceneW = spec.sceneWidth.coerceAtLeast(1)
        val maxH = constraints.maxHeight
        val minH = min(spec.minHeight, maxH)
        val placeables = measurables.map { it.measure(Constraints(sceneW, sceneW, minH, maxH)) }
        fun heightOf(index: Int, step: LandingStep): Float =
            if (step == LandingStep.Hello) spec.helloHeight.toFloat() else placeables[index].height.toFloat()
        val t = motion.t.value
        val h = if (steps.size == 1) {
            heightOf(0, to)
        } else {
            lerp(heightOf(0, from), heightOf(1, to), t)
        }.coerceIn(0f, maxH.toFloat())
        val sign = if (forward) 1f else -1f
        val slide = 28.dp.toPx()
        layout(constraints.maxWidth, h.toInt()) {
            placeables.forEachIndexed { index, placeable ->
                val leaving = steps.size == 2 && index == 0
                placeable.placeWithLayer(0, 0) {
                    val tt = motion.t.value.coerceIn(0f, 1f)
                    if (steps.size == 1) return@placeWithLayer
                    if (leaving) {
                        alpha = (1f - tt * 1.8f).coerceIn(0f, 1f)
                        translationX = -sign * slide * tt
                    } else {
                        alpha = ((tt - 0.3f) / 0.7f).coerceIn(0f, 1f)
                        translationX = sign * slide * (1f - tt)
                    }
                }
            }
        }
    }
}

/** The leaving scene takes no input while it fades. */
private fun Modifier.blockPointers(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}

/** The mascot's speech bubble: a rounded card whose tail points at the mascot ([tail]). */
@Composable
internal fun LandingBubble(
    title: String,
    body: String?,
    tail: BubbleTailState,
    background: Color,
    content: Color,
    modifier: Modifier = Modifier,
) {
    val color = background
    Column(
        modifier = modifier
            .drawBehind {
                val r = BubbleCorner.toPx()
                val path = Path()
                path.addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(r, r)))
                path.addPath(tailPath(tail.side, tail.offsetPx, size, this))
                drawPath(path, color)
            }
            .padding(start = 18.dp, end = 18.dp, top = 13.dp, bottom = 14.dp),
    ) {
        Text(
            text = title,
            color = content,
            style = MaterialTheme.typography.titleLarge.copy(
                fontFamily = GoogleSansFlexRounded,
                fontWeight = FontWeight.SemiBold,
                fontSize = 19.sp,
                lineHeight = 25.sp,
            ),
        )
        if (!body.isNullOrBlank()) {
            Text(
                text = body,
                color = content.copy(alpha = 0.82f),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 21.sp),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private val BubbleCorner = 22.dp

/** A 22 × 12dp tail with a rounded tip; [offset] is its tip along the edge, px. */
private fun tailPath(side: BubbleTail, offset: Float, size: Size, density: Density): Path = with(density) {
    val half = 11.dp.toPx()
    val depth = 11.dp.toPx()
    val path = Path()
    if (side == BubbleTail.Up) {
        val x = offset.coerceIn(half + 8.dp.toPx(), size.width - half - 8.dp.toPx())
        path.moveTo(x - half, 1f)
        path.cubicTo(x - half * 0.36f, 1f, x - half * 0.24f, -depth, x, -depth)
        path.cubicTo(x + half * 0.24f, -depth, x + half * 0.36f, 1f, x + half, 1f)
        path.close()
    } else {
        val y = offset.coerceIn(half + 8.dp.toPx(), size.height - half - 8.dp.toPx())
        path.moveTo(1f, y - half)
        path.cubicTo(1f, y - half * 0.36f, -depth, y - half * 0.24f, -depth, y)
        path.cubicTo(-depth, y + half * 0.24f, 1f, y + half * 0.36f, 1f, y + half)
        path.close()
    }
    path
}
