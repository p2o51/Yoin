package com.gpo.yoin.ui.nowplaying

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/*
 * Clean focus (owner 2026-11-03): what steps away when the cover is tapped —
 * the top bar, the three pills — must stop answering taps once it is mostly
 * gone, or an invisible button sits over the grown cover.
 */

/**
 * Swallows every touch on this subtree while [progress] (0 = here, 1 = gone)
 * is past half. Read per event, never in composition; the input handler
 * keeps the first [progress] it was given, so it must read state, not a
 * value captured at composition.
 */
fun Modifier.retreatInput(progress: () -> Float): Modifier = this.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (progress() > 0.5f) event.changes.forEach { it.consume() }
        }
    }
}

/** The top bar's retreat: it lifts a little and fades, and stops taking taps. */
fun Modifier.focusRetreat(progress: () -> Float): Modifier = this
    .graphicsLayer {
        val p = progress().coerceIn(0f, 1f)
        val fade = (p * 1.4f).coerceAtMost(1f)
        alpha = 1f - fade * fade * (3f - 2f * fade)
        translationY = -RetreatLift.toPx() * p
    }
    .retreatInput(progress)

private val RetreatLift = 10.dp
