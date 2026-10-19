package com.gpo.yoin.data.source

import com.gpo.yoin.data.model.MediaId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceFeatureCatalogTest {

    @Test
    fun should_markSpotifyFavoritesAsItsLibrary_when_catalogRead() {
        val spotify = ServiceFeatureCatalog.forProvider(MediaId.PROVIDER_SPOTIFY)

        assertTrue(spotify.favoritesAreLibrary)
        // Library Songs is Liked Songs; Home's random grid keeps its own capability.
        assertTrue(Capability.LIBRARY_SONGS in spotify.capabilities)
        assertTrue(Capability.RANDOM_SONGS in spotify.capabilities)
    }

    @Test
    fun should_keepFavoritesApartFromLibrary_when_serviceIsNotSpotify() {
        val subsonic = ServiceFeatureCatalog.forProvider(MediaId.PROVIDER_SUBSONIC)
        val apple = ServiceFeatureCatalog.forProvider(MediaId.PROVIDER_APPLE_MUSIC)

        assertFalse(subsonic.favoritesAreLibrary)
        // Subsonic has favorites of its own: the flag, not a capability, tells them apart.
        assertTrue(subsonic.supportsFavorites)
        assertFalse(apple.favoritesAreLibrary)
        assertFalse(ServiceFeatureCatalog.forProvider("unknown").favoritesAreLibrary)
    }

    @Test
    fun should_letAppleMusicTakeACopyWithoutPlaylistEdits_when_catalogRead() {
        val apple = ServiceFeatureCatalog.forProvider(MediaId.PROVIDER_APPLE_MUSIC).capabilities

        assertTrue(Capability.PLAYLIST_IMPORT in apple)
        // PLAYLISTS_WRITE would light up rename, delete and remove, which Apple's API lacks.
        assertFalse(Capability.PLAYLISTS_WRITE in apple)
        assertTrue(Capability.PLAYLIST_IMPORT in ServiceFeatureCatalog.forProvider(MediaId.PROVIDER_SPOTIFY).capabilities)
        assertTrue(Capability.PLAYLIST_IMPORT in ServiceFeatureCatalog.forProvider(MediaId.PROVIDER_SUBSONIC).capabilities)
        assertFalse(Capability.PLAYLIST_IMPORT in ServiceFeatureCatalog.forProvider(MediaId.PROVIDER_LOCAL).capabilities)
    }

    @Test
    fun should_sortOnlyAppleMusicsSongsByTitle_when_catalogRead() {
        assertTrue(ServiceFeatureCatalog.forProvider(MediaId.PROVIDER_APPLE_MUSIC).songsByTitle)
        // Spotify's Songs is Liked Songs, newest first; Subsonic's come album by album.
        assertFalse(ServiceFeatureCatalog.forProvider(MediaId.PROVIDER_SPOTIFY).songsByTitle)
        assertFalse(ServiceFeatureCatalog.forProvider(MediaId.PROVIDER_SUBSONIC).songsByTitle)
    }
}
