package com.gpo.yoin.data.source.spotify

import com.gpo.yoin.data.model.ReleaseDate
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifyReleaseDateTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun album(date: String?, precision: String?): SpotifyAlbumObject = json.decodeFromString(
        SpotifyAlbumObject.serializer(),
        buildString {
            append("""{"id":"a","name":"Album"""")
            if (date != null) append(""","release_date":"$date"""")
            if (precision != null) append(""","release_date_precision":"$precision"""")
            append("}")
        },
    )

    @Test
    fun should_keepTheDay_when_precisionIsDay() {
        val mapped = album("2023-10-27", "day").toAlbum()

        assertEquals(ReleaseDate(2023, 10, 27), mapped.releaseDate)
        assertEquals(2023, mapped.year)
    }

    @Test
    fun should_keepYearAndMonth_when_precisionIsMonth() {
        assertEquals(ReleaseDate(1999, 4), album("1999-04", "month").toAlbum().releaseDate)
    }

    @Test
    fun should_keepTheYear_when_precisionIsYear() {
        val mapped = album("1981", "year").toAlbum()

        assertEquals(ReleaseDate(1981), mapped.releaseDate)
        assertEquals(1981, mapped.year)
    }

    @Test
    fun should_carryTheDate_when_mappingASimplifiedAlbumAndItsTracks() {
        val full = album("2023-10-27", "day")
        val simplified = full.toSimplifiedAlbum()

        assertEquals(ReleaseDate(2023, 10, 27), simplified.toAlbum().releaseDate)
        assertEquals("day", simplified.releaseDatePrecision)
    }

    @Test
    fun should_giveNoDate_when_spotifyHasNone() {
        val unknown = album("0000", "year").toAlbum()
        val missing = album(null, null).toAlbum()

        assertNull(unknown.releaseDate)
        assertNull(unknown.year)
        assertNull(missing.releaseDate)
    }
}
