package com.gpo.yoin.ui.navigation.back

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
}
