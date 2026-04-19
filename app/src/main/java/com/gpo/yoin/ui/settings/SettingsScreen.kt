package com.gpo.yoin.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.data.profile.ProfileManager
import com.gpo.yoin.data.profile.ProviderKind
import com.gpo.yoin.data.source.spotify.SpotifyOAuthContract
import com.gpo.yoin.ui.component.ExpressiveHeaderBlock
import com.gpo.yoin.ui.component.ExpressivePageBackground
import com.gpo.yoin.ui.component.ExpressiveSectionPanel
import com.gpo.yoin.ui.component.ExpressiveTextField
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinShapeTokens
import com.gpo.yoin.ui.theme.YoinTheme
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBackClick: () -> Unit,
    focusSection: String? = null,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val switchingState by viewModel.switchingState.collectAsState()
    val profileFormSheet by viewModel.profileFormSheet.collectAsState()
    val providerPicker by viewModel.providerPickerState.collectAsState()
    val deleteConfirm by viewModel.deleteConfirmState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val spotifyOAuthLauncher = rememberLauncherForActivityResult(SpotifyOAuthContract()) { result ->
        viewModel.commitSpotifyProfile(result)
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is SettingsOneShotEvent.LaunchSpotifyOAuth ->
                    spotifyOAuthLauncher.launch(event.targetProfileId)
                is SettingsOneShotEvent.ShowError ->
                    scope.launch { snackbarHostState.showSnackbar(event.message) }
            }
        }
    }

    SettingsContent(
        uiState = uiState,
        switchingState = switchingState,
        profileFormSheet = profileFormSheet,
        providerPickerVisible = providerPicker.visible,
        deleteConfirmState = deleteConfirm,
        snackbarHostState = snackbarHostState,
        focusSection = focusSection,
        onBackClick = onBackClick,
        onSwitchToProfile = viewModel::switchToProfile,
        onEditProfile = viewModel::openEditProfile,
        onRequestDeleteProfile = viewModel::requestDeleteProfile,
        onReconnectProfile = viewModel::reconnectSpotifyProfile,
        onShowProviderPicker = viewModel::showProviderPicker,
        onHideProviderPicker = viewModel::hideProviderPicker,
        onPickProvider = viewModel::pickProviderForNewProfile,
        onCloseFormSheet = viewModel::closeProfileFormSheet,
        onTestConnection = viewModel::testConnection,
        onSaveProfile = viewModel::saveSubsonicProfile,
        onDismissSwitchError = viewModel::dismissSwitchError,
        onDismissDeleteConfirm = viewModel::dismissDeleteConfirm,
        onConfirmDeleteProfile = viewModel::confirmDeleteProfile,
        onSaveGeminiApiKey = viewModel::saveGeminiApiKey,
        onSaveSpotifyClientId = viewModel::saveSpotifyClientId,
        onClearCache = viewModel::clearCache,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    uiState: SettingsUiState,
    switchingState: ProfileManager.SwitchState,
    profileFormSheet: ProfileFormSheet,
    providerPickerVisible: Boolean,
    deleteConfirmState: DeleteConfirmState,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    focusSection: String? = null,
    onBackClick: () -> Unit,
    onSwitchToProfile: (String) -> Unit,
    onEditProfile: (String) -> Unit,
    onRequestDeleteProfile: (String) -> Unit,
    onReconnectProfile: (String) -> Unit,
    onShowProviderPicker: () -> Unit,
    onHideProviderPicker: () -> Unit,
    onPickProvider: (ProviderKind) -> Unit,
    onCloseFormSheet: () -> Unit,
    onTestConnection: (String, String, String) -> Unit,
    onSaveProfile: (String, String, String) -> Unit,
    onDismissSwitchError: () -> Unit,
    onDismissDeleteConfirm: () -> Unit,
    onConfirmDeleteProfile: () -> Unit,
    onSaveGeminiApiKey: (String) -> Unit = {},
    onSaveSpotifyClientId: (String) -> Unit = {},
    onClearCache: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
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
                                IconButton(onClick = onBackClick) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back",
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = Color.Transparent,
                                titleContentColor = MaterialTheme.colorScheme.onSurface,
                                navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        )
                    },
                    snackbarHost = {
                        SnackbarHost(hostState = snackbarHostState) { data ->
                            Snackbar(snackbarData = data)
                        }
                    },
                ) { innerPadding ->
                    val navBottom = WindowInsets.navigationBars.asPaddingValues()
                        .calculateBottomPadding()
                    val scrollState = rememberScrollState()
                    var spotifySectionTopPx by remember { mutableIntStateOf(-1) }
                    val spotifyClientIdFocusRequester = remember { FocusRequester() }
                    LaunchedEffect(focusSection, spotifySectionTopPx) {
                        if (focusSection == "spotify" && spotifySectionTopPx >= 0) {
                            scrollState.animateScrollTo(spotifySectionTopPx)
                            runCatching { spotifyClientIdFocusRequester.requestFocus() }
                        }
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(innerPadding)
                            .padding(
                                start = 16.dp,
                                top = 16.dp,
                                end = 16.dp,
                                bottom = 16.dp + navBottom,
                            ),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        when (uiState) {
                            is SettingsUiState.Loading -> {
                                YoinLoadingIndicator(
                                    modifier = Modifier.align(Alignment.CenterHorizontally),
                                )
                            }

                            is SettingsUiState.Content -> {
                                ProfileSwitcherSection(
                                    profileCards = uiState.profileCards,
                                    canAddProfile = uiState.canAddProfile,
                                    maxProfiles = uiState.maxProfiles,
                                    onSwitchToProfile = onSwitchToProfile,
                                    onEditProfile = onEditProfile,
                                    onRequestDeleteProfile = onRequestDeleteProfile,
                                    onReconnectProfile = onReconnectProfile,
                                    onShowProviderPicker = onShowProviderPicker,
                                )
                                GeminiSection(
                                    initialApiKey = uiState.geminiApiKey,
                                    onSaveApiKey = onSaveGeminiApiKey,
                                )
                                SpotifySection(
                                    initialClientId = uiState.spotifyClientId,
                                    usesFallback = uiState.spotifyClientIdUsesFallback,
                                    onSaveClientId = onSaveSpotifyClientId,
                                    clientIdFocusRequester = spotifyClientIdFocusRequester,
                                    modifier = Modifier.onGloballyPositioned { coords ->
                                        spotifySectionTopPx = coords.positionInParent().y
                                            .toInt()
                                            .coerceAtLeast(0)
                                    },
                                )
                                CacheSection(
                                    cacheSizeBytes = uiState.cacheSizeBytes,
                                    onClearCache = onClearCache,
                                )
                                AboutSection()
                            }
                        }
                    }
                }

                ProfileSwitchOverlay(
                    switchingState = switchingState,
                    activeName = (uiState as? SettingsUiState.Content)
                        ?.profileCards
                        ?.firstOrNull { card ->
                            card.id ==
                                (switchingState as? ProfileManager.SwitchState.Switching)?.profileId
                        }
                        ?.displayName,
                    onDismissError = onDismissSwitchError,
                )
            }
        }
    }

    if (providerPickerVisible) {
        ProviderPickerSheet(
            onDismiss = onHideProviderPicker,
            onPickProvider = onPickProvider,
        )
    }

    (profileFormSheet as? ProfileFormSheet.Visible)?.let { sheet ->
        ProfileFormBottomSheet(
            state = sheet,
            onDismiss = onCloseFormSheet,
            onTestConnection = onTestConnection,
            onSave = onSaveProfile,
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

// ── Profile switcher section (horizontal cards + "+" tile) ────────────

@Composable
private fun ProfileSwitcherSection(
    profileCards: List<ProfileCard>,
    canAddProfile: Boolean,
    maxProfiles: Int,
    onSwitchToProfile: (String) -> Unit,
    onEditProfile: (String) -> Unit,
    onRequestDeleteProfile: (String) -> Unit,
    onReconnectProfile: (String) -> Unit,
    onShowProviderPicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ExpressiveSectionPanel(
        modifier = modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f),
        tonalElevation = 1.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ExpressiveHeaderBlock(title = "Profiles")
                Text(
                    text = "${profileCards.size} / $maxProfiles",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (profileCards.isEmpty()) {
                Text(
                    text = "No profiles yet. Tap + to add one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LazyRow(
                contentPadding = PaddingValues(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(
                    items = profileCards,
                    key = { it.id },
                ) { card ->
                    ProfileCardTile(
                        card = card,
                        onTap = {
                            when {
                                card.requiresReconnect ->
                                    onReconnectProfile(card.id)
                                // Subsonic missing-credentials recovery: edit
                                // form regardless of active state so the user
                                // can re-enter URL + username + password
                                // without first activating a broken profile.
                                card.requiresCredentialsReentry ->
                                    onEditProfile(card.id)
                                card.isActive ->
                                    onEditProfile(card.id)
                                else ->
                                    onSwitchToProfile(card.id)
                            }
                        },
                        onReconnect = { onReconnectProfile(card.id) },
                        onEdit = { onEditProfile(card.id) },
                        onDelete = { onRequestDeleteProfile(card.id) },
                    )
                }
                item(key = "add") {
                    AddProfileCardTile(
                        enabled = canAddProfile,
                        onClick = onShowProviderPicker,
                    )
                }
            }

            if (!canAddProfile) {
                Text(
                    text = "Profile limit reached ($maxProfiles). Delete one to add another.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ProfileCardTile(
    card: ProfileCard,
    onTap: () -> Unit,
    onReconnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor by animateColorAsState(
        targetValue = if (card.isActive) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "cardContainerColor",
    )
    val contentColor = if (card.isActive) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val scale by animateFloatAsState(
        targetValue = if (card.isActive) 1.04f else 1f,
        animationSpec = YoinMotion.effectsSpring(),
        label = "cardScale",
    )
    var menuOpen by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .width(168.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        shape = YoinShapeTokens.Large,
        color = containerColor,
        contentColor = contentColor,
        onClick = onTap,
        tonalElevation = if (card.isActive) 2.dp else 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = providerIcon(card.provider),
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(26.dp),
                )
                Box {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "Profile options",
                            tint = contentColor.copy(alpha = 0.72f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        if (card.requiresReconnect) {
                            DropdownMenuItem(
                                text = { Text("Reconnect") },
                                onClick = {
                                    menuOpen = false
                                    onReconnect()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Edit") },
                            onClick = {
                                menuOpen = false
                                onEdit()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = card.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = contentColor,
                    maxLines = 1,
                )
                Text(
                    text = card.subtitle ?: card.provider.displayLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.72f),
                    maxLines = 1,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = card.provider.displayLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.66f),
                )
                when {
                    card.unavailableReason != null -> Surface(
                        shape = YoinShapeTokens.Small,
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ) {
                        Text(
                            text = card.unavailableReason,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    card.isActive -> Text(
                        text = "Active",
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun AddProfileCardTile(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val alpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.4f,
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "addCardAlpha",
    )
    Surface(
        modifier = modifier
            .width(120.dp)
            .alpha(alpha),
        shape = YoinShapeTokens.Large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        onClick = onClick,
        enabled = enabled,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "Add profile",
                modifier = Modifier.size(28.dp),
            )
            Text(
                text = "Add",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

private fun providerIcon(kind: ProviderKind): ImageVector = when (kind) {
    ProviderKind.SUBSONIC -> Icons.Filled.CloudQueue
    ProviderKind.SPOTIFY -> Icons.Filled.Headphones
    ProviderKind.LOCAL -> Icons.Filled.Folder
}

// ── Provider picker bottom sheet ──────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderPickerSheet(
    onDismiss: () -> Unit,
    onPickProvider: (ProviderKind) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Choose a source",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Yoin will save a new profile with the credentials you provide for the source.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ProviderKind.entries.forEach { provider ->
                ProviderOption(
                    provider = provider,
                    onClick = {
                        scope.launch {
                            sheetState.hide()
                            onPickProvider(provider)
                        }
                    },
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ProviderOption(
    provider: ProviderKind,
    onClick: () -> Unit,
) {
    val enabled = provider.isAvailable
    Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        enabled = enabled,
        shape = YoinShapeTokens.Large,
        color = if (enabled) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = providerIcon(provider),
                contentDescription = null,
                tint = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                },
                modifier = Modifier.size(28.dp),
            )
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = provider.displayLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    },
                )
                Text(
                    text = if (enabled) {
                        when (provider) {
                            ProviderKind.SUBSONIC -> "Connect an OpenSubsonic / Navidrome / Airsonic server"
                            ProviderKind.SPOTIFY -> "Connect via the Spotify app (Spotify Premium)"
                            ProviderKind.LOCAL -> "Play files from this device"
                        }
                    } else {
                        "Coming soon"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── Profile form bottom sheet (Subsonic create / edit) ────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileFormBottomSheet(
    state: ProfileFormSheet.Visible,
    onDismiss: () -> Unit,
    onTestConnection: (String, String, String) -> Unit,
    onSave: (String, String, String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        when (state.provider) {
            ProviderKind.SUBSONIC -> SubsonicProfileForm(
                state = state,
                onTestConnection = onTestConnection,
                onSave = onSave,
            )
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "${state.provider.displayLabel} support is coming soon.",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Close")
                    }
                }
            }
        }
    }
}

@Composable
private fun SubsonicProfileForm(
    state: ProfileFormSheet.Visible,
    onTestConnection: (String, String, String) -> Unit,
    onSave: (String, String, String) -> Unit,
) {
    val formIdentity = when (val mode = state.mode) {
        is ProfileFormSheet.Visible.Mode.Create -> "create:${state.provider.key}"
        is ProfileFormSheet.Visible.Mode.Edit -> "edit:${state.provider.key}:${mode.profileId}"
    }
    var serverUrl by rememberSaveable(formIdentity) { mutableStateOf(state.initialUrl) }
    var username by rememberSaveable(formIdentity) { mutableStateOf(state.initialUsername) }
    var password by rememberSaveable(formIdentity) { mutableStateOf(state.initialPassword) }

    val canSubmit = serverUrl.isNotBlank() &&
        username.isNotBlank() &&
        password.isNotBlank() &&
        !state.isTesting

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = when (state.mode) {
                is ProfileFormSheet.Visible.Mode.Create -> "Add Subsonic profile"
                is ProfileFormSheet.Visible.Mode.Edit -> "Edit profile"
            },
            style = MaterialTheme.typography.titleLarge,
        )

        ExpressiveTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = "Server URL",
            placeholder = "https://your-server.com",
            modifier = Modifier.fillMaxWidth(),
        )
        ExpressiveTextField(
            value = username,
            onValueChange = { username = it },
            label = "Username",
            placeholder = "music-admin",
            modifier = Modifier.fillMaxWidth(),
        )
        ExpressiveTextField(
            value = password,
            onValueChange = { password = it },
            label = "Password",
            placeholder = "App password",
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )

        ConnectionStatusIndicator(
            testResult = state.testResult,
            isTesting = state.isTesting,
            saveError = state.saveError,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = { onTestConnection(serverUrl, username, password) },
                enabled = canSubmit,
                modifier = Modifier.weight(1f),
            ) {
                Text("Test")
            }
            Button(
                onClick = { onSave(serverUrl, username, password) },
                enabled = canSubmit,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = when (state.mode) {
                        is ProfileFormSheet.Visible.Mode.Create -> "Save & Switch"
                        is ProfileFormSheet.Visible.Mode.Edit -> "Save"
                    },
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun ConnectionStatusIndicator(
    testResult: ConnectionResult?,
    isTesting: Boolean,
    saveError: String?,
    modifier: Modifier = Modifier,
) {
    val statusColor by animateColorAsState(
        targetValue = when {
            saveError != null -> MaterialTheme.colorScheme.error
            testResult is ConnectionResult.Success -> MaterialTheme.colorScheme.primary
            testResult is ConnectionResult.Failure -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "statusColor",
    )
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = YoinShapeTokens.Large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.64f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                isTesting -> {
                    YoinLoadingIndicator(
                        modifier = Modifier.size(20.dp),
                        size = 20.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Testing connection…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                saveError != null -> {
                    Icon(
                        imageVector = Icons.Filled.Error,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = saveError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = statusColor,
                    )
                }
                testResult is ConnectionResult.Success -> {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Connection successful",
                        style = MaterialTheme.typography.bodyMedium,
                        color = statusColor,
                    )
                }
                testResult is ConnectionResult.Failure -> {
                    Icon(
                        imageVector = Icons.Filled.Error,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = testResult.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = statusColor,
                    )
                }
                else -> {
                    Text(
                        text = "Enter server details and test the connection before saving.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ── Profile switch blocking overlay ──────────────────────────────────

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
        Surface(
            color = Color.Black.copy(alpha = 0.42f),
            modifier = Modifier.fillMaxSize(),
        ) {}

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
                            text = "Switching profile",
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
                            imageVector = Icons.Filled.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(36.dp),
                        )
                        Text(
                            text = "Couldn't switch profile",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = switchingState.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = onDismissError) {
                            Text("Dismiss")
                        }
                    }
                    ProfileManager.SwitchState.Idle -> Unit
                }
            }
        }
    }
}

private fun stageLabel(stage: ProfileManager.SwitchState.Stage, activeName: String?): String {
    val name = activeName.orEmpty().ifBlank { "the new server" }
    return when (stage) {
        ProfileManager.SwitchState.Stage.Preparing -> "Closing the current session…"
        ProfileManager.SwitchState.Stage.Connecting -> "Connecting to $name…"
        ProfileManager.SwitchState.Stage.Priming -> "Warming caches…"
    }
}

// ── Delete profile confirmation ──────────────────────────────────────

@Composable
private fun DeleteProfileDialog(
    displayName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete this profile?") },
        text = {
            Text(
                "\u201C$displayName\u201D will be removed. Local ratings and history stay.",
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// ── Preserved sections (Gemini / Cache / About) ──────────────────────

@Composable
private fun GeminiSection(
    initialApiKey: String,
    onSaveApiKey: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var apiKey by rememberSaveable { mutableStateOf(initialApiKey) }
    LaunchedEffect(initialApiKey) { apiKey = initialApiKey }

    ExpressiveSectionPanel(
        modifier = modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.95f),
        tonalElevation = 1.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ExpressiveHeaderBlock(title = "AI Features")
            Text(
                text = "Enter your Gemini API key to enable AI-powered song info. Get one from Google AI Studio.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ExpressiveTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = "Gemini API Key",
                placeholder = "AIza…",
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onSaveApiKey(apiKey) },
                enabled = apiKey.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save API Key")
            }
        }
    }
}

@Composable
private fun SpotifySection(
    initialClientId: String,
    usesFallback: Boolean,
    onSaveClientId: (String) -> Unit,
    clientIdFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    var clientId by rememberSaveable { mutableStateOf(initialClientId) }
    LaunchedEffect(initialClientId) { clientId = initialClientId }

    ExpressiveSectionPanel(
        modifier = modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.95f),
        tonalElevation = 1.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ExpressiveHeaderBlock(title = "Spotify")
            Text(
                text = "Paste the Client ID from your Spotify Developer app. " +
                    "Redirect URIs to register: " +
                    "yoin://auth/spotify/callback and yoin://auth/spotify/app-remote",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (usesFallback) {
                Text(
                    text = "Currently using the build-time fallback client ID from this APK. Saving here will override it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Reconnect lives on the per-profile card's overflow menu now —
            // that's where users expect "this profile needs attention"
            // affordances. Settings → Spotify is for global settings only
            // (Client ID), not per-profile actions.
            ExpressiveTextField(
                value = clientId,
                onValueChange = { clientId = it },
                label = "Spotify Client ID",
                placeholder = "32-char hex from developer.spotify.com",
                modifier = Modifier
                    .testTag("spotify_client_id_field")
                    .fillMaxWidth()
                    .then(
                        if (clientIdFocusRequester != null) {
                            Modifier.focusRequester(clientIdFocusRequester)
                        } else {
                            Modifier
                        },
                    ),
            )
            Button(
                onClick = { onSaveClientId(clientId) },
                enabled = clientId.trim() != initialClientId,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save Client ID")
            }
        }
    }
}

@Composable
private fun CacheSection(
    cacheSizeBytes: Long,
    onClearCache: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ExpressiveSectionPanel(
        modifier = modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ExpressiveHeaderBlock(title = "Cache")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Cache size: ${formatBytes(cacheSizeBytes)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onClearCache) { Text("Clear Cache") }
            }
        }
    }
}

@Composable
private fun AboutSection(modifier: Modifier = Modifier) {
    ExpressiveSectionPanel(
        modifier = modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ExpressiveHeaderBlock(title = "About")
            Text(
                text = "Built for testing navigation, playback, and Subsonic integration.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Version 0.1.0",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
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
        displayName = "alt · backup server",
        subtitle = "backup.example",
        provider = ProviderKind.SUBSONIC,
        isActive = false,
    ),
)

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
fun SettingsContentPreview() {
    YoinTheme {
        SettingsContent(
            uiState = SettingsUiState.Content(
                profileCards = previewCards,
                activeProfileId = "a",
                canAddProfile = true,
                cacheSizeBytes = 52_428_800L,
                geminiApiKey = "",
            ),
            switchingState = ProfileManager.SwitchState.Idle,
            profileFormSheet = ProfileFormSheet.Hidden,
            providerPickerVisible = false,
            deleteConfirmState = DeleteConfirmState.Hidden,
            onBackClick = {},
            onSwitchToProfile = {},
            onEditProfile = {},
            onRequestDeleteProfile = {},
            onReconnectProfile = {},
            onShowProviderPicker = {},
            onHideProviderPicker = {},
            onPickProvider = {},
            onCloseFormSheet = {},
            onTestConnection = { _, _, _ -> },
            onSaveProfile = { _, _, _ -> },
            onDismissSwitchError = {},
            onDismissDeleteConfirm = {},
            onConfirmDeleteProfile = {},
            onSaveGeminiApiKey = {},
            onClearCache = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
fun SettingsContentLoadingPreview() {
    YoinTheme {
        SettingsContent(
            uiState = SettingsUiState.Loading,
            switchingState = ProfileManager.SwitchState.Idle,
            profileFormSheet = ProfileFormSheet.Hidden,
            providerPickerVisible = false,
            deleteConfirmState = DeleteConfirmState.Hidden,
            onBackClick = {},
            onSwitchToProfile = {},
            onEditProfile = {},
            onRequestDeleteProfile = {},
            onReconnectProfile = {},
            onShowProviderPicker = {},
            onHideProviderPicker = {},
            onPickProvider = {},
            onCloseFormSheet = {},
            onTestConnection = { _, _, _ -> },
            onSaveProfile = { _, _, _ -> },
            onDismissSwitchError = {},
            onDismissDeleteConfirm = {},
            onConfirmDeleteProfile = {},
            onSaveGeminiApiKey = {},
            onClearCache = {},
        )
    }
}
