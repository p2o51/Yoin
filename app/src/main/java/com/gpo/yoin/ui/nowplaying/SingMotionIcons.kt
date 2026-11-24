@file:Suppress("ktlint:standard:function-naming") // Composables are PascalCase

package com.gpo.yoin.ui.nowplaying

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Shared 24×24 Luminous geometry & cadence constants for the two approved Sing UI icon animations
 * (`A + 1`, locked 2026-11-24):
 * - **A (Preparing · 拾音三柱)**: mic capsule morphs into 3 acoustic equalizer bars inside the steady
 *   U-cradle & stem on a 4-beat spring+hold cadence, reuniting cleanly to the resting mic silhouette
 *   before the active capsule expands.
 * - **1 (Enter / Exit · 麦头展成爱心)**: true 6-segment cubic Bézier point-to-point path interpolation
 *   between the Yoin mic capsule (`x: 9..15, y: 3.2..13`) and the exact `yoin_favorite.svg` heart
 *   contour, paired with an `S·fast` retracting diagonal slash and gentle cradle fold.
 */
internal object SingMotionGeometry {
    const val VIEWBOX = 24f
    const val STROKE_WIDTH = 1.5f

    /** One cubic Bézier segment `(c1x, c1y, c2x, c2y, x, y)` in 24×24 viewport coordinates. */
    @Immutable
    data class CubicSeg(
        val c1x: Float,
        val c1y: Float,
        val c2x: Float,
        val c2y: Float,
        val x: Float,
        val y: Float,
    )

    @Immutable
    data class ClosedCubicPath(
        val mx: Float,
        val my: Float,
        val segs: List<CubicSeg>,
    )

    @Immutable
    data class PrepBeat(
        val split: Float,
        val barL: Float,
        val barC: Float,
        val barR: Float,
        val holdMs: Long,
    )

    /** Exact `docs/icons/svg/yoin_favorite.svg` 6-segment clockwise contour from bottom tip `(12, 19.35)`. */
    val HeartClosed = ClosedCubicPath(
        mx = 12f,
        my = 19.35f,
        segs = listOf(
            CubicSeg(12f, 19.35f, 3.75f, 14.55f, 3.75f, 9.15f),
            CubicSeg(3.75f, 6.5f, 5.7f, 4.65f, 8.05f, 4.65f),
            CubicSeg(9.7f, 4.65f, 11.15f, 5.5f, 12f, 6.95f),
            CubicSeg(12.85f, 5.5f, 14.3f, 4.65f, 15.95f, 4.65f),
            CubicSeg(18.3f, 4.65f, 20.25f, 6.5f, 20.25f, 9.15f),
            CubicSeg(20.25f, 14.55f, 12f, 19.35f, 12f, 19.35f),
        ),
    )

    /**
     * Matching 6-segment clockwise contour for the Yoin mic capsule (`x: 9..15, y: 3.2..13, r: 3`),
     * starting at bottom center `(12, 13)` so every segment aligns 1:1 with [HeartClosed].
     */
    val MicCapsuleClosed = ClosedCubicPath(
        mx = 12f,
        my = 13.0f,
        segs = listOf(
            CubicSeg(10.343f, 13.0f, 9.0f, 11.657f, 9.0f, 10.0f),
            CubicSeg(9.0f, 8.73f, 9.0f, 7.47f, 9.0f, 6.2f),
            CubicSeg(9.0f, 4.543f, 10.343f, 3.2f, 12.0f, 3.2f),
            CubicSeg(13.657f, 3.2f, 15.0f, 4.543f, 15.0f, 6.2f),
            CubicSeg(15.0f, 7.47f, 15.0f, 8.73f, 15.0f, 10.0f),
            CubicSeg(15.0f, 11.657f, 13.657f, 13.0f, 12.0f, 13.0f),
        ),
    )

    /** 4-beat preparing cadence: 3 expressive bar beats + 1 reunite beat back into the intact mic. */
    val PrepBeats: List<PrepBeat> = listOf(
        PrepBeat(split = 1f, barL = 3.2f, barC = 9.2f, barR = 5.2f, holdMs = 240L),
        PrepBeat(split = 1f, barL = 7.6f, barC = 3.8f, barR = 8.2f, holdMs = 240L),
        PrepBeat(split = 1f, barL = 4.4f, barC = 8.8f, barR = 3.4f, holdMs = 240L),
        PrepBeat(split = 0f, barL = 3.2f, barC = 8.8f, barR = 3.2f, holdMs = 340L),
    )

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /**
     * Interpolates point-for-point between [MicCapsuleClosed] (`t = 0`) and [HeartClosed] (`t = 1`).
     * Intentionally allows slight spring overshoot (`t` outside `0..1`) for expressive feel.
     */
    fun interpolateMicToHeart(t: Float): ClosedCubicPath {
        val a = MicCapsuleClosed
        val b = HeartClosed
        return ClosedCubicPath(
            mx = lerp(a.mx, b.mx, t),
            my = lerp(a.my, b.my, t),
            segs = a.segs.indices.map { i ->
                val sa = a.segs[i]
                val sb = b.segs[i]
                CubicSeg(
                    c1x = lerp(sa.c1x, sb.c1x, t),
                    c1y = lerp(sa.c1y, sb.c1y, t),
                    c2x = lerp(sa.c2x, sb.c2x, t),
                    c2y = lerp(sa.c2y, sb.c2y, t),
                    x = lerp(sa.x, sb.x, t),
                    y = lerp(sa.y, sb.y, t),
                )
            },
        )
    }

    fun writeToPath(contour: ClosedCubicPath, scale: Float, out: Path) {
        out.reset()
        out.moveTo(contour.mx * scale, contour.my * scale)
        for (seg in contour.segs) {
            out.cubicTo(
                seg.c1x * scale,
                seg.c1y * scale,
                seg.c2x * scale,
                seg.c2y * scale,
                seg.x * scale,
                seg.y * scale,
            )
        }
        out.close()
    }
}

/**
 * Option A (**拾音三柱**) Sing preparing icon: replaces the generic spinner with a living mic glyph.
 * - When [preparing] is `false`, renders the resting 1.5dp Luminous mic (settling home cleanly if it was
 *   mid-beat, invoking [onSettledHome] as soon as the capsule reunites so [SingLyricsCapsuleKey] can
 *   expand without a mid-beat hard cut).
 * - Under [MotionProfile.AdaptiveReduced], keeps the intact mic and breathes opacity `0.42 ↔ 1.0`.
 */
@Composable
internal fun SingPreparingMicIcon(
    preparing: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    fastSettle: Boolean = false,
    onSettledHome: (() -> Unit)? = null,
) {
    val reduced = LocalMotionProfile.current == MotionProfile.AdaptiveReduced
    val eFast = YoinMotion.fastSpatialSpec<Float>(role = YoinMotionRole.Expressive)
    val eDefault = YoinMotion.defaultSpatialSpec<Float>(role = YoinMotionRole.Expressive)
    val slowEffects = YoinMotion.slowEffectsSpec<Float>(role = YoinMotionRole.Standard)
    val defaultEffects = YoinMotion.defaultEffectsSpec<Float>(role = YoinMotionRole.Standard)

    val split = remember { Animatable(0f) }
    val barL = remember { Animatable(3.2f) }
    val barC = remember { Animatable(8.8f) }
    val barR = remember { Animatable(3.2f) }
    val reducedAlpha = remember { Animatable(1f) }
    val contourPath = remember { Path() }

    val latestFastSettle by rememberUpdatedState(fastSettle)
    val latestOnSettled by rememberUpdatedState(onSettledHome)

    LaunchedEffect(preparing, reduced) {
        if (preparing) {
            if (reduced) {
                split.snapTo(0f)
                var dim = true
                while (isActive) {
                    reducedAlpha.animateTo(if (dim) 0.42f else 1f, slowEffects)
                    delay(450L)
                    dim = !dim
                }
            } else {
                reducedAlpha.snapTo(1f)
                var step = 0
                while (isActive) {
                    val beat = SingMotionGeometry.PrepBeats[step % SingMotionGeometry.PrepBeats.size]
                    val jSplit = launch { split.animateTo(beat.split, eDefault) }
                    val jL = launch { barL.animateTo(beat.barL, eFast) }
                    val jC = launch { barC.animateTo(beat.barC, eFast) }
                    val jR = launch { barR.animateTo(beat.barR, eFast) }
                    jSplit.join()
                    jL.join()
                    jC.join()
                    jR.join()
                    delay(beat.holdMs)
                    step++
                }
            }
        } else {
            // Settle cleanly back to the intact resting mic pose.
            val settleSpec = if (latestFastSettle) eFast else eDefault
            val jAlpha = launch {
                if (reducedAlpha.value != 1f) reducedAlpha.animateTo(1f, defaultEffects)
            }
            val jSplit = launch {
                if (split.value != 0f) split.animateTo(0f, settleSpec)
            }
            val jL = launch {
                if (barL.value != 3.2f) barL.animateTo(3.2f, settleSpec)
            }
            val jC = launch {
                if (barC.value != 8.8f) barC.animateTo(8.8f, settleSpec)
            }
            val jR = launch {
                if (barR.value != 3.2f) barR.animateTo(3.2f, settleSpec)
            }
            jAlpha.join()
            jSplit.join()
            jL.join()
            jC.join()
            jR.join()
            latestOnSettled?.invoke()
        }
    }

    Canvas(modifier = modifier.size(size)) {
        val s = minOf(this.size.width, this.size.height) / SingMotionGeometry.VIEWBOX
        val alpha = reducedAlpha.value.coerceIn(0f, 1f)
        val stroke = Stroke(
            width = SingMotionGeometry.STROKE_WIDTH * s,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        val baseColor = tint.copy(alpha = tint.alpha * alpha)

        // Steady U-cradle (center 12, 10.6; r = 5.7; 0°..180° bottom arc)
        val cradleRect = Rect(
            left = (12f - 5.7f) * s,
            top = (10.6f - 5.7f) * s,
            right = (12f + 5.7f) * s,
            bottom = (10.6f + 5.7f) * s,
        )
        drawArc(
            color = baseColor,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = cradleRect.topLeft,
            size = cradleRect.size,
            style = stroke,
        )

        // Steady stem
        drawLine(
            color = baseColor,
            start = Offset(12f * s, 16.4f * s),
            end = Offset(12f * s, 20.5f * s),
            strokeWidth = SingMotionGeometry.STROKE_WIDTH * s,
            cap = StrokeCap.Round,
        )

        val sp = split.value.coerceIn(0f, 1f)
        val capsuleAlpha = (1f - sp * 1.6f).coerceIn(0f, 1f)
        val barsAlpha = ((sp - 0.15f) / 0.85f).coerceIn(0f, 1f)

        // Resting mic capsule (gently tucks in as bars emerge)
        if (capsuleAlpha > 0.01f) {
            SingMotionGeometry.writeToPath(SingMotionGeometry.MicCapsuleClosed, s, contourPath)
            val sx = 1f - 0.12f * sp
            val sy = 1f - 0.16f * sp
            withTransform({
                scale(scaleX = sx, scaleY = sy, pivot = Offset(12f * s, 8.1f * s))
            }) {
                drawPath(
                    path = contourPath,
                    color = baseColor.copy(alpha = baseColor.alpha * capsuleAlpha),
                    style = stroke,
                )
            }
        }

        // Three vertical acoustic bars inside the cradle
        if (barsAlpha > 0.01f) {
            val barColor = baseColor.copy(alpha = baseColor.alpha * barsAlpha)
            val cy = 8.1f * s
            val drawBar: (Float, Float) -> Unit = { x, h ->
                val half = (h * 0.5f).coerceAtLeast(0.4f) * s
                drawLine(
                    color = barColor,
                    start = Offset(x * s, cy - half),
                    end = Offset(x * s, cy + half),
                    strokeWidth = SingMotionGeometry.STROKE_WIDTH * s,
                    cap = StrokeCap.Round,
                )
            }
            drawBar(9f, barL.value)
            drawBar(12f, barC.value)
            drawBar(15f, barR.value)
        }
    }
}

/**
 * Option 1 (**麦头展成爱心**) true 6-cubic Bézier path morph between `MicOff` (`morph = 0`) and
 * `yoin_favorite` Heart (`morph = 1`), with independent [slashProgress] (`1 = full slash`, `0 = retracted`)
 * and [heartFillAlpha] (`0..1`) for favorited tracks.
 */
@Composable
internal fun SingHeartMicMorphIcon(
    morph: Float,
    slashProgress: Float,
    heartFillAlpha: Float,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
) {
    val morphPath = remember { Path() }

    Canvas(modifier = modifier.size(size)) {
        val s = minOf(this.size.width, this.size.height) / SingMotionGeometry.VIEWBOX
        val clamped = morph.coerceIn(0f, 1f)
        val sl = slashProgress.coerceIn(0f, 1f)
        val fillOp = heartFillAlpha.coerceIn(0f, 1f)
        val stroke = Stroke(
            width = SingMotionGeometry.STROKE_WIDTH * s,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )

        val contour = SingMotionGeometry.interpolateMicToHeart(morph)
        SingMotionGeometry.writeToPath(contour, s, morphPath)

        // Optional heart fill blooming from center for starred tracks
        if (fillOp > 0.01f) {
            val fillScale = SingMotionGeometry.lerp(0.5f, 1f, fillOp)
            withTransform({
                scale(scaleX = fillScale, scaleY = fillScale, pivot = Offset(12f * s, 12f * s))
            }) {
                drawPath(
                    path = morphPath,
                    color = tint.copy(alpha = tint.alpha * fillOp),
                )
            }
        }

        // Cradle + stem gently fold toward bottom tip (12, 19.35) as mic opens into heart
        val auxAlpha = (1f - clamped * 1.65f).coerceIn(0f, 1f)
        if (auxAlpha > 0.01f) {
            val auxColor = tint.copy(alpha = tint.alpha * auxAlpha)
            val auxScale = SingMotionGeometry.lerp(1f, 0.55f, clamped)
            val auxTy = SingMotionGeometry.lerp(0f, 3.2f, clamped) * s
            val cradleRect = Rect(
                left = (12f - 5.7f) * s,
                top = (10.6f - 5.7f) * s,
                right = (12f + 5.7f) * s,
                bottom = (10.6f + 5.7f) * s,
            )
            withTransform({
                translate(left = 0f, top = auxTy)
                scale(scaleX = auxScale, scaleY = auxScale, pivot = Offset(12f * s, 14f * s))
            }) {
                drawArc(
                    color = auxColor,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = cradleRect.topLeft,
                    size = cradleRect.size,
                    style = stroke,
                )
            }
            val stemY1 = SingMotionGeometry.lerp(16.4f, 19.2f, clamped) * s
            val stemY2 = SingMotionGeometry.lerp(20.5f, 19.35f, clamped) * s
            drawLine(
                color = auxColor,
                start = Offset(12f * s, stemY1),
                end = Offset(12f * s, stemY2),
                strokeWidth = SingMotionGeometry.STROKE_WIDTH * s,
                cap = StrokeCap.Round,
            )
        }

        // Main morphing stroke (Mic capsule ↔ Heart contour)
        drawPath(
            path = morphPath,
            color = tint,
            style = stroke,
        )

        // Diagonal slash retracts cleanly toward top-left on S·fast
        if (sl > 0.03f) {
            val x2 = SingMotionGeometry.lerp(4.5f, 19.5f, sl) * s
            val y2 = SingMotionGeometry.lerp(4.5f, 19.5f, sl) * s
            drawLine(
                color = tint,
                start = Offset(4.5f * s, 4.5f * s),
                end = Offset(x2, y2),
                strokeWidth = SingMotionGeometry.STROKE_WIDTH * s,
                cap = StrokeCap.Round,
            )
        }
    }
}
