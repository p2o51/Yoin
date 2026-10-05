package com.gpo.yoin.ui.nowplaying

import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.player.PlaybackState
import com.gpo.yoin.ui.component.NoteDraft
import com.gpo.yoin.ui.component.NoteSortMode
import com.gpo.yoin.ui.component.NoteTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteWritingTest {

    private fun track(raw: String, title: String) = Track(
        id = MediaId(MediaId.PROVIDER_SUBSONIC, raw),
        title = title,
        artist = "Night QA Band",
        artistId = null,
        album = "Night QA Tapes",
        albumId = null,
        coverArt = null,
        durationSec = 60,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null,
    )

    private val songA = track("nq-t1", "Harbour Lights")
    private val songB = track("nq-t2", "Second Platform")
    private val targetA = NoteTarget(songId = songA.id.toString(), title = "Harbour Lights", artist = "Night QA Band")

    @Test
    fun should_fileUnderStartSong_when_playerMovedToNextSong() {
        val playback = PlaybackState(currentTrack = songB, queue = listOf(songA, songB), currentIndex = 1)

        assertSame(songA, resolveNoteTrack(targetA, playback))
    }

    @Test
    fun should_fileUnderCurrentSong_when_draftStartedOnIt() {
        val playback = PlaybackState(currentTrack = songA, queue = listOf(songA, songB), currentIndex = 0)

        assertSame(songA, resolveNoteTrack(targetA, playback))
    }

    @Test
    fun should_rebuildStartSong_when_itLeftTheQueue() {
        val playback = PlaybackState(currentTrack = songB, queue = listOf(songB), currentIndex = 0)

        val resolved = resolveNoteTrack(targetA, playback)

        assertEquals(songA.id, resolved?.id)
        assertEquals("Harbour Lights", resolved?.title)
        assertEquals("Night QA Band", resolved?.artist)
    }

    @Test
    fun should_fileUnderCurrentSong_when_requestHasNoTarget() {
        val playback = PlaybackState(currentTrack = songB)

        assertSame(songB, resolveNoteTrack(null, playback))
    }

    @Test
    fun should_dropNote_when_targetIdIsUnreadable() {
        val playback = PlaybackState(currentTrack = songB)

        assertNull(resolveNoteTrack(targetA.copy(songId = "garbled"), playback))
    }

    @Test
    fun should_giveComposerTheBottom_when_keyboardIsUpOnExpandedNotePage() {
        assertTrue(noteComposerOwnsBottom(NowPlayingStageMode.Expanded, NowPlayingDetailPage.Note, imeVisible = true))
    }

    @Test
    fun should_keepTitleAndAccessory_when_keyboardIsDownOrPageIsNotNote() {
        assertFalse(noteComposerOwnsBottom(NowPlayingStageMode.Expanded, NowPlayingDetailPage.Note, imeVisible = false))
        // The Ask bar on About owns its own keyboard layout.
        assertFalse(noteComposerOwnsBottom(NowPlayingStageMode.Expanded, NowPlayingDetailPage.About, imeVisible = true))
        // Compact: no write bar on screen; the stage must not reshape under another keyboard.
        assertFalse(noteComposerOwnsBottom(NowPlayingStageMode.Compact, NowPlayingDetailPage.Note, imeVisible = true))
    }

    @Test
    fun should_followCurrentNote_when_readingTheTimeline() {
        assertTrue(shouldFollowCurrentNote(NoteSortMode.Timeline, 3, userScrolled = false, writing = false))
    }

    @Test
    fun should_holdListStill_when_writingOrDraggedOrJournalOrder() {
        assertFalse(shouldFollowCurrentNote(NoteSortMode.Timeline, 3, userScrolled = false, writing = true))
        assertFalse(shouldFollowCurrentNote(NoteSortMode.Timeline, 3, userScrolled = true, writing = false))
        assertFalse(shouldFollowCurrentNote(NoteSortMode.Created, 3, userScrolled = false, writing = false))
        assertFalse(shouldFollowCurrentNote(NoteSortMode.Timeline, -1, userScrolled = false, writing = false))
    }

    private fun note(id: String, at: Long?, created: Long) = SongNote(
        id = id,
        trackId = "nq-t1",
        content = "placeholder $id",
        createdAt = created,
        updatedAt = created,
        title = "Harbour Lights",
        artist = "Night QA Band",
        positionMs = at,
    )

    private val early = note("early", at = 10_000L, created = 3L)
    private val middle = note("middle", at = 40_000L, created = 1L)
    private val late = note("late", at = 90_000L, created = 2L)
    private val legacy = note("legacy", at = null, created = 0L)
    private val notes = listOf(late, legacy, early, middle)

    private fun List<NoteRow>.keys() = map { row ->
        when (row) {
            is NoteRow.Line -> row.note.id
            is NoteRow.Deleted -> "deleted:${row.note.id}"
            is NoteRow.Draft -> "draft"
        }
    }

    @Test
    fun should_placeDraftAtItsMoment_when_writingOnTheTimeline() {
        val rows = noteRows(notes, NoteSortMode.Timeline, draftAnchorMs = 40_000L, showDraft = true)

        // After every note at or before its moment, before later ones and the un-anchored.
        assertEquals(listOf("early", "middle", "draft", "late", "legacy"), rows.keys())
    }

    @Test
    fun should_placeDraftLast_when_journalOrder() {
        val rows = noteRows(notes, NoteSortMode.Created, draftAnchorMs = 40_000L, showDraft = true)

        assertEquals(listOf("legacy", "middle", "late", "early", "draft"), rows.keys())
    }

    @Test
    fun should_placeDraftBeforeUnanchored_when_itIsTheLatestMoment() {
        val rows = noteRows(notes, NoteSortMode.Timeline, draftAnchorMs = 200_000L, showDraft = true)

        assertEquals(listOf("early", "middle", "late", "draft", "legacy"), rows.keys())
    }

    @Test
    fun should_leaveRowsAlone_when_notWriting() {
        assertEquals(listOf("early", "middle", "late", "legacy"), noteRows(notes, NoteSortMode.Timeline).keys())
    }

    @Test
    fun should_offerUndoInPlace_when_noteIsPendingDeletion() {
        val visible = notes - middle

        val rows = noteRows(visible, NoteSortMode.Timeline, deleted = middle)

        assertEquals(listOf("early", "deleted:middle", "late", "legacy"), rows.keys())
    }

    @Test
    fun should_hideAtOnceAndKeepRow_when_noteDeleted() {
        val deletion = NoteDeletionState()
        val committed = mutableListOf<String>()

        deletion.delete(middle) { committed += it }

        assertEquals(listOf(late, legacy, early), deletion.visible(notes))
        assertEquals(middle, deletion.pending)
        // Nothing is deleted from the store while it can still be taken back.
        assertTrue(committed.isEmpty())
    }

    @Test
    fun should_restoreSameNote_when_undone() {
        val deletion = NoteDeletionState()
        val committed = mutableListOf<String>()
        deletion.delete(middle) { committed += it }

        assertEquals(middle, deletion.undo())

        assertEquals(notes, deletion.visible(notes))
        assertNull(deletion.pending)
        assertTrue(committed.isEmpty())
    }

    @Test
    fun should_deleteForRealAndStayHidden_when_undoWindowCloses() {
        val deletion = NoteDeletionState()
        val committed = mutableListOf<String>()
        deletion.delete(middle) { committed += it }

        deletion.commit { committed += it }

        assertEquals(listOf("middle"), committed)
        assertNull(deletion.pending)
        // Until the store's list drops it, it must not flash back.
        assertEquals(listOf(late, legacy, early), deletion.visible(notes))
        deletion.prune(notes - middle)
        assertTrue(deletion.hiddenIds.isEmpty())
    }

    @Test
    fun should_commitFirst_when_secondNoteDeletedInsideUndoWindow() {
        val deletion = NoteDeletionState()
        val committed = mutableListOf<String>()
        deletion.delete(middle) { committed += it }

        deletion.delete(late) { committed += it }

        assertEquals(listOf("middle"), committed)
        assertEquals(late, deletion.pending)
        assertEquals(listOf(legacy, early), deletion.visible(notes))
    }

    @Test
    fun should_keepPendingHidden_when_pruningBeforeCommit() {
        val deletion = NoteDeletionState()
        deletion.delete(middle) {}

        // The song changed: the new list doesn't hold it, but it is still pending.
        deletion.prune(emptyList())

        assertTrue("middle" in deletion.hiddenIds)
    }

    @Test
    fun should_matchItsSong_when_comparingNoteToNowPlaying() {
        assertTrue(middle.isForSong("subsonic:nq-t1"))
        assertFalse(middle.isForSong("subsonic:nq-t2"))
        assertFalse(middle.isForSong("spotify:nq-t1"))
    }

    @Test
    fun should_landDraftHere_when_unboundOrStartedOnThisSong() {
        assertTrue(NoteDraft().belongsTo("subsonic:nq-t1"))
        assertTrue(NoteDraft(text = "x", anchorMs = 1L, target = targetA).belongsTo(targetA.songId))
        assertFalse(NoteDraft(text = "x", anchorMs = 1L, target = targetA).belongsTo("subsonic:nq-t2"))
    }
}
