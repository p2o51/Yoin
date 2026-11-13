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
        assertEquals(50f, point.speedScore!!, 0.01f)
        assertEquals(0.7f * 90f + 0.3f * 50f, point.overall!!, 0.01f)
    }

    @Test
    fun should_keepTheSpeedScoreBetweenZeroAndAHundred() {
        val instant = AiTierPoint(AiProvider.Gemini, AiTier.Good, accuracy = 90f, seconds = 0.4f, costPer1000 = 1f)
        val slow = instant.copy(seconds = 14f)

        assertEquals(100f, instant.speedScore!!, 0f)
        assertEquals(0f, slow.speedScore!!, 0f)
    }

    @Test
    fun should_haveNoOverallScore_when_theTierWasNotMeasured() {
        val untested = AiTierBenchmark.points.first { !it.measured }

        assertNull(untested.overall)
        assertNull(AiAxis.Overall.fraction(untested))
    }

    @Test
    fun should_keepEveryMeasuredTierInsideThePlot_inEveryView() {
        val measured = AiTierBenchmark.points.filter { it.measured }

        AiChartViews.forEach { (x, y) ->
            measured.forEach { point ->
                listOf(x, y).forEach { axis ->
                    val fraction = axis.fraction(point)!!
                    assertTrue("$axis clips ${point.provider} ${point.tier}", fraction > 0f && fraction < 1f)
                }
            }
        }
    }

    @Test
    fun should_putOneNameOnTheLeft_when_aServicesTwoTiersNearlyTouch() {
        val good = AiTierBenchmark.point(AiProvider.Gemini, AiTier.Good)!!
        val best = AiTierBenchmark.point(AiProvider.Gemini, AiTier.Best)!!

        AiChartViews.forEach { (x, y) ->
            val left = listOf(good, best).filter { AiTierBenchmark.labelOnLeft(it, x, y) }
            assertEquals("$x / $y", 1, left.size)
            // The one further left carries it.
            val other = if (left.single() === good) best else good
            assertTrue(x.fraction(left.single())!! <= x.fraction(other)!!)
        }
    }

    @Test
    fun should_keepBothNamesOnTheRight_when_theTiersAreFarApart() {
        val good = AiTierBenchmark.point(AiProvider.Qwen, AiTier.Good)!!
        val best = AiTierBenchmark.point(AiProvider.Qwen, AiTier.Best)!!
        val (x, y) = AiChartViews.first()

        assertFalse(AiTierBenchmark.labelOnLeft(good, x, y))
        assertFalse(AiTierBenchmark.labelOnLeft(best, x, y))
    }

    @Test
    fun should_keepTheNameOnTheRight_when_theOtherTierIsUntested() {
        val deepSeek = AiTierBenchmark.point(AiProvider.DeepSeek, AiTier.Good)!!
        val (x, y) = AiChartViews.first()

        assertFalse(AiTierBenchmark.labelOnLeft(deepSeek, x, y))
    }
}
