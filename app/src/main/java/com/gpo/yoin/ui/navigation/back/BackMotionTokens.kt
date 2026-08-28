package com.gpo.yoin.ui.navigation.back

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.unit.dp

object BackMotionTokens {
    /**
     * Scale the popped page shrinks to on back, matching the AOSP
     * cross-activity predictive back animation (CrossActivityBackAnimation
     * MAX_SCALE = 0.9).
     */
    const val PopPageScaleTarget = 0.9f

    /**
     * Corner radius the popped page clips to while shrinking; stand-in for
     * the device window corner radius the system animation uses.
     */
    val PopPageCornerRadius = 28.dp

    val MemoriesDismissTrigger = 112.dp

    /**
     * AOSP `Interpolators.EMPHASIZED` — the platform's curve for the
     * cross-activity open/close rides. Single definition shared by the
     * forward enter slide (DetailEnterIntro) and the shell's entering-side
     * pose (DetailBackEntering) so the mirrored trajectories can't drift.
     */
    val EmphasizedEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
}
