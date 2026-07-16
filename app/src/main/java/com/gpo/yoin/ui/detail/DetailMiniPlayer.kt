package com.gpo.yoin.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import com.gpo.yoin.AppContainer
import com.gpo.yoin.player.PlaybackState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Playback projections for the detail Activities' bottom-bar now-playing
 * pill — the shell's Now Playing overlay can't reach these standalone
 * windows, so the bar mirrors the shell's pill from the same PlaybackManager.
 */
data class DetailMiniPlayerState(
    val title: String,
    val artist: String,
    val coverArtUrl: String?,
    val isPlaying: Boolean,
)

/**
 * Narrow projection of the playback state for the bar pill. Deliberately
 * NOT the raw [PlaybackState]: that carries per-tick position fields, and
 * collecting it directly would recompose the bar every playback tick (the
 * project's NP-dedup invariant). distinctUntilChanged on this tiny snapshot
 * means it recomposes only on track / play changes.
 *
 * Seeded synchronously from the StateFlow's CURRENT value (same pattern as
 * the shell's playbackProgress) so "music already playing" is visible on the
 * window's first frame — the hand-off crossfade must land on a pill that
 * already matches the shell's.
 */
@Composable
fun rememberDetailMiniPlayerState(container: AppContainer): State<DetailMiniPlayerState?> {
    val seed = remember(container) {
        container.playbackManager.playbackState.value.toDetailMiniPlayerState(container)
    }
    return remember(container) {
        container.playbackManager.playbackState
            .map { state -> state.toDetailMiniPlayerState(container) }
            .distinctUntilChanged()
    }.collectAsState(initial = seed)
}

private fun PlaybackState.toDetailMiniPlayerState(
    container: AppContainer,
): DetailMiniPlayerState? {
    val track = currentTrack ?: pendingTrack ?: return null
    return DetailMiniPlayerState(
        title = track.title.orEmpty(),
        artist = track.artist.orEmpty(),
        coverArtUrl = container.repository.resolveCoverUrl(track.coverArt, size = 240),
        isPlaying = isPlaying,
    )
}

/**
 * Track progress fraction for the pill's wave fill. Quantized so the 250ms
 * position ticker only emits when the wave front would visibly move (~1px).
 */
@Composable
fun rememberDetailMiniPlayerProgress(container: AppContainer): State<Float> {
    // Seeded from the live state: a 0% first frame reads as a wave blip.
    val seed = remember(container) {
        container.playbackManager.playbackState.value.toQuantizedProgress()
    }
    return remember(container) {
        container.playbackManager.playbackState
            .map { state -> state.toQuantizedProgress() }
            .distinctUntilChanged()
    }.collectAsState(initial = seed)
}

private fun PlaybackState.toQuantizedProgress(): Float =
    if (duration <= 0L) 0f
    else ((position.toFloat() / duration) * 480f).toInt() / 480f
