package com.gpo.yoin.ui.memories.copy

import kotlin.math.floor

/**
 * The one score rounding every Memories surface shows (the emblem's label and tier, the card, Yoin's copy,
 * the diary's track rows): JS `Math.round(score * 10) / 10`, halves up, so 9.95 shows "10.0" and earns
 * the 10.0 tier. `String.format("%.1f")` / `toFixed(1)` would show 9.95 as "9.9" on a binary float.
 *
 * A [Float] score (Room stores ratings as REAL / Float) is read through its shortest decimal form first:
 * 9.95f is 9.9499998… in binary, but the user typed 9.95.
 */
object MemoryScores {
    /** The displayed score in tenths (9.95 → 100). */
    fun tenths(score: Double): Long = floor(score * 10 + 0.5).toLong()

    fun tenths(score: Float): Long = tenths(score.toString().toDouble())

    /** One decimal: "9.0", "8.5", "10.0". */
    fun text(score: Double): String = textOfTenths(tenths(score))

    fun text(score: Float): String = textOfTenths(tenths(score))

    private fun textOfTenths(tenths: Long): String = "${tenths / 10}.${tenths % 10}"
}
