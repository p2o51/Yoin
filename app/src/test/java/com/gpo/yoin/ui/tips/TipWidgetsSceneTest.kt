package com.gpo.yoin.ui.tips

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The home-screen widgets tip's demo: a home screen that answers a long-press with the widget list, Yoin's two
 * widgets in it under the names the system's own list reads from the app, and a pick sends the list away.
 * Native graphics, so the widgets' own art code really draws.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp")
class TipWidgetsSceneTest {

    @get:Rule
    val rule = createComposeRule()

    private fun show() {
        rule.setContent { YoinTheme { HomeScreenWidgetsDemo(playing = false) } }
        rule.waitForIdle()
    }

    /** Holds the home screen, on its one icon's label: the long-press is the screen's, wherever it lands. */
    private fun holdHomeScreen() {
        rule.onNodeWithText("Yoin").performTouchInput { longClick() }
        rule.waitForIdle()
    }

    @Test
    fun should_showTheHomeScreenWithoutTheList_when_atRest() {
        show()

        rule.onNodeWithText("Yoin").assertExists()
        rule.onNodeWithText("Recent listening").assertDoesNotExist()
        rule.onNodeWithText("Your album memories").assertDoesNotExist()
    }

    @Test
    fun should_listPlayAndMemory_when_theHomeScreenIsHeld() {
        show()

        holdHomeScreen()

        rule.onNodeWithText("Play").assertExists()
        rule.onNodeWithText("Recent listening").assertExists()
        rule.onNodeWithText("Memory").assertExists()
        rule.onNodeWithText("Your album memories").assertExists()
    }

    @Test
    fun should_sendTheListAway_when_aWidgetIsPicked() {
        show()
        holdHomeScreen()

        rule.onNodeWithText("Memory").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Recent listening").assertDoesNotExist()
        rule.onNodeWithText("Your album memories").assertDoesNotExist()
    }

    @Config(qualifiers = "zh-rCN-w412dp-h915dp")
    @Test
    fun should_nameTheWidgetsAsTheChineseListDoes_when_theAppUsesSimplifiedChinese() {
        show()

        holdHomeScreen()

        rule.onNodeWithText("播放").assertExists()
        rule.onNodeWithText("Memories").assertExists()
    }
}
