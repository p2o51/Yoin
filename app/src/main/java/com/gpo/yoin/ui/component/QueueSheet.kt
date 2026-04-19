package com.gpo.yoin.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gpo.yoin.ui.nowplaying.QueueItem
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinShapeTokens
import com.gpo.yoin.ui.theme.YoinTheme

/**
 * Bottom sheet displaying the play queue.
 *
 * @param queue list of songs in the queue
 * @param currentIndex index of the currently playing track
 * @param onItemClick called when user taps a queue item (skip to that track)
 * @param onDismiss called when the sheet is dismissed
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    queue: List<QueueItem>,
    currentIndex: Int,
    onItemClick: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    // Convention for every ModalBottomSheet in this codebase: NO outer
    // bottom padding on the wrapping Column, NO `heightIn` cap on the
    // LazyColumn. Material3's ModalBottomSheet limits its own height,
    // and a fixed cap creates a visible empty band once content reaches
    // it. Any breathing room belongs *inside* the LazyColumn's
    // `contentPadding` so it collapses naturally on short lists and
    // becomes part of the scrollable area on long ones.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier,
    ) {
        Column {
            Text(
                text = "Queue",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )

            val listState = rememberLazyListState()
            LaunchedEffect(currentIndex) {
                if (currentIndex >= 0) {
                    listState.animateScrollToItem(currentIndex)
                }
            }

            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                itemsIndexed(queue) { index, item ->
                    QueueListItem(
                        item = item,
                        isCurrent = index == currentIndex,
                        onClick = { onItemClick(index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueListItem(
    item: QueueItem,
    isCurrent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isCurrent) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        animationSpec = YoinMotion.effectsSpring(),
        label = "queueItemBg",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Thumbnail
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(YoinShapeTokens.Small)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = item.coverArtUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(40.dp),
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = item.title,
                style = if (isCurrent) {
                    MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                    )
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                color = if (isCurrent) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ── Previews ────────────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun QueueListItemPreview() {
    YoinTheme {
        Column {
            QueueListItem(
                item = QueueItem(
                    songId = "1",
                    title = "Starlight",
                    artist = "Muse",
                    coverArtUrl = null,
                ),
                isCurrent = true,
                onClick = {},
            )
            QueueListItem(
                item = QueueItem(
                    songId = "2",
                    title = "Supermassive Black Hole",
                    artist = "Muse",
                    coverArtUrl = null,
                ),
                isCurrent = false,
                onClick = {},
            )
        }
    }
}
