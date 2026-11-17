package com.gpo.yoin.ui.settings.ai

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size

/**
 * Where the AI chart puts a tier's name beside its dot. A name goes on the dot's right if it fits
 * there, otherwise on its left, above or below (centred, then running off to either side): the
 * first of those that stays inside the plot and covers no dot and no name placed before it.
 * Gemini's two tiers sit almost on top of each other and a phone's plot is narrow, so the right
 * side alone runs names off the plot or over the neighbouring dots.
 */
internal object AiChartLabels {

    /**
     * Each of [labels] (an index into [dots] and the name's size), in order, as the name's top-left
     * relative to its dot's centre. Nothing fits: the side that covers the least, the right first.
     * [gap] is from the dot's centre to the name; [dotRadius] is how much of a dot the names keep off.
     */
    fun place(
        plot: Rect,
        dots: List<Offset>,
        dotRadius: Float,
        gap: Float,
        labels: List<Pair<Int, Size>>,
    ): List<Offset> {
        val placed = mutableListOf<Rect>()
        return labels.map { (index, size) ->
            val centre = dots[index]
            val sides = listOf(
                Offset(gap, -size.height / 2f),
                Offset(-gap - size.width, -size.height / 2f),
                Offset(-size.width / 2f, -gap - size.height),
                Offset(-size.width / 2f, gap),
                // Above or below, running off to one side: for a dot near the plot's edge with a
                // neighbour beside it (DeepSeek's two tiers at the cheap end on a phone).
                Offset(0f, -gap - size.height),
                Offset(0f, gap),
                Offset(-size.width, -gap - size.height),
                Offset(-size.width, gap),
            )
            val best = sides.minBy { side ->
                val box = Rect(centre + side, size)
                var clashes = 0
                if (box.left < plot.left || box.right > plot.right || box.top < plot.top || box.bottom > plot.bottom) {
                    clashes++
                }
                dots.forEachIndexed { i, dot -> if (i != index && box.touches(dot, dotRadius)) clashes++ }
                clashes += placed.count { it.overlaps(box) }
                clashes
            }
            placed += Rect(centre + best, size)
            best
        }
    }

    private fun Rect.touches(centre: Offset, radius: Float): Boolean {
        val nearX = centre.x.coerceIn(left, right)
        val nearY = centre.y.coerceIn(top, bottom)
        val dx = centre.x - nearX
        val dy = centre.y - nearY
        return dx * dx + dy * dy < radius * radius
    }
}
