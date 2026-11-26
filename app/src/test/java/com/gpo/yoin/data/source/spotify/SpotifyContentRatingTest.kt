package com.gpo.yoin.data.source.spotify

import com.gpo.yoin.data.local.SpotifyLibraryAlbumCache
import com.gpo.yoin.data.model.ContentRating
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spotify's `explicit` on tracks, and an album's mark derived from its tracks (it has none of its own). */
class SpotifyContentRatingTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun track(id: String, explicit: Boolean?): String =
        """{"id":"$id","name":"Song $id"${explicit?.let { ""","explicit":$it""" }.orEmpty()}}"""

    private fun album(total: Int, vararg tracks: Pair<String, Boolean?>): SpotifyAlbumObject = json.decodeFromString(
        SpotifyAlbumObject.serializer(),
        """{"id":"a","name":"Album","total_tracks":$total,""" +
            """"tracks":{"items":[${tracks.joinToString(",") { (id, explicit) -> track(id, explicit) }}],"total":$total}}""",
    )

    @Test
    fun should_markTheTrack_when_spotifySaysExplicitOrNot() {
        val explicit = json.decodeFromString(SpotifyTrackObject.serializer(), track("t1", true)).toTrack()
        val clean = json.decodeFromString(SpotifyTrackObject.serializer(), track("t2", false)).toTrack()
        val unsaid = json.decodeFromString(SpotifyTrackObject.serializer(), track("t3", null)).toTrack()

        assertEquals(ContentRating.Explicit, explicit.contentRating)
        assertTrue(explicit.isExplicit)
        assertEquals(ContentRating.Clean, clean.contentRating)
        assertFalse(clean.isExplicit)
        assertNull(unsaid.contentRating)
    }

    @Test
    fun should_markTheAlbumExplicit_when_anyTrackIs() {
        val mapped = album(3, "t1" to false, "t2" to true, "t3" to false).toAlbum()

        assertEquals(ContentRating.Explicit, mapped.contentRating)
        assertEquals(listOf(false, true, false), mapped.tracks.map { it.isExplicit })
    }

    @Test
    fun should_markTheAlbumExplicit_when_anExplicitTrackIsOnAPartialPage() {
        // 60 tracks, the first page of 2 read: one explicit one is enough.
        assertEquals(ContentRating.Explicit, album(60, "t1" to false, "t2" to true).contentRating())
    }

    @Test
    fun should_markTheAlbumClean_onlyWhenTheWholeTracklistIsCleanAndKnown() {
        assertEquals(ContentRating.Clean, album(2, "t1" to false, "t2" to false).contentRating())
        // Only part of the tracklist known: the rest could be explicit.
        assertNull(album(60, "t1" to false, "t2" to false).contentRating())
        // A track Spotify said nothing about.
        assertNull(album(2, "t1" to false, "t2" to null).contentRating())
        // No tracks at all.
        assertNull(album(0).contentRating())
    }

    @Test
    fun should_leaveASimplifiedAlbumUnknown_when_itsTracksArentKnown() {
        val simplified = album(1, "t1" to true).toSimplifiedAlbum()

        assertNull(simplified.toAlbum().contentRating)
        assertEquals(ContentRating.Explicit, simplified.toAlbum(contentRating = ContentRating.Explicit).contentRating)
    }

    @Test
    fun should_keepTheMark_when_theLibraryMirrorRoundTrips() {
        val track = json.decodeFromString(SpotifyTrackObject.serializer(), track("t1", true)).toTrack()
        val album = album(1, "t1" to true).toAlbum()

        assertEquals(ContentRating.Explicit, track.toSpotifyLibraryTrackCache("p", 1L).toTrack().contentRating)
        assertEquals(ContentRating.Explicit, album.toSpotifyLibraryAlbumCache("p", 1L).toAlbum().contentRating)
        // A row from before the column (or an unknown name) is unknown.
        val old = SpotifyLibraryAlbumCache("p", "a", "Album", null, null, null, 1, null, true, null, 1L)
        assertNull(old.toAlbum().contentRating)
        assertNull(old.copy(contentRating = "Bogus").toAlbum().contentRating)
    }

    @Test
    fun should_keepTheReleaseKind_when_mappingAFullAlbum() {
        val ep = json.decodeFromString(
            SpotifyAlbumObject.serializer(),
            """{"id":"a","name":"EP","album_type":"single","total_tracks":5,"tracks":{"items":[],"total":5}}""",
        )
        assertEquals(com.gpo.yoin.data.model.ReleaseType.EP, ep.toAlbum().releaseType)
        assertEquals(com.gpo.yoin.data.model.ReleaseType.EP, ep.toAlbum().toSpotifyLibraryAlbumCache("p", 1L).toAlbum().releaseType)
    }
}
