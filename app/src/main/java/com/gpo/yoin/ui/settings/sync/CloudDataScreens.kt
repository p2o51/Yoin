package com.gpo.yoin.ui.settings.sync

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.data.sync.CloudAccountContent
import com.gpo.yoin.data.sync.CloudDeviceFiles
import com.gpo.yoin.data.sync.CloudFile
import com.gpo.yoin.data.sync.CloudGlobalContent
import com.gpo.yoin.data.sync.CloudScopeCounts
import com.gpo.yoin.data.sync.SyncFileProps
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.MetaGroup
import com.gpo.yoin.ui.component.MetaLine
import com.gpo.yoin.ui.component.YoinDropdownMenu
import com.gpo.yoin.ui.component.YoinDropdownMenuItem
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.component.formatTrackDurationMs
import com.gpo.yoin.ui.component.handPointer
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.settings.LocalSettingsRowShape
import com.gpo.yoin.ui.settings.SettingsGroup
import com.gpo.yoin.ui.settings.SettingsRowIcon
import com.gpo.yoin.ui.settings.SettingsRowMinHeight
import com.gpo.yoin.ui.settings.SettingsSegments
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import java.util.Locale

private const val DayMs = 24 * 60 * 60 * 1000L
private const val StaleDays = 30

// ── 全部文件 ───────────────────────────────────────────────────────────

@Composable
internal fun CloudFilesPage(
    files: CloudDataLoad<List<CloudDeviceFiles>>,
    now: Long,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onRemoveDevice: (CloudDeviceFiles) -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    var removeId by rememberSaveable { mutableStateOf<String?>(null) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    SyncPage(
        barTitle = stringResource(R.string.settings_sync_all_files),
        onBack = onBack,
        crumb = stringResource(R.string.settings_sync_crumb_cloud),
        headline = stringResource(R.string.settings_sync_all_files),
        lead = stringResource(R.string.settings_sync_files_lead),
        snackbarHostState = snackbarHostState,
    ) {
        when (files) {
            CloudDataLoad.Loading -> LoadingRow(stringResource(R.string.settings_sync_files_loading))
            CloudDataLoad.Failed -> FailedPanel(stringResource(R.string.settings_sync_files_failed), onRetry)
            is CloudDataLoad.Ready -> {
                val devices = files.value
                MetaLine(
                    groups = listOf(
                        stat(devices.sumOf { it.files.size }, R.plurals.settings_sync_unit_files),
                        sizeStat(devices.sumOf { it.totalBytes }),
                        stat(devices.size, R.plurals.settings_sync_unit_devices),
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 8.dp, top = 0.dp, bottom = 24.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsGroup(title = stringResource(R.string.settings_sync_section_devices)) {
                        devices.forEach { device ->
                            item(key = device.deviceId) {
                                DeviceFilesRow(
                                    device = device,
                                    now = now,
                                    expanded = expanded[device.deviceId] == true,
                                    onToggle = { expanded[device.deviceId] = expanded[device.deviceId] != true },
                                    onRequestRemove = { removeId = device.deviceId },
                                )
                            }
                        }
                    }
                    SyncNote(YoinSymbols.Info, stringResource(R.string.settings_sync_files_footer))
                }
                val removable = removeId?.let { id -> devices.firstOrNull { it.deviceId == id && !it.isThisDevice } }
                removable?.let { device ->
                    RemoveDeviceDialog(
                        deviceName = device.deviceName,
                        onDismiss = { removeId = null },
                        onConfirm = {
                            removeId = null
                            onRemoveDevice(device)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceFilesRow(
    device: CloudDeviceFiles,
    now: Long,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRequestRemove: () -> Unit,
) {
    val haptics = rememberYoinHaptics()
    var menuOpen by remember { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val thisIsTablet = configuration.smallestScreenWidthDp >= 600 ||
        (configuration.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK) >= Configuration.SCREENLAYOUT_SIZE_LARGE
    val tablet = if (device.isThisDevice) thisIsTablet else looksLikeTablet(device.deviceName)
    val staleDays = device.lastSyncAt?.let { ((now - it) / DayMs).toInt() }?.takeIf { !device.isThisDevice && it > StaleDays }
    val resources = LocalContext.current.resources
    Column(modifier = Modifier.fillMaxWidth().animateContentSize(YoinMotion.spatialSpring())) {
        Surface(
            onClick = {
                haptics.performTick()
                onToggle()
            },
            shape = LocalSettingsRowShape.current,
            color = Color.Transparent,
            modifier = Modifier
                .fillMaxWidth()
                .handPointer()
                .semantics { },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = SettingsRowMinHeight)
                    .padding(start = 16.dp, top = 14.dp, end = 4.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsRowIcon(if (tablet) YoinSymbols.Tablet else YoinSymbols.Smartphone)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    TitleWithPill(
                        title = device.deviceName,
                        pill = staleDays?.let { days ->
                            { SyncPill(pluralStringResource(R.plurals.settings_sync_stale_days, days, days), SyncPillTone.Muted) }
                        },
                    )
                    Text(
                        text = when {
                            device.isThisDevice -> stringResource(R.string.settings_sync_this_device)
                            device.lastSyncAt != null -> stringResource(
                                R.string.settings_sync_device_last_synced,
                                formatRelativeTime(device.lastSyncAt, now, resources = resources),
                            )
                            else -> stringResource(R.string.settings_sync_device_never)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    MetaLine(
                        groups = listOf(stat(device.files.size, R.plurals.settings_sync_unit_files), sizeStat(device.totalBytes)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Icon(
                    if (expanded) YoinSymbols.ChevronUp else YoinSymbols.ChevronDown,
                    contentDescription = stringResource(R.string.settings_sync_cd_expand_device, device.deviceName),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!device.isThisDevice) {
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
                                contentDescription = stringResource(R.string.settings_cd_device_options, device.deviceName),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        YoinDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            YoinDropdownMenuItem(
                                text = stringResource(R.string.settings_sync_remove_device_menu),
                                leadingIcon = { Icon(YoinSymbols.Delete, contentDescription = null) },
                                onClick = {
                                    haptics.performContextClick()
                                    menuOpen = false
                                    onRequestRemove()
                                },
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.width(12.dp))
                }
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(YoinMotion.spatialSpring(), expandFrom = Alignment.Top) +
                YoinMotion.fadeIn(role = YoinMotionRole.Standard),
            exit = shrinkVertically(YoinMotion.spatialSpring(), shrinkTowards = Alignment.Top) +
                YoinMotion.fadeOut(role = YoinMotionRole.Standard),
        ) {
            Column(modifier = Modifier.padding(start = 28.dp, end = 16.dp, bottom = 12.dp)) {
                device.files.forEach { FileRow(it) }
            }
        }
    }
}

@Composable
private fun FileRow(file: CloudFile) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(YoinSymbols.Article, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                file.name,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(
                    when (file.kind) {
                        SyncFileProps.KIND_STATE -> R.string.settings_sync_files_state
                        SyncFileProps.KIND_ARTIFACTS -> R.string.settings_sync_files_artifacts
                        else -> R.string.settings_sync_files_marker
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        file.sizeBytes?.let { bytes ->
            MetaLine(groups = listOf(sizeStat(bytes)), style = MaterialTheme.typography.labelLarge)
        }
    }
}

internal fun looksLikeTablet(name: String): Boolean = listOf("tablet", "tab ", "pad").any { name.lowercase().contains(it) }

@Composable
private fun LoadingRow(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        YoinLoadingIndicator(size = 40.dp)
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FailedPanel(text: String, onRetry: () -> Unit) {
    SettingsSegments {
        item {
            ActionPanel(text = text) {
                FilledTonalButton(onClick = onRetry, shapes = ButtonDefaults.shapes(), modifier = Modifier.handPointer()) {
                    Text(stringResource(R.string.settings_sync_try_again))
                }
            }
        }
    }
}

// ── one account's cloud data ──────────────────────────────────────────

private enum class AccountTab { Notes, Albums, Songs, Titles }

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun CloudAccountPage(
    account: CloudAccountRef,
    content: CloudDataLoad<CloudAccountContent>,
    counts: CloudScopeCounts?,
    removing: Boolean,
    now: Long,
    onBack: () -> Unit,
    onTransfer: () -> Unit,
    onRemove: () -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val haptics = rememberYoinHaptics()
    var query by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableStateOf(AccountTab.Notes) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    SyncPage(
        barTitle = account.displayName,
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        header = { titleModifier ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.settings_sync_crumb_cloud),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
                AccountGlyph(account.provider, size = 88.dp)
                Text(
                    account.displayName,
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                    modifier = titleModifier.semantics { heading() },
                )
                val line = accountLineGroups(account.provider, account.displayName, account.hint, account.fromDevice)
                if (line.isNotEmpty()) MetaLine(groups = line, style = MaterialTheme.typography.bodyMedium)
                if (account.cloudOnly) SyncPill(stringResource(R.string.settings_sync_cloud_only))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    FilledTonalButton(
                        onClick = {
                            haptics.performClick()
                            onTransfer()
                        },
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.handPointer(),
                    ) {
                        Icon(YoinSymbols.Forward, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.settings_xfer_title))
                    }
                    OutlinedButton(
                        onClick = {
                            haptics.performClick()
                            confirmRemove = true
                        },
                        enabled = !removing,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.handPointer(enabled = !removing),
                    ) {
                        if (removing) {
                            YoinLoadingIndicator(size = 20.dp)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(stringResource(R.string.settings_sync_remove_action))
                    }
                }
                SyncNote(
                    YoinSymbols.Visibility,
                    stringResource(R.string.settings_sync_browser_readonly, stringResource(R.string.settings_sync_google_drive)),
                    centered = true,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Spacer(Modifier.height(16.dp))
            }
        },
    ) {
        SyncSearchField(query, { query = it }, stringResource(R.string.settings_sync_browser_search))
        Spacer(Modifier.height(12.dp))
        when (content) {
            CloudDataLoad.Loading -> LoadingRow(stringResource(R.string.settings_sync_loading))
            CloudDataLoad.Failed -> FailedPanel(stringResource(R.string.settings_sync_files_failed)) {}
            is CloudDataLoad.Ready -> {
                val data = content.value
                val q = query.trim()
                val notes = data.notes.filter { matches(q, it.title, it.artist, it.content) }
                val albums = data.albums.filter { matches(q, it.name, it.artist, it.review) }
                val songs = data.songRatings.filter { matches(q, it.title, it.artist) }
                val titles = data.titles.filter { matches(q, it.title, it.subject) }
                SyncTabs(
                    tabs = listOf(
                        Triple(AccountTab.Notes, stringResource(R.string.settings_sync_tab_notes), notes.size),
                        Triple(AccountTab.Albums, stringResource(R.string.settings_sync_tab_albums), albums.size),
                        Triple(AccountTab.Songs, stringResource(R.string.settings_sync_tab_songs), songs.size),
                        Triple(AccountTab.Titles, stringResource(R.string.settings_sync_tab_titles), titles.size),
                    ),
                    selected = tab,
                    onSelect = { tab = it },
                )
                Spacer(Modifier.height(12.dp))
                val resources = LocalContext.current.resources
                val items: List<@Composable () -> Unit> = when (tab) {
                    AccountTab.Notes -> notes.map { note ->
                        {
                            BrowserItem(
                                title = note.title.ifBlank { stringResource(R.string.settings_sync_unknown_song) },
                                meta = listOfNotNull(note.artist.takeIf(String::isNotBlank)?.let { MetaGroup.Plain(it) }),
                                body = note.content,
                                stamp = note.positionMs?.let(::formatTrackDurationMs),
                                footer = formatRelativeTime(note.updatedAt, now, resources = resources),
                            )
                        }
                    }
                    AccountTab.Albums -> albums.map { album ->
                        {
                            BrowserItem(
                                title = album.name ?: stringResource(R.string.settings_sync_unknown_album),
                                meta = listOfNotNull(
                                    album.artist?.let { MetaGroup.Plain(it) },
                                    MetaGroup.Plain(formatRelativeTime(album.updatedAt, now, resources = resources), muted = true),
                                ),
                                body = album.review ?: stringResource(R.string.settings_sync_rating_only),
                                bodyMuted = album.review == null,
                                score = album.rating,
                            )
                        }
                    }
                    AccountTab.Songs -> songs.map { song ->
                        {
                            BrowserItem(
                                title = song.title ?: stringResource(R.string.settings_sync_unknown_song),
                                meta = listOfNotNull(song.artist?.takeIf(String::isNotBlank)?.let { MetaGroup.Plain(it) }),
                                score = song.rating,
                            )
                        }
                    }
                    AccountTab.Titles -> titles.map { title ->
                        {
                            BrowserItem(
                                title = title.title,
                                icon = YoinSymbols.Sparkle,
                                meta = listOfNotNull(
                                    title.subject?.let { MetaGroup.Plain(it) },
                                    MetaGroup.Kind(
                                        stringResource(if (title.custom) R.string.settings_sync_title_custom else R.string.settings_sync_title_ai),
                                        accent = title.custom,
                                    ),
                                ),
                            )
                        }
                    }
                }
                ItemList(items, emptyText = stringResource(if (q.isEmpty()) R.string.settings_sync_no_data else R.string.settings_sync_no_matches))
            }
        }
    }
    if (confirmRemove) {
        RemoveFromCloudDialog(
            account = account,
            counts = counts,
            onDismiss = { confirmRemove = false },
            onConfirm = {
                confirmRemove = false
                onRemove()
            },
        )
    }
}

private fun matches(query: String, vararg fields: String?): Boolean =
    query.isEmpty() || fields.any { it?.contains(query, ignoreCase = true) == true }

@Composable
private fun ItemList(items: List<@Composable () -> Unit>, emptyText: String) {
    if (items.isEmpty()) {
        Text(
            emptyText,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 40.dp),
        )
        return
    }
    SettingsSegments {
        items.forEach { content -> item { content() } }
    }
}

/** One record of the browser: title, a meta line, optional text (a note with its time point), a score. */
@Composable
private fun BrowserItem(
    title: String,
    meta: List<MetaGroup>,
    modifier: Modifier = Modifier,
    body: String? = null,
    bodyMuted: Boolean = false,
    stamp: String? = null,
    footer: String? = null,
    score: Float? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = if (body == null) Alignment.CenterVertically else Alignment.Top,
    ) {
        if (icon != null) SettingsRowIcon(icon)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            if (meta.isNotEmpty()) MetaLine(groups = meta, style = MaterialTheme.typography.bodySmall)
            if (body != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    if (stamp != null) SyncPill(stamp, SyncPillTone.Muted)
                    Text(
                        body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (bodyMuted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            if (footer != null) {
                MetaLine(groups = listOf(MetaGroup.Plain(footer, muted = true)), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (score != null) {
            Text(
                String.format(Locale.getDefault(), "%.1f", score),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

// ── 全局数据 ───────────────────────────────────────────────────────────

private enum class GlobalTab { Backups, Translations, About }

@Composable
internal fun CloudGlobalPage(
    content: CloudDataLoad<CloudGlobalContent>,
    now: Long,
    onBack: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableStateOf(GlobalTab.Backups) }
    SyncPage(
        barTitle = stringResource(R.string.settings_sync_global_data),
        onBack = onBack,
        crumb = stringResource(R.string.settings_sync_crumb_cloud),
        headline = stringResource(R.string.settings_sync_global_data),
        lead = stringResource(R.string.settings_sync_global_lead),
    ) {
        SyncSearchField(query, { query = it }, stringResource(R.string.settings_sync_global_search))
        Spacer(Modifier.height(12.dp))
        when (content) {
            CloudDataLoad.Loading -> LoadingRow(stringResource(R.string.settings_sync_loading))
            CloudDataLoad.Failed -> FailedPanel(stringResource(R.string.settings_sync_files_failed)) {}
            is CloudDataLoad.Ready -> {
                val data = content.value
                val q = query.trim()
                val backups = data.playlistBackups.filter { matches(q, it.name, it.accountLabel) }
                val translations = data.translations.filter { matches(q, it.title, it.artist) }
                val about = data.about.filter { matches(q, it.title, it.artist, it.question, it.answer) }
                SyncTabs(
                    tabs = listOf(
                        Triple(GlobalTab.Backups, stringResource(R.string.settings_sync_tab_backups), backups.size),
                        Triple(GlobalTab.Translations, stringResource(R.string.settings_sync_tab_translations), translations.size),
                        Triple(GlobalTab.About, stringResource(R.string.settings_sync_tab_about), about.size),
                    ),
                    selected = tab,
                    onSelect = { tab = it },
                )
                Spacer(Modifier.height(12.dp))
                val resources = LocalContext.current.resources
                val items: List<@Composable () -> Unit> = when (tab) {
                    GlobalTab.Backups -> backups.map { backup ->
                        {
                            val serviceName = identityOf(backup.provider)?.let { stringResource(it.nameRes) }
                            BrowserItem(
                                title = backup.name,
                                icon = YoinSymbols.Playlist,
                                meta = listOfNotNull(
                                    stat(backup.trackCount, R.plurals.settings_sync_unit_songs),
                                    serviceName?.let { MetaGroup.Kind(it) },
                                    backup.accountLabel.takeIf(String::isNotBlank)?.let { MetaGroup.Plain(it) },
                                ),
                                footer = backup.backedUpAt?.let {
                                    stringResource(R.string.settings_sync_backed_up_on, formatRelativeTime(it, now, resources = resources))
                                },
                            )
                        }
                    }
                    GlobalTab.Translations -> translations.map { entry ->
                        {
                            BrowserItem(
                                title = entry.title ?: entry.trackId,
                                icon = YoinSymbols.Translate,
                                meta = listOfNotNull(
                                    entry.artist?.takeIf(String::isNotBlank)?.let { MetaGroup.Plain(it) },
                                    entry.language.takeIf(String::isNotBlank)?.let { MetaGroup.Kind(languageLabel(it)) },
                                ),
                            )
                        }
                    }
                    GlobalTab.About -> about.map { entry ->
                        {
                            BrowserItem(
                                title = entry.title,
                                icon = YoinSymbols.Info,
                                meta = listOfNotNull(entry.artist.takeIf(String::isNotBlank)?.let { MetaGroup.Plain(it) }),
                                body = listOfNotNull(entry.question, entry.answer.takeIf(String::isNotBlank)).joinToString("\n"),
                            )
                        }
                    }
                }
                ItemList(items, emptyText = stringResource(if (q.isEmpty()) R.string.settings_sync_no_data else R.string.settings_sync_no_matches))
            }
        }
    }
}

/** "zh-Hans" → "简体中文" in the UI language; the tag itself when Java doesn't know it. */
private fun languageLabel(tag: String): String {
    val locale = Locale.forLanguageTag(tag)
    val name = locale.getDisplayName(Locale.getDefault())
    return name.takeIf { it.isNotBlank() && it != tag } ?: tag
}
