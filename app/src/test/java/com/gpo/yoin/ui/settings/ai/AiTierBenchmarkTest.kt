package com.gpo.yoin.ui.settings.ai

import com.gpo.yoin.data.remote.ai.AiProvider
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
                    val fraction = axis.fraction(point)
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
}
