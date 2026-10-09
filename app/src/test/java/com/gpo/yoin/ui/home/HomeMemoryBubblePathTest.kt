package com.gpo.yoin.ui.home

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** [memoryBubbleOutline] on real path math (the plain JVM tests stub android.graphics). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeMemoryBubblePathTest {

    // A 412dp phone at 2.625x: body 16..286dp, top 44dp, 60dp tall; tip at 8dp.
    private val d = 2.625f
    private val body = Rect(16f * d, 44f * d, 286f * d, 104f * d)
    private val tailTop = 8f * d

    private fun sample(tailX: Float?): List<androidx.compose.ui.geometry.Offset> {
        val path = Path()
        memoryBubbleOutline(path, body, 30f * d, tailX, tailTop, flank = 46f * d, lean = 6f * d, drop = 18f * d)
        val measure = PathMeasure().apply { setPath(path, forceClosed = true) }
        return (0..1200).map { i -> measure.getPosition(measure.length * i / 1200f) }
    }

    @Test
    fun should_riseToAPointOverTheChevron_when_tailFits() {
        for (tailX in listOf(150f * d, 206f * d)) {
            // Sample the curve itself (Path bounds include control points).
            val points = sample(tailX)
            assertTrue(points.minOf { it.x } >= body.left - 0.5f && points.maxOf { it.x } <= body.right + 0.5f)
            assertEquals(body.bottom, points.maxOf { it.y }, 0.5f)
            // The point is the topmost spot, right over the chevron.
            val top = points.minByOrNull { it.y }!!
            assertEquals(tailTop, top.y, 0.75f)
            assertEquals(tailX, top.x, 0.75f)
        }
    }

    @Test
    fun should_beAPlainRoundedBody_when_noTailOrNoRoom() {
        // No tail; and a tail too close to the left end is left off.
        for (tailX in listOf(null, body.left + 10f * d)) {
            val points = sample(tailX)
            assertEquals(body.top, points.minOf { it.y }, 0.5f)
            assertEquals(body.left, points.minOf { it.x }, 0.5f)
            assertEquals(body.right, points.maxOf { it.x }, 0.5f)
        }
    }
}
