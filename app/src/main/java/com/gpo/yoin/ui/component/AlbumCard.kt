package com.gpo.yoin.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.theme.YoinArtworkShapes
import com.gpo.yoin.ui.theme.YoinTheme

@Composable
fun AlbumCard(
    coverArtUrl: String?,
    title: String,
    subtitle: String? = null,
    metaLabel: String? = null,
    onClick: () -> Unit,
    extractBackdropColors: Boolean = true,
    modifier: Modifier = Modifier,
    fixedWidth: androidx.compose.ui.unit.Dp? = 156.dp,
    showIndication: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }

    // Surface intentionally has no `shape` (defaults to RectangleShape).
    // A rounded outer shape used to clip the inner Column, which chewed
    // into the metaPill / title / subtitle at the corners — most visible
    // as the "left-bottom corner being cut off" in the Library albums
    // grid and ArtistDetail. The cover artwork is clipped to its own
    // shape internally by `ExpressiveBackdropArtwork`, so the outer
    // container doesn't need to clip anything.
    Surface(
        modifier = modifier.then(
            if (fixedWidth != null) {
                Modifier.width(fixedWidth)
            } else {
                Modifier
            },
        ),
        color = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        // Spacing rhythm: full-bleed cover (the sub-1f fill fractions and the
        // end/bottom inset were placeholders for the removed animated backdrop
        // shape — pure ghost margins now), one deliberate 5dp gap to the text,
        // and the title + subtitle flush so they read as a single cluster. No
        // reserved min-height: a one-line card ends where its text ends.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = interactionSource,
                    indication = if (showIndication) ripple() else null,
                    role = Role.Button,
                    onClick = onClick,
                ),
        ) {
            ExpressiveBackdropArtwork(
                model = coverArtUrl,
                contentDescription = title,
                variant = ExpressiveBackdropVariant.Bun,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                shape = YoinArtworkShapes.Cover,
                fallbackIcon = Icons.Filled.LibraryMusic,
                interactionSource = interactionSource,
                fillFraction = 1f,
                tonalElevation = 0.dp,
                extractBackdropColors = extractBackdropColors,
            )
            Spacer(modifier = Modifier.height(5.dp))
            if (!metaLabel.isNullOrBlank()) {
                ExpressiveMetaPill(text = metaLabel)
                Spacer(modifier = Modifier.height(3.dp))
            }
            // One line only — overflow scrolls (marquee) instead of wrapping,
            // so long titles never make one card taller than its row.
            MarqueeText(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun AlbumCardPreview() {
    YoinTheme {
        AlbumCard(
            coverArtUrl = null,
            title = "Random Access Memories",
            subtitle = "Daft Punk",
            metaLabel = "2013",
            onClick = {},
            fixedWidth = null,
        )
    }
}
