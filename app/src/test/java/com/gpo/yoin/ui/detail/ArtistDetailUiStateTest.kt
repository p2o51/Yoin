package com.gpo.yoin.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtistDetailUiStateTest {
    private fun content(vararg ratings: Float?) = ArtistDetailUiState.Content(
        artistId = "subsonic:ar0",
        artistName = "Hazel Arden",
        heroCoverArtUrl = null,
        albums = ratings.mapIndexed { index, rating ->
            ArtistAlbum(id = "subsonic:al$index", name = "Album $index", coverArtUrl = null, year = 2020, songCount = 8, userRating = rating)
        },
    )

    @Test
    fun should_averageOnlyRatedAlbums_when_someAreRated() {
        val state = content(9f, null, 6f, null)
        assertEquals(2, state.ratedAlbumCount)
        assertEquals(7.5f, state.averageAlbumRating!!, 0.0001f)
    }

    @Test
    fun should_haveNoAverage_when_nothingIsRated() {
        val state = content(null, null)
        assertEquals(0, state.ratedAlbumCount)
        assertNull(state.averageAlbumRating)
    }
}
