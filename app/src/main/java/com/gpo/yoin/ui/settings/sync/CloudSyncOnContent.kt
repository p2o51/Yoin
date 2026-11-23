package com.gpo.yoin.ui.settings.sync

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.data.sync.CloudAccountRow
import com.gpo.yoin.data.sync.CloudDataSummary
import com.gpo.yoin.data.sync.CloudDeviceFiles
import com.gpo.yoin.data.sync.CloudScopeCounts
import com.gpo.yoin.data.sync.CloudSyncPhase
import com.gpo.yoin.data.sync.CloudSyncState
import com.gpo.yoin.data.sync.SyncAccountRow
import com.gpo.yoin.data.sync.SyncAccountStatus
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.MetaFlow
import com.gpo.yoin.ui.component.MetaGroup
import com.gpo.yoin.ui.component.MetaLine
import com.gpo.yoin.ui.component.YoinDropdownMenu
import com.gpo.yoin.ui.component.YoinDropdownMenuItem
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.component.handPointer
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.settings.LocalSettingsRowShape
import com.gpo.yoin.ui.settings.SettingsGroup
import com.gpo.yoin.ui.settings.SettingsItem
import com.gpo.yoin.ui.settings.SettingsRowMinHeight
import com.gpo.yoin.ui.settings.SettingsRowTextInset
import com.gpo.yoin.ui.settings.SettingsSegments
import com.gpo.yoin.ui.settings.service.SetupService
import com.gpo.yoin.ui.settings.tone
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole

/**
 * One account in the cloud, as the page and its sub-pages pass it around: a local profile that syncs (or is
 * paused), or an account only in the cloud.
 */
data class CloudAccountRef(
    val syncProfileId: String,
    val provider: String,
    val displayName: String,
    val hint: String? = null,
    val fromDevice: String? = null,
    val cloudOnly: Boolean = false,
    val localProfileId: String? = null,
)

/** The accounts the 云端数据 section lists: this device's synced (or paused) profiles, then cloud-only ones. */
internal fun cloudAccountsOf(state: CloudSyncState): List<CloudAccountRef> =
    state.accounts
        .filter { (it.status == SyncAccountStatus.Syncing || it.status == SyncAccountStatus.AccountChanged) && it.syncProfileId != null }
        .map { CloudAccountRef(it.syncProfileId!!, it.provider, it.displayName, localProfileId = it.localProfileId) } +
        state.cloudOnlyAccounts.map { it.toRef() }

internal fun CloudAccountRow.toRef() = CloudAccountRef(
    syncProfileId = syncProfileId,
    provider = provider,
    displayName = displayName,
    hint = hint,
    fromDevice = fromDeviceName,
    cloudOnly = true,
)

/** Profiles here that don't sync: removed from the cloud, not chosen (Apple Music), or waiting on something. */
private fun notSyncingOf(state: CloudSyncState): List<SyncAccountRow> = state.accounts.filter {
    it.status != SyncAccountStatus.Syncing && it.status != SyncAccountStatus.AccountChanged
}

/** Everything the redone "on" page needs besides [CloudSyncState]. */
internal data class CloudSyncOnActions(
    val onTurnOn: () -> Unit = {},
    val onSyncNow: () -> Unit = {},
    val onTurnOff: () -> Unit = {},
    val onOpenReview: () -> Unit = {},
    val onUploadAgain: () -> Unit = {},
    val onStartSyncingAccount: (String) -> Unit = {},
    val onLinkAccount: (String, String) -> Unit = { _, _ -> },
    val onAddCloudAccount: (SetupService) -> Unit = {},
    val onRequestDelete: () -> Unit = {},
    val onOpenLocation: () -> Unit = {},
    val onOpenFiles: () -> Unit = {},
    val onOpenAccount: (CloudAccountRef) -> Unit = {},
    val onOpenGlobal: () -> Unit = {},
    val onExport: () -> Unit = {},
    val onImport: () -> Unit = {},
    val onTransfer: (TransferArgs) -> Unit = {},
    val onRequestRemove: (CloudAccountRef) -> Unit = {},
    val onResumeAccount: (String) -> Unit = {},
    val onDismissMigrationNotice: () -> Unit = {},
    val onRequestChangedChoice: (String) -> Unit = {},
)

@Composable
internal fun CloudSyncOnContent(
    state: CloudSyncState,
    now: Long,
    turningOn: Boolean,
    deleting: Boolean,
    summary: CloudDataSummary?,
    files: List<CloudDeviceFiles>?,
    removing: Set<String>,
    preparingTransfer: String?,
    actions: CloudSyncOnActions,
) {
    val haptics = rememberYoinHaptics()
    Column(verticalArrangement = Arrangement.spacedBy(SyncGroupGap)) {
        SettingsSegments {
            item(key = "location") {
                LocationHero(
                    who = state.account?.email ?: state.account?.displayName ?: stringResource(R.string.settings_sync_google_account),
                    onChange = actions.onOpenLocation,
                )
            }
            item(key = "status") { SyncStatusRow(state = state, now = now, onSyncNow = actions.onSyncNow) }
            state.migratedNotice?.let { notice ->
                item(key = "migratedNotice") {
                    val targetName = if (notice.targetLocation == "icloud") {
                        stringResource(R.string.settings_sync_location_icloud)
                    } else {
                        notice.targetLocation
                    }
                    val body = if (notice.deviceName != null) {
                        stringResource(R.string.settings_sync_migrated_notice_device, notice.deviceName, targetName)
                    } else {
                        stringResource(R.string.settings_sync_migrated_notice_other, targetName)
                    }
                    ActionPanel(text = body) {
                        OutlinedButton(
                            onClick = {
                                haptics.performClick()
                                actions.onDismissMigrationNotice()
                            },
                            modifier = Modifier.handPointer(),
                        ) { Text(stringResource(R.string.settings_sync_migrated_notice_dismiss)) }
                    }
                }
            }
            when (val phase = state.phase) {
                CloudSyncPhase.NeedsReauth -> item(key = "reauth") {
                    ActionPanel(text = stringResource(R.string.settings_sync_reauth_body)) {
                        Button(
                            onClick = {
                                haptics.performConfirm()
                                actions.onTurnOn()
                            },
                            enabled = !turningOn,
                            modifier = Modifier.handPointer(enabled = !turningOn),
                        ) { Text(stringResource(R.string.settings_sync_reconnect)) }
                    }
                }
                is CloudSyncPhase.NeedsReview -> item(key = "review") {
                    SettingsItem(
                        icon = YoinSymbols.Note,
                        title = pluralStringResource(R.plurals.settings_sync_review_row_title, phase.count, phase.count),
                        summary = stringResource(R.string.settings_sync_review_summary, phase.accountName),
                        onClick = actions.onOpenReview,
                        trailing = { Chevron() },
                    )
                }
                CloudSyncPhase.CloudCopyRemoved -> item(key = "copyRemoved") {
                    ActionPanel(text = stringResource(R.string.settings_sync_copy_removed_body)) {
                        Button(
                            onClick = {
                                haptics.performConfirm()
                                actions.onUploadAgain()
                            },
                            modifier = Modifier.handPointer(),
                        ) { Text(stringResource(R.string.settings_sync_upload_again)) }
                        OutlinedButton(
                            onClick = {
                                haptics.performClick()
                                actions.onTurnOff()
                            },
                            modifier = Modifier.handPointer(),
                        ) { Text(stringResource(R.string.settings_sync_turn_off_short)) }
                    }
                }
                else -> Unit
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsGroup(
                title = stringResource(R.string.settings_sync_section_cloud_data),
                trailingLabel = stringResource(R.string.settings_sync_cloud_data_where),
            ) {
                item(key = "files") {
                    val fileCount = files?.sumOf { it.files.size }
                    val bytes = files?.sumOf { it.totalBytes }
                    val devices = files?.size ?: state.devices.size
                    SettingsItem(
                        icon = YoinSymbols.Database,
                        title = stringResource(R.string.settings_sync_all_files),
                        summaryContent = {
                            MetaLine(
                                groups = listOfNotNull(
                                    fileCount?.let { stat(it, R.plurals.settings_sync_unit_files) },
                                    bytes?.let { sizeStat(it) },
                                    stat(devices, R.plurals.settings_sync_unit_devices),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        },
                        onClick = actions.onOpenFiles,
                        trailing = { Chevron() },
                        modifier = Modifier.testTag("cloud_sync_all_files"),
                    )
                }
                cloudAccountsOf(state).forEach { account ->
                    item(key = "acct-${account.syncProfileId}") {
                        val local = account.localProfileId?.let { id -> state.accounts.firstOrNull { it.localProfileId == id } }
                        CloudAccountCard(
                            account = account,
                            counts = summary?.scopes?.get(account.syncProfileId) ?: summary?.let { CloudScopeCounts() },
                            paused = local?.status == SyncAccountStatus.AccountChanged,
                            busy = account.syncProfileId in removing || (local != null && preparingTransfer == local.localProfileId),
                            linkTargets = if (account.cloudOnly) {
                                state.accounts.filter { row ->
                                    val status = row.status
                                    status is SyncAccountStatus.NotSynced &&
                                        status.linkCandidates.any { it.syncProfileId == account.syncProfileId }
                                }
                            } else {
                                emptyList()
                            },
                            actions = actions,
                        )
                    }
                }
                item(key = "global") {
                    val global = summary?.global
                    SettingsItem(
                        icon = YoinSymbols.Library,
                        title = stringResource(R.string.settings_sync_global_data),
                        summaryContent = {
                            if (global != null) {
                                MetaFlow(
                                    groups = listOf(
                                        stat(global.playlistBackups, R.plurals.settings_sync_unit_backups),
                                        stat(global.translations, R.plurals.settings_sync_unit_translations),
                                        stat(global.songAbout, R.plurals.settings_sync_unit_about),
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        },
                        onClick = actions.onOpenGlobal,
                        trailing = { Chevron() },
                    )
                }
            }
            SyncNote(icon = YoinSymbols.Lock, text = stringResource(R.string.settings_sync_privacy_footer))
        }

        val notSyncing = notSyncingOf(state)
        if (notSyncing.isNotEmpty()) {
            SettingsGroup(title = stringResource(R.string.settings_sync_section_not_syncing)) {
                notSyncing.forEach { account ->
                    item(key = "local-${account.localProfileId}") {
                        NotSyncingRow(
                            account = account,
                            onResume = actions.onResumeAccount,
                            onStartSyncing = actions.onStartSyncingAccount,
                            onLink = actions.onLinkAccount,
                        )
                    }
                }
            }
        }

        BackupGroup(actions)

        SettingsGroup(title = stringResource(R.string.settings_sync_section_manage)) {
            item(key = "turnOff") {
                SettingsItem(
                    icon = YoinSymbols.CloudOffline,
                    title = stringResource(R.string.settings_sync_turn_off_title),
                    summary = stringResource(R.string.settings_sync_turn_off_summary),
                    onClick = actions.onTurnOff,
                )
            }
            item(key = "delete") {
                DestructiveItem(
                    icon = YoinSymbols.Delete,
                    title = stringResource(R.string.settings_sync_delete_title),
                    summary = if (deleting) stringResource(R.string.settings_sync_deleting) else null,
                    enabled = !deleting,
                    onClick = actions.onRequestDelete,
                )
            }
        }
    }
}

/** 备份与迁移: download everything, import a backup, transfer Memories. */
@Composable
internal fun BackupGroup(
    actions: CloudSyncOnActions,
    includeTransfer: Boolean = true,
    titleRes: Int = R.string.settings_sync_section_backup,
    // Sync off: the export is this device's data, not the cloud's.
    exportTitleRes: Int = R.string.settings_sync_export_title,
) {
    SettingsGroup(title = stringResource(titleRes)) {
        run {
            item(key = "export") {
                SettingsItem(
                    icon = YoinSymbols.Download,
                    title = stringResource(exportTitleRes),
                    summary = stringResource(R.string.settings_sync_export_summary),
                    onClick = actions.onExport,
                    trailing = { Chevron() },
                    modifier = Modifier.testTag("cloud_sync_export"),
                )
            }
        }
        item(key = "import") {
            SettingsItem(
                icon = YoinSymbols.Folder,
                title = stringResource(R.string.settings_sync_import_title),
                summary = stringResource(R.string.settings_sync_import_summary),
                onClick = actions.onImport,
                trailing = { Chevron() },
            )
        }
        if (includeTransfer) {
            item(key = "transfer") {
                SettingsItem(
                    icon = YoinSymbols.Forward,
                    title = stringResource(R.string.settings_xfer_title),
                    summary = stringResource(R.string.settings_xfer_summary),
                    onClick = { actions.onTransfer(TransferArgs()) },
                    trailing = { Chevron() },
                )
            }
        }
    }
}

@Composable
internal fun Chevron() {
    Icon(imageVector = YoinSymbols.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** 同步位置: the location's mark on a cookie, its name and account, and 更改. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LocationHero(who: String, onChange: () -> Unit) {
    val haptics = rememberYoinHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp)
            .padding(start = 16.dp, top = 16.dp, end = 12.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, MaterialShapes.Cookie9Sided.toShape()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                YoinSymbols.Cloud,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(26.dp),
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.settings_sync_location_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.settings_sync_google_drive),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            MetaLine(groups = listOf(MetaGroup.Plain(who)), style = MaterialTheme.typography.bodyMedium)
        }
        FilledTonalButton(
            onClick = {
                haptics.performClick()
                onChange()
            },
            shapes = ButtonDefaults.shapes(),
            contentPadding = ButtonDefaults.SmallContentPadding,
            modifier = Modifier.handPointer(),
        ) {
            Text(stringResource(R.string.settings_sync_location_change))
            Icon(YoinSymbols.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp))
        }
    }
}

/** Where sync stands, when it last ran, the last notable result, and 立即同步. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SyncStatusRow(state: CloudSyncState, now: Long, onSyncNow: () -> Unit) {
    val text = cloudSyncStatusText(state, now, LocalContext.current.resources)
    val syncing = state.phase == CloudSyncPhase.Syncing
    val problem = when (state.phase) {
        CloudSyncPhase.UpToDate, CloudSyncPhase.Syncing, CloudSyncPhase.Offline, is CloudSyncPhase.NeedsReview,
        is CloudSyncPhase.UpdateRequired,
        -> false
        else -> true
    }
    val haptics = rememberYoinHaptics()
    val tint by animateColorAsState(
        targetValue = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = YoinMotion.effectsSpring(),
        label = "syncStatusTint",
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = SettingsRowMinHeight)
                .padding(start = 16.dp, top = 14.dp, end = 12.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                AnimatedContent(
                    targetState = syncing,
                    transitionSpec = {
                        YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                            YoinMotion.fadeOut(role = YoinMotionRole.Standard)
                    },
                    contentAlignment = Alignment.Center,
                    label = "syncStatusIcon",
                ) { busy ->
                    if (busy) {
                        YoinLoadingIndicator(size = 32.dp)
                    } else {
                        Icon(
                            imageVector = statusIconOf(state),
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .animateContentSize(YoinMotion.spatialSpring()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(text.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                text.lines.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.lastSummary?.let { summary ->
                    Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            Button(
                onClick = {
                    haptics.performClick()
                    onSyncNow()
                },
                enabled = !syncing,
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.SmallContentPadding,
                modifier = Modifier.handPointer(enabled = !syncing),
            ) {
                Icon(YoinSymbols.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.settings_sync_sync_now))
            }
        }
    }
}

private fun statusIconOf(state: CloudSyncState): ImageVector = when (state.phase) {
    CloudSyncPhase.UpToDate -> if (state.pendingChanges) YoinSymbols.Cloud else YoinSymbols.Check
    CloudSyncPhase.Offline -> YoinSymbols.CloudOffline
    CloudSyncPhase.StorageFull -> YoinSymbols.Storage
    CloudSyncPhase.CloudCopyRemoved -> YoinSymbols.CloudOffline
    is CloudSyncPhase.NeedsReview, is CloudSyncPhase.UpdateRequired -> YoinSymbols.Info
    else -> YoinSymbols.Error
}

/** A problem that needs a decision: what happened, then the buttons. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ActionPanel(text: String, actions: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = SettingsRowTextInset, end = 16.dp, top = 14.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
    }
}

/**
 * An account in the cloud: its service glyph, name (仅在云端 when it isn't on this device), service line and
 * counts; tap opens its data, ⋮ offers 转移 Memories and 从云端移除. Cloud-only accounts carry their two
 * buttons under the row; a paused one (signed in as someone else) asks what to do.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CloudAccountCard(
    account: CloudAccountRef,
    counts: CloudScopeCounts?,
    paused: Boolean,
    busy: Boolean,
    linkTargets: List<SyncAccountRow>,
    actions: CloudSyncOnActions,
) {
    val haptics = rememberYoinHaptics()
    var menuOpen by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().animateContentSize(YoinMotion.spatialSpring())) {
        Surface(
            onClick = {
                haptics.performClick()
                actions.onOpenAccount(account)
            },
            shape = LocalSettingsRowShape.current,
            color = androidx.compose.ui.graphics.Color.Transparent,
            modifier = Modifier.fillMaxWidth().handPointer(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = SettingsRowMinHeight)
                    .padding(start = 16.dp, top = 14.dp, end = 4.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AccountGlyph(account.provider)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    TitleWithPill(
                        title = account.displayName,
                        pill = if (account.cloudOnly) {
                            { SyncPill(stringResource(R.string.settings_sync_cloud_only)) }
                        } else {
                            null
                        },
                    )
                    val line = accountLineGroups(account.provider, account.displayName, account.hint, account.fromDevice)
                    if (line.isNotEmpty()) MetaLine(groups = line, style = MaterialTheme.typography.bodyMedium)
                    if (counts != null) {
                        val stats = scopeStatGroups(counts)
                        if (stats.isEmpty()) {
                            Text(
                                stringResource(R.string.settings_sync_no_data),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        } else {
                            MetaFlow(groups = stats, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (paused) {
                        Text(
                            stringResource(R.string.settings_sync_account_changed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }
                if (busy) {
                    YoinLoadingIndicator(size = 32.dp)
                } else {
                    Box {
                        IconButton(
                            onClick = {
                                haptics.performTick()
                                menuOpen = true
                            },
                            modifier = Modifier.handPointer(),
                        ) {
                            Icon(
                                YoinSymbols.MoreVertical,
                                contentDescription = stringResource(R.string.settings_sync_cd_account_options, account.displayName),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        YoinDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            YoinDropdownMenuItem(
                                text = stringResource(R.string.settings_sync_menu_view),
                                leadingIcon = { Icon(YoinSymbols.Visibility, contentDescription = null) },
                                onClick = {
                                    haptics.performContextClick()
                                    menuOpen = false
                                    actions.onOpenAccount(account)
                                },
                            )
                            YoinDropdownMenuItem(
                                text = stringResource(R.string.settings_sync_menu_transfer),
                                leadingIcon = { Icon(YoinSymbols.Forward, contentDescription = null) },
                                onClick = {
                                    haptics.performContextClick()
                                    menuOpen = false
                                    actions.onTransfer(transferArgsFor(account))
                                },
                            )
                            YoinDropdownMenuItem(
                                text = stringResource(R.string.settings_sync_menu_remove),
                                leadingIcon = {
                                    Icon(YoinSymbols.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                },
                                onClick = {
                                    haptics.performContextClick()
                                    menuOpen = false
                                    actions.onRequestRemove(account)
                                },
                            )
                        }
                    }
                }
            }
        }
        val service = SetupService.entries.firstOrNull { it.key == account.provider }
        if (account.cloudOnly || paused) {
            FlowRow(
                modifier = Modifier.padding(start = SettingsRowTextInset, end = 16.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (paused) {
                    FilledTonalButton(
                        onClick = {
                            haptics.performClick()
                            account.localProfileId?.let(actions.onRequestChangedChoice)
                        },
                        enabled = !busy,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.handPointer(enabled = !busy),
                    ) { Text(stringResource(R.string.settings_sync_changed_choose)) }
                } else {
                    FilledTonalButton(
                        onClick = {
                            haptics.performClick()
                            actions.onTransfer(transferArgsFor(account))
                        },
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.handPointer(),
                    ) {
                        Icon(YoinSymbols.Forward, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.settings_xfer_title))
                    }
                    val single = linkTargets.singleOrNull()
                    when {
                        single != null -> OutlinedButton(
                            onClick = {
                                haptics.performConfirm()
                                actions.onLinkAccount(single.localProfileId, account.syncProfileId)
                            },
                            shapes = ButtonDefaults.shapes(),
                            modifier = Modifier.handPointer(),
                        ) { Text(stringResource(R.string.settings_sync_link_to, single.displayName)) }
                        service != null -> OutlinedButton(
                            onClick = {
                                haptics.performClick()
                                actions.onAddCloudAccount(service)
                            },
                            shapes = ButtonDefaults.shapes(),
                            modifier = Modifier.handPointer(),
                        ) { Text(stringResource(R.string.settings_sync_add_to_device)) }
                    }
                }
            }
        }
    }
}

internal fun transferArgsFor(account: CloudAccountRef): TransferArgs = if (account.localProfileId != null && !account.cloudOnly) {
    TransferArgs(sourceProfileId = account.localProfileId)
} else {
    TransferArgs(sourceCloudScope = account.syncProfileId)
}

@Composable
private fun notSyncingText(status: SyncAccountStatus): String = when (status) {
    SyncAccountStatus.Syncing -> stringResource(R.string.settings_sync_account_syncing)
    SyncAccountStatus.NeedsIdentity -> stringResource(R.string.settings_sync_account_needs_identity)
    SyncAccountStatus.NeedsReconnect -> stringResource(R.string.settings_sync_account_reconnect)
    is SyncAccountStatus.DuplicateOf -> stringResource(R.string.settings_sync_account_duplicate, status.otherDisplayName)
    is SyncAccountStatus.NotSynced -> stringResource(R.string.settings_sync_account_not_synced)
    SyncAccountStatus.AccountChanged -> stringResource(R.string.settings_sync_account_changed)
    SyncAccountStatus.RemovedFromCloud -> stringResource(R.string.settings_sync_account_removed_from_cloud)
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NotSyncingRow(
    account: SyncAccountRow,
    onResume: (String) -> Unit,
    onStartSyncing: (String) -> Unit,
    onLink: (String, String) -> Unit,
) {
    val haptics = rememberYoinHaptics()
    val status = account.status
    Column(modifier = Modifier.fillMaxWidth().animateContentSize(YoinMotion.spatialSpring())) {
        SettingsItem(
            icon = identityOf(account.provider)?.glyph ?: YoinSymbols.Person,
            iconTone = identityOf(account.provider)?.hue?.tone(),
            title = account.displayName,
            summaryContent = {
                val line = accountLineGroups(account.provider, account.displayName, null, null)
                if (line.isNotEmpty()) MetaLine(groups = line, style = MaterialTheme.typography.bodyMedium)
                Text(
                    notSyncingText(status),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            },
        )
        val hasActions = status == SyncAccountStatus.RemovedFromCloud || status is SyncAccountStatus.NotSynced
        if (hasActions) {
            FlowRow(
                modifier = Modifier.padding(start = SettingsRowTextInset, end = 16.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (status) {
                    SyncAccountStatus.RemovedFromCloud -> FilledTonalButton(
                        onClick = {
                            haptics.performConfirm()
                            onResume(account.localProfileId)
                        },
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.handPointer(),
                    ) { Text(stringResource(R.string.settings_sync_resume)) }
                    is SyncAccountStatus.NotSynced -> {
                        FilledTonalButton(
                            onClick = {
                                haptics.performConfirm()
                                onStartSyncing(account.localProfileId)
                            },
                            shapes = ButtonDefaults.shapes(),
                            modifier = Modifier.handPointer(),
                        ) { Text(stringResource(R.string.settings_sync_start_syncing)) }
                        LinkButton(
                            candidates = status.linkCandidates,
                            onLink = { cloud -> onLink(account.localProfileId, cloud.syncProfileId) },
                        )
                    }
                    else -> Unit
                }
            }
        }
    }
}

/** "Link to <account>" for one candidate; "Link…" with a menu for several. */
@Composable
private fun LinkButton(candidates: List<CloudAccountRow>, onLink: (CloudAccountRow) -> Unit) {
    if (candidates.isEmpty()) return
    val haptics = rememberYoinHaptics()
    val single = candidates.singleOrNull()
    if (single != null) {
        OutlinedButton(
            onClick = {
                haptics.performConfirm()
                onLink(single)
            },
            modifier = Modifier.handPointer(),
        ) { Text(stringResource(R.string.settings_sync_link_to, linkLabel(single))) }
        return
    }
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = {
                haptics.performTick()
                menuOpen = true
            },
            modifier = Modifier.handPointer(),
        ) { Text(stringResource(R.string.settings_sync_link_menu)) }
        YoinDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            candidates.forEach { candidate ->
                YoinDropdownMenuItem(
                    text = linkLabel(candidate),
                    onClick = {
                        haptics.performContextClick()
                        menuOpen = false
                        onLink(candidate)
                    },
                )
            }
        }
    }
}

/** "Apple Music from Pixel 9": Apple accounts are usually all called "Apple Music". */
@Composable
private fun linkLabel(candidate: CloudAccountRow): String {
    val device = candidate.fromDeviceName
    return if (device.isNullOrBlank()) {
        candidate.displayName
    } else {
        stringResource(R.string.settings_sync_link_from, candidate.displayName, device)
    }
}

/** A settings row whose action can't be undone: icon and title in the error colour. */
@Composable
internal fun DestructiveItem(
    icon: ImageVector,
    title: String,
    summary: String?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val haptics = rememberYoinHaptics()
    val color by animateColorAsState(
        targetValue = if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        animationSpec = YoinMotion.effectsSpring(),
        label = "destructiveRowColor",
    )
    Surface(
        onClick = {
            haptics.performClick()
            onClick()
        },
        enabled = enabled,
        shape = LocalSettingsRowShape.current,
        color = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.fillMaxWidth().handPointer(enabled = enabled),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium, color = color)
                if (summary != null) {
                    Text(text = summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ── Dialogs ──────────────────────────────────────────────────────────

/** 从云端移除: what goes (the account's counts), what happens on other devices, what stays. */
@Composable
internal fun RemoveFromCloudDialog(
    account: CloudAccountRef,
    counts: CloudScopeCounts?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val haptics = rememberYoinHaptics()
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(YoinSymbols.Delete, contentDescription = null) },
        title = { Text(stringResource(R.string.settings_sync_remove_title, account.displayName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(
                        R.string.settings_sync_remove_body,
                        stringResource(R.string.settings_sync_google_drive),
                        countsSentence(counts),
                    ),
                )
                Text(
                    when {
                        !account.cloudOnly -> stringResource(R.string.settings_sync_remove_body_local)
                        !account.fromDevice.isNullOrBlank() ->
                            stringResource(R.string.settings_sync_remove_body_cloud_device, account.fromDevice)
                        else -> stringResource(R.string.settings_sync_remove_body_cloud)
                    },
                )
                Text(stringResource(R.string.settings_sync_remove_body_transferred))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    haptics.performReject()
                    onConfirm()
                },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.handPointer(),
            ) { Text(stringResource(R.string.settings_sync_remove_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.handPointer()) {
                Text(stringResource(R.string.settings_sync_remove_cancel))
            }
        },
    )
}

/**
 * Signed in as a different account: keep syncing as the previous account, start fresh, or start fresh and copy
 * the previous account's Memories over.
 */
@Composable
internal fun AccountChangedDialog(
    account: SyncAccountRow,
    onDismiss: () -> Unit,
    onKeep: () -> Unit,
    onFresh: () -> Unit,
    onTransfer: () -> Unit,
) {
    val haptics = rememberYoinHaptics()
    val serviceName = identityOf(account.provider)?.let { stringResource(it.nameRes) } ?: account.displayName
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(YoinSymbols.PersonCheck, contentDescription = null) },
        title = { Text(stringResource(R.string.settings_sync_changed_title, serviceName)) },
        text = {
            // Long copy on a small phone: the choices scroll inside the dialog.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.settings_sync_changed_body, account.displayName))
                ChoiceCard(
                    icon = YoinSymbols.History,
                    title = stringResource(R.string.settings_sync_keep_before),
                    summary = stringResource(R.string.settings_sync_changed_keep_summary),
                    onClick = {
                        haptics.performConfirm()
                        onKeep()
                    },
                )
                ChoiceCard(
                    icon = YoinSymbols.Refresh,
                    title = stringResource(R.string.settings_sync_start_fresh),
                    summary = stringResource(R.string.settings_sync_changed_fresh_summary),
                    onClick = {
                        haptics.performClick()
                        onFresh()
                    },
                )
                ChoiceCard(
                    icon = YoinSymbols.Forward,
                    title = stringResource(R.string.settings_sync_changed_transfer),
                    summary = stringResource(R.string.settings_sync_changed_transfer_summary),
                    highlighted = true,
                    onClick = {
                        haptics.performConfirm()
                        onTransfer()
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.handPointer()) {
                Text(stringResource(R.string.settings_sync_changed_later))
            }
        },
    )
}

@Composable
private fun ChoiceCard(icon: ImageVector, title: String, summary: String, onClick: () -> Unit, highlighted: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (highlighted) colors.primaryContainer else colors.surfaceContainerHigh,
        contentColor = if (highlighted) colors.onPrimaryContainer else colors.onSurface,
        modifier = Modifier.fillMaxWidth().handPointer(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(top = 2.dp).size(22.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                TitleWithPill(
                    title = title,
                    pill = if (highlighted) {
                        { SyncPill(stringResource(R.string.settings_sync_new_badge), SyncPillTone.Accent) }
                    } else {
                        null
                    },
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                    color = androidx.compose.material3.LocalContentColor.current,
                )
                Text(summary, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
