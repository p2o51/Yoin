package com.gpo.yoin.ui.component

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RatingSliderTest {

    @get:Rule
    val rule = createComposeRule()

    private fun stateIs(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text)

    @Test
    fun should_showNoNumber_when_trackIsNotRated() {
        rule.setContent {
            YoinTheme { RatingSlider(rating = null, onRatingChange = {}, modifier = Modifier.height(200.dp)) }
        }

        rule.onNode(stateIs("Not rated")).assertExists()
        rule.onNodeWithText("0.0").assertDoesNotExist()
    }

    @Test
    fun should_showZeroPointZero_when_trackIsRatedZero() {
        rule.setContent {
            YoinTheme { RatingSlider(rating = 0f, onRatingChange = {}, modifier = Modifier.height(200.dp)) }
        }

        rule.onNodeWithText("0.0").assertIsDisplayed()
        rule.onNode(stateIs("Rated 0.0 out of 10")).assertExists()
    }

    @Test
    fun should_setAScore_when_unratedBarIsTapped() {
        var rating by mutableStateOf<Float?>(null)
        rule.setContent {
            YoinTheme {
                RatingSlider(rating = rating, onRatingChange = { rating = it }, modifier = Modifier.height(200.dp))
            }
        }

        rule.onNode(stateIs("Not rated")).performClick()
        rule.waitForIdle()

        // A tap lands mid-bar: a score, with its number on the bar.
        val score = rating
        assertNotNull(score)
        score!!
        assertEquals(5f, score, 0.11f)
        rule.onNodeWithText(ratingLabel(score)!!).assertIsDisplayed()
    }
}
