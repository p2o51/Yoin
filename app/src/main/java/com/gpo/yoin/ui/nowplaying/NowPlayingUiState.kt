package com.gpo.yoin.ui.nowplaying


import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.data.model.LibraryMembership
import com.gpo.yoin.data.source.ServiceFeatureCatalog
import com.gpo.yoin.data.source.ServiceFeatures
import com.gpo.yoin.player.PlayMode

sealed interface NowPlayingUiState {

    data object Idle : NowPlayingUiState

    /**
     * User tapped a track on a provider whose playback backend is still
     * coming up (e.g. Spotify App Remote negotiating the connection).
     * Display the target track's title/cover as "about to play" but do NOT
     * render controls as active, do NOT advance progress. A successful
     * first PlayerState transitions to [Playing]; a failure transitions to
     * [ConnectError].
     */
    data class Launching(
        val songTitle: String,
        val artist: String,
        val albumName: String,
        val coverArtUrl: String?,
        val durationMs: Long,
        val hint: String,
    ) : NowPlayingUiState

    /**
     * Backend refused / lost the connection while trying to play the
     * target track. Surface a dedicated error state so UI can render a
     * clear message instead of faking a playing-but-stuck screen.
     */
    data class ConnectError(
        val songTitle: String,
        val artist: String,
        val coverArtUrl: String?,
        val message: String,
    ) : NowPlayingUiState

    /**
     * NOTE: deliberately does NOT carry the playhead position or buffered
     * position — those tick 4×/s and would defeat data-class equality
     * dedup, recomposing the whole Now Playing tree per tick. Consumers
     * read [com.gpo.yoin.ui.nowplaying.NowPlayingViewModel.positionMs] /
     * [com.gpo.yoin.ui.nowplaying.NowPlayingViewModel.bufferedMs] instead.
     */
    data class Playing(
        val songTitle: String,
        val artist: String,
        val albumName: String,
        val coverArtUrl: String?,
        val isPlaying: Boolean,
        val durationMs: Long,
        val songId: String,
        val rating: Float,
        val isStarred: Boolean,
        val lyrics: List<LyricLine>,
        val showLyricsTranslation: Boolean,
        val lyricsActionInFlight: LyricsAction?,
        /**
         * True while the lyrics fetch is in flight (cache miss → provider
         * fallback). UI renders a loading affordance instead of the
         * "No lyrics available" empty state.
         */
        val lyricsLoading: Boolean,
        val queue: List<QueueItem>,
        val currentQueueIndex: Int,
        val playMode: PlayMode,
        val albumId: String?,
        val artistId: String?,
        val activityContext: ActivityContext,
        val serviceFeatures: ServiceFeatures = ServiceFeatureCatalog.subsonic,
        val libraryMembership: LibraryMembership = LibraryMembership.Unknown,
        val libraryActionInFlight: Boolean = false,
        /** The next song's timed lyrics, fetched ahead for the outro hand-over. */
        val upNextLyrics: UpNextLyrics? = null,
    ) : NowPlayingUiState
}

/**
 * The song that plays next, with its timed lyrics, prefetched during the
 * current song so the lyrics view can reveal it in the outro and hand over
 * without a loading state.
 */
data class UpNextLyrics(
    val songId: String,
    val title: String,
    val artist: String,
    val lines: List<LyricLine>,
    val providerName: String? = null,
    val providerSongId: String? = null,
)

data class LyricLine(
    val startMs: Long?,
    val text: String,
    val translation: String? = null,
)

enum class LyricsAction {
    Search,
    Translate,
    Apply,
}

data class LyricsSearchState(
    val isOpen: Boolean = false,
    val query: String = "",
    val loading: Boolean = false,
    val applyingCandidateKey: String? = null,
    val providers: List<LyricsSearchProviderUi> = emptyList(),
    val errorMessage: String? = null,
)

data class LyricsSearchProviderUi(
    val providerName: String,
    val results: List<LyricsSearchResultUi> = emptyList(),
    val errorMessage: String? = null,
)

data class LyricsSearchResultUi(
    val providerName: String,
    val songId: String,
    val title: String,
    val artist: String,
) {
    val stableKey: String
        get() = "$providerName:$songId"
}

data class LyricsTranslationSwitchOfferUi(
    val providerName: String,
)

data class QueueItem(
    val songId: String,
    val title: String,
    val artist: String,
    val coverArtUrl: String?,
)
