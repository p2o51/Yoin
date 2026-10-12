package com.gpo.yoin.ui.detail

import com.gpo.yoin.data.model.ReleaseType
import com.gpo.yoin.ui.common.UiText

sealed interface ArtistDetailUiState {
    data object Loading : ArtistDetailUiState

    data class Content(
        val artistId: String,
        val artistName: String,
        /**
         * Resolved URL for the artist's own portrait (from the provider's
         * artist endpoint — Spotify always returns one, Subsonic/Navidrome only
         * if the server has `artist.jpg`). `null` when the provider doesn't
         * supply one; callers fall back to the first album cover so the hero
         * never goes blank.
         */
        val heroCoverArtUrl: String?,
        /** The artist's own releases, newest first. */
        val albums: List<ArtistAlbum>,
        /** Whether the user follows / has starred this artist (the header star). */
        val isStarred: Boolean = false,
        /**
         * This profile's own listening, from local play history (every
         * provider). Loaded after the page paints; null until then, and
         * Most Played grows in once it lands.
         */
        val listening: ArtistListeningSummary? = null,
    ) : ArtistDetailUiState

    data class Error(val message: UiText) : ArtistDetailUiState
}

data class ArtistAlbum(
    val id: String,
    val name: String,
    val coverArtUrl: String?,
    val year: Int?,
    val songCount: Int?,
    /** Provider-reported kind; null = unknown (never guessed from the track count). */
    val releaseType: ReleaseType? = null,
)

data class ArtistListeningSummary(
    val playCount: Int,
    val mostPlayed: List<ArtistPlayedSong>,
)

/** One row of the artist's "Most Played" — the user's own play counts. */
data class ArtistPlayedSong(
    val id: String,
    val title: String,
    val album: String,
    val coverArtUrl: String?,
    val durationSec: Int?,
    val playCount: Int,
)
