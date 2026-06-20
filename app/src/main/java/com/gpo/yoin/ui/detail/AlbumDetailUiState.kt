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
         * null 表示用户还没手动评过这张专辑 —— UI 区分「未评」「评了 0 分」。
         * 优先级：用户手动评分 > 单曲均分 > N/A（见 [AlbumDetailUiState.Content.albumScore]）。
         */
        val userRating: Float? = null,
        /**
         * 已评分单曲的均分（0–10），仅当用户没有手动给专辑评分、但给至少一首
         * 单曲评过分时用于「Avg.」Bun 显示。null = 没有任何单曲被评分。
         */
        val averageTrackRating: Float? = null,
        /** 被用户评过分的单曲数量，用于「Based on X/N」副标。 */
        val ratedTrackCount: Int = 0,
        /**
         * 专辑级「上次播放」时间戳（epoch millis）。没有持久化字段，由
         * play_history 聚合（各单曲最近一次播放取 MAX）。null = 无记录。
         */
        val lastPlayedAt: Long? = null,
        /** 用户自写长评（= 专辑 Comment）；推 NeoDB Review.body 用的就是这个字段。 */
        val userReview: String = "",
        /** review 脏位：编辑后 vs Room 持久化的内容不一致。 */
        val reviewHasUnsavedEdits: Boolean = false,
    ) : AlbumDetailUiState {
        /**
         * 「Based on X/N」的分母 N。用实际加载到的 songs.size，而不是 provider
         * 报的 songCount —— 分子 ratedTrackCount 是在已加载曲目上数的，若 provider
         * 的 songCount 比实际加载多（分页/区域限制），N 会比分子的全集还大、显示
         * 误导。songs 为空时才回退 songCount。
         */
        val trackTotal: Int get() = if (songs.isNotEmpty()) songs.size else (songCount ?: 0)
    }

    data class Error(val message: String) : AlbumDetailUiState
}

/** Bun 评分卡要显示的三态。 */
enum class AlbumScoreKind { UserRating, Average, None }

data class AlbumScore(
    val kind: AlbumScoreKind,
    /** 0–10，仅在 [kind] != None 时有意义。 */
    val value: Float,
)

/**
 * 决定「Avg.」Bun 显示什么（产品规则）：
 *  1. 用户手动给专辑评了分 → 永远显示手动分，且不显示「Based on …」。
 *  2. 没手动评、但评过任意单曲 → 显示单曲均分 + 「Based on X/N」。
 *  3. 一首都没评 → N/A。
 */
fun AlbumDetailUiState.Content.albumScore(): AlbumScore = when {
    userRating != null && userRating > 0f -> AlbumScore(AlbumScoreKind.UserRating, userRating)
    averageTrackRating != null && ratedTrackCount > 0 ->
        AlbumScore(AlbumScoreKind.Average, averageTrackRating)
    else -> AlbumScore(AlbumScoreKind.None, 0f)
}

data class AlbumSong(
    val id: String,
    val title: String,
    val artist: String,
    val trackNumber: Int?,
    val duration: Int?,
    val isStarred: Boolean,
    /**
     * 单曲主唱与专辑歌手不一致时的 feat. 名字（取单曲主唱）；一致或缺失为 null。
     * 注意 Spotify 多歌手在 mapper 处已被收敛成首位主唱，这里只是个粗略信号。
     */
    val featArtist: String? = null,
)
