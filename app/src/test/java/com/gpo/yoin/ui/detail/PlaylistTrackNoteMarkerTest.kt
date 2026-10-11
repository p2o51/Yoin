package com.gpo.yoin.ui.detail

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The playlist rows wear the album row's note marker on the songs that carry a note. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h2000dp")
class PlaylistTrackNoteMarkerTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun should_markOnlyTheNotedRows_when_someSongsCarryNotes() {
        show(noted = setOf("subsonic:s2"))

        rule.onAllNodesWithContentDescription(HAS_NOTE).assertCountEquals(1)
        rule.onNode(hasText("Song 2") and hasContentDescription(HAS_NOTE)).assertExists()
        rule.onNode(hasText("Song 1") and hasContentDescription(HAS_NOTE)).assertDoesNotExist()
        rule.onNode(hasText("Song 3") and hasContentDescription(HAS_NOTE)).assertDoesNotExist()
    }

    @Test
    fun should_markEveryCopy_when_aNotedSongIsListedTwice() {
        show(noted = setOf("subsonic:s1"), songs = listOf("s1", "s2", "s1"))

        rule.onAllNodesWithContentDescription(HAS_NOTE).assertCountEquals(2)
    }

    @Test
    fun should_showNoMarker_when_noSongCarriesANote() {
        show(noted = emptySet())

        rule.onAllNodesWithContentDescription(HAS_NOTE).assertCountEquals(0)
    }

    @Test
    fun should_easeTheMarkerIn_when_aNoteLandsOnARowAlreadyUp() {
        var noted by mutableStateOf(emptySet<String>())
        val title = "A song title long enough to run under the marker and ellipsize"
        val content = content(listOf("s1")).let { list ->
            list.copy(songs = list.songs.map { song -> song.copy(title = title) })
        }
        rule.setContent {
            YoinTheme {
                PlaylistTrackList(
                    content = content,
                    listState = rememberLazyListState(),
                    rowMarks = PlaylistRowMarks(notedSongIds = noted, accent = { Color(0xFF6750A4) }),
                    onSongClick = {}
                )
            }
        }
        rule.waitForIdle()
        val bare = titleWidth(title)

        rule.mainClock.autoAdvance = false
        rule.runOnIdle { noted = setOf("subsonic:s1") }
        val frames = List(EASE_FRAMES) {
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            titleWidth(title)
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        val marked = titleWidth(title)

        // The marker and its gap take 18dp off the title, over several frames, not at once.
        assertEquals(with(rule.density) { 18.dp.toPx() }, bare - marked, 1f)
        val between = frames.filter { width -> width < bare - 0.5f && width > marked + 0.5f }
        assertTrue("no frame between bare and marked: $frames ($bare → $marked)", between.size >= 2)
        rule.onNodeWithContentDescription(HAS_NOTE, useUnmergedTree = true).assertExists()
    }

    private fun titleWidth(title: String): Float =
        rule.onNodeWithText(title, useUnmergedTree = true).fetchSemanticsNode().size.width.toFloat()

    private fun content(songs: List<String>): PlaylistDetailUiState.Content = PlaylistDetailUiState.Content(
        playlistId = "subsonic:pl",
        playlistName = "Playlist",
        owner = "owner",
        comment = null,
        isPublic = null,
        songCount = songs.size,
        totalDuration = 600,
        coverArtUrl = null,
        songs = songs.mapIndexed { index, rawId ->
            PlaylistSong(
                id = "subsonic:$rawId",
                title = "Song ${rawId.removePrefix("s")}",
                artist = "Artist",
                album = "Album",
                duration = 200,
                coverArtUrl = null,
                position = index
            )
        }
    )

    private fun show(noted: Set<String>, songs: List<String> = listOf("s1", "s2", "s3")) {
        val content = content(songs)
        rule.setContent {
            YoinTheme {
                PlaylistTrackList(
                    content = content,
                    listState = rememberLazyListState(),
                    rowMarks = PlaylistRowMarks(notedSongIds = noted, accent = { Color(0xFF6750A4) }),
                    onSongClick = {}
                )
            }
        }
        rule.waitForIdle()
    }

    private companion object {
        // R.string.detail_album_cd_has_note, reused by the playlist rows.
        const val HAS_NOTE = "Has note"

        // A few frames into the spatial spring: well short of its end.
        const val EASE_FRAMES = 6
    }
}
