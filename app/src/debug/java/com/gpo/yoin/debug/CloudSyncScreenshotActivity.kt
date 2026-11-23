package com.gpo.yoin.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.gpo.yoin.YoinActivityRoot
import com.gpo.yoin.enableYoinEdgeToEdge
import com.gpo.yoin.ui.settings.sync.BackupPage
import com.gpo.yoin.ui.settings.sync.BackupViewModel
import com.gpo.yoin.ui.settings.sync.CloudAccountPage
import com.gpo.yoin.ui.settings.sync.CloudDataLoad
import com.gpo.yoin.ui.settings.sync.CloudFilesPage
import com.gpo.yoin.ui.settings.sync.CloudGlobalPage
import com.gpo.yoin.ui.settings.sync.CloudSyncContent
import com.gpo.yoin.ui.settings.sync.CloudSyncSamples
import com.gpo.yoin.ui.settings.sync.FakeCloudSyncController
import com.gpo.yoin.ui.settings.sync.PreviewMemoriesOps
import com.gpo.yoin.ui.settings.sync.SyncLocationPage
import com.gpo.yoin.ui.settings.sync.TransferActions
import com.gpo.yoin.ui.settings.sync.TransferArgs
import com.gpo.yoin.ui.settings.sync.TransferFlow
import com.gpo.yoin.ui.settings.sync.TransferViewModel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Debug-only: the redone Cloud sync page, its sub-pages and the 转移 Memories / backup flows against
 * [FakeCloudSyncController] and [PreviewMemoriesOps] (the prototype's fictional library). Nothing touches
 * Google, Drive or the app's profiles.
 *   adb shell am start -n com.gpo.yoin/com.gpo.yoin.debug.CloudSyncScreenshotActivity --es screen main
 * screen: main | changed | files | account | global | location | export | import | xfer
 * xfer extras: --es source local:nav|cloud:sp-old  --es target nav|am|sp
 */
class CloudSyncScreenshotActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableYoinEdgeToEdge()
        val start = intent.getStringExtra("screen") ?: "main"
        val sourceExtra = intent.getStringExtra("source")
        val targetExtra = intent.getStringExtra("target")
        val now = System.currentTimeMillis()
        val controller = FakeCloudSyncController(
            initial = if (start == "changed") CloudSyncSamples.accountChanged(now) else CloudSyncSamples.proto(now),
        )
        setContent {
            YoinActivityRoot {
                var screen by remember { mutableStateOf(if (start == "changed") "main" else start) }
                var xferArgs by remember {
                    mutableStateOf(
                        TransferArgs(
                            sourceProfileId = sourceExtra?.takeIf { it.startsWith("local:") }?.removePrefix("local:"),
                            sourceCloudScope = sourceExtra?.takeIf { it.startsWith("cloud:") }?.removePrefix("cloud:"),
                            targetProfileId = targetExtra,
                        ),
                    )
                }
                val home = { if (screen == "main" || screen == start) finish() else screen = "main" }
                BackHandler(enabled = screen != "xfer") { home() }
                val state by controller.state.collectAsState()
                when (screen) {
                    "main" -> CloudSyncContent(
                        state = state,
                        onBackClick = ::finish,
                        now = now,
                        summary = CloudSyncSamples.summary,
                        files = CloudSyncSamples.files(now),
                        onSyncNow = controller::syncNow,
                        onOpenLocation = { screen = "location" },
                        onOpenFiles = { screen = "files" },
                        onOpenAccount = { screen = "account" },
                        onOpenGlobal = { screen = "global" },
                        onExport = { screen = "export" },
                        onImport = { screen = "import" },
                        onTransfer = {
                            xferArgs = it
                            screen = "xfer"
                        },
                        onTransferFromPrevious = { id ->
                            xferArgs = TransferArgs(sourceCloudScope = "sp-old", targetProfileId = "sp")
                            screen = "xfer"
                        },
                        onRemoveFromCloud = {},
                        onResumeAccount = controller::resumeAccountSync,
                    )
                    "files" -> CloudFilesPage(CloudDataLoad.Ready(CloudSyncSamples.files(now)), now, { home() }, {}, {})
                    "account" -> CloudAccountPage(
                        account = CloudSyncSamples.protoCloudOnly.first().let {
                            com.gpo.yoin.ui.settings.sync.CloudAccountRef(it.syncProfileId, it.provider, it.displayName, it.hint, it.fromDeviceName, cloudOnly = true)
                        },
                        content = CloudDataLoad.Ready(CloudSyncSamples.accountContent(now)),
                        counts = CloudSyncSamples.summary.scopes["sp-old"],
                        removing = false,
                        now = now,
                        onBack = { home() },
                        onTransfer = {
                            xferArgs = TransferArgs(sourceCloudScope = "sp-old")
                            screen = "xfer"
                        },
                        onRemove = {},
                    )
                    "global" -> CloudGlobalPage(CloudDataLoad.Ready(CloudSyncSamples.globalContent(now)), now, { home() })
                    "location" -> SyncLocationPage(account = "mika.demo@gmail.com", onBack = { home() })
                    "export", "import" -> {
                        val export = screen == "export"
                        val vm = remember(screen) {
                            BackupViewModel(PreviewMemoriesOps()) { true }.also {
                                if (export) {
                                    it.export("yoin-backup-2026-11-22.zip", open = { ByteArrayOutputStream() }, size = { 486 * 1024L })
                                } else {
                                    it.read("yoin-backup-2026-11-22.zip") { ByteArrayInputStream(ByteArray(0)) }
                                }
                            }
                        }
                        val backup by vm.state.collectAsState()
                        BackupPage(backup, { home() }, vm::choose, vm::confirmImport, { home() })
                    }
                    "xfer" -> {
                        val vm = remember(xferArgs) { TransferViewModel(PreviewMemoriesOps(), controller, xferArgs) }
                        val xfer by vm.state.collectAsState()
                        BackHandler { if (!vm.back()) home() }
                        TransferFlow(
                            state = xfer,
                            onBack = { if (!vm.back()) home() },
                            actions = TransferActions(
                                pickSource = vm::pickSource,
                                pickTarget = vm::pickTarget,
                                next = vm::next,
                                changeSource = vm::changeSource,
                                start = vm::start,
                                cancel = { home() },
                                setChecked = vm::setChecked,
                                choose = vm::choose,
                                copy = vm::copyReviewed,
                                setRemoveCard = vm::setRemoveCard,
                                clean = vm::cleanSource,
                                finish = { home() },
                            ),
                        )
                    }
                }
            }
        }
    }
}
