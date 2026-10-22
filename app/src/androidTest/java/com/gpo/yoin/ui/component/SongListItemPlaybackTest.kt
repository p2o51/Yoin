package com.gpo.yoin.ui.component

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.R
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SongListItemPlaybackTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun should_keepTheCurrentIndicatorOnPauseAndMoveIt_when_theTrackChanges() {
        var currentTrack by mutableIntStateOf(0)
        var playing by mutableStateOf(true)
        val nowPlaying = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.detail_album_cd_now_playing)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            YoinTheme {
                Column {
                    listOf("First song", "Second song").forEachIndexed { index, title ->
                        SongListItem(
                            title = title,
                            artist = "Test artist",
                            album = "Test album",
                            durationSeconds = 180,
                            coverArtUrl = null,
                            onClick = {},
                            isCurrentTrack = index == currentTrack,
                            isPlaying = playing,
                            extractBackdropColors = false,
                            modifier = Modifier.testTag("song-$index"),
                        )
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(1_500)
        assertIndicatorInRow(nowPlaying, "song-0")

        rule.runOnIdle { playing = false }
        rule.mainClock.advanceTimeBy(1_500)
        assertIndicatorInRow(nowPlaying, "song-0")

        rule.runOnIdle { currentTrack = 1 }
        rule.mainClock.advanceTimeBy(1_500)
        assertIndicatorInRow(nowPlaying, "song-1")

        rule.runOnIdle { playing = true }
        rule.mainClock.advanceTimeBy(1_500)
        assertIndicatorInRow(nowPlaying, "song-1")
    }

    @Test
    fun should_explainAnUnavailableTrack_when_itsBadgeIsTapped() {
        var playRequests = 0
        val reason = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.cmp_unavailable_apple_import)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            YoinTheme {
                SongListItem(
                    title = "Imported song",
                    artist = "Test artist",
                    album = "Test album",
                    durationSeconds = 180,
                    coverArtUrl = null,
                    onClick = { playRequests++ },
                    isUnavailable = true,
                    extractBackdropColors = false,
                )
            }
        }

        rule.onNodeWithText("?").assertIsDisplayed().performClick()
        rule.mainClock.advanceTimeBy(1_500)
        rule.onNodeWithText(reason).assertIsDisplayed()
        rule.runOnIdle { assertEquals(0, playRequests) }
    }

    private fun assertIndicatorInRow(description: String, rowTag: String) {
        rule.onAllNodes(hasContentDescription(description), useUnmergedTree = true)
            .assertCountEquals(1)
        rule.onNode(
            hasContentDescription(description) and hasAnyAncestor(hasTestTag(rowTag)),
            useUnmergedTree = true,
        ).assertIsDisplayed()
    }
}
