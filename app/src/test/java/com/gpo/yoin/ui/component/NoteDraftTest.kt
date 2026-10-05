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
}
