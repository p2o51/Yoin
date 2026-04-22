package com.gpo.yoin.ui.detail

sealed interface AlbumDetailUiState {
    data object Loading : AlbumDetailUiState

    data class Content(
        val albumId: String,
        val albumName: String,
        val artistName: String,
        val artistId: String?,
        val coverArtId: String?,
        val coverArtUrl: String?,
        val year: Int?,
        val songCount: Int?,
        val totalDuration: Int?,
        val songs: List<AlbumSong>,
        /**
         * Yoin 本地的专辑评分 0.0–10.0（整数步进，slider 步长 = 1）。
         * null 表示还没评过 —— UI 区分「未评」和「评了 0 分」。
         */
        val userRating: Float? = null,
        /** 用户自写长评；推 NeoDB Review.body 用的就是这个字段。 */
        val userReview: String = "",
        /** review 脏位：编辑后 vs Room 持久化的内容不一致。 */
        val reviewHasUnsavedEdits: Boolean = false,
    ) : AlbumDetailUiState

    data class Error(val message: String) : AlbumDetailUiState
}

data class AlbumSong(
    val id: String,
    val title: String,
    val artist: String,
    val trackNumber: Int?,
    val duration: Int?,
    val isStarred: Boolean,
)
