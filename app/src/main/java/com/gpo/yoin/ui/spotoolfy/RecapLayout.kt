package com.gpo.yoin.ui.spotoolfy

import com.gpo.yoin.data.migration.spotoolfy.RecapEmoji
import com.gpo.yoin.data.migration.spotoolfy.RecapKeyword
import kotlin.math.cos
import kotlin.math.sin

/** A placed box, in px from the area's top left. */
internal data class RecapBox(val x: Float, val y: Float, val width: Float, val height: Float) {
    fun overlaps(other: RecapBox, gap: Float): Boolean =
        x < other.x + other.width + gap && x + width + gap > other.x &&
            y < other.y + other.height + gap && y + height + gap > other.y
}

/** The keyword burst's size steps, by rank: three big words, then six, nine, and the rest. */
internal enum class RecapWordTier { Largest, Large, Medium, Small }

internal fun recapWordTier(rank: Int): RecapWordTier = when {
    rank < 3 -> RecapWordTier.Largest
    rank < 9 -> RecapWordTier.Large
    rank < 18 -> RecapWordTier.Medium
    else -> RecapWordTier.Small
}

/** One chip of the burst: a keyword or one of the few emoji. */
internal data class RecapWord(val text: String, val count: Int, val tier: RecapWordTier, val keyword: RecapKeyword?)

/**
 * The burst's words in rank order, the emoji tucked in among the first few (they read as words, and an emoji only
 * at the end would sit at the rim).
 */
internal fun recapWords(keywords: List<RecapKeyword>, emoji: List<RecapEmoji>): List<RecapWord> {
    val words = keywords.mapIndexedTo(ArrayList()) { rank, keyword ->
        RecapWord(keyword.text, keyword.count, recapWordTier(rank), keyword)
    }
    emoji.forEachIndexed { i, e ->
        val at = minOf(words.size, EMOJI_FIRST_SLOT + i * EMOJI_SPACING)
        words.add(at, RecapWord(e.emoji, e.count, RecapWordTier.Medium, keyword = null))
    }
    return words
}

/**
 * Lays [sizes] out from the area's centre along an outward spiral, in order, around [obstacles] (the lyric
 * cards). The first word lands nearest the centre. A word that finds no free spot inside [width] x [height] gets
 * null and is not shown. Deterministic: the same words on the same area land in the same places, so rotation
 * redraws the same picture.
 */
internal fun spiralPlacements(
    sizes: List<Pair<Float, Float>>,
    width: Float,
    height: Float,
    obstacles: List<RecapBox>,
    gap: Float,
    step: Float,
): List<RecapBox?> {
    val placed = obstacles.toMutableList()
    val cx = width / 2f
    val cy = height / 2f
    return sizes.map { (w, h) ->
        var t = 0f
        var found: RecapBox? = null
        while (found == null && t < SPIRAL_TURNS) {
            val r = step * t
            val box = RecapBox(
                x = cx + r * cos(t) * SPIRAL_STRETCH_X - w / 2f,
                y = cy + r * sin(t) * SPIRAL_STRETCH_Y - h / 2f,
                width = w,
                height = h,
            )
            val inside = box.x >= 0f && box.y >= 0f && box.x + w <= width && box.y + h <= height
            if (inside && placed.none { it.overlaps(box, gap) }) found = box
            t += SPIRAL_DT
        }
        found?.also { placed += it }
    }
}

private const val EMOJI_FIRST_SLOT = 4
private const val EMOJI_SPACING = 5

/** The spiral's angle range and resolution (radians), and its slight widening into an ellipse. */
private const val SPIRAL_TURNS = 120f
private const val SPIRAL_DT = 0.09f
private const val SPIRAL_STRETCH_X = 1.05f
private const val SPIRAL_STRETCH_Y = 1.18f
