package com.gpo.yoin.ui.detail

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import com.gpo.yoin.AppContainer
import com.gpo.yoin.MainActivity
import com.gpo.yoin.R
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

/**
 * Return to the shell Activity, optionally with Now Playing expanded.
 *
 * The expand request travels as an Intent extra consumed in the shell's
 * onNewIntent — NOT by setting the session-store flag up front. Compose's
 * frame clock is process-wide, so a flag set while the shell is stopped lets
 * the whole NP enter transition play invisibly in the background and the
 * shell resumes at the finished state. Deferring to onNewIntent (which fires
 * just before onStart) means the expansion animates on the shell's first
 * visible frames — the same bar→NP choreography as tapping the bottom group —
 * while this detail window dissolves above it (np_handoff_* animations).
 * CLEAR_TOP + SINGLE_TOP folds the detail stack back into the existing
 * shell instance instead of spawning a second one.
 */
fun launchShellFromDetail(
    context: Context,
    container: AppContainer,
    expandNowPlaying: Boolean,
) {
    val intent = Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    if (!expandNowPlaying) {
        context.startActivity(intent)
        return
    }
    intent.putExtra(MainActivity.EXTRA_EXPAND_NOW_PLAYING, true)
    val options = ActivityOptions.makeCustomAnimation(
        context,
        R.anim.np_handoff_enter,
        R.anim.np_handoff_exit,
    )
    context.startActivity(intent, options.toBundle())
}
