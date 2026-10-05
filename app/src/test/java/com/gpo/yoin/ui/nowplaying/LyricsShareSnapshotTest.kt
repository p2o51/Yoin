package com.gpo.yoin.ui.nowplaying

import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.player.PlayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 2026-10-05 device QA (问题 2): with the share sheet open, an automatic track
 * change closed it and dropped the pick — the pick is per song. The sheet now
 * renders a frozen [LyricsShareSnapshot] that outlives the song under it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp")
class LyricsShareSnapshotTest {

    @get:Rule
    val rule = createComposeRule()

    private fun lyrics(prefix: String) = (0 until 6).map { LyricLine(startMs = 1_000L * it, text = "$prefix line $it") }

    private fun playing(songId: String, title: String, lines: List<LyricLine>) = NowPlayingUiState.Playing(
        songTitle = title,
        artist = "Night QA Band",
        albumName = "Night QA Tapes",
        coverArtUrl = null,
        isPlaying = true,
        durationMs = 60_000L,
        songId = songId,
        rating = 0f,
        isStarred = false,
        lyrics = lines,
        showLyricsTranslation = false,
        lyricsActionInFlight = null,
        lyricsLoading = false,
        queue = emptyList(),
        currentQueueIndex = 0,
        playMode = PlayMode.RepeatAll,
        albumId = null,
        artistId = null,
        activityContext = ActivityContext.None,
    )

    private val harbour = playing("subsonic:nq-t1", "Harbour Lights", lyrics("Harbour"))
    private val platform = playing("subsonic:nq-t2", "Second Platform", lyrics("Platform"))

    @Test
    fun should_freezeThePickedLinesAndTheirSong_when_shareOpens() {
        val snapshot = lyricsShareSnapshot(harbour, selected = setOf(3, 2))!!

        assertEquals(listOf("Harbour line 2", "Harbour line 3"), snapshot.lines.map { it.text })
        assertEquals("subsonic:nq-t1", snapshot.songId)
        assertEquals("Harbour Lights", snapshot.songTitle)
        assertEquals("Night QA Band", snapshot.artist)
    }

    @Test
    fun should_haveNothingToShare_when_nothingIsPicked() {
        assertNull(lyricsShareSnapshot(harbour, selected = emptySet()))
        // Stale indices (the lyrics were swapped) drop out too.
        assertNull(lyricsShareSnapshot(harbour, selected = setOf(40)))
    }

    private lateinit var selection: LyricsSelectionState
    private var onShared: () -> Unit = {}
    private var onDismiss: () -> Unit = {}

    private fun setHost(state: () -> NowPlayingUiState.Playing) {
        rule.setContent {
            val current = state()
            val pick = rememberLyricsSelectionState(current.songId, current.lyrics)
            SideEffect { selection = pick }
            LyricsSelectionHost(
                state = current,
                selection = pick,
                lyricsPageOnScreen = true,
                onMessage = {},
                sheet = { snapshot, shared, dismiss ->
                    onShared = shared
                    onDismiss = dismiss
                    Text(snapshot.lines.joinToString(" / ") { it.text } + " — " + snapshot.songTitle)
                },
            )
        }
    }

    private fun pickAndShare() {
        rule.runOnIdle {
            selection.enter()
            selection.toggle(2)
            selection.toggle(3)
            selection.openShare()
        }
        rule.waitForIdle()
    }

    private val sheetText = "Harbour line 2 / Harbour line 3 — Harbour Lights"

    @Test
    fun should_keepTheSheetAndItsLines_when_theSongChangesUnderIt() {
        var state by mutableStateOf(harbour)
        setHost { state }
        pickAndShare()
        rule.onNodeWithText(sheetText).assertExists()

        // Auto-advance while the sheet is open.
        rule.runOnIdle { state = platform }
        rule.waitForIdle()

        rule.onNodeWithText(sheetText).assertExists()
        rule.runOnIdle { assertFalse("the new song's pick starts fresh", selection.active) }
    }

    @Test
    fun should_closeTheSheetAndKeepThePick_when_dismissed() {
        setHost { harbour }
        pickAndShare()

        rule.runOnIdle { onDismiss() }
        rule.waitForIdle()

        rule.onNodeWithText(sheetText).assertDoesNotExist()
        rule.runOnIdle {
            assertTrue(selection.active)
            assertFalse(selection.sharing)
            assertEquals(setOf(2, 3), selection.selected)
        }
    }

    @Test
    fun should_leaveSelectMode_when_shared() {
        setHost { harbour }
        pickAndShare()

        rule.runOnIdle { onShared() }
        rule.waitForIdle()

        rule.onNodeWithText(sheetText).assertDoesNotExist()
        rule.runOnIdle { assertFalse(selection.active) }
    }

    @Test
    fun should_closeTheSheet_when_dismissedAfterTheSongChanged() {
        var state by mutableStateOf(harbour)
        setHost { state }
        pickAndShare()
        rule.runOnIdle { state = platform }
        rule.waitForIdle()

        rule.runOnIdle { onDismiss() }
        rule.waitForIdle()

        rule.onNodeWithText(sheetText).assertDoesNotExist()
    }
}
