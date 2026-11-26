package com.gpo.yoin.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContentRatingTest {

    @Test
    fun should_beExplicit_when_anyTrackIs_evenWithTheTracklistIncomplete() {
        assertEquals(ContentRating.Explicit, ContentRating.fromTracks(listOf(null, ContentRating.Explicit), complete = false))
    }

    @Test
    fun should_beClean_onlyWhenEveryTrackOfTheWholeTracklistIsClean() {
        val clean = listOf(ContentRating.Clean, ContentRating.Clean)
        assertEquals(ContentRating.Clean, ContentRating.fromTracks(clean, complete = true))
        assertNull(ContentRating.fromTracks(clean, complete = false))
        assertNull(ContentRating.fromTracks(listOf(ContentRating.Clean, null), complete = true))
        assertNull(ContentRating.fromTracks(emptyList(), complete = true))
    }

    @Test
    fun should_roundTripTheStoredName_andReadAnythingElseAsUnknown() {
        ContentRating.entries.forEach { assertEquals(it, ContentRating.fromStorage(it.name)) }
        assertNull(ContentRating.fromStorage(null))
        assertNull(ContentRating.fromStorage("Restricted"))
    }
}
