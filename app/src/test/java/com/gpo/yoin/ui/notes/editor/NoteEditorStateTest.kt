package com.gpo.yoin.ui.notes.editor

import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.TextRange
import com.gpo.yoin.shared.notes.MentionKind
import com.gpo.yoin.shared.notes.NoteMarkup
import com.gpo.yoin.shared.notes.NoteMention
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NoteEditorStateTest {

    private val charm = NoteMention(MentionKind.Album, "subsonic", "al-charm", "Charm", "Clairo")

    private fun NoteEditorState.type(value: String) {
        fieldState.edit {
            append(value)
            placeCursorAtEnd()
        }
        Snapshot.sendApplyNotifications()
    }

    @Test
    fun should_followTheAt_andDismissUntilAnotherOne() {
        val state = NoteEditorState("整张像 ")
        state.type("@ch")
        assertEquals("ch", state.trigger?.query)
        state.dismissPopup()
        assertNull(state.trigger)
        state.type(" and @")
        assertEquals("", state.trigger?.query)
    }

    @Test
    fun should_reopen_when_aDismissedAtIsDeletedAndTypedAgainAtTheSamePlace() {
        val state = NoteEditorState("")
        state.type("@")
        state.dismissPopup()
        assertNull(state.trigger)
        state.fieldState.edit {
            replace(0, length, "")
            placeCursorAtEnd()
        }
        Snapshot.sendApplyNotifications()
        state.syncDismissal()
        state.type("@")
        state.syncDismissal()
        assertEquals("", state.trigger?.query)
    }

    @Test
    fun should_keepTheDismissal_while_typingAfterTheSameAt() {
        val state = NoteEditorState("x ")
        state.type("@a")
        state.dismissPopup()
        state.type("b")
        state.syncDismissal()
        assertNull(state.trigger)
    }

    @Test
    fun should_insertAChip_inPlaceOfTheTypedAt() {
        val state = NoteEditorState("整张像 ")
        state.type("@cha")
        state.insertMention(charm)
        assertEquals("整张像 ${NoteMarkup.serializeMention(charm)} ", state.text)
        assertEquals(TextRange(state.text.length), state.fieldState.selection)
        assertNull(state.trigger)
        assertEquals(listOf(charm), NoteMarkup.mentions(state.text))
    }

    @Test
    fun should_runTheRowsTools_andToggleTodos() {
        val state = NoteEditorState("one")
        state.apply(NoteTool.Checklist)
        assertEquals("- [ ] one", state.text)
        assertTrue(NoteTool.Checklist in state.activeTools)
        state.toggleChecklist(0)
        assertEquals("- [x] one", state.text)
        state.setText("")
        state.apply(NoteTool.Mention)
        assertEquals("@", state.text)
        assertEquals("", state.trigger?.query)
    }
}
