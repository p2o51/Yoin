package com.gpo.yoin.data.source

import com.gpo.yoin.data.model.MediaId

/** Service support in this build, independent of a particular account's auth state. */
enum class FeatureSupport(val label: String) {
    AVAILABLE("Available"),
    PARTIAL("Limited"),
    LOCAL("By Yoin"),
    NOT_IMPLEMENTED("Not in Yoin yet"),
    SERVICE_UNAVAILABLE("Not exposed by service"),
    UNVERIFIED("Not verified")
}

data class ServiceFeature(
    val title: String,
    val support: FeatureSupport,
    val explanation: String
)

data class ServiceFeatures(
    val id: String,
    val name: String,
    val summary: String,
    val integrated: Boolean,
    val capabilities: Set<Capability>,
    val features: List<ServiceFeature>,
    val supportsYoinCast: Boolean = false,
    val saveLabel: String = "Add to favorites",
    val removeLabel: String = "Remove from favorites"
) {
    val supportsFavorites: Boolean get() = Capability.FAVORITES in capabilities
}

/** Shared by MusicSource implementations and service explanations. No credentials or live account state. */
object ServiceFeatureCatalog {
    val subsonic = ServiceFeatures(
        id = MediaId.PROVIDER_SUBSONIC,
        name = "Subsonic",
        supportsYoinCast = true,
        summary = "Plays in Yoin · Server features may vary",
        integrated = true,
        capabilities = setOf(
            Capability.FAVORITES,
            Capability.SEARCH,
            Capability.RANDOM_SONGS,
            Capability.PLAYLISTS_READ,
            Capability.PLAYLISTS_WRITE,
            Capability.LYRICS
        ),
        features = listOf(
            ServiceFeature(
                "Playback",
                FeatureSupport.AVAILABLE,
                "Stream from your server in Yoin, with background and system playback controls."
            ),
            ServiceFeature("Favorites", FeatureSupport.AVAILABLE, "The heart stars or unstars music on your server."),
            ServiceFeature(
                "Playlists",
                FeatureSupport.PARTIAL,
                "Browse and edit writable playlists. Permissions and supported operations depend on your server."
            ),
            ServiceFeature(
                "Lyrics",
                FeatureSupport.PARTIAL,
                "Use server lyrics when available, with Yoin's additional lyric sources. Matches are not " +
                    "guaranteed."
            ),
            ServiceFeature(
                "Ratings & notes",
                FeatureSupport.LOCAL,
                "Saved separately for each profile. Track ratings also sync to servers that support ratings."
            ),
            ServiceFeature(
                "Audio quality",
                FeatureSupport.PARTIAL,
                "Your server supplies the stream and may transcode it. The original file format does not " +
                    "confirm the current output."
            ),
            ServiceFeature(
                "Devices & cache",
                FeatureSupport.PARTIAL,
                "Yoin supports Cast and automatic playback caching. The cache is not a managed offline " +
                    "download library."
            )
        )
    )
    val spotify = ServiceFeatures(
        id = MediaId.PROVIDER_SPOTIFY,
        name = "Spotify",
        saveLabel = "Save to Spotify liked songs",
        removeLabel = "Remove from Spotify liked songs",
        summary = "Plays through Spotify · Premium required",
        integrated = true,
        capabilities = setOf(
            Capability.FAVORITES,
            Capability.SEARCH,
            Capability.RANDOM_SONGS,
            Capability.PLAYLISTS_READ,
            Capability.PLAYLISTS_WRITE
        ),
        features = listOf(
            ServiceFeature(
                "Playback",
                FeatureSupport.PARTIAL,
                "Requires the Spotify app and Premium. Spotify handles audio and system playback " +
                    "controls; Yoin controls playback remotely."
            ),
            ServiceFeature(
                "Saved music",
                FeatureSupport.AVAILABLE,
                "The heart saves or removes a song from your Spotify liked songs."
            ),
            ServiceFeature(
                "Playlists",
                FeatureSupport.PARTIAL,
                "Browse playlists and edit those you can write to. Removing your playlist in Yoin " +
                    "unfollows it on Spotify."
            ),
            ServiceFeature(
                "Lyrics",
                FeatureSupport.LOCAL,
                "Yoin uses additional lyric sources. Spotify's official lyrics are not available through " +
                    "this integration; matches may differ."
            ),
            ServiceFeature(
                "Ratings & notes",
                FeatureSupport.LOCAL,
                "Saved separately for each Yoin profile. Ratings do not sync to Spotify."
            ),
            ServiceFeature(
                "Audio quality",
                FeatureSupport.PARTIAL,
                "Managed in Spotify. Yoin cannot select or confirm the current stream's audio quality."
            ),
            ServiceFeature(
                "Devices & cache",
                FeatureSupport.PARTIAL,
                "Use Spotify Connect for playback targets. Yoin's Cast and audio cache do not apply to " +
                    "Spotify; manage downloads in Spotify."
            )
        )
    )

    val appleMusic = ServiceFeatures(
        id = MediaId.PROVIDER_APPLE_MUSIC,
        name = "Apple Music",
        summary = "Plays in Yoin with MusicKit · Subscription required",
        integrated = true,
        capabilities = setOf(Capability.SEARCH, Capability.PLAYLISTS_READ),
        features = listOf(
            ServiceFeature(
                "Playback",
                FeatureSupport.PARTIAL,
                "MusicKit plays catalog songs in Yoin, with background and system " +
                    "controls. Imported songs without a catalog match must be played in Apple Music."
            ),
            ServiceFeature(
                "Library & search",
                FeatureSupport.AVAILABLE,
                "Browse your library albums, artists and playlists, and search the Apple Music catalog."
            ),
            ServiceFeature(
                "Favorites & library changes",
                FeatureSupport.NOT_IMPLEMENTED,
                "Manage favorites and add or remove library items in Apple Music. " +
                    "Library membership is separate from favorites."
            ),
            ServiceFeature(
                "Playlists",
                FeatureSupport.PARTIAL,
                "Browse and play playlists. Edit them in Apple Music."
            ),
            ServiceFeature(
                "Lyrics, ratings & notes",
                FeatureSupport.LOCAL,
                "Yoin's additional lyric sources, local ratings and notes. Apple's official lyrics are not included."
            ),
            ServiceFeature(
                "Audio quality",
                FeatureSupport.UNVERIFIED,
                "MusicKit manages the stream. Yoin does not confirm lossless or spatial audio output."
            ),
            ServiceFeature(
                "Offline & devices",
                FeatureSupport.NOT_IMPLEMENTED,
                "Yoin's Cast and audio cache are not available for Apple Music."
            )
        )
    )
    val local = ServiceFeatures(
        MediaId.PROVIDER_LOCAL,
        "Local files",
        "Not in Yoin yet",
        false,
        emptySet(),
        listOf(
            ServiceFeature(
                "Local library",
                FeatureSupport.NOT_IMPLEMENTED,
                "Browsing and importing local files is not implemented in Yoin yet."
            )
        )
    )
    val entries = listOf(subsonic, spotify, appleMusic, local)

    fun forProvider(id: String?): ServiceFeatures = entries.firstOrNull { it.id == id }
        ?: ServiceFeatures(
            id.orEmpty(), "Music service", "Service capabilities have not been verified", false, emptySet(),
            listOf(
                ServiceFeature(
                    "Service features", FeatureSupport.UNVERIFIED,
                    "No verified feature information is available for this service."
                )
            )
        )
}
