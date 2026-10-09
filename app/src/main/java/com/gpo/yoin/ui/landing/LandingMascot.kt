package com.gpo.yoin.ui.landing

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.res.vectorResource
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.ui.experience.YoinHaptics
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// The landing's character: the three-arrow mark split into its fins. Pink is
// its left hand, navy its right, violet its head; the hands wave from the
// joint where the fins meet. Everything the character does is a spring on a
// layer property, so a wave never recomposes or re-lays out anything.

/** The mark's viewport (see landing_mascot_*.xml): 1300 x 1040 units. */
internal const val MascotViewportWidth = 1300f
internal const val MascotViewportHeight = 1040f
internal const val MascotAspect = MascotViewportWidth / MascotViewportHeight

/** One fin: its entrance pose (dx, dy in viewport units, rot in degrees, scale) plus its gesture angle. */
@Stable
internal class MascotFin(
    @param:DrawableRes val drawable: Int,
    /** The joint it turns around, in viewport units. */
    val pivotX: Float,
    val pivotY: Float,
) {
    val dx = Animatable(0f)
    val dy = Animatable(0f)
    val rot = Animatable(0f)
    val scale = Animatable(1f)
    val arm = Animatable(0f)

    suspend fun rest() {
        dx.snapTo(0f); dy.snapTo(0f); rot.snapTo(0f); scale.snapTo(1f); arm.snapTo(0f)
    }
}

/**
 * The mascot's choreography. Gestures replace each other: a new one cancels
 * the one running and starts from the pose on screen.
 */
@Stable
class LandingMascotState internal constructor(
    private val scope: CoroutineScope,
    private val haptics: YoinHaptics?,
    private val reduced: Boolean,
) {
    internal val pink = MascotFin(R.drawable.landing_mascot_pink, pivotX = 660f, pivotY = 520f)
    internal val violet = MascotFin(R.drawable.landing_mascot_violet, pivotX = 652f, pivotY = 480f)
    internal val navy = MascotFin(R.drawable.landing_mascot_navy, pivotX = 660f, pivotY = 500f)
    internal val fins = listOf(pink, violet, navy)

    /** Whole-character alpha (the entrance fade, the hand-off fade). */
    internal val alpha = Animatable(1f)

    /** Vertical kick, in viewport units (negative = up). */
    internal val hop = Animatable(0f)

    private var gesture: Job? = null

    private fun play(block: suspend CoroutineScope.() -> Unit) {
        gesture?.cancel()
        gesture = scope.launch(block = block)
    }

    /** The fins fly in from three sides and land as the mark, then it waves. */
    fun intro(onLanded: () -> Unit = {}) = play {
        if (reduced) {
            fins.forEach { it.rest() }
            alpha.snapTo(1f)
            onLanded()
            return@play
        }
        val from = mapOf(pink to Triple(-330f, 220f, -55f), violet to Triple(0f, -300f, 24f), navy to Triple(330f, 230f, 55f))
        fins.forEach { fin ->
            val (x, y, r) = from.getValue(fin)
            fin.dx.snapTo(x); fin.dy.snapTo(y); fin.rot.snapTo(r); fin.scale.snapTo(0.35f); fin.arm.snapTo(0f)
        }
        alpha.snapTo(0f)
        launch { delay(60); alpha.animateTo(1f, YoinMotion.mascotLandSpring()) }
        listOf(violet to 80L, pink to 170L, navy to 260L).forEach { (fin, wait) ->
            launch {
                delay(wait)
                launch { fin.dx.animateTo(0f, YoinMotion.mascotLandSpring()) }
                launch { fin.dy.animateTo(0f, YoinMotion.mascotLandSpring()) }
                launch { fin.rot.animateTo(0f, YoinMotion.mascotLandSpring()) }
                fin.scale.animateTo(1f, YoinMotion.mascotLandSpring())
            }
        }
        delay(LandedAfterMs)
        onLanded()
        waveSequence(times = 2, ticks = true)
    }

    /** Both hands up and back, [times] swings; a tick at the top of each of the first hand's swings. */
    fun wave(times: Int = 1, ticks: Boolean = true) = play { waveSequence(times, ticks) }

    private suspend fun CoroutineScope.waveSequence(times: Int, ticks: Boolean) {
        if (reduced) return
        // (delay before this pose, pink arm, navy arm, head tilt): pink swings clockwise to raise, navy the other way.
        val poses = if (times <= 1) {
            listOf(Pose(0, 28f, -12f, -2.5f), Pose(240, 8f, -2f, 2.5f), Pose(240, 0f, 0f, 0f))
        } else {
            listOf(
                Pose(0, 26f, -12f, -2.5f), Pose(230, 9f, -3f, 2.5f), Pose(230, 30f, -14f, -2.5f),
                Pose(230, 10f, -4f, 2.5f), Pose(240, 0f, 0f, 0f),
            )
        }
        poses.forEachIndexed { index, pose ->
            delay(pose.afterMs.toLong())
            launch { pink.arm.animateTo(pose.left, YoinMotion.mascotGestureSpring()) }
            launch { navy.arm.animateTo(pose.right, YoinMotion.mascotGestureSpring()) }
            launch { violet.arm.animateTo(pose.head, YoinMotion.mascotGestureSpring()) }
            if (ticks && pose.left > 20f && (index == 0 || index == 2)) {
                launch { delay(PeakAfterMs); haptics?.performTick() }
            }
        }
    }

    /** Both hands up and a hop: a connection went through. */
    fun cheer() = play {
        if (reduced) return@play
        launch { hop.animateTo(0f, YoinMotion.mascotHopSpring(), initialVelocity = -CheerHopVelocity) }
        launch { pink.arm.animateTo(36f, YoinMotion.mascotGestureSpring()) }
        navy.arm.animateTo(-16f, YoinMotion.mascotGestureSpring())
        delay(CheerHoldMs)
        launch { pink.arm.animateTo(0f, YoinMotion.mascotGestureSpring()) }
        navy.arm.animateTo(0f, YoinMotion.mascotGestureSpring())
    }

    /** A small nod toward something the user picked. */
    fun nod() = play {
        if (reduced) return@play
        launch { hop.animateTo(0f, YoinMotion.mascotHopSpring(), initialVelocity = -NodHopVelocity) }
        violet.arm.animateTo(7f, YoinMotion.mascotGestureSpring())
        delay(NodHoldMs)
        violet.arm.animateTo(0f, YoinMotion.mascotGestureSpring())
    }

    /** The head rings out a "no": something the user typed was refused. */
    fun shake() = play {
        if (reduced) return@play
        violet.arm.snapTo(0f)
        violet.arm.animateTo(0f, YoinMotion.mascotShakeSpring(), initialVelocity = ShakeVelocity)
    }

    private data class Pose(val afterMs: Int, val left: Float, val right: Float, val head: Float)

    private companion object {
        const val LandedAfterMs = 760L
        const val PeakAfterMs = 120L
        const val CheerHopVelocity = 1700f
        const val CheerHoldMs = 520L
        const val NodHopVelocity = 700f
        const val NodHoldMs = 380L
        const val ShakeVelocity = 160f
    }
}

@Composable
fun rememberLandingMascotState(haptics: YoinHaptics?, reduced: Boolean): LandingMascotState {
    val scope = rememberCoroutineScope()
    return remember(scope, reduced) { LandingMascotState(scope, haptics, reduced) }
}

/**
 * The mascot, drawn at the width of [modifier] (the height follows the mark's aspect). [breathing] runs a slow
 * rise and fall while it waits; the motion is read in draw, so it costs one redraw of this node per frame.
 */
@Composable
fun LandingMascot(
    state: LandingMascotState,
    modifier: Modifier = Modifier,
    breathing: Boolean = true,
) {
    val breath: () -> Float = if (breathing) {
        val transition = rememberInfiniteTransition(label = "mascotBreath")
        val phase = transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(BreathPeriodMs, easing = LinearEasing), RepeatMode.Restart),
            label = "mascotBreathPhase",
        )
        val sample: () -> Float = { kotlin.math.sin(phase.value * 2f * Math.PI.toFloat()) }
        sample
    } else {
        val still: () -> Float = { 0f }
        still
    }
    val shapes = state.fins.map { rememberFinShapes(it.drawable) }
    Canvas(
        modifier = modifier
            .aspectRatio(MascotAspect)
            .clearAndSetSemantics {},
    ) {
        // One draw node, every transform done while drawing: as layers, a fin moved by a fraction of a pixel
        // showed a 1px seam along the box's bottom edge (device QA 2026-10-09).
        val unit = size.width / MascotViewportWidth
        val alpha = state.alpha.value.coerceIn(0f, 1f)
        if (alpha <= 0f) return@Canvas
        val lift = (state.hop.value + breath() * BreathLift) * unit
        state.fins.forEachIndexed { index, fin ->
            withTransform({
                translate(fin.dx.value * unit, fin.dy.value * unit + lift)
                val pivot = Offset(fin.pivotX * unit, fin.pivotY * unit)
                rotate(fin.rot.value + fin.arm.value, pivot)
                scale(fin.scale.value, fin.scale.value, pivot)
                scale(unit, unit, Offset.Zero)
            }) {
                shapes[index].forEach { part -> part.draw(this, alpha) }
            }
        }
    }
}

/** One path of a fin, in the drawable's viewport units. */
private class FinPart(val path: Path, val fill: Brush?, val stroke: Brush?, val strokeWidth: Float) {
    fun draw(scope: DrawScope, alpha: Float) {
        fill?.let { scope.drawPath(path, it, alpha = alpha) }
        stroke?.let { scope.drawPath(path, it, alpha = alpha, style = Stroke(width = strokeWidth)) }
    }
}

/** The drawable's paths (geometry stays in res/drawable/landing_mascot_*.xml), with their groups' offsets applied. */
@Composable
private fun rememberFinShapes(@DrawableRes drawable: Int): List<FinPart> {
    val vector = ImageVector.vectorResource(drawable)
    return remember(vector) {
        buildList {
            fun walk(group: VectorGroup, dx: Float, dy: Float) {
                group.forEach { node ->
                    when (node) {
                        is VectorGroup -> walk(node, dx + node.translationX, dy + node.translationY)
                        is VectorPath -> {
                            val path = PathParser().addPathNodes(node.pathData).toPath()
                            if (dx != 0f || dy != 0f) path.translate(Offset(dx, dy))
                            add(FinPart(path, node.fill, node.stroke, node.strokeLineWidth))
                        }
                    }
                }
            }
            walk(vector.root, 0f, 0f)
        }
    }
}

private const val BreathPeriodMs = 3400
private const val BreathLift = 5f

@Preview(showBackground = true)
@Composable
private fun LandingMascotPreview() {
    YoinTheme {
        LandingMascot(
            state = rememberLandingMascotState(haptics = null, reduced = true),
            modifier = Modifier.size(width = 240.dp, height = 192.dp),
            breathing = false,
        )
    }
}
