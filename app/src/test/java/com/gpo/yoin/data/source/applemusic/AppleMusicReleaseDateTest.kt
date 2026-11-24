package com.gpo.yoin.data.source.applemusic

import com.gpo.yoin.data.model.ReleaseDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppleMusicReleaseDateTest {

    private fun album(json: String) = AppleMusicSource.album(Json.parseToJsonElement(json).jsonObject)

    @Test
    fun should_keepTheDay_when_aCatalogAlbumGivesTheFullDate() {
        val mapped = album("""{"id":"1440","type":"albums","attributes":{"name":"A","releaseDate":"2023-10-27"}}""")

        assertEquals(ReleaseDate(2023, 10, 27), mapped.releaseDate)
        assertEquals(2023, mapped.year)
    }

    @Test
    fun should_keepTheYear_when_appleGivesOnlyTheYear() {
        val mapped = album("""{"id":"1440","type":"albums","attributes":{"name":"A","releaseDate":"1977"}}""")

        assertEquals(ReleaseDate(1977), mapped.releaseDate)
    }

    @Test
    fun should_takeTheCatalogDate_when_theLibraryAlbumHasOnlyTheYearOrNothing() {
        val catalog = """"relationships":{"catalog":{"data":[{"id":"1440","type":"albums",
            "attributes":{"name":"A","releaseDate":"2023-10-27"}}]}}"""
        val yearOnly = album(
            """{"id":"l.1","type":"library-albums","attributes":{"name":"A","releaseDate":"2023"},$catalog}""",
        )
        val none = album("""{"id":"l.1","type":"library-albums","attributes":{"name":"A"},$catalog}""")

        assertEquals(ReleaseDate(2023, 10, 27), yearOnly.releaseDate)
        assertEquals(ReleaseDate(2023, 10, 27), none.releaseDate)
        assertEquals(2023, none.year)
    }

    @Test
    fun should_keepTheLibraryDate_when_theCatalogIsAnotherYearOrNoMorePrecise() {
        assertEquals(ReleaseDate(2019), AppleMusicSource.appleReleaseDate("2019", "2023-10-27"))
        assertEquals(ReleaseDate(2023, 1, 5), AppleMusicSource.appleReleaseDate("2023-01-05", "2023-10-27"))
        assertEquals(ReleaseDate(2023, 1, 5), AppleMusicSource.appleReleaseDate("2023-01-05", null))
    }

    @Test
    fun should_giveNoDate_when_neitherHasOne() {
        assertNull(album("""{"id":"l.1","type":"library-albums","attributes":{"name":"A"}}""").releaseDate)
        assertNull(AppleMusicSource.appleReleaseDate(null, null))
    }
}
