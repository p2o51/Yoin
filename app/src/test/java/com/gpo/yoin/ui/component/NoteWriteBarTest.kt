package com.gpo.yoin.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Note page's write bar: one field, docked; the draft outlives it. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp")
class NoteWriteBarTest {

    @get:Rule
    val rule = createComposeRule()

    private val songA = NoteTarget(songId = "subsonic:nq-t1", title = "Harbour Lights", artist = "Night QA Band")
    private val songB = NoteTarget(songId = "subsonic:nq-t2", title = "Second Platform", artist = "Night QA Band")

    @Test
    fun should_appendToDraft_when_barOpensOverExistingDraft() {
        val bar = NoteWriteBarState(NoteDraftState(NoteDraft(text = "r3 draft", anchorMs = 28_000L, target = songA)))
        rule.setContent {
            Bar(bar, current = songA, positionMs = { 30_000L }, onSave = {})
        }

        field().assert(caretAt(8))
        field().performTextInput("Z")

        // The bug: a String-valued field started at 0 and wrote "Zr3 draft".
        assertEquals("r3 draftZ", bar.draftState.draft.text)
    }

    @Test
    fun should_appendToDraft_when_barComesBackAfterLeaving() {
        val bar = NoteWriteBarState(NoteDraftState())
        var shown by mutableStateOf(true)
        var playhead = 30_000L
        rule.setContent {
            if (shown) {
                Bar(bar, current = songA, positionMs = { playhead }, onSave = {})
            }
        }
        field().performTextInput("first thought")

        // Swiped to Lyrics (the bar leaves composition), then back later.
        rule.runOnIdle { shown = false }
        rule.waitForIdle()
        playhead = 45_000L
        rule.runOnIdle { shown = true }
        rule.waitForIdle()

        field().assert(caretAt("first thought".length))
        field().performTextInput(", then more")
        assertEquals("first thought, then more", bar.draftState.draft.text)
        // The moment stays the one writing began on.
        assertEquals(30_000L, bar.draftState.draft.anchorMs)
    }

    @Test
    fun should_saveAtWritingMomentAndKeepFocus_when_saveTapped() {
        val bar = NoteWriteBarState(NoteDraftState())
        val saved = mutableListOf<NoteSaveRequest>()
        var playhead = 58_000L
        rule.setContent {
            Bar(bar, current = songA, positionMs = { playhead }, onSave = { saved += it })
        }
        field().performTextInput("call: hai hai")
        playhead = 63_000L

        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()

        assertEquals(listOf(NoteSaveRequest(content = "call: hai hai", anchorMs = 58_000L, target = songA)), saved)
        // Ready for the next line: empty, re-bound to now, the keyboard kept.
        assertEquals(NoteDraft(text = "", anchorMs = 63_000L, target = songA), bar.draftState.draft)
        assertTrue(bar.focused)
        assertTrue(bar.open)
    }

    @Test
    fun should_waitForGate_when_writeRequestedBeforePageLanded() {
        val bar = NoteWriteBarState(NoteDraftState())
        var gate by mutableStateOf(false)
        rule.setContent {
            NoteWriteBar(
                state = bar,
                current = songA,
                positionMs = { 12_000L },
                onSave = {},
                focusGate = gate,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        rule.runOnIdle { bar.requestWriting() }
        rule.waitForIdle()
        assertFalse(bar.focused)

        rule.runOnIdle { gate = true }
        rule.waitForIdle()
        assertTrue(bar.focused)
        assertFalse(bar.pendingFocus)
        // Writing began now: the draft is bound to this song and moment.
        assertEquals(12_000L, bar.draftState.draft.anchorMs)
        assertEquals(songA, bar.draftState.draft.target)
    }

    @Test
    fun should_keepStartMomentWithoutSentence_when_draftCarriedOverSkip() {
        val bar = NoteWriteBarState(
            NoteDraftState(NoteDraft(text = "the outro hits", anchorMs = 58_000L, target = songA)),
        )
        rule.setContent {
            Bar(bar, current = songB, positionMs = { 4_000L }, onSave = {})
        }

        // The stamp keeps the start song's moment (its cover sits beside it);
        // no sentence names the song.
        rule.onNodeWithText("0:58").assertExists()
        rule.onNodeWithText("写给", substring = true).assertDoesNotExist()
    }

    @Test
    fun should_growWithTheWords_when_writing() {
        val bar = NoteWriteBarState(NoteDraftState())
        lateinit var density: Density
        rule.setContent {
            density = LocalDensity.current
            Bar(bar, current = songA, positionMs = { 1_000L }, onSave = {})
        }
        assertEquals(NoteWriteBarDefaults.IdleHeight, bar.height(density))

        field().performTextInput("one line")
        rule.waitForIdle()
        val twoLines = bar.height(density)
        assertTrue(twoLines > NoteWriteBarDefaults.IdleHeight)

        field().performTextInput("\nsecond\nthird\nfourth")
        rule.waitForIdle()
        assertTrue(bar.height(density) > twoLines)
    }

    @Test
    fun should_keepMomentAndSaveInReach_when_landscapeKeyboardLeavesLittleHeight() {
        // A landscape phone with the keyboard up: ~60dp for the bar, three lines written.
        val bar = NoteWriteBarState(
            NoteDraftState(NoteDraft(text = "line one\nline two\nline three", anchorMs = 61_000L, target = songA)),
        )
        rule.setContent {
            Box(modifier = Modifier.width(480.dp).height(60.dp)) {
                NoteWriteBar(
                    state = bar,
                    current = songA,
                    positionMs = { 61_000L },
                    onSave = {},
                    inline = true,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        val save = rule.onNodeWithText("Save").getUnclippedBoundsInRoot()
        val stamp = rule.onNodeWithText("1:01").getUnclippedBoundsInRoot()
        assertTrue(save.top >= 0.dp && save.bottom <= 60.dp)
        assertTrue(stamp.top >= 0.dp && stamp.bottom <= 60.dp)
        // The words scroll inside the line instead of pushing them out.
        val words = field().getUnclippedBoundsInRoot()
        assertTrue(words.bottom <= 60.dp)
    }

    @Composable
    private fun Bar(
        state: NoteWriteBarState,
        current: NoteTarget,
        positionMs: () -> Long,
        onSave: (NoteSaveRequest) -> Unit,
    ) {
        NoteWriteBar(
            state = state,
            current = current,
            positionMs = positionMs,
            onSave = onSave,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    private fun field() = rule.onNode(hasSetTextAction())

    private fun caretAt(offset: Int) =
        SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(offset))
}
