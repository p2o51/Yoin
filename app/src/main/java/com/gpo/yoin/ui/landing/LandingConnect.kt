package com.gpo.yoin.ui.landing

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.yoin.AppContainer
import com.gpo.yoin.R
import com.gpo.yoin.data.remote.applemusic.YoinTokenService
import com.gpo.yoin.data.source.spotify.SpotifyAuthConfig
import com.gpo.yoin.data.source.spotify.SpotifyOAuthContract
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.common.UiText
import com.gpo.yoin.ui.common.asString
import com.gpo.yoin.ui.component.ExpressiveTextField
import com.gpo.yoin.ui.component.MorphPolygonShape
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.landing.guide.SpotifyGuideActivity
import com.gpo.yoin.ui.settings.SecretTextField
import com.gpo.yoin.ui.settings.applemusic.AppleMusicValidationViewModel
import com.gpo.yoin.ui.settings.provider
import com.gpo.yoin.ui.settings.service.ServiceSetupEvent
import com.gpo.yoin.ui.settings.service.ServiceSetupRequest
import com.gpo.yoin.ui.settings.service.ServiceSetupViewModel
import com.gpo.yoin.ui.settings.service.SetupService
import com.gpo.yoin.ui.settings.service.SubsonicStatus
import com.gpo.yoin.ui.settings.service.intro
import com.gpo.yoin.ui.settings.serviceIdentity
import com.gpo.yoin.ui.settings.tone
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotionSpeed
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// The connect steps reuse the Settings setup ViewModels unchanged (one per
// service, keyed, in the landing's own ViewModel store). Only the faces
// differ: the floating window's tighter layout, and the mascot reacting.

/** What the mascot does about a connect step. */
internal interface LandingReactions {
    fun refused()
    fun connected(service: SetupService)
}

@Composable
private fun rememberSetupViewModel(container: AppContainer, service: SetupService): ServiceSetupViewModel =
    viewModel(
        key = "landing-setup-${service.key}",
        factory = ServiceSetupViewModel.Factory(container, ServiceSetupRequest(service)),
    )

/** The service's face at the top of its step: avatar, name, tagline. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ServiceHeader(service: SetupService) {
    val identity = service.provider.serviceIdentity
    val tone = identity.hue.tone()
    val intro = service.intro
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.padding(bottom = 16.dp),
    ) {
        val shape = remember { MorphPolygonShape(Morph(MaterialShapes.Cookie9Sided, MaterialShapes.Cookie9Sided), 0f) }
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(tone.accent, shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(identity.glyph, contentDescription = null, tint = tone.onAccent, modifier = Modifier.size(26.dp))
        }
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(intro.nameRes),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                intro.badgeRes?.let { badge ->
                    Text(
                        text = stringResource(badge).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(9.dp))
                            .padding(horizontal = 7.dp, vertical = 1.dp),
                    )
                }
            }
            Text(
                text = stringResource(intro.taglineRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The step's main button in the service's colour; spins while busy, settles into "Connected". */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ServiceCta(
    service: SetupService,
    @StringRes label: Int,
    busyLabel: String?,
    done: Boolean,
    onClick: () -> Unit,
) {
    val tone = service.provider.serviceIdentity.hue.tone()
    val focusManager = LocalFocusManager.current
    val container by animateColorAsState(if (done) tone.container else tone.accent, YoinMotion.defaultEffectsSpec(), label = "ctaBg")
    val content by animateColorAsState(if (done) tone.onContainer else tone.onAccent, YoinMotion.defaultEffectsSpec(), label = "ctaFg")
    Button(
        onClick = {
            if (!done && busyLabel == null) {
                // The keyboard goes down so the answer (the mascot, an error line) is in view.
                focusManager.clearFocus()
                onClick()
            }
        },
        shapes = ButtonDefaults.shapes(),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .height(56.dp),
    ) {
        when {
            done -> {
                Icon(YoinSymbols.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.landing_connected), style = MaterialTheme.typography.titleSmall)
            }
            busyLabel != null -> {
                YoinLoadingIndicator(size = 24.dp)
                Spacer(Modifier.width(10.dp))
                Text(busyLabel, style = MaterialTheme.typography.titleSmall)
            }
            else -> Text(stringResource(label), style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun ErrorLine(text: String?) {
    AnimatedVisibility(
        visible = text != null,
        enter = YoinMotion.fadeIn(role = YoinMotionRole.Standard) + expandVertically(YoinMotion.defaultSpatialSpec()),
        exit = YoinMotion.fadeOut(role = YoinMotionRole.Standard, speed = YoinMotionSpeed.Fast) + shrinkVertically(YoinMotion.defaultSpatialSpec()),
    ) {
        Text(
            text = text.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp),
        )
    }
}

/** A short horizontal shake for a refused form. */
private suspend fun Animatable<Float, AnimationVector1D>.shake() {
    snapTo(0f)
    animateTo(0f, YoinMotion.mascotShakeSpring(), initialVelocity = ShakeVelocity)
}

private const val ShakeVelocity = 900f

// ── Subsonic ────────────────────────────────────────────────────────────

/** Connect = reach the server, then save: an unreachable server never becomes an account. */
@Composable
internal fun SubsonicConnectScene(
    container: AppContainer,
    connected: Boolean,
    reactions: LandingReactions,
) {
    val vm = rememberSetupViewModel(container, SetupService.Subsonic)
    val state by vm.uiState.collectAsState()
    val currentReactions by rememberUpdatedState(reactions)
    var url by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var connecting by rememberSaveable { mutableStateOf(false) }
    val shake = remember { Animatable(0f) }
    val status = state.subsonic.status
    LaunchedEffect(status) {
        when {
            status is SubsonicStatus.Reachable && connecting -> vm.saveSubsonicProfile(url, user, password)
            status is SubsonicStatus.Failed -> {
                connecting = false
                currentReactions.refused()
                shake.shake()
            }
        }
    }
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            if (event is ServiceSetupEvent.Done) {
                connecting = false
                currentReactions.connected(SetupService.Subsonic)
            }
        }
    }
    LandingSceneColumn {
        ServiceHeader(SetupService.Subsonic)
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.graphicsLayer { translationX = shake.value },
        ) {
            ExpressiveTextField(
                value = url,
                onValueChange = { url = it },
                label = stringResource(R.string.settings_setup_address_label),
                placeholder = stringResource(R.string.settings_setup_address_placeholder),
            )
            ExpressiveTextField(
                value = user,
                onValueChange = { user = it },
                label = stringResource(R.string.settings_setup_username_label),
                placeholder = stringResource(R.string.settings_setup_username_placeholder),
            )
            SecretTextField(
                value = password,
                onValueChange = { password = it },
                label = stringResource(R.string.settings_setup_password_label),
                placeholder = stringResource(R.string.settings_setup_password_placeholder),
            )
        }
        ErrorLine((status as? SubsonicStatus.Failed)?.message?.asString())
        ServiceCta(
            service = SetupService.Subsonic,
            label = R.string.settings_setup_connect,
            busyLabel = when (status) {
                SubsonicStatus.Testing -> stringResource(R.string.settings_setup_reaching)
                SubsonicStatus.Saving -> stringResource(R.string.settings_setup_connecting)
                else -> null
            },
            done = connected,
            onClick = {
                connecting = true
                vm.testSubsonicConnection(url, user, password)
            },
        )
    }
}

// ── Spotify ─────────────────────────────────────────────────────────────

/**
 * Spotify needs the user's own app on Spotify for Developers: the picture-in-picture guide walks them through
 * it, and coming back here reads the Client ID they copied.
 */
@Composable
internal fun SpotifyConnectScene(
    container: AppContainer,
    connected: Boolean,
    reactions: LandingReactions,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val vm = rememberSetupViewModel(container, SetupService.Spotify)
    val state by vm.uiState.collectAsState()
    val currentReactions by rememberUpdatedState(reactions)
    val scope = rememberCoroutineScope()
    var clientId by rememberSaveable { mutableStateOf("") }
    var pasted by rememberSaveable { mutableStateOf(false) }
    var guideOpened by rememberSaveable { mutableStateOf(false) }
    var waiting by rememberSaveable { mutableStateOf(false) }
    var urisOpen by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<UiText?>(null) }
    val shake = remember { Animatable(0f) }
    val launcher = rememberLauncherForActivityResult(SpotifyOAuthContract()) { result ->
        waiting = false
        vm.commitSpotifyOAuth(result)
    }
    LaunchedEffect(state.spotify.clientId) {
        if (clientId.isBlank() && state.spotify.clientId.isNotBlank() && !state.spotify.usesBuildFallback) {
            clientId = state.spotify.clientId
        }
    }
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is ServiceSetupEvent.LaunchSpotifyOAuth -> launcher.launch(event.targetProfileId)
                is ServiceSetupEvent.ShowError -> {
                    waiting = false
                    error = event.message
                    currentReactions.refused()
                    shake.shake()
                }
                is ServiceSetupEvent.Done -> {
                    waiting = false
                    currentReactions.connected(SetupService.Spotify)
                }
            }
        }
    }
    // Back from the guide: the window regains focus and may read the clipboard (Android 10+ only lets the
    // focused app read it). A 32-hex string is a Spotify Client ID.
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused, guideOpened) {
        if (!focused || !guideOpened || clientId.isNotBlank()) return@LaunchedEffect
        readClientIdFromClipboard(context)?.let {
            clientId = it
            pasted = true
            // Filled for them: no keyboard, the next tap is Continue.
            focusManager.clearFocus()
        }
    }
    LandingSceneColumn {
        ServiceHeader(SetupService.Spotify)
        GuideStep(number = 1, title = R.string.landing_spotify_create) {
            Text(
                text = stringResource(R.string.landing_spotify_create_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(
                onClick = {
                    guideOpened = true
                    focusManager.clearFocus()
                    context.startActivity(SpotifyGuideActivity.intent(context))
                },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Icon(YoinSymbols.Launch, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.landing_spotify_open))
            }
        }
        GuideStep(number = 2, title = R.string.landing_spotify_paste) {
            Box(Modifier.graphicsLayer { translationX = shake.value }) {
                ExpressiveTextField(
                    value = clientId,
                    onValueChange = {
                        clientId = it
                        pasted = false
                        error = null
                    },
                    label = stringResource(R.string.settings_setup_client_id_field),
                    placeholder = stringResource(R.string.settings_setup_client_placeholder),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            AnimatedVisibility(visible = pasted) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(start = 8.dp, top = 6.dp),
                ) {
                    Icon(YoinSymbols.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Text(
                        text = stringResource(R.string.landing_spotify_pasted),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        RedirectUris(open = urisOpen, onToggle = { urisOpen = !urisOpen })
        ErrorLine(error?.asString())
        ServiceCta(
            service = SetupService.Spotify,
            label = R.string.settings_setup_spotify_continue,
            busyLabel = if (waiting) stringResource(R.string.settings_setup_connecting) else null,
            done = connected,
            onClick = {
                val id = clientId.trim()
                if (id.isBlank()) {
                    error = UiText.Res(R.string.settings_setup_spotify_need_client_error)
                    currentReactions.refused()
                    scope.launch { shake.shake() }
                    return@ServiceCta
                }
                error = null
                waiting = true
                vm.saveSpotifyClientId(id)
                scope.launch {
                    // connectSpotify reads the effective id; wait for the write to land.
                    withTimeoutOrNull(ClientIdSettleMs) { container.spotifyClientIdFlow.first { it == id } }
                    vm.connectSpotify()
                }
            },
        )
    }
}

private const val ClientIdSettleMs = 3_000L

private val ClientIdPattern = Regex("[0-9a-fA-F]{32}")

private fun readClientIdFromClipboard(context: Context): String? {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
    val text = runCatching { clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull() ?: return null
    return ClientIdPattern.find(text.trim())?.value?.takeIf { it.length == text.trim().length }
}

@Composable
private fun GuideStep(number: Int, @StringRes title: Int, content: @Composable () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 2.dp, bottom = 2.dp),
            )
            content()
        }
    }
}

/** The two redirect URIs, folded away: the guide copies them; this is for setting up from a computer. */
@Composable
private fun RedirectUris(open: Boolean, onToggle: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceBright,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onToggle)
                    .padding(horizontal = 14.dp)
                    .height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.landing_spotify_uris),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = YoinSymbols.ChevronDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.graphicsLayer { rotationZ = if (open) 180f else 0f },
                )
            }
            AnimatedVisibility(
                visible = open,
                enter = YoinMotion.fadeIn(role = YoinMotionRole.Standard) + expandVertically(YoinMotion.defaultSpatialSpec()),
                exit = YoinMotion.fadeOut(role = YoinMotionRole.Standard, speed = YoinMotionSpeed.Fast) + shrinkVertically(YoinMotion.defaultSpatialSpec()),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                ) {
                    CopyRow(SpotifyAuthConfig.REDIRECT_URI)
                    CopyRow(SpotifyAuthConfig.APP_REMOTE_REDIRECT_URI)
                }
            }
        }
    }
}

@Composable
internal fun CopyRow(text: String) {
    val context = LocalContext.current
    val haptics = rememberYoinHaptics()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(CopiedHoldMs)
            copied = false
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
    ) {
        SelectionContainer(modifier = Modifier.weight(1f)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
        }
        FilledTonalButton(
            onClick = {
                copyToClipboard(context, text)
                haptics.performConfirm()
                copied = true
            },
            contentPadding = PaddingValues(horizontal = 12.dp),
            modifier = Modifier.height(32.dp),
        ) {
            Icon(if (copied) YoinSymbols.Check else YoinSymbols.Copy, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(if (copied) R.string.landing_copied else R.string.landing_copy), style = MaterialTheme.typography.labelLarge)
        }
    }
}

private const val CopiedHoldMs = 1_800L

internal fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Yoin", text))
}

// ── Apple Music ─────────────────────────────────────────────────────────

/**
 * Apple Music needs a developer token service. Yoin's own is the default and is never shown or copied; a
 * user with an Apple Developer Program membership can point at their own.
 */
@Composable
internal fun AppleConnectScene(
    connected: Boolean,
    reactions: LandingReactions,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val vm: AppleMusicValidationViewModel = viewModel(
        key = "landing-apple",
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application),
    )
    val state by vm.state.collectAsState()
    val currentReactions by rememberUpdatedState(reactions)
    var ownService by rememberSaveable { mutableStateOf(false) }
    var ownUrl by rememberSaveable { mutableStateOf("") }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val shake = remember { Animatable(0f) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.authorizationResult(it.data)
    }
    LaunchedEffect(vm) { vm.initialize(null) }
    LaunchedEffect(vm) { vm.authorization.collect { launcher.launch(it) } }
    LaunchedEffect(vm) {
        vm.saved.collect {
            attempted = false
            currentReactions.connected(SetupService.AppleMusic)
        }
    }
    val failed = attempted && !state.busy && !state.connected && state.retryable
    LaunchedEffect(failed) {
        if (failed) {
            currentReactions.refused()
            shake.shake()
        }
    }
    LandingSceneColumn {
        ServiceHeader(SetupService.AppleMusic)
        LandingLabel(R.string.landing_apple_token)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            TokenOption(
                title = R.string.landing_apple_yoin_title,
                body = R.string.landing_apple_yoin_body,
                selected = !ownService,
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 4.dp, bottomEnd = 4.dp),
                onClick = { ownService = false },
            )
            TokenOption(
                title = R.string.landing_apple_own_title,
                body = R.string.landing_apple_own_body,
                selected = ownService,
                shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = if (ownService) 4.dp else 20.dp, bottomEnd = if (ownService) 4.dp else 20.dp),
                onClick = { ownService = true },
            )
            AnimatedVisibility(
                visible = ownService,
                enter = YoinMotion.fadeIn(role = YoinMotionRole.Standard) + expandVertically(YoinMotion.defaultSpatialSpec()),
                exit = YoinMotion.fadeOut(role = YoinMotionRole.Standard, speed = YoinMotionSpeed.Fast) + shrinkVertically(YoinMotion.defaultSpatialSpec()),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 20.dp, bottomEnd = 20.dp))
                        .background(MaterialTheme.colorScheme.surfaceBright)
                        .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp)
                        .graphicsLayer { translationX = shake.value },
                ) {
                    ExpressiveTextField(
                        value = ownUrl,
                        onValueChange = { ownUrl = it },
                        label = stringResource(R.string.settings_apple_endpoint_label),
                        placeholder = stringResource(R.string.settings_apple_endpoint_placeholder),
                    )
                    Text(
                        text = "developer.apple.com/programs ↗",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(start = 6.dp, top = 8.dp)
                            .clickable(role = Role.Button) { uriHandler.openUri(AppleDeveloperProgramUrl) },
                    )
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(start = 6.dp, top = 12.dp),
        ) {
            Icon(YoinSymbols.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Text(
                text = stringResource(R.string.landing_apple_need),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ErrorLine(if (failed) state.status.asString() else null)
        ServiceCta(
            service = SetupService.AppleMusic,
            label = R.string.settings_apple_connect,
            busyLabel = if (state.busy && attempted) stringResource(R.string.settings_apple_working) else null,
            done = connected,
            onClick = {
                val endpoint = if (ownService) ownUrl.trim() else YoinTokenService.URL
                if (!endpoint.startsWith("https://")) {
                    currentReactions.refused()
                    attempted = false
                    return@ServiceCta
                }
                attempted = true
                vm.connect(endpoint)
            },
        )
    }
}

private const val AppleDeveloperProgramUrl = "https://developer.apple.com/programs/"

@Composable
private fun TokenOption(
    @StringRes title: Int,
    @StringRes body: Int,
    selected: Boolean,
    shape: androidx.compose.ui.graphics.Shape,
    onClick: () -> Unit,
) {
    val haptics = rememberYoinHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceBright)
            .clickable(role = Role.RadioButton) {
                if (!selected) haptics.performContextClick()
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
