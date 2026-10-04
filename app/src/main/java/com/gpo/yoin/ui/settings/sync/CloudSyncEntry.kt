package com.gpo.yoin.ui.settings.sync

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.data.sync.CloudSyncState
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.settings.SettingsItem

/**
 * Settings › Storage's first row: "Cloud sync" and where it stands
 * ("Off", "On · Synced 2 min ago", "Paused · Reconnect Google"…). Opens
 * [CloudSyncActivity].
 */
@Composable
fun CloudSyncEntryRow(
    state: CloudSyncState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    now: Long = rememberTickingNow(),
    showChevron: Boolean = false,
) {
    SettingsItem(
        icon = YoinSymbols.Devices,
        title = "Cloud sync",
        summary = cloudSyncEntrySummary(state, now),
        onClick = onClick,
        modifier = modifier,
        // List-detail: rows that open a page on the right carry a chevron.
        trailing = if (showChevron) {
            {
                Icon(
                    imageVector = YoinSymbols.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            null
        },
    )
}

/**
 * [CloudSyncEntryRow] bound to the app's sync manager, for the stateful
 * Settings screen. [onClick] opens [CloudSyncActivity] (a plain Pattern A
 * launch; the caller owns the list-detail selection).
 */
@Composable
fun CloudSyncSettingsRow(onClick: () -> Unit, modifier: Modifier = Modifier, showChevron: Boolean = false) {
    val context = LocalContext.current
    val controller = remember(context) { (context.applicationContext as YoinApplication).container.cloudSync }
    val state by controller.state.collectAsState()
    CloudSyncEntryRow(state = state, onClick = onClick, modifier = modifier, showChevron = showChevron)
}
