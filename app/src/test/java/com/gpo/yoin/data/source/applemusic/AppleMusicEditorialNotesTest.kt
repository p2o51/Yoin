package com.gpo.yoin.data.source.applemusic

import com.gpo.yoin.data.cache.AlbumDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
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
}
