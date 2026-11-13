package com.gpo.yoin.ui.component

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.YoinDevice
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.YoinContainerShapes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesSheet(
    providerId: String?,
    devices: List<YoinDevice>,
    loading: Boolean,
    busyDeviceId: String? = null,
    errorMessage: String? = null,
    onRefresh: () -> Unit,
    onSelect: (YoinDevice) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val haptics = rememberYoinHaptics()
    val devicesTitle = stringResource(R.string.cmp_devices_title)
    val spotifySubtitle = stringResource(R.string.cmp_devices_subtitle_spotify)
    val subsonicSubtitle = stringResource(R.string.cmp_devices_subtitle_subsonic)
    val otherSubtitle = stringResource(R.string.cmp_devices_subtitle_other)
    val refreshDescription = stringResource(R.string.cmp_devices_cd_refresh)
    val spotifyEmpty = stringResource(R.string.cmp_devices_empty_spotify)
    val subsonicEmpty = stringResource(R.string.cmp_devices_empty_subsonic)
    val otherEmpty = stringResource(R.string.cmp_devices_empty_other)
    // Fetch on open: the sheet used to show whatever stale snapshot the VM
    // held until the user found the manual refresh button.
    LaunchedEffect(Unit) {
        onRefresh()
    }
    // Back from Spotify's own picker ("More devices"): read where it plays now.
    var resumedOnce by rememberSaveable { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        if (resumedOnce) onRefresh() else resumedOnce = true
        onPauseOrDispose { }
    }
    val context = LocalContext.current
    val windowInfo = LocalWindowInfo.current
    val scope = rememberCoroutineScope()
    var moreDevicesFailed by rememberSaveable { mutableStateOf(false) }
    // Spotify's device picker, inside Yoin when Android's output switcher opens for Spotify;
    // the Spotify app otherwise.
    val openSpotifyPicker: () -> Unit = {
        moreDevicesFailed = false
        if (SpotifyOutputSwitcher.request(context)) {
            scope.launch {
                delay(SpotifyOutputSwitcher.SHOW_WAIT_MS)
                // Still focused: the system panel didn't come up (another Android, or refused).
                if (windowInfo.isWindowFocused) {
                    moreDevicesFailed = !SpotifyOutputSwitcher.openSpotify(context)
                    return@launch
                }
                // The panel is a System UI window: Yoin stays resumed, so wait for focus to come
                // back, then read the devices once Spotify has moved (a speaker may be picked).
                withTimeoutOrNull(SpotifyOutputSwitcher.PANEL_MAX_MS) {
                    snapshotFlow { windowInfo.isWindowFocused }.first { it }
                }
                delay(SpotifyOutputSwitcher.SETTLE_MS)
                onRefresh()
            }
        } else {
            moreDevicesFailed = !SpotifyOutputSwitcher.openSpotify(context)
        }
    }
    val openFailedMessage = stringResource(R.string.np_msg_spotify_open_failed)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentWindowInsets = {
            BottomSheetDefaults.modalWindowInsets.only(
                WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
            )
        },
        modifier = modifier,
    ) {
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, top = 8.dp, end = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = devicesTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = when (providerId) {
                            MediaId.PROVIDER_SPOTIFY -> spotifySubtitle
                            MediaId.PROVIDER_SUBSONIC -> subsonicSubtitle
                            else -> otherSubtitle
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = {
                        haptics.performTick()
                        onRefresh()
                    },
                    enabled = !loading,
                ) {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = YoinSymbols.Refresh,
                            contentDescription = refreshDescription,
                        )
                    }
                }
            }

            PullToRefreshBox(
                isRefreshing = loading,
                onRefresh = {
                    haptics.performTick()
                    onRefresh()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    // The heading stays above; the list's top edge is the tide line (owner 2026-11-06).
                    modifier = Modifier.sheetScrollEdge(listState),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = 8.dp,
                        end = 16.dp,
                        bottom = 16.dp + navBottom,
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val shownError = errorMessage ?: openFailedMessage.takeIf { moreDevicesFailed }
                    if (shownError != null) {
                        item {
                            Text(
                                text = shownError,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }

                    if (!loading && devices.isEmpty()) {
                        item {
                            Text(
                                text = when (providerId) {
                                    MediaId.PROVIDER_SPOTIFY -> spotifyEmpty
                                    MediaId.PROVIDER_SUBSONIC -> subsonicEmpty
                                    else -> otherEmpty
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                            )
                        }
                    }

                    items(items = devices, key = YoinDevice::id) { device ->
                        DeviceRow(
                            device = device,
                            busy = busyDeviceId == device.id,
                            onClick = {
                                haptics.performContextClick()
                                // Sonos and the like take no Web API transfer: Spotify's picker does it.
                                if (device is YoinDevice.SpotifyConnect && device.spotifyOnly) {
                                    if (!device.isActive) openSpotifyPicker()
                                } else {
                                    onSelect(device)
                                }
                            },
                        )
                    }

                    // The Web API lists only devices online in Spotify's cloud; Sonos and
                    // Cast speakers wake only from Spotify's own picker (owner 2026-11-08).
                    if (providerId == MediaId.PROVIDER_SPOTIFY) {
                        item(key = "more-devices") {
                            MoreDevicesRow(
                                onClick = {
                                    haptics.performTick()
                                    openSpotifyPicker()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun MoreDevicesRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = YoinContainerShapes.ListRow,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = YoinSymbols.Speaker,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.cmp_devices_more_title),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.cmp_devices_more_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun DeviceRow(
    device: YoinDevice,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = device.isSelectable && !busy
    val currentDevice = stringResource(R.string.cmp_devices_cd_current)
    androidx.compose.material3.Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled || device.isActive) 1f else 0.6f),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = YoinContainerShapes.ListRow,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = iconForDevice(device),
                contentDescription = null,
                tint = if (device.isActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(22.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (device is YoinDevice.LocalPlayback) {
                        stringResource(R.string.cmp_devices_this_device)
                    } else {
                        device.name
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                device.statusText?.let { status ->
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    busy -> CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    device.isActive -> Icon(
                        imageVector = YoinSymbols.Check,
                        contentDescription = currentDevice,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}
