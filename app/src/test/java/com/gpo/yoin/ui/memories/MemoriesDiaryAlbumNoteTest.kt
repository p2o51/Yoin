package com.gpo.yoin.ui.memories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoriesDiaryAlbumNoteTest {

    @Test
    fun should_keep_only_the_latest_album_note_when_an_album_has_several() {
        val writings = listOf(
            albumNote(id = "a1", text = "First", at = 1L),
            albumNote(id = "a2", text = "Latest", at = 3L),
            MemoryWriting(kind = MemoryWriting.Kind.SONG_NOTE, text = "Song", writtenAt = 4L, noteId = "s1"),
            albumNote(id = "a3", text = "Middle", at = 2L),
        )

        assertEquals(listOf("a2"), diaryAlbumNotes(writings).map(MemoryWriting::noteId))
    }

    @Test
    fun should_show_no_album_note_when_there_is_none() {
        val writings = listOf(
            MemoryWriting(kind = MemoryWriting.Kind.REVIEW, text = "Review", writtenAt = 1L),
        )

        assertTrue(diaryAlbumNotes(writings).isEmpty())
    }

    private fun albumNote(id: String, text: String, at: Long) = MemoryWriting(
        kind = MemoryWriting.Kind.ALBUM_NOTE,
        text = text,
        writtenAt = at,
        noteId = id,
    )
}
