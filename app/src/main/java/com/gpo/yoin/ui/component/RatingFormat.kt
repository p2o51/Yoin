package com.gpo.yoin.ui.component

import kotlin.math.roundToInt

/*
 * A rating is null when the track was never rated (or its score was cleared
 * in the album scrapbook) and 0.0–10.0 otherwise. 0.0 is a real score: it
 * shows its number. Only null reads as "not rated": an empty bar, no number.
 */

/**
 * The rating bar's number: one decimal, a full 10 as "10"; null when [rating]
 * is null, so an unrated bar shows no number at all (never "0.0").
 */
fun ratingLabel(rating: Float?): String? {
    if (rating == null) return null
    val clamped = rating.coerceIn(0f, 10f)
    val roundedTenths = (clamped * 10f).roundToInt()
    if (roundedTenths >= 100) return "10"
    return "%d.%d".format(roundedTenths / 10, roundedTenths % 10)
}

/** How much of the bar [rating] fills, 0..1; an unrated bar is empty, like a 0.0. */
fun ratingFillFraction(rating: Float?): Float = ((rating ?: 0f) / 10f).coerceIn(0f, 1f)
