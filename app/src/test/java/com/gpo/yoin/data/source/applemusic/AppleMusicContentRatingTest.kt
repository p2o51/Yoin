package com.gpo.yoin.data.source.applemusic

import com.gpo.yoin.data.model.ContentRating
import com.gpo.yoin.data.remote.applemusic.AppleMusicSong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Apple Music's `contentRating` ("explicit" / "clean" / absent) on albums and songs. */
class AppleMusicContentRatingTest {

    private fun album(json: String) = AppleMusicSource.album(Json.parseToJsonElement(json).jsonObject)

    private fun song(json: String) = AppleMusicSong.fromJson(Json.parseToJsonElement(json).jsonObject).toTrack()

    @Test
    fun should_markCatalogAlbums_by_theirContentRating() {
        val explicit = album("""{"id":"1","type":"albums","attributes":{"name":"A","contentRating":"explicit"}}""")
        val clean = album("""{"id":"2","type":"albums","attributes":{"name":"A","contentRating":"clean"}}""")
        val unrated = album("""{"id":"3","type":"albums","attributes":{"name":"A"}}""")

        assertEquals(ContentRating.Explicit, explicit.contentRating)
        assertEquals(ContentRating.Clean, clean.contentRating)
        assertNull(unrated.contentRating)
    }

    @Test
    fun should_takeTheCatalogRating_when_theLibraryAlbumHasNone() {
        val catalog = """"relationships":{"catalog":{"data":[{"id":"1","type":"albums",
            "attributes":{"name":"A","contentRating":"explicit"}}]}}"""
        val bare = album("""{"id":"l.1","type":"library-albums","attributes":{"name":"A"},$catalog}""")
        val own = album(
            """{"id":"l.1","type":"library-albums","attributes":{"name":"A","contentRating":"clean"},$catalog}""",
        )

        assertEquals(ContentRating.Explicit, bare.contentRating)
        assertEquals(ContentRating.Clean, own.contentRating)
    }

    @Test
    fun should_markSongs_by_theirOwnOrTheirCatalogSongsRating() {
        val catalogSong = song("""{"id":"10","type":"songs","attributes":{"name":"S","contentRating":"explicit"}}""")
        val cleanSong = song("""{"id":"11","type":"songs","attributes":{"name":"S","contentRating":"clean"}}""")
        val librarySong = song(
            """{"id":"i.1","type":"library-songs","attributes":{"name":"S"},
            "relationships":{"catalog":{"data":[{"id":"10","type":"songs",
            "attributes":{"name":"S","contentRating":"explicit"}}]}}}""",
        )
        val unrated = song("""{"id":"i.2","type":"library-songs","attributes":{"name":"S"}}""")

        assertEquals(ContentRating.Explicit, catalogSong.contentRating)
        assertEquals(ContentRating.Clean, cleanSong.contentRating)
        assertEquals(ContentRating.Explicit, librarySong.contentRating)
        assertNull(unrated.contentRating)
    }

    @Test
    fun should_readAnUnknownRatingAsUnknown() {
        assertNull(ContentRating.fromAppleMusic("restricted"))
        assertEquals(ContentRating.Explicit, ContentRating.fromAppleMusic(" Explicit "))
    }
}
