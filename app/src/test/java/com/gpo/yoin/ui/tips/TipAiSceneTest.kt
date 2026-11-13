package com.gpo.yoin.ui.tips

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The AI features tip: the real page in the demo phone, and the dot's choices stay in the phone. */
@RunWith(RobolectricTestRunner::class)
class TipAiSceneTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun should_pickDeepSeekThenBest_when_theDemoPlays() {
        rule.mainClock.autoAdvance = false
        rule.setContent { YoinTheme { AiServicesDemo(playing = true) } }
        rule.mainClock.advanceTimeByFrame()

        rule.onNode(hasText("Gemini") and hasClickAction()).assertIsSelected()
        rule.onNodeWithText("Good").assertIsSelected()

        // Past both taps, before the script starts over.
        rule.mainClock.advanceTimeBy(6_000)

        rule.onNode(hasText("DeepSeek") and hasClickAction()).assertIsSelected()
        rule.onNode(hasText("Gemini") and hasClickAction()).assertIsNotSelected()
        rule.onNodeWithText("Best").assertIsSelected()
    }

    @Test
    fun should_standStill_when_theTipIsNotTheSettledPage() {
        rule.setContent { YoinTheme { AiServicesDemo(playing = false) } }

        rule.onNode(hasText("Gemini") and hasClickAction()).assertIsSelected()
        rule.onNodeWithText("Good").assertIsSelected()
    }
}
