package com.gpo.yoin.ui.detail

sealed interface ArtistDetailUiState {
    data object Loading : ArtistDetailUiState

    data class Content(
        val artistName: String,
        val albumCount: Int?,
        val albums: List<ArtistAlbum>,
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
