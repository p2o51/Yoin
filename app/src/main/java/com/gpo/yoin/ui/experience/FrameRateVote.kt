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
 *
 * The element is always present and only its category changes (Default when
 * idle): adding and removing it changed the modifier chain's STRUCTURE at the
 * motion's first frame, which invalidated measurement of the node and its
 * subtree — an ~11 ms full Now Playing layout on the first frame of the
 * expanded lyrics' back gesture (Pixel Tablet trace, 2026-10-06).
 */
fun Modifier.voteHighFrameRate(active: Boolean): Modifier =
    preferredFrameRate(if (active) FrameRateCategory.High else FrameRateCategory.Default)
