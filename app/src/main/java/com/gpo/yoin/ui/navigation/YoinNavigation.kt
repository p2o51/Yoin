package com.gpo.yoin.ui.navigation

import kotlinx.serialization.Serializable

/**
 * Type-safe navigation routes for Yoin.
 *
 * The app now boots into a single Shell route that owns the floating button group plus the
 * Home/Library section switch. Settings and details still use pushed routes; Now Playing and
 * Memories live as shell-owned overlays instead of separate top-level destinations.
 */
@Serializable
sealed interface YoinRoute {

    @Serializable
    data object Shell : YoinRoute

    @Serializable
    data object Home : YoinRoute

    @Serializable
    data object Library : YoinRoute

    @Serializable
    data class Settings(
        /**
         * Optional section to focus on entry. Currently honoured:
         * `"spotify"` — scrolls to the Spotify client-id section and
         * focuses the text field. `null` means no special focus.
         */
        val focusSection: String? = null,
    ) : YoinRoute

    @Serializable
    data class AlbumDetail(
        val albumId: String,
        val sharedTransitionKey: String? = null,
    ) : YoinRoute

    @Serializable
    data class ArtistDetail(
        val artistId: String,
        val sharedTransitionKey: String? = null,
    ) : YoinRoute

    @Serializable
    data class PlaylistDetail(
        val playlistId: String,
        val sharedTransitionKey: String? = null,
    ) : YoinRoute
}

/** The two main content sections selectable via the Button Group. */
enum class YoinSection { HOME, LIBRARY }
