package com.gpo.yoin.ui.memories

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryCopyInputMarkupTest {

    @Test
    fun should_quoteWords_notMarkup_onTheMemoryCard() {
        val review = MemoryWriting(
            kind = MemoryWriting.Kind.REVIEW,
            text = "**整张**都像 [@Titanic Rising](yoin:album/applemusic:1?a=Weyes%20Blood)。",
            writtenAt = 0L,
        )
        val note = MemoryWriting(
            kind = MemoryWriting.Kind.SONG_NOTE,
            text = "- [ ] 和 [@Andromeda](yoin:track/applemusic:2) 一起听",
            writtenAt = 1L,
            trackTitle = "Movies",
        )

        val input = memoryCopyInput(
            albumName = "Titanic Rising",
            aiTitle = null,
            review = review,
            writings = listOf(review, note),
            tracks = emptyList(),
            ratedTracks = 0,
            totalTracks = 0,
            albumScore = null,
            history = null,
            zone = ZoneId.of("UTC"),
        )

        assertEquals("整张都像 Titanic Rising。", input.review)
        val noteText = input.notes.single().text
        assertEquals(false, noteText.contains("yoin:"))
        assertEquals(true, noteText.contains("和 Andromeda 一起听"))
    }
}
