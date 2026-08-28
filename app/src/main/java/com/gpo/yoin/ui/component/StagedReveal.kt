package com.gpo.yoin.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One-shot staged reveal for hero surfaces ("启幕"): each beat is an
 * [Animatable] 0→1 on the Expressive slow spatial spring, started with a
 * small stagger so the eye reads cover → meta → titles as separate arrivals.
 * Fires ONCE per [key] (rememberSaveable survives config change and
 * covered-shell pauses — returning from a detail page must NOT replay).
 * After the first run every beat parks at 1 and the modifiers are inert.
 *
 * [ready] gates the start on CONTENT, not composition: feeds load async, and
 * a reveal that fires against an empty/loading page is a reveal nobody sees.
 * The beats park at 0 until [ready] first reports content, then the stagger
 * runs and [key] latches.
 *
 * Not for list items / repeated content: the motion audit bans per-item
 * entrances on scroll and staggers on repeat visits.
 */
@Stable
class StagedReveal internal constructor(
    internal val beatValues: List<Animatable<Float, AnimationVector1D>>,
) {
    /** Beat 0 — the hero itself (cover / bento card). */
    val hero: Float get() = beatValues[0].value

    /** Beat 1 — supporting meta (Last Play / Avg / section rows). */
    val meta: Float get() = beatValues[1].value

    /** Beat 2 — the textual payload (flowing titles / track rows). */
    val payload: Float get() = beatValues[2].value
}

@Composable
fun rememberStagedReveal(key: Any, ready: Boolean = true): StagedReveal {
    var played by rememberSaveable(key) { mutableStateOf(false) }
    val reveal = remember(key) {
        StagedReveal(List(STAGED_BEAT_COUNT) { Animatable(if (played) 1f else 0f) })
    }
    val spec = YoinMotion.slowSpatialSpec<Float>(role = YoinMotionRole.Expressive)
    LaunchedEffect(key, ready) {
        if (!played && ready) {
            reveal.beatValues.forEachIndexed { index, beat ->
                launch {
                    delay(STAGED_BEAT_DELAYS_MS[index])
                    beat.animateTo(1f, spec)
                }
            }
            played = true
        }
    }
    return reveal
}

/**
 * Draw-phase mapping of a reveal beat to alpha + rise (+ optional grow-in).
 * All reads happen inside the graphicsLayer lambda — the beats never
 * recompose their page.
 */
fun Modifier.stagedBeat(
    progress: () -> Float,
    rise: Dp = 14.dp,
    scaleFrom: Float = 1f,
): Modifier = graphicsLayer {
    val p = progress().coerceIn(0f, 1f)
    if (p >= 1f) return@graphicsLayer
    alpha = p
    translationY = (1f - p) * rise.toPx()
    if (scaleFrom < 1f) {
        val s = scaleFrom + (1f - scaleFrom) * p
        scaleX = s
        scaleY = s
    }
}

private const val STAGED_BEAT_COUNT = 3
private val STAGED_BEAT_DELAYS_MS = longArrayOf(0L, 90L, 170L)
