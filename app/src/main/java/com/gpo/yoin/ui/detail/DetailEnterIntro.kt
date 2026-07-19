package com.gpo.yoin.ui.detail

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay

/**
 * The forward half of the AOSP cross-activity motion: the incoming page's
 * CONTENT slides in from 96dp right on the platform's EMPHASIZED curve while
 * the window itself only fades (any window-level translate would carry the
 * bottom bar with it and break the "one persistent bar" illusion — the bar
 * is a sibling of the transformed container, so it stays pixel-locked over
 * the bar beneath). The shell mirrors this with its own 96dp recede
 * (rememberDetailBackEnteringModifier's covered rest), so open and
 * predictive-back are the same trajectory run in both directions.
 *
 * [barHandoff] launches (from shell / Now Playing) hold the window
 * transparent for 200ms while the shell bar morphs — the slide waits so its
 * visible portion starts at full amplitude. Detail→detail pushes appear
 * immediately and slide right away, reading as a native push over the live
 * page beneath. Plays once per Activity (rememberSaveable), so rotation
 * doesn't replay it.
 */
@Stable
class DetailEnterIntroState internal constructor(alreadyPlayed: Boolean) {
    internal val slide = Animatable(if (alreadyPlayed) 0f else 1f)
}

@Composable
fun rememberDetailEnterIntro(barHandoff: Boolean): DetailEnterIntroState {
    var played by rememberSaveable { mutableStateOf(false) }
    val state = remember { DetailEnterIntroState(played) }
    LaunchedEffect(Unit) {
        if (!played) {
            if (barHandoff) delay(BAR_HANDOFF_HOLD_MS)
            state.slide.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = ENTER_DURATION_MS,
                    easing = EnterEmphasizedEasing,
                ),
            )
            played = true
        }
    }
    return state
}

/** Apply to the same content container as detailBackCollapseTransform. */
fun Modifier.detailEnterIntroTransform(state: DetailEnterIntroState): Modifier =
    graphicsLayer {
        val s = state.slide.value
        if (s <= 0f) return@graphicsLayer
        translationX = ENTER_START_OFFSET_DP * density * s
    }

/** Mirror of the back gesture's entering start offset (AOSP 96dp). */
private const val ENTER_START_OFFSET_DP = 96f

/** AOSP cross-activity open duration, same as the back post-commit settle. */
private const val ENTER_DURATION_MS = 450

/** res/anim/detail_bar_handoff_enter.xml's startOffset. */
private const val BAR_HANDOFF_HOLD_MS = 200L

/** AOSP Interpolators.EMPHASIZED. */
private val EnterEmphasizedEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
