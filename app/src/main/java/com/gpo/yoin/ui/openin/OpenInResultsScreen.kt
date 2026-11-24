package com.gpo.yoin.ui.openin

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gpo.yoin.R
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.SongListItem
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.component.handPointer
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.settings.SettingsBackButton
import com.gpo.yoin.ui.settings.SettingsHeadline
import com.gpo.yoin.ui.theme.YoinArtworkShapes
import com.gpo.yoin.ui.theme.YoinContainerShapes
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinShapeTokens
import com.gpo.yoin.ui.theme.YoinTheme

/**
 * Choose a match: the account in use's closest results for a link from another service, best first. The
 * description says the match couldn't be confirmed, except when the best row is the same item by the
 * playlist-transfer rules (then the list just leads with it).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenInResultsScreen(
    state: OpenInResultsState,
    onBackClick: () -> Unit,
    onRowClick: (OpenInResultRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(R.string.open_in_results_title)
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { SettingsBackButton(onClick = onBackClick) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { innerPadding ->
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        AnimatedContent(
            targetState = state,
            transitionSpec = {
                YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                    YoinMotion.fadeOut(role = YoinMotionRole.Standard)
            },
            contentKey = { it::class },
            label = "openInResults",
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) { shown ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp + navBottom),
            ) {
                item(key = "headline") { SettingsHeadline(title = title) }
                when (shown) {
                    OpenInResultsState.Loading -> item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
                            YoinLoadingIndicator()
                        }
                    }
                    is OpenInResultsState.Empty -> item(key = "empty") {
                        Note(stringResource(R.string.open_in_results_empty, serviceName(shown.provider)))
                    }
                    is OpenInResultsState.Results -> {
                        if (!shown.exact) {
                            item(key = "description") {
                                Note(stringResource(R.string.open_in_results_description, serviceName(shown.provider)))
                            }
                        }
                        items(shown.rows, key = OpenInResultRow::key) { row ->
                            ResultRow(row = row, onClick = { onRowClick(row) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 16.dp),
    )
}

@Composable
private fun ResultRow(row: OpenInResultRow, onClick: () -> Unit) {
    val haptics = rememberYoinHaptics()
    val click = {
        haptics.performClick()
        onClick()
    }
    when (row) {
        is OpenInResultRow.Song -> SongListItem(
            title = row.track.title.orEmpty(),
            artist = row.track.artist.orEmpty(),
            album = row.track.album.orEmpty(),
            durationSeconds = row.track.durationSec,
            coverArtUrl = row.coverUrl,
            onClick = click,
            extractBackdropColors = false,
        )
        is OpenInResultRow.AlbumRow -> EntityRow(
            title = row.album.name,
            subtitle = row.album.artist,
            coverUrl = row.coverUrl,
            fallback = YoinSymbols.Album,
            round = false,
            onClick = click,
        )
        is OpenInResultRow.ArtistRow -> EntityRow(
            title = row.artist.name,
            subtitle = null,
            coverUrl = row.coverUrl,
            fallback = YoinSymbols.Artist,
            round = true,
            onClick = click,
        )
        is OpenInResultRow.PlaylistRow -> EntityRow(
            title = row.playlist.name,
            subtitle = row.playlist.owner,
            coverUrl = row.coverUrl,
            fallback = YoinSymbols.Playlist,
            round = false,
            onClick = click,
        )
    }
}

@Composable
private fun EntityRow(
    title: String,
    subtitle: String?,
    coverUrl: String?,
    fallback: ImageVector,
    round: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().handPointer(),
        shape = YoinContainerShapes.ListRow,
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(if (round) YoinShapeTokens.Full else YoinArtworkShapes.Thumb)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(fallback, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                if (coverUrl != null) {
                    AsyncImage(
                        model = coverUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.takeIf(String::isNotBlank)?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
internal fun serviceName(provider: String?): String = stringResource(
    when (provider) {
        MediaId.PROVIDER_SPOTIFY -> R.string.settings_service_name_spotify
        MediaId.PROVIDER_APPLE_MUSIC -> R.string.settings_service_name_apple
        else -> R.string.settings_service_name_subsonic
    },
)

@Preview
@Composable
private fun OpenInResultsScreenPreview() {
    val track = Track(
        id = MediaId.subsonic("1"),
        title = "Blinding Lights",
        artist = "The Weeknd",
        artistId = null,
        album = "After Hours",
        albumId = null,
        coverArt = null,
        durationSec = 200,
        trackNumber = 9,
        year = 2020,
        genre = null,
        userRating = null,
    )
    YoinTheme {
        OpenInResultsScreen(
            state = OpenInResultsState.Results(
                rows = listOf(
                    OpenInResultRow.Song(track, null),
                    OpenInResultRow.Song(track.copy(id = MediaId.subsonic("2"), title = "Blinding Lights (Live)"), null),
                ),
                exact = false,
                provider = MediaId.PROVIDER_SUBSONIC,
            ),
            onBackClick = {},
            onRowClick = {},
        )
    }
}
