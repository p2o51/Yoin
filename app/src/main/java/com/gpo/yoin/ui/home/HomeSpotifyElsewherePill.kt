package com.gpo.yoin.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gpo.yoin.R
import com.gpo.yoin.data.source.spotify.SpotifyElsewhere
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.settings.ServiceMarks
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinTheme
import kotlinx.coroutines.delay

/**
 * How long Home's header shows another Spotify account's playback (owner
 * 2026-11-10): playing or paused alike, 5 s. Left alone that long, the user
 * means to stay in the account they opened, and the pill goes.
 */
internal const val SpotifyElsewhereShowMs = 5_000L

/**
 * What [elsewhere] says while Home shows it: the pill is up only when Yoin
 * plays nothing itself and the active account is not Spotify (the peek's own
 * rule). Its 5 s start when it first can be seen; [onElapsed] then drops it.
 */
internal fun spotifyElsewhereVisible(elsewhere: SpotifyElsewhere?, yoinPlaying: Boolean): Boolean =
    elsewhere != null && !yoinPlaying

/** Drops [elsewhere] [SpotifyElsewhereShowMs] after it is first shown; a new answer starts over. */
@Composable
internal fun SpotifyElsewhereTimer(elsewhere: SpotifyElsewhere?, visible: Boolean, onElapsed: () -> Unit) {
    val elapsed by rememberUpdatedState(onElapsed)
    LaunchedEffect(elsewhere, visible) {
        if (elsewhere == null) return@LaunchedEffect
        if (!visible) {
            // Yoin started playing first: the pill has nothing to offer.
            elapsed()
            return@LaunchedEffect
        }
        delay(SpotifyElsewhereShowMs)
        elapsed()
    }
}

/**
 * Home header's pill for a Spotify account's playback while another account is
 * in use: the cover (nothing drawn on it: Spotify's artwork rule), the title,
 * and the Spotify mark with the artist. A tap switches to that account.
 * No device name (owner 2026-11-10).
 */
@Composable
internal fun HomeSpotifyElsewherePill(
    elsewhere: SpotifyElsewhere?,
    visible: Boolean,
    onClick: (SpotifyElsewhere) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The last answer stays drawn while the pill leaves.
    val shown = remember { LastNonNull<SpotifyElsewhere>() }
    elsewhere?.let { shown.value = it }
    val haptics = rememberYoinHaptics()
    AnimatedVisibility(
        visible = visible && elsewhere != null,
        modifier = modifier,
        enter = YoinMotion.fadeIn(YoinMotionRole.Expressive) +
            YoinMotion.scaleIn(YoinMotionRole.Expressive, initialScale = 0.85f, transformOrigin = PillOrigin),
        exit = YoinMotion.fadeOut(YoinMotionRole.Expressive) +
            YoinMotion.scaleOut(YoinMotionRole.Expressive, targetScale = 0.85f, transformOrigin = PillOrigin),
    ) {
        val item = shown.value ?: return@AnimatedVisibility
        val description = stringResource(R.string.home_spotify_elsewhere_cd, item.trackTitle, item.artistName)
        Row(
            modifier = Modifier
                .height(40.dp)
                .widthIn(max = 220.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(role = Role.Button) {
                    haptics.performContextClick()
                    onClick(item)
                }
                .semantics { contentDescription = description }
                .padding(start = 6.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                if (item.coverUrl != null) {
                    AsyncImage(
                        model = item.coverUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.Center) {
                Text(
                    text = item.trackTitle,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = ServiceMarks.Spotify,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        text = item.artistName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Grows from its end, where the header's avatar is. */
private val PillOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0.5f)

private class LastNonNull<T : Any> {
    var value: T? = null
}

@Preview
@Composable
private fun HomeSpotifyElsewherePillPreview() {
    YoinTheme {
        HomeSpotifyElsewherePill(
            elsewhere = SpotifyElsewhere(
                profileId = "spotify",
                trackTitle = "Pink + White",
                artistName = "Frank Ocean",
                coverUrl = null,
                isPlaying = true,
            ),
            visible = true,
            onClick = {},
        )
    }
}
