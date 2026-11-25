@file:Suppress("ktlint:standard:function-naming") // Composables are PascalCase

package com.gpo.yoin.ui.settings

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.player.sing.SingAccelerator
import com.gpo.yoin.player.sing.SingModelDownloadStatus
import com.gpo.yoin.player.sing.SingModelOverview
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.handPointer
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import kotlin.math.roundToInt

private const val TensorEasterEggTapTarget = 10
private const val TensorTapResetWindowMs = 2_000L

/**
 * Clean Yoin Labs row for the on-device Lyra Sing model: status, one-line hardware optimization note,
 * download/remove action, and a hidden 10-tap trigger for the Google Tensor G5 / G6 easter egg page.
 */
@Composable
internal fun LyraSingLabsRow(
    overview: SingModelOverview,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    onOpenTensorEasterEgg: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberYoinHaptics()
    val status = overview.status
    val canOpenEasterEgg = overview.isTensorTpu && onOpenTensorEasterEgg != null

    var tapCount by remember { mutableIntStateOf(0) }
    var lastTapAtMs by remember { mutableLongStateOf(0L) }

    val handleRowClick: (() -> Unit)? = if (canOpenEasterEgg) {
        val openEgg = onOpenTensorEasterEgg
        {
            val now = SystemClock.uptimeMillis()
            val next = if (now - lastTapAtMs > TensorTapResetWindowMs) 1 else tapCount + 1
            lastTapAtMs = now
            if (next >= TensorEasterEggTapTarget) {
                tapCount = 0
                haptics.performConfirm()
                openEgg()
            } else {
                tapCount = next
                if (next >= TensorEasterEggTapTarget - 3) {
                    haptics.performTick()
                }
            }
        }
    } else {
        null
    }

    val optimizationLine = overview.tensorGeneration?.let { gen ->
        stringResource(R.string.labs_sing_optimized_for, gen.displayName)
    } ?: if (overview.lastRun?.accelerator == SingAccelerator.CPU) {
        stringResource(R.string.labs_sing_running_cpu)
    } else {
        stringResource(R.string.labs_sing_accelerated_gpu)
    }

    SettingsItem(
        icon = YoinSymbols.Mic,
        title = stringResource(R.string.labs_sing_model_name),
        modifier = modifier,
        onClick = handleRowClick,
        summaryContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val (statusText, statusColor) = when (status) {
                    SingModelDownloadStatus.Checking ->
                        stringResource(R.string.labs_sing_checking) to MaterialTheme.colorScheme.onSurfaceVariant
                    SingModelDownloadStatus.NotDownloaded ->
                        stringResource(
                            R.string.labs_sing_not_downloaded,
                            SingModelOverview.formatBytes(overview.expectedDownloadBytes),
                        ) to MaterialTheme.colorScheme.onSurfaceVariant
                    is SingModelDownloadStatus.Downloading ->
                        stringResource(
                            R.string.labs_sing_downloading,
                            (status.progress * 100f).roundToInt().coerceIn(0, 100),
                        ) to MaterialTheme.colorScheme.onSurfaceVariant
                    SingModelDownloadStatus.Ready ->
                        stringResource(
                            R.string.labs_sing_ready,
                            SingModelOverview.formatBytes(overview.totalInstalledBytes),
                        ) to MaterialTheme.colorScheme.onSurfaceVariant
                    is SingModelDownloadStatus.Failed ->
                        stringResource(R.string.labs_sing_download_failed, status.message) to MaterialTheme.colorScheme.error
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = statusColor,
                )
                if (status is SingModelDownloadStatus.Downloading) {
                    LinearProgressIndicator(
                        progress = { status.progress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .height(4.dp)
                            .clip(CircleShape),
                    )
                }
                Text(
                    text = optimizationLine,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailing = {
            when (status) {
                SingModelDownloadStatus.NotDownloaded,
                is SingModelDownloadStatus.Failed,
                -> FilledTonalButton(
                    onClick = {
                        haptics.performContextClick()
                        onDownload()
                    },
                    modifier = Modifier.handPointer(),
                ) {
                    Text(stringResource(R.string.labs_sing_download))
                }
                is SingModelDownloadStatus.Downloading,
                SingModelDownloadStatus.Checking,
                -> CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 3.dp,
                )
                SingModelDownloadStatus.Ready -> TextButton(
                    onClick = {
                        haptics.performContextClick()
                        onRemove()
                    },
                    modifier = Modifier.handPointer(),
                ) {
                    Text(stringResource(R.string.labs_sing_remove))
                }
            }
        },
    )
}
