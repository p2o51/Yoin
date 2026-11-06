package com.gpo.yoin.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.data.local.SongAboutEntry
import com.gpo.yoin.data.model.LyricCredits
import com.gpo.yoin.ui.nowplaying.AboutCarousel
import com.gpo.yoin.ui.nowplaying.AboutUiState
import com.gpo.yoin.ui.nowplaying.SpineSourceFacts
import com.gpo.yoin.ui.nowplaying.aboutCarouselPages
import com.gpo.yoin.ui.nowplaying.aboutParagraphs
import com.gpo.yoin.ui.nowplaying.aboutReceiptRows
import com.gpo.yoin.ui.nowplaying.resourceAboutLabels
import com.gpo.yoin.ui.settings.SettingsActivity
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinTheme

/**
 * Compact About used inside `NowPlayingScreen`'s middle pager (owner 2026-11-05): a carousel of the
 * fullscreen About's receipt lines (a few at a time) and its paragraphs, turning on its own; a tap
 * expands About (owner 2026-11-06). Gemini's state (loading, no key, an error) sits under it, or alone when there is nothing
 * to show yet. Editing / asking happens in the fullscreen About pane; this surface is read-only.
 */
@Composable
fun SongInfoDisplay(
    aboutUiState: AboutUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    /** Credits the lyrics source wrote into the lyrics; shown with or without Gemini. */
    credits: LyricCredits? = null,
    /** What the Subsonic server says about the song; null on other providers. */
    facts: SpineSourceFacts? = null,
    /** Starts the carousel over on a new song. */
    songKey: String = "",
) {
    val resources = LocalContext.current.resources
    val entries = (aboutUiState as? AboutUiState.Ready)?.entries.orEmpty()
    val reviewLabel = stringResource(R.string.np_about_review)
    val pages = remember(entries, credits, facts, resources, reviewLabel) {
        aboutCarouselPages(
            rows = aboutReceiptRows(entries, credits, facts, resourceAboutLabels(resources)),
            paragraphs = aboutParagraphs(entries, reviewLabel),
        )
    }
    if (pages.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
            AboutStatus(aboutUiState, onRetry)
        }
        return
    }
    Column(modifier = modifier.fillMaxSize()) {
        AboutCarousel(
            pages = pages,
            songKey = songKey,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )
        if (aboutUiState !is AboutUiState.Ready) AboutStatus(aboutUiState, onRetry)
    }
}

/** Gemini's side of the compact About: tap to load, searching, no key, or the error with Retry. */
@Composable
private fun AboutStatus(state: AboutUiState, onRetry: () -> Unit) {
    AnimatedContent(
        targetState = state,
        transitionSpec = {
            YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                YoinMotion.fadeOut(role = YoinMotionRole.Standard)
        },
        contentKey = { it::class },
        label = "songInfoStatus",
    ) { shown ->
        when (shown) {
            is AboutUiState.Idle -> TextButton(onClick = onRetry) {
                Text(stringResource(R.string.cmp_song_info_tap_load))
            }
            is AboutUiState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                YoinLoadingIndicator(size = 32.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.cmp_song_info_searching),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is AboutUiState.ApiKeyMissing -> {
                val context = LocalContext.current
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.cmp_song_info_no_key),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    TextButton(onClick = { context.startActivity(SettingsActivity.intent(context)) }) {
                        Text(stringResource(R.string.cmp_song_info_settings))
                    }
                }
            }
            is AboutUiState.Error -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = shown.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                TextButton(onClick = onRetry) { Text(stringResource(R.string.cmp_song_info_retry)) }
            }
            is AboutUiState.Ready -> Unit
        }
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
