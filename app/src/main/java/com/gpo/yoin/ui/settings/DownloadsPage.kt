package com.gpo.yoin.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.data.offline.DownloadQuality
import com.gpo.yoin.data.offline.OfflineCollection
import com.gpo.yoin.data.offline.OfflineDownloads
import com.gpo.yoin.data.offline.OfflineEntry
import com.gpo.yoin.data.offline.OfflineStatus
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.seamFade
import com.gpo.yoin.ui.detail.AlbumDetailActivity
import com.gpo.yoin.ui.detail.PlaylistDetailActivity
import com.gpo.yoin.ui.experience.rememberYoinHaptics

/*
 * Settings › Storage › Downloads (owner 2026-11-04: "default a value you can
 * change, like Spotify's settings"), a PushPage like Streaming quality:
 * SettingsFeatureActivity, native back. Download quality (320 by default),
 * Download using mobile data (off), how much the downloads take, and what is
 * downloaded: each album or playlist opens its page, which works offline from
 * the detail cache, and its songs play from the downloads.
 */
@Composable
internal fun DownloadsPageContent(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val downloads = (context.applicationContext as YoinApplication).container.offlineDownloads
    val haptics = rememberYoinHaptics()
    val settings by downloads.settingsStore.settings.collectAsState()
    val library by downloads.library.collectAsState()
    val downloadBytes by downloads.downloadBytes.collectAsState()
    var confirmRemoveAll by remember { mutableStateOf(false) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(28.dp)) {
        SettingsGroup(title = stringResource(R.string.settings_downloads_quality)) {
            DownloadQuality.entries.forEach { quality ->
                item(key = quality) {
                    ChoiceRow(
                        label = downloadQualityLabel(quality),
                        detail = if (quality == DownloadQuality.Original) {
                            stringResource(R.string.settings_downloads_quality_original_detail)
                        } else {
                            null
                        },
                        selected = quality == settings.quality,
                        onClick = {
                            if (quality != settings.quality) {
                                haptics.performContextClick()
                                downloads.settingsStore.setQuality(quality)
                            }
                        },
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.settings_downloads_quality_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp).seamFade(),
        )
        SettingsSegments {
            item {
                SettingsItem(
                    icon = YoinSymbols.Download,
                    title = stringResource(R.string.settings_downloads_mobile_data),
                    summary = if (settings.overMobileData) null else stringResource(R.string.settings_downloads_mobile_data_detail),
                    onClick = {
                        haptics.performContextClick()
                        downloads.settingsStore.setOverMobileData(!settings.overMobileData)
                    },
                    trailing = { Switch(checked = settings.overMobileData, onCheckedChange = null) },
                )
            }
        }
        SettingsGroup(title = stringResource(R.string.settings_downloads_storage)) {
            item {
                SettingsItem(
                    icon = YoinSymbols.Storage,
                    title = stringResource(R.string.settings_downloads_used),
                    summary = formatBytes(downloadBytes, context.resources),
                )
            }
            item {
                SettingsItem(
                    icon = YoinSymbols.DeleteSweep,
                    title = stringResource(R.string.settings_downloads_remove_all),
                    summary = stringResource(R.string.settings_downloads_remove_all_detail),
                    onClick = if (downloadBytes > 0 || library.songs.isNotEmpty()) ({ confirmRemoveAll = true }) else null,
                )
            }
        }
        SettingsGroup(title = stringResource(R.string.settings_downloads_list)) {
            if (library.entries.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.settings_downloads_list_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp).seamFade(),
                    )
                }
            }
            library.entries.forEach { entry ->
                item(key = entry.collection.key) { DownloadEntryRow(entry, downloads) }
            }
        }
    }
    if (confirmRemoveAll) {
        AlertDialog(
            onDismissRequest = { confirmRemoveAll = false },
            title = { Text(stringResource(R.string.settings_downloads_remove_all)) },
            text = { Text(stringResource(R.string.settings_downloads_remove_all_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptics.performReject()
                        confirmRemoveAll = false
                        downloads.removeAll()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.settings_downloads_remove_all_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoveAll = false }) {
                    Text(stringResource(R.string.settings_downloads_cancel))
                }
            },
        )
    }
}

@Composable
private fun DownloadEntryRow(entry: OfflineEntry, downloads: OfflineDownloads) {
    val context = LocalContext.current
    val haptics = rememberYoinHaptics()
    val collection = entry.collection
    val icon = when (collection.kind) {
        OfflineCollection.Kind.Album -> YoinSymbols.Album
        OfflineCollection.Kind.Playlist -> YoinSymbols.Playlist
        OfflineCollection.Kind.Song -> YoinSymbols.MusicNote
    }
    val status = when (val s = entry.status) {
        is OfflineStatus.Downloading -> stringResource(R.string.settings_downloads_entry_progress, s.done, s.total)
        OfflineStatus.Failed -> stringResource(R.string.settings_downloads_entry_failed)
        else -> stringResource(
            R.string.settings_downloads_entry_songs,
            entry.total,
            formatBytes(entry.bytes, context.resources),
        )
    }
    val open: (() -> Unit)? = when (collection.kind) {
        OfflineCollection.Kind.Album -> ({ context.startActivity(AlbumDetailActivity.intent(context, collection.id)) })
        OfflineCollection.Kind.Playlist -> ({ context.startActivity(PlaylistDetailActivity.intent(context, collection.id)) })
        OfflineCollection.Kind.Song -> null
    }
    SettingsItem(
        icon = icon,
        title = collection.title,
        summary = listOfNotNull(collection.subtitle?.takeIf(String::isNotBlank), status).joinToString(" · "),
        onClick = open,
        trailing = {
            IconButton(
                onClick = {
                    haptics.performReject()
                    downloads.removeLater(collection.key) {}
                },
            ) {
                Icon(YoinSymbols.Close, contentDescription = stringResource(R.string.settings_downloads_entry_remove))
            }
        },
    )
}

@Composable
private fun ChoiceRow(label: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onClick)
            .heightIn(min = SettingsRowMinHeight)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(SettingsRowIconGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(horizontal = 8.dp))
        Column(modifier = Modifier.weight(1f).seamFade(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun downloadQualityLabel(quality: DownloadQuality): String = when (val kbps = quality.maxBitRateKbps) {
    null -> stringResource(R.string.settings_stream_quality_original)
    else -> stringResource(R.string.settings_stream_quality_kbps, kbps)
}

/** The Storage row's summary: the quality, and what the downloads take. */
@Composable
internal fun downloadsSummary(downloads: OfflineDownloads): String {
    val settings by downloads.settingsStore.settings.collectAsState()
    val bytes by downloads.downloadBytes.collectAsState()
    val quality = downloadQualityLabel(settings.quality)
    return if (bytes <= 0L) {
        stringResource(R.string.settings_downloads_summary_empty, quality)
    } else {
        stringResource(R.string.settings_downloads_summary, quality, formatBytes(bytes, LocalContext.current.resources))
    }
}
