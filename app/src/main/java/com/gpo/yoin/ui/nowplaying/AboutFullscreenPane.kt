package com.gpo.yoin.ui.nowplaying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.yoin.data.local.SongAboutEntry
import com.gpo.yoin.ui.component.markdownBoldAnnotatedString
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.component.verticalEdgeFadeOnScroll
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole

/**
 * Fullscreen About page content. Intentionally does NOT render the Ask
 * Gemini bar — the bar is a sibling accessory in `NowPlayingScreen`
 * so it can slide with the About page and grow upward when focused.
 */
@Composable
fun AboutFullscreenPane(
    aboutUiState: AboutUiState,
    onRetryCanonical: () -> Unit,
    modifier: Modifier = Modifier,
    contentBottomPadding: androidx.compose.ui.unit.Dp = 96.dp,
) {
    AnimatedContent(
        targetState = aboutUiState,
        transitionSpec = {
            YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                YoinMotion.fadeOut(role = YoinMotionRole.Standard)
        },
        contentKey = { it::class },
        modifier = modifier,
        label = "aboutContent",
    ) { state ->
        when (state) {
            AboutUiState.Idle -> EmptyAboutHint(
                text = "Tap About to start — we'll fetch song details on first open.",
            )
            AboutUiState.Loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    YoinLoadingIndicator()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Searching for song info…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            AboutUiState.ApiKeyMissing -> EmptyAboutHint(
                text = "Configure your Gemini API key in Settings to see AI-generated song info.",
            )
            is AboutUiState.Error -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 12.dp),
            ) {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onRetryCanonical) { Text("Retry") }
            }
            is AboutUiState.Ready -> ReadyContent(
                entries = state.entries,
                bottomPadding = contentBottomPadding,
            )
        }
    }
}

@Composable
private fun EmptyAboutHint(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun ReadyContent(
    entries: List<SongAboutEntry>,
    bottomPadding: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    // Scroll-aware fade on the content itself (not the AnimatedContent
    // wrapper): releases at the end of the scroll so the last entry reads
    // crisp above the Ask Gemini bar.
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalEdgeFadeOnScroll(scrollState, bottom = 64.dp)
            .verticalScroll(scrollState),
    ) {
        val byKey = entries.filter { it.kind == SongAboutEntry.KIND_CANONICAL }
            .associateBy { it.entryKey }

        SongAboutEntry.CANONICAL_ORDER
            .mapNotNull { key -> byKey[key]?.let { key to it } }
            .filter { (_, row) -> row.answerText.isNotBlank() }
            .forEach { (_, row) ->
                InfoItem(label = labelFor(row.entryKey), value = row.answerText)
            }

        val asks = entries.filter { it.kind == SongAboutEntry.KIND_ASK }
        if (asks.isNotEmpty()) {
            Spacer(modifier = Modifier.height(20.dp))
            asks.forEach { row ->
                // Heading is Gemini's short title (v16+). Rows written
                // before the titleText column existed fall back to the
                // user's original question so history stays readable.
                val heading = row.titleText?.takeIf { it.isNotBlank() }
                    ?: row.promptText.orEmpty()
                InfoItem(label = heading, value = row.answerText)
            }
        }
        Spacer(modifier = Modifier.height(bottomPadding))
    }
}

private fun labelFor(entryKey: String): String = when (entryKey) {
    SongAboutEntry.CANON_CREATION_TIME -> "Creation Time"
    SongAboutEntry.CANON_CREATION_LOCATION -> "Creation Location"
    SongAboutEntry.CANON_LYRICIST -> "Lyricist"
    SongAboutEntry.CANON_COMPOSER -> "Composer"
    SongAboutEntry.CANON_PRODUCER -> "Producer"
    SongAboutEntry.CANON_REVIEW -> "About"
    else -> entryKey
}

@Composable
private fun InfoItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = markdownBoldAnnotatedString(value),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
