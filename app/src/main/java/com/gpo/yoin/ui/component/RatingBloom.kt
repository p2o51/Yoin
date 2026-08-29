package com.gpo.yoin.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import kotlinx.coroutines.delay

/**
 * "Score bloom": a celebratory pop (0.72 → overshoot → 1 on the Expressive
 * fast spatial spring) plus a confirm haptic, fired when a rating COMMIT
 * lands downstream on a badge.
 *
 * The bloom only arms after the badge has been composed and quiet for
 * [settleMs]: the page-open data resolve (rating arrives async, a few
 * hundred ms in) finishes inside that window, so opening an already-rated
 * page never blooms — a commit seconds later does. Pass 0 for commit-point
 * displays (the review sheet's own readout), which must pop on the very
 * first commit after opening and have no load-resolve to suppress.
 */
@Composable
fun Modifier.ratingBloom(trigger: Any?, settleMs: Long = RATING_BLOOM_SETTLE_MS): Modifier {
    val scale = remember { Animatable(1f) }
    val haptics = rememberYoinHaptics()
    // Slow spatial spring: the fast bucket settles in ~100ms — the pop read
    // as a flicker, not a bloom. The slow bucket keeps the 0.72 → overshoot → 1
    // travel on screen long enough to actually register as celebration.
    val spec = YoinMotion.slowSpatialSpec<Float>(role = YoinMotionRole.Expressive)
    var settled by remember { mutableStateOf(false) }
    var lastTrigger by remember { mutableStateOf(trigger) }
    LaunchedEffect(trigger) {
        if (!settled) {
            delay(settleMs)
            settled = true
            lastTrigger = trigger
        } else if (trigger != lastTrigger) {
            lastTrigger = trigger
            haptics.performConfirm()
            scale.snapTo(0.72f)
            scale.animateTo(1f, spec)
        }
    }
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
        // The pop reads stronger with a slight lift than scale alone.
        translationY = -(1f - scale.value) * 6.dp.toPx()
    }
}

private const val RATING_BLOOM_SETTLE_MS = 800L
