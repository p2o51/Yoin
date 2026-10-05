package com.gpo.yoin.ui.memories.showcase

import androidx.compose.animation.core.spring
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.TestMonotonicFrameClock
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.gpo.yoin.ui.memories.MemoryEntityType
import com.gpo.yoin.ui.memories.MemoryEntry
import com.gpo.yoin.ui.memories.copy.MemoryTitleKind
import com.gpo.yoin.ui.memories.withUserMemoryTitle
import com.gpo.yoin.ui.navigation.back.MemoriesBackMath
import com.gpo.yoin.ui.theme.YoinMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The title editor's rules: what Save does, what the field takes, the keyboard lift, back's preview. */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class MemoryTitleEditorTest {

    @Test
    fun should_write_nothing_when_field_is_saved_unchanged() {
        assertEquals(TitleEditOutcome.Unchanged, titleEditOutcome(" Rain on the glass ", "Rain on the glass", false))
        // the album name stands in: the field opened empty, and an empty save leaves it so
        assertEquals(TitleEditOutcome.Unchanged, titleEditOutcome("", "", false))
    }

    @Test
    fun should_restore_when_user_title_is_saved_empty() {
        assertEquals(TitleEditOutcome.Restore, titleEditOutcome("   ", "Night bus", userTitleInUse = true))
        // Yoin's own title cleared: there is nothing of the user's to take off
        assertEquals(TitleEditOutcome.Unchanged, titleEditOutcome("", "Rain on the glass", userTitleInUse = false))
    }

    @Test
    fun should_save_trimmed_words_when_field_changed() {
        assertEquals(TitleEditOutcome.Save("Night bus"), titleEditOutcome("  Night bus ", "Rain", false))
        assertEquals(TitleEditOutcome.Save("Night bus"), titleEditOutcome("Night bus", "", false))
    }

    @Test
    fun should_keep_title_on_one_line_and_capped_when_typed_or_pasted() {
        val pasted = sanitizeTitleInput(TextFieldValue("Night\nbus\r", TextRange(10)))
        assertEquals("Night bus ", pasted.text)
        assertEquals(TextRange(10), pasted.selection)

        val long = sanitizeTitleInput(TextFieldValue("x".repeat(MEMORY_TITLE_MAX_LENGTH + 20), TextRange(100)))
        assertEquals(MEMORY_TITLE_MAX_LENGTH, long.text.length)
        assertEquals(TextRange(MEMORY_TITLE_MAX_LENGTH), long.selection)

        val plain = TextFieldValue("Night bus", TextRange(3))
        assertTrue(sanitizeTitleInput(plain) === plain)
    }

    @Test
    fun should_close_field_without_morph_only_on_its_own_words_in_the_same_face() {
        val editingAi = TitleFace.Editing(MemoryTitleKind.AI)
        // saved: the field's words, now the user's title in the same serif
        assertTrue(titleFaceSeamless(editingAi, TitleFace.Shown("Night bus", MemoryTitleKind.USER), " Night bus"))
        // the motif's face becomes the serif: a morph
        val editingMotif = TitleFace.Editing(MemoryTitleKind.MOTIF)
        assertFalse(titleFaceSeamless(editingMotif, TitleFace.Shown("Night bus", MemoryTitleKind.USER), "Night bus"))
        // restored, or cancelled after typing: other words, a morph
        assertFalse(titleFaceSeamless(editingAi, TitleFace.Shown("Rain", MemoryTitleKind.AI), "Night bus"))
        // opening the field over the same face needs nothing either
        assertTrue(titleFaceSeamless(TitleFace.Shown("Rain", MemoryTitleKind.USER), editingAi, "Rain"))
        // a title changed from elsewhere always morphs
        val rain = TitleFace.Shown("Rain", MemoryTitleKind.AI)
        assertFalse(titleFaceSeamless(rain, TitleFace.Shown("Snow", MemoryTitleKind.AI), ""))
    }

    @Test
    fun should_seed_field_and_hint_from_the_card() {
        val ai = entry(memoryTitle = "Rain on the glass", kind = MemoryTitleKind.AI)
        assertEquals("Rain on the glass", ai.titleDraftSeed())
        assertEquals("Rain on the glass", ai.titlePlaceholder())
        assertEquals("Restore AI title", ai.withUserMemoryTitle("Mine").restoreTitleLabel())

        // the album name stands in: the field opens empty, the hint is the album name
        val album = entry(memoryTitle = "Blue Album", kind = MemoryTitleKind.ALBUM)
        assertEquals("", album.titleDraftSeed())
        assertEquals("Blue Album", album.titlePlaceholder())

        // over a motif the hint (and the restore) is Yoin's motif, not an "AI" title
        val motif = entry(memoryTitle = "Three days, two notes", kind = MemoryTitleKind.MOTIF)
            .withUserMemoryTitle("Mine")
        assertEquals("Mine", motif.titleDraftSeed())
        assertEquals("Three days, two notes", motif.titlePlaceholder())
        assertEquals("Restore Yoin's title", motif.restoreTitleLabel())
    }

    @Test
    fun should_lift_title_block_only_by_what_the_keyboard_covers() {
        // no keyboard: nothing
        assertEquals(0f, memoryTitleLiftPx(800f, rootHeightPx = 900f, imeBottomPx = 0f, clearancePx = 24f))
        // the keyboard's top at 600: the block (bottom 640 + 24 clear) rises 64
        assertEquals(64f, memoryTitleLiftPx(640f, 900f, imeBottomPx = 300f, clearancePx = 24f))
        // already clear of it: never pushed down
        assertEquals(0f, memoryTitleLiftPx(400f, 900f, imeBottomPx = 300f, clearancePx = 24f))
    }

    @Test
    fun should_open_one_surface_with_cursor_at_end_when_title_tapped() {
        val editor = MemoryTitleEditor(null, TextFieldValue(), "")
        val memory = entry(memoryTitle = "Rain", kind = MemoryTitleKind.AI)
        val card = MemoryTitleSurface.Card.keyFor(memory)

        editor.begin(card, "Rain")

        assertTrue(editor.isEditing)
        assertTrue(editor.isEditing(card))
        // the diary's title of the same memory stays closed
        assertFalse(editor.isEditing(MemoryTitleSurface.Diary.keyFor(memory)))
        assertEquals(TextFieldValue("Rain", TextRange(4)), editor.draft)
        assertEquals("Rain", editor.seed)
        editor.end()
        assertFalse(editor.isEditing)
        assertNull(editor.editingKey)
    }

    @Test
    fun should_cancel_edit_on_back_commit_and_bring_row_back_on_back_cancel() = runTest {
        val editor = MemoryTitleEditor(null, TextFieldValue(), "")
        editor.settleSpec = spring()
        val scope = CoroutineScope(coroutineContext + TestMonotonicFrameClock(this))
        editor.begin("Card:m1", "Rain")

        // the finger owns the preview 1:1 (eased progress), and a cancel springs it home
        editor.previewBack(MemoriesBackMath.titleEditPreview(0.5f))
        assertEquals(YoinMotion.backGestureEasing.transform(0.5f), editor.backPreview, 1e-6f)
        editor.cancelBack(scope)
        advanceUntilIdle()
        assertEquals(0f, editor.backPreview, 1e-3f)
        assertTrue(editor.isEditing)

        // a committed back cancels the edit; the row leaves from where the preview had it
        editor.previewBack(MemoriesBackMath.titleEditPreview(1f))
        editor.commitBack()
        assertFalse(editor.isEditing)
        assertEquals(1f, editor.backPreview, 1e-6f)
        // the next edit starts with its row whole
        editor.begin("Card:m1", "Rain")
        assertEquals(0f, editor.backPreview)
    }

    @Test
    fun should_run_the_open_fields_save_when_row_save_is_tapped() {
        val editor = MemoryTitleEditor(null, TextFieldValue(), "")
        var saves = 0
        editor.requestSave() // nothing open: nothing happens
        editor.saveAction = { saves++ }
        editor.requestSave()
        assertEquals(1, saves)
    }

    @Test
    fun should_map_back_progress_through_the_back_easing() {
        assertEquals(0f, MemoriesBackMath.titleEditPreview(0f), 1e-6f)
        assertEquals(1f, MemoriesBackMath.titleEditPreview(1f), 1e-6f)
        assertEquals(1f, MemoriesBackMath.titleEditPreview(1.4f), 1e-6f)
        assertEquals(YoinMotion.backGestureEasing.transform(0.3f), MemoriesBackMath.titleEditPreview(0.3f), 1e-6f)
    }

    private fun entry(memoryTitle: String, kind: MemoryTitleKind): MemoryEntry = MemoryEntry(
        stableId = "album:profile-a:subsonic:a1",
        sourceActivityId = 1L,
        entityType = MemoryEntityType.ALBUM,
        entityId = "a1",
        entityProvider = "subsonic",
        title = if (kind == MemoryTitleKind.ALBUM) memoryTitle else "Blue Album",
        supportingText = "Artist",
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
