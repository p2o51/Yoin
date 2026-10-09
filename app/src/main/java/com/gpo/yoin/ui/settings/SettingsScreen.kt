package com.gpo.yoin.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpo.yoin.BuildConfig
import com.gpo.yoin.MainActivity
import com.gpo.yoin.R
import com.gpo.yoin.data.integration.neodb.NeoDBOAuthContract
import com.gpo.yoin.data.local.GeminiConfig
import com.gpo.yoin.data.profile.ProfileManager
import com.gpo.yoin.data.profile.ProviderKind
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.common.asString
import com.gpo.yoin.ui.component.ExpressiveTextField
import com.gpo.yoin.ui.component.SeamTopPreference
import com.gpo.yoin.ui.component.SeamTopStyle
import com.gpo.yoin.ui.component.nameRes
import com.gpo.yoin.ui.component.YoinDropdownMenu
import com.gpo.yoin.ui.component.YoinDropdownMenuItem
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.component.currentSeamTopStyle
import com.gpo.yoin.ui.component.ignoreParentHorizontalPadding
import com.gpo.yoin.ui.component.minimumTouchTarget
import com.gpo.yoin.ui.detail.DetailBackButton
import com.gpo.yoin.ui.experience.rememberIsActivityEmbedded
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.settings.service.ServiceSetupContract
import com.gpo.yoin.ui.settings.service.ServiceSetupRequest
import com.gpo.yoin.ui.settings.service.SetupService
import com.gpo.yoin.ui.settings.sync.CloudSyncActivity
import com.gpo.yoin.ui.settings.sync.CloudSyncSettingsRow
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
    // List-detail (SettingsTablet, 断点交接 §7): in an Activity Embedding split
    // the account pages open in the right pane; the list marks the open one.
    val listDetail = rememberIsActivityEmbedded()
    var openAccountId by rememberSaveable { mutableStateOf<String?>(null) }
    var openFeature by rememberSaveable { mutableStateOf<SettingsFeature?>(null) }
    var cloudSyncOpen by rememberSaveable { mutableStateOf(false) }
    // The setup page hands back the id of a newly added account; the switch
    // runs here so it survives that page finishing.
    val serviceSetupLauncher = rememberLauncherForActivityResult(ServiceSetupContract()) { activateId ->
        openAccountId = null
        activateId?.let(viewModel::switchToProfile)
    }
    val openService: (ServiceSetupRequest) -> Unit = { request ->
        openFeature = null
        cloudSyncOpen = false
        openAccountId = request.profileId
        serviceSetupLauncher.launch(request)
    }
    val featureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        openFeature = null
    }
    val context = LocalContext.current
    val openFeaturePage: (SettingsFeature) -> Unit = { feature ->
        openAccountId = null
        cloudSyncOpen = false
        openFeature = feature
        featureLauncher.launch(SettingsFeatureActivity.intent(context, feature))
    }
    val cloudSyncLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        cloudSyncOpen = false
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is SettingsOneShotEvent.LaunchNeoDbOAuth -> neoDbOAuthLauncher.launch(event.instance)
                is SettingsOneShotEvent.ShowError ->
                    scope.launch { snackbarHostState.showSnackbar(event.message.asString(context)) }
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
                face = spotifyProfile?.face,
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
        listDetail = listDetail,
        openAccountId = openAccountId,
        openFeature = openFeature,
        onOpenFeature = openFeaturePage,
        cloudSyncRow = {
            CloudSyncSettingsRow(
                showChevron = listDetail,
                onClick = {
                    openAccountId = null
                    openFeature = null
                    cloudSyncOpen = true
                    cloudSyncLauncher.launch(CloudSyncActivity.intent(context))
                },
            )
        },
        cloudSyncSelected = cloudSyncOpen,
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
    // List-detail pane: accounts as rows, the open one highlighted (§7).
    listDetail: Boolean = false,
    openAccountId: String? = null,
    openFeature: SettingsFeature? = null,
    onOpenFeature: (SettingsFeature) -> Unit = {},
    // Settings › Storage › Cloud sync; null in previews/tests that have no app container.
    cloudSyncRow: (@Composable () -> Unit)? = null,
    cloudSyncSelected: Boolean = false,
) {
    // Large title → app-bar title handoff (Pixel's collapsing header): the
    // positions land in plain float state and are read only in the bar
    // title's graphicsLayer, so scrolling redraws one layer.
    val viewportTop = remember { mutableFloatStateOf(0f) }
    val headlineTop = remember { mutableFloatStateOf(Float.NaN) }
    val headlineHeight = remember { mutableFloatStateOf(1f) }
    var headerHeightPx by remember { mutableIntStateOf(0) }
    ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
        SettingsPageBackground(listPane = listDetail, modifier = modifier) {
            Box(modifier = Modifier.fillMaxSize()) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    text = stringResource(R.string.settings_title),
                                    modifier = Modifier.graphicsLayer {
                                        alpha = handoffProgress(viewportTop, headlineTop, headlineHeight)
                                        translationY = (1f - alpha) * size.height * 0.5f
                                    },
                                )
                            },
                            navigationIcon = { SettingsBackButton(onClick = onBackClick) },
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
                            scrollState.animateScrollTo(headerHeightPx + neoDbTopPx)
                        }
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            // Keep low-on-page fields (API key / token) above
                            // the IME (Scaffold contentWindowInsets is 0).
                            .imePadding()
                            .padding(innerPadding)
                            .onGloballyPositioned { viewportTop.floatValue = it.positionInWindow().y }
                            // Full width on every window (user, 2026-10-04):
                            // a centred reading column under a full-bleed
                            // card row read as two layouts.
                            .verticalScroll(scrollState)
                            .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp + navBottom),
                    ) {
                        SettingsHeadline(
                            title = stringResource(R.string.settings_title),
                            modifier = Modifier.onSizeChanged { headerHeightPx = it.height },
                            titleModifier = Modifier.onGloballyPositioned {
                                headlineTop.floatValue = it.positionInWindow().y
                                headlineHeight.floatValue = it.size.height.toFloat().coerceAtLeast(1f)
                            },
                        )
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
                                        asRows = listDetail,
                                        openAccountId = openAccountId,
                                    )
                                    SettingsGroup(
                                        title = stringResource(R.string.settings_section_features),
                                        modifier = Modifier.onGloballyPositioned { coords ->
                                            neoDbTopPx = coords.positionInParent().y.toInt().coerceAtLeast(0)
                                        },
                                    ) {
                                        item(key = SettingsFeature.Gemini, paintsOwnSegment = listDetail) {
                                            GeminiItem(
                                                apiKey = state.geminiApiKey,
                                                targetLanguage = state.geminiTargetLanguage,
                                                onSaveApiKey = onSaveGeminiApiKey,
                                                onSaveTargetLanguage = onSaveGeminiTargetLanguage,
                                                // List-detail: the feature opens on the right (§7).
                                                onOpenPage = if (listDetail) {
                                                    { onOpenFeature(SettingsFeature.Gemini) }
                                                } else {
                                                    null
                                                },
                                                selected = openFeature == SettingsFeature.Gemini,
                                            )
                                        }
                                        item(key = SettingsFeature.NeoDb, paintsOwnSegment = listDetail) {
                                            NeoDbItem(
                                                instance = state.neoDbInstance,
                                                accessToken = state.neoDbAccessToken,
                                                initiallyExpanded = focusSection == SettingsFocus.NEODB,
                                                onOpenSignIn = onOpenNeoDbSignIn,
                                                onSaveConfig = onSaveNeoDbConfig,
                                                onClearToken = onClearNeoDbToken,
                                                onOpenPage = if (listDetail) {
                                                    { onOpenFeature(SettingsFeature.NeoDb) }
                                                } else {
                                                    null
                                                },
                                                selected = openFeature == SettingsFeature.NeoDb,
                                            )
                                        }
                                    }
                                    SettingsGroup(title = stringResource(R.string.settings_section_motion)) {
                                        item(key = SettingsFeature.ScrollEdge, paintsOwnSegment = listDetail) {
                                            ScrollEdgeItem(
                                                listDetail = listDetail,
                                                selected = openFeature == SettingsFeature.ScrollEdge,
                                                onOpenPage = { onOpenFeature(SettingsFeature.ScrollEdge) },
                                            )
                                        }
                                    }
                                    SettingsGroup(title = stringResource(R.string.settings_section_storage)) {
                                        cloudSyncRow?.let { row ->
                                            item(key = "cloud-sync", paintsOwnSegment = listDetail) {
                                                if (listDetail) SelectableSegment(selected = cloudSyncSelected) { row() } else row()
                                            }
                                        }
                                        item { CacheItem(state.cacheSizeBytes, onClearCache) }
                                    }
                                    SettingsGroup(title = stringResource(R.string.settings_section_about)) {
                                        item { WelcomeGuideItem() }
                                        item {
                                            SettingsItem(
                                                icon = YoinSymbols.Info,
                                                title = stringResource(R.string.settings_about_name),
                                                summary = stringResource(
                                                    R.string.settings_about_version,
                                                    BuildConfig.VERSION_NAME,
                                                ),
                                            )
                                        }
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

/**
 * Pixel's sub-page header: the page title set large (displaySmall) on the
 * 24dp line the group labels share, with generous air above and below.
 */
@Composable
internal fun SettingsHeadline(
    title: String,
    modifier: Modifier = Modifier,
    titleModifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Spacer(Modifier.height(40.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = titleModifier
                .padding(horizontal = 8.dp)
                .semantics { heading() },
        )
        Spacer(Modifier.height(36.dp))
    }
}

/** 0 while the large title is in view, 1 once it has slid fully under the bar; tracks the scroll 1:1. */
internal fun handoffProgress(
    viewportTop: MutableFloatState,
    titleTop: MutableFloatState,
    titleHeight: MutableFloatState,
): Float {
    val top = titleTop.floatValue
    if (top.isNaN()) return 0f
    return ((viewportTop.floatValue - top) / titleHeight.floatValue).coerceIn(0f, 1f)
}

/**
 * Pixel's back affordance: the arrow on a tonal circle, its left edge on the
 * same 24dp line as the large title and the group labels.
 */
@Composable
internal fun SettingsBackButton(onClick: () -> Unit) {
    DetailBackButton(
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        // End air so the handed-off bar title clears the filled circle.
        modifier = Modifier.padding(start = 20.dp, end = 14.dp),
    )
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
    asRows: Boolean = false,
    openAccountId: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsGroupLabel(
            title = stringResource(R.string.settings_section_accounts),
            trailingLabel = if (profileCards.isEmpty()) {
                null
            } else {
                stringResource(R.string.settings_accounts_count, profileCards.size, maxProfiles)
            },
        )
        if (profileCards.isEmpty()) {
            EmptyAccountsCard(onAddAccount)
            return@Column
        }
        if (asRows) {
            // List-detail: a row per account; tapping opens its page on the
            // right (the ⋮ menu keeps "Use" and "Remove").
            SettingsSegments {
                profileCards.forEach { card ->
                    item(key = card.id, paintsOwnSegment = true) {
                        ProfileAccountRow(
                            card = card,
                            selected = card.id == openAccountId,
                            onOpen = {
                                SetupService.forProvider(card.provider)?.let { service ->
                                    onOpenService(ServiceSetupRequest(service, profileId = card.id, face = card.face))
                                }
                            },
                            onUse = { onSwitchToProfile(card.id) },
                            onRemove = { onRequestDeleteProfile(card.id) },
                        )
                    }
                }
                if (canAddProfile) {
                    item(key = "add") {
                        SettingsItem(
                            icon = YoinSymbols.Add,
                            title = stringResource(R.string.settings_accounts_add),
                            onClick = onAddAccount,
                        )
                    }
                }
            }
            return@Column
        }
        AccountCardRow(
            profileCards = profileCards,
            canAddProfile = canAddProfile,
            onSwitchToProfile = onSwitchToProfile,
            onOpenService = onOpenService,
            onRequestDeleteProfile = onRequestDeleteProfile,
            onAddAccount = onAddAccount,
        )
    }
}

/**
 * The account switcher. It runs edge to edge of the screen, past the 16dp
 * page padding, so cards are only ever cut by the screen edge; resting, the
 * first card sits on the page margin. No edge fade: the row cuts cleanly.
 */
@Composable
private fun AccountCardRow(
    profileCards: List<ProfileCard>,
    canAddProfile: Boolean,
    onSwitchToProfile: (String) -> Unit,
    onOpenService: (ServiceSetupRequest) -> Unit,
    onRequestDeleteProfile: (String) -> Unit,
    onAddAccount: () -> Unit,
) {
    val bleed = 16.dp
    LazyRow(
        contentPadding = PaddingValues(horizontal = bleed, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(accountCardHeight())
            .ignoreParentHorizontalPadding(bleed),
    ) {
        items(items = profileCards, key = { it.id }) { card ->
            val manage = {
                SetupService.forProvider(card.provider)?.let { service ->
                    onOpenService(ServiceSetupRequest(service, profileId = card.id, face = card.face))
                }
                Unit
            }
            // Anything that needs attention — or the account you're
            // already on — opens its page; any other card switches.
            val opensPage = card.isActive || card.requiresReconnect || card.requiresCredentialsReentry
            ProfileCardTile(
                modifier = Modifier.animateItem(
                    fadeInSpec = YoinMotion.defaultEffectsSpec(),
                    placementSpec = YoinMotion.spatialSpring(),
                    fadeOutSpec = YoinMotion.defaultEffectsSpec(),
                ),
                card = card,
                tapLabel = stringResource(
                    if (opensPage) R.string.settings_cd_manage_account else R.string.settings_cd_switch_account,
                ),
                onTap = {
                    if (opensPage) manage() else onSwitchToProfile(card.id)
                },
                onManage = manage,
                onRemove = { onRequestDeleteProfile(card.id) },
            )
        }
        if (canAddProfile) {
            item(key = "add") {
                AddAccountTile(
                    onClick = onAddAccount,
                    modifier = Modifier.animateItem(
                        fadeInSpec = YoinMotion.defaultEffectsSpec(),
                        placementSpec = YoinMotion.spatialSpring(),
                        fadeOutSpec = YoinMotion.defaultEffectsSpec(),
                    ),
                )
            }
        }
    }
}

/**
 * Card height from its content, so text never clips at large font scales:
 * the fixed parts in dp (the row's 6+6 padding, the card's 16+16, the 48dp
 * avatar row, the pill's 10dp gap and 3+3 padding, a few dp of slack) plus
 * each text line converted on its own — Android 14+ scales large sp less than
 * small sp, so one summed sp value would undercount.
 */
@Composable
private fun accountCardHeight(): Dp {
    val type = MaterialTheme.typography
    return with(LocalDensity.current) {
        112.dp + type.titleMedium.lineHeight.toDp() * 2 + type.bodyMedium.lineHeight.toDp() +
            type.labelMedium.lineHeight.toDp()
    }
}

/** One account as a list-detail row: the open one detaches onto the detail pane's colour. */
@Composable
private fun ProfileAccountRow(
    card: ProfileCard,
    selected: Boolean,
    onOpen: () -> Unit,
    onUse: () -> Unit,
    onRemove: () -> Unit,
) {
    val haptics = rememberYoinHaptics()
    val identity = card.provider.serviceIdentity
    var menuOpen by remember { mutableStateOf(false) }
    SelectableSegment(selected = selected) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("account_row_${card.id}"),
            shape = LocalSettingsRowShape.current,
            color = Color.Transparent,
            onClick = {
                haptics.performClick()
                onOpen()
            },
        ) {
            Row(
                modifier = Modifier
                    .heightIn(min = 72.dp)
                    .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AccountAvatar(
                    monogram = monogramOf(card.title, stringResource(identity.nameRes)),
                    shapeIndex = card.avatarShape,
                    photoUrl = card.photoUrl,
                    identity = identity,
                    ringColor = LocalSettingsRowColor.current,
                    size = 40.dp,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = card.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    serviceLineGroups(
                        stringResource(identity.nameRes),
                        card.title,
                        card.subtitle,
                    )?.let { groups ->
                        ServiceLine(groups = groups)
                    }
                    AccountStatusPill(card = card, modifier = Modifier.padding(top = 6.dp))
                }
                Box {
                    IconButton(
                        onClick = {
                            haptics.performTick()
                            menuOpen = true
                        },
                        modifier = Modifier.minimumTouchTarget(),
                    ) {
                        Icon(
                            imageVector = YoinSymbols.MoreVertical,
                            contentDescription = stringResource(R.string.settings_cd_account_options_row, card.title),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    YoinDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (!card.isActive) {
                            YoinDropdownMenuItem(
                                text = stringResource(R.string.settings_accounts_use),
                                onClick = {
                                    haptics.performContextClick()
                                    menuOpen = false
                                    onUse()
                                },
                            )
                        }
                        YoinDropdownMenuItem(
                            text = stringResource(R.string.settings_accounts_remove_menu),
                            onClick = {
                                haptics.performReject()
                                menuOpen = false
                                onRemove()
                            },
                        )
                    }
                }
            }
        }
    }
}

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
            Text(stringResource(R.string.settings_accounts_empty_title), style = MaterialTheme.typography.titleLarge)
            Button(
                onClick = {
                    haptics.performClick()
                    onAddAccount()
                },
            ) {
                Icon(YoinSymbols.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_accounts_add))
            }
        }
    }
}

/**
 * One account in the switcher. The service is the colour (the account in use
 * fills with it) plus the badge on the avatar and its name on the line under
 * the account; the avatar's shape and letter tell two accounts on one
 * service apart.
 */
@Composable
private fun ProfileCardTile(
    card: ProfileCard,
    tapLabel: String,
    onTap: () -> Unit,
    onManage: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberYoinHaptics()
    val identity = card.provider.serviceIdentity
    val tone = identity.hue.tone()
    val surfaces = settingsSurfaces()
    val containerColor by animateColorAsState(
        targetValue = if (card.isActive) tone.container else surfaces.row,
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "accountCardContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = if (card.isActive) tone.onContainer else MaterialTheme.colorScheme.onSurface,
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "accountCardContent",
    )
    // Secondary text at full strength on the tinted card (AA at 12–14sp);
    // the usual variant colour on a plain one.
    val secondaryColor by animateColorAsState(
        targetValue = if (card.isActive) tone.onContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "accountCardSecondary",
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
            .testTag("account_card_${card.id}")
            .semantics {
                selected = card.isActive
                onClick(label = tapLabel) {
                    onTap()
                    true
                }
            },
        shape = YoinContainerShapes.Card,
        color = containerColor,
        contentColor = contentColor,
        onClick = {
            haptics.performClick()
            onTap()
        },
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 4.dp, bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                AccountAvatar(
                    monogram = monogramOf(card.title, stringResource(identity.nameRes)),
                    shapeIndex = card.avatarShape,
                    photoUrl = card.photoUrl,
                    identity = identity,
                    ringColor = containerColor,
                )
                Spacer(Modifier.weight(1f))
                Box(modifier = Modifier.offset(y = (-8).dp)) {
                    IconButton(
                        onClick = {
                            haptics.performTick()
                            menuOpen = true
                        },
                        modifier = Modifier.minimumTouchTarget(),
                    ) {
                        Icon(
                            imageVector = YoinSymbols.MoreVertical,
                            contentDescription = stringResource(R.string.settings_cd_account_options_card, card.title),
                            tint = secondaryColor,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    YoinDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        YoinDropdownMenuItem(
                            text = stringResource(R.string.settings_accounts_manage),
                            onClick = {
                                haptics.performContextClick()
                                menuOpen = false
                                onManage()
                            },
                        )
                        YoinDropdownMenuItem(
                            text = stringResource(R.string.settings_accounts_remove_card),
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
            // Name and service read as one cluster; the status pill is its own.
            Text(
                text = card.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 12.dp),
            )
            serviceLineGroups(
                stringResource(identity.nameRes),
                card.title,
                card.subtitle,
            )?.let { groups ->
                ServiceLine(
                    groups = groups,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
            AccountStatusPill(card = card, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

/**
 * The one status an account can need to say: a problem (error pill) beats
 * "In use" (a pill in the service's own accent) — and when the account in
 * use has a problem the pill says both, so "in use" never rests on colour
 * alone. Otherwise nothing. Grows in and out on the spatial spring.
 */
@Composable
private fun AccountStatusPill(card: ProfileCard, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val issue = card.unavailableReason?.let { accountIssueLabel(it) }
    val status: Triple<String, Color, Color>? = issue?.let {
        Triple(it, scheme.errorContainer, scheme.onErrorContainer)
    }
    // Keep the last label on screen while the pill shrinks away.
    var shown by remember { mutableStateOf(status) }
    if (status != null) shown = status
    AnimatedVisibility(
        visible = status != null,
        modifier = modifier,
        enter = expandVertically(
            animationSpec = YoinMotion.spatialSpring(),
            expandFrom = Alignment.Top,
        ) + YoinMotion.fadeIn(role = YoinMotionRole.Standard),
        exit = shrinkVertically(
            animationSpec = YoinMotion.spatialSpring(),
            shrinkTowards = Alignment.Top,
        ) + YoinMotion.fadeOut(role = YoinMotionRole.Standard),
    ) {
        val (label, container, content) = shown ?: return@AnimatedVisibility
        val pillColor by animateColorAsState(
            targetValue = container,
            animationSpec = YoinMotion.defaultEffectsSpec(),
            label = "accountStatusPill",
        )
        val textColor by animateColorAsState(
            targetValue = content,
            animationSpec = YoinMotion.defaultEffectsSpec(),
            label = "accountStatusPillText",
        )
        Surface(
            shape = YoinShapeTokens.Full,
            color = pillColor,
            contentColor = textColor,
        ) {
            AnimatedContent(
                targetState = label,
                transitionSpec = {
                    YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                        YoinMotion.fadeOut(role = YoinMotionRole.Standard)
                },
                label = "accountStatusLabel",
            ) { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun AddAccountTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberYoinHaptics()
    Surface(
        modifier = modifier
            .width(112.dp)
            .fillMaxHeight()
            .testTag("add_account"),
        shape = YoinContainerShapes.Card,
        color = settingsSurfaces().row,
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
            Icon(YoinSymbols.Add, contentDescription = null, modifier = Modifier.size(28.dp))
            Text(stringResource(R.string.settings_accounts_add_short), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Settings' flat page colour (Pixel Settings), in place of the shell's gradient. */
@Composable
internal fun SettingsPageBackground(
    modifier: Modifier = Modifier,
    // The list pane of the two-pane split steps down to surfaceDim.
    listPane: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val page by animateColorAsState(
        targetValue = settingsSurfaces(listPane).page,
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "settingsPage",
    )
    CompositionLocalProvider(LocalSettingsHueSource provides rememberSystemPrimary()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(page),
            content = content,
        )
    }
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
                text = stringResource(R.string.settings_add_sheet_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
            )
            ServiceChoiceRow(
                identity = ProviderKind.SUBSONIC.serviceIdentity,
                summary = stringResource(R.string.settings_provider_subsonic_summary),
                onClick = { pick(SetupService.Subsonic) },
            )
            ServiceChoiceRow(
                identity = ProviderKind.SPOTIFY.serviceIdentity,
                summary = stringResource(R.string.settings_provider_spotify_summary),
                onClick = { pick(SetupService.Spotify) },
            )
            ServiceChoiceRow(
                identity = ProviderKind.APPLE_MUSIC.serviceIdentity,
                summary = stringResource(R.string.settings_provider_apple_summary),
                badge = stringResource(R.string.settings_provider_badge_preview),
                onClick = { pick(SetupService.AppleMusic) },
            )
            ServiceChoiceRow(
                identity = ProviderKind.LOCAL.serviceIdentity,
                summary = stringResource(R.string.settings_provider_local_summary),
                onClick = null,
            )
        }
    }
}

@Composable
private fun ServiceChoiceRow(
    identity: ServiceIdentity,
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
            SettingsRowIcon(identity.glyph, tone = identity.hue.tone())
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(identity.nameRes), style = MaterialTheme.typography.titleMedium)
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
                    YoinSymbols.ChevronRight,
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
    // List-detail: the row opens the feature's page on the right instead.
    onOpenPage: (() -> Unit)? = null,
    selected: Boolean = false,
    // The feature's own page: always open.
    asPage: Boolean = false,
) {
    val summaryText = if (apiKey.isBlank()) {
        stringResource(R.string.settings_gemini_summary_empty)
    } else {
        stringResource(
            R.string.settings_gemini_summary_set,
            GeminiConfig.normalizeTargetLanguage(targetLanguage),
        )
    }
    if (onOpenPage != null) {
        FeaturePageRow(
            icon = YoinSymbols.Sparkle,
            title = stringResource(R.string.settings_gemini_title),
            summary = summaryText,
            selected = selected,
            onClick = onOpenPage,
        )
        return
    }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var draftKey by rememberSaveable(apiKey) { mutableStateOf(apiKey) }
    var languageMenuOpen by remember { mutableStateOf(false) }
    val language = GeminiConfig.normalizeTargetLanguage(targetLanguage)
    val haptics = rememberYoinHaptics()

    SettingsExpandableItem(
        icon = YoinSymbols.Sparkle,
        title = stringResource(R.string.settings_gemini_title),
        summary = summaryText,
        expanded = expanded,
        onExpandedChange = { expanded = it },
        collapsible = !asPage,
    ) {
        SecretTextField(
            value = draftKey,
            onValueChange = { draftKey = it },
            label = stringResource(R.string.settings_gemini_key_label),
            placeholder = stringResource(R.string.settings_gemini_key_placeholder),
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
                Text(stringResource(R.string.settings_gemini_answer_in, language), modifier = Modifier.weight(1f))
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
        ) { Text(stringResource(R.string.settings_gemini_save_key)) }
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
    onOpenPage: (() -> Unit)? = null,
    selected: Boolean = false,
    asPage: Boolean = false,
) {
    if (onOpenPage != null) {
        FeaturePageRow(
            icon = YoinSymbols.Reviews,
            title = stringResource(R.string.settings_neodb_title),
            summary = stringResource(
                if (accessToken.isNotBlank()) {
                    R.string.settings_neodb_summary_on
                } else {
                    R.string.settings_neodb_summary_off
                },
            ),
            selected = selected,
            onClick = onOpenPage,
        )
        return
    }
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    var draftInstance by rememberSaveable(instance) { mutableStateOf(instance) }
    var draftToken by rememberSaveable(accessToken) { mutableStateOf(accessToken) }
    var showManualToken by rememberSaveable { mutableStateOf(false) }
    val signedIn = accessToken.isNotBlank()

    SettingsExpandableItem(
        icon = YoinSymbols.Reviews,
        title = stringResource(R.string.settings_neodb_title_page),
        summary = stringResource(
            if (signedIn) R.string.settings_neodb_summary_on_page else R.string.settings_neodb_summary_off_page,
        ),
        expanded = expanded,
        onExpandedChange = { expanded = it },
        collapsible = !asPage,
    ) {
        ExpressiveTextField(
            value = draftInstance,
            onValueChange = { draftInstance = it },
            label = stringResource(R.string.settings_neodb_instance_label),
            placeholder = stringResource(R.string.settings_neodb_instance_placeholder),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onOpenSignIn(draftInstance) }) {
                Text(
                    stringResource(
                        if (signedIn) R.string.settings_neodb_sign_in_again else R.string.settings_neodb_sign_in,
                    ),
                )
            }
            if (signedIn) {
                TextButton(
                    onClick = {
                        draftToken = ""
                        onClearToken()
                    },
                ) { Text(stringResource(R.string.settings_neodb_sign_out)) }
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
                TextButton(onClick = { showManualToken = true }) {
                    Text(stringResource(R.string.settings_neodb_use_token))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SecretTextField(
                        value = draftToken,
                        onValueChange = { draftToken = it },
                        label = stringResource(R.string.settings_neodb_token_label),
                        placeholder = stringResource(R.string.settings_neodb_token_placeholder),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        onClick = { onSaveConfig(draftInstance, draftToken) },
                        enabled = draftToken.isNotBlank() && draftToken.trim() != accessToken.trim(),
                    ) { Text(stringResource(R.string.settings_neodb_save_token)) }
                }
            }
        }
    }
}

/** A Features row in list-detail: opens the feature's page on the right, marked while open. */
@Composable
private fun FeaturePageRow(
    icon: ImageVector,
    title: String,
    summary: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    SelectableSegment(selected = selected) {
        SettingsItem(
            icon = icon,
            title = title,
            summary = summary,
            onClick = onClick,
            trailing = {
                Icon(
                    imageVector = YoinSymbols.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
    }
}

/**
 * A row that can be the open one in the two-pane list (Pixel): open, it takes
 * the detail pane's colour and rounds all its corners, detaching from its
 * neighbours — colour on the effects spring, corners on the spatial spring.
 * Its group item must be `paintsOwnSegment`.
 */
@Composable
private fun SelectableSegment(selected: Boolean, content: @Composable () -> Unit) {
    val surfaces = settingsSurfaces()
    val container by animateColorAsState(
        targetValue = if (selected) surfaces.rowSelected else surfaces.row,
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "segmentSelection",
    )
    val shape = animateSelectedRowShape(selected)
    Surface(
        color = container,
        shape = shape,
        // The open row is the selected one for TalkBack, too.
        modifier = Modifier.semantics { this.selected = selected },
    ) {
        CompositionLocalProvider(LocalSettingsRowShape provides shape, LocalSettingsRowColor provides container) {
            content()
        }
    }
}

/** The colour behind the current row (for badges that ring-cut against it). */
private val LocalSettingsRowColor = staticCompositionLocalOf { Color.Unspecified }

/** Which Settings feature a [SettingsFeatureScreen] shows. */
enum class SettingsFeature { Gemini, NeoDb, ScrollEdge }

/**
 * A Settings feature (AI features / NeoDB) as its own page — the right pane of
 * the Settings list-detail (断点交接 §7). Same controls as the in-place
 * expansion on narrow windows, always open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsFeatureScreen(
    viewModel: SettingsViewModel,
    feature: SettingsFeature,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val neoDbOAuthLauncher = rememberLauncherForActivityResult(NeoDBOAuthContract()) { result ->
        viewModel.commitNeoDbOAuth(result)
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is SettingsOneShotEvent.LaunchNeoDbOAuth -> neoDbOAuthLauncher.launch(event.instance)
                is SettingsOneShotEvent.ShowError ->
                    scope.launch { snackbarHostState.showSnackbar(event.message.asString(context)) }
            }
        }
    }
    val featureTitle = when (feature) {
        SettingsFeature.Gemini -> stringResource(R.string.settings_gemini_title_page)
        SettingsFeature.NeoDb -> stringResource(R.string.settings_neodb_title_page)
        SettingsFeature.ScrollEdge -> stringResource(R.string.settings_motion_scroll_edge)
    }
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
                                text = featureTitle,
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
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .imePadding()
                        .padding(innerPadding)
                        .onGloballyPositioned { viewportTop.floatValue = it.positionInWindow().y }
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp + navBottom),
                ) {
                    SettingsHeadline(
                        title = featureTitle,
                        titleModifier = Modifier.onGloballyPositioned {
                            headlineTop.floatValue = it.positionInWindow().y
                            headlineHeight.floatValue = it.size.height.toFloat().coerceAtLeast(1f)
                        },
                    )
                    val content = uiState as? SettingsUiState.Content
                    if (feature == SettingsFeature.ScrollEdge) {
                        // Reads only the global style: no need to wait for the Settings state.
                        ScrollEdgePageContent()
                    } else if (content == null) {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            YoinLoadingIndicator()
                        }
                    } else {
                        SettingsSegments {
                            item {
                                when (feature) {
                                    SettingsFeature.Gemini -> GeminiItem(
                                        apiKey = content.geminiApiKey,
                                        targetLanguage = content.geminiTargetLanguage,
                                        onSaveApiKey = viewModel::saveGeminiApiKey,
                                        onSaveTargetLanguage = viewModel::saveGeminiTargetLanguage,
                                        asPage = true,
                                    )
                                    SettingsFeature.NeoDb -> NeoDbItem(
                                        instance = content.neoDbInstance,
                                        accessToken = content.neoDbAccessToken,
                                        initiallyExpanded = true,
                                        onOpenSignIn = viewModel::openNeoDbSignIn,
                                        onSaveConfig = viewModel::saveNeoDbConfig,
                                        onClearToken = viewModel::clearNeoDbToken,
                                        asPage = true,
                                    )
                                    SettingsFeature.ScrollEdge -> Unit
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Motion ───────────────────────────────────────────────────────────

/**
 * How scrolling content meets the fixed chrome at the top of a page (the
 * chips, a detail page's header): the tide line, the original dots or the
 * Cookie wave. Only that seam; it repaints open pages live.
 */
@Composable
private fun ScrollEdgeItem(
    listDetail: Boolean,
    selected: Boolean,
    onOpenPage: () -> Unit,
) {
    val title = stringResource(R.string.settings_motion_scroll_edge)
    val summary = stringResource(currentSeamTopStyle().nameRes)
    if (listDetail) {
        FeaturePageRow(icon = YoinSymbols.UnfoldLess, title = title, summary = summary, selected = selected, onClick = onOpenPage)
    } else {
        SettingsItem(icon = YoinSymbols.UnfoldLess, title = title, summary = summary, onClick = onOpenPage)
    }
}

/** Settings › About › Welcome guide: back to Home with the landing running again (accounts kept). */
@Composable
private fun WelcomeGuideItem() {
    val context = LocalContext.current
    SettingsItem(
        icon = YoinSymbols.Refresh,
        title = stringResource(R.string.settings_about_welcome_guide),
        summary = stringResource(R.string.settings_about_welcome_guide_summary),
        onClick = { context.startActivity(MainActivity.welcomeGuideIntent(context)) },
    )
}

// ── Storage ──────────────────────────────────────────────────────────

@Composable
private fun CacheItem(cacheSizeBytes: Long, onClearCache: () -> Unit) {
    val haptics = rememberYoinHaptics()
    SettingsItem(
        icon = YoinSymbols.Storage,
        title = stringResource(R.string.settings_storage_cache),
        summary = formatBytes(cacheSizeBytes, LocalContext.current.resources),
        trailing = {
            TextButton(
                onClick = {
                    haptics.performReject()
                    onClearCache()
                },
                enabled = cacheSizeBytes > 0,
            ) { Text(stringResource(R.string.settings_storage_clear)) }
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
                            text = stringResource(R.string.settings_switch_title),
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
                            imageVector = YoinSymbols.ErrorFilled,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(36.dp),
                        )
                        Text(
                            text = stringResource(R.string.settings_switch_failed),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = switchingState.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = onDismissError) { Text(stringResource(R.string.settings_switch_ok)) }
                    }
                    ProfileManager.SwitchState.Idle -> Unit
                }
            }
        }
    }
}

@Composable
private fun stageLabel(stage: ProfileManager.SwitchState.Stage, activeName: String?): String {
    val name = activeName.orEmpty()
    return when (stage) {
        ProfileManager.SwitchState.Stage.Preparing -> stringResource(R.string.settings_switch_preparing)
        ProfileManager.SwitchState.Stage.Connecting ->
            if (name.isBlank()) {
                stringResource(R.string.settings_switch_connecting_unnamed)
            } else {
                stringResource(R.string.settings_switch_connecting, name)
            }
        ProfileManager.SwitchState.Stage.Priming -> stringResource(R.string.settings_switch_priming)
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
        title = { Text(stringResource(R.string.settings_remove_title)) },
        text = { Text(stringResource(R.string.settings_remove_body, displayName)) },
        confirmButton = {
            TextButton(
                onClick = {
                    haptics.performReject()
                    onConfirm()
                },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.settings_remove_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_remove_cancel)) } },
    )
}

private fun formatBytes(bytes: Long, resources: android.content.res.Resources): String = when {
    bytes <= 0L -> resources.getString(R.string.settings_cache_empty)
    bytes < 1_024L -> resources.getString(R.string.settings_cache_bytes, bytes)
    bytes < 1_048_576L -> resources.getString(R.string.settings_cache_kb, bytes / 1_024.0)
    bytes < 1_073_741_824L -> resources.getString(R.string.settings_cache_mb, bytes / 1_048_576.0)
    else -> resources.getString(R.string.settings_cache_gb, bytes / 1_073_741_824.0)
}

@Composable
private fun accountIssueLabel(reason: String): String = when (reason) {
    "No Client ID" -> stringResource(R.string.settings_spotify_no_client_id)
    "Install Spotify" -> stringResource(R.string.settings_spotify_install)
    "Premium required" -> stringResource(R.string.settings_spotify_premium_required)
    "Reconnect" -> stringResource(R.string.settings_account_reconnect)
    "Restore sign-in" -> stringResource(R.string.settings_spotify_restore_sign_in)
    "Credentials missing" -> stringResource(R.string.settings_account_credentials_missing)
    else -> reason
}


// ── Previews ─────────────────────────────────────────────────────────

private val previewCards = listOf(
    ProfileCard(
        id = "a",
        displayName = "demo @ demo.navidrome.org",
        subtitle = "demo.navidrome.org",
        provider = ProviderKind.SUBSONIC,
        isActive = true,
        title = "demo",
        avatarShape = 0,
    ),
    ProfileCard(
        id = "b",
        displayName = "Chen",
        subtitle = null,
        provider = ProviderKind.SPOTIFY,
        isActive = false,
        unavailableReason = "Reconnect",
        requiresReconnect = true,
        avatarShape = 1,
    ),
    ProfileCard(
        id = "c",
        displayName = "Apple Music",
        subtitle = null,
        provider = ProviderKind.APPLE_MUSIC,
        isActive = false,
        avatarShape = 2,
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
