package com.gpo.yoin.ui.nowplaying

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LyricsViewportTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun should_measureListOnceAndKeepFocusPosition_whenStageExpandsAndReverses() {
        checkViewport(LyricsFocusFraction)
    }

    @Test
    fun should_keepTopAnchor_whenLyricsAreUntimedOrManuallyScrolled() {
        checkViewport(0f)
    }

    private fun checkViewport(focusFraction: Float) {
        var visibleHeight by mutableIntStateOf(180)
        var measureCount = 0
        var measuredHeight = 0
        var markerY = 0f
        var density = 1f
        rule.setContent {
            density = LocalDensity.current.density
            val growth = with(LocalDensity.current) {
                600.dp.roundToPx() - visibleHeight.dp.roundToPx()
            }
            val probePolicy = remember {
                MeasurePolicy { measurables, constraints ->
                    measureCount++
                    measuredHeight = constraints.maxHeight
                    val marker = measurables.single().measure(constraints.copy(minHeight = 0, maxHeight = 1))
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        marker.place(0, (constraints.maxHeight * focusFraction).toInt())
                    }
                }
            }
            Box(Modifier.width(300.dp).height(visibleHeight.dp)) {
                Box(Modifier.lyricsViewport(growth, focusFraction)) {
                    Layout(
                        content = {
                            Box(Modifier.onGloballyPositioned { markerY = it.positionInRoot().y })
                        },
                        modifier = Modifier.fillMaxSize(),
                        measurePolicy = probePolicy,
                    )
                }
            }
        }
        rule.waitForIdle()
        val initialMeasures = measureCount
        // Includes a mid-flight reversal, then expansion all the way to rest.
        for (height in (200..500 step 20) + (480 downTo 200 step 20) + (220..600 step 20)) {
            rule.runOnIdle { visibleHeight = height }
            rule.waitForIdle()
            assertEquals("List height must remain stable", (600 * density).toInt(), measuredHeight)
            assertEquals("Focus must follow the visible viewport", height * density * focusFraction, markerY, 2f)
        }
        assertEquals("Animation must not remeasure the list", initialMeasures, measureCount)
    }
}
