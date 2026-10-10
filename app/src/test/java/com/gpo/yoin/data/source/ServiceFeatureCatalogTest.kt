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
}
