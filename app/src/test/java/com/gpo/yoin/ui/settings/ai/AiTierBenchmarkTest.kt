package com.gpo.yoin.ui.settings.ai

import com.gpo.yoin.data.remote.ai.AiProvider
import com.gpo.yoin.data.remote.ai.AiSearchService
import com.gpo.yoin.data.remote.ai.AiTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chart's scores and the order of its views. */
class AiTierBenchmarkTest {

    @Test
    fun should_openOnCostAgainstTheOverallScore() {
        assertEquals(AiAxis.Cost to AiAxis.Overall, AiChartViews.first())
    }

    @Test
    fun should_pairThePlainMeasures_inTheOtherThreeViews() {
        val rest = AiChartViews.drop(1)

        assertEquals(3, rest.size)
        assertTrue(rest.none { (x, y) -> x == AiAxis.Overall || y == AiAxis.Overall })
        assertEquals(
            setOf(
                setOf(AiAxis.Speed, AiAxis.Accuracy),
                setOf(AiAxis.Cost, AiAxis.Accuracy),
                setOf(AiAxis.Speed, AiAxis.Cost),
            ),
            rest.map { (x, y) -> setOf(x, y) }.toSet(),
        )
    }

    @Test
    fun should_weighAccuracySevenToThree_inTheOverallScore() {
        val point = AiTierPoint(AiProvider.Gemini, AiTier.Good, accuracy = 90f, seconds = 5.5f, costPer1000 = 1f)

        // 5.5 s is halfway between 1 s (100) and 10 s (0).
        assertEquals(50f, point.speedScore, 0.01f)
        assertEquals(0.7f * 90f + 0.3f * 50f, point.overall, 0.01f)
    }

    @Test
    fun should_keepTheSpeedScoreBetweenZeroAndAHundred() {
        val instant = AiTierPoint(AiProvider.Gemini, AiTier.Good, accuracy = 90f, seconds = 0.4f, costPer1000 = 1f)
        val slow = instant.copy(seconds = 14f)

        assertEquals(100f, instant.speedScore, 0f)
        assertEquals(0f, slow.speedScore, 0f)
    }

    @Test
    fun should_placeDeepSeekBestAsAnEstimate_aLittleAboveGood() {
        val good = AiTierBenchmark.point(AiProvider.DeepSeek, AiTier.Good)!!
        val best = AiTierBenchmark.point(AiProvider.DeepSeek, AiTier.Best)!!

        assertTrue(best.estimated)
        assertFalse(good.estimated)
        assertEquals(93f, best.accuracy, 0f)
        assertEquals(4.4f, best.seconds, 0f)
        assertEquals(3.9f, best.costPer1000, 0f)
        // One point more accurate, a little slower and dearer: overall it lands just below Good.
        assertEquals(0.7f * 93f + 0.3f * (10f - 4.4f) / 9f * 100f, best.overall, 0.01f)
        assertTrue(best.accuracy > good.accuracy && best.seconds > good.seconds && best.costPer1000 > good.costPer1000)
    }

    @Test
    fun should_markOnlyDeepSeekBestAsEstimated() {
        assertEquals(
            listOf(AiProvider.DeepSeek to AiTier.Best),
            AiTierBenchmark.points.filter { it.estimated }.map { it.provider to it.tier },
        )
    }

    @Test
    fun should_keepEveryTierInsideThePlot_inEveryView() {
        val points = AiTierBenchmark.points

        AiChartViews.forEach { (x, y) ->
            points.forEach { point ->
                listOf(x, y).forEach { axis ->
                    val fraction = axis.fraction(point, AiChartSet.Tiers)
                    assertTrue("$axis clips ${point.provider} ${point.tier}", fraction > 0f && fraction < 1f)
                }
            }
        }
    }

    @Test
    fun should_onlyHoldTiersTheirServiceOffers() {
        AiTierBenchmark.points.forEach { point ->
            assertTrue("${point.provider} ${point.tier}", point.tier in point.provider.tiers)
        }
        // Qwen has one tier, and it was measured.
        assertFalse(AiTierBenchmark.point(AiProvider.Qwen, AiTier.Good)!!.estimated)
        assertNull(AiTierBenchmark.point(AiProvider.Qwen, AiTier.Best))
    }

    @Test
    fun should_holdTheMeasuredSearchNumbers_withDeepSeekWritingForEach() {
        fun check(service: AiSearchService, accuracy: Float, seconds: Float, cost: Float) {
            val point = AiSearchBenchmark.point(service)
            assertFalse("$service", point.estimated)
            assertEquals("$service accuracy", accuracy, point.accuracy, 0f)
            assertEquals("$service seconds", seconds, point.seconds, 0f)
            assertEquals("$service cost", cost, point.costPer1000, 0f)
        }
        check(AiSearchService.DeepSeek, 91.9f, 3.69f, 2.89f)
        check(AiSearchService.Exa, 92.7f, 3.94f, 9.04f)
        check(AiSearchService.Doubao, 88.2f, 4.47f, 2.38f)
        check(AiSearchService.None, 65.0f, 2.40f, 0.32f)
        // Each accuracy is the mean of the report's field and review scores.
        assertEquals((85.0f + 98.8f) / 2f, AiSearchBenchmark.point(AiSearchService.DeepSeek).accuracy, 0.05f)
        assertEquals((86.5f + 98.9f) / 2f, AiSearchBenchmark.point(AiSearchService.Exa).accuracy, 0.05f)
        assertEquals((79.4f + 97.0f) / 2f, AiSearchBenchmark.point(AiSearchService.Doubao).accuracy, 0.05f)
        assertEquals((41.3f + 88.7f) / 2f, AiSearchBenchmark.point(AiSearchService.None).accuracy, 0.05f)
    }

    @Test
    fun should_estimateTavilyAndBrave_fromTheSideTestsHitRates() {
        // Field 85.0 x hit / 83.3, review taken as DeepSeek search's 98.8, then the mean.
        assertEquals(88.07f, AiSearchBenchmark.estimateAccuracy(AiSearchBenchmark.TAVILY_SIDE_HIT), 0.01f)
        assertEquals(80.17f, AiSearchBenchmark.estimateAccuracy(AiSearchBenchmark.BRAVE_SIDE_HIT), 0.01f)
        // DeepSeek's own side-test hit rate gives back its measured accuracy.
        assertEquals(91.9f, AiSearchBenchmark.estimateAccuracy(AiSearchBenchmark.DEEPSEEK_SIDE_HIT), 0.01f)

        val tavily = AiSearchBenchmark.point(AiSearchService.Tavily)
        val brave = AiSearchBenchmark.point(AiSearchService.Brave)
        assertTrue(tavily.estimated && brave.estimated)
        assertEquals(Math.round(AiSearchBenchmark.estimateAccuracy(AiSearchBenchmark.TAVILY_SIDE_HIT)).toFloat(), tavily.accuracy, 0f)
        assertEquals(Math.round(AiSearchBenchmark.estimateAccuracy(AiSearchBenchmark.BRAVE_SIDE_HIT)).toFloat(), brave.accuracy, 0f)
        assertEquals(5.7f, tavily.seconds, 0f)
        assertEquals(17.2f, tavily.costPer1000, 0f)
        assertEquals(3.2f, brave.seconds, 0f)
        assertEquals(5.7f, brave.costPer1000, 0f)
        assertEquals(
            setOf(AiSearchService.Tavily, AiSearchService.Brave),
            AiSearchBenchmark.points.filter { it.estimated }.map { it.service }.toSet(),
        )
    }

    @Test
    fun should_holdOnePointPerSearchService_inMenuOrder() {
        assertEquals(AiSearchService.entries.toList(), AiSearchBenchmark.points.map { it.service })
    }

    @Test
    fun should_scoreSearchServicesLikeTiers() {
        val exa = AiSearchBenchmark.point(AiSearchService.Exa)
        val asTier = AiTierPoint(AiProvider.DeepSeek, AiTier.Good, exa.accuracy, exa.seconds, exa.costPer1000)

        assertEquals(asTier.speedScore, exa.speedScore, 0f)
        assertEquals(asTier.overall, exa.overall, 0f)
        assertEquals(0.7f * 92.7f + 0.3f * (10f - 3.94f) / 9f * 100f, exa.overall, 0.01f)
    }

    @Test
    fun should_plotTheSearchServices_onlyForCustom() {
        assertEquals(AiChartSet.Search, AiChartSet.forProvider(AiProvider.Custom))
        listOf(AiProvider.Gemini, AiProvider.DeepSeek, AiProvider.Qwen).forEach {
            assertEquals("$it", AiChartSet.Tiers, AiChartSet.forProvider(it))
        }
        assertEquals(AiTierBenchmark.points, AiChartSet.Tiers.points)
        assertEquals(AiSearchBenchmark.points, AiChartSet.Search.points)
    }

    @Test
    fun should_giveEachSetItsOwnAxisRanges() {
        assertEquals(0f..4f, AiChartSet.Tiers.range(AiAxis.Cost))
        assertEquals(0f..20f, AiChartSet.Search.range(AiAxis.Cost))
        assertEquals(70f..100f, AiChartSet.Tiers.range(AiAxis.Accuracy))
        assertEquals(60f..100f, AiChartSet.Search.range(AiAxis.Accuracy))
        AiChartSet.entries.forEach { set ->
            assertEquals(1f..10f, set.range(AiAxis.Speed))
            assertEquals(50f..100f, set.range(AiAxis.Overall))
        }
        // The same cost sits at different places in the two sets.
        val deepSeek = AiSearchBenchmark.point(AiSearchService.DeepSeek)
        assertEquals(1f - 2.89f / 4f, AiAxis.Cost.fraction(deepSeek, AiChartSet.Tiers), 0.001f)
        assertEquals(1f - 2.89f / 20f, AiAxis.Cost.fraction(deepSeek, AiChartSet.Search), 0.001f)
    }

    @Test
    fun should_keepEverySearchServiceInsideThePlot_inEveryView_withBetterTowardTopRight() {
        AiChartViews.forEach { (x, y) ->
            AiSearchBenchmark.points.forEach { point ->
                listOf(x, y).forEach { axis ->
                    val fraction = axis.fraction(point, AiChartSet.Search)
                    assertTrue("$axis clips ${point.service}", fraction > 0f && fraction < 1f)
                }
            }
        }
        val exa = AiSearchBenchmark.point(AiSearchService.Exa)
        val tavily = AiSearchBenchmark.point(AiSearchService.Tavily)
        val none = AiSearchBenchmark.point(AiSearchService.None)
        // Cheaper and faster sit further right; more accurate further up.
        assertTrue(AiAxis.Cost.fraction(exa, AiChartSet.Search) > AiAxis.Cost.fraction(tavily, AiChartSet.Search))
        assertTrue(AiAxis.Speed.fraction(none, AiChartSet.Search) > AiAxis.Speed.fraction(tavily, AiChartSet.Search))
        assertTrue(AiAxis.Accuracy.fraction(exa, AiChartSet.Search) > AiAxis.Accuracy.fraction(none, AiChartSet.Search))
    }

    @Test
    fun should_printEachSetsOwnAxisEnds() {
        // Cost's worst end is the dear one, on the origin side.
        assertEquals("$4", axisValue(AiAxis.Cost, AiChartSet.Tiers, worst = true))
        assertEquals("$20", axisValue(AiAxis.Cost, AiChartSet.Search, worst = true))
        assertEquals("$0", axisValue(AiAxis.Cost, AiChartSet.Search, worst = false))
        assertEquals("60%", axisValue(AiAxis.Accuracy, AiChartSet.Search, worst = true))
        assertEquals("10s", axisValue(AiAxis.Speed, AiChartSet.Search, worst = true))
    }

    @Test
    fun should_highlightTheChosenSearch_forCustom_andTheTierOtherwise() {
        AiSearchService.entries.forEach { service ->
            assertEquals(
                AiSearchBenchmark.point(service),
                chosenChartPoint(AiProvider.Custom, AiTier.Best, service),
            )
        }
        assertEquals(
            AiTierBenchmark.point(AiProvider.DeepSeek, AiTier.Best),
            chosenChartPoint(AiProvider.DeepSeek, AiTier.Best, AiSearchService.Exa),
        )
    }
}
