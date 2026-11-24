package com.gpo.yoin.ui.settings.sync

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.data.sync.SyncKinds
import com.gpo.yoin.shared.portability.BackupFormat
import com.gpo.yoin.shared.portability.ImportAccountPlan
import com.gpo.yoin.shared.portability.ImportTarget
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.MetaFlow
import com.gpo.yoin.ui.component.MetaGroup
import com.gpo.yoin.ui.component.MetaLine
import com.gpo.yoin.ui.component.handPointer
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.settings.SettingsGroup
import com.gpo.yoin.ui.settings.SettingsItem
import com.gpo.yoin.ui.settings.SettingsRowTextInset
import com.gpo.yoin.ui.settings.SettingsSegments
import com.gpo.yoin.ui.settings.backup.CountTile
import com.gpo.yoin.ui.settings.tone
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

// ── 同步位置 ───────────────────────────────────────────────────────────

/** Where sync lives: Google Drive (current); iCloud and Lyra listed, not yet selectable. */
@Composable
internal fun SyncLocationPage(account: String?, onBack: () -> Unit, snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }) {
    val scope = rememberCoroutineScope()
    val haptics = rememberYoinHaptics()
    val icloudSoon = stringResource(R.string.settings_sync_location_icloud_soon)
    val lyraSoon = stringResource(R.string.settings_sync_location_lyra_soon)
    SyncPage(
        barTitle = stringResource(R.string.settings_sync_location_label),
        onBack = onBack,
        crumb = stringResource(R.string.settings_sync_crumb_location),
        headline = stringResource(R.string.settings_sync_location_label),
        lead = stringResource(R.string.settings_sync_location_lead),
        snackbarHostState = snackbarHostState,
        footer = {
            FlowButtons(primary = stringResource(R.string.settings_sync_location_is_current), onPrimary = {}, primaryEnabled = false)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SyncGroupGap)) {
            SettingsGroup(title = stringResource(R.string.settings_sync_location_section)) {
                item(key = "drive") {
                    LocationOption(
                        icon = YoinSymbols.Cloud,
                        title = stringResource(R.string.settings_sync_google_drive),
                        meta = listOfNotNull(account?.let { MetaGroup.Plain(it) }),
                        pill = { SyncPill(stringResource(R.string.settings_sync_location_current), SyncPillTone.Primary) },
                        selected = true,
                    )
                }
                item(key = "icloud") {
                    LocationOption(
                        icon = YoinSymbols.Cloud,
                        title = stringResource(R.string.settings_sync_location_icloud),
                        meta = listOf(
                            MetaGroup.Plain(stringResource(R.string.settings_sync_location_cloudkit)),
                            MetaGroup.Plain(stringResource(R.string.settings_sync_location_apple_devices)),
                        ),
                        pill = { SyncPill(stringResource(R.string.settings_sync_location_apple_only), SyncPillTone.Muted) },
                        selected = false,
                        enabled = false,
                        onClick = {
                            haptics.performReject()
                            scope.launch { snackbarHostState.showSnackbar(icloudSoon) }
                        },
                    )
                }
                item(key = "lyra") {
                    LocationOption(
                        icon = YoinSymbols.Storage,
                        title = stringResource(R.string.settings_sync_location_lyra),
                        meta = listOf(MetaGroup.Plain(stringResource(R.string.settings_sync_location_lyra_summary))),
                        pill = { SyncPill(stringResource(R.string.settings_sync_location_soon), SyncPillTone.Muted) },
                        selected = false,
                        enabled = false,
                        onClick = {
                            haptics.performReject()
                            scope.launch { snackbarHostState.showSnackbar(lyraSoon) }
                        },
                    )
                }
            }
            SettingsGroup(title = stringResource(R.string.settings_sync_location_steps)) {
                listOf(
                    R.string.settings_sync_location_step1 to R.string.settings_sync_location_step1_summary,
                    R.string.settings_sync_location_step2 to R.string.settings_sync_location_step2_summary,
                    R.string.settings_sync_location_step3 to R.string.settings_sync_location_step3_summary,
                    R.string.settings_sync_location_step4 to R.string.settings_sync_location_step4_summary,
                ).forEachIndexed { index, (title, summary) ->
                    item(key = "step$index") { NumberedRow(index + 1, stringResource(title), stringResource(summary)) }
                }
            }
            SyncNote(YoinSymbols.Info, stringResource(R.string.settings_sync_location_info))
        }
    }
}

@Composable
private fun LocationOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    meta: List<MetaGroup>,
    pill: @Composable () -> Unit,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val row: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            com.gpo.yoin.ui.settings.SettingsRowIcon(icon)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // The badge sits by the name, as on the account and device rows.
                TitleWithPill(title = title, pill = pill)
                if (meta.isNotEmpty()) MetaLine(groups = meta, style = MaterialTheme.typography.bodyMedium)
            }
            RadioMark(selected = selected, enabled = enabled)
        }
    }
    if (onClick == null) {
        Box(modifier = Modifier.fillMaxWidth()) { row() }
    } else {
        androidx.compose.material3.Surface(
            onClick = onClick,
            shape = com.gpo.yoin.ui.settings.LocalSettingsRowShape.current,
            color = androidx.compose.ui.graphics.Color.Transparent,
            modifier = Modifier.fillMaxWidth().handPointer(),
        ) { row() }
    }
}

@Composable
private fun RadioMark(selected: Boolean, enabled: Boolean) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(22.dp)
            .background(if (selected) colors.primary else colors.outlineVariant.copy(alpha = if (enabled) 1f else 0.5f), CircleShape)
            .padding(if (selected) 6.dp else 2.dp)
            .background(if (selected) colors.onPrimary else colors.surfaceBright, CircleShape),
    )
}

@Composable
private fun NumberedRow(number: Int, title: String, summary: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(number.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ── export / import ───────────────────────────────────────────────────

@Composable
internal fun BackupPage(
    state: BackupUiState,
    onBack: () -> Unit,
    onChoose: (scope: String, target: ImportTarget) -> Unit,
    onConfirmImport: () -> Unit,
    onDone: () -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val barTitle = when (state) {
        is BackupUiState.Exported -> stringResource(R.string.settings_sync_export_done_bar)
        is BackupUiState.Exporting, is BackupUiState.ExportFailed -> stringResource(R.string.settings_sync_export_running)
        else -> stringResource(R.string.settings_sync_import_title)
    }
    SyncPage(
        barTitle = barTitle,
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        footer = when (state) {
            is BackupUiState.Review -> {
                {
                    FlowButtons(
                        primary = stringResource(R.string.settings_sync_import_confirm),
                        onPrimary = onConfirmImport,
                        primaryEnabled = state.canImport,
                        secondary = stringResource(R.string.settings_sync_import_cancel),
                        onSecondary = onDone,
                    )
                }
            }
            is BackupUiState.Exported, is BackupUiState.ExportFailed, is BackupUiState.ReadFailed,
            is BackupUiState.Imported, is BackupUiState.ImportFailed,
            -> {
                { FlowButtons(primary = stringResource(R.string.settings_sync_done), onPrimary = onDone) }
            }
            else -> null
        },
    ) {
        AnimatedContent(
            targetState = state,
            contentKey = { it::class },
            transitionSpec = {
                YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith YoinMotion.fadeOut(role = YoinMotionRole.Standard)
            },
            label = "backupStep",
        ) { current ->
            Column {
                when (current) {
                    BackupUiState.Idle -> ProgressHero(progress = null)
                    is BackupUiState.Exporting -> {
                        SyncHeadline(
                            crumb = null,
                            headline = stringResource(R.string.settings_sync_export_running),
                            lead = stringResource(
                                if (current.fromCloud) {
                                    R.string.settings_sync_export_running_summary
                                } else {
                                    R.string.settings_sync_export_running_summary_local
                                },
                            ),
                            small = true,
                        )
                        ProgressHero(progress = null)
                    }
                    is BackupUiState.Exported -> ExportedContent(current)
                    is BackupUiState.ExportFailed -> OutcomeHero(
                        icon = YoinSymbols.Close,
                        title = stringResource(R.string.settings_sync_export_failed),
                        body = stringResource(R.string.settings_sync_export_failed_summary),
                        failed = true,
                    )
                    is BackupUiState.Reading -> {
                        SyncHeadline(crumb = null, headline = stringResource(R.string.settings_sync_import_reading), lead = current.fileName, small = true)
                        ProgressHero(progress = null)
                    }
                    is BackupUiState.ReadFailed -> OutcomeHero(
                        icon = YoinSymbols.Close,
                        title = stringResource(
                            when (current.reason) {
                                BackupReadFailure.NotABackup -> R.string.settings_sync_read_not_backup
                                BackupReadFailure.TooNew -> R.string.settings_sync_read_too_new
                                BackupReadFailure.Checksum -> R.string.settings_sync_read_checksum
                                BackupReadFailure.Unreadable -> R.string.settings_sync_read_failed
                            },
                        ),
                        body = current.fileName,
                        failed = true,
                    )
                    is BackupUiState.Review -> ImportReview(current, onChoose)
                    is BackupUiState.Importing -> {
                        SyncHeadline(
                            crumb = null,
                            headline = stringResource(R.string.settings_sync_import_running),
                            lead = mergeTarget(current.review.intoCloud),
                            small = true,
                        )
                        ProgressHero(progress = null)
                    }
                    is BackupUiState.Imported -> ImportedContent(current)
                    is BackupUiState.ImportFailed -> OutcomeHero(
                        icon = YoinSymbols.Close,
                        title = stringResource(R.string.settings_sync_import_failed),
                        body = stringResource(R.string.settings_sync_import_failed_summary),
                        failed = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun mergeTarget(intoCloud: Boolean): String = stringResource(
    R.string.settings_sync_import_merge_into,
    stringResource(if (intoCloud) R.string.settings_sync_google_drive else R.string.settings_sync_import_this_device_inline),
)

@Composable
private fun ExportedContent(state: BackupUiState.Exported) {
    val manifest = state.summary.manifest
    val items = manifest.accounts.sumOf { account -> account.counts.values.sum() } + manifest.globalCounts.values.sum()
    Column(verticalArrangement = Arrangement.spacedBy(SyncGroupGap)) {
        OutcomeHero(
            icon = YoinSymbols.DownloadDone,
            title = stringResource(R.string.settings_sync_export_saved),
            body = state.fileName,
            extra = {
                MetaLine(
                    groups = listOfNotNull(
                        state.bytes?.let { sizeStat(it) },
                        stat(manifest.accounts.size, R.plurals.settings_sync_unit_accounts),
                        stat(items, R.plurals.settings_sync_unit_items),
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
        )
        SettingsGroup(title = stringResource(R.string.settings_sync_export_contents)) {
            item(key = "manifest") { TreeRow(BackupFormat.MANIFEST_PATH, stringResource(R.string.settings_sync_export_manifest), depth = 0) }
            item(key = "data") { TreeRow(BackupFormat.SNAPSHOT_PATH, stringResource(R.string.settings_sync_export_data), depth = 0) }
            item(key = "readable") {
                Column(modifier = Modifier.padding(bottom = 8.dp)) {
                    TreeRow(BackupFormat.READABLE_DIR, stringResource(R.string.settings_sync_export_readable), depth = 0, folder = true)
                    // readableDir is the zip path ("readable/<account>/"): show the folder under readable/.
                    manifest.accounts.mapNotNull { it.readableDir }.distinct().forEach { dir ->
                        TreeRow(dir.removePrefix(BackupFormat.READABLE_DIR).trimEnd('/') + "/", null, depth = 1, folder = true)
                    }
                }
            }
        }
        SettingsGroup(title = stringResource(R.string.settings_sync_export_about)) {
            item {
                SettingsItem(
                    icon = YoinSymbols.CheckCircle,
                    title = stringResource(R.string.settings_sync_export_lossless),
                    summary = stringResource(R.string.settings_sync_export_lossless_summary),
                )
            }
            item {
                SettingsItem(
                    icon = YoinSymbols.Article,
                    title = stringResource(R.string.settings_sync_export_offline),
                    summary = stringResource(R.string.settings_sync_export_offline_summary),
                )
            }
            item {
                SettingsItem(
                    icon = YoinSymbols.Lock,
                    title = stringResource(R.string.settings_sync_export_private),
                    summary = stringResource(R.string.settings_sync_export_private_summary),
                )
            }
        }
        if (state.summary.readFailures.isNotEmpty()) {
            SyncNote(
                YoinSymbols.Info,
                stringResource(R.string.settings_sync_export_read_failures, kindLabels(state.summary.readFailures)),
            )
        }
    }
}

@Composable
private fun TreeRow(name: String, note: String?, depth: Int, folder: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp + 24.dp * depth, end = 16.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (folder) YoinSymbols.Folder else YoinSymbols.Article,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurface)
            if (note != null) {
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Kinds as the person knows them ("Notes, Ratings"). */
@Composable
private fun kindLabels(kinds: Collection<String>): String = joinedList(
    kinds.map { kind ->
        when (kind) {
            SyncKinds.SONG_NOTE, SyncKinds.ALBUM_NOTE -> R.string.settings_sync_kind_notes
            SyncKinds.TRACK_RATING, SyncKinds.ALBUM_RATING -> R.string.settings_sync_kind_ratings
            SyncKinds.ALBUM_REVIEW -> R.string.settings_sync_kind_reviews
            SyncKinds.MEMORY_TITLE, SyncKinds.ALBUM_TITLE, SyncKinds.MEMORY_COPY -> R.string.settings_sync_kind_titles
            else -> R.string.settings_sync_kind_other
        }
    }.distinct().map { stringResource(it) },
)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ImportReview(state: BackupUiState.Review, onChoose: (String, ImportTarget) -> Unit) {
    val manifest = state.plan.backup.manifest
    val resources = androidx.compose.ui.platform.LocalContext.current.resources
    Column(verticalArrangement = Arrangement.spacedBy(SyncGroupGap)) {
        SyncHeadline(crumb = null, headline = stringResource(R.string.settings_sync_import_review), lead = state.fileName, small = true)
        SettingsGroup(title = stringResource(R.string.settings_sync_import_info)) {
            item {
                SettingsItem(
                    icon = YoinSymbols.History,
                    title = DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(Date(manifest.exportedAt)),
                    summary = stringResource(R.string.settings_sync_import_exported),
                )
            }
            item {
                SettingsItem(
                    icon = YoinSymbols.Cloud,
                    title = stringResource(
                        if (manifest.source.location == BackupFormat.LOCATION_GOOGLE_DRIVE) {
                            R.string.settings_sync_google_drive
                        } else {
                            R.string.settings_sync_import_this_device
                        },
                    ),
                    summaryContent = {
                        MetaLine(groups = listOf(MetaGroup.Plain(manifest.source.deviceName, muted = true)), style = MaterialTheme.typography.bodyMedium)
                    },
                )
            }
            item {
                SettingsItem(
                    icon = YoinSymbols.CheckCircle,
                    title = stringResource(R.string.settings_sync_import_verified),
                    summary = stringResource(R.string.settings_sync_import_verified_summary),
                )
            }
        }
        SettingsGroup(title = mergeTarget(state.intoCloud)) {
            state.plan.accounts.forEach { account ->
                item(key = account.account.scope) {
                    ImportAccountRow(account, state.choices[account.account.scope] ?: account.target) { onChoose(account.account.scope, it) }
                }
            }
            if (state.plan.globalCounts.values.sum() > 0) {
                item(key = "global") {
                    SettingsItem(
                        icon = YoinSymbols.Library,
                        title = stringResource(R.string.settings_sync_import_global),
                        summaryContent = {
                            MetaFlow(groups = globalCountGroups(state.plan.globalCounts), style = MaterialTheme.typography.bodyMedium)
                        },
                    )
                }
            }
        }
        SettingsGroup(title = stringResource(R.string.settings_sync_import_rules)) {
            item {
                SettingsItem(
                    icon = YoinSymbols.Note,
                    title = stringResource(R.string.settings_sync_import_rule_add),
                    summary = stringResource(R.string.settings_sync_import_rule_add_summary),
                )
            }
            item {
                SettingsItem(
                    icon = YoinSymbols.Star,
                    title = stringResource(R.string.settings_sync_import_rule_rating),
                    summary = stringResource(R.string.settings_sync_import_rule_rating_summary),
                )
            }
            item {
                SettingsItem(
                    icon = YoinSymbols.Reviews,
                    title = stringResource(R.string.settings_sync_import_rule_review),
                    summary = stringResource(R.string.settings_sync_import_rule_review_summary),
                )
            }
        }
    }
}

/** An account's live record counts in the backup, in the page's words. */
@Composable
private fun accountCountGroups(counts: Map<String, Int>): List<MetaGroup> {
    val notes = (counts[SyncKinds.SONG_NOTE] ?: 0) + (counts[SyncKinds.ALBUM_NOTE] ?: 0)
    val albums = maxOf(counts[SyncKinds.ALBUM_RATING] ?: 0, counts[SyncKinds.ALBUM_REVIEW] ?: 0)
    val songs = counts[SyncKinds.TRACK_RATING] ?: 0
    val titles = (counts[SyncKinds.MEMORY_TITLE] ?: 0) + (counts[SyncKinds.ALBUM_TITLE] ?: 0)
    return scopeStatGroups(com.gpo.yoin.data.sync.CloudScopeCounts(notes, albums, songs, titles))
}

@Composable
private fun globalCountGroups(counts: Map<String, Int>): List<MetaGroup> = listOf(
    stat(counts[SyncKinds.PLAYLIST_BACKUP] ?: 0, R.plurals.settings_sync_unit_backups),
    stat(counts[SyncKinds.LYRICS_TRANSLATION] ?: 0, R.plurals.settings_sync_unit_translations),
    stat(counts[SyncKinds.SONG_ABOUT] ?: 0, R.plurals.settings_sync_unit_about),
)

/** One account of the backup: its counts and where it goes (asked when it isn't the same account here). */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ImportAccountRow(plan: ImportAccountPlan, choice: ImportTarget, onChoose: (ImportTarget) -> Unit) {
    val haptics = rememberYoinHaptics()
    val account = plan.account
    Column(modifier = Modifier.fillMaxWidth().animateContentSize(YoinMotion.spatialSpring())) {
        SettingsItem(
            icon = identityOf(account.provider)?.glyph ?: YoinSymbols.Person,
            iconTone = identityOf(account.provider)?.hue?.tone(),
            title = account.displayName,
            summaryContent = {
                val line = accountLineGroups(account.provider, account.displayName, account.hint, null)
                if (line.isNotEmpty()) MetaLine(groups = line, style = MaterialTheme.typography.bodyMedium)
                val counts = accountCountGroups(plan.counts)
                if (counts.isEmpty()) {
                    Text(
                        stringResource(R.string.settings_sync_no_data),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                } else {
                    MetaFlow(groups = counts, style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    when {
                        plan.sameAccount && choice != ImportTarget.Skip -> stringResource(R.string.settings_sync_import_same_account)
                        choice == ImportTarget.CloudOnly -> stringResource(R.string.settings_sync_import_cloud_only_note)
                        else -> stringResource(R.string.settings_sync_import_choose)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            },
        )
        // Every account can be left out (a backup may carry accounts this import shouldn't touch); one that is
        // the same account here offers only that and Skip.
        run {
            val options = plan.targetOptions()
            FlowRow(
                modifier = Modifier.padding(start = SettingsRowTextInset, end = 16.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                options.forEach { option ->
                    ToggleButton(
                        checked = option == choice,
                        onCheckedChange = {
                            haptics.performSegmentTick()
                            onChoose(option)
                        },
                        modifier = Modifier.handPointer(),
                    ) {
                        Text(
                            when (option) {
                                is ImportTarget.IntoProfile -> stringResource(
                                    R.string.settings_sync_import_into_profile,
                                    plan.options.firstOrNull { it.localProfileId == option.localProfileId }?.displayName.orEmpty(),
                                )
                                ImportTarget.CloudOnly -> stringResource(R.string.settings_sync_import_keep_cloud)
                                ImportTarget.Skip -> stringResource(R.string.settings_sync_import_skip)
                            },
                        )
                    }
                }
            }
            if (plan.wasPurged) {
                Text(
                    stringResource(R.string.settings_sync_import_purged_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = SettingsRowTextInset, end = 16.dp, bottom = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun ImportedContent(state: BackupUiState.Imported) {
    val result = state.result
    val all = result.byScope.values.fold(result.global) { acc, counts -> acc + counts }
    val written = all.written + all.keptBoth
    val skipped = all.unchanged + all.keptLocal + all.tombstones + all.skipped
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(SyncGroupGap)) {
        OutcomeHero(
            icon = YoinSymbols.Check,
            title = stringResource(R.string.settings_sync_import_done),
            body = if (state.review.intoCloud) {
                stringResource(R.string.settings_sync_import_done_summary, stringResource(R.string.settings_sync_google_drive))
            } else {
                stringResource(R.string.settings_sync_import_done_summary_local)
            },
            extra = {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    CountTile(written, stringResource(R.string.settings_sync_import_written), colors.primaryContainer, colors.onPrimaryContainer)
                    CountTile(skipped, stringResource(R.string.settings_sync_import_skipped), colors.surfaceContainerHigh, colors.onSurface)
                    if (result.cloudOnlyMerged > 0) {
                        CountTile(result.cloudOnlyMerged, stringResource(R.string.settings_sync_import_cloud), colors.surfaceContainerHigh, colors.onSurface)
                    }
                }
            },
        )
        val failed = all.failedKinds
        val refused = result.cloudOnlyRefused.mapNotNull { scope ->
            state.review.plan.accounts.firstOrNull { it.account.scope == scope }?.account?.displayName ?: scope
        }
        if (failed.isNotEmpty() || refused.isNotEmpty()) {
            SettingsSegments {
                if (failed.isNotEmpty()) {
                    item { SettingsItem(icon = YoinSymbols.Error, title = stringResource(R.string.settings_sync_import_failed_kinds, kindLabels(failed))) }
                }
                if (refused.isNotEmpty()) {
                    item { SettingsItem(icon = YoinSymbols.Info, title = stringResource(R.string.settings_sync_import_refused, joinedList(refused))) }
                }
            }
        }
    }
}
