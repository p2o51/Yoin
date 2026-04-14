package com.gpo.yoin.ui.navigation.back

import kotlin.math.pow

fun normalizeBackProgress(raw: Float): Float {
    return raw.coerceIn(0f, 1f)
        .pow(BackMotionTokens.PreviewCurveExponent)
        .coerceIn(0f, 1f)
}
