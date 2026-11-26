package com.gpo.yoin.ui.nowplaying

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.player.PlayMode
import com.gpo.yoin.ui.component.NoteDraftState
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 2026-11-25 Pixel Tablet QA: a song note emptied in Now Playing's edit card
 * and saved (= deleted, with undo) was gone from the Note page but still in
 * the database afterwards — Home's Jump Back In showed it again. A pending
 * deletion must reach the store however Now Playing is left: the undo window
 * closing, the screen closing, or the app stopping before either.
 */
@RunWith(RobolectricTestRunner::class)
class NowPlayingNoteDeleteCommitTest {

    @get:Rule
    val rule = createComposeRule()

    private val note = SongNote(
        id = "n1",
        trackId = "nq-t1",
        content = "placeholder line one",
        createdAt = 1L,
        updatedAt = 1L,
        title = "Harbour Lights",
        artist = "Night QA Band",
        positionMs = 12_000L,
    )

    private val playing = NowPlayingUiState.Playing(
        songTitle = "Harbour Lights",
        artist = "Night QA Band",
        albumName = "Night QA",
        coverArtUrl = null,
        isPlaying = false,
        durationMs = 240_000L,
        songId = "subsonic:nq-t1",
        rating = null,
        isStarred = false,
        lyrics = emptyList(),
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

    private class Host : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private val deleted = mutableListOf<String>()
    private var page by mutableStateOf(NowPlayingDetailPage.Note)
    private var shown by mutableStateOf(true)
    private val host = Host()

    private fun setScreen(presentation: NowPlayingPresentation) {
        var notes by mutableStateOf(listOf(note))
        val draft = NoteDraftState()
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides host) {
                YoinTheme {
                    if (shown) {
                        NowPlayingScreen(
                            uiState = playing,
                            positionMs = { 0L },
                            bufferedMs = { 0L },
                            hasAudioSpectrum = false,
                            onTogglePlayPause = {},
                            onSkipNext = {},
                            onSkipPrevious = {},
                            onSeek = {},
                            onRatingChange = {},
                            onToggleFavorite = {},
                            onAddCurrentToPlaylist = {},
                            onSkipToQueueItem = {},
                            stageMode = NowPlayingStageMode.Expanded,
                            detailPage = page,
                            onDetailPageChange = { page = it },
                            notesState = notes,
                            noteDraft = draft,
                            onDeleteNote = { id ->
                                deleted += id
                                notes = notes.filterNot { it.id == id }
                            },
                            presentation = presentation,
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    /** Long-press the note into its menu and Delete it: deleted with undo, the window still open. */
    private fun saveTheNoteEmptied() {
        rule.onNode(
            hasText(note.content, substring = true) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick),
        ).performTouchInput { longClick() }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("Delete").performClick()
        rule.mainClock.advanceTimeBy(500L)
        rule.onNodeWithText("Undo").assertExists()
        assertTrue("still in its undo window", deleted.isEmpty())
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun should_deleteTheNote_when_nowPlayingClosesAfterAboutWithinTheUndoWindow() {
        setScreen(NowPlayingPresentation.Phone)
        saveTheNoteEmptied()

        page = NowPlayingDetailPage.About
        rule.mainClock.advanceTimeBy(1_000L)
        shown = false
        rule.mainClock.advanceTimeBy(100L)

        assertEquals(listOf("n1"), deleted)
        rule.mainClock.advanceTimeBy(NoteUndoWindowMs + 1_000L)
        assertEquals("deleted once", listOf("n1"), deleted)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun should_deleteTheNote_when_theTabletsTwoColumnsCloseWithinTheUndoWindow() {
        setScreen(NowPlayingPresentation.DualPane)
        saveTheNoteEmptied()

        page = NowPlayingDetailPage.About
        rule.mainClock.advanceTimeBy(1_000L)
        shown = false
        rule.mainClock.advanceTimeBy(100L)

        assertEquals(listOf("n1"), deleted)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun should_deleteTheNote_when_theAppStopsWithinTheUndoWindow() {
        setScreen(NowPlayingPresentation.DualPane)
        saveTheNoteEmptied()

        // Home button / another app: the process may be killed before the window closes.
        rule.runOnIdle { host.registry.currentState = Lifecycle.State.CREATED }
        rule.mainClock.advanceTimeBy(100L)

        assertEquals(listOf("n1"), deleted)
        rule.mainClock.advanceTimeBy(NoteUndoWindowMs + 1_000L)
        assertEquals("deleted once", listOf("n1"), deleted)
    }
}
