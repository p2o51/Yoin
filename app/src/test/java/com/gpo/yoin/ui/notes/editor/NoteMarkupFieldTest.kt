package com.gpo.yoin.ui.notes.editor

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import com.gpo.yoin.shared.notes.MentionKind
import com.gpo.yoin.shared.notes.NoteMarkup
import com.gpo.yoin.shared.notes.NoteMention
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The @ popup in a real field: each letter filters at once, and the soft keyboard's Enter picks the top row. */
@RunWith(RobolectricTestRunner::class)
class NoteMarkupFieldTest {
    @get:Rule
    val rule = createComposeRule()

    private fun row(title: String) = MentionCandidate(
        mention = NoteMention(MentionKind.Track, "subsonic", title.lowercase(), title, "Someone"),
        coverUrl = null,
        source = MentionCandidate.Source.Recent,
    )

    private val asked = mutableListOf<String>()
    private val source = MentionCandidateSource { query, _ ->
        asked += query
        if (query.isEmpty()) MentionCandidates(tracks = listOf(row("Animal"), row("Sunny"), row("New York"))) else MentionCandidates.Empty
    }

    @Test
    fun should_putNewYorkFirst_onAtN_andPickItOnEnter() {
        val state = NoteEditorState("")
        rule.setContent {
            YoinTheme {
                NoteMarkupField(
                    state = state,
                    candidates = source,
                    romanizer = MentionRomanizer.None,
                    modifier = Modifier.testTag("field"),
                )
            }
        }
        rule.onNodeWithTag("field").performTextInput("@")
        rule.waitForIdle()
        rule.onNodeWithTag("field").performTextInput("N")
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()

        assertEquals("New York", state.enterPick?.title)
        assertEquals(listOf("", "N"), asked)

        rule.onNodeWithTag("field").performTextInput("\n")
        rule.waitForIdle()

        assertEquals(listOf("New York"), NoteMarkup.mentions(state.text).map { it.title })
        assertEquals(false, state.text.contains('\n'))
        assertNull(state.trigger)
    }

    @Test
    fun should_typeANewline_when_noPopupIsOpen() {
        val state = NoteEditorState("")
        rule.setContent {
            YoinTheme {
                NoteMarkupField(state = state, candidates = source, modifier = Modifier.testTag("field"))
            }
        }
        rule.onNodeWithTag("field").performTextInput("plain")
        rule.onNodeWithTag("field").performTextInput("\n")
        rule.waitForIdle()

        assertEquals("plain\n", state.text)
    }
}
