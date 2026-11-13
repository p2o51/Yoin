package com.gpo.yoin.ui.settings.ai

import com.gpo.yoin.data.remote.ai.AiProvider
import com.gpo.yoin.data.remote.ai.AiTier
import kotlin.math.abs

/**
 * The numbers behind the AI page's chart and the tier page.
 *
 * Source: the 500-song benchmark of 2026-11-13 (499 songs after duplicates),
 * About prompt in Simplified Chinese, no thinking. [seconds] is the median over
 * all of them. [accuracy] comes from 109 of them checked against search
 * results by one judge model: (right + half of partly right) over the release
 * date, lyricist, composer and producer fields, an empty field counting as
 * zero. [costPer1000] is US$ for 1,000 About calls at list prices, tokens plus
 * search; Gemini's assumes the monthly 5,000 free searches, and 3.5
 * Flash-Lite's token price is a third party's figure. Qwen's is converted from
 * yuan at 7.1. DeepSeek's two-search tier was not run on this set. Qwen has
 * one tier ([AiProvider.tiers]).
 */
data class AiTierPoint(
    val provider: AiProvider,
    val tier: AiTier,
    val accuracy: Float?,
    val seconds: Float?,
    val costPer1000: Float?,
    val costEstimated: Boolean = false,
) {
    val measured: Boolean get() = accuracy != null && seconds != null && costPer1000 != null

    /** 0–100: [FASTEST_SECONDS] or less is 100, [SLOWEST_SECONDS] or more is 0. */
    val speedScore: Float?
        get() = seconds?.let {
            ((SLOWEST_SECONDS - it) / (SLOWEST_SECONDS - FASTEST_SECONDS) * 100f).coerceIn(0f, 100f)
        }

    /** 0–100: accuracy and speed in one number, accuracy weighing more (About is written once). */
    val overall: Float?
        get() {
            val quality = accuracy ?: return null
            val speed = speedScore ?: return null
            return ACCURACY_WEIGHT * quality + (1f - ACCURACY_WEIGHT) * speed
        }

    companion object {
        const val FASTEST_SECONDS = 1f
        const val SLOWEST_SECONDS = 10f
        const val ACCURACY_WEIGHT = 0.7f
    }
}

object AiTierBenchmark {
    val points: List<AiTierPoint> = listOf(
        AiTierPoint(AiProvider.Gemini, AiTier.Good, accuracy = 94f, seconds = 2.8f, costPer1000 = 0.54f),
        AiTierPoint(AiProvider.Gemini, AiTier.Best, accuracy = 93f, seconds = 2.6f, costPer1000 = 0.95f, costEstimated = true),
        AiTierPoint(AiProvider.DeepSeek, AiTier.Good, accuracy = 92f, seconds = 3.7f, costPer1000 = 2.89f),
        // Not run on the 500 songs: drawn as a hollow dot.
        AiTierPoint(AiProvider.DeepSeek, AiTier.Best, accuracy = null, seconds = null, costPer1000 = null),
        AiTierPoint(AiProvider.Qwen, AiTier.Good, accuracy = 81f, seconds = 4.9f, costPer1000 = 0.57f),
    )

    fun point(provider: AiProvider, tier: AiTier): AiTierPoint? =
        points.firstOrNull { it.provider == provider && it.tier == tier }

    /**
     * Whether [point]'s name sits on the left of its dot in the view ([x], [y]). Names go on the
     * right; when a service's two tiers land so close that both names there would run into each
     * other (Gemini's are 0.2 s and one point apart), the dot further left takes the left side.
     */
    fun labelOnLeft(point: AiTierPoint, x: AiAxis, y: AiAxis): Boolean {
        val twin = points.firstOrNull { it.provider == point.provider && it.tier != point.tier && it.measured }
            ?: return false
        val px = x.fraction(point) ?: return false
        val py = y.fraction(point) ?: return false
        val tx = x.fraction(twin) ?: return false
        val ty = y.fraction(twin) ?: return false
        if (abs(px - tx) >= LABEL_CLASH_X || abs(py - ty) >= LABEL_CLASH_Y) return false
        return px < tx || (px == tx && point.tier == AiTier.Good)
    }

    // Fractions of the plot: about a name's width and height on a phone.
    private const val LABEL_CLASH_X = 0.16f
    private const val LABEL_CLASH_Y = 0.12f
}

/** One of the chart's axes. Every axis reads "better" toward the top / right. */
enum class AiAxis(val min: Float, val max: Float, val higherIsBetter: Boolean) {
    Speed(min = AiTierPoint.FASTEST_SECONDS, max = AiTierPoint.SLOWEST_SECONDS, higherIsBetter = false),
    Cost(min = 0f, max = 4f, higherIsBetter = false),
    Accuracy(min = 70f, max = 100f, higherIsBetter = true),

    /** [AiTierPoint.overall]: accuracy and speed together. */
    Overall(min = 50f, max = 100f, higherIsBetter = true),
    ;

    fun value(point: AiTierPoint): Float? = when (this) {
        Speed -> point.seconds
        Cost -> point.costPer1000
        Accuracy -> point.accuracy
        Overall -> point.overall
    }

    /** 0 = worst edge, 1 = best edge. */
    fun fraction(point: AiTierPoint): Float? = value(point)?.let { v ->
        val f = ((v - min) / (max - min)).coerceIn(0f, 1f)
        if (higherIsBetter) f else 1f - f
    }
}

/**
 * The views 「换个角度」 steps through: (x, y). The first is what the page opens on: what it costs
 * against how good it is overall. The other three set the plain measures against each other.
 */
val AiChartViews: List<Pair<AiAxis, AiAxis>> = listOf(
    AiAxis.Cost to AiAxis.Overall,
    AiAxis.Speed to AiAxis.Accuracy,
    AiAxis.Cost to AiAxis.Accuracy,
    AiAxis.Speed to AiAxis.Cost,
)
