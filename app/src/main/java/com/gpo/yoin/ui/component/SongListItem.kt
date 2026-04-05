package com.gpo.yoin.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gpo.yoin.R
import com.gpo.yoin.ui.theme.YoinTheme

@Composable
fun SongListItem(
    title: String,
    artist: String,
    album: String,
    durationSeconds: Int?,
    coverArtUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = coverArtUrl,
            contentDescription = title,
            modifier = Modifier
                .size(48.dp)
                .clip(MaterialTheme.shapes.small),
            contentScale = ContentScale.Crop,
            error = painterResource(R.drawable.ic_launcher_foreground),
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(artist)
                    if (album.isNotBlank()) {
                        append(" — ")
                        append(album)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (durationSeconds != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = formatDuration(durationSeconds),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun SongListItemNoDurationPreview() {
    YoinTheme {
        SongListItem(
            title = "A Very Long Song Title That Should Be Truncated",
            artist = "Artist Name",
            album = "",
            durationSeconds = null,
            coverArtUrl = null,
            onClick = {},
        )
    }
}
