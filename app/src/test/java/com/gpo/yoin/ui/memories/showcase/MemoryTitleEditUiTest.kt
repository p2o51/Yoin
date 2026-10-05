package com.gpo.yoin.ui.memories.showcase

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.memories.MemoryEntityType
import com.gpo.yoin.ui.memories.MemoryEntry
import com.gpo.yoin.ui.memories.copy.MemoryTitleKind
import com.gpo.yoin.ui.memories.withUserMemoryTitle
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The card's title, edited in place: tap → the field in the title's own place → Save (or Done, or Cancel,
 * or Restore). The host stands in for the ViewModel and re-titles the card as it would.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h914dp")
class MemoryTitleEditUiTest {

    @get:Rule
    val rule = createComposeRule()

    private class Host(private val retitle: (String?) -> Unit) : MemoriesDiaryHost {
        val saved = mutableListOf<String>()
        var restores = 0

        override val titlesEditable: Boolean get() = true

        override fun saveMemoryTitle(memory: MemoryEntry, title: String) {
            saved += title
            retitle(title)
        }

        override fun restoreMemoryTitle(memory: MemoryEntry) {
            restores++
            retitle(null)
        }
    }

    private val editTitle = SemanticsMatcher("has the Edit title action") { node ->
        SemanticsActions.OnClick in node.config && node.config[SemanticsActions.OnClick].label == "Edit title"
    }

    private fun setCard(initial: MemoryEntry): Triple<MemoryTitleEditor, Host, () -> MemoryEntry> {
        var memory by mutableStateOf(initial)
        val host = Host { title -> memory = memory.withUserMemoryTitle(title) }
        val editor = MemoryTitleEditor(null, TextFieldValue(), "")
        rule.setContent {
            YoinTheme(darkTheme = false) {
                val haptics = rememberDiaryHaptics()
                MemoryCardFace(
                    memory = memory,
                    tones = MemoryPaletteSamples.M1.tones(dark = false),
                    metrics = memoryCardMetrics(411.dp, 914.dp),
                    onOpenDiary = {},
                    onOpenAlbum = {},
                    cover = { m -> Box(m) },
                    emblem = { m -> Box(m) },
                    titleEditing = MemoryTitleEditing(editor, host, haptics),
                )
            }
        }
        return Triple(editor, host) { memory }
    }

    @Test
    fun should_save_trimmed_title_in_place_when_save_tapped() {
        val (editor, host, memory) = setCard(entry("Rain on the glass", MemoryTitleKind.AI))

        rule.onNode(editTitle).performClick()
        assertTrue(editor.isEditing(MemoryTitleSurface.Card.keyFor(memory())))
        // the field opens on the title itself; no restore over Yoin's own title
        rule.onNodeWithContentDescription("Title").assertIsDisplayed()
        rule.onNodeWithText("Restore AI title").assertDoesNotExist()

        rule.onNodeWithContentDescription("Title").performTextReplacement("  Night bus ")
        rule.onNodeWithContentDescription("Save").performClick()
        rule.waitForIdle()

        assertEquals(listOf("Night bus"), host.saved)
        assertFalse(editor.isEditing)
        assertEquals(MemoryTitleKind.USER, memory().memoryTitleKind)
        rule.onNodeWithText("Night bus").assertIsDisplayed()
        rule.onNodeWithContentDescription("Title").assertDoesNotExist()
    }

    @Test
    fun should_save_from_keyboard_done_and_write_nothing_when_unchanged() {
        val (editor, host, _) = setCard(entry("Rain on the glass", MemoryTitleKind.AI))

        rule.onNode(editTitle).performClick()
        rule.onNodeWithContentDescription("Title").performImeAction()
        rule.waitForIdle()

        // Done saved the untouched title: the edit closes, nothing is written
        assertFalse(editor.isEditing)
        assertTrue(host.saved.isEmpty())
        assertEquals(0, host.restores)
        rule.onNodeWithText("Rain on the glass").assertIsDisplayed()
    }

    @Test
    fun should_restore_yoin_title_when_restore_tapped_over_user_title() {
        val named = entry("Rain on the glass", MemoryTitleKind.AI).withUserMemoryTitle("Night bus")
        val (editor, host, memory) = setCard(named)

        rule.onNode(editTitle).performClick()
        rule.onNodeWithText("Restore AI title").performClick()
        rule.waitForIdle()

        assertEquals(1, host.restores)
        assertFalse(editor.isEditing)
        assertEquals(MemoryTitleKind.AI, memory().memoryTitleKind)
        rule.onNodeWithText("Rain on the glass").assertIsDisplayed()
    }

    @Test
    fun should_keep_title_when_edit_cancelled() {
        val (editor, host, memory) = setCard(entry("Rain on the glass", MemoryTitleKind.AI))

        rule.onNode(editTitle).performClick()
        rule.onNodeWithContentDescription("Title").performTextReplacement("Something else")
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()

        assertFalse(editor.isEditing)
        assertNull(editor.editingKey)
        assertTrue(host.saved.isEmpty())
        assertEquals("Rain on the glass", memory().memoryTitle)
        rule.onNodeWithText("Rain on the glass").assertIsDisplayed()
    }

    @Test
    fun should_cancel_edit_when_system_back_commits() {
        val (editor, host, memory) = setCard(entry("Rain on the glass", MemoryTitleKind.AI))

        rule.onNode(editTitle).performClick()
        rule.onNodeWithContentDescription("Title").performTextReplacement("Something else")
        // MemoriesPredictiveBack's TitleEdit level on commit
        rule.runOnIdle { editor.commitBack() }
        rule.waitForIdle()

        assertTrue(host.saved.isEmpty())
        assertEquals("Rain on the glass", memory().memoryTitle)
        rule.onNodeWithContentDescription("Title").assertDoesNotExist()
        rule.onNodeWithText("Rain on the glass").assertIsDisplayed()
    }

    private fun entry(memoryTitle: String, kind: MemoryTitleKind): MemoryEntry = MemoryEntry(
        stableId = "album:profile-a:subsonic:a1",
        sourceActivityId = 1L,
        entityType = MemoryEntityType.ALBUM,
        entityId = "a1",
        entityProvider = "subsonic",
        title = "Blue Album",
        supportingText = "Artist · 2019",
        metaText = null,
        coverArtUrl = null,
        timestamp = 0L,
        scoreText = "N/A",
        scoreSupportingText = null,
        footerText = null,
        memoryTitle = memoryTitle,
        memoryTitleKind = kind,
        playbackSongs = emptyList(),
        tracks = emptyList(),
    )
}
