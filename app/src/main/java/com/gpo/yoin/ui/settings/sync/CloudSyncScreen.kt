package com.gpo.yoin.ui.settings.sync

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
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
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
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
import com.gpo.yoin.data.sync.CloudSyncPhase
import com.gpo.yoin.data.sync.CloudSyncState
import com.gpo.yoin.data.sync.SyncAccountRow
import com.gpo.yoin.data.sync.SyncAccountStatus
import com.gpo.yoin.data.sync.SyncDeviceRow
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.MetaGroup
import com.gpo.yoin.ui.component.MetaLine
import com.gpo.yoin.ui.component.YoinDropdownMenu
import com.gpo.yoin.ui.component.YoinDropdownMenuItem
import com.gpo.yoin.ui.component.YoinLoadingIndicator
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
import kotlinx.coroutines.launch

// Settings › Cloud sync. Back surface: full-screen destination without shared
// chrome → Pattern A (native cross-Activity back); the arrow just finishes.

@Composable
fun CloudSyncScreen(
    viewModel: CloudSyncViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val turningOn by viewModel.turningOn.collectAsState()
    val deleting by viewModel.deleting.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result -> viewModel.onConsentResult(result.resultCode, result.data) }
    // Here the new account only matters to sync, so pull its data down at once.
    val serviceSetupLauncher = rememberLauncherForActivityResult(AddAccountContract()) { saved ->
        if (saved) viewModel.onAccountAdded()
    }
    // Only while the page is started: Google's sheet must not be launched
    // from the background, and events wait in the ViewModel until then.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.events.collect { event ->
                when (event) {
                    is CloudSyncEvent.LaunchConsent -> try {
                        consentLauncher.launch(IntentSenderRequest.Builder(event.intentSender).build())
                    } catch (_: ActivityNotFoundException) {
                        viewModel.onConsentLaunchFailed()
                    }
                    is CloudSyncEvent.ShowMessage -> scope.launch { snackbarHostState.showSnackbar(event.message) }
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
        onAddCloudAccount = { service -> serviceSetupLauncher.launch(ServiceSetupRequest(service)) },
        onRemoveDevice = viewModel::removeDevice,
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
    onAddCloudAccount: (SetupService) -> Unit = {},
    onRemoveDevice: (deviceId: String) -> Unit = {},
) {
    var reviewDialogOpen by rememberSaveable { mutableStateOf(false) }
    var deleteDialogOpen by rememberSaveable { mutableStateOf(false) }
    var startFreshProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    var removeDeviceId by rememberSaveable { mutableStateOf<String?>(null) }

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
    val removableDevice = removeDeviceId?.let { id ->
        state.devices.firstOrNull { it.deviceId == id && !it.isThisDevice }
    }
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
    LaunchedEffect(removableDevice == null) { if (removableDevice == null) removeDeviceId = null }

    // Large title → app-bar title handoff, as on the other Settings pages.
    val viewportTop = remember { mutableFloatStateOf(0f) }
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
                snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(it) } },
            ) { innerPadding ->
                val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                // Full width on every window, like the rest of the Settings family.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .onGloballyPositioned { viewportTop.floatValue = it.positionInWindow().y }
                        .verticalScroll(rememberScrollState())
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
                            OnContent(
                                state = current,
                                now = now,
                                turningOn = turningOn,
                                deleting = deleting,
                                onTurnOn = onTurnOn,
                                onSyncNow = onSyncNow,
                                onTurnOff = onTurnOff,
                                onOpenReview = { reviewDialogOpen = true },
                                onUploadAgain = onUploadAgain,
                                onStartSyncingAccount = onStartSyncingAccount,
                                onLinkAccount = onLinkAccount,
                                onKeepAccountAsBefore = onKeepAccountAsBefore,
                                onRequestStartFresh = { startFreshProfileId = it },
                                onAddCloudAccount = onAddCloudAccount,
                                onRequestRemoveDevice = { removeDeviceId = it },
                                onRequestDelete = { deleteDialogOpen = true },
                            )
                        } else {
                            OffContent(
                                phase = current.phase,
                                turningOn = turningOn,
                                snackbarHostState = snackbarHostState,
                                onTurnOn = onTurnOn,
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
    removableDevice
        ?.let { device ->
            RemoveDeviceDialog(
                device = device,
                onDismiss = { removeDeviceId = null },
                onConfirm = {
                    removeDeviceId = null
                    onRemoveDevice(device.deviceId)
                },
            )
        }
}

/**
 * The Settings sub-page header (large title on the 24dp line, same air as
 * `SettingsHeadline`) plus, while sync is off, the one-line pitch under it.
 */
@Composable
private fun CloudSyncHeadline(showLead: Boolean, titleModifier: Modifier = Modifier) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(40.dp))
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
                        modifier = Modifier.fillMaxWidth(),
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
                .testTag("cloud_sync_turn_on"),
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

// ── On ───────────────────────────────────────────────────────────────

@Composable
private fun OnContent(
    state: CloudSyncState,
    now: Long,
    turningOn: Boolean,
    deleting: Boolean,
    onTurnOn: () -> Unit,
    onSyncNow: () -> Unit,
    onTurnOff: () -> Unit,
    onOpenReview: () -> Unit,
    onUploadAgain: () -> Unit,
    onStartSyncingAccount: (String) -> Unit,
    onLinkAccount: (String, String) -> Unit,
    onKeepAccountAsBefore: (String) -> Unit,
    onRequestStartFresh: (String) -> Unit,
    onAddCloudAccount: (SetupService) -> Unit,
    onRequestRemoveDevice: (String) -> Unit,
    onRequestDelete: () -> Unit,
) {
    val haptics = rememberYoinHaptics()
    Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
        SettingsSegments {
            item(key = "status") { StatusRow(state = state, now = now, onSyncNow = onSyncNow) }
            when (val phase = state.phase) {
                CloudSyncPhase.NeedsReauth -> item(key = "reauth") {
                    ActionPanel(
                        text = stringResource(R.string.settings_sync_reauth_body),
                    ) {
                        Button(
                            onClick = {
                                haptics.performConfirm()
                                onTurnOn()
                            },
                            enabled = !turningOn,
                        ) {
                            Text(stringResource(R.string.settings_sync_reconnect))
                        }
                    }
                }
                is CloudSyncPhase.NeedsReview -> item(key = "review") {
                    SettingsItem(
                        icon = YoinSymbols.Note,
                        title = pluralStringResource(
                            R.plurals.settings_sync_review_row_title,
                            phase.count,
                            phase.count,
                        ),
                        summary = stringResource(R.string.settings_sync_review_summary, phase.accountName),
                        onClick = onOpenReview,
                        trailing = {
                            Icon(
                                imageVector = YoinSymbols.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
                CloudSyncPhase.CloudCopyRemoved -> item(key = "copyRemoved") {
                    ActionPanel(
                        text = stringResource(R.string.settings_sync_copy_removed_body),
                    ) {
                        Button(
                            onClick = {
                                haptics.performConfirm()
                                onUploadAgain()
                            },
                        ) { Text(stringResource(R.string.settings_sync_upload_again)) }
                        OutlinedButton(
                            onClick = {
                                haptics.performClick()
                                onTurnOff()
                            },
                        ) { Text(stringResource(R.string.settings_sync_turn_off_short)) }
                    }
                }
                else -> Unit
            }
            item(key = "account") {
                val account = state.account
                val googleAccount = stringResource(R.string.settings_sync_google_account)
                val title = account?.email ?: account?.displayName ?: googleAccount
                SettingsItem(
                    icon = YoinSymbols.Person,
                    title = title,
                    summary = googleAccount.takeIf { title != googleAccount },
                )
            }
        }

        if (state.accounts.isNotEmpty()) {
            SettingsGroup(title = stringResource(R.string.settings_sync_section_accounts)) {
                state.accounts.forEach { account ->
                    item(key = account.localProfileId) {
                        AccountRow(
                            account = account,
                            onStartSyncing = onStartSyncingAccount,
                            onLink = onLinkAccount,
                            onKeepAsBefore = onKeepAccountAsBefore,
                            onRequestStartFresh = onRequestStartFresh,
                        )
                    }
                }
            }
        }

        if (state.cloudOnlyAccounts.isNotEmpty()) {
            SettingsGroup(title = stringResource(R.string.settings_sync_section_cloud_only)) {
                state.cloudOnlyAccounts.forEach { cloud ->
                    item(key = cloud.syncProfileId) {
                        CloudAccountItem(
                            cloud = cloud,
                            // Apple Music never auto-binds: a local Apple profile that isn't
                            // syncing can take this Drive account over instead of adding one.
                            linkTargets = state.accounts.filter { local ->
                                val status = local.status
                                status is SyncAccountStatus.NotSynced &&
                                    status.linkCandidates.any { it.syncProfileId == cloud.syncProfileId }
                            },
                            onAdd = onAddCloudAccount,
                            onLink = onLinkAccount,
                        )
                    }
                }
            }
        }

        if (state.devices.isNotEmpty()) {
            SettingsGroup(title = stringResource(R.string.settings_sync_section_devices)) {
                state.devices.sortedByDescending { it.isThisDevice }.forEach { device ->
                    item(key = device.deviceId) {
                        DeviceItem(device = device, now = now, onRequestRemove = onRequestRemoveDevice)
                    }
                }
            }
        }

        SettingsSegments {
            item(key = "whatSyncs") { WhatSyncsItem() }
            item(key = "turnOff") {
                SettingsItem(
                    icon = YoinSymbols.CloudOffline,
                    title = stringResource(R.string.settings_sync_turn_off_title),
                    summary = stringResource(R.string.settings_sync_turn_off_summary),
                    onClick = onTurnOff,
                )
            }
            item(key = "delete") {
                DestructiveItem(
                    icon = YoinSymbols.Delete,
                    title = stringResource(R.string.settings_sync_delete_title),
                    summary = if (deleting) stringResource(R.string.settings_sync_deleting) else null,
                    enabled = !deleting,
                    onClick = onRequestDelete,
                )
            }
        }
    }
}

private enum class StatusVisual(val icon: ImageVector, val problem: Boolean) {
    Syncing(YoinSymbols.Refresh, problem = false),
    UpToDate(YoinSymbols.Check, problem = false),
    Pending(YoinSymbols.Cloud, problem = false),
    Offline(YoinSymbols.CloudOffline, problem = false),
    Attention(YoinSymbols.Info, problem = false),
    Problem(YoinSymbols.Error, problem = true),
    Storage(YoinSymbols.Storage, problem = true),
    Removed(YoinSymbols.CloudOffline, problem = true),
}

private fun statusVisualOf(state: CloudSyncState): StatusVisual = when (state.phase) {
    CloudSyncPhase.Syncing -> StatusVisual.Syncing
    CloudSyncPhase.UpToDate -> if (state.pendingChanges) StatusVisual.Pending else StatusVisual.UpToDate
    CloudSyncPhase.Offline -> StatusVisual.Offline
    CloudSyncPhase.StorageFull -> StatusVisual.Storage
    CloudSyncPhase.CloudCopyRemoved -> StatusVisual.Removed
    is CloudSyncPhase.NeedsReview, is CloudSyncPhase.UpdateRequired -> StatusVisual.Attention
    else -> StatusVisual.Problem
}

/** Where sync stands: icon by state, what's happening, when it last synced, and Sync now. */
@Composable
private fun StatusRow(state: CloudSyncState, now: Long, onSyncNow: () -> Unit) {
    val text = cloudSyncStatusText(state, now, LocalContext.current.resources)
    val visual = statusVisualOf(state)
    val haptics = rememberYoinHaptics()
    val tint by animateColorAsState(
        targetValue = if (visual.problem) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = YoinMotion.effectsSpring(),
        label = "cloudSyncStatusTint",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            AnimatedContent(
                targetState = visual,
                transitionSpec = {
                    YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                        YoinMotion.fadeOut(role = YoinMotionRole.Standard)
                },
                contentAlignment = Alignment.Center,
                label = "cloudSyncStatusIcon",
            ) { current ->
                if (current == StatusVisual.Syncing) {
                    YoinLoadingIndicator(size = 32.dp)
                } else {
                    Icon(
                        imageVector = current.icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .animateContentSize(YoinMotion.spatialSpring()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = text.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            text.lines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.lastSummary?.let { summary ->
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        IconButton(
            onClick = {
                haptics.performClick()
                onSyncNow()
            },
            enabled = state.phase != CloudSyncPhase.Syncing,
        ) {
            Icon(
                imageVector = YoinSymbols.Refresh,
                contentDescription = stringResource(R.string.settings_cd_sync_now),
            )
        }
    }
}

/** A problem that needs a decision: what happened, then the buttons. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionPanel(text: String, actions: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = SettingsRowTextInset, end = 16.dp, top = 14.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { actions() }
    }
}

// ── Accounts ─────────────────────────────────────────────────────────

@Composable
private fun accountStatusText(status: SyncAccountStatus): String = when (status) {
    SyncAccountStatus.Syncing -> stringResource(R.string.settings_sync_account_syncing)
    SyncAccountStatus.NeedsIdentity -> stringResource(R.string.settings_sync_account_needs_identity)
    SyncAccountStatus.NeedsReconnect -> stringResource(R.string.settings_sync_account_reconnect)
    is SyncAccountStatus.DuplicateOf ->
        stringResource(R.string.settings_sync_account_duplicate, status.otherDisplayName)
    is SyncAccountStatus.NotSynced -> stringResource(R.string.settings_sync_account_not_synced)
    SyncAccountStatus.AccountChanged -> stringResource(R.string.settings_sync_account_changed)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountRow(
    account: SyncAccountRow,
    onStartSyncing: (String) -> Unit,
    onLink: (String, String) -> Unit,
    onKeepAsBefore: (String) -> Unit,
    onRequestStartFresh: (String) -> Unit,
) {
    val identity = ProviderKind.fromKey(account.provider)?.serviceIdentity
    val status = account.status
    val haptics = rememberYoinHaptics()
    Column(modifier = Modifier.fillMaxWidth().animateContentSize(YoinMotion.spatialSpring())) {
        SettingsItem(
            icon = identity?.glyph ?: YoinSymbols.Person,
            iconTone = identity?.hue?.tone(),
            title = account.displayName,
            summaryContent = {
                val groups = identity?.let {
                    serviceLineGroups(stringResource(it.nameRes), account.displayName, null)
                }
                if (!groups.isNullOrEmpty()) {
                    MetaLine(groups = groups, style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    text = accountStatusText(status),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            },
        )
        val hasActions = status is SyncAccountStatus.NotSynced || status == SyncAccountStatus.AccountChanged
        if (hasActions) {
            FlowRow(
                modifier = Modifier.padding(start = SettingsRowTextInset, end = 16.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (status) {
                    is SyncAccountStatus.NotSynced -> {
                        FilledTonalButton(
                            onClick = {
                                haptics.performConfirm()
                                onStartSyncing(account.localProfileId)
                            },
                        ) { Text(stringResource(R.string.settings_sync_start_syncing)) }
                        LinkButton(
                            candidates = status.linkCandidates,
                            onLink = { cloud -> onLink(account.localProfileId, cloud.syncProfileId) },
                        )
                    }
                    SyncAccountStatus.AccountChanged -> {
                        FilledTonalButton(
                            onClick = {
                                haptics.performConfirm()
                                onKeepAsBefore(account.localProfileId)
                            },
                        ) { Text(stringResource(R.string.settings_sync_keep_before)) }
                        OutlinedButton(
                            onClick = {
                                haptics.performClick()
                                onRequestStartFresh(account.localProfileId)
                            },
                        ) { Text(stringResource(R.string.settings_sync_start_fresh)) }
                    }
                }
            }
        }
    }
}

/** "Link to <account>" for one candidate; "Link…" with a menu for several. */
@Composable
private fun LinkButton(candidates: List<CloudAccountRow>, onLink: (CloudAccountRow) -> Unit) {
    if (candidates.isEmpty()) return
    val haptics = rememberYoinHaptics()
    val single = candidates.singleOrNull()
    if (single != null) {
        OutlinedButton(
            onClick = {
                haptics.performConfirm()
                onLink(single)
            },
        ) { Text(stringResource(R.string.settings_sync_link_to, linkLabel(single))) }
        return
    }
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = {
                haptics.performTick()
                menuOpen = true
            },
        ) { Text(stringResource(R.string.settings_sync_link_menu)) }
        YoinDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            candidates.forEach { candidate ->
                YoinDropdownMenuItem(
                    text = linkLabel(candidate),
                    onClick = {
                        haptics.performContextClick()
                        menuOpen = false
                        onLink(candidate)
                    },
                )
            }
        }
    }
}

/** "Apple Music from Pixel 9": Apple accounts are usually all called "Apple Music". */
@Composable
private fun linkLabel(candidate: CloudAccountRow): String {
    val device = candidate.fromDeviceName
    return if (device.isNullOrBlank()) {
        candidate.displayName
    } else {
        stringResource(R.string.settings_sync_link_from, candidate.displayName, device)
    }
}

@Composable
private fun CloudAccountItem(
    cloud: CloudAccountRow,
    linkTargets: List<SyncAccountRow>,
    onAdd: (SetupService) -> Unit,
    onLink: (String, String) -> Unit,
) {
    val identity = ProviderKind.fromKey(cloud.provider)?.serviceIdentity
    val service = SetupService.entries.firstOrNull { it.key == cloud.provider }
    val haptics = rememberYoinHaptics()
    var menuOpen by remember { mutableStateOf(false) }
    SettingsItem(
        icon = identity?.glyph ?: YoinSymbols.Person,
        iconTone = identity?.hue?.tone(),
        title = cloud.displayName,
        summaryContent = {
            val groups = buildList {
                val service = identity?.let {
                    serviceLineGroups(stringResource(it.nameRes), cloud.displayName, cloud.hint)
                }
                if (service != null) {
                    addAll(service)
                } else {
                    cloud.hint?.takeIf { it.isNotBlank() }?.let { add(MetaGroup.Plain(it)) }
                }
                cloud.fromDeviceName?.takeIf { it.isNotBlank() }?.let {
                    add(MetaGroup.Plain(it, muted = true))
                }
            }
            if (groups.isNotEmpty()) {
                MetaLine(groups = groups, style = MaterialTheme.typography.bodyMedium)
            }
        },
        trailing = {
            when {
                linkTargets.isNotEmpty() -> Box {
                    TextButton(
                        onClick = {
                            val single = linkTargets.singleOrNull()
                            if (single != null) {
                                haptics.performConfirm()
                                onLink(single.localProfileId, cloud.syncProfileId)
                            } else {
                                haptics.performTick()
                                menuOpen = true
                            }
                        },
                    ) { Text(stringResource(R.string.settings_sync_link)) }
                    YoinDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        linkTargets.forEach { local ->
                            YoinDropdownMenuItem(
                                text = local.displayName,
                                onClick = {
                                    haptics.performContextClick()
                                    menuOpen = false
                                    onLink(local.localProfileId, cloud.syncProfileId)
                                },
                            )
                        }
                    }
                }
                service != null -> TextButton(
                    onClick = {
                        haptics.performClick()
                        onAdd(service)
                    },
                ) { Text(stringResource(R.string.settings_sync_add)) }
            }
        },
    )
}

// ── Devices ──────────────────────────────────────────────────────────

@Composable
private fun DeviceItem(device: SyncDeviceRow, now: Long, onRequestRemove: (String) -> Unit) {
    val haptics = rememberYoinHaptics()
    var menuOpen by remember { mutableStateOf(false) }
    val thisIsTablet = LocalConfiguration.current.smallestScreenWidthDp >= 600 ||
        (LocalConfiguration.current.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK) >=
        Configuration.SCREENLAYOUT_SIZE_LARGE
    val tablet = if (device.isThisDevice) thisIsTablet else looksLikeTablet(device.name)
    SettingsItem(
        icon = if (tablet) YoinSymbols.Tablet else YoinSymbols.Smartphone,
        title = device.name,
        summary = when {
            device.isThisDevice -> stringResource(R.string.settings_sync_this_device)
            device.lastSyncAt != null -> stringResource(
                R.string.settings_sync_device_last_synced,
                formatRelativeTime(device.lastSyncAt, now, resources = LocalContext.current.resources),
            )
            else -> stringResource(R.string.settings_sync_device_never)
        },
        trailing = if (device.isThisDevice) {
            null
        } else {
            {
                Box {
                    IconButton(
                        onClick = {
                            haptics.performTick()
                            menuOpen = true
                        },
                    ) {
                        Icon(
                            imageVector = YoinSymbols.MoreVertical,
                            contentDescription = stringResource(R.string.settings_cd_device_options, device.name),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    YoinDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        YoinDropdownMenuItem(
                            text = stringResource(R.string.settings_sync_remove_device_menu),
                            leadingIcon = { Icon(imageVector = YoinSymbols.Delete, contentDescription = null) },
                            onClick = {
                                haptics.performContextClick()
                                menuOpen = false
                                onRequestRemove(device.deviceId)
                            },
                        )
                    }
                }
            }
        },
    )
}

private fun looksLikeTablet(name: String): Boolean =
    listOf("tablet", "tab ", "pad").any { name.lowercase().contains(it) }

// ── Footer ───────────────────────────────────────────────────────────

@Composable
private fun WhatSyncsItem() {
    var expanded by rememberSaveable { mutableStateOf(false) }
    SettingsExpandableItem(
        icon = YoinSymbols.Info,
        title = stringResource(R.string.settings_sync_what_title),
        summary = stringResource(R.string.settings_sync_what_summary),
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        WhatSyncsList(
            title = stringResource(R.string.settings_sync_what_syncs),
            lines = listOf(
                stringResource(R.string.settings_sync_what_notes),
                stringResource(R.string.settings_sync_what_ratings),
                stringResource(R.string.settings_sync_what_home),
                stringResource(R.string.settings_sync_what_translations),
                stringResource(R.string.settings_sync_what_settings),
                stringResource(R.string.settings_sync_what_accounts),
            ),
        )
        WhatSyncsList(
            title = stringResource(R.string.settings_sync_what_stays),
            lines = listOf(
                stringResource(R.string.settings_sync_what_history),
                stringResource(R.string.settings_sync_what_secrets),
                stringResource(R.string.settings_sync_what_caches),
            ),
        )
    }
}

@Composable
private fun WhatSyncsList(title: String, lines: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SettingsGroupLabel(title = title)
        lines.forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

/** A settings row whose action can't be undone: icon and title in the error colour. */
@Composable
private fun DestructiveItem(
    icon: ImageVector,
    title: String,
    summary: String?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val haptics = rememberYoinHaptics()
    val color by animateColorAsState(
        targetValue = if (enabled) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        },
        animationSpec = YoinMotion.effectsSpring(),
        label = "destructiveRowColor",
    )
    Surface(
        onClick = {
            haptics.performClick()
            onClick()
        },
        enabled = enabled,
        shape = LocalSettingsRowShape.current,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium, color = color)
                if (summary != null) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

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
            ) { Text(stringResource(R.string.settings_sync_review_restore)) }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    haptics.performReject()
                    onResolve(false)
                },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
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
            ) { Text(stringResource(R.string.settings_sync_delete_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_sync_delete_cancel)) }
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
            ) { Text(stringResource(R.string.settings_sync_fresh_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_sync_fresh_cancel)) }
        },
    )
}

@Composable
private fun RemoveDeviceDialog(device: SyncDeviceRow, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val haptics = rememberYoinHaptics()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_sync_remove_device_title, device.name)) },
        text = { Text(stringResource(R.string.settings_sync_remove_device_body)) },
        confirmButton = {
            TextButton(
                onClick = {
                    haptics.performReject()
                    onConfirm()
                },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.settings_sync_remove_device_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_sync_remove_device_cancel)) }
        },
    )
}
