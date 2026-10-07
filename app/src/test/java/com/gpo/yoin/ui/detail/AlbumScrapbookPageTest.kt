package com.gpo.yoin.ui.detail

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.gpo.yoin.data.album.AlbumScrapbookAbout
import com.gpo.yoin.data.album.AlbumScrapbookAlbumNote
import com.gpo.yoin.data.album.AlbumScrapbookAsk
import com.gpo.yoin.data.album.AlbumScrapbookData
import com.gpo.yoin.data.album.AlbumScrapbookNote
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h2000dp")
class AlbumScrapbookPageTest {

    @get:Rule
    val rule = createComposeRule()

    private val played = mutableListOf<String>()
    private val moments = mutableListOf<Pair<String, Long?>>()
    private var reviewEdits = 0

    @Test
    fun should_playTheSong_when_ticketTapped() {
        show()

        rule.onNode(hasContentDescription("Track 1, Song 1, rated 8.5", substring = true)).performScrollTo().performClick()

        assertEquals(listOf("subsonic:t1"), played)
    }

    @Test
    fun should_playFromTheNotesMoment_when_noteLineTapped() {
        show()

        rule.onNodeWithContentDescription("Note at 1:01: the drums come in").performScrollTo().performClick()

        assertEquals(listOf("subsonic:t2" to 61_000L), moments)
    }

    @Test
    fun should_openTheFullAnswer_when_questionCardTapped() {
        show()

        rule.onNode(hasContentDescription("Asked about Song 3", substring = true)).performScrollTo().performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Second sentence. Third sentence.", substring = true).assertIsDisplayed()
    }

    @Test
    fun should_openTheReviewSheet_when_blankReviewTapped() {
        show()

        rule.onNodeWithContentDescription("Write a comment").performClick()

        assertEquals(1, reviewEdits)
    }

    @Test
    fun should_showOneAlbumNoteWithoutALabel_when_albumHasOne() {
        show(albumNote = AlbumScrapbookAlbumNote("an", "Heard it first on the way to the airport.", 1L))

        rule.onNodeWithContentDescription("Album note: Heard it first on the way to the airport.").assertIsDisplayed()
        // told apart by its paper alone: no "Album note" / "Your …" label on the page
        rule.onAllNodesWithText("Album note", substring = true).assertCountEquals(0)
        rule.onAllNodesWithText("Your", substring = true).assertCountEquals(0)
    }

    @Test
    fun should_showNoNotYetLabelOrMoulds_when_someTracksHaveNothingYet() {
        show()

        rule.onAllNodesWithText("Not yet").assertCountEquals(0)
        rule.onAllNodes(hasContentDescription("Track 4, Song 4")).assertCountEquals(0)
    }

    private fun show(albumNote: AlbumScrapbookAlbumNote? = null) {
        val content = AlbumDetailUiState.Content(
            albumId = "subsonic:al-1",
            albumName = "Album",
            artistName = "Artist",
            artistId = null,
            coverArtId = null,
            coverArtUrl = null,
            year = 2020,
            songCount = 4,
            totalDuration = null,
            songs = (1..4).map { n -> AlbumSong("subsonic:t$n", "Song $n", "Artist", n, 200, isStarred = false) },
        )
        val data = AlbumScrapbookData(
            ratings = mapOf(MediaId(MediaId.PROVIDER_SUBSONIC, "t1") to 8.5f),
            notes = mapOf(
                MediaId(MediaId.PROVIDER_SUBSONIC, "t2") to listOf(AlbumScrapbookNote("n1", "the drums come in", 61_000L, 1L)),
            ),
            about = mapOf(
                MediaId(MediaId.PROVIDER_SUBSONIC, "t3") to AlbumScrapbookAbout(
                    asks = listOf(
                        AlbumScrapbookAsk(
                            question = "Who plays?",
                            title = "The band",
                            answer = "First sentence. Second sentence. Third sentence.",
                        ),
                    ),
                ),
            ),
            albumNote = albumNote,
        )
        rule.setContent {
            YoinTheme(darkTheme = false) {
                AlbumScrapbookPage(
                    state = AlbumScrapbookUiState.Ready(buildAlbumScrapbook(content, data, nowMillis = 0L)),
                    content = content,
                    colors = rememberScrapbookColors(MaterialTheme.colorScheme),
                    currentTrackId = null,
                    pageOffset = { 0f },
                    onSongClick = { played += it },
                    onNoteMomentClick = { songId, at -> moments += songId to at },
                    onEditReview = { reviewEdits++ },
                )
            }
        }
    }
}
