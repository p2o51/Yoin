package com.gpo.yoin.ui.notes.editor

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.foundation.layout.size
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.performTextReplacement
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
        if (query.isEmpty()) {
            MentionCandidates(tracks = listOf(row("Animal"), row("Sunny"), row("New York"), row("灯塔")))
        } else {
            MentionCandidates.Empty
        }
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

    private fun show(state: NoteEditorState) {
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
    }

    @Test
    fun should_pick_when_theKeyboardCommitsACorrectedWordWithTheNewline() {
        val state = NoteEditorState("")
        show(state)
        rule.onNodeWithTag("field").performTextInput("@")
        rule.waitForIdle()
        rule.onNodeWithTag("field").performTextInput("Ne")
        rule.waitForIdle()

        // Gboard's Enter: the composing word auto-completed and the newline, in one edit
        rule.onNodeWithTag("field").performTextReplacement("@New\n")
        rule.waitForIdle()

        assertEquals(listOf("New York"), NoteMarkup.mentions(state.text).map { it.title })
        assertEquals(false, state.text.contains('\n'))
    }

    @Test
    fun should_openOnAFullWidthAt_andMatchCommittedChinese() {
        val state = NoteEditorState("这首像")
        show(state)
        // Gboard's Chinese keyboard: a full-width ＠, then characters committed from its candidate strip
        rule.onNodeWithTag("field").performTextInput("＠")
        rule.waitForIdle()
        assertEquals("", state.query)
        rule.onNodeWithTag("field").performTextInput("灯塔")
        rule.waitForIdle()

        assertEquals("灯塔", state.query)
        assertEquals("灯塔", state.enterPick?.title)

        rule.onNodeWithTag("field").performTextInput("\n")
        rule.waitForIdle()
        assertEquals(listOf("灯塔"), NoteMarkup.mentions(state.text).map { it.title })
    }

    @Test
    fun should_endMentionMode_when_theFieldLosesFocus_notOnATouchOutsideThePopup() {
        val state = NoteEditorState("")
        lateinit var focus: androidx.compose.ui.focus.FocusManager
        rule.setContent {
            focus = androidx.compose.ui.platform.LocalFocusManager.current
            YoinTheme {
                NoteMarkupField(state = state, candidates = source, romanizer = MentionRomanizer.None, modifier = Modifier.testTag("field"))
            }
        }
        rule.onNodeWithTag("field").performTextInput("@")
        rule.waitForIdle()
        rule.onNodeWithTag("field").performTextInput("N")
        rule.waitForIdle()
        assertEquals("New York", state.enterPick?.title)

        // a tap elsewhere in the app takes focus away: the popup goes with it
        rule.runOnIdle { focus.clearFocus() }
        rule.waitForIdle()
        assertNull(state.enterPick)
    }

    @Test
    fun should_endMentionMode_onATapElsewhereInTheWindow_thoughNothingTakesFocus() {
        val state = NoteEditorState("")
        val taps = WindowTaps()
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalWindowTaps provides taps) {
                YoinTheme {
                    androidx.compose.foundation.layout.Column(Modifier.windowTapSource(taps)) {
                        NoteMarkupField(
                            state = state,
                            candidates = source,
                            romanizer = MentionRomanizer.None,
                            modifier = Modifier.testTag("field"),
                        )
                        // blank background: not focusable, not clickable
                        androidx.compose.foundation.layout.Box(
                            Modifier
                                .testTag("blank")
                                .androidxSize(),
                        )
                    }
                }
            }
        }
        rule.onNodeWithTag("field").performTextInput("@")
        rule.waitForIdle()
        rule.onNodeWithTag("field").performTextInput("N")
        rule.waitForIdle()
        assertEquals("New York", state.enterPick?.title)

        // a tap in the field itself keeps it
        rule.onNodeWithTag("field").performTouchInput { click(center) }
        rule.waitForIdle()
        assertEquals("N", state.query)

        // a tap on the blank area ends it, though the field keeps focus
        rule.onNodeWithTag("blank").performTouchInput { click(center) }
        rule.waitForIdle()
        assertNull(state.trigger)
        assertNull(state.enterPick)
    }

    @Test
    fun should_keepMentionMode_onATapInTheHostsBoxAroundTheField() {
        val state = NoteEditorState("")
        val taps = WindowTaps()
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalWindowTaps provides taps) {
                YoinTheme {
                    androidx.compose.foundation.layout.Column(Modifier.windowTapSource(taps)) {
                        // the review sheet's rounded box: the field, then its count line
                        androidx.compose.foundation.layout.Column(Modifier.noteEditorContainer(state)) {
                            NoteMarkupField(
                                state = state,
                                candidates = source,
                                romanizer = MentionRomanizer.None,
                                modifier = Modifier.testTag("field"),
                            )
                            androidx.compose.material3.Text("Short comment", modifier = Modifier.testTag("label"))
                        }
                        androidx.compose.foundation.layout.Box(Modifier.testTag("blank").androidxSize())
                    }
                }
            }
        }
        rule.onNodeWithTag("field").performTextInput("@")
        rule.waitForIdle()
        rule.onNodeWithTag("field").performTextInput("N")
        rule.waitForIdle()

        rule.onNodeWithTag("label").performTouchInput { click(center) }
        rule.waitForIdle()
        assertEquals("New York", state.enterPick?.title)

        rule.onNodeWithTag("blank").performTouchInput { click(center) }
        rule.waitForIdle()
        assertNull(state.enterPick)
    }
}

private fun Modifier.androidxSize(): Modifier = this.then(Modifier.size(200.dp))
