package com.gpo.yoin.ui.notes.editor

import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.TextRange
import com.gpo.yoin.shared.notes.MentionKind
import com.gpo.yoin.shared.notes.NoteEditorText
import com.gpo.yoin.shared.notes.NoteMarkup
import com.gpo.yoin.shared.notes.NoteMention
import com.gpo.yoin.shared.notes.NoteTool
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

    // ── @ mode as a state machine (notes 0.6.4) ──────────────────────────

    private fun NoteEditorState.backspace() {
        fieldState.edit {
            replace(length - 1, length, "")
            placeCursorAtEnd()
        }
        Snapshot.sendApplyNotifications()
        syncDismissal()
    }

    @Test
    fun should_followEachLetter_andEnd_onSpaceDeletedAtOrEscape() {
        val state = NoteEditorState("")
        state.type("@")
        assertEquals("", state.query)
        state.type("N")
        assertEquals("N", state.query)
        state.type("e")
        assertEquals("Ne", state.query)

        // a space ends mention mode
        state.type(" ")
        assertNull(state.trigger)
        // back over the space: the same @ is live again
        state.backspace()
        assertEquals("Ne", state.query)

        // deleting the @ itself ends it
        state.backspace()
        state.backspace()
        assertEquals("", state.query)
        state.backspace()
        assertNull(state.trigger)

        // Escape (dismiss) ends it until another @
        state.type("@x")
        state.dismissPopup()
        assertNull(state.trigger)
        state.type("y")
        state.syncDismissal()
        assertNull(state.trigger)
        state.type(" @")
        state.syncDismissal()
        assertEquals("", state.query)
    }

    @Test
    fun should_pickTheMention_when_aMentionIsChosenFromTheTypedQuery() {
        val state = NoteEditorState("")
        state.type("@cha")
        state.enterPick = charm
        // what the field's Enter does through the input transformation: the newline becomes the pick
        val fix = NoteEditorText.enterPicksMention(state.text, state.text + "\n", state.trigger!!, state.enterPick!!)!!
        assertEquals("${NoteMarkup.serializeMention(charm)} ", fix.text)
    }
}
