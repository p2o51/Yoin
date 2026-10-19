@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.gpo.yoin.ui.settings.backup

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
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
import com.gpo.yoin.data.transfer.ReviewSummary
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.ExpressiveTextField
import com.gpo.yoin.ui.component.MorphPolygonShape
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.component.YoinModalBottomSheet
import com.gpo.yoin.ui.component.formatTrackDuration
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.settings.SettingsRowIcon
import com.gpo.yoin.ui.settings.settingsSegmentShape
import com.gpo.yoin.ui.settings.tone
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinTheme

/**
 * A backup's restore (docs/playlist-transfer.md §7) in one sheet: pick the
 * account, watch the matching, review every song, create the playlist, see
 * what came of it. A song the match didn't find opens a search on the
 * target's own service, in a sheet of its own. Back and drag close a sheet
 * ([YoinModalBottomSheet]); the matching and the review stay in the
 * ViewModel, and Restore reopens them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistRestoreSheet(
    state: RestoreUiState,
    backup: PlaylistBackupRecord?,
    targets: List<RestoreTarget>,
    search: RestoreSearchState?,
    onChooseTarget: (RestoreTarget) -> Unit,
    onCheck: (position: Int, checked: Boolean) -> Unit,
    onChoose: (position: Int, index: Int) -> Unit,
    onOpenSearch: (position: Int, query: String) -> Unit,
    onSearchQuery: (String) -> Unit,
    onPick: (Track) -> Unit,
    onCloseSearch: () -> Unit,
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
            onOpenSearch = onOpenSearch,
            onCreate = onCreate,
            onCancel = onCancel,
            onDone = onDismiss,
        )
    }
    val target = (state as? RestoreUiState.Review)?.target
    if (search != null && target != null) {
        RestoreSearchSheet(
            search = search,
            target = target,
            track = backup.content.tracks.getOrNull(search.position),
            onQuery = onSearchQuery,
            onPick = onPick,
            onDismiss = onCloseSearch,
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
    onOpenSearch: (position: Int, query: String) -> Unit,
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
                is RestoreUiState.Matching -> RestoreUiState.Matching::class
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
            RestoreUiState.Idle, RestoreUiState.ChoosingTarget -> TargetList(backup, targets, onChooseTarget)
            is RestoreUiState.Matching -> MatchingProgress(shown)
            is RestoreUiState.Review, is RestoreUiState.Writing -> Review(
                state = (shown as? RestoreUiState.Writing)?.review ?: shown as RestoreUiState.Review,
                tracks = backup.content.tracks,
                writing = shown is RestoreUiState.Writing,
                onCheck = onCheck,
                onChoose = onChoose,
                onOpenSearch = onOpenSearch,
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
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .semantics { heading() },
    )
}

// ── Choose the account ─────────────────────────────────────────────────

@Composable
private fun TargetList(
    backup: PlaylistBackupRecord,
    targets: List<RestoreTarget>,
    onChooseTarget: (RestoreTarget) -> Unit,
) {
    val haptics = rememberYoinHaptics()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(modifier = Modifier.padding(bottom = 16.dp + navBottom)) {
        SheetTitle(stringResource(R.string.playlist_restore_title))
        Text(
            text = backup.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
        )
        if (targets.isEmpty()) {
            Text(
                text = stringResource(R.string.playlist_restore_no_targets),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            return@Column
        }
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(SegmentGap),
        ) {
            targets.forEachIndexed { index, target ->
                val service = serviceOf(target.provider)
                val tone = service.hue.tone()
                val serviceName = stringResource(service.nameRes)
                Surface(
                    onClick = {
                        haptics.performConfirm()
                        onChooseTarget(target)
                    },
                    shape = settingsSegmentShape(index, targets.size),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .heightIn(min = 76.dp)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        SettingsRowIcon(service.glyph, tone = tone)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = target.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (serviceName != target.displayName) {
                                Text(
                                    text = serviceName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                        Icon(
                            YoinSymbols.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// ── Matching ───────────────────────────────────────────────────────────

/** A determinate Expressive loading indicator (its shapes morph as it fills), the count, and a wavy bar. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MatchingProgress(state: RestoreUiState.Matching) {
    val fraction = if (state.total > 0) state.done.toFloat() / state.total else 0f
    val progress by animateFloatAsState(fraction, YoinMotion.spatialSpring(), label = "playlistRestoreProgress")
    val service = serviceOf(state.target.provider)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(WindowInsets.navigationBars.asPaddingValues())
            .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, MaterialShapes.Cookie9Sided.toShape()),
            contentAlignment = Alignment.Center,
        ) {
            LoadingIndicator(
                progress = { progress },
                modifier = Modifier.size(72.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Text(
            text = stringResource(R.string.playlist_restore_progress, state.done, state.total),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        LinearWavyProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsRowIcon(service.glyph, tone = service.hue.tone())
            Text(
                text = stringResource(R.string.playlist_restore_matching, state.target.displayName),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ── Review ─────────────────────────────────────────────────────────────

private enum class ReviewFilter { All, ToCheck, NotFound }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Review(
    state: RestoreUiState.Review,
    tracks: List<BackupTrack>,
    writing: Boolean,
    onCheck: (position: Int, checked: Boolean) -> Unit,
    onChoose: (position: Int, index: Int) -> Unit,
    onOpenSearch: (position: Int, query: String) -> Unit,
    onCreate: () -> Unit,
    onCancel: () -> Unit,
) {
    val haptics = rememberYoinHaptics()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val summary = RestoreReview.summary(state.rows)
    val toWrite = RestoreReview.trackIdsToWrite(state.rows).size
    val canCreate = !writing && toWrite > 0
    var filter by rememberSaveable { mutableStateOf(ReviewFilter.All) }
    val shown = when (filter) {
        ReviewFilter.All -> state.rows
        ReviewFilter.ToCheck -> state.rows.filter { it.status == ReviewStatus.Uncertain }
        ReviewFilter.NotFound -> state.rows.filter { it.status == ReviewStatus.NotFound }
    }
    val service = serviceOf(state.target.provider)
    val serviceName = stringResource(service.nameRes)
    Column(modifier = Modifier.fillMaxHeight()) {
        Row(
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsRowIcon(service.glyph, tone = service.hue.tone())
            Text(
                text = state.target.displayName,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
        }
        SummaryBar(summary, modifier = Modifier.padding(horizontal = 24.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            val options = listOf(
                ReviewFilter.All to (stringResource(R.string.playlist_restore_all) to state.rows.size),
                ReviewFilter.ToCheck to (stringResource(R.string.playlist_restore_to_check) to summary.toCheck),
                ReviewFilter.NotFound to (stringResource(R.string.playlist_restore_not_found) to summary.notFound),
            )
            options.forEachIndexed { index, (option, label) ->
                ToggleButton(
                    checked = filter == option,
                    onCheckedChange = {
                        haptics.performSegmentTick()
                        filter = option
                    },
                    shapes = when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .semantics { role = Role.Tab },
                ) {
                    Text("${label.first} ${label.second}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(SegmentGap),
        ) {
            itemsIndexed(shown, key = { _, row -> row.position }) { index, row ->
                val track = tracks.getOrNull(row.position) ?: return@itemsIndexed
                ReviewRowItem(
                    row = row,
                    track = track,
                    shapeIndex = index,
                    shapeCount = shown.size,
                    enabled = !writing,
                    searchLabel = stringResource(R.string.playlist_restore_search, serviceName),
                    onCheck = { checked ->
                        haptics.performClick()
                        onCheck(row.position, checked)
                    },
                    onChoose = { candidate ->
                        haptics.performClick()
                        onChoose(row.position, candidate)
                    },
                    onSearch = {
                        haptics.performClick()
                        onOpenSearch(row.position, listOf(track.title, track.artists.firstOrNull().orEmpty()).joinToString(" ").trim())
                    },
                    modifier = Modifier.animateItem(
                        fadeInSpec = null,
                        fadeOutSpec = null,
                        placementSpec = YoinMotion.spatialSpring(),
                    ),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp + navBottom),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel, enabled = !writing, modifier = Modifier.height(56.dp)) {
                Text(stringResource(R.string.playlist_backup_cancel))
            }
            Button(
                onClick = {
                    haptics.performConfirm()
                    onCreate()
                },
                enabled = canCreate,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
            ) {
                if (writing) {
                    YoinLoadingIndicator(size = 24.dp)
                    Spacer(modifier = Modifier.width(10.dp))
                }
                Text(
                    stringResource(
                        if (writing) R.string.playlist_restore_writing_short else R.string.playlist_restore_create,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.width(10.dp))
                CountBadge(toWrite)
            }
        }
    }
}

@Composable
private fun CountBadge(count: Int) {
    Box(
        modifier = Modifier
            .heightIn(min = 24.dp)
            .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(count.toString(), style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * The review in one bar: matched, picked, to check and not found as
 * rounded segments sized by their share — they settle on the spatial spring
 * as the user picks songs — with the counts under them.
 */
@Composable
private fun SummaryBar(summary: ReviewSummary, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val parts = listOf(
        Triple(summary.matched, colors.primary, R.string.playlist_restore_matched),
        Triple(summary.picked, colors.secondary, R.string.playlist_restore_picked),
        Triple(summary.toCheck, colors.tertiary, R.string.playlist_restore_to_check),
        Triple(summary.notFound, colors.outlineVariant, R.string.playlist_restore_not_found),
    )
    val spring = YoinMotion.spatialSpring<Float>()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            parts.forEachIndexed { i, (count, color, _) ->
                val weight by animateFloatAsState(count.toFloat(), spring, label = "summaryPart$i")
                if (weight > 0.01f) {
                    Box(
                        modifier = Modifier
                            .weight(weight)
                            .fillMaxHeight()
                            .background(color, CircleShape),
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            parts.forEach { (count, color, label) ->
                // Matched always; the rest only when there are some.
                if (count > 0 || label == R.string.playlist_restore_matched) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
                        Text(
                            text = count.toString(),
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.onSurface,
                        )
                        Text(
                            text = stringResource(label),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One song of the review, as a segment of the list: its mark (checked songs
 * wear a filled cookie with a tick; an unchecked one is an empty circle, and
 * the two morph into each other), the backup's song, and what the target
 * offers. An uncertain row opens its candidates; a song not found offers a
 * search on the target's service.
 */
@Composable
private fun ReviewRowItem(
    row: ReviewRow,
    track: BackupTrack,
    shapeIndex: Int,
    shapeCount: Int,
    enabled: Boolean,
    searchLabel: String,
    onCheck: (Boolean) -> Unit,
    onChoose: (Int) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showCandidates by rememberSaveable(row.position) { mutableStateOf(false) }
    val uncertain = row.status == ReviewStatus.Uncertain
    val notFound = row.status == ReviewStatus.NotFound
    Surface(
        shape = settingsSegmentShape(shapeIndex, shapeCount),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .clickable(enabled = enabled && !notFound) {
                        if (uncertain) showCandidates = !showCandidates else onCheck(!row.checked)
                    }
                    .padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ReviewMark(
                    status = row.status,
                    checked = row.checked,
                    enabled = enabled && row.checkable,
                    onToggle = { onCheck(!row.checked) },
                )
                Column(modifier = Modifier.weight(1f)) {
                    SongLines(
                        title = track.title,
                        artist = track.artists.joinToString(", "),
                        dimmed = notFound,
                    )
                    // What would be written, when it isn't plainly the same song.
                    if (uncertain || row.status == ReviewStatus.Picked) {
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
                when (row.status) {
                    ReviewStatus.Matched -> track.durationSec?.let { seconds ->
                        Text(
                            text = formatTrackDuration(seconds),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ReviewStatus.Uncertain -> StatusPill(
                        text = stringResource(R.string.playlist_restore_to_check),
                        container = MaterialTheme.colorScheme.tertiaryContainer,
                        content = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    ReviewStatus.Picked -> StatusPill(
                        text = stringResource(R.string.playlist_restore_picked),
                        container = MaterialTheme.colorScheme.secondaryContainer,
                        content = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    ReviewStatus.NotFound -> FilledTonalButton(
                        onClick = onSearch,
                        enabled = enabled,
                        shapes = ButtonDefaults.shapes(),
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        modifier = Modifier.semantics { contentDescription = searchLabel },
                    ) {
                        Icon(YoinSymbols.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.playlist_restore_search_hint))
                    }
                }
            }
            AnimatedVisibility(
                visible = uncertain && showCandidates,
                enter = expandVertically(YoinMotion.spatialSpring(), expandFrom = Alignment.Top) +
                    YoinMotion.fadeIn(role = YoinMotionRole.Standard),
                exit = shrinkVertically(YoinMotion.spatialSpring(), shrinkTowards = Alignment.Top) +
                    YoinMotion.fadeOut(role = YoinMotionRole.Standard),
            ) {
                Candidates(row, enabled, onChoose, onSearch, searchLabel)
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, container: Color, content: Color) {
    Box(
        modifier = Modifier
            .background(container, CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = content, maxLines = 1)
    }
}

/**
 * The row's mark: checked = a filled 9-sided cookie with a tick, unchecked =
 * an empty circle; the shape morphs between them on the spatial spring and
 * the colour rides the effects spring. Not found: a quiet circle, no tick.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ReviewMark(status: ReviewStatus, checked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val morph = remember { Morph(MaterialShapes.Circle, MaterialShapes.Cookie9Sided) }
    val progress = remember { Animatable(if (checked) 1f else 0f) }
    val spring = YoinMotion.spatialSpring<Float>()
    LaunchedEffect(checked) { progress.animateTo(if (checked) 1f else 0f, spring) }
    val container by animateColorAsState(
        targetValue = when {
            checked -> colors.primary
            status == ReviewStatus.Uncertain -> colors.tertiaryContainer
            else -> colors.surfaceContainerHighest
        },
        animationSpec = YoinMotion.effectsSpring(),
        label = "reviewMarkFill",
    )
    val label = stringResource(R.string.playlist_restore_cd_check)
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onToggle)
            .semantics {
                contentDescription = label
                role = Role.Checkbox
                selected = checked
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .graphicsLayer { rotationZ = 40f * progress.value }
                .background(container, MorphPolygonShape(morph, progress.value)),
        )
        if (checked) {
            Icon(
                YoinSymbols.Check,
                contentDescription = null,
                tint = colors.onPrimary,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer {
                        val s = progress.value.coerceIn(0f, 1f)
                        scaleX = s
                        scaleY = s
                    },
            )
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

/** An uncertain row's candidates, each a card to pick; the target's search under them. */
@Composable
private fun Candidates(
    row: ReviewRow,
    enabled: Boolean,
    onChoose: (Int) -> Unit,
    onSearch: () -> Unit,
    searchLabel: String,
) {
    Column(
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(SegmentGap),
    ) {
        row.candidates.forEachIndexed { index, candidate ->
            val selected = row.checked && row.chosen == index
            CandidateRow(
                candidate = candidate,
                selected = selected,
                enabled = enabled,
                shape = settingsSegmentShape(index, row.candidates.size),
                onClick = { onChoose(index) },
            )
        }
        TextButton(onClick = onSearch, enabled = enabled, modifier = Modifier.align(Alignment.End)) {
            Icon(YoinSymbols.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(searchLabel)
        }
    }
}

@Composable
private fun CandidateRow(
    candidate: Track,
    selected: Boolean,
    enabled: Boolean,
    shape: androidx.compose.ui.graphics.Shape,
    onClick: () -> Unit,
) {
    val container by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        YoinMotion.effectsSpring(),
        label = "candidateFill",
    )
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        color = container,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { this.selected = selected },
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 60.dp)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
            if (selected) {
                Icon(YoinSymbols.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// ── Search on the target ───────────────────────────────────────────────

/**
 * The target service's own search for one song the match didn't settle:
 * starts from the song's title and first artist; a result picked becomes
 * the row's song, checked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RestoreSearchSheet(
    search: RestoreSearchState,
    target: RestoreTarget,
    track: BackupTrack?,
    onQuery: (String) -> Unit,
    onPick: (Track) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = rememberYoinHaptics()
    val service = serviceOf(target.provider)
    val serviceName = stringResource(service.nameRes)
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    YoinModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.fillMaxHeight(0.92f)) {
            Row(
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SettingsRowIcon(service.glyph, tone = service.hue.tone())
                Text(
                    text = stringResource(R.string.playlist_restore_search, serviceName),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
            }
            // The song being looked for, from the backup.
            if (track != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        SongLines(track.title, track.artists.joinToString(", "), dimmed = false)
                        track.durationSec?.let { seconds ->
                            Text(
                                formatTrackDuration(seconds),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            ExpressiveTextField(
                value = search.query,
                onValueChange = onQuery,
                label = stringResource(R.string.playlist_restore_search_hint),
                placeholder = serviceName,
                leadingContent = {
                    Icon(YoinSymbols.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                },
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    search.loading -> YoinLoadingIndicator(modifier = Modifier.align(Alignment.Center))
                    search.failed || search.results.isEmpty() -> Text(
                        text = if (search.failed) {
                            stringResource(R.string.playlist_restore_failed, target.displayName)
                        } else {
                            stringResource(R.string.playlist_restore_search_none)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 32.dp),
                    )
                    else -> LazyColumn(
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp + navBottom),
                        verticalArrangement = Arrangement.spacedBy(SegmentGap),
                    ) {
                        itemsIndexed(search.results, key = { _, result -> result.id.toString() }) { index, result ->
                            CandidateRow(
                                candidate = result,
                                selected = false,
                                enabled = true,
                                shape = settingsSegmentShape(index, search.results.size),
                                onClick = {
                                    haptics.performConfirm()
                                    onPick(result)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Outcome ────────────────────────────────────────────────────────────

/** What came of it: a burst that springs in with a tick, the playlist's place, and the counts as tiles. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Outcome(target: RestoreTarget, record: PlaylistRestoreRecord, onDone: () -> Unit) {
    val appear = remember { Animatable(0f) }
    val spring = YoinMotion.defaultSpatialSpec<Float>(role = YoinMotionRole.Expressive)
    LaunchedEffect(Unit) { appear.animateTo(1f, spring) }
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(WindowInsets.navigationBars.asPaddingValues())
            .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Box(
            modifier = Modifier
                .size(112.dp)
                .graphicsLayer {
                    scaleX = appear.value
                    scaleY = appear.value
                    rotationZ = (1f - appear.value) * -60f
                }
                .background(
                    if (record.failed) colors.errorContainer else colors.primaryContainer,
                    (if (record.failed) MaterialShapes.Cookie4Sided else MaterialShapes.SoftBurst).toShape(),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (record.failed) YoinSymbols.Close else YoinSymbols.Check,
                contentDescription = null,
                tint = if (record.failed) colors.onErrorContainer else colors.onPrimaryContainer,
                modifier = Modifier.size(44.dp),
            )
        }
        Text(
            text = stringResource(
                if (record.failed) R.string.playlist_restore_not_created else R.string.playlist_restore_done,
                target.displayName,
            ),
            style = MaterialTheme.typography.headlineSmall,
            color = colors.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            CountTile(record.added, stringResource(R.string.playlist_restore_added), colors.primaryContainer, colors.onPrimaryContainer)
            CountTile(record.skipped, stringResource(R.string.playlist_restore_skipped), colors.surfaceContainerHigh, colors.onSurface)
            CountTile(record.notFound, stringResource(R.string.playlist_restore_not_found), colors.surfaceContainerHigh, colors.onSurface)
        }
        Button(
            onClick = onDone,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) { Text(stringResource(R.string.playlist_restore_done_button), style = MaterialTheme.typography.titleMedium) }
    }
}

@Composable
private fun RowScope.CountTile(count: Int, label: String, container: Color, content: Color) {
    Column(
        modifier = Modifier
            .weight(1f)
            .background(container, RoundedCornerShape(24.dp))
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Text(count.toString(), style = MaterialTheme.typography.displaySmall, color = content)
        Text(label, style = MaterialTheme.typography.labelLarge, color = content.copy(alpha = 0.8f), maxLines = 1)
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDone) { Text(stringResource(R.string.playlist_restore_done_button)) }
        }
    }
}

private val SegmentGap = 2.dp

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

@Preview(showBackground = true, heightDp = 720)
@Composable
private fun PlaylistRestoreReviewPreview() {
    val backup = previewBackup()
    val rows = listOf(
        ReviewRow(0, ReviewStatus.Matched, listOf(previewCandidate("c0", "Entropy", "Beach Bunny", "Emotional Creature")), 0, true),
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
                onOpenSearch = { _, _ -> },
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
                onOpenSearch = { _, _ -> },
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
                onOpenSearch = { _, _ -> },
                onCreate = {},
                onCancel = {},
                onDone = {},
            )
        }
    }
}
