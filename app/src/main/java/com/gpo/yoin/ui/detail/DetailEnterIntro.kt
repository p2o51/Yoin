package com.gpo.yoin.ui.detail

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.ui.navigation.back.BackMotionTokens
import kotlinx.coroutines.delay

/**
 * The forward half of the AOSP cross-activity motion: the incoming page's
 * CONTENT slides in from 96dp right on the platform's EMPHASIZED curve,
 * OPAQUE from its first frame — the AOSP push model, which is the only
 * choreography with no blank gap and no ghost:
 *
 *  - a window-wide alpha fade (the old system anim, then a Compose one)
 *    ghosts the whole outgoing page through the incoming one for the fade's
 *    full duration;
 *  - receding Home before the incoming is visible leaves a blank window;
 *  - an opaque slide covers the receding Home progressively, so neither
 *    artifact can exist.
 *
 * The window itself stays transparent by theme (the bar-morph hold shows the
 * shell untouched beneath it — the hold is free once nothing fades). The
 * shell mirrors the slide with its own 96dp recede
 * (rememberDetailBackEnteringModifier's covered rest), so open and
 * predictive-back are the same trajectory run in both directions.
 *
 * [barHandoff] launches (from shell / Now Playing) hold the slide for 200ms
 * so the shell bar's nav→split morph reads as the tap feedback before the
 * incoming window covers it. The tick that releases the shell's recede fires
 * only after this window has produced its FIRST FRAME ([withFrameNanos]) —
 * a wall-clock hold can elapse before the window can draw, and a tick fired
 * then lets the recede run while our animations are frozen at their start
 * values (the old occasional blank-window recurrence). Detail→detail pushes
 * appear immediately and slide right away, reading as a native push over the
 * live page beneath. Plays once per Activity (rememberSaveable), so rotation
 * doesn't replay it.
 */
@Stable
class DetailEnterIntroState internal constructor(alreadyPlayed: Boolean) {
    internal val slide = Animatable(if (alreadyPlayed) 0f else 1f)
}

@Composable
fun rememberDetailEnterIntro(barHandoff: Boolean): DetailEnterIntroState {
    // Previews render the settled end state directly (no enter animation).
    val inspectionMode = LocalInspectionMode.current
    var played by rememberSaveable { mutableStateOf(inspectionMode) }
    val state = remember { DetailEnterIntroState(played) }
    val context = LocalContext.current
    val store = remember(context) {
        (context.applicationContext as YoinApplication).container.experienceSessionStore
    }
    LaunchedEffect(Unit) {
        if (!played) {
            // Anchor to the first frame this window actually DRAWS, not to
            // composition-apply (see the KDoc above).
            withFrameNanos { }
            if (barHandoff) delay(BAR_HANDOFF_HOLD_MS)
            // Signal BEFORE the first animated value lands: the shell's
            // mirrored recede keys off this tick to run the same 450ms
            // EMPHASIZED ride in lockstep with this slide (its own clock
            // starts at tap time, hundreds of ms before this window exists).
            store.noteDetailEnterSlideStarted()
            state.slide.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = ENTER_DURATION_MS,
                    easing = BackMotionTokens.EmphasizedEasing,
                ),
            )
            played = true
        }
    }
    return state
}

/** Slide only — apply to the page CONTENT container (never the bottom bar). */
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

/** The bar-morph hold — the tap feedback beat before the slide covers it. */
private const val BAR_HANDOFF_HOLD_MS = 200L
