package com.gpo.yoin.ui.settings.sync

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.gpo.yoin.R
import com.gpo.yoin.data.profile.ProviderKind
import com.gpo.yoin.data.sync.CloudAccountRow
import com.gpo.yoin.data.sync.CloudDataSummary
import com.gpo.yoin.data.sync.CloudDeviceFiles
import com.gpo.yoin.data.sync.CloudSyncPhase
import com.gpo.yoin.data.sync.CloudSyncState
import com.gpo.yoin.data.sync.SyncAccountRow
import com.gpo.yoin.data.sync.SyncAccountStatus
import com.gpo.yoin.data.sync.SyncDeviceRow
import com.gpo.yoin.data.sync.auth.GoogleAccountPick
import com.gpo.yoin.data.sync.auth.GoogleAccountPicker
import com.gpo.yoin.data.sync.backup.fileName
import com.gpo.yoin.shared.portability.BackupFormat
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.MetaGroup
import com.gpo.yoin.ui.component.MetaLine
import com.gpo.yoin.ui.component.SeamTop
import com.gpo.yoin.ui.component.YoinDropdownMenu
import com.gpo.yoin.ui.component.YoinDropdownMenuItem
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.component.YoinSnackbarHost
import com.gpo.yoin.ui.component.handPointer
import com.gpo.yoin.ui.component.seamDissolveViewport
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.settings.LocalSettingsRowShape
import com.gpo.yoin.ui.settings.SettingsBackButton
import com.gpo.yoin.ui.settings.SettingsExpandableItem
import com.gpo.yoin.ui.settings.SettingsGroup
import com.gpo.yoin.ui.settings.SettingsGroupLabel
import com.gpo.yoin.ui.settings.SettingsItem
import com.gpo.yoin.ui.settings.SettingsPageBackground
import com.gpo.yoin.ui.settings.SettingsRowIcon
import com.gpo.yoin.ui.settings.SettingsRowTextInset
import com.gpo.yoin.ui.settings.SettingsSegments
import com.gpo.yoin.ui.settings.handoffProgress
import com.gpo.yoin.ui.settings.service.ServiceSetupContract
import com.gpo.yoin.ui.settings.service.ServiceSetupRequest
import com.gpo.yoin.ui.settings.service.SetupService
import com.gpo.yoin.ui.settings.serviceIdentity
import com.gpo.yoin.ui.settings.serviceLineGroups
import com.gpo.yoin.ui.settings.tone
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

// Settings › Cloud sync. Back surface: full-screen destination without shared
// chrome → Pattern A (native cross-Activity back); the arrow just finishes.

@Composable
fun CloudSyncScreen(
    viewModel: CloudSyncViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    accountPicker: GoogleAccountPicker? = null,
) {
    val activity = LocalActivity.current
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val turningOn by viewModel.turningOn.collectAsState()
    val deleting by viewModel.deleting.collectAsState()
    val summary by viewModel.summary.collectAsState()
    val files by viewModel.files.collectAsState()
    val removing by viewModel.removing.collectAsState()
    val preparingTransfer by viewModel.preparingTransfer.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result -> viewModel.onConsentResult(result.resultCode, result.data) }
    // Here the new account only matters to sync, so pull its data down at once.
    val serviceSetupLauncher = rememberLauncherForActivityResult(AddAccountContract()) { saved ->
        if (saved) viewModel.onAccountAdded()
    }
    // 下载全部云端数据 / 从备份文件导入: the system's own save and open sheets, then the backup page.
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupActivity.ZIP_MIME)) { uri ->
        if (uri != null) context.startActivity(BackupActivity.exportIntent(context, uri))
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) context.startActivity(BackupActivity.importIntent(context, uri))
    }
    // The counts and the file list follow the replica: read them on open and after every sync.
    LaunchedEffect(viewModel, state.enabled, state.lastSyncAt) { viewModel.refreshCloudData() }
    // Only while the page is started: Google's sheet must not be launched
    // from the background, and events wait in the ViewModel until then.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.events.collect { event ->
                when (event) {
                    CloudSyncEvent.PickAccount -> scope.launch {
                        val pick = try {
                            if (accountPicker != null && activity != null) {
                                accountPicker.pick(activity)
                            } else {
                                GoogleAccountPick.Unavailable
                            }
                        } catch (error: CancellationException) {
                            // The page went away with the sheet up (rotation, back): don't leave Turn on spinning.
                            viewModel.onAccountPicked(GoogleAccountPick.Canceled)
                            throw error
                        }
                        viewModel.onAccountPicked(pick)
                    }
                    is CloudSyncEvent.LaunchConsent -> try {
                        consentLauncher.launch(IntentSenderRequest.Builder(event.intentSender).build())
                    } catch (_: ActivityNotFoundException) {
                        viewModel.onConsentLaunchFailed()
                    }
                    is CloudSyncEvent.ShowMessage -> scope.launch { snackbarHostState.showSnackbar(event.message) }
                    is CloudSyncEvent.OpenTransfer -> context.startActivity(MemoriesTransferActivity.intent(context, event.args))
                }
            }
        }
    }
    CloudSyncContent(
        state = state,
        onBackClick = onBack,
        modifier = modifier,
        turningOn = turningOn,
        deleting = deleting,
        summary = summary,
        files = files,
        removing = removing,
        preparingTransfer = preparingTransfer,
        snackbarHostState = snackbarHostState,
        onTurnOn = viewModel::turnOn,
        onSyncNow = viewModel::syncNow,
        onTurnOff = viewModel::turnOff,
        onDeleteCloudData = viewModel::deleteCloudData,
        onResolveReview = viewModel::resolveReview,
        onUploadAgain = viewModel::uploadAgain,
        onStartSyncingAccount = viewModel::startSyncingAccount,
        onLinkAccount = viewModel::linkAccount,
        onKeepAccountAsBefore = viewModel::keepAccountAsBefore,
        onStartFreshAccount = viewModel::startFreshAccount,
        onTransferFromPrevious = viewModel::transferFromPreviousAccount,
        onAddCloudAccount = { service -> serviceSetupLauncher.launch(ServiceSetupRequest(service)) },
        onRemoveFromCloud = { account -> viewModel.removeFromCloud(account.syncProfileId, account.displayName) },
        onResumeAccount = viewModel::resumeAccountSync,
        onDismissMigrationNotice = viewModel::dismissMigrationNotice,
        onOpenLocation = { context.startActivity(SyncLocationActivity.intent(context)) },
        onOpenFiles = { context.startActivity(CloudDataActivity.filesIntent(context)) },
        onOpenAccount = { account -> context.startActivity(CloudDataActivity.accountIntent(context, account)) },
        onOpenGlobal = { context.startActivity(CloudDataActivity.globalIntent(context)) },
        onExport = {
            try {
                exportLauncher.launch(BackupFormat.fileName(System.currentTimeMillis()))
            } catch (_: ActivityNotFoundException) {
                Unit
            }
        },
        onImport = {
            try {
                importLauncher.launch(BackupActivity.OPEN_MIMES)
            } catch (_: ActivityNotFoundException) {
                Unit
            }
        },
        onTransfer = { args -> context.startActivity(MemoriesTransferActivity.intent(context, args)) },
    )
}

/**
 * [ServiceSetupContract]'s page, answering whether an account was saved at
 * all. ServiceSetupContract's own result is only the profile Settings should
 * switch to, which is null for the very first account (it's already active),
 * so it can't tell "added" from "backed out".
 */
internal class AddAccountContract : ActivityResultContract<ServiceSetupRequest, Boolean>() {
    private val setup = ServiceSetupContract()

    override fun createIntent(context: Context, input: ServiceSetupRequest): Intent =
        setup.createIntent(context, input)

    override fun parseResult(resultCode: Int, intent: Intent?): Boolean = resultCode == Activity.RESULT_OK
}

/**
 * The Cloud sync page for [state]. Dialog visibility is the page's own UI
 * state; everything else comes in through [state] and goes out through the
 * callbacks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudSyncContent(
    state: CloudSyncState,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    now: Long = rememberTickingNow(),
    turningOn: Boolean = false,
    deleting: Boolean = false,
    summary: CloudDataSummary? = null,
    files: List<CloudDeviceFiles>? = null,
    removing: Set<String> = emptySet(),
    preparingTransfer: String? = null,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onTurnOn: () -> Unit = {},
    onSyncNow: () -> Unit = {},
    onTurnOff: () -> Unit = {},
    onDeleteCloudData: () -> Unit = {},
    onResolveReview: (restore: Boolean) -> Unit = {},
    onUploadAgain: () -> Unit = {},
    onStartSyncingAccount: (localProfileId: String) -> Unit = {},
    onLinkAccount: (localProfileId: String, syncProfileId: String) -> Unit = { _, _ -> },
    onKeepAccountAsBefore: (localProfileId: String) -> Unit = {},
    onStartFreshAccount: (localProfileId: String) -> Unit = {},
    onTransferFromPrevious: (localProfileId: String) -> Unit = {},
    onAddCloudAccount: (SetupService) -> Unit = {},
    onRemoveFromCloud: (CloudAccountRef) -> Unit = {},
    onResumeAccount: (localProfileId: String) -> Unit = {},
    onDismissMigrationNotice: () -> Unit = {},
    onOpenLocation: () -> Unit = {},
    onOpenFiles: () -> Unit = {},
    onOpenAccount: (CloudAccountRef) -> Unit = {},
    onOpenGlobal: () -> Unit = {},
    onExport: () -> Unit = {},
    onImport: () -> Unit = {},
    onTransfer: (TransferArgs) -> Unit = {},
) {
    var reviewDialogOpen by rememberSaveable { mutableStateOf(false) }
    var deleteDialogOpen by rememberSaveable { mutableStateOf(false) }
    var startFreshProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    var changedProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    var removeScope by rememberSaveable { mutableStateOf<String?>(null) }

    // A dialog belongs to the state that offered it. When that state goes
    // away the request is dropped, so a dialog never reopens on its own later
    // or confirms something that no longer applies. A sync that runs while
    // the review is open keeps it open on the last known review.
    val liveReview = state.phase as? CloudSyncPhase.NeedsReview
    var lastReview by remember { mutableStateOf(liveReview) }
    val review = liveReview ?: lastReview?.takeIf { state.phase == CloudSyncPhase.Syncing }
    val startFreshAccount = startFreshProfileId?.let { id ->
        state.accounts.firstOrNull { it.localProfileId == id && it.status == SyncAccountStatus.AccountChanged }
    }
    val changedAccount = changedProfileId?.let { id ->
        state.accounts.firstOrNull { it.localProfileId == id && it.status == SyncAccountStatus.AccountChanged }
    }
    val removable = removeScope?.let { scope -> cloudAccountsOf(state).firstOrNull { it.syncProfileId == scope } }
    LaunchedEffect(state.phase) {
        when (val phase = state.phase) {
            is CloudSyncPhase.NeedsReview -> lastReview = phase
            CloudSyncPhase.Syncing -> Unit
            else -> {
                lastReview = null
                reviewDialogOpen = false
            }
        }
    }
    LaunchedEffect(state.enabled) { if (!state.enabled) deleteDialogOpen = false }
    LaunchedEffect(startFreshAccount == null) { if (startFreshAccount == null) startFreshProfileId = null }
    LaunchedEffect(changedAccount == null) { if (changedAccount == null) changedProfileId = null }
    LaunchedEffect(removable == null) { if (removable == null) removeScope = null }

    // Large title → app-bar title handoff, as on the other Settings pages.
    val viewportTop = remember { mutableFloatStateOf(0f) }
    val scroll = rememberScrollState()
    val headlineTop = remember { mutableFloatStateOf(Float.NaN) }
    val headlineHeight = remember { mutableFloatStateOf(1f) }
    ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
        SettingsPageBackground(modifier = modifier) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                text = stringResource(R.string.settings_sync_title_bar),
                                modifier = Modifier.graphicsLayer {
                                    alpha = handoffProgress(viewportTop, headlineTop, headlineHeight)
                                    translationY = (1f - alpha) * size.height * 0.5f
                                },
                            )
                        },
                        navigationIcon = { SettingsBackButton(onClick = onBackClick) },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                },
                snackbarHost = { YoinSnackbarHost(snackbarHostState) },
            ) { innerPadding ->
                val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                // Full width on every window, like the rest of the Settings family.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .onGloballyPositioned { viewportTop.floatValue = it.positionInWindow().y }
                        // Rows break up and text fades under the bar, as on Settings itself.
                        .seamDissolveViewport(top = SeamTop.Chrome) { scroll.value.toFloat() }
                        .verticalScroll(scroll)
                        .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp + navBottom),
                ) {
                    CloudSyncHeadline(
                        showLead = !state.enabled,
                        titleModifier = Modifier.onGloballyPositioned {
                            headlineTop.floatValue = it.positionInWindow().y
                            headlineHeight.floatValue = it.size.height.toFloat().coerceAtLeast(1f)
                        },
                    )
                    AnimatedContent(
                        targetState = state,
                        transitionSpec = {
                            YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                                YoinMotion.fadeOut(role = YoinMotionRole.Standard)
                        },
                        // Only On ⇄ Off swaps the page; updates inside either just recompose.
                        contentKey = { it.enabled },
                        label = "cloudSyncMode",
                        modifier = Modifier.fillMaxWidth(),
                    ) { current ->
                        if (current.enabled) {
                            CloudSyncOnContent(
                                state = current,
                                now = now,
                                turningOn = turningOn,
                                deleting = deleting,
                                summary = summary,
                                files = files,
                                removing = removing,
                                preparingTransfer = preparingTransfer,
                                actions = CloudSyncOnActions(
                                    onTurnOn = onTurnOn,
                                    onSyncNow = onSyncNow,
                                    onTurnOff = onTurnOff,
                                    onOpenReview = { reviewDialogOpen = true },
                                    onUploadAgain = onUploadAgain,
                                    onStartSyncingAccount = onStartSyncingAccount,
                                    onLinkAccount = onLinkAccount,
                                    onAddCloudAccount = onAddCloudAccount,
                                    onRequestDelete = { deleteDialogOpen = true },
                                    onOpenLocation = onOpenLocation,
                                    onOpenFiles = onOpenFiles,
                                    onOpenAccount = onOpenAccount,
                                    onOpenGlobal = onOpenGlobal,
                                    onExport = onExport,
                                    onImport = onImport,
                                    onTransfer = onTransfer,
                                    onRequestRemove = { removeScope = it.syncProfileId },
                                    onResumeAccount = onResumeAccount,
                                    onDismissMigrationNotice = onDismissMigrationNotice,
                                    onRequestChangedChoice = { changedProfileId = it },
                                ),
                            )
                        } else {
                            OffContent(
                                phase = current.phase,
                                turningOn = turningOn,
                                snackbarHostState = snackbarHostState,
                                onTurnOn = onTurnOn,
                                onImport = onImport,
                                onExport = onExport,
                            )
                        }
                    }
                }
            }
        }
    }

    review?.takeIf { reviewDialogOpen }?.let { review ->
        ReviewDialog(
            review = review,
            onDismiss = { reviewDialogOpen = false },
            onResolve = { restore ->
                reviewDialogOpen = false
                onResolveReview(restore)
            },
        )
    }
    if (deleteDialogOpen && state.enabled) {
        DeleteCloudDataDialog(
            onDismiss = { deleteDialogOpen = false },
            onConfirm = {
                deleteDialogOpen = false
                onDeleteCloudData()
            },
        )
    }
    changedAccount?.let { account ->
        AccountChangedDialog(
            account = account,
            onDismiss = { changedProfileId = null },
            onKeep = {
                changedProfileId = null
                onKeepAccountAsBefore(account.localProfileId)
            },
            onFresh = {
                changedProfileId = null
                startFreshProfileId = account.localProfileId
            },
            onTransfer = {
                changedProfileId = null
                onTransferFromPrevious(account.localProfileId)
            },
        )
    }
    startFreshAccount
        ?.let { account ->
            StartFreshDialog(
                account = account,
                onDismiss = { startFreshProfileId = null },
                onConfirm = {
                    startFreshProfileId = null
                    onStartFreshAccount(account.localProfileId)
                },
            )
        }
    removable?.let { account ->
        RemoveFromCloudDialog(
            account = account,
            counts = summary?.scopes?.get(account.syncProfileId),
            onDismiss = { removeScope = null },
            onConfirm = {
                removeScope = null
                onRemoveFromCloud(account)
            },
        )
    }
}

/**
 * The Settings sub-page header: where it sits (设置 › 同步与存储), the large title on the 24dp line, and while
 * sync is off, the one-line pitch under it.
 */
@Composable
private fun CloudSyncHeadline(showLead: Boolean, titleModifier: Modifier = Modifier) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(32.dp))
        Text(
            text = stringResource(R.string.settings_sync_crumb),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
        Text(
            text = stringResource(R.string.settings_sync_title),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = titleModifier
                .padding(horizontal = 8.dp)
                .semantics { heading() },
        )
        AnimatedVisibility(
            visible = showLead,
            enter = expandVertically(YoinMotion.spatialSpring(), expandFrom = Alignment.Top) +
                YoinMotion.fadeIn(role = YoinMotionRole.Standard),
            exit = shrinkVertically(YoinMotion.spatialSpring(), shrinkTowards = Alignment.Top) +
                YoinMotion.fadeOut(role = YoinMotionRole.Standard),
        ) {
            Text(
                text = stringResource(R.string.settings_sync_lead),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 12.dp),
            )
        }
        Spacer(Modifier.height(36.dp))
    }
}

// ── Off ──────────────────────────────────────────────────────────────

@Composable
private fun OffContent(
    phase: CloudSyncPhase,
    turningOn: Boolean,
    snackbarHostState: SnackbarHostState,
    onTurnOn: () -> Unit,
    onImport: () -> Unit = {},
    onExport: () -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
        if (phase is CloudSyncPhase.ResetElsewhere) {
            SettingsSegments {
                item {
                    val resetOn = phase.deviceName
                    TextRow(
                        icon = YoinSymbols.Info,
                        text = if (resetOn != null) {
                            stringResource(R.string.settings_sync_reset_on_device, resetOn)
                        } else {
                            stringResource(R.string.settings_sync_reset_other_device)
                        },
                        emphasized = true,
                    )
                }
            }
        }
        SettingsGroup(title = stringResource(R.string.settings_sync_section_what_you_get)) {
            item {
                SettingsItem(
                    icon = YoinSymbols.EditNote,
                    title = stringResource(R.string.settings_sync_benefit_notes),
                )
            }
            item {
                SettingsItem(
                    icon = YoinSymbols.Translate,
                    title = stringResource(R.string.settings_sync_benefit_translations),
                )
            }
            item {
                SettingsItem(
                    icon = YoinSymbols.Settings,
                    title = stringResource(R.string.settings_sync_benefit_settings),
                )
            }
            item {
                SettingsItem(
                    icon = YoinSymbols.CloudOffline,
                    title = stringResource(R.string.settings_sync_benefit_offline),
                )
            }
        }
        SettingsGroup(title = stringResource(R.string.settings_sync_section_good_to_know)) {
            item {
                TextRow(icon = YoinSymbols.Folder, text = stringResource(R.string.settings_sync_know_drive))
            }
            item {
                TextRow(icon = YoinSymbols.Person, text = stringResource(R.string.settings_sync_know_secrets))
            }
            item { TextRow(icon = YoinSymbols.Insights, text = stringResource(R.string.settings_sync_know_history)) }
        }
        // A backup imports without Cloud sync too (no Google services, or moving from another service).
        BackupGroup(
            actions = CloudSyncOnActions(onImport = onImport, onExport = onExport),
            includeTransfer = false,
            titleRes = R.string.settings_sync_section_backup_file,
            exportTitleRes = R.string.settings_sync_export_title_local,
        )
        // The action changes with what this device can do; keyed on the kind
        // so a turn-on that comes back "not set up" swaps in place.
        AnimatedContent(
            targetState = phase,
            transitionSpec = {
                YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                    YoinMotion.fadeOut(role = YoinMotionRole.Standard)
            },
            contentKey = { it::class },
            label = "cloudSyncOffAction",
            modifier = Modifier.fillMaxWidth(),
        ) { current ->
            when (current) {
                is CloudSyncPhase.Unavailable -> SettingsSegments {
                    item {
                        TextRow(
                            icon = YoinSymbols.Error,
                            text = stringResource(
                                if (current.updateRequired) {
                                    R.string.settings_sync_play_update
                                } else {
                                    R.string.settings_sync_play_missing
                                },
                            ),
                            emphasized = true,
                        )
                    }
                }
                is CloudSyncPhase.Misconfigured -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsSegments {
                        item {
                            TextRow(
                                icon = YoinSymbols.Error,
                                text = stringResource(R.string.settings_sync_unavailable),
                                emphasized = true,
                            )
                        }
                        item {
                            BuildDetailsRow(
                                packageName = current.packageName,
                                certSha1 = current.certSha1,
                                snackbarHostState = snackbarHostState,
                            )
                        }
                    }
                    // Once this build's certificate is registered with Google, trying again works.
                    OutlinedButton(
                        onClick = onTurnOn,
                        enabled = !turningOn,
                        modifier = Modifier.fillMaxWidth().handPointer(enabled = !turningOn),
                    ) {
                        Text(
                            stringResource(
                                if (turningOn) {
                                    R.string.settings_sync_waiting_retry
                                } else {
                                    R.string.settings_sync_try_again
                                },
                            ),
                        )
                    }
                }
                else -> TurnOnBlock(turningOn = turningOn, onTurnOn = onTurnOn)
            }
        }
    }
}

@Composable
private fun TurnOnBlock(turningOn: Boolean, onTurnOn: () -> Unit) {
    val haptics = rememberYoinHaptics()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = {
                haptics.performConfirm()
                onTurnOn()
            },
            enabled = !turningOn,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("cloud_sync_turn_on")
                .handPointer(enabled = !turningOn),
        ) {
            Text(
                stringResource(
                    if (turningOn) R.string.settings_sync_waiting_turn_on else R.string.settings_sync_turn_on,
                ),
            )
        }
    }
}

/** Package + signing certificate for the "not set up for this build" case. */
@Composable
private fun BuildDetailsRow(
    packageName: String,
    certSha1: String?,
    snackbarHostState: SnackbarHostState,
) {
    val clipboard = LocalClipboard.current
    val haptics = rememberYoinHaptics()
    val scope = rememberCoroutineScope()
    val sha1 = certSha1?.let(::formatCertSha1) ?: stringResource(R.string.settings_sync_cert_unknown)
    val clipLabel = stringResource(R.string.settings_sync_clip_label)
    val clipBody = stringResource(R.string.settings_sync_clip_body, packageName, sha1)
    val copied = stringResource(R.string.settings_sync_copied)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsRowIcon(YoinSymbols.Code)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.settings_sync_details),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.settings_sync_unrecognized),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.settings_sync_fingerprint, packageName, wrapFingerprint(sha1)),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        IconButton(
            onClick = {
                haptics.performClick()
                scope.launch {
                    clipboard.setClipEntry(
                        ClipData.newPlainText(clipLabel, clipBody).toClipEntry(),
                    )
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        snackbarHostState.showSnackbar(copied)
                    }
                }
            },
            modifier = Modifier.handPointer(),
        ) {
            Icon(
                imageVector = YoinSymbols.Copy,
                contentDescription = stringResource(R.string.settings_cd_copy_build),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "AB:CD:…" in lines of ten pairs, so a SHA-1 breaks between pairs instead of mid-hex. */
private fun wrapFingerprint(hex: String): String =
    hex.split(':').chunked(10).joinToString(":\n") { it.joinToString(":") }

/** A row that's a sentence, not a setting: icon and wrapping body text. */
@Composable
private fun TextRow(icon: ImageVector, text: String, emphasized: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsRowIcon(icon)
        Text(
            text = text,
            style = if (emphasized) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
            color = if (emphasized) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}


// ── Dialogs ──────────────────────────────────────────────────────────

@Composable
private fun ReviewDialog(
    review: CloudSyncPhase.NeedsReview,
    onDismiss: () -> Unit,
    onResolve: (restore: Boolean) -> Unit,
) {
    val haptics = rememberYoinHaptics()
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(imageVector = YoinSymbols.Note, contentDescription = null) },
        title = {
            Text(pluralStringResource(R.plurals.settings_sync_review_dialog_title, review.count, review.count))
        },
        text = {
            Text(
                pluralStringResource(
                    R.plurals.settings_sync_review_dialog_body,
                    review.count,
                    review.accountName,
                ),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    haptics.performConfirm()
                    onResolve(true)
                },
                modifier = Modifier.handPointer(),
            ) { Text(stringResource(R.string.settings_sync_review_restore)) }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    haptics.performReject()
                    onResolve(false)
                },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.handPointer(),
            ) { Text(stringResource(R.string.settings_sync_review_delete_all)) }
        },
    )
}

@Composable
private fun DeleteCloudDataDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val haptics = rememberYoinHaptics()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_sync_delete_dialog_title)) },
        text = { Text(stringResource(R.string.settings_sync_delete_dialog_body)) },
        confirmButton = {
            TextButton(
                onClick = {
                    haptics.performReject()
                    onConfirm()
                },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.handPointer(),
            ) { Text(stringResource(R.string.settings_sync_delete_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.handPointer()) { Text(stringResource(R.string.settings_sync_delete_cancel)) }
        },
    )
}

@Composable
private fun StartFreshDialog(account: SyncAccountRow, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val haptics = rememberYoinHaptics()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_sync_fresh_title)) },
        text = { Text(stringResource(R.string.settings_sync_fresh_body, account.displayName)) },
        confirmButton = {
            TextButton(
                onClick = {
                    haptics.performReject()
                    onConfirm()
                },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.handPointer(),
            ) { Text(stringResource(R.string.settings_sync_fresh_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.handPointer()) { Text(stringResource(R.string.settings_sync_fresh_cancel)) }
        },
    )
}

@Composable
internal fun RemoveDeviceDialog(deviceName: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val haptics = rememberYoinHaptics()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_sync_remove_device_title, deviceName)) },
        text = { Text(stringResource(R.string.settings_sync_remove_device_body)) },
        confirmButton = {
            TextButton(
                onClick = {
                    haptics.performReject()
                    onConfirm()
                },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.handPointer(),
            ) { Text(stringResource(R.string.settings_sync_remove_device_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.handPointer()) { Text(stringResource(R.string.settings_sync_remove_device_cancel)) }
        },
    )
}
