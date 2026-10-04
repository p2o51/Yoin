package com.gpo.yoin.ui.navigation.pane

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * The detail column's own back stack entries (Wide + tall windows only). The
 * same pages are Activities on narrower windows; the shell picks the host per
 * window ([com.gpo.yoin.ui.experience.hasDetailPane]), never both.
 */
@Serializable
sealed interface DetailPaneRoute : NavKey {
    /** The remote entity id this entry shows (a full `provider:raw` MediaId string). */
    val entityId: String

    @Serializable
    data class Album(val albumId: String) : DetailPaneRoute {
        override val entityId: String get() = albumId
    }

    @Serializable
    data class Artist(val artistId: String) : DetailPaneRoute {
        override val entityId: String get() = artistId
    }

    @Serializable
    data class Playlist(val playlistId: String) : DetailPaneRoute {
        override val entityId: String get() = playlistId
    }
}
