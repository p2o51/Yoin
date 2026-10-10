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
        integrated = true,
        capabilities = setOf(
            Capability.FAVORITES,
            Capability.SEARCH,
            Capability.CATALOG_SEARCH,
            Capability.SEARCH_PLAYLISTS,
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
