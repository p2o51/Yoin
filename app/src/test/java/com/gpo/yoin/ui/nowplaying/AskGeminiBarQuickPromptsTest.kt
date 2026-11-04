package com.gpo.yoin.ui.nowplaying

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Ask bar, focused and empty, offers the quick questions not asked about the song yet. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp")
class AskGeminiBarQuickPromptsTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun should_fillTheField_when_aQuickQuestionIsTapped_andLeaveOutOnesAsked() {
        val sent = mutableListOf<String>()
        rule.setContent {
            AskGeminiBar(
                askState = AskBarState.Focused,
                onSubmit = { sent += it },
                onFocus = {},
                onCollapseRequest = {},
                onDismissError = {},
                asked = listOf("who sampled this song?"),
            )
        }

        rule.onNodeWithText("Who sampled this song?").assertDoesNotExist()
        rule.onNodeWithText("What does this song sample?").performClick()
        rule.waitForIdle()

        rule.onNode(hasSetTextAction())
            .assert(hasText("What does this song sample?"))
            .performImeAction()
        assertEquals(listOf("What does this song sample?"), sent)
    }
}
