package com.gpo.yoin.ui.component

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteDraftTest {

    private val songA = NoteTarget(songId = "subsonic:a", title = "Harbour Lights", artist = "Night QA Band")
    private val songB = NoteTarget(songId = "subsonic:b", title = "Second Platform", artist = "Night QA Band")

    @Test
    fun should_bindToSongAndPlayhead_when_writingBegins() {
        val draft = NoteDraft().followIfEmpty(songA, positionMs = 58_000L)

        assertEquals(songA, draft.target)
        assertEquals(58_000L, draft.anchorMs)
    }

    @Test
    fun should_keepStartSongAndAnchor_when_songChangesWhileWriting() {
        val writing = NoteDraft()
            .followIfEmpty(songA, positionMs = 58_000L)
            .edit("the outro hits", songA, positionMs = 59_000L)

        // The player moves on to B; the screen re-runs its follow and the user keeps typing.
        val afterSkip = writing
            .followIfEmpty(songB, positionMs = 1_000L)
            .edit("the outro hits hard", songB, positionMs = 3_000L)

        assertEquals(songA, afterSkip.target)
        assertEquals(58_000L, afterSkip.anchorMs)
        assertEquals("the outro hits hard", afterSkip.text)
        assertTrue(afterSkip.isForOtherTrack(songB))
        assertFalse(afterSkip.isForOtherTrack(songA))
    }

    @Test
    fun should_saveToStartSong_when_savedAfterSkip() {
        val state = NoteDraftState()
        state.followIfEmpty(songA, positionMs = 58_000L)
        state.edit("call: hai hai", songA, positionMs = 59_000L)

        val request = state.takeSaveRequest(current = songB, positionMs = 4_000L)

        assertEquals(NoteSaveRequest(content = "call: hai hai", anchorMs = 58_000L, target = songA), request)
        // The next draft starts fresh on what plays now.
        assertEquals(NoteDraft(text = "", anchorMs = 4_000L, target = songB), state.draft)
    }

    @Test
    fun should_followCurrentSong_when_draftIsEmpty() {
        val draft = NoteDraft()
            .followIfEmpty(songA, positionMs = 58_000L)
            .followIfEmpty(songB, positionMs = 2_000L)

        assertEquals(songB, draft.target)
        assertEquals(2_000L, draft.anchorMs)
        assertFalse(draft.isForOtherTrack(songB))
    }

    @Test
    fun should_keepFocusMoment_when_firstWordComesLater() {
        val draft = NoteDraft()
            .followIfEmpty(songA, positionMs = 10_000L)
            .edit("h", songA, positionMs = 14_000L)

        assertEquals(10_000L, draft.anchorMs)
    }

    @Test
    fun should_rebindToCurrentSong_when_emptiedDraftOfEndedSongGetsNewWords() {
        val emptiedOnB = NoteDraft()
            .followIfEmpty(songA, positionMs = 58_000L)
            .edit("x", songA, positionMs = 58_500L)
            .edit("", songB, positionMs = 6_000L)

        val retyped = emptiedOnB.edit("new thought", songB, positionMs = 7_000L)

        assertEquals(songB, retyped.target)
        assertEquals(7_000L, retyped.anchorMs)
    }

    @Test
    fun should_rebindToCurrentSongAndPlayhead_when_realigned() {
        val draft = NoteDraft()
            .followIfEmpty(songA, positionMs = 58_000L)
            .edit("carried over", songA, positionMs = 59_000L)
            .realign(songB, positionMs = 12_000L)

        assertEquals(songB, draft.target)
        assertEquals(12_000L, draft.anchorMs)
        assertEquals("carried over", draft.text)
    }

    @Test
    fun should_returnNoRequest_when_draftIsBlank() {
        val state = NoteDraftState(NoteDraft(text = "   ", anchorMs = 1L, target = songA))

        assertNull(state.takeSaveRequest(current = songA, positionMs = 2L))
        assertEquals("   ", state.draft.text)
    }

    @Test
    fun should_trimContent_when_saving() {
        val request = NoteDraft(text = "  late night  \n", anchorMs = 5L, target = songA).toSaveRequest()

        assertEquals("late night", request?.content)
    }

    @Test
    fun should_restoreSameDraft_when_savedAndRestored() {
        val draft = NoteDraft(text = "half a thought", anchorMs = 31_000L, target = songA)

        assertEquals(draft, NoteDraft.fromSaveable(draft.toSaveable()))
        assertEquals(NoteDraft(), NoteDraft.fromSaveable(NoteDraft().toSaveable()))
    }

    @Test
    fun should_dropWordsSongAndMoment_when_discarded() {
        val state = NoteDraftState()
        state.followIfEmpty(songA, positionMs = 58_000L)
        state.edit("written on the old profile", songA, positionMs = 59_000L)

        state.discard()

        assertEquals(NoteDraft(), state.draft)
        assertNull(state.takeSaveRequest(current = songB, positionMs = 1_000L))
    }

    @Test
    fun should_putCaretAtEnd_when_fieldIsCreatedOverDraft() {
        assertEquals(TextFieldValue("r3 draft", TextRange(8)), draftFieldValue("r3 draft", held = null))
        assertEquals(TextFieldValue("", TextRange.Zero), draftFieldValue("", held = null))
    }

    @Test
    fun should_keepCaretAndSelection_when_fieldStillShowsDraft() {
        val held = TextFieldValue("the outro hits", selection = TextRange(4, 9))

        assertEquals(held, draftFieldValue("the outro hits", held))
    }

    @Test
    fun should_moveCaretToEnd_when_draftChangedElsewhere() {
        // The other composer (or a save) changed the words under this field.
        val held = TextFieldValue("the outro", selection = TextRange(2))

        assertEquals(
            TextFieldValue("the outro hits", TextRange(14)),
            draftFieldValue("the outro hits", held),
        )
    }

    // ── editing a note from the Note page (owner 2026-11-24) ─────────────

    @Test
    fun should_openTheNotesWordsAndMoment_when_editBegins() {
        val draft = NoteDraft().beginEdit("n1", "the outro hits", anchorMs = 91_000L, song = songA)

        assertEquals("the outro hits", draft.text)
        assertEquals(91_000L, draft.anchorMs)
        assertEquals("n1", draft.editingNoteId)
        assertTrue(draft.isUntouchedEdit)
        assertFalse(draft.canSave)
    }

    @Test
    fun should_stayTheSameNote_whileEditedEmptiedOrTheSongChanges() {
        val editing = NoteDraft().beginEdit("n1", "words", anchorMs = 10_000L, song = songA)
        val emptied = editing.edit("", songB, positionMs = 1_000L).followIfEmpty(songB, positionMs = 2_000L)

        assertEquals("n1", emptied.editingNoteId)
        assertEquals(songA, emptied.target)
        assertEquals(10_000L, emptied.anchorMs)
        assertTrue(emptied.canSave)
    }

    @Test
    fun should_saveTheEditUnderItsNote_andAnEmptiedOneAsADelete() {
        val editing = NoteDraft().beginEdit("n1", "words", anchorMs = 10_000L, song = songA)

        val changed = editing.edit("better words ", songA, 0L).realign(songA, 12_000L).toSaveRequest()!!
        assertEquals(NoteSaveRequest("better words", 12_000L, songA, editingNoteId = "n1"), changed)

        val emptied = editing.edit("  ", songA, 0L).toSaveRequest()!!
        assertEquals("", emptied.content)
        assertEquals("n1", emptied.editingNoteId)
        // a new note never saves blank
        assertNull(NoteDraft().edit("  ", songA, 0L).toSaveRequest())
    }

    @Test
    fun should_neverDropUnsavedWords_forAnEdit() {
        val state = NoteDraftState(NoteDraft().edit("a new thought", songA, 5_000L))

        assertFalse(state.beginEdit("n1", "old note", 1_000L, songA))
        assertEquals("a new thought", state.draft.text)
        // the bar still opens, on the words that were waiting
        assertTrue(state.pendingEditFocus)

        val empty = NoteDraftState()
        assertTrue(empty.beginEdit("n1", "old note", 1_000L, songA))
        assertEquals("old note", empty.draft.text)
        // an untouched edit gives way to another note's
        assertTrue(empty.beginEdit("n2", "other note", 2_000L, songA))
        assertEquals("n2", empty.draft.editingNoteId)
    }

    @Test
    fun should_closeAnUntouchedEdit_andSurviveRotation() {
        val state = NoteDraftState()
        state.beginEdit("n1", "old note", 1_000L, songA)
        val restored = NoteDraft.fromSaveable(state.draft.toSaveable())
        assertEquals(state.draft, restored)

        state.cancelEdit(songB, 3_000L)
        assertEquals(NoteDraft(anchorMs = 3_000L, target = songB), state.draft)
    }
}
