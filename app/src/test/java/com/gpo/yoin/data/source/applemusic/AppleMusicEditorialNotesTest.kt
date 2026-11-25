package com.gpo.yoin.data.source.applemusic

import com.gpo.yoin.data.cache.AlbumDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class AppleMusicEditorialNotesTest {

    private fun album(json: String) = AppleMusicSource.album(Json.parseToJsonElement(json).jsonObject)

    @Test
    fun should_preferTheStandardNotes_asPlainText() {
        val mapped = album(
            """{"id":"1440","type":"albums","attributes":{"name":"A","recordLabel":"Boys Don't Cry",
               "editorialNotes":{"standard":"<i>Blonde</i> took four years.<br />It shows.","short":"Short."}}}""",
        )

        assertEquals("Blonde took four years.\nIt shows.", mapped.editorialNotes)
        assertEquals(listOf("Boys Don't Cry"), mapped.recordLabels)
    }

    @Test
    fun should_fallBackToTheShortNotes_thenToTheCatalogs() {
        val short = album("""{"id":"1","type":"albums","attributes":{"name":"A","editorialNotes":{"short":"Short &amp; sweet."}}}""")
        val library = album(
            """{"id":"l.1","type":"library-albums","attributes":{"name":"A"},
               "relationships":{"catalog":{"data":[{"id":"1","type":"albums",
               "attributes":{"name":"A","editorialNotes":{"standard":"From the catalog."}}}]}}}""",
        )

        assertEquals("Short & sweet.", short.editorialNotes)
        assertEquals("From the catalog.", library.editorialNotes)
    }

    @Test
    fun should_haveNoNotes_when_appleHasNone() {
        assertNull(album("""{"id":"1","type":"albums","attributes":{"name":"A","editorialNotes":{"standard":" "}}}""").editorialNotes)
        assertNull(album("""{"id":"1","type":"albums","attributes":{"name":"A"}}""").editorialNotes)
    }

    @Test
    fun should_keepTheNotesWithTheCachedAlbum() {
        val mapped = album("""{"id":"1","type":"albums","attributes":{"name":"A","editorialNotes":{"short":"Kept."}}}""")

        assertEquals("Kept.", AlbumDto.from(mapped).toDomain().editorialNotes)
    }

    @Test
    fun should_useTheNotes_ofARealCatalogAlbumResponse() {
        // Trimmed from GET /v1/catalog/us/albums/1440873509 (Red (Deluxe Version)): editorialNotes is one of
        // the album's default attributes, no extend needed.
        val mapped = album(
            """{"id":"1440873509","type":"albums","href":"/v1/catalog/us/albums/1440873509",
               "attributes":{"name":"Red (Deluxe Version)","artistName":"Taylor Swift","recordLabel":"Big Machine Records, LLC",
               "releaseDate":"2012-10-22","trackCount":22,"isComplete":true,"genreNames":["Country","Music"],
               "copyright":"℗ 2012 Big Machine Records, LLC",
               "editorialNotes":{"standard":"Taylor Swift&rsquo;s fourth album widens her sound.<br /><br />Rock guitars sit beside pop production.",
                 "short":"Swift reaches past country."},
               "playParams":{"id":"1440873509","kind":"album"}},
               "relationships":{"artists":{"data":[{"id":"159260351","type":"artists","attributes":{"name":"Taylor Swift"}}]}}}""",
        )

        assertEquals("Taylor Swift’s fourth album widens her sound.\n\nRock guitars sit beside pop production.", mapped.editorialNotes)
        assertEquals(listOf("Big Machine Records, LLC"), mapped.recordLabels)
    }

    @Test
    fun should_openALibraryAlbum_asItsCatalogAlbum_withItsNotes() {
        val mapped = album(
            """{"id":"l.AbCdEf","type":"library-albums","attributes":{"name":"Red (Deluxe Version)","artistName":"Taylor Swift"},
               "relationships":{"catalog":{"data":[{"id":"1440873509","type":"albums",
               "attributes":{"name":"Red (Deluxe Version)","recordLabel":"Big Machine Records, LLC",
               "editorialNotes":{"short":"Swift reaches past country."}}}]}}}""",
        )

        assertEquals("1440873509", mapped.id.rawId)
        assertEquals("Swift reaches past country.", mapped.editorialNotes)
        assertEquals(listOf("Big Machine Records, LLC"), mapped.recordLabels)
    }

    @Test
    fun should_readAgain_a_cachedAlbumRowFromBeforeLabelsAndNotes() {
        val mapped = album("""{"id":"1","type":"albums","attributes":{"name":"A","editorialNotes":{"short":"Kept."}}}""")

        assertTrue(AlbumDto.from(mapped).credited)
        // A row written before: no "credited" flag, so DetailCacheStore.readAlbum treats it as a miss.
        val old = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(AlbumDto.serializer(), """{"id":"applemusic:1","name":"A","dated":true}""")
        assertFalse(old.credited)
    }
}
