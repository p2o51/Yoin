package com.gpo.yoin.ui.experience

import androidx.compose.ui.FrameRateCategory
import androidx.compose.ui.Modifier
import androidx.compose.ui.preferredFrameRate

/**
 * Per-node ARR frame-rate vote (Android 15 QPR1+ adaptive refresh rate).
 *
 * On ARR devices un-voted Compose content renders at Normal (~60Hz) unless a
 * touch boost is live — so no-touch animations (post-release predictive-back
 * settles, the tabletop lyrics emphasis, stage reshapes) paced at 60 on a
 * 120Hz panel. The fix is exactly what the platform recommends: vote High on
 * the nodes that are actually animating, for only as long as they animate —
 * NOT a window-wide pin. One High vote per window per frame is enough to lift
 * that window's cadence. Pre-ARR devices ignore the vote entirely (they get
 * the peak-mode request from requestPeakRefreshRate instead).
 *
 * Gate [active] with derivedStateOf over the driving Animatable so the flag
 * flips composition only twice per animation, not per frame.
 */
fun Modifier.voteHighFrameRate(active: Boolean): Modifier =
    if (active) preferredFrameRate(FrameRateCategory.High) else this
