package com.gpo.yoin.ui.settings.backup

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.data.backup.BackupTrack
import com.gpo.yoin.data.backup.PlaylistBackupRecord
import com.gpo.yoin.data.backup.PlaylistRestoreRecord
import com.gpo.yoin.data.backup.RestoreTarget
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.transfer.RestoreReview
import com.gpo.yoin.data.transfer.ReviewRow
import com.gpo.yoin.data.transfer.ReviewStatus
import com.gpo.yoin.ui.component.YoinModalBottomSheet
import com.gpo.yoin.ui.component.formatTrackDuration
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.settings.SettingsRowIcon
import com.gpo.yoin.ui.settings.tone
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinTheme

/**
 * A backup's restore (docs/playlist-transfer.md §7) in one sheet: pick the
 * account, watch the matching, review every song, create the playlist, see
 * what came of it. Back and drag close it ([YoinModalBottomSheet]); the
 * matching and the review stay in the ViewModel, and Restore reopens them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistRestoreSheet(
    state: RestoreUiState,
    backup: PlaylistBackupRecord?,
    targets: List<RestoreTarget>,
    onChooseTarget: (RestoreTarget) -> Unit,
    onCheck: (position: Int, checked: Boolean) -> Unit,
    onChoose: (position: Int, index: Int) -> Unit,
    onCreate: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (state == RestoreUiState.Idle || backup == null) return
    YoinModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        PlaylistRestoreSheetContent(
            state = state,
            backup = backup,
            targets = targets,
            onChooseTarget = onChooseTarget,
            onCheck = onCheck,
            onChoose = onChoose,
            onCreate = onCreate,
            onCancel = onCancel,
            onDone = onDismiss,
        )
    }
}

@Composable
internal fun PlaylistRestoreSheetContent(
    state: RestoreUiState,
    backup: PlaylistBackupRecord,
    targets: List<RestoreTarget>,
    onChooseTarget: (RestoreTarget) -> Unit,
    onCheck: (position: Int, checked: Boolean) -> Unit,
    onChoose: (position: Int, index: Int) -> Unit,
    onCreate: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    val sizeSpring = YoinMotion.spatialSpring<IntSize>()
    AnimatedContent(
        targetState = state,
        // Review → Writing keeps the list in place; every other step change cross-fades.
        contentKey = { shown ->
            when (shown) {
                is RestoreUiState.Review, is RestoreUiState.Writing -> RestoreUiState.Review::class
                else -> shown::class
            }
        },
        transitionSpec = {
            val fade = YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                YoinMotion.fadeOut(role = YoinMotionRole.Standard)
            fade.using(SizeTransform(clip = false) { _, _ -> sizeSpring })
        },
        label = "playlistRestoreSheet",
    ) { shown ->
        when (shown) {
            RestoreUiState.Idle, RestoreUiState.ChoosingTarget -> TargetList(targets, onChooseTarget)
            is RestoreUiState.Matching -> MatchingProgress(shown)
            is RestoreUiState.Review, is RestoreUiState.Writing -> Review(
                state = (shown as? RestoreUiState.Writing)?.review ?: shown as RestoreUiState.Review,
                tracks = backup.content.tracks,
                writing = shown is RestoreUiState.Writing,
                onCheck = onCheck,
                onChoose = onChoose,
                onCreate = onCreate,
                onCancel = onCancel,
            )
            is RestoreUiState.Done -> Outcome(shown.target, shown.record, onDone)
            is RestoreUiState.Failed -> Failure(shown.target, onDone)
        }
    }
}

@Composable
private fun SheetTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .semantics { heading() },
    )
}

@Composable
private fun TargetList(targets: List<RestoreTarget>, onChooseTarget: (RestoreTarget) -> Unit) {
    val haptics = rememberYoinHaptics()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column {
        SheetTitle(stringResource(R.string.playlist_restore_title))
        if (targets.isEmpty()) {
            Text(
                text = stringResource(R.string.playlist_restore_no_targets),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp + navBottom),
            )
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp + navBottom)) {
            items(targets, key = { it.profileId }) { target ->
                val service = serviceOf(target.provider)
                val serviceName = stringResource(service.nameRes)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .clickable {
                            haptics.performConfirm()
                            onChooseTarget(target)
                        }
                        .padding(horizontal = 24.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SettingsRowIcon(service.glyph, tone = service.hue.tone())
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = target.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // The service under the profile's own name, unless that is its name.
                        if (serviceName != target.displayName) {
                            Text(
                                text = serviceName,
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
private fun MatchingProgress(state: RestoreUiState.Matching) {
    val fraction = if (state.total > 0) state.done.toFloat() / state.total else 0f
    val progress by animateFloatAsState(fraction, YoinMotion.spatialSpring(), label = "playlistRestoreProgress")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(WindowInsets.navigationBars.asPaddingValues())
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SheetTitle(stringResource(R.string.playlist_restore_matching, state.target.displayName))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        )
        Text(
            text = stringResource(R.string.playlist_restore_progress, state.done, state.total),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

@Composable
private fun Review(
    state: RestoreUiState.Review,
    tracks: List<BackupTrack>,
    writing: Boolean,
    onCheck: (position: Int, checked: Boolean) -> Unit,
    onChoose: (position: Int, index: Int) -> Unit,
    onCreate: () -> Unit,
    onCancel: () -> Unit,
) {
    val haptics = rememberYoinHaptics()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val summary = RestoreReview.summary(state.rows)
    val canCreate = !writing && RestoreReview.trackIdsToWrite(state.rows).isNotEmpty()
    Column(modifier = Modifier.fillMaxHeight()) {
        SheetTitle(state.target.displayName)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            SummaryCount(summary.matched, stringResource(R.string.playlist_restore_matched))
            SummaryCount(summary.toCheck, stringResource(R.string.playlist_restore_to_check))
            SummaryCount(summary.notFound, stringResource(R.string.playlist_restore_not_found))
        }
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(state.rows, key = { it.position }) { row ->
                val track = tracks.getOrNull(row.position) ?: return@items
                ReviewRowItem(
                    row = row,
                    track = track,
                    enabled = !writing,
                    onCheck = { checked ->
                        haptics.performClick()
                        onCheck(row.position, checked)
                    },
                    onChoose = { index ->
                        haptics.performClick()
                        onChoose(row.position, index)
                    },
                )
            }
        }
        if (writing) {
            Text(
                text = stringResource(R.string.playlist_restore_writing, state.target.displayName),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp + navBottom),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel, enabled = !writing) {
                Text(stringResource(R.string.playlist_backup_cancel))
            }
            Button(
                onClick = {
                    haptics.performConfirm()
                    onCreate()
                },
                enabled = canCreate,
            ) { Text(stringResource(R.string.playlist_restore_create)) }
        }
    }
}

@Composable
private fun SummaryCount(count: Int, label: String) {
    Column {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One song of the review: the backup's song, checked or not, and — while
 * it is uncertain — the target's candidates to pick from, opened by tapping
 * the row.
 */
@Composable
private fun ReviewRowItem(
    row: ReviewRow,
    track: BackupTrack,
    enabled: Boolean,
    onCheck: (Boolean) -> Unit,
    onChoose: (Int) -> Unit,
) {
    var showCandidates by rememberSaveable(row.position) { mutableStateOf(false) }
    val uncertain = row.status == ReviewStatus.Uncertain
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clickable(enabled = enabled && row.checkable) {
                    if (uncertain) showCandidates = !showCandidates else onCheck(!row.checked)
                }
                .padding(start = 12.dp, end = 24.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A read-only checkbox drops its 48dp touch target; keep it so every row lines up.
            Checkbox(
                checked = row.checked,
                onCheckedChange = if (row.checkable) onCheck else null,
                enabled = enabled && row.checkable,
                modifier = Modifier.minimumInteractiveComponentSize(),
            )
            Column(modifier = Modifier.weight(1f)) {
                SongLines(
                    title = track.title,
                    artist = track.artists.joinToString(", "),
                    dimmed = !row.checkable,
                )
                // An uncertain row names the candidate it would write, under the song.
                if (uncertain) {
                    row.choice?.let { choice ->
                        Text(
                            text = choice.title.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Text(
                text = when (row.status) {
                    ReviewStatus.Matched -> track.durationSec?.let(::formatTrackDuration).orEmpty()
                    ReviewStatus.Uncertain -> stringResource(R.string.playlist_restore_to_check)
                    ReviewStatus.NotFound -> stringResource(R.string.playlist_restore_not_found)
                },
                style = MaterialTheme.typography.labelLarge,
                color = if (row.status == ReviewStatus.Matched) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.tertiary
                },
            )
        }
        AnimatedVisibility(
            visible = uncertain && showCandidates,
            enter = expandVertically(YoinMotion.spatialSpring(), expandFrom = Alignment.Top) +
                YoinMotion.fadeIn(role = YoinMotionRole.Standard),
            exit = shrinkVertically(YoinMotion.spatialSpring(), shrinkTowards = Alignment.Top) +
                YoinMotion.fadeOut(role = YoinMotionRole.Standard),
        ) {
            Candidates(row, enabled, onChoose)
        }
    }
}

@Composable
private fun ColumnScope.SongLines(title: String, artist: String, dimmed: Boolean) {
    Text(
        text = title,
        style = MaterialTheme.typography.bodyLarge,
        color = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    if (artist.isNotEmpty()) {
        Text(
            text = artist,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Candidates(row: ReviewRow, enabled: Boolean, onChoose: (Int) -> Unit) {
    Column(modifier = Modifier.padding(start = 60.dp, end = 24.dp, bottom = 8.dp)) {
        Text(
            text = stringResource(R.string.playlist_restore_candidates),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        row.candidates.forEachIndexed { index, candidate ->
            val selected = row.checked && row.chosen == index
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable(enabled = enabled) { onChoose(index) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, onClick = { onChoose(index) }, enabled = enabled)
                Column(modifier = Modifier.weight(1f)) {
                    SongLines(
                        title = candidate.title.orEmpty(),
                        artist = listOfNotNull(candidate.artist).joinToString(),
                        dimmed = false,
                    )
                    candidate.album?.let { album ->
                        Text(
                            text = album,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                candidate.durationSec?.let { seconds ->
                    Text(
                        text = formatTrackDuration(seconds),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Outcome(target: RestoreTarget, record: PlaylistRestoreRecord, onDone: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(WindowInsets.navigationBars.asPaddingValues())
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SheetTitle(
            stringResource(
                if (record.failed) R.string.playlist_restore_not_created else R.string.playlist_restore_done,
                target.displayName,
            ),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            SummaryCount(record.added, stringResource(R.string.playlist_restore_added))
            if (record.skipped > 0) SummaryCount(record.skipped, stringResource(R.string.playlist_restore_skipped))
            if (record.notFound > 0) SummaryCount(record.notFound, stringResource(R.string.playlist_restore_not_found))
        }
        DoneButton(onDone)
    }
}

@Composable
private fun Failure(target: RestoreTarget, onDone: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(WindowInsets.navigationBars.asPaddingValues())
            .padding(bottom = 8.dp),
    ) {
        SheetTitle(stringResource(R.string.playlist_restore_failed, target.displayName))
        DoneButton(onDone)
    }
}

@Composable
private fun DoneButton(onDone: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onDone) { Text(stringResource(R.string.playlist_restore_done_button)) }
    }
}

private fun previewCandidate(id: String, title: String, artist: String, album: String) = Track(
    id = MediaId(MediaId.PROVIDER_APPLE_MUSIC, id),
    title = title,
    artist = artist,
    artistId = null,
    album = album,
    albumId = null,
    coverArt = null,
    durationSec = 255,
    trackNumber = null,
    year = null,
    genre = null,
    userRating = null,
)

@Preview(showBackground = true, heightDp = 640)
@Composable
private fun PlaylistRestoreReviewPreview() {
    val backup = previewBackup()
    val rows = listOf(
        ReviewRow(
            position = 0,
            status = ReviewStatus.Matched,
            candidates = listOf(previewCandidate("c0", "Entropy", "Beach Bunny", "Emotional Creature")),
            chosen = 0,
            checked = true,
        ),
        ReviewRow(
            1,
            ReviewStatus.Uncertain,
            listOf(
                previewCandidate("c1", "Lemon", "米津玄師", "Lemon"),
                previewCandidate("c2", "Lemon (Cover)", "Someone", "Covers"),
            ),
            0,
            false,
        ),
        ReviewRow(2, ReviewStatus.NotFound, emptyList(), 0, false),
    )
    YoinTheme {
        Surface {
            PlaylistRestoreSheetContent(
                state = RestoreUiState.Review(previewTargets.first(), rows),
                backup = backup,
                targets = previewTargets,
                onChooseTarget = {},
                onCheck = { _, _ -> },
                onChoose = { _, _ -> },
                onCreate = {},
                onCancel = {},
                onDone = {},
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PlaylistRestoreTargetsPreview() {
    YoinTheme {
        Surface {
            PlaylistRestoreSheetContent(
                state = RestoreUiState.ChoosingTarget,
                backup = previewBackup(),
                targets = previewTargets,
                onChooseTarget = {},
                onCheck = { _, _ -> },
                onChoose = { _, _ -> },
                onCreate = {},
                onCancel = {},
                onDone = {},
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PlaylistRestoreOutcomePreview() {
    YoinTheme {
        Surface {
            PlaylistRestoreSheetContent(
                state = RestoreUiState.Done(
                    previewTargets.first(),
                    PlaylistRestoreRecord(
                        at = 0L,
                        targetProvider = MediaId.PROVIDER_APPLE_MUSIC,
                        targetLabel = "Apple Music",
                        added = 38,
                        skipped = 4,
                        notFound = 3,
                    ),
                ),
                backup = previewBackup(),
                targets = previewTargets,
                onChooseTarget = {},
                onCheck = { _, _ -> },
                onChoose = { _, _ -> },
                onCreate = {},
                onCancel = {},
                onDone = {},
            )
        }
    }
}
