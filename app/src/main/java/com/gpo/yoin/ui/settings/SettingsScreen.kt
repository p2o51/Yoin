package com.gpo.yoin.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Reviews
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.BuildConfig
import com.gpo.yoin.data.integration.neodb.NeoDBOAuthContract
import com.gpo.yoin.data.local.GeminiConfig
import com.gpo.yoin.data.profile.ProfileManager
import com.gpo.yoin.data.profile.ProviderKind
import com.gpo.yoin.ui.component.ExpressivePageBackground
import com.gpo.yoin.ui.component.ExpressiveTextField
import com.gpo.yoin.ui.component.YoinDropdownMenu
import com.gpo.yoin.ui.component.YoinDropdownMenuItem
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.component.YoinPageWidths
import com.gpo.yoin.ui.component.horizontalEdgeFadeOnScroll
import com.gpo.yoin.ui.component.ignoreParentHorizontalPadding
import com.gpo.yoin.ui.component.minimumTouchTarget
import com.gpo.yoin.ui.component.yoinPageContentWidth
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.settings.service.ServiceSetupContract
import com.gpo.yoin.ui.settings.service.ServiceSetupRequest
import com.gpo.yoin.ui.settings.service.SetupService
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinContainerShapes
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinShapeTokens
import com.gpo.yoin.ui.theme.YoinTheme
import kotlinx.coroutines.launch

/** Deep-link targets for [SettingsActivity]'s `focusSection` extra. */
object SettingsFocus {
    /** Opens the Spotify setup page with its Client ID field focused. */
    const val SPOTIFY = "spotify"

    /** Scrolls to and expands the NeoDB row. */
    const val NEODB = "neodb"
}

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBackClick: () -> Unit,
    focusSection: String? = null,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val switchingState by viewModel.switchingState.collectAsState()
    val providerPicker by viewModel.providerPickerState.collectAsState()
    val deleteConfirm by viewModel.deleteConfirmState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val neoDbOAuthLauncher = rememberLauncherForActivityResult(NeoDBOAuthContract()) { result ->
        viewModel.commitNeoDbOAuth(result)
    }
    // The setup page hands back the id of a newly added account; the switch
    // runs here so it survives that page finishing.
    val serviceSetupLauncher = rememberLauncherForActivityResult(ServiceSetupContract()) { activateId ->
        activateId?.let(viewModel::switchToProfile)
    }
    val openService: (ServiceSetupRequest) -> Unit = { serviceSetupLauncher.launch(it) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is SettingsOneShotEvent.LaunchNeoDbOAuth -> neoDbOAuthLauncher.launch(event.instance)
                is SettingsOneShotEvent.ShowError ->
                    scope.launch { snackbarHostState.showSnackbar(event.message) }
            }
        }
    }

    // "No Client ID" deep link lands on the Spotify page itself; Settings
    // stays underneath so back reads Spotify → Settings → origin.
    var spotifyDeepLinkHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(focusSection, uiState) {
        val content = uiState as? SettingsUiState.Content ?: return@LaunchedEffect
        if (focusSection != SettingsFocus.SPOTIFY || spotifyDeepLinkHandled) return@LaunchedEffect
        spotifyDeepLinkHandled = true
        val spotifyProfile = content.profileCards
            .filter { it.provider == ProviderKind.SPOTIFY }
            .let { cards -> cards.firstOrNull { it.isActive } ?: cards.firstOrNull() }
        openService(
            ServiceSetupRequest(
                service = SetupService.Spotify,
                profileId = spotifyProfile?.id,
                focusClientId = true,
            ),
        )
    }

    SettingsContent(
        uiState = uiState,
        switchingState = switchingState,
        providerPickerVisible = providerPicker.visible,
        deleteConfirmState = deleteConfirm,
        snackbarHostState = snackbarHostState,
        focusSection = focusSection,
        onBackClick = onBackClick,
        onSwitchToProfile = viewModel::switchToProfile,
        onOpenService = openService,
        onRequestDeleteProfile = viewModel::requestDeleteProfile,
        onShowProviderPicker = viewModel::showProviderPicker,
        onHideProviderPicker = viewModel::hideProviderPicker,
        onDismissSwitchError = viewModel::dismissSwitchError,
        onDismissDeleteConfirm = viewModel::dismissDeleteConfirm,
        onConfirmDeleteProfile = viewModel::confirmDeleteProfile,
        onSaveGeminiApiKey = viewModel::saveGeminiApiKey,
        onSaveGeminiTargetLanguage = viewModel::saveGeminiTargetLanguage,
        onOpenNeoDbSignIn = viewModel::openNeoDbSignIn,
        onSaveNeoDbConfig = viewModel::saveNeoDbConfig,
        onClearNeoDbToken = viewModel::clearNeoDbToken,
        onClearCache = viewModel::clearCache,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    uiState: SettingsUiState,
    switchingState: ProfileManager.SwitchState,
    providerPickerVisible: Boolean,
    deleteConfirmState: DeleteConfirmState,
    onBackClick: () -> Unit,
    onSwitchToProfile: (String) -> Unit,
    onOpenService: (ServiceSetupRequest) -> Unit,
    onRequestDeleteProfile: (String) -> Unit,
    onShowProviderPicker: () -> Unit,
    onHideProviderPicker: () -> Unit,
    onDismissSwitchError: () -> Unit,
    onDismissDeleteConfirm: () -> Unit,
    onConfirmDeleteProfile: () -> Unit,
    onClearCache: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    focusSection: String? = null,
    onSaveGeminiApiKey: (String) -> Unit = {},
    onSaveGeminiTargetLanguage: (String) -> Unit = {},
    onOpenNeoDbSignIn: (String) -> Unit = {},
    onSaveNeoDbConfig: (String, String) -> Unit = { _, _ -> },
    onClearNeoDbToken: () -> Unit = {},
) {
    ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
        val haptics = rememberYoinHaptics()
        ExpressivePageBackground(modifier = modifier) {
            Box(modifier = Modifier.fillMaxSize()) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    topBar = {
                        TopAppBar(
                            title = { Text("Settings") },
                            navigationIcon = {
                                IconButton(
                                    onClick = {
                                        haptics.performClick()
                                        onBackClick()
                                    },
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = Color.Transparent,
                                titleContentColor = MaterialTheme.colorScheme.onSurface,
                                navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        )
                    },
                    snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(it) } },
                ) { innerPadding ->
                    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    val scrollState = rememberScrollState()
                    var neoDbTopPx by remember { mutableIntStateOf(-1) }
                    LaunchedEffect(focusSection, neoDbTopPx) {
                        if (focusSection == SettingsFocus.NEODB && neoDbTopPx >= 0) {
                            scrollState.animateScrollTo(neoDbTopPx)
                        }
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            // Reading/form surface: cap + center on Medium+
                            // windows; no-op on phones.
                            .yoinPageContentWidth(YoinPageWidths.Prose)
                            // Keep low-on-page fields (API key / token) above
                            // the IME (Scaffold contentWindowInsets is 0).
                            .imePadding()
                            .padding(innerPadding)
                            .verticalScroll(scrollState)
                            .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp + navBottom),
                    ) {
                        AnimatedContent(
                            targetState = uiState,
                            transitionSpec = {
                                YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                                    YoinMotion.fadeOut(role = YoinMotionRole.Standard)
                            },
                            // Keyed on the state class so Content→Content data
                            // refreshes don't re-run the fade.
                            contentKey = { it::class },
                            label = "settingsState",
                            modifier = Modifier.fillMaxWidth(),
                        ) { state ->
                            when (state) {
                                is SettingsUiState.Loading -> Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center,
                                ) { YoinLoadingIndicator() }

                                is SettingsUiState.Content -> Column(
                                    verticalArrangement = Arrangement.spacedBy(28.dp),
                                ) {
                                    AccountsSection(
                                        profileCards = state.profileCards,
                                        canAddProfile = state.canAddProfile,
                                        maxProfiles = state.maxProfiles,
                                        onSwitchToProfile = onSwitchToProfile,
                                        onOpenService = onOpenService,
                                        onRequestDeleteProfile = onRequestDeleteProfile,
                                        onAddAccount = onShowProviderPicker,
                                    )
                                    SettingsGroup(
                                        title = "Features",
                                        modifier = Modifier.onGloballyPositioned { coords ->
                                            neoDbTopPx = coords.positionInParent().y.toInt().coerceAtLeast(0)
                                        },
                                    ) {
                                        GeminiItem(
                                            apiKey = state.geminiApiKey,
                                            targetLanguage = state.geminiTargetLanguage,
                                            onSaveApiKey = onSaveGeminiApiKey,
                                            onSaveTargetLanguage = onSaveGeminiTargetLanguage,
                                        )
                                        SettingsRowDivider()
                                        NeoDbItem(
                                            instance = state.neoDbInstance,
                                            accessToken = state.neoDbAccessToken,
                                            initiallyExpanded = focusSection == SettingsFocus.NEODB,
                                            onOpenSignIn = onOpenNeoDbSignIn,
                                            onSaveConfig = onSaveNeoDbConfig,
                                            onClearToken = onClearNeoDbToken,
                                        )
                                    }
                                    SettingsGroup(title = "Storage") {
                                        CacheItem(state.cacheSizeBytes, onClearCache)
                                    }
                                    SettingsGroup(title = "About") {
                                        SettingsItem(
                                            icon = Icons.Rounded.Info,
                                            title = "Yoin",
                                            summary = "Version ${BuildConfig.VERSION_NAME}",
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                ProfileSwitchOverlay(
                    switchingState = switchingState,
                    activeName = (uiState as? SettingsUiState.Content)
                        ?.profileCards
                        ?.firstOrNull { card ->
                            card.id == (switchingState as? ProfileManager.SwitchState.Switching)?.profileId
                        }
                        ?.displayName,
                    onDismissError = onDismissSwitchError,
                )
            }
        }
    }

    if (providerPickerVisible) {
        AddAccountSheet(
            onDismiss = onHideProviderPicker,
            onPick = { service -> onOpenService(ServiceSetupRequest(service)) },
        )
    }

    (deleteConfirmState as? DeleteConfirmState.Confirming)?.let { confirm ->
        DeleteProfileDialog(
            displayName = confirm.displayName,
            onDismiss = onDismissDeleteConfirm,
            onConfirm = onConfirmDeleteProfile,
        )
    }
}

// ── Accounts ──────────────────────────────────────────────────────────

@Composable
private fun AccountsSection(
    profileCards: List<ProfileCard>,
    canAddProfile: Boolean,
    maxProfiles: Int,
    onSwitchToProfile: (String) -> Unit,
    onOpenService: (ServiceSetupRequest) -> Unit,
    onRequestDeleteProfile: (String) -> Unit,
    onAddAccount: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsGroupLabel(
            title = "Accounts",
            trailingLabel = if (profileCards.isEmpty()) null else "${profileCards.size} of $maxProfiles",
        )
        if (profileCards.isEmpty()) {
            EmptyAccountsCard(onAddAccount)
            return@Column
        }
        val rowState = rememberLazyListState()
        LazyRow(
            state = rowState,
            // Full-bleed past the page padding: cards scroll under the screen
            // edges with a fade instead of being chopped at the padding line.
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicCardHeight)
                .ignoreParentHorizontalPadding(16.dp)
                .horizontalEdgeFadeOnScroll(rowState),
        ) {
            items(items = profileCards, key = { it.id }) { card ->
                val manage = {
                    SetupService.forProvider(card.provider)?.let { service ->
                        onOpenService(ServiceSetupRequest(service, profileId = card.id))
                    }
                    Unit
                }
                ProfileCardTile(
                    card = card,
                    // Anything that needs attention — or the account you're
                    // already on — opens its page; any other card switches.
                    onTap = {
                        if (card.isActive || card.requiresReconnect || card.requiresCredentialsReentry) {
                            manage()
                        } else {
                            onSwitchToProfile(card.id)
                        }
                    },
                    onManage = manage,
                    onRemove = { onRequestDeleteProfile(card.id) },
                )
            }
            if (canAddProfile) {
                item(key = "add") { AddAccountTile(onClick = onAddAccount) }
            }
        }
    }
}

private val IntrinsicCardHeight = 172.dp

@Composable
private fun EmptyAccountsCard(onAddAccount: () -> Unit) {
    val haptics = rememberYoinHaptics()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = YoinContainerShapes.Panel,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Bring your music", style = MaterialTheme.typography.titleLarge)
            Text(
                "Connect a server or a streaming account to start listening.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = {
                    haptics.performClick()
                    onAddAccount()
                },
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add account")
            }
        }
    }
}

@Composable
private fun ProfileCardTile(
    card: ProfileCard,
    onTap: () -> Unit,
    onManage: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberYoinHaptics()
    val containerColor by animateColorAsState(
        targetValue = if (card.isActive) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "accountCardContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = if (card.isActive) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "accountCardContent",
    )
    val scale by animateFloatAsState(
        targetValue = if (card.isActive) 1f else 0.96f,
        animationSpec = YoinMotion.spatialSpring(),
        label = "accountCardScale",
    )
    var menuOpen by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .width(200.dp)
            .fillMaxHeight()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .testTag("account_card_${card.id}"),
        shape = YoinContainerShapes.Card,
        color = containerColor,
        contentColor = contentColor,
        onClick = {
            haptics.performClick()
            onTap()
        },
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, top = 6.dp, end = 4.dp, bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = providerIcon(card.provider),
                    contentDescription = card.provider.displayLabel,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(
                        onClick = {
                            haptics.performTick()
                            menuOpen = true
                        },
                        modifier = Modifier.minimumTouchTarget(),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = "Account options",
                            tint = contentColor.copy(alpha = 0.72f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    YoinDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        YoinDropdownMenuItem(
                            text = "Manage",
                            onClick = {
                                haptics.performContextClick()
                                menuOpen = false
                                onManage()
                            },
                        )
                        YoinDropdownMenuItem(
                            text = "Remove",
                            onClick = {
                                haptics.performReject()
                                menuOpen = false
                                onRemove()
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = card.displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 12.dp),
            )
            Spacer(Modifier.height(6.dp))
            AccountStatus(card = card, contentColor = contentColor)
        }
    }
}

/** One line under the name: a problem if there is one, else "In use", else where it lives. */
@Composable
private fun AccountStatus(card: ProfileCard, contentColor: Color) {
    val issue = card.unavailableReason
    when {
        issue != null -> Surface(
            shape = YoinShapeTokens.Full,
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ) {
            Text(
                text = issue,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
        card.isActive -> Surface(
            shape = YoinShapeTokens.Full,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Text(
                text = "In use",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
        else -> Text(
            text = card.subtitle ?: card.provider.displayLabel,
            style = MaterialTheme.typography.bodySmall,
            color = contentColor.copy(alpha = 0.72f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 12.dp),
        )
    }
}

@Composable
private fun AddAccountTile(onClick: () -> Unit) {
    val haptics = rememberYoinHaptics()
    Surface(
        modifier = Modifier
            .width(112.dp)
            .fillMaxHeight()
            .testTag("add_account"),
        shape = YoinContainerShapes.Card,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.primary,
        onClick = {
            haptics.performClick()
            onClick()
        },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(28.dp))
            Text("Add", style = MaterialTheme.typography.labelLarge)
        }
    }
}

private fun providerIcon(kind: ProviderKind): ImageVector = when (kind) {
    ProviderKind.SUBSONIC -> Icons.Rounded.CloudQueue
    ProviderKind.SPOTIFY -> Icons.Rounded.Headphones
    ProviderKind.APPLE_MUSIC -> Icons.Rounded.MusicNote
    ProviderKind.LOCAL -> Icons.Rounded.Folder
}

// ── Add account sheet ────────────────────────────────────────────────

/**
 * Just the choice: name + one short line each. What a service can do is
 * told on its own page, after the user shows interest by tapping it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddAccountSheet(
    onDismiss: () -> Unit,
    onPick: (SetupService) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val pick: (SetupService) -> Unit = { service ->
        scope.launch {
            sheetState.hide()
            onDismiss()
            onPick(service)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp),
        ) {
            Text(
                text = "Add an account",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
            )
            ServiceChoiceRow(
                icon = Icons.Rounded.CloudQueue,
                title = "Subsonic",
                summary = "Navidrome, Airsonic and other servers",
                onClick = { pick(SetupService.Subsonic) },
            )
            ServiceChoiceRow(
                icon = Icons.Rounded.Headphones,
                title = "Spotify",
                summary = "Your Spotify library",
                onClick = { pick(SetupService.Spotify) },
            )
            ServiceChoiceRow(
                icon = Icons.Rounded.MusicNote,
                title = "Apple Music",
                summary = "Your Apple Music library and catalog",
                badge = "Preview",
                onClick = { pick(SetupService.AppleMusic) },
            )
            ServiceChoiceRow(
                icon = Icons.Rounded.Folder,
                title = "Files on this device",
                summary = "Coming later",
                onClick = null,
            )
        }
    }
}

@Composable
private fun ServiceChoiceRow(
    icon: ImageVector,
    title: String,
    summary: String,
    onClick: (() -> Unit)?,
    badge: String? = null,
) {
    val haptics = rememberYoinHaptics()
    val enabled = onClick != null
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .alpha(if (enabled) 1f else 0.5f),
        onClick = {
            haptics.performClick()
            onClick?.invoke()
        },
        enabled = enabled,
        shape = YoinContainerShapes.Card,
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsRowIcon(icon)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (badge != null) {
                        Surface(
                            shape = YoinShapeTokens.Full,
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        ) {
                            Text(
                                badge,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (enabled) {
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── Features ─────────────────────────────────────────────────────────

@Composable
private fun GeminiItem(
    apiKey: String,
    targetLanguage: String,
    onSaveApiKey: (String) -> Unit,
    onSaveTargetLanguage: (String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var draftKey by rememberSaveable(apiKey) { mutableStateOf(apiKey) }
    var languageMenuOpen by remember { mutableStateOf(false) }
    val language = GeminiConfig.normalizeTargetLanguage(targetLanguage)
    val haptics = rememberYoinHaptics()

    SettingsExpandableItem(
        icon = Icons.Rounded.AutoAwesome,
        title = "AI features",
        summary = if (apiKey.isBlank()) "Song info, Ask Gemini and translation" else "Gemini · $language",
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        SecretTextField(
            value = draftKey,
            onValueChange = { draftKey = it },
            label = "Gemini API key",
            placeholder = "AIza…",
            modifier = Modifier.fillMaxWidth(),
        )
        Box {
            OutlinedButton(
                onClick = {
                    haptics.performTick()
                    languageMenuOpen = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Answer in $language", modifier = Modifier.weight(1f))
            }
            YoinDropdownMenu(expanded = languageMenuOpen, onDismissRequest = { languageMenuOpen = false }) {
                GeminiConfig.SUPPORTED_TARGET_LANGUAGES.forEach { option ->
                    YoinDropdownMenuItem(
                        text = option,
                        onClick = {
                            haptics.performContextClick()
                            languageMenuOpen = false
                            onSaveTargetLanguage(option)
                        },
                    )
                }
            }
        }
        Button(
            onClick = { onSaveApiKey(draftKey) },
            enabled = draftKey.isNotBlank() && draftKey.trim() != apiKey,
        ) { Text("Save key") }
    }
}

@Composable
private fun NeoDbItem(
    instance: String,
    accessToken: String,
    initiallyExpanded: Boolean,
    onOpenSignIn: (String) -> Unit,
    onSaveConfig: (String, String) -> Unit,
    onClearToken: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    var draftInstance by rememberSaveable(instance) { mutableStateOf(instance) }
    var draftToken by rememberSaveable(accessToken) { mutableStateOf(accessToken) }
    var showManualToken by rememberSaveable { mutableStateOf(false) }
    val signedIn = accessToken.isNotBlank()

    SettingsExpandableItem(
        icon = Icons.Rounded.Reviews,
        title = "NeoDB",
        summary = if (signedIn) "Album ratings and reviews sync" else "Sync album ratings and reviews",
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        ExpressiveTextField(
            value = draftInstance,
            onValueChange = { draftInstance = it },
            label = "Instance",
            placeholder = "https://neodb.social",
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onOpenSignIn(draftInstance) }) {
                Text(if (signedIn) "Sign in again" else "Sign in")
            }
            if (signedIn) {
                TextButton(
                    onClick = {
                        draftToken = ""
                        onClearToken()
                    },
                ) { Text("Sign out") }
            }
        }
        // Manual token is an escape hatch, not a step — hidden until asked for.
        AnimatedContent(
            targetState = showManualToken,
            transitionSpec = {
                YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                    YoinMotion.fadeOut(role = YoinMotionRole.Standard)
            },
            contentAlignment = Alignment.TopStart,
            label = "neoDbManualToken",
        ) { manual ->
            if (!manual) {
                TextButton(onClick = { showManualToken = true }) { Text("Use an access token instead") }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SecretTextField(
                        value = draftToken,
                        onValueChange = { draftToken = it },
                        label = "Access token",
                        placeholder = "Paste token",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        onClick = { onSaveConfig(draftInstance, draftToken) },
                        enabled = draftToken.isNotBlank() && draftToken.trim() != accessToken.trim(),
                    ) { Text("Save token") }
                }
            }
        }
    }
}

// ── Storage ──────────────────────────────────────────────────────────

@Composable
private fun CacheItem(cacheSizeBytes: Long, onClearCache: () -> Unit) {
    val haptics = rememberYoinHaptics()
    SettingsItem(
        icon = Icons.Rounded.Storage,
        title = "Playback cache",
        summary = formatBytes(cacheSizeBytes),
        trailing = {
            TextButton(
                onClick = {
                    haptics.performReject()
                    onClearCache()
                },
                enabled = cacheSizeBytes > 0,
            ) { Text("Clear") }
        },
    )
}

// ── Account switch blocking overlay ──────────────────────────────────

@Composable
private fun ProfileSwitchOverlay(
    switchingState: ProfileManager.SwitchState,
    activeName: String?,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = switchingState !is ProfileManager.SwitchState.Idle
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "overlayAlpha",
    )
    if (alpha <= 0f) return

    Box(
        modifier = modifier
            .fillMaxSize()
            .alpha(alpha),
        contentAlignment = Alignment.Center,
    ) {
        Surface(color = Color.Black.copy(alpha = 0.42f), modifier = Modifier.fillMaxSize()) {}
        Surface(
            shape = YoinShapeTokens.ExtraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
            modifier = Modifier.width(280.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (switchingState) {
                    is ProfileManager.SwitchState.Switching -> {
                        YoinLoadingIndicator(size = 36.dp)
                        Text(
                            text = "Switching account",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stageLabel(switchingState.stage, activeName),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is ProfileManager.SwitchState.Error -> {
                        Icon(
                            imageVector = Icons.Rounded.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(36.dp),
                        )
                        Text(text = "Couldn't switch account", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = switchingState.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = onDismissError) { Text("OK") }
                    }
                    ProfileManager.SwitchState.Idle -> Unit
                }
            }
        }
    }
}

private fun stageLabel(stage: ProfileManager.SwitchState.Stage, activeName: String?): String {
    val name = activeName.orEmpty().ifBlank { "the new account" }
    return when (stage) {
        ProfileManager.SwitchState.Stage.Preparing -> "Closing the current session…"
        ProfileManager.SwitchState.Stage.Connecting -> "Connecting to $name…"
        ProfileManager.SwitchState.Stage.Priming -> "Warming caches…"
    }
}

// ── Remove account confirmation ──────────────────────────────────────

@Composable
private fun DeleteProfileDialog(
    displayName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val haptics = rememberYoinHaptics()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove this account?") },
        text = { Text("“$displayName” will be removed from Yoin. Your ratings, notes and history stay.") },
        confirmButton = {
            TextButton(
                onClick = {
                    haptics.performReject()
                    onConfirm()
                },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Remove") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "Empty"
    bytes < 1_024L -> "$bytes B"
    bytes < 1_048_576L -> "%.1f KB".format(bytes / 1_024.0)
    bytes < 1_073_741_824L -> "%.1f MB".format(bytes / 1_048_576.0)
    else -> "%.2f GB".format(bytes / 1_073_741_824.0)
}

// ── Previews ─────────────────────────────────────────────────────────

private val previewCards = listOf(
    ProfileCard(
        id = "a",
        displayName = "demo @ demo.navidrome.org",
        subtitle = "demo.navidrome.org",
        provider = ProviderKind.SUBSONIC,
        isActive = true,
    ),
    ProfileCard(
        id = "b",
        displayName = "Chen's Spotify",
        subtitle = "Spotify account",
        provider = ProviderKind.SPOTIFY,
        isActive = false,
        unavailableReason = "Reconnect",
        requiresReconnect = true,
    ),
)

@Composable
private fun SettingsPreviewHost(uiState: SettingsUiState) {
    YoinTheme {
        SettingsContent(
            uiState = uiState,
            switchingState = ProfileManager.SwitchState.Idle,
            providerPickerVisible = false,
            deleteConfirmState = DeleteConfirmState.Hidden,
            onBackClick = {},
            onSwitchToProfile = {},
            onOpenService = {},
            onRequestDeleteProfile = {},
            onShowProviderPicker = {},
            onHideProviderPicker = {},
            onDismissSwitchError = {},
            onDismissDeleteConfirm = {},
            onConfirmDeleteProfile = {},
            onClearCache = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
fun SettingsContentPreview() {
    SettingsPreviewHost(
        SettingsUiState.Content(
            profileCards = previewCards,
            activeProfileId = "a",
            canAddProfile = true,
            cacheSizeBytes = 52_428_800L,
            geminiTargetLanguage = GeminiConfig.DEFAULT_TARGET_LANGUAGE,
        ),
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
fun SettingsContentEmptyPreview() {
    SettingsPreviewHost(
        SettingsUiState.Content(profileCards = emptyList(), activeProfileId = null, canAddProfile = true),
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
fun SettingsContentLoadingPreview() {
    SettingsPreviewHost(SettingsUiState.Loading)
}
