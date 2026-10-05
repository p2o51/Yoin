package com.gpo.yoin.ui.nowplaying

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.ui.component.NoteDraft
import com.gpo.yoin.ui.component.NoteDraftState
import com.gpo.yoin.ui.component.NoteSortMode
import com.gpo.yoin.ui.component.NoteTarget
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The expanded Note page: lyric-style lines, a long-press menu, undo in place. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp")
class NoteFullscreenPaneTest {

    @get:Rule
    val rule = createComposeRule()

    private val song = NoteTarget(songId = "subsonic:nq-t1", title = "Harbour Lights", artist = "Night QA Band")

    private fun note(id: String, at: Long?, text: String) = SongNote(
        id = id,
        trackId = "nq-t1",
        content = text,
        createdAt = 1L,
        updatedAt = 1L,
        title = song.title,
        artist = song.artist,
        positionMs = at,
    )

    private val first = note("n1", 12_000L, "placeholder line one")
    private val second = note("n2", 48_000L, "placeholder line two")

    @Test
    fun should_seekToMoment_when_lineTapped() {
        val seeks = mutableListOf<Long>()
        setPane(notes = listOf(first, second), onSeek = { seeks += it })

        rule.onNodeWithText("placeholder line two").performClick()

        assertEquals(listOf(48_000L), seeks)
    }

    @Test
    fun should_deleteFromLongPressMenu_when_deleteChosen() {
        val deleted = mutableListOf<String>()
        setPane(notes = listOf(first, second), onDelete = { deleted += it })

        rule.onNodeWithText("placeholder line one").performTouchInput { longClick() }
        rule.onNodeWithText("删除").performClick()

        assertEquals(listOf("n1"), deleted)
    }

    @Test
    fun should_realignToMomentOfLongPress_when_realignChosen() {
        val realigned = mutableListOf<Pair<String, Long>>()
        setPane(
            notes = listOf(first, second),
            playhead = 95_000L,
            onRealign = { note, at -> realigned += note.id to at },
        )

        rule.onNodeWithText("placeholder line two").performTouchInput { longClick() }
        rule.onNodeWithText("对齐到现在 · 1:35").performClick()

        assertEquals(listOf("n2" to 95_000L), realigned)
    }

    @Test
    fun should_offerDeleteOnly_when_hostCannotRealign() {
        setPane(notes = listOf(first), onRealign = null)

        rule.onNodeWithText("placeholder line one").performTouchInput { longClick() }

        rule.onNodeWithText("删除").assertExists()
        rule.onNodeWithText("对齐到现在", substring = true).assertDoesNotExist()
    }

    @Test
    fun should_undoInPlace_when_noteJustDeleted() {
        var undone = 0
        setPane(notes = listOf(second), deleted = first, onUndo = { undone++ })

        rule.onNodeWithText("已删除").assertExists()
        rule.onNodeWithText("撤销").performClick()

        assertEquals(1, undone)
    }

    @Test
    fun should_reportAFingerOnTheList_until_itLifts() {
        val held = mutableListOf<Boolean>()
        setPane(notes = listOf(second), deleted = first, onListHeld = { held += it })

        rule.onNodeWithText("placeholder line two").performTouchInput { down(center) }
        rule.waitForIdle()
        assertEquals(listOf(true), held)

        rule.onNodeWithText("placeholder line two").performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(listOf(true, false), held)
    }

    @Test
    fun should_ignoreATapOnTheNoteThatSlidUp_when_theUndoRowJustCollapsed() {
        val seeks = mutableListOf<Long>()
        var deleted by mutableStateOf<SongNote?>(first)
        rule.setContent {
            NoteFullscreenPane(
                notes = listOf(second),
                sortMode = NoteSortMode.Timeline,
                onSortModeChange = {},
                positionMs = { 0L },
                onSeekToMs = { seeks += it },
                draftState = NoteDraftState(),
                current = song,
                writing = false,
                onDelete = {},
                deleted = deleted,
            )
        }
        rule.onNodeWithText("撤销").assertExists()

        // The undo window closes: the row collapses and "two" glides up into its place.
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { deleted = null }
        rule.waitForIdle()
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        rule.onNodeWithText("撤销").assertDoesNotExist()
        rule.onNodeWithText("placeholder line two").performTouchInput { click() }
        rule.mainClock.advanceTimeByFrame()
        assertEquals("a tap that came down on the gliding row must not seek", emptyList<Long>(), seeks)

        // Once it has settled, the same row answers.
        rule.mainClock.advanceTimeBy(1_000L)
        rule.onNodeWithText("placeholder line two").performTouchInput { click() }
        rule.mainClock.advanceTimeByFrame()
        assertEquals(listOf(48_000L), seeks)
    }

    @Test
    fun should_showWhereDraftLands_when_writing() {
        var writing by mutableStateOf(false)
        val draft = NoteDraftState(NoteDraft(anchorMs = 30_000L, target = song))
        rule.setContent {
            NoteFullscreenPane(
                notes = listOf(first, second),
                sortMode = NoteSortMode.Timeline,
                onSortModeChange = {},
                positionMs = { 30_000L },
                onSeekToMs = {},
                draftState = draft,
                current = song,
                writing = writing,
                onDelete = {},
            )
        }
        rule.onNodeWithText("正在写…").assertDoesNotExist()

        rule.runOnIdle { writing = true }

        rule.onNodeWithText("正在写…").assertExists()
        rule.onNodeWithText("0:30").assertExists()
    }

    @Test
    fun should_notShowOtherSongsDraft_when_writingCarriedOverDraft() {
        val elsewhere = NoteTarget(songId = "subsonic:nq-t2", title = "Second Platform", artist = "Night QA Band")
        val draft = NoteDraftState(
            NoteDraft(text = "still about the last song", anchorMs = 30_000L, target = elsewhere),
        )
        setPane(notes = listOf(first), draft = draft, writing = true)

        rule.onNodeWithText("正在写…").assertDoesNotExist()
    }

    private fun setPane(
        notes: List<SongNote>,
        playhead: Long = 0L,
        deleted: SongNote? = null,
        draft: NoteDraftState = NoteDraftState(),
        writing: Boolean = false,
        onSeek: (Long) -> Unit = {},
        onDelete: (String) -> Unit = {},
        onUndo: () -> Unit = {},
        onRealign: ((SongNote, Long) -> Unit)? = { _, _ -> },
        onListHeld: (Boolean) -> Unit = {},
    ) {
        rule.setContent {
            NoteFullscreenPane(
                notes = notes,
                sortMode = NoteSortMode.Timeline,
                onSortModeChange = {},
                positionMs = { playhead },
                onSeekToMs = onSeek,
                draftState = draft,
                current = song,
                writing = writing,
                onDelete = onDelete,
                deleted = deleted,
                onUndoDelete = onUndo,
                onRealign = onRealign,
                onListHeldChange = onListHeld,
            )
        }
    }
}
