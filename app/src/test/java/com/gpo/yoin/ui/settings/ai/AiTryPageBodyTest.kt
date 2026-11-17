package com.gpo.yoin.ui.settings.ai

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.gpo.yoin.data.remote.ai.AiSettings
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 「拿一首歌试试」 over plain state: the recent songs, a tap running one, the answer. */
@RunWith(RobolectricTestRunner::class)
class AiTryPageBodyTest {

    @get:Rule
    val rule = createComposeRule()

    private val lemon = TestSong("Lemon", "Kenshi Yonezu", "STRAY SHEEP")
    private val plastic = TestSong("Plastic Love", "Mariya Takeuchi", "Variety")
    private val songs = listOf(RecentTestSong("1", lemon, null), RecentTestSong("2", plastic, null))
    private val run: MutableState<TestRun> = mutableStateOf(TestRun.Idle)
    private val picked = mutableListOf<TestSong>()

    private fun showPage(list: List<RecentTestSong>?) {
        rule.setContent {
            YoinTheme {
                AiTryPageBody(songs = list, run = run.value, onPick = { picked += it })
            }
        }
    }

    @Test
    fun should_runTheTappedSong_when_aRecentSongIsTapped() {
        showPage(songs)

        rule.onNodeWithText("Kenshi Yonezu").assertIsDisplayed()
        rule.onNode(hasText("Plastic Love") and hasClickAction()).performClick()

        rule.runOnIdle { assertEquals(listOf(plastic), picked) }
    }

    @Test
    fun should_startNoSecondRun_when_aSongIsTappedWhileOneRuns() {
        run.value = TestRun.Running(lemon)
        showPage(songs)

        rule.onNode(hasText("Plastic Love") and hasClickAction()).performClick()

        rule.runOnIdle { assertEquals(emptyList<TestSong>(), picked) }
        rule.onNodeWithText("Searching…").assertIsDisplayed()
    }

    @Test
    fun should_showTheAnswer_when_theRunIsDone() {
        run.value = TestRun.Done(
            song = lemon,
            millis = 3_400,
            fields = emptyMap(),
            search = AiSettings.SearchKind.External,
            queries = listOf("Lemon Kenshi Yonezu songwriting credits producer"),
            pages = 7,
            model = "deepseek-flash",
        )
        showPage(songs)

        rule.onNodeWithText("deepseek-flash").assertIsDisplayed()
        rule.onNodeWithText("3.4 s").assertIsDisplayed()
    }

    @Test
    fun should_sayToPlayASongFirst_when_thereIsNoPlayHistory() {
        showPage(emptyList())

        rule.onNodeWithText("Play a song first").assertIsDisplayed()
    }

    @Config(qualifiers = "zh-rCN")
    @Test
    fun should_sayItInChinese_when_theAppUsesSimplifiedChinese() {
        showPage(emptyList())

        rule.onNodeWithText("先播一首歌").assertIsDisplayed()
    }
}
