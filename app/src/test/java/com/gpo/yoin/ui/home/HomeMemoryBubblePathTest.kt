package com.gpo.yoin.ui.home

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** [speechBubblePath] on real path math (the plain JVM tests stub android.graphics). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeMemoryBubblePathTest {

    @Test
    fun should_keepOutlineInsideBounds_when_tipAnywhere() {
        val density = Density(2.625f)
        val size = Size(600f, 225f) // 54dp body + 28dp horn + sag at 2.625x
        for (tip in listOf(0f, 40f, 79f, 120f)) {
            // Sample the curve itself (Path bounds include control points,
            // which an arc's approximation pushes past the circle).
            val measure = PathMeasure().apply { setPath(speechBubblePath(size, tip, density), forceClosed = true) }
            val points = (0..800).map { i -> measure.getPosition(measure.length * i / 800f) }
            val left = points.minOf { it.x }
            val right = points.maxOf { it.x }
            val top = points.minOf { it.y }
            val bottom = points.maxOf { it.y }
            assertTrue("tip $tip: $left..$right", left >= -0.5f && right <= size.width + 0.5f)
            assertTrue("tip $tip: $top..$bottom", top >= -0.5f && bottom <= size.height + 0.5f)
            assertTrue("tip $tip: spans the box", right - left > 590f && bottom - top > 215f)
            // The tail reaches the top edge: that's where the arrow sits.
            assertEquals(0f, top, 0.75f)
        }
    }
}
