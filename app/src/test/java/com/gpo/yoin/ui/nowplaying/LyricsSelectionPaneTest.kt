package com.gpo.yoin.ui.nowplaying

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Select mode on the expanded lyrics list: taps drive one contiguous, capped run (L6). */
@RunWith(RobolectricTestRunner::class)
// Tall enough that every line the tests tap is laid out on screen.
@Config(qualifiers = "w400dp-h2400dp")
class LyricsSelectionPaneTest {

    @get:Rule
    val rule = createComposeRule()

    private val lyrics = (0 until 40).map { LyricLine(startMs = 10_000L + it * 3_000L, text = "Line $it") }

    private fun showPane(selection: LyricsSelectionState, toggled: MutableList<Int>) {
        rule.setContent {
            YoinTheme {
                Box(Modifier.fillMaxSize()) {
                    LyricsFullscreenPane(
                        lyrics = lyrics,
                        positionMs = { 0L },
                        loading = false,
                        showTranslation = false,
                        autoScrollEnabled = true,
                        recenterRequestKey = 0,
                        onUserScroll = {},
                        onSeekToMs = {},
                        selecting = selection.active,
                        selectedLines = selection.selected,
                        onToggleLine = { index ->
                            toggled += index
                            selection.toggle(index)
                        },
                    )
                }
            }
        }
    }

    @Test
    fun should_buildOneRunAndStartOverFarAway_when_linesAreTapped() {
        val selection = LyricsSelectionState().apply { enter() }
        val toggled = mutableListOf<Int>()
        showPane(selection, toggled)

        rule.onNodeWithText("Line 2").performClick()
        rule.onNodeWithText("Line 3").performClick()
        rule.waitForIdle()
        assertEquals(setOf(2, 3), selection.selected)
        rule.onNodeWithText("2 lines selected").assertExists()

        rule.onNodeWithText("Line 9").performClick()
        rule.waitForIdle()
        assertEquals(setOf(9), selection.selected)
    }

    @Test
    fun should_refuseAndNameTheCap_when_aFullRunIsAskedToGrow() {
        val selection = LyricsSelectionState().apply { enter() }
        repeat(MaxSelectedLyricLines) { selection.toggle(it) }
        val toggled = mutableListOf<Int>()
        showPane(selection, toggled)
        rule.onNodeWithText("$MaxSelectedLyricLines lines selected · max").assertExists()

        rule.onNodeWithText("Line $MaxSelectedLyricLines").performClick()
        rule.waitForIdle()

        assertTrue("a refused tap never reaches the state", toggled.isEmpty())
        assertEquals((0 until MaxSelectedLyricLines).toSet(), selection.selected)
        rule.onNodeWithText("Up to $MaxSelectedLyricLines lines at a time").assertExists()
    }
}
