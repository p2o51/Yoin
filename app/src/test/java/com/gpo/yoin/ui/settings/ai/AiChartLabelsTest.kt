package com.gpo.yoin.ui.settings.ai

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.gpo.yoin.data.remote.ai.AiProvider
import com.gpo.yoin.data.remote.ai.AiTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chart's tier names, in dp at density 1: the page's plot (52 dp of y values on the left, 8 dp
 * on the right and top, 34 dp of x axis below a 200 dp canvas) on a 360 dp phone and on the Pixel
 * Tablet both ways. Name sizes are a little over what labelMedium measures, in bold.
 */
class AiChartLabelsTest {

    private val canvasWidths = listOf(328f, 768f, 1248f)
    private val nameSizes = mapOf(
        "en" to mapOf(AiTier.Good to Size(36f, 16f), AiTier.Best to Size(32f, 16f)),
        "zh" to mapOf(AiTier.Good to Size(14f, 16f), AiTier.Best to Size(27f, 16f)),
    )
    private val dotRadius = 9.5f
    private val gap = 10f

    private fun plot(width: Float) = Rect(52f, 8f, width - 8f, 200f - 34f)

    private fun dots(plot: Rect, x: AiAxis, y: AiAxis, points: List<AiTierPoint>) = points.map {
        Offset(plot.left + x.fraction(it)!! * plot.width, plot.bottom - y.fraction(it)!! * plot.height)
    }

    // The hollow dot of DeepSeek's untested tier and its 「Not tested」.
    private fun obstacles(plot: Rect): List<Rect> {
        val hollow = Offset(plot.left + 14f, plot.top + 14f)
        return listOf(Rect(hollow, 7.5f), Rect(Offset(hollow.x + 14f, hollow.y - 7f), Size(56f, 14f)))
    }

    @Test
    fun should_keepEveryNameInsideThePlotAndOffTheDots_inEveryViewWidthAndLanguage() {
        val measured = AiTierBenchmark.points.filter { it.measured }
        val named = listOf(AiProvider.Gemini, AiProvider.DeepSeek)

        canvasWidths.forEach { width ->
            val plot = plot(width)
            nameSizes.forEach { (language, sizes) ->
                AiChartViews.forEach { (x, y) ->
                    val dots = dots(plot, x, y, measured)
                    named.forEach { provider ->
                        val mine = measured.indices.filter { measured[it].provider == provider }
                        val labels = mine.map { it to sizes.getValue(measured[it].tier) }
                        val placed = AiChartLabels.place(plot, dots, dotRadius, gap, obstacles(plot), labels)
                        val boxes = labels.mapIndexed { k, (i, size) -> Rect(dots[i] + placed[k], size) }
                        val case = "$width dp, $language, $x / $y, $provider"

                        boxes.forEachIndexed { k, box ->
                            assertTrue("$case: off the plot $box", box.left >= plot.left && box.right <= plot.right)
                            assertTrue("$case: off the plot $box", box.top >= plot.top && box.bottom <= plot.bottom)
                            dots.forEachIndexed { d, dot ->
                                if (d != labels[k].first) {
                                    val nx = dot.x.coerceIn(box.left, box.right)
                                    val ny = dot.y.coerceIn(box.top, box.bottom)
                                    val far = (dot - Offset(nx, ny)).getDistance() >= dotRadius
                                    assertTrue("$case: name over ${measured[d].provider} ${measured[d].tier}", far)
                                }
                            }
                            obstacles(plot).forEach { assertFalse("$case: over the untested dot", it.overlaps(box)) }
                            boxes.forEachIndexed { o, other ->
                                if (o != k) assertFalse("$case: names overlap", other.overlaps(box))
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun should_putTheNameOnTheRight_when_itFitsThere() {
        val plot = Rect(0f, 0f, 300f, 200f)
        val dots = listOf(Offset(100f, 100f))

        val placed = AiChartLabels.place(plot, dots, dotRadius, gap, emptyList(), listOf(0 to Size(30f, 16f)))

        assertEquals(Offset(10f, -8f), placed.single())
    }

    @Test
    fun should_moveTheName_when_theRightRunsOffThePlotOrOverADot() {
        val plot = Rect(0f, 0f, 300f, 200f)
        val size = Size(30f, 16f)

        // Too near the right edge: the left.
        val edge = listOf(Offset(280f, 100f))
        val atEdge = AiChartLabels.place(plot, edge, dotRadius, gap, emptyList(), listOf(0 to size))
        assertEquals(Offset(-40f, -8f), atEdge.single())

        // A dot just to the right, and one just to the left: above.
        val boxedIn = listOf(Offset(100f, 100f), Offset(125f, 100f), Offset(75f, 100f))
        val above = AiChartLabels.place(plot, boxedIn, dotRadius, gap, emptyList(), listOf(0 to size))
        assertEquals(Offset(-15f, -26f), above.single())
    }

    @Test
    fun should_keepTwoNamesApart_when_theirDotsNearlyTouch() {
        val plot = Rect(0f, 0f, 300f, 200f)
        val dots = listOf(Offset(150f, 100f), Offset(156f, 104f))
        val size = Size(30f, 16f)

        val placed = AiChartLabels.place(plot, dots, dotRadius, gap, emptyList(), listOf(0 to size, 1 to size))

        assertFalse(Rect(dots[0] + placed[0], size).overlaps(Rect(dots[1] + placed[1], size)))
    }
}
