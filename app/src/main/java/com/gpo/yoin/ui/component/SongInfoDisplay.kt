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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.nowplaying.SongInfoUiState
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinTheme

@Composable
fun SongInfoDisplay(
    songInfoState: SongInfoUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = songInfoState,
        transitionSpec = {
            YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                YoinMotion.fadeOut(role = YoinMotionRole.Standard)
        },
        contentKey = { it::class },
        modifier = modifier,
        label = "songInfoContent",
    ) { state ->
        when (state) {
            is SongInfoUiState.Idle -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = "Swipe here to load song info",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            is SongInfoUiState.Loading -> {
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

            is SongInfoUiState.ApiKeyMissing -> {
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

            is SongInfoUiState.Error -> {
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

            is SongInfoUiState.Success -> {
                SuccessContent(state = state)
            }
        }
    }
}

@Composable
private fun SuccessContent(
    state: SongInfoUiState.Success,
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
            val fields = buildList {
                state.creationTime?.let { add("Created" to it) }
                state.creationLocation?.let { add("Location" to it) }
                state.lyricist?.let { add("Lyricist" to it) }
                state.composer?.let { add("Composer" to it) }
                state.producer?.let { add("Producer" to it) }
            }

            fields.forEach { (label, value) ->
                InfoItem(label = label, value = value)
            }

            if (state.review != null) {
                if (fields.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                }
                Text(
                    text = "About",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
                Text(
                    text = state.review,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            // Extra space so bottom fade doesn't clip content
            Spacer(modifier = Modifier.height(56.dp))
        }
    }
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
            songInfoState = SongInfoUiState.Loading,
            onRetry = {},
            modifier = Modifier.heightIn(min = 160.dp),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun SongInfoDisplaySuccessPreview() {
    YoinTheme {
        SongInfoDisplay(
            songInfoState = SongInfoUiState.Success(
                creationTime = "2006",
                creationLocation = "London, UK",
                lyricist = "Matt Bellamy",
                composer = "Matt Bellamy",
                producer = "Rich Costey",
                review = "A soaring anthem that blends stadium rock grandeur with intimate longing, capturing the duality of distance and desire through its celestial imagery.",
            ),
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
            songInfoState = SongInfoUiState.ApiKeyMissing,
            onRetry = {},
            modifier = Modifier.heightIn(min = 160.dp),
        )
    }
}
