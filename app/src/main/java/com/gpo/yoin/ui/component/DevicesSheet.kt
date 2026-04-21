package com.gpo.yoin.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.YoinDevice
import com.gpo.yoin.ui.theme.YoinShapeTokens

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
                        text = "Devices",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = when (providerId) {
                            MediaId.PROVIDER_SPOTIFY -> "Spotify Connect"
                            MediaId.PROVIDER_SUBSONIC -> "Local + Cast status"
                            else -> "Current playback targets"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onRefresh, enabled = !loading) {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Refresh devices",
                        )
                    }
                }
            }

            PullToRefreshBox(
                isRefreshing = loading,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = 8.dp,
                        end = 16.dp,
                        bottom = 16.dp + navBottom,
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (errorMessage != null) {
                        item {
                            Text(
                                text = errorMessage,
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
                                    MediaId.PROVIDER_SPOTIFY -> "No Spotify devices found."
                                    MediaId.PROVIDER_SUBSONIC -> "Only local playback is available right now."
                                    else -> "No devices found."
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
                            onClick = { onSelect(device) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: YoinDevice,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = device.isSelectable && !busy
    androidx.compose.material3.Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled || device.isActive) 1f else 0.6f),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = YoinShapeTokens.ExtraLarge,
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
                    text = device.name,
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
            when {
                busy -> CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
                device.isActive -> Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = "Current device",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
