package com.gpo.yoin.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.AppContainer
import com.gpo.yoin.R
import com.gpo.yoin.data.profile.ProfileManager
import com.gpo.yoin.data.profile.ProviderKind
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.navigation.back.DialogWindowPredictiveBack
import com.gpo.yoin.ui.nowplaying.StageBackPreview
import com.gpo.yoin.ui.nowplaying.backPreviewTransform
import com.gpo.yoin.ui.settings.AccountAvatar
import com.gpo.yoin.ui.settings.ProfileCard
import com.gpo.yoin.ui.settings.ServiceLine
import com.gpo.yoin.ui.settings.SettingsItem
import com.gpo.yoin.ui.settings.SettingsSegments
import com.gpo.yoin.ui.settings.monogramOf
import com.gpo.yoin.ui.settings.profileCardsFlow
import com.gpo.yoin.ui.settings.serviceIdentity
import com.gpo.yoin.ui.settings.serviceLineGroups
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/*
 * Home's account switcher (owner 2026-10-09, after the Play Store's account
 * card): the avatar of the account in use replaces Home's Settings gear; tap
 * it and a card opens over Home — the account in use (avatar, name, service,
 * Manage accounts), the other accounts to switch to, Add account, then Yoin's
 * own entries (Settings, Edit Home). Same cards and avatars as Settings
 * (profileCardsFlow / AccountAvatar), so the two never disagree.
 *
 * Back: the card is its own dialog window; back (gesture or button) closes it
 * and nothing else, so Home's back-to-home is untouched.
 */

/** The accounts for Home's switcher. */
class AccountSwitcherViewModel(private val container: AppContainer) : ViewModel() {
    val cards: StateFlow<List<ProfileCard>> = profileCardsFlow(container)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // A switch begun here that fails: Home's feed comes back on its own
    // (HomeViewModel) and Home says so once, in the shell's snackbar.
    private val failures = Channel<Unit>(Channel.CONFLATED)
    val switchFailures: Flow<Unit> = failures.receiveAsFlow()

    fun switchTo(profileId: String) {
        val profiles = container.profileManager
        if (profiles.activeProfileId.value == profileId) return
        viewModelScope.launch {
            if (profiles.switchFromHome(profileId)) failures.trySend(Unit)
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AccountSwitcherViewModel(container) as T
    }
}

/**
 * Switch to [profileId] from Home's switcher; true when the switch failed.
 * The failure is acknowledged here, where Home tells it — otherwise
 * ProfileManager keeps it, and the next visit to Settings would open on its
 * error card for a switch Settings never started.
 */
internal suspend fun ProfileManager.switchFromHome(profileId: String): Boolean {
    switchTo(profileId)
    val outcome = switchingState.value
    if (outcome !is ProfileManager.SwitchState.Error || outcome.profileId != profileId) return false
    acknowledgeSwitchError()
    return true
}

/** The account in use as Home's header button (in place of the Settings gear). */
@Composable
internal fun HomeAccountButton(
    card: ProfileCard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    // While the switcher is open the avatar is up there as the card; this one steps aside.
    hidden: Boolean = false,
    // Where the avatar is, in window px: the switcher grows out of it.
    onAvatarPositioned: (Rect) -> Unit = {},
) {
    val haptics = rememberYoinHaptics()
    val identity = card.provider.serviceIdentity
    val label = stringResource(R.string.home_account_cd, card.title)
    // No ripple: the avatar itself grows into the switcher, which is the feedback
    // (a ripple would also be left pressed under the dialog window).
    Box(
        modifier = modifier
            .size(48.dp)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                haptics.performContextClick()
                onClick()
            }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        AccountAvatar(
            monogram = monogramOf(card.title, stringResource(identity.nameRes)),
            shapeIndex = card.avatarShape,
            identity = identity,
            ringColor = MaterialTheme.colorScheme.surfaceContainer,
            photoUrl = card.photoUrl,
            size = HomeAccountButtonAvatar,
            modifier = Modifier
                .onGloballyPositioned { onAvatarPositioned(it.boundsInWindow()) }
                .graphicsLayer { alpha = if (hidden) 0f else 1f },
        )
    }
}

private val HomeAccountButtonAvatar = 32.dp

/**
 * The account card, centred on the screen, grown out of the header avatar
 * ([anchor], window px): one spatial spring p (0 = the avatar, 1 = the card)
 * drives the card's surface from the avatar's bounds and roundness to its own,
 * the avatar's flight to the card's big avatar, the scrim, and — late — the
 * card's contents. Closing runs the same p back into the avatar; [onDismissed]
 * runs once it is there, so the dialog window leaves after its card.
 */
@Composable
internal fun HomeAccountSwitcherDialog(
    visible: Boolean,
    anchor: Rect?,
    cards: List<ProfileCard>,
    onRequestClose: () -> Unit,
    onDismissed: () -> Unit,
    onSwitch: (ProfileCard) -> Unit,
    onManageAccounts: () -> Unit,
    onAddAccount: () -> Unit,
    onOpenSettings: () -> Unit,
    onEditHome: () -> Unit,
    onCardHome: () -> Unit = {},
) {
    val reduced = LocalMotionProfile.current == MotionProfile.AdaptiveReduced
    val progress = remember { Animatable(0f) }
    val spatial = YoinMotion.defaultSpatialSpec<Float>()
    val effects = YoinMotion.defaultEffectsSpec<Float>()
    // Where things are, in this window's px.
    var origin by remember { mutableStateOf(Offset.Zero) }
    var cardBounds by remember { mutableStateOf(Rect.Zero) }
    var bigAvatarBounds by remember { mutableStateOf(Rect.Zero) }
    val dismissed by rememberUpdatedState(onDismissed)
    val cardHome by rememberUpdatedState(onCardHome)
    LaunchedEffect(visible) {
        if (visible) {
            // The dialog's window takes a few frames to come up: start once the
            // card is laid out and two frames have drawn, or the spring would be
            // nearly done before anything is on screen.
            snapshotFlow { cardBounds }.first { !it.isEmpty }
            withFrameNanos {}
            withFrameNanos {}
        }
        // Reduced motion: no flight — the card fades where it sits.
        // [onCardHome] fires as soon as the card is visibly back in the avatar; the spring's
        // invisible tail still settles before [onDismissed] removes the window.
        var home = false
        progress.animateTo(if (visible) 1f else 0f, if (reduced) effects else spatial) {
            if (!visible && !home && value <= CardHomeProgress) {
                home = true
                cardHome()
            }
        }
        if (!visible) {
            if (!home) cardHome()
            dismissed()
        }
    }
    val active = cards.firstOrNull { it.isActive } ?: cards.firstOrNull()
    val surface = MaterialTheme.colorScheme.surfaceContainer
    val scrim = MaterialTheme.colorScheme.scrim
    val density = LocalDensity.current
    val cardCorner = with(density) { HomeAccountCardCorner.toPx() }
    val flying by remember { derivedStateOf { progress.value < 1f || progress.isRunning } }

    // System back previews the card in the AOSP pose (scale toward 0.9, toward the
    // swipe's edge, following the finger) — snapped per event, the morph is not
    // scrubbed; commit closes it back into the avatar, both ends spring the pose home.
    val backPreview = remember { StageBackPreview() }
    val backScope = rememberCoroutineScope()
    val previewSpec = YoinMotion.defaultSpatialSpec<Float>(role = YoinMotionRole.Standard)
    var backStartY by remember { mutableFloatStateOf(Float.NaN) }
    fun releasePreview() {
        backStartY = Float.NaN
        backScope.launch { backPreview.progress.animateTo(0f, previewSpec) }
    }
    Dialog(
        onDismissRequest = onRequestClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = false,
        ),
    ) {
        DialogWindowPredictiveBack(
            enabled = visible,
            onProgress = { event ->
                if (backStartY.isNaN()) backStartY = event.touchY
                backPreview.swipeEdge = event.swipeEdge
                backPreview.touchYDelta = event.touchY - backStartY
                backScope.launch {
                    backPreview.progress.snapTo(YoinMotion.backGestureEasing.transform(event.progress))
                }
            },
            onCommitted = {
                releasePreview()
                onRequestClose()
            },
            onCancelled = { releasePreview() },
        )
        // The window draws its own scrim (on p) and has no enter/exit animation of its own.
        (LocalView.current.parent as? DialogWindowProvider)?.window?.let { window ->
            SideEffect {
                window.setDimAmount(0f)
                window.setWindowAnimations(0)
            }
        }
        fun from(): Rect {
            val a = anchor?.translate(-origin.x, -origin.y)
            return a ?: Rect(cardBounds.center, cardBounds.center)
        }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { origin = it.positionInWindow() }
                .drawBehind {
                    drawRect(scrim, alpha = HomeAccountScrimAlpha * progress.value.coerceIn(0f, 1f))
                }
                // The window is the whole screen: a tap outside the card closes it.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onRequestClose,
                ),
            contentAlignment = Alignment.Center,
        ) {
            val cardWidth = (maxWidth - 32.dp).coerceAtMost(HomeAccountCardMaxWidth)
            // Centred on the screen, never taller than it (the card scrolls inside).
            val cardMaxHeight = maxHeight - 48.dp
            // Everything the back gesture previews: the surface, the card and the avatar's flight.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .backPreviewTransform(backPreview)
                    .drawBehind {
                        val p = progress.value
                        if (cardBounds.isEmpty) return@drawBehind
                        // The card's surface, from the avatar's bounds to its own.
                        val r = lerpRect(from(), cardBounds, if (reduced) 1f else p)
                        val corner = lerpF(from().width / 2f, cardCorner, if (reduced) 1f else p)
                        drawRoundRect(
                            color = surface,
                            topLeft = r.topLeft,
                            size = r.size,
                            cornerRadius = CornerRadius(corner),
                            alpha = if (reduced) p else 1f,
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                HomeAccountCard(
                    cards = cards,
                    onClose = onRequestClose,
                    onSwitch = onSwitch,
                    onManageAccounts = onManageAccounts,
                    onAddAccount = onAddAccount,
                    onOpenSettings = onOpenSettings,
                    onEditHome = onEditHome,
                    containerColor = Color.Transparent,
                    // The big avatar stays hidden while its twin flies in, and reports where it is.
                    avatarModifier = Modifier
                        .onGloballyPositioned { bigAvatarBounds = it.boundsInWindow().translate(-origin.x, -origin.y) }
                        .graphicsLayer { alpha = if (flying) 0f else 1f },
                    modifier = Modifier
                        .systemBarsPadding()
                        .width(cardWidth)
                        .heightIn(max = cardMaxHeight)
                        .onGloballyPositioned { cardBounds = it.boundsInWindow().translate(-origin.x, -origin.y) }
                        .graphicsLayer {
                            // The contents come in late, inside the growing surface.
                            val p = progress.value
                            alpha = smoothStep(0.45f, 0.95f, p)
                            val r = lerpRect(from(), cardBounds, if (reduced) 1f else p)
                            val corner = lerpF(from().width / 2f, cardCorner, if (reduced) 1f else p)
                            shape = RoundedCornerShape(corner)
                            clip = !reduced && p < 1f
                            if (clip && !cardBounds.isEmpty) {
                                shape = InsetRoundedShape(r.translate(-cardBounds.left, -cardBounds.top), corner)
                            }
                        }
                        // Taps on the card stay on the card.
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ),
                )
                // The avatar's flight, from the header to the big avatar.
                if (flying && active != null && !bigAvatarBounds.isEmpty && !reduced) {
                    val identity = active.provider.serviceIdentity
                    AccountAvatar(
                        monogram = monogramOf(active.title, stringResource(identity.nameRes)),
                        shapeIndex = active.avatarShape,
                        identity = identity,
                        ringColor = surface,
                        photoUrl = active.photoUrl,
                        size = HomeAccountBigAvatar,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .graphicsLayer {
                                val p = progress.value
                                val start = from()
                                val r = lerpRect(start, bigAvatarBounds, p)
                                transformOrigin = TransformOrigin(0f, 0f)
                                translationX = r.left
                                translationY = r.top
                                val scale = r.width / bigAvatarBounds.width
                                scaleX = scale
                                scaleY = scale
                            },
                    )
                }
            }
        }
    }
}

/** A rounded rect at [rect] inside the layer (the clip of a growing card). */
private class InsetRoundedShape(private val rect: Rect, private val corner: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(rect, CornerRadius(corner)))
}

private fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t

private fun lerpRect(a: Rect, b: Rect, t: Float) = Rect(
    lerpF(a.left, b.left, t),
    lerpF(a.top, b.top, t),
    lerpF(a.right, b.right, t),
    lerpF(a.bottom, b.bottom, t),
)

private fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private val HomeAccountCardCorner = 28.dp
private val HomeAccountBigAvatar = 72.dp
private const val HomeAccountScrimAlpha = 0.32f

private val HomeAccountCardMaxWidth = 420.dp

@Composable
private fun HomeAccountCard(
    cards: List<ProfileCard>,
    onClose: () -> Unit,
    onSwitch: (ProfileCard) -> Unit,
    onManageAccounts: () -> Unit,
    onAddAccount: () -> Unit,
    onOpenSettings: () -> Unit,
    onEditHome: () -> Unit,
    modifier: Modifier = Modifier,
    avatarModifier: Modifier = Modifier,
    // The dialog paints the card's surface itself (it grows out of the avatar).
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
) {
    val active = cards.firstOrNull { it.isActive } ?: cards.firstOrNull()
    val others = cards.filter { it !== active }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(HomeAccountCardCorner),
        color = containerColor,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.size(48.dp))
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    active?.let { card -> AccountServiceLine(card) }
                }
                IconButton(onClick = onClose) {
                    Icon(YoinSymbols.Close, contentDescription = stringResource(R.string.home_account_close))
                }
            }
            if (active != null) {
                val identity = active.provider.serviceIdentity
                AccountAvatar(
                    monogram = monogramOf(active.title, stringResource(identity.nameRes)),
                    shapeIndex = active.avatarShape,
                    identity = identity,
                    ringColor = MaterialTheme.colorScheme.surfaceContainer,
                    photoUrl = active.photoUrl,
                    size = HomeAccountBigAvatar,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .then(avatarModifier),
                )
                Text(
                    text = active.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 12.dp, start = 16.dp, end = 16.dp),
                )
            }
            OutlinedButton(
                onClick = onManageAccounts,
                modifier = Modifier.padding(top = 12.dp, bottom = 16.dp),
            ) {
                Text(stringResource(R.string.home_account_manage))
            }
            SettingsSegments {
                others.forEach { card ->
                    item(key = card.id) {
                        AccountRow(card = card, onClick = { onSwitch(card) })
                    }
                }
                item(key = "add") {
                    SettingsItem(
                        icon = YoinSymbols.Add,
                        title = stringResource(R.string.home_account_add),
                        onClick = onAddAccount,
                    )
                }
            }
            Spacer(Modifier.size(12.dp))
            SettingsSegments {
                item(key = "settings") {
                    SettingsItem(
                        icon = YoinSymbols.Settings,
                        title = stringResource(R.string.home_account_settings),
                        onClick = onOpenSettings,
                    )
                }
                item(key = "edit") {
                    SettingsItem(
                        icon = YoinSymbols.Edit,
                        title = stringResource(R.string.home_edit_title),
                        onClick = onEditHome,
                    )
                }
            }
        }
    }
}

/** Another account: avatar, name, service line (or why it can't be used now). */
@Composable
private fun AccountRow(card: ProfileCard, onClick: () -> Unit) {
    val identity = card.provider.serviceIdentity
    val haptics = rememberYoinHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) {
                haptics.performClick()
                onClick()
            }
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AccountAvatar(
            monogram = monogramOf(card.title, stringResource(identity.nameRes)),
            shapeIndex = card.avatarShape,
            identity = identity,
            ringColor = MaterialTheme.colorScheme.surfaceBright,
            photoUrl = card.photoUrl,
            size = 40.dp,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = card.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val reason = card.unavailableReason
            if (reason != null) {
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                )
            } else {
                AccountServiceLine(card)
            }
        }
    }
}

@Composable
private fun AccountServiceLine(card: ProfileCard) {
    val serviceName = stringResource(card.provider.serviceIdentity.nameRes)
    val groups = serviceLineGroups(serviceName, card.title, card.subtitle) ?: return
    ServiceLine(groups = groups)
}

@Preview(showBackground = true, widthDp = 412, heightDp = 760)
@Composable
private fun HomeAccountCardPreview() {
    YoinTheme {
        HomeAccountCard(
            cards = listOf(
                ProfileCard(
                    id = "a",
                    displayName = "qa",
                    subtitle = "music.example.com",
                    provider = ProviderKind.SUBSONIC,
                    isActive = true,
                ),
                ProfileCard(
                    id = "b",
                    displayName = "Spotify",
                    subtitle = null,
                    provider = ProviderKind.SPOTIFY,
                    isActive = false,
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
            ),
            onClose = {},
            onSwitch = {},
            onManageAccounts = {},
            onAddAccount = {},
            onOpenSettings = {},
            onEditHome = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

/** Closing progress at which the card reads as back in the avatar ([HomeAccountSwitcherDialog]'s onCardHome). */
private const val CardHomeProgress = 0.04f
