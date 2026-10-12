package com.gpo.yoin.ui.nowplaying

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Now Playing's rating bar writes once per press, with the value the finger
 * lifts at — a drag across the bar used to write every value it passed
 * (one provider call per pointer move). The fill follows the finger in the
 * meantime and keeps the committed value until the stored rating catches up.
 */
@RunWith(RobolectricTestRunner::class)
class NowPlayingRatingBarTest {

    @get:Rule
    val rule = createComposeRule()

    private val commits = mutableListOf<Float>()
    private var stored by mutableFloatStateOf(2f)
    private var songId by mutableStateOf("tr-1116")

    private fun setBar() {
        rule.setContent {
            NowPlayingRatingBar(
                songId = songId,
                rating = stored,
                onRatingCommit = { commits += it },
                modifier = Modifier.height(400.dp).testTag(BAR)
            )
        }
    }

    @Test
    fun should_commitOnce_when_aDragLifts() {
        setBar()

        rule.onNodeWithTag(BAR).performTouchInput {
            down(at(0.02f))
            // Up the bar in small steps: many moves, many 0.1 steps crossed.
            for (step in 1..40) moveTo(at(0.77f * step / 40f))
        }
        rule.waitForIdle()
        assertEquals(emptyList<Float>(), commits)
        // The fill follows the finger before anything is written.
        rule.onNodeWithTag(BAR).assert(shows(7.7f))

        rule.onNodeWithTag(BAR).performTouchInput { up() }
        rule.waitForIdle()

        assertEquals(listOf(7.7f), commits)
    }

    @Test
    fun should_keepTheCommittedValue_when_theStoredRatingHasNotCaughtUpYet() {
        setBar()

        rule.onNodeWithTag(BAR).performTouchInput { click(at(0.5f)) }
        rule.waitForIdle()

        assertEquals(listOf(5f), commits)
        // Still 2.0 in Room: the bar doesn't flick back to it.
        rule.onNodeWithTag(BAR).assert(shows(5f))

        // The write landed, then a rating from elsewhere: the bar shows what is stored.
        stored = 5f
        rule.waitForIdle()
        rule.onNodeWithTag(BAR).assert(shows(5f))
        stored = 3f
        rule.waitForIdle()
        rule.onNodeWithTag(BAR).assert(shows(3f))
    }

    @Test
    fun should_showTheNextSongsRating_when_theSongChangesBeforeTheWriteLands() {
        setBar()
        rule.onNodeWithTag(BAR).performTouchInput { click(at(0.5f)) }
        rule.waitForIdle()

        songId = "tr-1117"
        stored = 9f
        rule.waitForIdle()

        rule.onNodeWithTag(BAR).assert(shows(9f))
        assertEquals(listOf(5f), commits)
    }

    /** The point on the vertical bar that reads [fraction] of a full rating. */
    private fun TouchInjectionScope.at(fraction: Float) = Offset(centerX, height * (1f - fraction))

    private fun shows(rating: Float) = SemanticsMatcher.expectValue(
        SemanticsProperties.ProgressBarRangeInfo,
        ProgressBarRangeInfo(current = rating, range = 0f..10f, steps = 99)
    )

    private companion object {
        const val BAR = "ratingBar"
    }
}
