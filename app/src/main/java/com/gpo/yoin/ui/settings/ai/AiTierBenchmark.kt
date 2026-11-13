package com.gpo.yoin.ui.settings.ai

import com.gpo.yoin.data.remote.ai.AiProvider
import com.gpo.yoin.data.remote.ai.AiTier

/**
 * The numbers behind the AI page's chart and the tier page.
 *
 * Source: the 500-song benchmark of 2026-11-13 (499 songs after duplicates),
 * About prompt in Simplified Chinese, no thinking. [seconds] is the median over
 * all of them. [accuracy] is the mean of two scores a Gemini agent gave every
 * answer against reference answers: the five fields (release date, place,
 * lyricist, composer, producer; fields no public source settles left out, an
 * empty field counting as zero) and the review paragraph's factuality.
 * [costPer1000] is US$ for 1,000 About calls at list prices, tokens plus
 * search; Gemini's assumes the monthly 5,000 free searches, and 3.5
 * Flash-Lite's token price is a third party's figure. Qwen's is converted from
 * yuan at 7.1. DeepSeek's two-search tier was not run on this set: its numbers
 * are estimates ([estimated]). Qwen has one tier ([AiProvider.tiers]).
 */
data class AiTierPoint(
    val provider: AiProvider,
    val tier: AiTier,
    val accuracy: Float,
    val seconds: Float,
    val costPer1000: Float,
    /** Only the cost is an estimate (Gemini 3.5 Flash-Lite's third-party token price). */
    val costEstimated: Boolean = false,
    /** Every number is an estimate: the tier was not run on the benchmark. */
    val estimated: Boolean = false,
) {
    /** 0–100: [FASTEST_SECONDS] or less is 100, [SLOWEST_SECONDS] or more is 0. */
    val speedScore: Float
        get() = ((SLOWEST_SECONDS - seconds) / (SLOWEST_SECONDS - FASTEST_SECONDS) * 100f).coerceIn(0f, 100f)

    /** 0–100: accuracy and speed in one number, accuracy weighing more (About is written once). */
    val overall: Float
        get() = ACCURACY_WEIGHT * accuracy + (1f - ACCURACY_WEIGHT) * speedScore

    companion object {
        const val FASTEST_SECONDS = 1f
        const val SLOWEST_SECONDS = 10f
        const val ACCURACY_WEIGHT = 0.7f
    }
}

object AiTierBenchmark {
    val points: List<AiTierPoint> = listOf(
        AiTierPoint(AiProvider.Gemini, AiTier.Good, accuracy = 95f, seconds = 2.8f, costPer1000 = 0.54f),
        AiTierPoint(AiProvider.Gemini, AiTier.Best, accuracy = 95f, seconds = 2.6f, costPer1000 = 0.95f, costEstimated = true),
        AiTierPoint(AiProvider.DeepSeek, AiTier.Good, accuracy = 92f, seconds = 3.7f, costPer1000 = 2.89f),
        // Not run on the 500 songs, so estimated (owner 2026-11-14: "a little stronger; it searches
        // one more time"). In the 35-song test of 2026-11-12, Best was +2 accuracy points, +0.7 s and
        // x1.35 cost over Good. Applied to Good's 500-song 92 / 3.7 s / $2.89, with the accuracy gain
        // halved because the 500-song accuracy averages two scores: 93 / 4.4 s / $3.9.
        AiTierPoint(
            AiProvider.DeepSeek,
            AiTier.Best,
            accuracy = 93f,
            seconds = 4.4f,
            costPer1000 = 3.9f,
            estimated = true,
        ),
        AiTierPoint(AiProvider.Qwen, AiTier.Good, accuracy = 83f, seconds = 4.9f, costPer1000 = 0.57f),
    )

    fun point(provider: AiProvider, tier: AiTier): AiTierPoint? =
        points.firstOrNull { it.provider == provider && it.tier == tier }
}

/** One of the chart's axes. Every axis reads "better" toward the top / right. */
enum class AiAxis(val min: Float, val max: Float, val higherIsBetter: Boolean) {
    Speed(min = AiTierPoint.FASTEST_SECONDS, max = AiTierPoint.SLOWEST_SECONDS, higherIsBetter = false),
    Cost(min = 0f, max = 4f, higherIsBetter = false),
    Accuracy(min = 70f, max = 100f, higherIsBetter = true),

    /** [AiTierPoint.overall]: accuracy and speed together. */
    Overall(min = 50f, max = 100f, higherIsBetter = true),
    ;

    fun value(point: AiTierPoint): Float = when (this) {
        Speed -> point.seconds
        Cost -> point.costPer1000
        Accuracy -> point.accuracy
        Overall -> point.overall
    }

    /** 0 = worst edge, 1 = best edge. */
    fun fraction(point: AiTierPoint): Float {
        val f = ((value(point) - min) / (max - min)).coerceIn(0f, 1f)
        return if (higherIsBetter) f else 1f - f
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
