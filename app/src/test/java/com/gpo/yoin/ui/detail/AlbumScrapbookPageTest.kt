package com.gpo.yoin.ui.detail

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import com.gpo.yoin.data.album.AlbumScrapbookAbout
import com.gpo.yoin.data.album.AlbumScrapbookAboutRef
import com.gpo.yoin.data.album.AlbumScrapbookAlbumNote
import com.gpo.yoin.data.album.AlbumScrapbookAsk
import com.gpo.yoin.data.album.AlbumScrapbookData
import com.gpo.yoin.data.album.AlbumScrapbookNote
import com.gpo.yoin.data.local.SongAboutEntry
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.ui.theme.YoinTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
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
    private val edits = mutableListOf<ScrapEdit>()
    private val askRef = AlbumScrapbookAboutRef("song 3", "artist", "album", SongAboutEntry.KIND_ASK, "who plays?")

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
    fun should_clearTheScore_when_ticketLongPressedAndScoreCleared() {
        show()

        rule.onNode(hasContentDescription("Track 1, Song 1, rated 8.5", substring = true))
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnLongClick)
        rule.waitForIdle()
        // the score is a pill beside the title; it opens the bar
        rule.onNodeWithContentDescription("Score 8.5").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Clear").performClick()

        assertEquals(listOf<ScrapEdit>(ScrapEdit.Score("subsonic:t1", 0f)), edits)
    }

    @Test
    fun should_saveTheEditedAnswer_when_questionEditedInTheTrackSheet() {
        show()

        rule.onNode(hasContentDescription("Asked about Song 3", substring = true)).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Edit").performClick()
        rule.waitForIdle()
        rule.onNode(hasSetTextAction() and hasText("First sentence.", substring = true))
            .performTextReplacement("Just the drummer.")
        rule.onNodeWithText("Done").performClick()

        assertEquals(listOf<ScrapEdit>(ScrapEdit.About(askRef, "The band", "Just the drummer.")), edits)
    }

    @Test
    fun should_removeTheQuestion_when_deletedInTheTrackSheet() {
        show()

        rule.onNode(hasContentDescription("Asked about Song 3", substring = true)).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Edit").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Delete").performClick()

        assertEquals(listOf<ScrapEdit>(ScrapEdit.About(askRef, null, "")), edits)
    }

    @Test
    fun should_writeTheQuestionBack_when_deleteUndone() {
        show()

        rule.onNode(hasContentDescription("Asked about Song 3", substring = true)).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Edit").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Delete").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Deleted “The band”").assertIsDisplayed()
        rule.onNodeWithText("Undo").performClick()
        rule.waitForIdle()

        assertEquals(
            listOf<ScrapEdit>(
                ScrapEdit.About(askRef, null, ""),
                ScrapEdit.About(askRef, "The band", "First sentence. Second sentence. Third sentence."),
            ),
            edits,
        )
    }

    @Test
    fun should_openTheEditor_when_anAnswerIsLongPressed() {
        show()

        openSong3()
        rule.onNode(hasText("Third sentence.", substring = true) and hasLongClick())
            .performSemanticsAction(SemanticsActions.OnLongClick)
        rule.waitForIdle()

        rule.onNode(hasSetTextAction() and hasText("First sentence.", substring = true)).assertIsDisplayed()
        rule.onNodeWithText("Regenerate").assertIsDisplayed()
    }

    @Test
    fun should_askGemini_when_regenerateTappedInTheEditor() {
        show()

        openSong3()
        rule.onNodeWithContentDescription("Edit").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Regenerate").performClick()

        assertEquals(listOf<ScrapEdit>(ScrapEdit.Regenerate(askRef)), edits)
    }

    @Test
    fun should_askGeminiAboutTheTrack_when_aNewTitleIsAsked() {
        show()

        openSong3()
        rule.onNode(emptyField()).performScrollTo().performTextReplacement("Who sampled it?")
        rule.onNodeWithText("Ask Gemini").performScrollTo().performClick()

        assertEquals(listOf<ScrapEdit>(ScrapEdit.Ask("subsonic:t3", "Who sampled it?")), edits)
    }

    @Test
    fun should_saveTheListenersOwnEntry_when_written() {
        show()

        openSong3()
        rule.onNode(emptyField()).performScrollTo().performTextReplacement("Where I heard it")
        rule.onNodeWithText("Write").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNode(emptyField()).performScrollTo().performTextReplacement("A friend's car, summer 2019.")
        rule.onNodeWithText("Save").performScrollTo().performClick()

        assertEquals(
            listOf<ScrapEdit>(ScrapEdit.Write("subsonic:t3", "Where I heard it", "A friend's car, summer 2019.")),
            edits,
        )
    }

    @Test
    fun should_showThreeLyricLinesEachSide_when_theNoteTabOpens() {
        show(feeds = lyricsFeeds())

        openNotesOfSong2()

        // the note was taken on line 6 (1:00): lines 3–9 around it, the rest left out
        rule.onNodeWithText("the drums come in").assertIsDisplayed()
        rule.onNodeWithText("Line 3").assertExists()
        rule.onNodeWithText("Line 9").assertExists()
        rule.onAllNodesWithText("Line 2").assertCountEquals(0)
        rule.onAllNodesWithText("Line 10").assertCountEquals(0)
    }

    @Test
    fun should_playFromTheNote_when_noteTappedInTheNoteTab() {
        show(feeds = lyricsFeeds())

        openNotesOfSong2()
        rule.onNodeWithText("the drums come in").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Play from 1:01").performClick()

        assertEquals(listOf("subsonic:t2" to 61_000L), moments)
    }

    @Test
    fun should_writeANoteAtTheLine_when_aLyricLineIsTapped() {
        show(feeds = lyricsFeeds())

        openNotesOfSong2()
        rule.onNodeWithText("Line 8").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("New note").performClick()
        rule.waitForIdle()
        rule.onNode(emptyField()).performTextReplacement("the hook")
        rule.onNodeWithText("Done").performClick()

        assertEquals(listOf<ScrapEdit>(ScrapEdit.NewNote("subsonic:t2", 80_000L, "the hook")), edits)
    }

    @Test
    fun should_deleteTheNoteWithUndo_when_itIsEmptied() {
        show(feeds = lyricsFeeds())

        openNotesOfSong2()
        rule.onNodeWithText("the drums come in").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Edit").performClick()
        rule.waitForIdle()
        rule.onNode(hasSetTextAction() and hasText("the drums come in")).performTextReplacement("")
        rule.onNodeWithText("Done").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Note deleted").assertIsDisplayed()
        rule.onNodeWithText("Undo").performClick()
        rule.waitForIdle()

        assertEquals(listOf(ScrapEdit.NoteText("n1", ""), ScrapEdit.RestoreNote("n1")), edits)
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

    private fun openSong3() {
        rule.onNode(hasContentDescription("Asked about Song 3", substring = true)).performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun openNotesOfSong2() {
        rule.onNode(hasContentDescription("Track 2, Song 2", substring = true))
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnLongClick)
        rule.waitForIdle()
        rule.onNodeWithText("Note · 1").performClick()
        rule.waitForIdle()
    }

    private fun hasLongClick() = SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)

    /** A text field with nothing typed in it yet. */
    private fun emptyField() = hasSetTextAction() and SemanticsMatcher("an empty field") { node ->
        node.config.getOrNull(SemanticsProperties.EditableText)?.text?.isEmpty() == true
    }

    /** Twelve timed lines, "Line 0" at 0:00 to "Line 11" at 1:50, ten seconds apart. */
    private fun lyricsFeeds() = ScrapSheetFeeds(
        lyrics = { flowOf(ScrapLyrics((0..11).map { ScrapLyricLine(it * 10_000L, "Line $it") })) },
        gemini = MutableStateFlow(ScrapGemini()),
        events = emptyFlow(),
    )

    private fun show(albumNote: AlbumScrapbookAlbumNote? = null, feeds: ScrapSheetFeeds = ScrapSheetFeeds.None) {
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
                            ref = askRef,
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
                    onEdit = { edits += it },
                    feeds = feeds,
                )
            }
        }
    }
}
