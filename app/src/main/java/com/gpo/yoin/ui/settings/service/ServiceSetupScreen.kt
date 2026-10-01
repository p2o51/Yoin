package com.gpo.yoin.ui.settings.service

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.data.source.spotify.SpotifyOAuthContract
import com.gpo.yoin.ui.component.ExpressivePageBackground
import com.gpo.yoin.ui.component.ExpressiveTextField
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.component.YoinPageWidths
import com.gpo.yoin.ui.component.yoinPageContentWidth
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.settings.SecretTextField
import com.gpo.yoin.ui.settings.SettingsExpandableItem
import com.gpo.yoin.ui.settings.SettingsGroup
import com.gpo.yoin.ui.settings.SettingsItem
import com.gpo.yoin.ui.settings.SettingsRowDivider
import com.gpo.yoin.ui.settings.SettingsRowIcon
import com.gpo.yoin.ui.settings.applemusic.AppleMusicValidationSection
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinShapeTokens
import com.gpo.yoin.ui.theme.YoinTheme
import kotlinx.coroutines.launch

@Composable
fun ServiceSetupScreen(
    viewModel: ServiceSetupViewModel,
    focusClientId: Boolean,
    onBackClick: () -> Unit,
    onDone: (activateProfileId: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val spotifyOAuthLauncher = rememberLauncherForActivityResult(SpotifyOAuthContract()) {
        viewModel.commitSpotifyOAuth(it)
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ServiceSetupEvent.LaunchSpotifyOAuth -> spotifyOAuthLauncher.launch(event.targetProfileId)
                is ServiceSetupEvent.ShowError -> scope.launch { snackbarHostState.showSnackbar(event.message) }
                is ServiceSetupEvent.Done -> onDone(event.activateProfileId)
            }
        }
    }
    ServiceSetupContent(
        state = uiState,
        snackbarHostState = snackbarHostState,
        focusClientId = focusClientId,
        onBackClick = onBackClick,
        onTestSubsonic = viewModel::testSubsonicConnection,
        onSaveSubsonic = viewModel::saveSubsonicProfile,
        onSaveSpotifyClientId = viewModel::saveSpotifyClientId,
        onConnectSpotify = viewModel::connectSpotify,
        appleMusicContent = { AppleMusicValidationSection(profileId = viewModel.profileId, onSaved = { onDone(it) }) },
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceSetupContent(
    state: ServiceSetupUiState,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    focusClientId: Boolean = false,
    onTestSubsonic: (String, String, String) -> Unit = { _, _, _ -> },
    onSaveSubsonic: (String, String, String) -> Unit = { _, _, _ -> },
    onSaveSpotifyClientId: (String) -> Unit = {},
    onConnectSpotify: () -> Unit = {},
    appleMusicContent: @Composable () -> Unit = {},
) {
    val intro = state.service.intro
    val haptics = rememberYoinHaptics()
    val heroTitle = if (state.isManaging) state.existingProfileName ?: intro.name else intro.name
    // Hero title → app-bar title handoff. Positions land in plain float state
    // and are read only inside the bar title's graphicsLayer, so scrolling
    // redraws one layer and never recomposes the page.
    val viewportTop = remember { mutableFloatStateOf(0f) }
    val heroTitleTop = remember { mutableFloatStateOf(Float.NaN) }
    val heroTitleHeight = remember { mutableFloatStateOf(1f) }
    ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
        ExpressivePageBackground(modifier = modifier) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                text = heroTitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.graphicsLayer {
                                    // 0 while the hero title is in view, 1 once it has
                                    // fully slid under the bar; tracks the scroll 1:1.
                                    val top = heroTitleTop.floatValue
                                    val progress = if (top.isNaN()) {
                                        0f
                                    } else {
                                        ((viewportTop.floatValue - top) / heroTitleHeight.floatValue)
                                            .coerceIn(0f, 1f)
                                    }
                                    alpha = progress
                                    translationY = (1f - progress) * size.height * 0.5f
                                },
                            )
                        },
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
                        .yoinPageContentWidth(YoinPageWidths.Prose)
                        .imePadding()
                        .padding(innerPadding)
                        .onGloballyPositioned { viewportTop.floatValue = it.positionInWindow().y }
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp + navBottom),
                    verticalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    ServiceHero(
                        intro = intro,
                        title = heroTitle,
                        tagline = if (state.isManaging) intro.name else intro.tagline,
                        compactTitle = state.isManaging,
                        titleModifier = Modifier.onGloballyPositioned {
                            heroTitleTop.floatValue = it.positionInWindow().y
                            heroTitleHeight.floatValue = it.size.height.toFloat().coerceAtLeast(1f)
                        },
                    )
                    // Adding: pitch first, then connect. Managing: the user
                    // already knows the service — go straight to the controls.
                    if (!state.isManaging) {
                        HighlightsGroup(intro)
                        RequirementsGroup(intro.requirements)
                    }
                    when (state.service) {
                        SetupService.Subsonic -> SubsonicConnectGroup(
                            state = state,
                            onTest = onTestSubsonic,
                            onSave = onSaveSubsonic,
                        )
                        SetupService.Spotify -> SpotifyConnectGroup(
                            state = state,
                            focusClientId = focusClientId,
                            onSaveClientId = onSaveSpotifyClientId,
                            onConnect = onConnectSpotify,
                        )
                        SetupService.AppleMusic -> SettingsGroup(title = "Account") {
                            appleMusicContent()
                        }
                    }
                }
            }
        }
    }
}

// ── Hero ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ServiceHero(
    intro: ServiceIntro,
    title: String,
    tagline: String,
    compactTitle: Boolean = false,
    titleModifier: Modifier = Modifier,
) {
    // One-shot entrance: the mark blooms in on the spatial spring while the
    // page itself rides the native Activity open. Saveable so rotation and
    // process restore don't replay it.
    var played by rememberSaveable { mutableStateOf(false) }
    val bloom = remember { Animatable(if (played) 1f else 0.6f) }
    val bloomSpec = YoinMotion.slowSpatialSpring<Float>()
    LaunchedEffect(Unit) {
        played = true
        bloom.animateTo(1f, bloomSpec)
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(
            modifier = Modifier
                .size(96.dp)
                .graphicsLayer {
                    scaleX = bloom.value
                    scaleY = bloom.value
                    rotationZ = (1f - bloom.value) * -40f
                },
            shape = MaterialShapes.Cookie9Sided.toShape(),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(intro.icon, contentDescription = null, modifier = Modifier.size(44.dp))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = titleModifier,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    // Account names ("user @ host") run long; the service's
                    // own name gets the display size, a managed account one step down.
                    style = if (compactTitle) {
                        MaterialTheme.typography.headlineLarge
                    } else {
                        MaterialTheme.typography.displaySmall
                    },
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )
                intro.badge?.let { badge ->
                    Surface(
                        shape = YoinShapeTokens.Full,
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    ) {
                        Text(
                            text = badge,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            Text(
                text = tagline,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── What you get / what you need ─────────────────────────────────────

@Composable
private fun HighlightsGroup(intro: ServiceIntro) {
    SettingsGroup(title = "What you get") {
        intro.highlights.forEachIndexed { index, highlight ->
            if (index > 0) SettingsRowDivider()
            SettingsItem(icon = highlight.icon, title = highlight.title, summary = highlight.body)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RequirementsGroup(requirements: List<String>) {
    if (requirements.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "You'll need",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            requirements.forEach { requirement ->
                Surface(
                    shape = YoinShapeTokens.Full,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Row(
                        modifier = Modifier.padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(text = requirement, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

// ── Subsonic ──────────────────────────────────────────────────────────

@Composable
private fun SubsonicConnectGroup(
    state: ServiceSetupUiState,
    onTest: (String, String, String) -> Unit,
    onSave: (String, String, String) -> Unit,
) {
    val form = state.subsonic
    // Keyed on `loaded` so an edit form picks up the stored values once they
    // arrive instead of keeping the empty first frame.
    var serverUrl by rememberSaveable(form.loaded) { mutableStateOf(form.initialUrl) }
    var username by rememberSaveable(form.loaded) { mutableStateOf(form.initialUsername) }
    var password by rememberSaveable(form.loaded) { mutableStateOf(form.initialPassword) }
    val filled = serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()
    val canSubmit = filled && !form.isBusy && form.loaded && state.canAddProfile

    SettingsGroup(title = if (state.isManaging) "Server" else "Connect") {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (form.credentialsMissing) {
                InlineNotice("Sign in again — this account's saved password is no longer on this device.")
            }
            ExpressiveTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = "Server address",
                placeholder = "https://music.example.com",
                modifier = Modifier.fillMaxWidth(),
            )
            ExpressiveTextField(
                value = username,
                onValueChange = { username = it },
                label = "Username",
                placeholder = "Your username",
                modifier = Modifier.fillMaxWidth(),
            )
            SecretTextField(
                value = password,
                onValueChange = { password = it },
                label = "Password",
                placeholder = "Password",
                modifier = Modifier.fillMaxWidth(),
            )
            SubsonicStatusLine(form.status)
            if (!state.canAddProfile) {
                InlineNotice("You've reached the account limit. Remove one in Settings to add another.")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { onTest(serverUrl, username, password) },
                    enabled = filled && !form.isBusy,
                    modifier = Modifier.weight(1f),
                ) { Text("Test") }
                Button(
                    onClick = { onSave(serverUrl, username, password) },
                    enabled = canSubmit,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("subsonic_connect"),
                ) { Text(if (state.isManaging) "Save" else "Connect") }
            }
        }
    }
}

/** Only speaks when there's something to say; animates between states. */
@Composable
private fun SubsonicStatusLine(status: SubsonicStatus) {
    AnimatedContent(
        targetState = status,
        transitionSpec = {
            YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                YoinMotion.fadeOut(role = YoinMotionRole.Standard)
        },
        contentKey = { it::class },
        label = "subsonicStatus",
    ) { current ->
        val color by animateColorAsState(
            targetValue = when (current) {
                is SubsonicStatus.Failed -> MaterialTheme.colorScheme.error
                SubsonicStatus.Reachable -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            animationSpec = YoinMotion.effectsSpring(),
            label = "subsonicStatusColor",
        )
        when (current) {
            SubsonicStatus.Idle -> Spacer(Modifier.height(0.dp))
            SubsonicStatus.Testing, SubsonicStatus.Saving -> Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                YoinLoadingIndicator(size = 24.dp)
                Text(
                    text = if (current == SubsonicStatus.Testing) "Reaching your server…" else "Connecting…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = color,
                )
            }
            SubsonicStatus.Reachable -> StatusRow(Icons.Rounded.Check, "Server found — ready to connect", color)
            is SubsonicStatus.Failed -> StatusRow(Icons.Rounded.ErrorOutline, current.message, color)
        }
    }
}

@Composable
private fun StatusRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
private fun InlineNotice(text: String) {
    Surface(
        shape = YoinShapeTokens.Large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

// ── Spotify ───────────────────────────────────────────────────────────

@Composable
private fun SpotifyConnectGroup(
    state: ServiceSetupUiState,
    focusClientId: Boolean,
    onSaveClientId: (String) -> Unit,
    onConnect: () -> Unit,
) {
    val spotify = state.spotify
    val hasClientId = spotify.clientId.isNotBlank()
    val haptics = rememberYoinHaptics()

    SettingsGroup(title = if (state.isManaging) "Account" else "Connect") {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val message = when {
                !hasClientId -> "Add your Client ID below first."
                spotify.needsReconnect -> "Sign in again to keep using this account."
                spotify.accountIssue != null -> spotify.accountIssue
                state.isManaging -> "Connected. Sign in again to switch Spotify accounts."
                else -> "You'll sign in through Spotify and come right back."
            }
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!state.canAddProfile) {
                InlineNotice("You've reached the account limit. Remove one in Settings to add another.")
            }
            Button(
                onClick = {
                    haptics.performConfirm()
                    onConnect()
                },
                enabled = hasClientId && state.canAddProfile,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("spotify_connect"),
            ) { Text(if (state.isManaging) "Sign in again" else "Continue with Spotify") }
        }
    }

    SpotifyDeveloperGroup(
        clientId = spotify.clientId,
        usesBuildFallback = spotify.usesBuildFallback,
        focusClientId = focusClientId,
        onSaveClientId = onSaveClientId,
    )
}

/**
 * Client ID is a one-time developer chore, not the pitch — tucked into an
 * expandable row that opens by itself only when it's actually blocking.
 */
@Composable
private fun SpotifyDeveloperGroup(
    clientId: String,
    usesBuildFallback: Boolean,
    focusClientId: Boolean,
    onSaveClientId: (String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(focusClientId || clientId.isBlank()) }
    var draft by rememberSaveable(clientId) { mutableStateOf(clientId) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(focusClientId) {
        if (focusClientId) runCatching { focusRequester.requestFocus() }
    }
    SettingsGroup(title = "Developer setup") {
        SettingsExpandableItem(
            icon = Icons.Rounded.Code,
            title = "Client ID",
            summary = when {
                clientId.isBlank() -> "Not set"
                usesBuildFallback -> "Using this build's default"
                else -> "Set"
            },
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            Text(
                text = "Create an app at developer.spotify.com and register these redirect URIs:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectionContainer {
                Text(
                    text = "yoin://auth/spotify/callback\nyoin://auth/spotify/app-remote",
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            ExpressiveTextField(
                value = draft,
                onValueChange = { draft = it },
                label = "Client ID",
                placeholder = "32-character ID",
                modifier = Modifier
                    .testTag("spotify_client_id_field")
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
            Button(
                onClick = { onSaveClientId(draft) },
                enabled = draft.isNotBlank() && draft.trim() != clientId,
            ) { Text("Save") }
        }
    }
}

// ── Previews ─────────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F, heightDp = 1400)
@Composable
private fun ServiceSetupSubsonicPreview() {
    YoinTheme {
        ServiceSetupContent(
            state = ServiceSetupUiState(service = SetupService.Subsonic, isManaging = false),
            onBackClick = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F, heightDp = 1400)
@Composable
private fun ServiceSetupSpotifyPreview() {
    YoinTheme {
        ServiceSetupContent(
            state = ServiceSetupUiState(service = SetupService.Spotify, isManaging = false),
            onBackClick = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun ServiceSetupManageSpotifyPreview() {
    YoinTheme {
        ServiceSetupContent(
            state = ServiceSetupUiState(
                service = SetupService.Spotify,
                isManaging = true,
                existingProfileName = "Chen's Spotify",
                spotify = SpotifySetupState(clientId = "abc", needsReconnect = true),
            ),
            onBackClick = {},
        )
    }
}
