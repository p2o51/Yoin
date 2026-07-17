package com.gpo.yoin.ui.navigation.back

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.gpo.yoin.ui.experience.DetailBackPhase
import com.gpo.yoin.ui.experience.ExperienceSessionStore
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The AOSP "entering target" movement for the window REVEALED by a detail
 * page's predictive back: while the detail card collapses above (that window
 * turns translucent for the gesture), this window's content sits 96dp to the
 * left, scales in sync with the gesture and follows the finger vertically;
 * on commit it settles to identity with the platform's EMPHASIZED curve
 * (450ms) while the detail window dissolves.
 *
 * Ported from WM Shell DefaultCrossActivityBackAnimation's
 * preparePreCommitEnteringRectMovement/onPostCommitProgress. The pose is
 * bridged across windows through [ExperienceSessionStore]'s snapshot states
 * and read only inside the graphicsLayer lambda — 60Hz gesture frames never
 * recompose the shell.
 *
 * Apply to the shell's CONTENT only — its bottom bar must stay put (it is
 * pixel-aligned under the detail window's bar).
 */
@Composable
fun rememberDetailBackEnteringModifier(store: ExperienceSessionStore): Modifier {
    val settle = remember { Animatable(0f) }
    var seedProgress by remember { mutableFloatStateOf(0f) }
    var seedTouchY by remember { mutableFloatStateOf(0f) }
    val phase by store.detailBackPhase

    LaunchedEffect(phase) {
        if (phase == DetailBackPhase.Committed) {
            // Freeze the gesture pose, then run the AOSP post-commit settle.
            seedProgress = store.detailBackProgress.floatValue
            seedTouchY = store.detailBackTouchYDelta.floatValue
            settle.snapTo(1f)
            settle.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = POST_COMMIT_DURATION_MS,
                    easing = EmphasizedEasing,
                ),
            )
            store.detailBackProgress.floatValue = 0f
            store.detailBackTouchYDelta.floatValue = 0f
            store.detailBackPhase.value = DetailBackPhase.Idle
        }
    }

    return remember(store) {
        Modifier.graphicsLayer {
            val f: Float
            val p: Float
            val ty: Float
            when (store.detailBackPhase.value) {
                DetailBackPhase.Gesture -> {
                    f = 1f
                    p = store.detailBackProgress.floatValue
                    ty = store.detailBackTouchYDelta.floatValue
                }
                DetailBackPhase.Committed -> {
                    f = settle.value
                    p = seedProgress
                    ty = seedTouchY
                }
                DetailBackPhase.Idle -> return@graphicsLayer
            }
            if (f <= 0f) return@graphicsLayer

            // Entering rect: fullscreen offset -96dp, scaled centered in sync
            // with the closing card; everything eases out with the settle.
            val scale = 1f - (1f - BackMotionTokens.PopPageScaleTarget) * p * f
            scaleX = scale
            scaleY = scale
            translationX = -ENTERING_START_OFFSET_DP * density * f

            if (ty != 0f) {
                val halfH = size.height / 2f
                val ratio = min(halfH, abs(ty)) / halfH
                val decelerated = 1f - (1f - ratio) * (1f - ratio)
                val maxShift =
                    max(0f, (size.height - size.height * scale) / 2f - DISPLAY_MARGIN_DP * density)
                translationY = maxShift * decelerated * (if (ty < 0f) -1f else 1f) * f
            }
        }
    }
}

/**
 * The revealed window's BAR pose during a detail back commit: seeded from the
 * frozen scrub progress (1 − gesture progress = chrome morph) so the bar
 * beneath the dissolving window starts EXACTLY where the scrubbed bar above
 * stopped, then settles to nav chrome on the same spring the detail bar uses
 * to finish its own scrub — the crossfade shows one bar, not two poses.
 * Returns null when no commit settle is in flight (normal animated morph).
 */
@Composable
fun rememberDetailBackBarMorphOverride(store: ExperienceSessionStore): (() -> Float)? {
    val morph = remember { Animatable(0f) }
    var active by remember { mutableStateOf(false) }
    val phase by store.detailBackPhase
    val spec = YoinMotion.defaultSpatialSpec<Float>(role = YoinMotionRole.Standard)
    LaunchedEffect(phase) {
        if (phase == DetailBackPhase.Committed) {
            morph.snapTo((1f - store.detailBackProgress.floatValue).coerceIn(0f, 1f))
            active = true
            morph.animateTo(0f, spec)
            active = false
        }
    }
    return if (active) {
        { morph.value }
    } else {
        null
    }
}

/** AOSP R.dimen.cross_activity_back_entering_start_offset. */
private const val ENTERING_START_OFFSET_DP = 96f

/** AOSP DefaultCrossActivityBackAnimation.POST_COMMIT_DURATION. */
private const val POST_COMMIT_DURATION_MS = 450

/** AOSP Interpolators.EMPHASIZED. */
private val EmphasizedEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

private const val DISPLAY_MARGIN_DP = 8f
