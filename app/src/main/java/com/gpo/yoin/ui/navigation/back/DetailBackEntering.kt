package com.gpo.yoin.ui.navigation.back

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
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
 * The shell bar's chrome morph (0 = nav, 1 = detail split) — the ONE owner of
 * that pose. A lone Animatable plays the open/restore morphs off
 * [detailChromeActive] AND the detail-back commit settle, so no second driver
 * exists to hand off to (the previous shape — a temporary override lambda over
 * YoinButtonGroup's own animateFloatAsState — released back to a stale split
 * pose after a long-drag commit: the override's settle from ≈nav finished
 * instantly while the Boolean spring hadn't even been retargeted, and the bar
 * flashed split before re-morphing. Same lesson as the NP stage settle:
 * one Animatable, one owner).
 *
 * Commit: seed from the frozen scrub pose (1 − gesture progress) bridged
 * through [ExperienceSessionStore], then settle to nav on the SAME spring the
 * dissolving detail bar above uses to finish its own scrub — the crossfade
 * shows one bar riding one trajectory. The normal chrome effect is parked
 * while a commit settle runs (keyed on the committed Boolean, so the detail's
 * onBackClick flipping the flag mid-commit can neither re-seed nor rewind);
 * both paths end at nav, so the hand-back is coincident by construction.
 */
@Composable
fun rememberShellBarChromeMorph(
    store: ExperienceSessionStore,
    detailChromeActive: Boolean,
): () -> Float {
    val morph = remember { Animatable(if (detailChromeActive) 1f else 0f) }
    val phase by store.detailBackPhase
    val committed = phase == DetailBackPhase.Committed
    val spec = YoinMotion.defaultSpatialSpec<Float>(role = YoinMotionRole.Standard)
    LaunchedEffect(committed) {
        if (committed) {
            morph.snapTo((1f - store.detailBackProgress.floatValue).coerceIn(0f, 1f))
            morph.animateTo(0f, spec)
        }
    }
    LaunchedEffect(detailChromeActive, committed) {
        if (!committed) {
            morph.animateTo(if (detailChromeActive) 1f else 0f, spec)
        }
    }
    return remember(morph) { { morph.value } }
}

/** AOSP R.dimen.cross_activity_back_entering_start_offset. */
private const val ENTERING_START_OFFSET_DP = 96f

/** AOSP DefaultCrossActivityBackAnimation.POST_COMMIT_DURATION. */
private const val POST_COMMIT_DURATION_MS = 450

/** AOSP Interpolators.EMPHASIZED. */
private val EmphasizedEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

private const val DISPLAY_MARGIN_DP = 8f
