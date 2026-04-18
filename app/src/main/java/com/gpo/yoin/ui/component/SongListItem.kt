package com.gpo.yoin.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.theme.YoinShapeTokens
import com.gpo.yoin.ui.theme.YoinTheme
import com.gpo.yoin.ui.theme.withTabularFigures

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongListItem(
    title: String,
    artist: String,
    album: String,
    durationSeconds: Int?,
    coverArtUrl: String?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    isNowPlaying: Boolean = false,
    playbackSignal: Float = 0f,
    extractBackdropColors: Boolean = true,
    modifier: Modifier = Modifier,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }

    // We drop Surface's `onClick = ...` overload and handle both gestures on
    // the same clickable boundary via combinedClickable. Layering a separate
    // pointerInput over an already-clickable Surface consumes events
    // inconsistently on some API levels — single-click ripples stop firing or
    // long-press accidentally propagates as a click.
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = interactionSource,
                indication = ripple(),
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        shape = YoinShapeTokens.ExtraLarge,
        color = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ExpressiveBackdropArtwork(
                model = coverArtUrl,
                contentDescription = title,
                variant = ExpressiveBackdropVariant.Circle,
                modifier = Modifier.size(54.dp),
                shape = YoinShapeTokens.Small,
                fallbackIcon = Icons.Filled.MusicNote,
                interactionSource = interactionSource,
                isPlaybackActive = isNowPlaying,
                playbackSignal = playbackSignal,
                fillFraction = 0.78f,
                backdropScale = 0.78f,
                artworkShiftFraction = 0.06f,
                tonalElevation = 0.dp,
                extractBackdropColors = extractBackdropColors,
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(artist)
                        if (album.isNotBlank()) {
                            append("  ·  ")
                            append(album)
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (durationSeconds != null) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = formatDuration(durationSeconds),
                    style = MaterialTheme.typography.labelLarge.withTabularFigures(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (trailingContent != null) {
                Spacer(modifier = Modifier.width(2.dp))
                trailingContent()
            }
        }
    }
}

private fun formatDuration(seconds: Int): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return "%d:%02d".format(mins, secs)
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun SongListItemPreview() {
    YoinTheme {
        SongListItem(
            title = "Bohemian Rhapsody",
            artist = "Queen",
            album = "A Night at the Opera",
            durationSeconds = 354,
            coverArtUrl = null,
            onClick = {},
        )
    }
}
