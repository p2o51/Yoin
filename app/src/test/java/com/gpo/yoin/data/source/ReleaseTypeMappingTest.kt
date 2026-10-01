package com.gpo.yoin.data.source

import com.gpo.yoin.data.model.ReleaseType
import com.gpo.yoin.data.source.spotify.spotifyReleaseType
import com.gpo.yoin.data.source.subsonic.subsonicReleaseType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseTypeMappingTest {
    @Test
    fun should_mapSpotifyAlbumTypes_when_typeIsKnown() {
        assertEquals(ReleaseType.Album, spotifyReleaseType("album", 11))
        assertEquals(ReleaseType.Compilation, spotifyReleaseType("compilation", 20))
        assertEquals(ReleaseType.Single, spotifyReleaseType("single", 1))
    }

    @Test
    fun should_labelSpotifySingleAsEp_when_itHasFourOrMoreTracks() {
        assertEquals(ReleaseType.EP, spotifyReleaseType("single", 4))
        assertEquals(ReleaseType.Single, spotifyReleaseType("single", 3))
    }

    @Test
    fun should_leaveTypeUnknown_when_providerSaysNothing() {
        assertNull(spotifyReleaseType(null, 10))
        assertNull(spotifyReleaseType("appears_on", 10))
        assertNull(subsonicReleaseType(emptyList()))
        assertNull(subsonicReleaseType(listOf("Soundtrack")))
    }

    @Test
    fun should_takeFirstRecognisedOpenSubsonicType_when_severalAreListed() {
        assertEquals(ReleaseType.EP, subsonicReleaseType(listOf("Live", "EP", "Album")))
        assertEquals(ReleaseType.Single, subsonicReleaseType(listOf(" single ")))
    }
}
