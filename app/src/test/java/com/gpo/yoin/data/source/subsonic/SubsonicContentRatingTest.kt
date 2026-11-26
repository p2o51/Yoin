package com.gpo.yoin.data.source.subsonic

import com.gpo.yoin.data.model.ContentRating
import com.gpo.yoin.data.remote.Album as SubsonicAlbum
import com.gpo.yoin.data.remote.Song as SubsonicSong
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** OpenSubsonic's `explicitStatus` ("explicit" / "clean" / "" / absent) on songs and albums. */
class SubsonicContentRatingTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun album(body: String) =
        json.decodeFromString(SubsonicAlbum.serializer(), """{"id":"al","name":"Album"$body}""").toAlbum()

    private fun song(body: String) =
        json.decodeFromString(SubsonicSong.serializer(), """{"id":"s","title":"Song"$body}""").toTrack()

    @Test
    fun should_markSongs_by_theirExplicitStatus() {
        assertEquals(ContentRating.Explicit, song(""","explicitStatus":"explicit"""").contentRating)
        assertEquals(ContentRating.Clean, song(""","explicitStatus":"clean"""").contentRating)
        // Navidrome sends "" when the file carries no advisory tag; plain Subsonic sends nothing.
        assertNull(song(""","explicitStatus":""""").contentRating)
        assertNull(song("").contentRating)
    }

    @Test
    fun should_markAlbums_by_theirOwnExplicitStatus() {
        assertEquals(ContentRating.Explicit, album(""","explicitStatus":"explicit"""").contentRating)
        assertEquals(ContentRating.Clean, album(""","explicitStatus":"clean"""").contentRating)
        assertNull(album(""","explicitStatus":""""").contentRating)
        assertNull(album("").contentRating)
    }

    @Test
    fun should_markTheAlbumsTracks_when_getAlbumListsThem() {
        val mapped = album(
            ""","explicitStatus":"explicit","song":[
                {"id":"1","title":"A","explicitStatus":"explicit"},
                {"id":"2","title":"B","explicitStatus":"clean"}]""",
        )

        assertEquals(listOf(ContentRating.Explicit, ContentRating.Clean), mapped.tracks.map { it.contentRating })
    }
}
