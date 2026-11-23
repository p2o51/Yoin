package com.gpo.yoin.ui.settings.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.yoin.R
import com.gpo.yoin.YoinActivityRoot
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.enableYoinEdgeToEdge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * The Cloud sync page's sub-pages, each its own Activity like the rest of Settings (Pattern A: native
 * cross-Activity predictive back; the bar's arrow finishes).
 */

/** 全部文件, one account's cloud data, or 全局数据. */
class CloudDataActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableYoinEdgeToEdge()
        val controller = (application as YoinApplication).container.cloudSync
        val page = pageOf(intent)
        setContent {
            YoinActivityRoot {
                val viewModel: CloudDataViewModel = viewModel(factory = CloudDataViewModel.Factory(controller, page))
                val snackbar = remember { SnackbarHostState() }
                val context = LocalContext.current
                val resources = LocalResources.current
                val now = rememberTickingNow()
                LaunchedEffect(viewModel) {
                    viewModel.events.collect { event ->
                        when (event) {
                            is CloudDataEvent.DeviceRemoved ->
                                snackbar.showSnackbar(resources.getString(R.string.settings_sync_device_removed, event.name))
                            is CloudDataEvent.AccountRemoved -> {
                                Toast.makeText(context, resources.getString(R.string.settings_sync_removed, event.name), Toast.LENGTH_SHORT).show()
                                finish()
                            }
                            is CloudDataEvent.RemoveFailed ->
                                snackbar.showSnackbar(resources.getString(R.string.settings_sync_remove_failed, event.name))
                        }
                    }
                }
                when (page) {
                    CloudDataPage.Files -> {
                        val files by viewModel.files.collectAsState()
                        CloudFilesPage(
                            files = files,
                            now = now,
                            onBack = ::finish,
                            onRetry = viewModel::load,
                            onRemoveDevice = { viewModel.removeDevice(it.deviceId, it.deviceName) },
                            snackbarHostState = snackbar,
                        )
                    }
                    is CloudDataPage.Account -> {
                        val content by viewModel.account.collectAsState()
                        val summary by viewModel.summary.collectAsState()
                        val removing by viewModel.removing.collectAsState()
                        CloudAccountPage(
                            account = page.account,
                            content = content,
                            counts = summary?.scopes?.get(page.account.syncProfileId),
                            removing = removing,
                            now = now,
                            onBack = ::finish,
                            onTransfer = { startActivity(MemoriesTransferActivity.intent(context, transferArgsFor(page.account))) },
                            onRemove = viewModel::removeAccount,
                            onRetry = viewModel::load,
                            snackbarHostState = snackbar,
                        )
                    }
                    CloudDataPage.Global -> {
                        val content by viewModel.global.collectAsState()
                        CloudGlobalPage(content = content, now = now, onBack = ::finish, onRetry = viewModel::load)
                    }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_PAGE = "page"
        private const val EXTRA_SCOPE = "scope"
        private const val EXTRA_PROVIDER = "provider"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_HINT = "hint"
        private const val EXTRA_FROM = "from"
        private const val EXTRA_CLOUD_ONLY = "cloudOnly"
        private const val EXTRA_PROFILE = "profile"

        fun filesIntent(context: Context): Intent = Intent(context, CloudDataActivity::class.java).putExtra(EXTRA_PAGE, "files")

        fun globalIntent(context: Context): Intent = Intent(context, CloudDataActivity::class.java).putExtra(EXTRA_PAGE, "global")

        fun accountIntent(context: Context, account: CloudAccountRef): Intent = Intent(context, CloudDataActivity::class.java)
            .putExtra(EXTRA_PAGE, "account")
            .putExtra(EXTRA_SCOPE, account.syncProfileId)
            .putExtra(EXTRA_PROVIDER, account.provider)
            .putExtra(EXTRA_NAME, account.displayName)
            .putExtra(EXTRA_HINT, account.hint)
            .putExtra(EXTRA_FROM, account.fromDevice)
            .putExtra(EXTRA_CLOUD_ONLY, account.cloudOnly)
            .putExtra(EXTRA_PROFILE, account.localProfileId)

        private fun pageOf(intent: Intent): CloudDataPage = when (intent.getStringExtra(EXTRA_PAGE)) {
            "account" -> CloudDataPage.Account(
                CloudAccountRef(
                    syncProfileId = intent.getStringExtra(EXTRA_SCOPE).orEmpty(),
                    provider = intent.getStringExtra(EXTRA_PROVIDER).orEmpty(),
                    displayName = intent.getStringExtra(EXTRA_NAME).orEmpty(),
                    hint = intent.getStringExtra(EXTRA_HINT),
                    fromDevice = intent.getStringExtra(EXTRA_FROM),
                    cloudOnly = intent.getBooleanExtra(EXTRA_CLOUD_ONLY, false),
                    localProfileId = intent.getStringExtra(EXTRA_PROFILE),
                ),
            )
            "global" -> CloudDataPage.Global
            else -> CloudDataPage.Files
        }
    }
}

/** 同步位置: Google Drive today; iCloud and Lyra listed as coming soon. */
class SyncLocationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableYoinEdgeToEdge()
        val controller = (application as YoinApplication).container.cloudSync
        setContent {
            YoinActivityRoot {
                val state by controller.state.collectAsState()
                SyncLocationPage(account = state.account?.email ?: state.account?.displayName, onBack = ::finish)
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, SyncLocationActivity::class.java)
    }
}

/** A backup's export or import, after the system's save / open sheet picked the file ([Intent.getData]). */
class BackupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableYoinEdgeToEdge()
        val container = (application as YoinApplication).container
        val uri = intent.data
        val export = intent.getBooleanExtra(EXTRA_EXPORT, false)
        if (uri == null) {
            finish()
            return
        }
        setContent {
            YoinActivityRoot {
                val viewModel: BackupViewModel = viewModel(
                    factory = BackupViewModel.Factory(AppMemoriesOps(container)) { container.cloudSync.state.value.enabled },
                )
                val state by viewModel.state.collectAsState()
                val snackbar = remember { SnackbarHostState() }
                val context = LocalContext.current
                val resources = LocalResources.current
                LaunchedEffect(viewModel) {
                    if (viewModel.state.value != BackupUiState.Idle) return@LaunchedEffect
                    val name = displayName(uri) ?: uri.lastPathSegment.orEmpty()
                    if (export) {
                        viewModel.export(
                            fileName = name,
                            open = { withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri, "wt") } },
                            size = { withContext(Dispatchers.IO) { sizeOf(uri) } },
                        )
                    } else {
                        viewModel.read(name) { withContext(Dispatchers.IO) { contentResolver.openInputStream(uri) } }
                    }
                }
                val busyText = resources.getString(R.string.settings_sync_busy)
                val back = {
                    if (viewModel.busy) {
                        Toast.makeText(context, busyText, Toast.LENGTH_SHORT).show()
                    } else {
                        finish()
                    }
                }
                BackHandler(enabled = viewModel.busy) { back() }
                BackupPage(
                    state = state,
                    onBack = back,
                    onChoose = viewModel::choose,
                    onConfirmImport = viewModel::confirmImport,
                    onDone = ::finish,
                    snackbarHostState = snackbar,
                )
            }
        }
    }

    private suspend fun displayName(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    private fun sizeOf(uri: Uri): Long? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    }.getOrNull()

    companion object {
        const val ZIP_MIME = "application/zip"

        /** What the open sheet offers: zips, and files some providers label generically. */
        val OPEN_MIMES = arrayOf(ZIP_MIME, "application/x-zip-compressed", "application/octet-stream")

        private const val EXTRA_EXPORT = "export"

        fun exportIntent(context: Context, uri: Uri): Intent = Intent(context, BackupActivity::class.java)
            .setData(uri)
            .putExtra(EXTRA_EXPORT, true)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)

        fun importIntent(context: Context, uri: Uri): Intent = Intent(context, BackupActivity::class.java)
            .setData(uri)
            .putExtra(EXTRA_EXPORT, false)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

/** 转移 Memories, from any entry: the account card, the account's page, or a re-sign-in as someone else. */
class MemoriesTransferActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableYoinEdgeToEdge()
        val container = (application as YoinApplication).container
        val args = TransferArgs(
            sourceProfileId = intent.getStringExtra(EXTRA_SOURCE_PROFILE),
            sourceCloudScope = intent.getStringExtra(EXTRA_SOURCE_CLOUD),
            targetProfileId = intent.getStringExtra(EXTRA_TARGET_PROFILE),
            sourceProvider = intent.getStringExtra(EXTRA_SOURCE_PROVIDER),
        )
        setContent {
            YoinActivityRoot {
                val viewModel: TransferViewModel = viewModel(
                    factory = TransferViewModel.Factory(AppMemoriesOps(container), container.cloudSync, args),
                )
                val state by viewModel.state.collectAsState()
                val snackbar = remember { SnackbarHostState() }
                val context = LocalContext.current
                val resources = LocalResources.current
                LaunchedEffect(viewModel) {
                    viewModel.events.collect { event ->
                        when (event) {
                            is TransferEvent.Finished -> {
                                event.message?.let { message ->
                                    val text = when (message) {
                                        is TransferFinishMessage.Copied -> resources.getString(R.string.settings_xfer_finished, message.sourceName)
                                        is TransferFinishMessage.CardRemoved ->
                                            resources.getString(R.string.settings_xfer_card_removed, message.sourceName)
                                    }
                                    Toast.makeText(context, text, Toast.LENGTH_LONG).show()
                                }
                                finish()
                            }
                            is TransferEvent.Message -> snackbar.showSnackbar(
                                when (val text = event.text) {
                                    is TransferMessage.Cleaned -> resources.getString(R.string.settings_xfer_cleaned, text.sourceName)
                                    is TransferMessage.CleanFailed -> resources.getString(R.string.settings_sync_remove_failed, text.sourceName)
                                    TransferMessage.Busy -> resources.getString(R.string.settings_sync_busy)
                                },
                            )
                        }
                    }
                }
                val onBack = { if (!viewModel.back()) finish() }
                BackHandler { onBack() }
                TransferFlow(
                    state = state,
                    onBack = onBack,
                    actions = TransferActions(
                        pickSource = viewModel::pickSource,
                        pickTarget = viewModel::pickTarget,
                        next = viewModel::next,
                        changeSource = viewModel::changeSource,
                        start = viewModel::start,
                        cancel = viewModel::cancel,
                        setChecked = viewModel::setChecked,
                        choose = viewModel::choose,
                        copy = viewModel::copyReviewed,
                        setRemoveCard = viewModel::setRemoveCard,
                        clean = viewModel::cleanSource,
                        finish = viewModel::finish,
                        addAccount = { startActivity(com.gpo.yoin.ui.settings.SettingsActivity.intent(context)) },
                    ),
                    snackbarHostState = snackbar,
                )
            }
        }
    }

    companion object {
        private const val EXTRA_SOURCE_PROFILE = "sourceProfile"
        private const val EXTRA_SOURCE_CLOUD = "sourceCloud"
        private const val EXTRA_TARGET_PROFILE = "targetProfile"
        private const val EXTRA_SOURCE_PROVIDER = "sourceProvider"

        fun intent(context: Context, args: TransferArgs = TransferArgs()): Intent =
            Intent(context, MemoriesTransferActivity::class.java)
                .putExtra(EXTRA_SOURCE_PROFILE, args.sourceProfileId)
                .putExtra(EXTRA_SOURCE_CLOUD, args.sourceCloudScope)
                .putExtra(EXTRA_TARGET_PROFILE, args.targetProfileId)
                .putExtra(EXTRA_SOURCE_PROVIDER, args.sourceProvider)
    }
}
