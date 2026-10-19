@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.gpo.yoin.ui.settings.backup

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.data.backup.BackupTrack
import com.gpo.yoin.data.backup.BackupUnexported
import com.gpo.yoin.data.backup.PlaylistBackupRecord
import com.gpo.yoin.data.backup.PlaylistRestoreRecord
import com.gpo.yoin.data.backup.RestoreTarget
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.YoinDropdownMenu
import com.gpo.yoin.ui.component.YoinDropdownMenuItem
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.component.formatTrackDuration
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.settings.SettingsBackButton
import com.gpo.yoin.ui.settings.SettingsGroup
import com.gpo.yoin.ui.settings.SettingsGroupLabel
import com.gpo.yoin.ui.settings.SettingsHeadline
import com.gpo.yoin.ui.settings.SettingsItem
import com.gpo.yoin.ui.settings.SettingsPageBackground
import com.gpo.yoin.ui.settings.handoffProgress
import com.gpo.yoin.ui.settings.tone
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinTheme

/** A backup's page, wired to its [PlaylistBackupViewModel]. */
@Composable
internal fun PlaylistBackupRoute(
    viewModel: PlaylistBackupViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val restore by viewModel.restore.collectAsState()
    val sheetOpen by viewModel.sheetOpen.collectAsState()
    val targets by viewModel.targets.collectAsState()
    val search by viewModel.search.collectAsState()
    val backingUp by viewModel.backingUp.collectAsState()
    // The restore's Live Update needs the notification permission: asked once, as the user starts one.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val context = LocalContext.current
    val restoreTo: (RestoreTarget) -> Unit = { target ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        viewModel.restoreTo(target)
    }
    val currentOnBack by androidx.compose.runtime.rememberUpdatedState(onBackClick)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                PlaylistBackupEvent.Deleted -> currentOnBack()
            }
        }
    }
    PlaylistBackupScreen(
        uiState = uiState,
        onBackClick = onBackClick,
        onRestore = viewModel::openRestore,
        onBackUpNow = viewModel::backUpAgain,
        backingUp = backingUp,
        onDelete = viewModel::delete,
        modifier = modifier,
    )
    if (sheetOpen) {
        PlaylistRestoreSheet(
            state = restore,
            backup = (uiState as? PlaylistBackupUiState.Content)?.backup,
            targets = targets,
            search = search,
            onChooseTarget = restoreTo,
            onCheck = viewModel::setChecked,
            onChoose = viewModel::choose,
            onOpenSearch = viewModel::openSearch,
            onSearchQuery = { viewModel.runSearch(it) },
            onPick = viewModel::pickSearchResult,
            onCloseSearch = viewModel::closeSearch,
            onCreate = viewModel::createPlaylist,
            onCancel = viewModel::cancelRestore,
            onDismiss = viewModel::dismissSheet,
        )
    }
}

/**
 * One backup (docs/playlist-transfer.md §7): where it came from and when,
 * Restore, its restore history, what couldn't be backed up and why, and the
 * songs it holds. Pattern A page: the top-bar arrow just leaves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistBackupScreen(
    uiState: PlaylistBackupUiState,
    onBackClick: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onBackUpNow: () -> Unit = {},
    backingUp: Boolean = false,
    nowMs: Long = remember { System.currentTimeMillis() },
) {
    val backup = (uiState as? PlaylistBackupUiState.Content)?.backup
    val title = backup?.name ?: stringResource(R.string.settings_playlist_backups_title)
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    val viewportTop = remember { mutableFloatStateOf(0f) }
    val headlineTop = remember { mutableFloatStateOf(Float.NaN) }
    val headlineHeight = remember { mutableFloatStateOf(1f) }
    ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
        SettingsPageBackground(modifier = modifier) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                text = title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.graphicsLayer {
                                    alpha = handoffProgress(viewportTop, headlineTop, headlineHeight)
                                    translationY = (1f - alpha) * size.height * 0.5f
                                },
                            )
                        },
                        navigationIcon = { SettingsBackButton(onClick = onBackClick) },
                        actions = {
                            if (backup != null) BackupOverflow(onDelete = { showDeleteConfirm = true })
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                },
            ) { innerPadding ->
                val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                AnimatedContent(
                    targetState = uiState,
                    contentKey = { it::class },
                    transitionSpec = {
                        YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                            YoinMotion.fadeOut(role = YoinMotionRole.Standard)
                    },
                    label = "playlistBackup",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) { state ->
                    when (state) {
                        PlaylistBackupUiState.Loading, PlaylistBackupUiState.Missing ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                if (state == PlaylistBackupUiState.Loading) YoinLoadingIndicator()
                            }
                        is PlaylistBackupUiState.Content -> LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .onGloballyPositioned { viewportTop.floatValue = it.positionInWindow().y },
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                top = 8.dp,
                                end = 16.dp,
                                bottom = 24.dp + navBottom,
                            ),
                        ) {
                            item("headline") {
                                SettingsHeadline(
                                    title = state.backup.name,
                                    titleModifier = Modifier.onGloballyPositioned {
                                        headlineTop.floatValue = it.positionInWindow().y
                                        headlineHeight.floatValue = it.size.height.toFloat().coerceAtLeast(1f)
                                    },
                                )
                            }
                            backupBody(state.backup, nowMs, onRestore, onBackUpNow, backingUp)
                        }
                    }
                }
            }
        }
    }
    if (showDeleteConfirm && backup != null) {
        DeleteBackupDialog(
            onDismiss = { showDeleteConfirm = false },
            onConfirm = {
                showDeleteConfirm = false
                onDelete()
            },
        )
    }
}

private fun LazyListScope.backupBody(
    backup: PlaylistBackupRecord,
    nowMs: Long,
    onRestore: () -> Unit,
    onBackUpNow: () -> Unit,
    backingUp: Boolean,
) {
    item("status") { BackupStatusCard(backup, nowMs) }
    item("actions") {
        val haptics = rememberYoinHaptics()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            Button(
                onClick = {
                    haptics.performConfirm()
                    onRestore()
                },
                enabled = backup.trackCount > 0,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp),
            ) {
                Icon(YoinSymbols.Add, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.playlist_backup_restore), style = MaterialTheme.typography.titleMedium)
            }
            // Only the device that follows the playlist can read it again.
            if (backup.following && backup.sourceProfileId != null) {
                FilledTonalButton(
                    onClick = {
                        haptics.performClick()
                        onBackUpNow()
                    },
                    enabled = !backingUp,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.heightIn(min = 56.dp),
                ) {
                    if (backingUp) {
                        YoinLoadingIndicator(size = 24.dp)
                    } else {
                        Icon(YoinSymbols.Refresh, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text(stringResource(R.string.playlist_backup_now))
                    }
                }
            }
        }
    }
    if (backup.restores.isNotEmpty()) {
        item("restores") {
            Column(modifier = Modifier.padding(top = 28.dp)) {
                SettingsGroup(title = stringResource(R.string.playlist_backup_section_restores)) {
                    // Newest first, like the backups themselves.
                    backup.restores.asReversed().forEachIndexed { index, record ->
                        item(key = "restore-$index") { RestoreHistoryRow(record, nowMs) }
                    }
                }
            }
        }
    }
    if (backup.content.unexported.isNotEmpty()) {
        item("unexported-label") {
            SettingsGroupLabel(
                title = stringResource(R.string.playlist_backup_section_unexported),
                trailingLabel = backup.unexportedCount.toString(),
                modifier = Modifier.padding(top = 28.dp, bottom = 4.dp),
            )
        }
        itemsIndexed(backup.content.unexported, key = { index, _ -> "unexported-$index" }) { _, item ->
            UnexportedRow(item)
        }
    }
    item("songs-label") {
        SettingsGroupLabel(
            title = stringResource(R.string.playlist_backup_section_songs),
            trailingLabel = backup.trackCount.toString(),
            modifier = Modifier.padding(top = 28.dp, bottom = 4.dp),
        )
    }
    itemsIndexed(backup.content.tracks, key = { index, _ -> "track-$index" }) { index, track ->
        BackupTrackRow(index, track)
    }
}

@Composable
private fun RestoreHistoryRow(record: PlaylistRestoreRecord, nowMs: Long) {
    val service = serviceOf(record.targetProvider)
    val resources = LocalContext.current.resources
    SettingsItem(
        icon = service.glyph,
        iconTone = service.hue.tone(),
        title = record.targetLabel,
        summaryContent = {
            Text(
                text = formatBackupTime(record.at, nowMs, resources),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (record.failed) {
                Text(
                    text = stringResource(R.string.playlist_backup_restore_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                RestoreCounts(record)
            }
        },
    )
}

/** The three counts side by side, each its own label: added, skipped, not found (zero ones left out). */
@Composable
private fun RestoreCounts(record: PlaylistRestoreRecord) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = pluralStringResource(R.plurals.playlist_backup_restore_added, record.added, record.added),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        if (record.skipped > 0) {
            Text(
                text = pluralStringResource(R.plurals.playlist_backup_restore_skipped, record.skipped, record.skipped),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (record.notFound > 0) {
            Text(
                text = pluralStringResource(
                    R.plurals.playlist_backup_restore_not_found,
                    record.notFound,
                    record.notFound,
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UnexportedRow(item: BackupUnexported) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title ?: stringResource(R.string.playlist_backup_untitled),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            item.artist?.let { artist ->
                Text(
                    text = artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = stringResource(item.reasonKind.labelRes()),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
internal fun BackupTrackRow(index: Int, track: BackupTrack, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = (index + 1).toString(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 24.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (track.artists.isNotEmpty()) {
                Text(
                    text = track.artists.joinToString(", "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        track.durationSec?.let { seconds ->
            Text(
                text = formatTrackDuration(seconds),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BackupOverflow(onDelete: () -> Unit) {
    val haptics = rememberYoinHaptics()
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = {
                haptics.performClick()
                expanded = true
            },
        ) {
            Icon(YoinSymbols.MoreVertical, contentDescription = stringResource(R.string.playlist_backup_cd_more))
        }
        YoinDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            YoinDropdownMenuItem(
                text = stringResource(R.string.playlist_backup_delete),
                leadingIcon = { Icon(YoinSymbols.Delete, contentDescription = null) },
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun DeleteBackupDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val haptics = rememberYoinHaptics()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.playlist_backup_delete_title)) },
        text = { Text(stringResource(R.string.playlist_backup_delete_body)) },
        confirmButton = {
            TextButton(
                onClick = {
                    haptics.performReject()
                    onConfirm()
                },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.playlist_backup_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.playlist_backup_cancel)) }
        },
    )
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun PlaylistBackupScreenPreview() {
    YoinTheme {
        PlaylistBackupScreen(
            uiState = PlaylistBackupUiState.Content(previewBackup(restores = 1)),
            onBackClick = {},
            onRestore = {},
            onDelete = {},
            nowMs = 1_760_950_000_000L,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun BackupTrackRowPreview() {
    YoinTheme {
        Column {
            BackupTrackRow(0, BackupTrack("Entropy", listOf("Beach Bunny", "Tegan"), null, 221, null, "spotify:t1"))
            Spacer(Modifier.height(8.dp))
        }
    }
}

internal val previewTargets = listOf(
    RestoreTarget("am", "Apple Music", com.gpo.yoin.data.model.MediaId.PROVIDER_APPLE_MUSIC),
    RestoreTarget("ss", "Home server", com.gpo.yoin.data.model.MediaId.PROVIDER_SUBSONIC),
)

/**
 * Where the backup stands, on its service's colour: the account, whether it
 * still follows the playlist (or the playlist is gone and it is kept), and
 * its dates as tiles — the playlist's own, when the backup began, the last
 * backup and the last change.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BackupStatusCard(backup: PlaylistBackupRecord, nowMs: Long) {
    val service = serviceOf(backup.sourceProvider)
    val tone = service.hue.tone()
    val resources = LocalContext.current.resources
    val serviceName = stringResource(service.nameRes)
    val following = backup.following
    val state = when {
        !following -> stringResource(R.string.playlist_backup_source_gone)
        backup.sourceProfileId == null -> stringResource(R.string.playlist_backup_not_here)
        else -> stringResource(R.string.playlist_backup_following)
    }
    Surface(
        color = tone.container,
        contentColor = tone.onContainer,
        shape = RoundedCornerShape(32.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .background(tone.accent, MaterialShapes.Cookie9Sided.toShape()),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(service.glyph, contentDescription = null, tint = tone.onAccent, modifier = Modifier.size(26.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = backup.sourceProfileLabel,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (serviceName != backup.sourceProfileLabel) {
                        Text(serviceName, style = MaterialTheme.typography.bodyMedium, color = tone.onContainer.copy(alpha = 0.75f))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(if (following) tone.accent else MaterialTheme.colorScheme.outline, CircleShape),
                )
                Text(state, style = MaterialTheme.typography.labelLarge)
                if (!following) {
                    backup.sourceGoneAt?.let { at ->
                        Text(
                            formatBackupTime(at, nowMs, resources),
                            style = MaterialTheme.typography.labelLarge,
                            color = tone.onContainer.copy(alpha = 0.7f),
                        )
                    }
                }
            }
            val tiles = buildList {
                parseServiceDate(backup.playlistCreatedAt)?.let { add(R.string.playlist_backup_created to it) }
                add(R.string.playlist_backup_since to backup.firstBackedUpAt)
                add(R.string.playlist_backup_last to backup.lastBackedUpAt)
                add(R.string.playlist_backup_changed to backup.lastChangedAt)
            }
            tiles.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    pair.forEach { (label, at) ->
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .background(tone.onContainer.copy(alpha = 0.07f), RoundedCornerShape(20.dp))
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                        ) {
                            Text(
                                stringResource(label),
                                style = MaterialTheme.typography.labelMedium,
                                color = tone.onContainer.copy(alpha = 0.75f),
                            )
                            Text(
                                formatBackupTime(at, nowMs, resources),
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** A service's ISO-8601 date ("2024-03-01T10:00:00Z", or a bare date) as epoch millis; null when unreadable. */
internal fun parseServiceDate(text: String?): Long? {
    val value = text?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrNull()
        ?: runCatching { java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching {
            java.time.LocalDate.parse(value.take(10)).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        }.getOrNull()
}
