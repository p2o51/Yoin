package com.gpo.yoin.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.data.local.SongAboutEntry
import com.gpo.yoin.ui.nowplaying.AboutUiState
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinTheme

/**
 * Compact About preview used inside `NowPlayingScreen`'s middle pager.
 * Renders canonical rows in fixed [SongAboutEntry.CANONICAL_ORDER] plus a
 * teaser of the most recent Ask Gemini answer. Editing / asking happens in
 * the fullscreen About pane; this surface is intentionally read-only.
 */
@Composable
fun SongInfoDisplay(
    aboutUiState: AboutUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = aboutUiState,
        transitionSpec = {
            YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                YoinMotion.fadeOut(role = YoinMotionRole.Standard)
        },
        contentKey = { it::class },
        modifier = modifier,
        label = "songInfoContent",
    ) { state ->
        when (state) {
            is AboutUiState.Idle -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = "Tap to load song info",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            is AboutUiState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Column {
                        YoinLoadingIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Searching for song info…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            is AboutUiState.ApiKeyMissing -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = "Configure your Gemini API key in Settings to see AI-generated song info.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            is AboutUiState.Error -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Column {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        TextButton(onClick = onRetry) {
                            Text("Retry")
                        }
                    }
                }
            }

            is AboutUiState.Ready -> {
                ReadyContent(entries = state.entries)
            }
        }
    }
}

@Composable
private fun ReadyContent(
    entries: List<SongAboutEntry>,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val fadeHeight = 48.dp.toPx()
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Black, Color.Transparent),
                        startY = size.height - fadeHeight,
                        endY = size.height,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            val byKey = entries.filter { it.kind == SongAboutEntry.KIND_CANONICAL }
                .associateBy { it.entryKey }

            SongAboutEntry.CANONICAL_ORDER
                .mapNotNull { key -> byKey[key]?.let { key to it } }
                .forEach { (key, row) ->
                    if (key == SongAboutEntry.CANON_REVIEW) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "About",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                        Text(
                            text = row.answerText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    } else {
                        InfoItem(label = labelFor(key), value = row.answerText)
                    }
                }

            // Preview teaser: just the most recent ask (DAO order = updatedAt desc).
            val latestAsk = entries.firstOrNull { it.kind == SongAboutEntry.KIND_ASK }
            if (latestAsk != null) {
                Spacer(modifier = Modifier.height(12.dp))
                val heading = latestAsk.titleText?.takeIf { it.isNotBlank() }
                    ?: latestAsk.promptText.orEmpty()
                Text(
                    text = heading,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = latestAsk.answerText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            // Extra space so bottom fade doesn't clip content
            Spacer(modifier = Modifier.height(56.dp))
        }
    }
}

private fun labelFor(entryKey: String): String = when (entryKey) {
    SongAboutEntry.CANON_CREATION_TIME -> "Created"
    SongAboutEntry.CANON_CREATION_LOCATION -> "Location"
    SongAboutEntry.CANON_LYRICIST -> "Lyricist"
    SongAboutEntry.CANON_COMPOSER -> "Composer"
    SongAboutEntry.CANON_PRODUCER -> "Producer"
    SongAboutEntry.CANON_REVIEW -> "About"
    else -> entryKey
}

@Composable
private fun InfoItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ── Previews ────────────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun SongInfoDisplayLoadingPreview() {
    YoinTheme {
        SongInfoDisplay(
            aboutUiState = AboutUiState.Loading,
            onRetry = {},
            modifier = Modifier.heightIn(min = 160.dp),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun SongInfoDisplayReadyPreview() {
    YoinTheme {
        val now = 1_700_000_000L
        val previewEntries = listOf(
            SongAboutEntry(
                titleKey = "starlight",
                artistKey = "muse",
                albumKey = "black holes and revelations",
                titleDisplay = "Starlight",
                artistDisplay = "Muse",
                albumDisplay = "Black Holes and Revelations",
                kind = SongAboutEntry.KIND_CANONICAL,
                entryKey = SongAboutEntry.CANON_CREATION_TIME,
                promptText = null,
                titleText = null,
                answerText = "2006",
                createdAt = now,
                updatedAt = now,
            ),
            SongAboutEntry(
                titleKey = "starlight",
                artistKey = "muse",
                albumKey = "black holes and revelations",
                titleDisplay = "Starlight",
                artistDisplay = "Muse",
                albumDisplay = "Black Holes and Revelations",
                kind = SongAboutEntry.KIND_CANONICAL,
                entryKey = SongAboutEntry.CANON_LYRICIST,
                promptText = null,
                titleText = null,
                answerText = "Matt Bellamy",
                createdAt = now,
                updatedAt = now,
            ),
            SongAboutEntry(
                titleKey = "starlight",
                artistKey = "muse",
                albumKey = "black holes and revelations",
                titleDisplay = "Starlight",
                artistDisplay = "Muse",
                albumDisplay = "Black Holes and Revelations",
                kind = SongAboutEntry.KIND_CANONICAL,
                entryKey = SongAboutEntry.CANON_REVIEW,
                promptText = null,
                titleText = null,
                answerText = "A soaring anthem blending stadium rock grandeur with intimate longing.",
                createdAt = now,
                updatedAt = now,
            ),
        )
        SongInfoDisplay(
            aboutUiState = AboutUiState.Ready(previewEntries),
            onRetry = {},
            modifier = Modifier.heightIn(min = 160.dp),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun SongInfoDisplayApiKeyMissingPreview() {
    YoinTheme {
        SongInfoDisplay(
            aboutUiState = AboutUiState.ApiKeyMissing,
            onRetry = {},
            modifier = Modifier.heightIn(min = 160.dp),
        )
    }
}
