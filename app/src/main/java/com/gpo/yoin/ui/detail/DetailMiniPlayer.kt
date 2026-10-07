package com.gpo.yoin.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import com.gpo.yoin.AppContainer
import com.gpo.yoin.player.PlaybackState
import com.gpo.yoin.ui.experience.LocalWindowCovered
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * Playback projections for the detail Activities' bottom-bar now-playing
 * pill — the shell's Now Playing overlay can't reach these standalone
 * windows, so the bar mirrors the shell's pill from the same PlaybackManager.
 */
data class DetailMiniPlayerState(
    val trackId: String?,
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
        trackId = track.id.toString(),
        title = track.title.orEmpty(),
        artist = track.artist.orEmpty(),
        // EXACTLY the shell pill's URL (YoinNavHost: no size). This pill is
        // the pixel twin drawn over the shell's during the hand-off; a sized
        // URL was a different cache key, so every detail window re-downloaded
        // the thumbnail and the pill's cover blinked out and faded back in
        // mid hand-off. Same URL = memory hit = painted on the first frame.
        coverArtUrl = container.repository.resolveCoverUrl(track.coverArt),
        isPlaying = isPlaying,
    )
}

/**
 * The exact same fraction as the shell's pill. Quantizing only the detail
 * copy shifts its wave front at the cross-window return handoff.
 */
@Composable
fun rememberDetailMiniPlayerProgress(container: AppContainer): State<Float> {
    // Seeded from the live state: a 0% first frame reads as a wave blip.
    val seed = remember(container) {
        container.playbackManager.playbackState.value.toPlaybackProgress()
    }
    // Held while another detail window covers this one (no one sees the
    // pill): the 4 Hz tick would redraw the frozen window, stalling the main
    // thread the visible window shares. Catches up on the first uncovered frame.
    val covered = LocalWindowCovered.current
    return remember(container, covered) {
        container.playbackManager.playbackState
            .map { state -> state.toPlaybackProgress() }
            .distinctUntilChanged()
            .combine(snapshotFlow { covered.value }) { progress, isCovered -> progress to isCovered }
            .filter { (_, isCovered) -> !isCovered }
            .map { (progress, _) -> progress }
            .distinctUntilChanged()
    }.collectAsState(initial = seed)
}

/**
 * Whether playback is playing, for a detail Activity's root — which reads nothing else of the playback state, so
 * it no longer recomposes on every 4 Hz position tick. Seeded from the live value: a `false` first frame would
 * start the page background in its paused branch.
 */
@Composable
fun rememberDetailIsPlaying(container: AppContainer): State<Boolean> {
    val seed = remember(container) { container.playbackManager.playbackState.value.isPlaying }
    return remember(container) {
        container.playbackManager.playbackState
            .map { state -> state.isPlaying }
            .distinctUntilChanged()
    }.collectAsState(initial = seed)
}

private fun PlaybackState.toPlaybackProgress(): Float =
    if (duration <= 0L) 0f
    else (position.toFloat() / duration).coerceIn(0f, 1f)
