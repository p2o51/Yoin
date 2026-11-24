package com.gpo.yoin.data.source.subsonic

import com.gpo.yoin.data.model.ReleaseDate
import com.gpo.yoin.data.remote.Album as SubsonicAlbum
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubsonicReleaseDateTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun album(body: String): SubsonicAlbum =
        json.decodeFromString(SubsonicAlbum.serializer(), """{"id":"al","name":"Album"$body}""")

    @Test
    fun should_useReleaseDate_when_navidromeGivesTheDay() {
        val mapped = album(
            ""","year":2022,"releaseDate":{"year":2022,"month":6,"day":12},"originalReleaseDate":{"year":1990}""",
        ).toAlbum()

        assertEquals(ReleaseDate(2022, 6, 12), mapped.releaseDate)
        assertEquals(2022, mapped.year)
    }

    @Test
    fun should_keepYearAndMonth_when_releaseDateHasNoDay() {
        assertEquals(ReleaseDate(2022, 6), album(""","releaseDate":{"year":2022,"month":6}""").toAlbum().releaseDate)
    }

    @Test
    fun should_fallBackToOriginalReleaseDate_when_releaseDateIsEmpty() {
        val mapped = album(""","year":2024,"releaseDate":{},"originalReleaseDate":{"year":2024,"month":3,"day":9}""")
            .toAlbum()

        assertEquals(ReleaseDate(2024, 3, 9), mapped.releaseDate)
    }

    @Test
    fun should_fallBackToTheYear_when_noOpenSubsonicDate() {
        val mapped = album(""","year":2022""").toAlbum()

        assertEquals(ReleaseDate(2022), mapped.releaseDate)
        assertEquals(2022, mapped.year)
    }

    @Test
    fun should_fillTheYear_when_onlyTheDateCarriesIt() {
        assertEquals(2021, album(""","releaseDate":{"year":2021,"month":1,"day":2}""").toAlbum().year)
    }

    @Test
    fun should_giveNoDate_when_theServerHasNone() {
        val mapped = album(""","year":0""").toAlbum()

        assertNull(mapped.releaseDate)
        assertNull(mapped.year)
        assertNull(album("").toAlbum().releaseDate)
    }
}
