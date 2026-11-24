package com.gpo.yoin.player.sing

import kotlin.math.log10
import kotlin.math.pow

/**
 * Slider position p ∈ [0, 1] ↔ vocal gain g. Perceptual: equal slider steps are roughly equal steps in
 * loudness — g = 10^(−[RANGE_DB]·(1 − p)/20) — with the last [KNEE] of travel fading linearly to
 * true silence so p = 0 is g = 0 (no vocal), p = 1 is g = 1 (the original mix).
 *
 * 28 dB (owner, 2026-11-24; same numbers on iOS): the default 12 % sits at p ≈ 0.342, a third of the
 * way up, so the quiet-vocal end gets most of the travel (48 dB put it at 0.62, "over half"). The knee
 * starts at g ≈ 4.7 %. The level is only remembered in memory as a position (not persisted), so an
 * app update moves nothing on disk.
 */
object SingGainCurve {
    /** The slider spans this many dB above the silent knee. */
    const val RANGE_DB = 28.0

    /** Below this position the gain falls linearly from its knee value to 0. */
    const val KNEE = 0.05

    /** Where Sing settles when it turns on: 12 % of the vocal, Apple Music Sing's level. */
    const val DEFAULT_GAIN = 0.12f

    private val kneeGain = dbCurve(KNEE)

    fun gainFor(position: Float): Float {
        val p = position.toDouble().coerceIn(0.0, 1.0)
        val g = if (p >= KNEE) dbCurve(p) else kneeGain * p / KNEE
        return g.toFloat()
    }

    fun positionFor(gain: Float): Float {
        val g = gain.toDouble().coerceIn(0.0, 1.0)
        val p = if (g >= kneeGain) 1.0 + 20.0 * log10(g) / RANGE_DB else g / kneeGain * KNEE
        return p.toFloat().coerceIn(0f, 1f)
    }

    val DEFAULT_POSITION: Float = positionFor(DEFAULT_GAIN)

    /** Gains in here snap to [DEFAULT_GAIN] (the owner's "light haptic snap around 10–12 %"). */
    const val SNAP_LOW_GAIN = 0.10f
    const val SNAP_HIGH_GAIN = 0.135f

    /** [position], or [DEFAULT_POSITION] when its gain falls in the snap zone. */
    fun snap(position: Float): Float {
        val p = position.coerceIn(0f, 1f)
        return if (gainFor(p) in SNAP_LOW_GAIN..SNAP_HIGH_GAIN) DEFAULT_POSITION else p
    }

    private fun dbCurve(p: Double): Double = 10.0.pow(-RANGE_DB * (1.0 - p) / 20.0)
}
