package com.gpo.yoin.ui.detail

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.yoin.AppContainer
import com.gpo.yoin.MainActivity
import com.gpo.yoin.ui.component.ExpressiveMediaArtwork
import com.gpo.yoin.ui.component.MarqueeText
import com.gpo.yoin.ui.component.noRippleClickable
import com.gpo.yoin.ui.component.rememberExpressiveBackdropColors
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinShapeTokens
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The Figma mini-player (node 406:1072) for the detail Activities — the
 * shell's Now Playing overlay can't reach these standalone Activities, so
 * browsing an album/artist/playlist while music played used to mean flying
 * blind. Three segments on one palette-tinted bar: a Home square (back to
 * the shell), the now-playing chip (cover + title/artist; tap = open the
 * shell WITH Now Playing already expanded), and a play/pause square.
 */
data class DetailMiniPlayerState(
    val title: String,
    val artist: String,
    val coverArtUrl: String?,
    val isPlaying: Boolean,
)

/**
 * Narrow projection of the playback state for the mini player. Deliberately
 * NOT the raw [com.gpo.yoin.player.PlaybackState]: that carries per-tick
 * position fields, and collecting it directly would recompose the bar every
 * playback tick (the project's NP-dedup invariant). distinctUntilChanged on
 * this tiny snapshot means the bar recomposes only on track / play changes.
 */
@Composable
fun rememberDetailMiniPlayerState(container: AppContainer): State<DetailMiniPlayerState?> =
    remember(container) {
        container.playbackManager.playbackState
            .map { state ->
                val track = state.currentTrack ?: state.pendingTrack
                track?.let {
                    DetailMiniPlayerState(
                        title = it.title.orEmpty(),
                        artist = it.artist.orEmpty(),
                        coverArtUrl = container.repository.resolveCoverUrl(it.coverArt, size = 240),
                        isPlaying = state.isPlaying,
                    )
                }
            }
            .distinctUntilChanged()
    }.collectAsState(initial = null)

/**
 * Return to the shell Activity, optionally with Now Playing expanded. The
 * session store is process-global, so setting the flag BEFORE the intent
 * means the shell resumes already showing NP — no extras round-trip.
 * CLEAR_TOP + SINGLE_TOP folds the detail stack back into the existing
 * shell instance instead of spawning a second one.
 */
fun launchShellFromDetail(
    context: Context,
    container: AppContainer,
    expandNowPlaying: Boolean,
) {
    if (expandNowPlaying) {
        container.experienceSessionStore.setNowPlayingExpanded(true)
    }
    context.startActivity(
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
    )
}

@Composable
fun DetailMiniPlayer(
    state: DetailMiniPlayerState?,
    onHome: () -> Unit,
    onOpenNowPlaying: () -> Unit,
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keep the last non-null state so the exit slide animates with content.
    var lastState by remember { mutableStateOf(state) }
    if (state != null) lastState = state
    AnimatedVisibility(
        visible = state != null,
        enter = YoinMotion.slideInVertically(role = YoinMotionRole.Expressive) { it } +
            YoinMotion.fadeIn(role = YoinMotionRole.Expressive),
        exit = YoinMotion.slideOutVertically(role = YoinMotionRole.Expressive) { it } +
            YoinMotion.fadeOut(role = YoinMotionRole.Expressive),
        modifier = modifier,
    ) {
        lastState?.let { current ->
            DetailMiniPlayerBar(
                state = current,
                onHome = onHome,
                onOpenNowPlaying = onOpenNowPlaying,
                onTogglePlay = onTogglePlay,
            )
        }
    }
}

@Composable
private fun DetailMiniPlayerBar(
    state: DetailMiniPlayerState,
    onHome: () -> Unit,
    onOpenNowPlaying: () -> Unit,
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberYoinHaptics()
    val backdrop = rememberExpressiveBackdropColors(
        model = state.coverArtUrl,
        fallbackBaseColor = MaterialTheme.colorScheme.secondaryContainer,
        fallbackAccentColor = MaterialTheme.colorScheme.tertiaryContainer,
    )
    // Figma two-tone: the outer bar in a light wash of the cover palette,
    // the now-playing chip a step deeper — same recipe as the home bento.
    val barColor = lerp(
        MaterialTheme.colorScheme.surfaceContainerHigh,
        backdrop.baseColor,
        0.28f,
    )
    val chipColor = lerp(
        MaterialTheme.colorScheme.surfaceContainerHigh,
        backdrop.baseColor,
        0.5f,
    )
    val contentColor = MaterialTheme.colorScheme.onSurface
    val chipInteraction = remember { MutableInteractionSource() }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = YoinShapeTokens.Full,
        color = barColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiniPlayerSquareButton(
                icon = Icons.Rounded.Home,
                contentDescription = "Home",
                tint = contentColor,
                containerColor = chipColor,
                onClick = {
                    haptics.performContextClick()
                    onHome()
                },
            )
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .noRippleClickable(interactionSource = chipInteraction) {
                        haptics.performContextClick()
                        onOpenNowPlaying()
                    },
                shape = YoinShapeTokens.Full,
                color = chipColor,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ExpressiveMediaArtwork(
                        model = state.coverArtUrl,
                        contentDescription = state.title,
                        modifier = Modifier.size(40.dp),
                        shape = YoinShapeTokens.Small,
                        fallbackIcon = Icons.Rounded.PlayArrow,
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        MarqueeText(
                            text = state.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = contentColor,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (state.artist.isNotBlank()) {
                            Text(
                                text = state.artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = contentColor.copy(alpha = 0.75f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            MiniPlayerSquareButton(
                icon = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (state.isPlaying) "Pause" else "Play",
                tint = contentColor,
                containerColor = chipColor,
                onClick = {
                    haptics.performContextClick()
                    onTogglePlay()
                },
            )
        }
    }
}

@Composable
private fun MiniPlayerSquareButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: androidx.compose.ui.graphics.Color,
    containerColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        modifier = Modifier
            .size(52.dp)
            .noRippleClickable(interactionSource = interactionSource, onClick = onClick),
        shape = YoinShapeTokens.Large,
        color = containerColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        androidx.compose.foundation.layout.Box(
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
