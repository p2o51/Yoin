package com.gpo.yoin.ui.tips

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.ui.settings.SettingsPageBackground
import com.gpo.yoin.ui.settings.sync.BackupGroup
import com.gpo.yoin.ui.settings.sync.BackupPage
import com.gpo.yoin.ui.settings.sync.BackupUiState
import com.gpo.yoin.ui.settings.sync.CloudSyncOnActions
import com.gpo.yoin.ui.settings.sync.SyncHeadline
import com.gpo.yoin.ui.settings.sync.SyncRedoSamples
import com.gpo.yoin.ui.settings.sync.TransferActions
import com.gpo.yoin.ui.settings.sync.TransferFlow
import com.gpo.yoin.ui.settings.sync.TransferStep
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinTheme
import kotlinx.coroutines.delay

/** Where the cloud sync demo is: the 备份与迁移 group, the finished download, or the transfer's first step. */
internal enum class CloudDemoPage { Group, Exported, Transfer }

/**
 * Your data is yours (redone Cloud sync, PR #284): Settings › Sync & storage › Cloud sync's 备份与迁移 group —
 * the real rows — where the dot downloads everything (the real "saved" page, over the sync pages' sample
 * library), then opens Transfer Memories on its real step that asks which account to move them to.
 * Nothing is read or written: the demo's pages are the samples the sync pages' previews use.
 */
@Composable
internal fun CloudExportTransferDemo(playing: Boolean) {
    var page by remember { mutableStateOf(CloudDemoPage.Group) }
    var target by remember { mutableStateOf<String?>(null) }
    DemoPhone(playing = playing, script = {
        page = CloudDemoPage.Group
        target = null
        delay(800)
        moveTo("sync-export", fx = 0.5f)
        tap { page = CloudDemoPage.Exported }
        lift()
        delay(2_200)
        page = CloudDemoPage.Group
        delay(900)
        moveTo("sync-transfer", fx = 0.5f)
        tap { page = CloudDemoPage.Transfer }
        lift()
        delay(2_800)
    }) {
        CloudExportTransferPhone(
            page = page,
            target = target,
            onPage = { page = it },
            onTarget = { target = it },
        )
    }
}

/** The demo phone's screen on [page]; [target]: the account picked on Transfer's first step. */
@Composable
internal fun CloudExportTransferPhone(
    page: CloudDemoPage,
    target: String?,
    onPage: (CloudDemoPage) -> Unit,
    onTarget: (String) -> Unit,
) {
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                YoinMotion.fadeOut(role = YoinMotionRole.Standard)
        },
        label = "cloudExportTransfer",
        modifier = Modifier.fillMaxSize(),
    ) { shown ->
        when (shown) {
            CloudDemoPage.Group -> SettingsPageBackground(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    SyncHeadline(
                        crumb = stringResource(R.string.settings_sync_crumb),
                        headline = stringResource(R.string.settings_sync_title),
                        lead = null,
                    )
                    BackupGroup(
                        actions = CloudSyncOnActions(
                            onExport = { onPage(CloudDemoPage.Exported) },
                            onTransfer = { onPage(CloudDemoPage.Transfer) },
                        ),
                        itemModifier = { key -> Modifier.demoTarget("sync-$key") },
                    )
                }
            }
            CloudDemoPage.Exported -> BackupPage(
                state = remember {
                    BackupUiState.Exported(DemoBackupFile, SyncRedoSamples.exportSummary(), DemoBackupBytes)
                },
                onBack = { onPage(CloudDemoPage.Group) },
                onChoose = { _, _ -> },
                onConfirmImport = {},
                onDone = { onPage(CloudDemoPage.Group) },
            )
            CloudDemoPage.Transfer -> TransferFlow(
                state = SyncRedoSamples.transfer(TransferStep.Target, targetKey = target),
                onBack = { onPage(CloudDemoPage.Group) },
                actions = TransferActions(pickTarget = onTarget),
            )
        }
    }
}

private const val DemoBackupFile = "yoin-backup-2026-11-23.zip"
private const val DemoBackupBytes = 486 * 1024L

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun CloudExportTransferDemoPreview() {
    YoinTheme { Box(Modifier.padding(16.dp)) { CloudExportTransferDemo(playing = false) } }
}
