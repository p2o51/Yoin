package com.gpo.yoin.ui.component

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.theme.YoinShapeTokens
import com.gpo.yoin.ui.theme.YoinTheme

@Composable
fun AlbumCard(
    coverArtUrl: String?,
    title: String,
    subtitle: String? = null,
    metaLabel: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fixedWidth: androidx.compose.ui.unit.Dp? = 156.dp,
) {
    val interactionSource = remember { MutableInteractionSource() }

    Surface(
        onClick = onClick,
        modifier = modifier.then(
            if (fixedWidth != null) {
                Modifier.width(fixedWidth)
            } else {
                Modifier
            },
        ),
        shape = YoinShapeTokens.ExtraLarge,
        color = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        interactionSource = interactionSource,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .padding(end = 8.dp, bottom = 8.dp),
            ) {
                ExpressiveBackdropArtwork(
                    model = coverArtUrl,
                    contentDescription = title,
                    variant = ExpressiveBackdropVariant.Bun,
                    modifier = Modifier.fillMaxSize(),
                    shape = YoinShapeTokens.Small,
                    fallbackIcon = Icons.Filled.LibraryMusic,
                    interactionSource = interactionSource,
                    fillFraction = 0.82f,
                    backdropScale = 0.8f,
                    artworkShiftFraction = 0.06f,
                    offsetX = 8.dp,
                    offsetY = 10.dp,
                    tonalElevation = 0.dp,
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp)
                    .padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (!metaLabel.isNullOrBlank()) {
                    ExpressiveMetaPill(text = metaLabel)
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
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
                Spacer(modifier = Modifier.height(2.dp))
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
