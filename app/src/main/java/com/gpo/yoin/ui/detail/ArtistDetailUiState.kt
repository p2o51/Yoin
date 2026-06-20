package com.gpo.yoin.ui.detail

sealed interface ArtistDetailUiState {
    data object Loading : ArtistDetailUiState

    data class Content(
        val artistId: String,
        val artistName: String,
        val albumCount: Int?,
        /**
         * Resolved URL for the artist's own portrait (from the provider's
         * artist endpoint — Spotify always returns one, Subsonic/Navidrome only
         * if the server has `artist.jpg`). `null` when the provider doesn't
         * supply one; callers fall back to the first album cover so the hero
         * never goes blank.
         */
        val heroCoverArtUrl: String?,
        val albums: List<ArtistAlbum>,
        /** Whether the user follows / has starred this artist (the Follow toggle). */
        val isStarred: Boolean = false,
    ) : ArtistDetailUiState

    data class Error(val message: String) : ArtistDetailUiState
}

data class ArtistAlbum(
    val id: String,
    val name: String,
    val coverArtUrl: String?,
    val year: Int?,
    val songCount: Int?,
)
