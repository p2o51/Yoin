package com.gpo.yoin.ui.settings.ai

import com.gpo.yoin.data.remote.ai.AiProvider
import com.gpo.yoin.data.remote.ai.AiTier

/**
 * The numbers behind the AI page's chart and the tier page.
 *
 * Source: 35 hard songs checked by hand on 2026-11-12, About prompt in
 * Simplified Chinese, no thinking; [accuracy] is the share of 66 checkable
 * credit/date fields answered right. Replace with the 500-song benchmark when
 * it has run. [costPer1000] is US$ for 1,000 About calls at list prices;
 * Gemini's assumes the monthly 5,000 free searches, and 3.5 Flash-Lite's
 * price was unpublished, so its cost is estimated at twice 3.1's.
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
}

object AiTierBenchmark {
    val points: List<AiTierPoint> = listOf(
        AiTierPoint(AiProvider.Gemini, AiTier.Good, accuracy = 73f, seconds = 2.8f, costPer1000 = 0.6f),
        AiTierPoint(AiProvider.Gemini, AiTier.Best, accuracy = 83f, seconds = 2.6f, costPer1000 = 1.2f, costEstimated = true),
        AiTierPoint(AiProvider.DeepSeek, AiTier.Good, accuracy = 83f, seconds = 3.7f, costPer1000 = 2.6f),
        AiTierPoint(AiProvider.DeepSeek, AiTier.Best, accuracy = 85f, seconds = 4.4f, costPer1000 = 3.5f),
        // Not tested yet: drawn as hollow dots.
        AiTierPoint(AiProvider.Qwen, AiTier.Good, accuracy = null, seconds = null, costPer1000 = null),
        AiTierPoint(AiProvider.Qwen, AiTier.Best, accuracy = null, seconds = null, costPer1000 = null),
    )

    fun point(provider: AiProvider, tier: AiTier): AiTierPoint? =
        points.firstOrNull { it.provider == provider && it.tier == tier }
}

/** One of the chart's axes. Every axis reads "better" toward the top / right. */
enum class AiAxis(val min: Float, val max: Float, val higherIsBetter: Boolean) {
    Speed(min = 2f, max = 5f, higherIsBetter = false),
    Cost(min = 0f, max = 4f, higherIsBetter = false),
    Accuracy(min = 60f, max = 90f, higherIsBetter = true);

    fun value(point: AiTierPoint): Float? = when (this) {
        Speed -> point.seconds
        Cost -> point.costPer1000
        Accuracy -> point.accuracy
    }

    /** 0 = worst edge, 1 = best edge. */
    fun fraction(point: AiTierPoint): Float? = value(point)?.let { v ->
        val f = ((v - min) / (max - min)).coerceIn(0f, 1f)
        if (higherIsBetter) f else 1f - f
    }
}

/** The views 「换个角度」 steps through: (x, y). */
val AiChartViews: List<Pair<AiAxis, AiAxis>> = listOf(
    AiAxis.Speed to AiAxis.Accuracy,
    AiAxis.Cost to AiAxis.Accuracy,
    AiAxis.Speed to AiAxis.Cost,
)
