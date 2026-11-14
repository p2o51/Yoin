package com.gpo.yoin.ui.settings.ai

import com.gpo.yoin.data.remote.ai.AiProvider
import com.gpo.yoin.data.remote.ai.AiSearchService
import com.gpo.yoin.data.remote.ai.AiTier

/**
 * What the AI page's chart plots of one dot: a service tier ([AiTierPoint]) or, while Custom is
 * chosen, a search service ([AiSearchPoint]). Both are scored the same way.
 */
sealed interface AiChartPoint {
    val accuracy: Float
    val seconds: Float
    val costPer1000: Float

    /** Every number is an estimate: it was not run on the 499 songs. */
    val estimated: Boolean

    /** 0–100: [AiTierPoint.FASTEST_SECONDS] or less is 100, [AiTierPoint.SLOWEST_SECONDS] or more is 0. */
    val speedScore: Float
        get() = (
            (AiTierPoint.SLOWEST_SECONDS - seconds) /
                (AiTierPoint.SLOWEST_SECONDS - AiTierPoint.FASTEST_SECONDS) * 100f
            ).coerceIn(0f, 100f)

    /** 0–100: accuracy and speed in one number, accuracy weighing more (About is written once). */
    val overall: Float
        get() = AiTierPoint.ACCURACY_WEIGHT * accuracy + (1f - AiTierPoint.ACCURACY_WEIGHT) * speedScore
}

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
    override val accuracy: Float,
    override val seconds: Float,
    override val costPer1000: Float,
    /** Only the cost is an estimate (Gemini 3.5 Flash-Lite's third-party token price). */
    val costEstimated: Boolean = false,
    /** Every number is an estimate: the tier was not run on the benchmark. */
    override val estimated: Boolean = false,
) : AiChartPoint {
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

/**
 * Custom's search services, for the chart while Custom is chosen (owner 2026-11-14): the writing
 * model is fixed and only the search changes, so a dot is what a search service does for About.
 *
 * Source: the same 499-song benchmark of 2026-11-13 (report `yoin_499.html`), writing model
 * deepseek-flash for every service, About prompt in Simplified Chinese, no thinking. [accuracy] is
 * the mean of the field accuracy and the review fact score, as for the tiers; [seconds] the median;
 * [costPer1000] US$ per 1,000 About calls, search plus DeepSeek API tokens.
 *  - DeepSeek search: field 85.0, review 98.8, so 91.9; 3.69 s; $2.89 ($0.75 search + $2.14 tokens).
 *  - Exa (with page text): field 86.5, review 98.9, so 92.7; 3.94 s; $9.04 ($7.00 + $2.04).
 *  - Doubao (Volcengine web search): field 79.4, review 97.0, so 88.2; 4.47 s; $2.38 pay-as-you-go
 *    ($0.56 + $1.82). Inside a monthly search package it is the tokens alone, $1.82.
 *  - No search (deepseek-flash alone): field 41.3, review 88.7, so 65.0; 2.40 s; $0.32.
 *
 * Tavily and Brave were not run on the 499 songs, only in the 35-song side test of the same report
 * (same writer, 66 fields, a field hit rate and no review score): DeepSeek Search 83.3 %, Tavily
 * advanced (snippets) 75.8 % at 5.74 s and $17.17, Brave (summary only) 60.3 % at 3.19 s and $5.68.
 * Their field accuracy is DeepSeek search's 499-song 85.0 scaled by hit rate against DeepSeek's
 * 83.3 %, their review score is taken as DeepSeek search's 98.8, and the two are averaged
 * ([estimateAccuracy]): Tavily 77.3 and 98.8, so 88; Brave 61.5 and 98.8, so 80. Seconds and cost
 * are the side test's own. Yoin's Brave call reads page text through Brave's LLM Context endpoint
 * first, which the side test's summary-only run did not, so Brave may do better than this.
 */
data class AiSearchPoint(
    val service: AiSearchService,
    override val accuracy: Float,
    override val seconds: Float,
    override val costPer1000: Float,
    override val estimated: Boolean = false,
) : AiChartPoint

object AiSearchBenchmark {
    /** DeepSeek search on the 499 songs: the yardstick the side test's estimates are scaled by. */
    const val DEEPSEEK_FIELD = 85.0f
    const val DEEPSEEK_REVIEW = 98.8f

    /** DeepSeek Search's field hit rate in the 35-song side test. */
    const val DEEPSEEK_SIDE_HIT = 83.3f
    const val TAVILY_SIDE_HIT = 75.8f
    const val BRAVE_SIDE_HIT = 60.3f

    /** A 499-song accuracy for a service seen only in the side test, from its field hit rate there. */
    fun estimateAccuracy(sideHitRate: Float): Float =
        (DEEPSEEK_FIELD * sideHitRate / DEEPSEEK_SIDE_HIT + DEEPSEEK_REVIEW) / 2f

    /** In [AiSearchService] order, one per service. */
    val points: List<AiSearchPoint> = listOf(
        AiSearchPoint(AiSearchService.DeepSeek, accuracy = 91.9f, seconds = 3.69f, costPer1000 = 2.89f),
        AiSearchPoint(AiSearchService.Exa, accuracy = 92.7f, seconds = 3.94f, costPer1000 = 9.04f),
        // estimateAccuracy(75.8) = 88.07.
        AiSearchPoint(AiSearchService.Tavily, accuracy = 88f, seconds = 5.7f, costPer1000 = 17.2f, estimated = true),
        // estimateAccuracy(60.3) = 80.17.
        AiSearchPoint(AiSearchService.Brave, accuracy = 80f, seconds = 3.2f, costPer1000 = 5.7f, estimated = true),
        AiSearchPoint(AiSearchService.Doubao, accuracy = 88.2f, seconds = 4.47f, costPer1000 = 2.38f),
        AiSearchPoint(AiSearchService.None, accuracy = 65.0f, seconds = 2.40f, costPer1000 = 0.32f),
    )

    fun point(service: AiSearchService): AiSearchPoint = points.first { it.service == service }
}

/**
 * Which dots the chart shows, each with axis ranges of its own: Custom's search costs run to
 * Tavily's $17, and No search sits far below every tier's accuracy.
 */
enum class AiChartSet {
    Tiers,
    Search,
    ;

    val points: List<AiChartPoint>
        get() = when (this) {
            Tiers -> AiTierBenchmark.points
            Search -> AiSearchBenchmark.points
        }

    /** The axis's worse-or-better ends in this set, min first. */
    fun range(axis: AiAxis): ClosedFloatingPointRange<Float> = when (axis) {
        AiAxis.Speed -> AiTierPoint.FASTEST_SECONDS..AiTierPoint.SLOWEST_SECONDS
        AiAxis.Cost -> if (this == Tiers) 0f..4f else 0f..20f
        AiAxis.Accuracy -> if (this == Tiers) 70f..100f else 60f..100f
        AiAxis.Overall -> 50f..100f
    }

    companion object {
        fun forProvider(provider: AiProvider): AiChartSet =
            if (provider == AiProvider.Custom) Search else Tiers
    }
}

/** One of the chart's axes. Every axis reads "better" toward the top / right. */
enum class AiAxis(val higherIsBetter: Boolean) {
    Speed(higherIsBetter = false),
    Cost(higherIsBetter = false),
    Accuracy(higherIsBetter = true),

    /** [AiChartPoint.overall]: accuracy and speed together. */
    Overall(higherIsBetter = true),
    ;

    fun value(point: AiChartPoint): Float = when (this) {
        Speed -> point.seconds
        Cost -> point.costPer1000
        Accuracy -> point.accuracy
        Overall -> point.overall
    }

    /** 0 = worst edge, 1 = best edge, on [set]'s range for this axis. */
    fun fraction(point: AiChartPoint, set: AiChartSet): Float {
        val range = set.range(this)
        val f = ((value(point) - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
        return if (higherIsBetter) f else 1f - f
    }
}

/**
 * The views 「换个角度」 steps through: (x, y). The first is what the page opens on: what it costs
 * against how good it is overall. The other three set the plain measures against each other.
 * Both sets use the same views.
 */
val AiChartViews: List<Pair<AiAxis, AiAxis>> = listOf(
    AiAxis.Cost to AiAxis.Overall,
    AiAxis.Speed to AiAxis.Accuracy,
    AiAxis.Cost to AiAxis.Accuracy,
    AiAxis.Speed to AiAxis.Cost,
)
