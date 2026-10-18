package com.gpo.yoin.ui.detail

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.source.ServiceFeatureCatalog
import com.gpo.yoin.data.transfer.PlaylistCopyTarget
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.YoinModalBottomSheet
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinTheme

/**
 * The playlist page's "Copy to…" (docs/playlist-transfer.md): one sheet that
 * lists the profiles to copy to, then the copy's progress, then what came of
 * it. Back and drag close it ([YoinModalBottomSheet]); a copy still running
 * carries on and reports on the page's snackbar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistCopySheet(
    state: PlaylistCopyState,
    targets: List<PlaylistCopyTarget>,
    onCopyTo: (PlaylistCopyTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    if (state == PlaylistCopyState.Closed) return
    YoinModalBottomSheet(onDismissRequest = onDismiss) {
        PlaylistCopySheetContent(state = state, targets = targets, onCopyTo = onCopyTo, onDone = onDismiss)
    }
}

@Composable
private fun PlaylistCopySheetContent(
    state: PlaylistCopyState,
    targets: List<PlaylistCopyTarget>,
    onCopyTo: (PlaylistCopyTarget) -> Unit,
    onDone: () -> Unit,
) {
    val sizeSpring = YoinMotion.spatialSpring<androidx.compose.ui.unit.IntSize>()
    AnimatedContent(
        targetState = state,
        contentKey = { it::class },
        transitionSpec = {
            (YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                YoinMotion.fadeOut(role = YoinMotionRole.Standard))
                .using(SizeTransform(clip = false) { _, _ -> sizeSpring })
        },
        label = "playlistCopySheet",
    ) { shown ->
        when (shown) {
            PlaylistCopyState.Closed, PlaylistCopyState.Choosing -> CopyTargetList(targets, onCopyTo)
            is PlaylistCopyState.Copying -> CopyProgress(shown)
            is PlaylistCopyState.Copied -> CopyOutcome(shown, onDone)
            is PlaylistCopyState.Failed -> CopyFailure(onDone)
        }
    }
}

@Composable
private fun SheetTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .semantics { heading() },
    )
}

@Composable
private fun CopyTargetList(targets: List<PlaylistCopyTarget>, onCopyTo: (PlaylistCopyTarget) -> Unit) {
    val haptics = rememberYoinHaptics()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column {
        SheetTitle(stringResource(R.string.detail_playlist_copy_title))
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp + navBottom)) {
            items(targets, key = { it.profileId }) { target ->
                val service = stringResource(ServiceFeatureCatalog.forProvider(target.provider).nameRes)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable {
                            haptics.performConfirm()
                            onCopyTo(target)
                        }
                        .padding(horizontal = 24.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(
                        imageVector = YoinSymbols.Playlist,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = target.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // The service under the profile's own name, unless that is its name.
                        if (service != target.displayName) {
                            Text(
                                text = service,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CopyProgress(state: PlaylistCopyState.Copying) {
    val fraction = if (state.total > 0) state.done.toFloat() / state.total else 0f
    val progress by animateFloatAsState(fraction, YoinMotion.spatialSpring(), label = "playlistCopyProgress")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(WindowInsets.navigationBars.asPaddingValues())
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SheetTitle(stringResource(R.string.detail_playlist_copy_running, state.target))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        )
        Text(
            text = stringResource(R.string.detail_playlist_copy_progress, state.done, state.total),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

@Composable
private fun CopyOutcome(state: PlaylistCopyState.Copied, onDone: () -> Unit) {
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val unsure = state.missing.filter { it.unsure }
    val notFound = state.missing.filterNot { it.unsure }
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp + navBottom)) {
        item("title") {
            val title = if (state.added == 0) {
                stringResource(R.string.detail_playlist_copy_none, state.target)
            } else {
                pluralStringResource(R.plurals.detail_playlist_copy_done, state.total, state.added, state.total, state.target)
            }
            SheetTitle(title)
        }
        if (state.stoppedEarly) {
            item("stopped") {
                Text(
                    text = stringResource(R.string.detail_playlist_copy_stopped),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }
        }
        missingSection("unsure", R.string.detail_playlist_copy_unsure, unsure)
        missingSection("notFound", R.string.detail_playlist_copy_not_found, notFound)
        item("done") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDone) { Text(stringResource(R.string.detail_playlist_copy_done_button)) }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.missingSection(
    key: String,
    label: Int,
    songs: List<PlaylistCopyMissing>,
) {
    if (songs.isEmpty()) return
    item(key) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
        )
    }
    items(songs, key = { "$key-${it.position}" }) { song ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 6.dp),
        ) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (song.artist.isNotBlank()) {
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CopyFailure(onDone: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(WindowInsets.navigationBars.asPaddingValues())
            .padding(bottom = 8.dp),
    ) {
        SheetTitle(stringResource(R.string.detail_playlist_copy_failed))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDone) { Text(stringResource(R.string.detail_playlist_copy_done_button)) }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PlaylistCopyTargetsPreview() {
    YoinTheme {
        Surface {
            PlaylistCopySheetContent(
                state = PlaylistCopyState.Choosing,
                targets = listOf(
                    PlaylistCopyTarget("a", "Apple Music", MediaId.PROVIDER_APPLE_MUSIC),
                    PlaylistCopyTarget("b", "Home server", MediaId.PROVIDER_SUBSONIC),
                ),
                onCopyTo = {},
                onDone = {},
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PlaylistCopyOutcomePreview() {
    YoinTheme {
        Surface {
            PlaylistCopySheetContent(
                state = PlaylistCopyState.Copied(
                    target = "Apple Music",
                    added = 42,
                    total = 45,
                    missing = listOf(
                        PlaylistCopyMissing(3, "Lemon", "Kenshi Yonezu", unsure = true),
                        PlaylistCopyMissing(17, "Untitled Demo", "Local Artist", unsure = false),
                        PlaylistCopyMissing(30, "Interlude", "Someone", unsure = false),
                    ),
                ),
                targets = emptyList(),
                onCopyTo = {},
                onDone = {},
            )
        }
    }
}
