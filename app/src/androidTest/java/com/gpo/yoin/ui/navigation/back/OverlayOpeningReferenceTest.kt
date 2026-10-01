package com.gpo.yoin.ui.navigation.back

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/** Reference copied from the pre-rewrite NowPlayingOverlayHost (1551592f). */
class OverlayOpeningReferenceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun should_matchOriginalPageSpringAndFade_whenOpenedFromRest() {
        var expanded by mutableStateOf(false)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Row(Modifier.size(300.dp, 400.dp).background(Color.White)) {
                Box(Modifier.size(150.dp, 400.dp)) {
                    OverlayPlayerVisibility(expanded, Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().background(Color.Red))
                    }
                }
                Box(Modifier.size(150.dp, 400.dp)) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = expanded,
                        enter = YoinMotion.slideInVertically(role = YoinMotionRole.Expressive) { it } +
                            YoinMotion.fadeIn(role = YoinMotionRole.Standard),
                        exit = ExitTransition.None,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Box(Modifier.fillMaxSize().background(Color.Red))
                    }
                }
            }
        }
        rule.runOnIdle { expanded = true }
        var intermediateFrames = 0
        repeat(32) { frame ->
            rule.mainClock.advanceTimeByFrame()
            val pixels = rule.onRoot().captureToImage().toPixelMap()
            val leftX = pixels.width / 4
            val rightX = pixels.width * 3 / 4
            var difference = 0f
            var visibleRows = 0
            for (y in 0 until pixels.height) {
                val actual = pixels[leftX, y]
                val reference = pixels[rightX, y]
                difference += abs(actual.green - reference.green)
                if (reference.green < .95f) visibleRows++
            }
            if (visibleRows in 1 until pixels.height) intermediateFrames++
            assertTrue(
                "Frame $frame must match original slide/fade; difference=${difference / pixels.height}",
                difference / pixels.height < .025f,
            )
        }
        assertTrue("Must compare moving frames, not just the endpoints", intermediateFrames >= 3)
    }
}
