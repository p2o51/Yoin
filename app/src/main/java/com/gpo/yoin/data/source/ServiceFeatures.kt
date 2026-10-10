package com.gpo.yoin.data.source

import androidx.annotation.StringRes
import com.gpo.yoin.R
import com.gpo.yoin.data.model.MediaId

/** Service support in this build, independent of a particular account's auth state. */
enum class FeatureSupport(
    @param:StringRes @get:StringRes val labelRes: Int,
) {
    AVAILABLE(R.string.settings_feature_support_available),
    PARTIAL(R.string.settings_feature_support_partial),
    LOCAL(R.string.settings_feature_support_local),
    NOT_IMPLEMENTED(R.string.settings_feature_support_not_implemented),
    SERVICE_UNAVAILABLE(R.string.settings_feature_support_unavailable),
    UNVERIFIED(R.string.settings_feature_support_unverified),
}

data class ServiceFeature(
    @param:StringRes @get:StringRes val titleRes: Int,
    val support: FeatureSupport,
    @param:StringRes @get:StringRes val explanationRes: Int,
)

data class ServiceFeatures(
    val id: String,
    @param:StringRes @get:StringRes val nameRes: Int,
    val integrated: Boolean,
    val capabilities: Set<Capability>,
    val features: List<ServiceFeature>,
    val supportsYoinCast: Boolean = false,
    /**
     * The service's favorites are its library: a liked song, a saved album and
     * a followed artist are what the library holds (Spotify). Library then has
     * no Favorites tab of its own — Songs is the liked list, newest like first,
     * and plays as that collection; Artists keeps only the followed ones. Set
     * here, never inferred from [capabilities]: Subsonic has favorites and a
     * library too, and keeps its Favorites tab.
     */
    val favoritesAreLibrary: Boolean = false,
    /**
     * The service lists no songs of its own to page through, so Library's
     * Songs is its albums' songs: the most recently added album first, each
     * in its track order, a page of albums at a time as the list scrolls
     * (Subsonic: `getAlbumList2` type=newest, then each album). Set here, as
     * [favoritesAreLibrary] is: [Capability.RANDOM_SONGS] stays for Home's
     * grid and says nothing about Library.
     */
    val songsFromNewestAlbums: Boolean = false,
    /**
     * Library lists whose items carry the date they joined the library
     * ([com.gpo.yoin.data.model.Album.libraryAddedAt],
     * [com.gpo.yoin.data.model.Playlist.libraryAddedAt]), so Library offers
     * Recently added for them. Followed and library artists carry no such
     * date on any service.
     */
    val albumsHaveLibraryDates: Boolean = false,
    val playlistsHaveLibraryDates: Boolean = false,
    /**
     * Library's Alphabetical and Creator sorts skip a leading article ("The
     * Beatles" under B), as the service's own lists do (Subsonic's
     * `ignoredArticles`).
     */
    val sortIgnoresArticles: Boolean = false,
    @param:StringRes @get:StringRes val saveLabel: Int = R.string.settings_feature_save_favorites,
    @param:StringRes @get:StringRes val removeLabel: Int = R.string.settings_feature_remove_favorites,
) {
    val supportsFavorites: Boolean get() = Capability.FAVORITES in capabilities
    val supportsLibraryAdd: Boolean get() = Capability.LIBRARY_ADD in capabilities
}

/** Shared by MusicSource implementations and service explanations. No credentials or live account state. */
object ServiceFeatureCatalog {
    val subsonic = ServiceFeatures(
        id = MediaId.PROVIDER_SUBSONIC,
        nameRes = R.string.settings_feature_name_subsonic,
        supportsYoinCast = true,
        songsFromNewestAlbums = true,
        // AlbumID3.created and a playlist's created.
        albumsHaveLibraryDates = true,
        playlistsHaveLibraryDates = true,
        sortIgnoresArticles = true,
        integrated = true,
        capabilities = setOf(
            Capability.FAVORITES,
            Capability.SEARCH,
            Capability.RANDOM_SONGS,
            Capability.PLAYLISTS_READ,
            Capability.PLAYLISTS_WRITE,
            Capability.LYRICS,
        ),
        features = listOf(
            ServiceFeature(
                R.string.settings_feature_subsonic_playback_title,
                FeatureSupport.AVAILABLE,
                R.string.settings_feature_subsonic_playback_body,
            ),
            ServiceFeature(
                R.string.settings_feature_subsonic_favorites_title,
                FeatureSupport.AVAILABLE,
                R.string.settings_feature_subsonic_favorites_body,
            ),
            ServiceFeature(
                R.string.settings_feature_subsonic_playlists_title,
                FeatureSupport.PARTIAL,
                R.string.settings_feature_subsonic_playlists_body,
            ),
            ServiceFeature(
                R.string.settings_feature_subsonic_lyrics_title,
                FeatureSupport.PARTIAL,
                R.string.settings_feature_subsonic_lyrics_body,
            ),
            ServiceFeature(
                R.string.settings_feature_subsonic_ratings_title,
                FeatureSupport.LOCAL,
                R.string.settings_feature_subsonic_ratings_body,
            ),
            ServiceFeature(
                R.string.settings_feature_subsonic_quality_title,
                FeatureSupport.PARTIAL,
                R.string.settings_feature_subsonic_quality_body,
            ),
            ServiceFeature(
                R.string.settings_feature_subsonic_devices_title,
                FeatureSupport.PARTIAL,
                R.string.settings_feature_subsonic_devices_body,
            ),
        ),
    )
    val spotify = ServiceFeatures(
        id = MediaId.PROVIDER_SPOTIFY,
        nameRes = R.string.settings_feature_name_spotify,
        saveLabel = R.string.settings_feature_save_spotify,
        removeLabel = R.string.settings_feature_remove_spotify,
        favoritesAreLibrary = true,
        // A saved album's added_at; /me/playlists carries no date.
        albumsHaveLibraryDates = true,
        integrated = true,
        capabilities = setOf(
            Capability.FAVORITES,
            Capability.SEARCH,
            Capability.CATALOG_SEARCH,
            Capability.SEARCH_PLAYLISTS,
            // Library Songs is Liked Songs, read from the synced cache by
            // YoinRepository; RANDOM_SONGS stays for Home's grid.
            Capability.LIBRARY_SONGS,
            Capability.RANDOM_SONGS,
            Capability.PLAYLISTS_READ,
            Capability.PLAYLISTS_WRITE,
        ),
        features = listOf(
            ServiceFeature(
                R.string.settings_feature_spotify_playback_title,
                FeatureSupport.PARTIAL,
                R.string.settings_feature_spotify_playback_body,
            ),
            ServiceFeature(
                R.string.settings_feature_spotify_saved_title,
                FeatureSupport.AVAILABLE,
                R.string.settings_feature_spotify_saved_body,
            ),
            ServiceFeature(
                R.string.settings_feature_spotify_playlists_title,
                FeatureSupport.PARTIAL,
                R.string.settings_feature_spotify_playlists_body,
            ),
            ServiceFeature(
                R.string.settings_feature_spotify_lyrics_title,
                FeatureSupport.LOCAL,
                R.string.settings_feature_spotify_lyrics_body,
            ),
            ServiceFeature(
                R.string.settings_feature_spotify_ratings_title,
                FeatureSupport.LOCAL,
                R.string.settings_feature_spotify_ratings_body,
            ),
            ServiceFeature(
                R.string.settings_feature_spotify_quality_title,
                FeatureSupport.PARTIAL,
                R.string.settings_feature_spotify_quality_body,
            ),
            ServiceFeature(
                R.string.settings_feature_spotify_devices_title,
                FeatureSupport.PARTIAL,
                R.string.settings_feature_spotify_devices_body,
            ),
        ),
    )

    val appleMusic = ServiceFeatures(
        id = MediaId.PROVIDER_APPLE_MUSIC,
        nameRes = R.string.settings_feature_name_apple,
        // Library albums' and library playlists' dateAdded.
        albumsHaveLibraryDates = true,
        playlistsHaveLibraryDates = true,
        integrated = true,
        capabilities = setOf(
            Capability.SEARCH,
            Capability.CATALOG_SEARCH,
            Capability.SEARCH_PLAYLISTS,
            Capability.LIBRARY_ADD,
            Capability.LIBRARY_SONGS,
            Capability.PLAYLISTS_READ,
        ),
        features = listOf(
            ServiceFeature(
                R.string.settings_feature_apple_playback_title,
                FeatureSupport.PARTIAL,
                R.string.settings_feature_apple_playback_body,
            ),
            ServiceFeature(
                R.string.settings_feature_apple_library_title,
                FeatureSupport.AVAILABLE,
                R.string.settings_feature_apple_library_body,
            ),
            ServiceFeature(
                R.string.settings_feature_apple_add_title,
                FeatureSupport.AVAILABLE,
                R.string.settings_feature_apple_add_body,
            ),
            ServiceFeature(
                R.string.settings_feature_apple_playlists_title,
                FeatureSupport.PARTIAL,
                R.string.settings_feature_apple_playlists_body,
            ),
            ServiceFeature(
                R.string.settings_feature_apple_lyrics_title,
                FeatureSupport.LOCAL,
                R.string.settings_feature_apple_lyrics_body,
            ),
            ServiceFeature(
                R.string.settings_feature_apple_quality_title,
                FeatureSupport.UNVERIFIED,
                R.string.settings_feature_apple_quality_body,
            ),
            ServiceFeature(
                R.string.settings_feature_apple_offline_title,
                FeatureSupport.NOT_IMPLEMENTED,
                R.string.settings_feature_apple_offline_body,
            ),
        ),
    )
    val local = ServiceFeatures(
        id = MediaId.PROVIDER_LOCAL,
        nameRes = R.string.settings_feature_name_local,
        integrated = false,
        capabilities = emptySet(),
        features = listOf(
            ServiceFeature(
                R.string.settings_feature_local_library_title,
                FeatureSupport.NOT_IMPLEMENTED,
                R.string.settings_feature_local_library_body,
            ),
        ),
    )
    val entries = listOf(subsonic, spotify, appleMusic, local)

    fun forProvider(id: String?): ServiceFeatures = entries.firstOrNull { it.id == id }
        ?: ServiceFeatures(
            id = id.orEmpty(),
            nameRes = R.string.settings_feature_name_unknown,
            integrated = false,
            capabilities = emptySet(),
            features = listOf(
                ServiceFeature(
                    R.string.settings_feature_unknown_title,
                    FeatureSupport.UNVERIFIED,
                    R.string.settings_feature_unknown_body,
                ),
            ),
        )
}
