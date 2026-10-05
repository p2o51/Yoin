package com.gpo.yoin.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [springHeight] eases a section's height when its composition's key
 * changes, and must always land on the new content's height. Device QA
 * 2026-10-05: switching to a profile whose Jump Back In seats a template
 * (another key) left the section at the previous profile's height, so
 * Recently Added drew on top of the grid — the seeding measure never read
 * the spring, so nothing re-laid the section while it ran.
 */
@RunWith(RobolectricTestRunner::class)
class HomeSpringHeightTest {

    @get:Rule
    val rule = createComposeRule()

    private val spec = YoinMotion.defaultSpatialSpec<Float>(
        role = YoinMotionRole.Expressive,
        expressiveScheme = MotionScheme.expressive(),
    )

    @Test
    fun should_landOnTheNewContentHeight_when_keyAndContentChangeTogether() {
        var grown by mutableStateOf(false)
        rule.setContent {
            Box(
                Modifier
                    .testTag(HOST)
                    .springHeight(spec = spec, key = grown, enabled = true),
            ) {
                Box(Modifier.size(width = 50.dp, height = if (grown) 300.dp else 100.dp))
            }
        }
        rule.onNodeWithTag(HOST).assertHeightIsEqualTo(100.dp)

        rule.runOnIdle { grown = true }
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        rule.waitForIdle()

        rule.onNodeWithTag(HOST).assertHeightIsEqualTo(300.dp)
    }

    @Test
    fun should_landOnTheNewContentHeight_when_keyAndContentShrinkTogether() {
        var grown by mutableStateOf(true)
        rule.setContent {
            Box(
                Modifier
                    .testTag(HOST)
                    .springHeight(spec = spec, key = grown, enabled = true),
            ) {
                Box(Modifier.size(width = 50.dp, height = if (grown) 300.dp else 100.dp))
            }
        }
        rule.onNodeWithTag(HOST).assertHeightIsEqualTo(300.dp)

        rule.runOnIdle { grown = false }
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        rule.waitForIdle()

        rule.onNodeWithTag(HOST).assertHeightIsEqualTo(100.dp)
    }

    @Test
    fun should_easeThroughTheOldHeight_when_keyChanges() {
        var grown by mutableStateOf(false)
        rule.setContent {
            Box(
                Modifier
                    .testTag(HOST)
                    .springHeight(spec = spec, key = grown, enabled = true),
            ) {
                Box(Modifier.size(width = 50.dp, height = if (grown) 300.dp else 100.dp))
            }
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false

        rule.runOnIdle { grown = true }
        // Frame by frame, reading the height each frame as the device would draw it.
        val heights = List(EASE_FRAMES) {
            rule.mainClock.advanceTimeBy(FRAME_MS)
            rule.onNodeWithTag(HOST).getBoundsInRoot().let { it.bottom - it.top }
        }

        assertTrue(
            "the height should pass between 100dp and 300dp, was $heights",
            heights.any { it > 100.dp && it < 299.dp },
        )
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        rule.onNodeWithTag(HOST).assertHeightIsEqualTo(300.dp)
    }

    private companion object {
        const val HOST = "spring-height-host"
        const val SETTLE_MS = 5_000L
        const val FRAME_MS = 32L
        const val EASE_FRAMES = 8
    }
}
