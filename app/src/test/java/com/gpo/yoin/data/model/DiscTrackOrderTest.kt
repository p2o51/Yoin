package com.gpo.yoin.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class DiscTrackOrderTest {
    private fun track(id: String, disc: Int?, number: Int?) = Track(
        id = MediaId.subsonic(id),
        title = id,
        artist = null,
        artistId = null,
        album = null,
        albumId = null,
        coverArt = null,
        durationSec = null,
        trackNumber = number,
        year = null,
        genre = null,
        userRating = null,
        discNumber = disc,
    )

    @Test
    fun should_orderByDiscThenTrack_when_albumHasTwoDiscs() {
        val shuffled = listOf(track("2-1", 2, 1), track("1-2", 1, 2), track("2-2", 2, 2), track("1-1", 1, 1))
        assertEquals(listOf("1-1", "1-2", "2-1", "2-2"), discTrackOrder(shuffled).map { it.title })
    }

    @Test
    fun should_keepServerOrder_when_noDiscNumbers() {
        val list = listOf(track("b", null, 2), track("a", null, 1))
        assertEquals(listOf("b", "a"), discTrackOrder(list).map { it.title })
    }
}
