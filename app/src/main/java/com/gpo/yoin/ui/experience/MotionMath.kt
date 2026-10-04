package com.gpo.yoin.ui.experience

/**
 * Hermite smoothstep: 0 at or below [edge0], 1 at or above [edge1], an
 * S-curve in between. Shared by the bar and Home so their cross-fades match.
 */
internal fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
