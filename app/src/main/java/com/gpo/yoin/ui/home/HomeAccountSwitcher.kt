package com.gpo.yoin.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
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
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.navigation.back.DialogWindowPredictiveBack
import com.gpo.yoin.ui.nowplaying.StageBackPreview
import com.gpo.yoin.ui.nowplaying.backPreviewTransform
import com.gpo.yoin.ui.settings.AccountAvatar
import com.gpo.yoin.ui.settings.LocalSettingsRowColor
import com.gpo.yoin.ui.settings.ProfileCard
import com.gpo.yoin.ui.settings.SegmentSelection
import com.gpo.yoin.ui.settings.SelectableSegment
import com.gpo.yoin.ui.settings.ServiceLine
import com.gpo.yoin.ui.settings.SettingsItem
import com.gpo.yoin.ui.settings.SettingsSegments
import com.gpo.yoin.ui.settings.accountIssueLabel
import com.gpo.yoin.ui.settings.face
import com.gpo.yoin.ui.settings.monogramOf
import com.gpo.yoin.ui.settings.profileCardsFlow
import com.gpo.yoin.ui.settings.service.ServiceSetupContract
import com.gpo.yoin.ui.settings.service.ServiceSetupRequest
import com.gpo.yoin.ui.settings.service.SetupService
import com.gpo.yoin.ui.settings.serviceIdentity
import com.gpo.yoin.ui.settings.serviceLineGroups
import com.gpo.yoin.ui.settings.tone
import com.gpo.yoin.ui.theme.LocalYoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinTheme
import com.gpo.yoin.ui.theme.withPacedFrames
import kotlin.math.roundToInt
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
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
class AccountSwitcherViewModel internal constructor(
    private val profiles: ProfileManager,
    profileCards: Flow<List<ProfileCard>>,
    // ProfileManager.activeNeedsReconnect: the one "needs reconnect" signal.
    activeNeedsReconnect: Flow<Boolean>
) : ViewModel() {
    val cards: StateFlow<List<ProfileCard>> = profileCards
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The account in use needs its owner (owner 2026-10-11): its credentials
     * couldn't be read, or Spotify revoked its grant. Home's header avatar
     * wears a dot and the card offers Reconnect. Local state only: nothing
     * polls, nothing asks a server.
     */
    val needsReconnect: StateFlow<Boolean> = activeNeedsReconnect
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // A Reconnect under way: from the tap until ProfileManager's one more try
    // at the account is over. The card waits meanwhile, as through a switch.
    private val retrying = MutableStateFlow(false)
    val reconnecting: StateFlow<Boolean> = retrying.asStateFlow()

    // How each Reconnect begun here ended, for the card to answer.
    private val outcomes = Channel<ReconnectOutcome>(Channel.CONFLATED)
    internal val reconnectOutcomes: Flow<ReconnectOutcome> = outcomes.receiveAsFlow()

    /**
     * Reconnect the account in use: one more try at its source, without
     * switching (a local read and a ping). Fixed: the dot goes, Home's feed
     * loads as it does when a source comes in, and [reconnectOutcomes] says
     * [ReconnectOutcome.Fixed]. Not fixed, and the account still in use: it
     * gets [ReconnectOutcome.OpenPage]. False when nothing starts: a
     * Reconnect or a switch begun here is under way, or there is no account.
     */
    fun reconnect(): Boolean {
        if (retrying.value || pending.value != null) return false
        val account = cards.value.firstOrNull { it.isActive } ?: return false
        retrying.value = true
        viewModelScope.launch {
            try {
                val fixed = profiles.retryActiveSource()
                // Another account in use by now (switched in Settings): not this one's page.
                val stillInUse = profiles.activeProfileId.value.let { it == null || it == account.id }
                when {
                    fixed -> outcomes.trySend(ReconnectOutcome.Fixed)
                    stillInUse -> outcomes.trySend(ReconnectOutcome.OpenPage(account))
                }
            } finally {
                retrying.value = false
            }
        }
        return true
    }

    // A switch begun here that doesn't land — failed, or turned away (another
    // switch under way, the account gone): Home's feed comes back on its own
    // (HomeViewModel) and Home says so once, in the shell's snackbar.
    private val failures = Channel<Unit>(Channel.CONFLATED)
    val switchFailures: Flow<Unit> = failures.receiveAsFlow()

    // The account a switch begun here is going to, from the tap until the
    // switch is over: landed (once the cards have it in use, so Home's header
    // never blinks back to the old account), failed or turned away. Home's
    // header shows it from the tap; the card's other rows wait meanwhile.
    private val pending = MutableStateFlow<String?>(null)
    val pendingTarget: StateFlow<String?> = pending.asStateFlow()

    /**
     * Switch to [profileId]. False when nothing starts: it is the account in
     * use, or a switch begun here is still under way.
     */
    fun switchTo(profileId: String): Boolean {
        if (pending.value != null || retrying.value || profiles.activeProfileId.value == profileId) return false
        // Set before anything suspends: the tap's own frame shows the target.
        pending.value = profileId
        viewModelScope.launch {
            try {
                when (profiles.switchFromHome(profileId)) {
                    HomeSwitchOutcome.Failed, HomeSwitchOutcome.TurnedAway -> failures.trySend(Unit)
                    // Landed: the cards follow a beat later (they're combined
                    // off the main thread); hold until they have it in use.
                    HomeSwitchOutcome.Landed -> cards.first { list ->
                        list.firstOrNull { it.isActive }?.id == profileId ||
                            profiles.activeProfileId.value != profileId
                    }
                }
            } finally {
                pending.compareAndSet(profileId, null)
            }
        }
        return true
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val profiles = container.profileManager
            return AccountSwitcherViewModel(profiles, profileCardsFlow(container), profiles.activeNeedsReconnect) as T
        }
    }
}

/** How a Reconnect begun from Home's switcher ended ([AccountSwitcherViewModel.reconnect]). */
internal sealed interface ReconnectOutcome {
    /** The account works again: the card goes back into the avatar, which wears no dot now. */
    data object Fixed : ReconnectOutcome

    /** Not fixed, and still the account in use: once the card is back in the avatar, [account]'s own page. */
    data class OpenPage(val account: ProfileCard) : ReconnectOutcome
}

/** How a switch begun from Home's switcher ended ([switchFromHome]). */
internal enum class HomeSwitchOutcome {
    /** The account is the one in use. */
    Landed,

    /** It ran and failed (acknowledged: Home tells it). */
    Failed,

    /** ProfileManager didn't run it: another switch was under way, or the account is gone. */
    TurnedAway
}

/**
 * Switch to [profileId] from Home's switcher. A failure is acknowledged here,
 * where Home tells it — otherwise ProfileManager keeps it, and the next visit
 * to Settings would open on its error card for a switch Settings never
 * started. A switch that is turned away leaves ProfileManager's state alone
 * (another switch's error is Settings' to tell); turned away by a switch to
 * the same account begun elsewhere, it waits that one out.
 */
internal suspend fun ProfileManager.switchFromHome(profileId: String): HomeSwitchOutcome {
    switchTo(profileId)
    val outcome = switchingState.value
    if (outcome is ProfileManager.SwitchState.Error && outcome.profileId == profileId) {
        acknowledgeSwitchError()
        return HomeSwitchOutcome.Failed
    }
    if (outcome is ProfileManager.SwitchState.Switching && outcome.profileId == profileId) {
        switchingState.first { it !is ProfileManager.SwitchState.Switching }
    }
    return if (activeProfileId.value == profileId) HomeSwitchOutcome.Landed else HomeSwitchOutcome.TurnedAway
}

/** What Home's header gets from its account switcher ([HomeAccountSwitcherHost]). */
@Immutable
internal data class HomeAccountChrome(
    /** The account the header shows: a switch's target from the tap on, else the one in use (null = none yet). */
    val account: ProfileCard?,
    /** Opens the switcher's card, out of the header avatar. */
    val onOpen: () -> Unit,
    /**
     * The switcher's window has the header's avatar: up there, as the card or
     * its flight. From the window's first frames (its twin of the avatar on
     * the header's own) until it hands the avatar back, two frames before the
     * window goes — never while neither shows it.
     */
    val windowUp: Boolean,
    /** The header reports where its avatar is (window px)… */
    val onAnchor: (Rect) -> Unit,
    /**
     * …and this is where it was last. Read where it is used (a snapshotFlow,
     * layout, draw), never in composition: the header reports it every frame
     * it moves (a scroll, a pull), and a pull frame must not recompose Home.
     */
    val anchor: () -> Rect?,
    /** A switch begun from the card is under way. */
    val switchPending: Boolean,
    /** The window's last close flew an avatar into the header (false: reduced motion, the card faded where it sat). */
    val landed: Boolean,
    /** The account the header shows needs its owner: its avatar wears the dot. */
    val needsReconnect: Boolean = false
)

/**
 * Home's account switcher as Home hosts it: the card's window over Home and
 * the pick it starts — the switch's target pends from the tap
 * ([AccountSwitcherViewModel.pendingTarget]) and the card closes from the
 * picked row into the header — and what the header shows meanwhile (the
 * [HomeAccountChrome] [content] gets). A trip to Settings (Manage accounts,
 * Add account, Settings, an account that can't be used now) waits until the
 * card is back in the avatar. Null [accountSwitcher]: no card, and the header
 * keeps its Settings gear.
 *
 * An account in use that needs its owner ([AccountSwitcherViewModel.needsReconnect])
 * wears a dot in the header and gets Reconnect on the card; the card goes
 * back into the avatar when a Reconnect ends — then, if the retry couldn't
 * fix it, the account's own page opens ([onOpenAccountPage]).
 */
@Suppress("ktlint:standard:function-naming") // a Composable
@Composable
internal fun HomeAccountSwitcherHost(
    accountSwitcher: AccountSwitcherViewModel?,
    onNavigateToSettings: () -> Unit,
    onEditHome: () -> Unit,
    onOpenAccountPage: (ProfileCard) -> Unit = rememberAccountPageOpener(),
    content: @Composable (HomeAccountChrome) -> Unit
) {
    val accountCards by (accountSwitcher?.cards ?: remember { MutableStateFlow(emptyList()) }).collectAsState()
    val pendingTarget by (accountSwitcher?.pendingTarget ?: remember { MutableStateFlow<String?>(null) })
        .collectAsState()
    val needsReconnect by (accountSwitcher?.needsReconnect ?: remember { MutableStateFlow(false) }).collectAsState()
    val reconnecting by (accountSwitcher?.reconnecting ?: remember { MutableStateFlow(false) }).collectAsState()
    val activeAccount = accountCards.firstOrNull { it.isActive }
    // The account the header shows: a switch's target from the tap on (the
    // pick shows at once), the one in use again if the switch fails.
    val displayAccount = pendingTarget?.let { id -> accountCards.firstOrNull { it.id == id } } ?: activeAccount
    // Shown = the dialog window exists; open = its card is in (it animates out before the window goes).
    var switcherShown by rememberSaveable { mutableStateOf(false) }
    var switcherOpen by rememberSaveable { mutableStateOf(false) }
    // The window has the header's avatar ([HomeAccountChrome.windowUp]): a
    // beat after it is shown, until a beat before it goes.
    var avatarUp by remember { mutableStateOf(false) }
    var accountAnchor by remember { mutableStateOf<Rect?>(null) }
    // A switch closes the card from the picked row's avatar (window px).
    var switchFrom by remember { mutableStateOf<Rect?>(null) }
    var landed by remember { mutableStateOf(true) }
    // A trip to Settings waits until the card is back in the avatar: launched at once, Settings covers the shell
    // mid-spring, the stopped window's frame clock pauses, and the half-closed card greets you on the way back.
    // (Only the spring's invisible tail may be left then.)
    var afterSwitcherDismissed by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun closeSwitcherThen(action: () -> Unit) {
        afterSwitcherDismissed = action
        switcherOpen = false
    }
    // A Reconnect that fixed the account: the card goes back into the
    // avatar (T7's spring), and Home under it shows the dot gone and the
    // feed coming in. One the retry couldn't fix: the account's own page,
    // once the card is back in the avatar. A card closed meanwhile (back, a
    // tap outside) leaves it at that: the dot, if still needed, is there to
    // come back by.
    val openAccountPage by rememberUpdatedState(onOpenAccountPage)
    LaunchedEffect(accountSwitcher) {
        accountSwitcher?.reconnectOutcomes?.collect { outcome ->
            if (!switcherOpen) return@collect
            when (outcome) {
                ReconnectOutcome.Fixed -> switcherOpen = false
                is ReconnectOutcome.OpenPage -> closeSwitcherThen { openAccountPage(outcome.account) }
            }
        }
    }
    if (switcherShown && accountSwitcher != null) {
        HomeAccountSwitcherDialog(
            visible = switcherOpen,
            anchor = accountAnchor,
            cards = accountCards,
            onRequestClose = { switcherOpen = false },
            onCardHome = {
                afterSwitcherDismissed?.let { action ->
                    afterSwitcherDismissed = null
                    action()
                }
            },
            onAvatarTaken = { avatarUp = true },
            onAvatarHandedBack = { landedInHeader ->
                landed = landedInHeader
                avatarUp = false
            },
            onDismissed = {
                avatarUp = false
                switcherShown = false
                switchFrom = null
            },
            onSwitch = { card, avatar ->
                // An account that can't be used right now is recovered in Settings.
                if (card.unavailableReason != null) {
                    closeSwitcherThen(onNavigateToSettings)
                } else {
                    // Same frame: the switch's target is pending (the header
                    // shows it) and the card hands its avatar up from the row.
                    if (accountSwitcher.switchTo(card.id)) switchFrom = avatar
                    switcherOpen = false
                }
            },
            pendingTarget = pendingTarget,
            closeFrom = switchFrom,
            closeInto = switchFrom?.let { displayAccount },
            onManageAccounts = { closeSwitcherThen(onNavigateToSettings) },
            onAddAccount = { closeSwitcherThen(onNavigateToSettings) },
            onOpenSettings = { closeSwitcherThen(onNavigateToSettings) },
            onEditHome = {
                // Not while a switch runs: the feed edit mode would freeze is on its way out.
                if (pendingTarget == null) {
                    switcherOpen = false
                    onEditHome()
                }
            },
            needsReconnect = needsReconnect,
            reconnecting = reconnecting,
            onReconnect = { accountSwitcher.reconnect() }
        )
    }
    val onOpen = remember {
        {
            switchFrom = null
            switcherShown = true
            switcherOpen = true
        }
    }
    val onAnchor = remember { { rect: Rect -> accountAnchor = rect } }
    val anchor = remember { { accountAnchor } }
    content(
        HomeAccountChrome(
            account = displayAccount,
            onOpen = onOpen,
            windowUp = switcherShown && avatarUp,
            onAnchor = onAnchor,
            anchor = anchor,
            switchPending = pendingTarget != null,
            landed = landed,
            // Through a switch the header shows its target, not the account in use.
            needsReconnect = needsReconnect && pendingTarget == null
        )
    )
}

/**
 * Opens an account's own page — the setup page in manage mode, where its
 * credentials are entered again (Subsonic) or its sign-in run again (Spotify's
 * reconnect, Apple Music's) — over Home: its back comes straight back here.
 */
@Composable
private fun rememberAccountPageOpener(): (ProfileCard) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { account ->
            SetupService.forProvider(account.provider)?.let { service ->
                val request = ServiceSetupRequest(service, profileId = account.id, face = account.face)
                context.startActivity(ServiceSetupContract().createIntent(context, request))
            }
        }
    }
}

/** The account in use as Home's header button (in place of the Settings gear). */
@Composable
internal fun HomeAccountButton(
    card: ProfileCard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    // While the switcher is open the avatar is up there as the card, and while a
    // switch from Home runs it is Home's stand-in ([HomeAccountAvatarHold]):
    // this one steps aside, and waits.
    hidden: Boolean = false,
    // Where the avatar is, in window px: the switcher grows out of it.
    onAvatarPositioned: (Rect) -> Unit = {},
    // The account needs its owner: a dot on the avatar's corner.
    needsReconnect: Boolean = false,
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
                enabled = enabled && !hidden,
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
        Box {
            AccountAvatar(
                monogram = monogramOf(card.title, stringResource(identity.nameRes)),
                shapeIndex = card.avatarShape,
                identity = identity,
                ringColor = MaterialTheme.colorScheme.surfaceContainer,
                photoUrl = card.photoUrl,
                size = HomeAccountButtonAvatar,
                modifier = Modifier
                    .onGloballyPositioned { onAvatarPositioned(it.unclippedBoundsInWindow()) }
                    .graphicsLayer { alpha = if (hidden) 0f else 1f },
            )
            // Hidden with the avatar, in the same frame, while the card or a
            // switch's stand-in has it — not left behind springing away on its
            // own — and back with it, as it is, once the avatar is home. Only
            // the account's own change springs it in or out.
            if (!hidden) {
                HomeAccountReconnectDot(
                    visible = needsReconnect,
                    ringColor = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.align(Alignment.TopEnd)
                )
            }
        }
    }
}

/**
 * The account needs its owner (owner 2026-10-11): M3's small badge — 6dp, in
 * the error colour — on a ring of the header's colour, at the avatar's
 * top-end corner. No text. Comes and goes with scale on the spatial spring
 * and alpha on the effects spring; TalkBack hears it with the avatar. It
 * starts as [visible] says (no entrance when it comes back with the avatar).
 */
@Suppress("ktlint:standard:function-naming") // a Composable
@Composable
private fun HomeAccountReconnectDot(visible: Boolean, ringColor: Color, modifier: Modifier = Modifier) {
    val role = LocalYoinMotionRole.current
    val label = stringResource(R.string.home_account_reconnect_needed)
    val shown = remember { MutableTransitionState(visible) }
    shown.targetState = visible
    AnimatedVisibility(
        visibleState = shown,
        modifier = modifier,
        enter = YoinMotion.fadeIn(role) + YoinMotion.scaleIn(role, initialScale = 0f),
        exit = YoinMotion.fadeOut(role) + YoinMotion.scaleOut(role, targetScale = 0f)
    ) {
        Box(
            modifier = Modifier
                .testTag(HOME_ACCOUNT_RECONNECT_DOT_TAG)
                .semantics { contentDescription = label }
                .background(ringColor, CircleShape)
                .padding(HomeAccountDotRing)
        ) {
            Badge(containerColor = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * Where this layout draws, in window px — not cut down to what its parents
 * show: a header half scrolled under the status bar is still centred where it
 * is, and that is where the avatars land.
 */
internal fun LayoutCoordinates.unclippedBoundsInWindow(): Rect {
    val root = findRootCoordinates()
    val bounds = root.localBoundingBoxOf(this, clipBounds = false)
    return Rect(root.localToWindow(bounds.topLeft), root.localToWindow(bounds.bottomRight))
}

private val HomeAccountButtonAvatar = 32.dp
private val HomeAccountRowAvatar = 40.dp

// The reconnect dot's ring: M3's 6dp small badge, cut out of the avatar by 1dp of the header.
private val HomeAccountDotRing = 1.dp

// T7's loading indicator, sized into the Reconnect button's label.
private val HomeAccountReconnectIndicator = 24.dp

/**
 * Home's header avatar through a switch begun from Home: from the tap until
 * the page is back under it, [HomeAccountAvatarHold] stands in for the
 * header's own avatar (which the feed takes away while it goes to Loading).
 * One hold per switch ([begin]); Home ends it ([handBack], [fadeAway],
 * [release]).
 */
@Stable
internal class HomeAccountHold {
    /** From a switch's start until Home releases it. */
    var latched by mutableStateOf(false)
        private set

    /** Bumped as each switch begins: the stand-in starts over, placed afresh and opaque. */
    var generation by mutableIntStateOf(0)
        private set

    /**
     * The stand-in lost its spot: the window changed size while no header
     * was there to measure. It fades, and the header's own avatar comes back
     * with the feed instead of a stand-in parked where the header used to be.
     */
    var abandoned by mutableStateOf(false)
        internal set

    /** Handing back: the header's own avatar is up, under the stand-in, which fades off it. */
    var handingBack by mutableStateOf(false)
        private set

    // The stand-in's centre and where it is headed (the header avatar's), in its box's px.
    internal val center = Animatable(Offset.Zero, Offset.VectorConverter)
    internal var target by mutableStateOf<Offset?>(null)
    internal var swapIdle by mutableStateOf(true)

    // Its fade at the end (Home's, on the effects spring) and on losing its spot (its own).
    internal val fade = Animatable(1f)
    internal val lostSpot = Animatable(1f)

    /**
     * The header's own avatar needn't step aside: it is coming back under the
     * stand-in ([handingBack]), or the stand-in has given up its spot.
     */
    val headerAvatarBack: Boolean
        get() = abandoned || handingBack

    /** On the header avatar's spot, showing one account (no swap under way). */
    val atRest: Boolean
        get() = target.let { it != null && !center.isRunning && center.value == it } && swapIdle

    /** A switch begins: hold from here. */
    fun begin() {
        generation++
        latched = true
        abandoned = false
        handingBack = false
    }

    /**
     * Over, on a feed: the header's own avatar comes up under the stand-in —
     * the same account in the same spot — and the stand-in fades off it, so
     * whatever the header's avatar wears there (the top seam's fade, a clip)
     * comes in on the effects spring, not in one frame.
     */
    suspend fun handBack(spec: AnimationSpec<Float>) {
        handingBack = true
        fade.animateTo(0f, spec)
    }

    /** Over, with no header to hand back to (an error page): the stand-in fades. */
    suspend fun fadeAway(spec: AnimationSpec<Float>) {
        fade.animateTo(0f, spec)
    }

    /** The page is Home's own again. */
    fun release() {
        latched = false
        abandoned = false
        handingBack = false
    }
}

/**
 * The stand-in for Home's header avatar while [holding]: [card] (the switch's
 * target, the account in use again if it failed) at the header avatar's spot
 * ([anchor], window px). It takes over from the switch's flight where that
 * lands ([shown] = the switcher's window has gone; [landed] = an avatar flew
 * into the header, otherwise — reduced motion, the card faded where it sat —
 * it fades in on the effects spring), follows the spot on the spatial spring
 * if the next feed puts it elsewhere (1:1 once it is handing back: the header
 * moves under a finger), and gives way to another account on the flight's own
 * swap. With no header on the page ([anchorLive] false) a change in its size
 * means [anchor] is stale (rotated, unfolded, resized): it fades rather than
 * hold a spot the header won't come back to. Not a button: the header's own
 * avatar (under it, waiting) carries the semantics.
 */
@Suppress("ktlint:standard:function-naming") // a Composable
@Composable
internal fun HomeAccountAvatarHold(
    hold: HomeAccountHold,
    holding: Boolean,
    card: ProfileCard?,
    anchor: () -> Rect?,
    anchorLive: Boolean,
    shown: Boolean,
    landed: Boolean,
    modifier: Modifier = Modifier
) {
    if (!holding || card == null) return
    var origin by remember { mutableStateOf<Offset?>(null) }
    var boxSize by remember { mutableStateOf<IntSize?>(null) }
    val currentAnchor by rememberUpdatedState(anchor)
    val currentLive by rememberUpdatedState(anchorLive)
    // Standard role, as the flight that hands it over.
    val spatial = YoinMotion.defaultSpatialSpec<Offset>(role = YoinMotionRole.Standard)
    val effects = YoinMotion.defaultEffectsSpec<Float>(role = YoinMotionRole.Standard)
    val swap = rememberAccountAvatarSwap(card)
    LaunchedEffect(hold, hold.generation) {
        var placed = false
        try {
            snapshotFlow {
                val a = currentAnchor()
                val o = origin
                if (a == null || o == null) null else a.center - o
            }.filterNotNull().collectLatest { spot ->
                hold.target = spot
                if (placed && !hold.handingBack) {
                    hold.center.animateTo(spot, spatial)
                } else {
                    hold.center.snapTo(spot)
                    placed = true
                }
            }
        } finally {
            // The next hold starts from its own spot, not this one's.
            hold.target = null
        }
    }
    LaunchedEffect(hold, hold.generation) {
        // The size [anchor] was last measured at (the header on the page).
        var measuredAt: IntSize? = null
        snapshotFlow { boxSize to currentLive }.collect { (now, live) ->
            if (now == null) return@collect
            if (live || measuredAt == null) {
                measuredAt = now
            } else if (now != measuredAt) {
                hold.abandoned = true
            }
        }
    }
    LaunchedEffect(hold, hold.abandoned) {
        if (hold.abandoned) hold.lostSpot.animateTo(0f, effects) else hold.lostSpot.snapTo(1f)
    }
    LaunchedEffect(hold, swap) {
        snapshotFlow { swap.idle }.collect { hold.swapIdle = it }
    }
    // Comes in where nothing flew to it: the switcher's card faded where it sat.
    val fadeIn = remember { Animatable(0f) }
    LaunchedEffect(hold, shown, landed) {
        if (shown && !landed) fadeIn.animateTo(1f, effects) else fadeIn.snapTo(0f)
    }
    val half = with(LocalDensity.current) { HomeAccountButtonAvatar.toPx() } / 2f
    Box(
        modifier = modifier.onGloballyPositioned {
            origin = it.positionInWindow()
            boxSize = it.size
        },
        // Window px: placed the same way whatever the layout direction.
        contentAlignment = AbsoluteAlignment.TopLeft
    ) {
        if (hold.target != null) {
            HomeAccountAvatarSwap(
                card = card,
                size = HomeAccountButtonAvatar,
                ringColor = MaterialTheme.colorScheme.surfaceContainer,
                swap = swap,
                modifier = Modifier
                    // Placed (not just drawn) on the spot, read per frame in placement.
                    .absoluteOffset {
                        val c = hold.center.value
                        IntOffset((c.x - half).roundToInt(), (c.y - half).roundToInt())
                    }
                    .graphicsLayer {
                        // Up in the frame the window goes (where a flight landed),
                        // or fading in from it (where none did).
                        val entered = when {
                            !shown -> 0f
                            landed -> 1f
                            else -> fadeIn.value
                        }
                        alpha = entered * hold.fade.value * hold.lostSpot.value
                    }
                    .semantics { homeAccountHoldUp = shown && !hold.abandoned }
                    .testTag(HOME_ACCOUNT_HOLD_TAG)
            )
        }
    }
}

/**
 * [card]'s avatar; another account takes its place on a crossfade (effects)
 * with a little give (spatial), on paced frames ([AccountAvatarSwap]). Standard
 * role wherever it is drawn, so the switch's flight (the dialog's window) and
 * Home's stand-in give way in step.
 */
@Suppress("ktlint:standard:function-naming") // a Composable
@Composable
private fun HomeAccountAvatarSwap(
    card: ProfileCard,
    size: Dp,
    ringColor: Color,
    modifier: Modifier = Modifier,
    swap: AccountAvatarSwap = rememberAccountAvatarSwap(card)
) {
    Box(modifier = modifier.semantics { homeAccountShown = card.id }) {
        // The one giving way under the one coming in.
        swap.shown.forEach { shown ->
            key(shown.card.id) {
                val shownCard = shown.card
                val identity = shownCard.provider.serviceIdentity
                AccountAvatar(
                    monogram = monogramOf(shownCard.title, stringResource(identity.nameRes)),
                    shapeIndex = shownCard.avatarShape,
                    identity = identity,
                    ringColor = ringColor,
                    photoUrl = shownCard.photoUrl,
                    size = size,
                    modifier = Modifier.graphicsLayer {
                        alpha = shown.alpha.value
                        scaleX = shown.scale.value
                        scaleY = shown.scale.value
                    }
                )
            }
        }
    }
}

/**
 * An avatar's accounts through a swap: the one it shows, and any still
 * giving way to it — each fading on the effects spring and scaling (to and
 * from [ACCOUNT_SWAP_SCALE]) on the spatial one. Paced ([withPacedFrames]): a
 * swap lands on a failed switch's busy frames (the old feed coming back), and
 * a late frame holds the crossfade rather than cutting from one account to
 * the other.
 */
@Stable
internal class AccountAvatarSwap(initial: ProfileCard) {
    /** One account's avatar in the swap. */
    @Stable
    internal class Shown(card: ProfileCard, entered: Boolean) {
        var card by mutableStateOf(card)
        val alpha = Animatable(if (entered) 1f else 0f)
        val scale = Animatable(if (entered) 1f else ACCOUNT_SWAP_SCALE)
    }

    /** In drawing order: the account coming in last, on top. */
    val shown = mutableStateListOf(Shown(initial, entered = true))

    /** One account, all in: no swap under way. */
    val idle: Boolean
        get() = shown.size == 1 && shown[0].alpha.value == 1f && shown[0].scale.value == 1f

    /** [card] comes in (or stays, with its fields brought up to date); any other gives way. */
    suspend fun showIn(card: ProfileCard, effects: AnimationSpec<Float>, spatial: AnimationSpec<Float>) {
        val coming = shown.firstOrNull { it.card.id == card.id } ?: Shown(card, entered = false)
        coming.card = card
        if (shown.lastOrNull() !== coming) {
            shown.remove(coming)
            shown.add(coming)
        }
        withPacedFrames {
            shown.toList().forEach { each ->
                val target = if (each === coming) 1f else 0f
                launch { each.alpha.animateTo(target, effects) }
                launch { each.scale.animateTo(lerpF(ACCOUNT_SWAP_SCALE, 1f, target), spatial) }
            }
        }
        shown.removeAll { it !== coming }
    }
}

/** [card]'s [AccountAvatarSwap], swapping as [card] changes (Standard role). */
@Composable
private fun rememberAccountAvatarSwap(card: ProfileCard): AccountAvatarSwap {
    val swap = remember { AccountAvatarSwap(card) }
    val effects = YoinMotion.defaultEffectsSpec<Float>(role = YoinMotionRole.Standard)
    val spatial = YoinMotion.defaultSpatialSpec<Float>(role = YoinMotionRole.Standard)
    LaunchedEffect(swap, card) { swap.showIn(card, effects, spatial) }
    return swap
}

/**
 * A switch's flight: the picked row's avatar from [takeOff] (40dp) into the
 * header avatar's slot ([landing]'s centre, 32dp) on the card's p. Its size
 * and ring follow p frame by frame, so it leaves the row and lands in the
 * header without a jump at either end. [card] gives way mid-air if the switch
 * fails on the way up.
 */
@Suppress("ktlint:standard:function-naming") // a Composable
@Composable
private fun BoxScope.HomeAccountSwitchFlight(
    card: ProfileCard,
    progress: () -> Float,
    takeOff: Rect,
    landing: () -> Offset,
    rowRing: Color,
    headerRing: Color
) {
    val p = progress().coerceIn(0f, 1f)
    val size = lerp(HomeAccountButtonAvatar, HomeAccountRowAvatar, p)
    val half = with(LocalDensity.current) { size.toPx() } / 2f
    HomeAccountAvatarSwap(
        card = card,
        size = size,
        ringColor = lerp(headerRing, rowRing, p),
        modifier = Modifier
            // Window px: placed the same way whatever the layout direction.
            .align(AbsoluteAlignment.TopLeft)
            .absoluteOffset {
                val c = lerp(landing(), takeOff.center, progress())
                IntOffset((c.x - half).roundToInt(), (c.y - half).roundToInt())
            }
            .testTag(HOME_ACCOUNT_FLIGHT_TAG)
    )
}

/**
 * The picked row's selection on Home's account card (T7): its segment's
 * colour on the effects spring — the beat the card's close waits for
 * ([SWITCH_SELECTION_BEAT]) — the other accounts' dim on that same spring,
 * and its corners detaching on the spatial spring. One set of springs, drawn
 * by the segment and the rows as they stand, and paced ([withPacedFrames]):
 * the tap's frames are busy ones (Home going to Loading, the motion tier
 * turning), and a late frame holds the selection where it was instead of
 * showing it done.
 */
@Stable
internal class HomeAccountSelection {
    /** The picked row: kept while it eases back once the switch is over. */
    var row by mutableStateOf<String?>(null)
        private set
    private val colour = Animatable(0f)
    private val corners = Animatable(0f)

    /** [id]'s segment colour, 0 (a row's) to 1 (selected). */
    fun colourOf(id: String): Float = if (id == row) colour.value else 0f

    /** [id]'s segment corners, 0 (the row's) to 1 (all round, detached). */
    fun cornersOf(id: String): Float = if (id == row) corners.value else 0f

    /** [id]'s content alpha: the accounts other than the pick wait, dimmed, while it is selected. */
    fun alphaOf(id: String): Float {
        val picked = row
        return if (picked == null || id == picked) {
            1f
        } else {
            lerpF(1f, ACCOUNT_ROW_DIMMED_ALPHA, colour.value.coerceIn(0f, 1f))
        }
    }

    /**
     * Follows a switch's [target] (null: none, or it is over): a new pick
     * starts from a plain row; one that is over eases back on the same springs.
     */
    suspend fun follow(target: String?, effects: AnimationSpec<Float>, spatial: AnimationSpec<Float>) {
        if (target != null && target != row) {
            colour.snapTo(0f)
            corners.snapTo(0f)
            row = target
        }
        val to = if (target != null) 1f else 0f
        withPacedFrames {
            launch { corners.animateTo(to, spatial) }
            colour.animateTo(to, effects)
        }
    }
}

/**
 * The account card, centred on the screen, grown out of the header avatar
 * ([anchor], window px): one spatial spring p (0 = the avatar, 1 = the card)
 * drives the card's surface from the avatar's bounds and roundness to its own,
 * the avatar's flight to the card's big avatar, the scrim, and — late — the
 * card's contents. Closing runs the same p back into the avatar; [onDismissed]
 * runs once it is there, so the dialog window leaves after its card — told
 * whether an avatar flew into the header (false: reduced motion, the card
 * faded where it sat).
 *
 * A close that is a switch ([closeFrom] set) reads as the pick going up, not
 * as a cancel: the picked row takes its selection first, then on the same p
 * its avatar ([closeFrom], window px, 40dp) flies into the header avatar as
 * [closeInto] (32dp) while the old account's big avatar goes with the
 * card's contents. The header avatar's spot is taken as the flight leaves;
 * should the header move meanwhile, the landing follows it on the spatial
 * spring, and the window waits for it there.
 *
 * Once a switch is pending, or the card is on its way out, it takes no more
 * input: nothing on it is left to start while the switch runs.
 *
 * The dialog sits outside Home's Expressive motion role, so its springs are
 * the Standard role's — the selection's too (Settings' selectable segment).
 */
@Composable
internal fun HomeAccountSwitcherDialog(
    visible: Boolean,
    anchor: Rect?,
    cards: List<ProfileCard>,
    onRequestClose: () -> Unit,
    onDismissed: (landedInHeader: Boolean) -> Unit,
    // The account picked, and its avatar's bounds in window px (where a switch takes off from).
    onSwitch: (card: ProfileCard, avatar: Rect) -> Unit,
    onManageAccounts: () -> Unit,
    onAddAccount: () -> Unit,
    onOpenSettings: () -> Unit,
    onEditHome: () -> Unit,
    onCardHome: () -> Unit = {},
    // The account a switch begun from this card is going to: its row is the
    // selected one, and the other accounts wait (disabled) until it is over.
    pendingTarget: String? = null,
    // A switch's close: the picked row's avatar (window px) and the account the
    // header shows next — the switch's target, or the one in use again if the
    // switch has failed meanwhile. Null = a plain close (✕, outside, back).
    closeFrom: Rect? = null,
    closeInto: ProfileCard? = null,
    // The account in use needs its owner: the card offers Reconnect, and
    // waits — as through a switch — while [reconnecting].
    needsReconnect: Boolean = false,
    reconnecting: Boolean = false,
    onReconnect: () -> Unit = {},
    // The header's avatar changes hands with this window's twin of it, never
    // leaving its spot bare: taken once the window's first frames (the twin
    // on the header's) are drawn, handed back — told whether an avatar flew
    // into the header — two frames before [onDismissed] takes the window away.
    onAvatarTaken: () -> Unit = {},
    onAvatarHandedBack: (landedInHeader: Boolean) -> Unit = {},
    // The picked row's selection: the card's own springs, which its close waits on.
    selection: HomeAccountSelection = remember { HomeAccountSelection() }
) {
    // The card closes the way it opened: the profile it opened under holds
    // until the window goes. It can flip while the card is up — Home's
    // Loading reports motion pressure the moment a switch begins (Q14b), just
    // as the card heads back into the avatar — and read per frame, that would
    // snap the morph to the card's rect and drop the avatar's flight half-way.
    val openedReduced = LocalMotionProfile.current == MotionProfile.AdaptiveReduced
    val reduced = remember { openedReduced }
    val progress = remember { Animatable(0f) }
    val spatial = YoinMotion.defaultSpatialSpec<Float>()
    val effects = YoinMotion.defaultEffectsSpec<Float>()
    // Where things are, in this window's px.
    var origin by remember { mutableStateOf(Offset.Zero) }
    var cardBounds by remember { mutableStateOf(Rect.Zero) }
    var bigAvatarBounds by remember { mutableStateOf(Rect.Zero) }
    val dismissed by rememberUpdatedState(onDismissed)
    val cardHome by rememberUpdatedState(onCardHome)
    val avatarTaken by rememberUpdatedState(onAvatarTaken)
    val avatarHandedBack by rememberUpdatedState(onAvatarHandedBack)
    val switchFrom by rememberUpdatedState(closeFrom)
    val switchInto by rememberUpdatedState(closeInto)
    val currentAnchor by rememberUpdatedState(anchor)

    // The header avatar's bounds in this window's px (the card's centre until it is known).
    fun from(): Rect {
        val a = currentAnchor?.translate(-origin.x, -origin.y)
        return a ?: Rect(cardBounds.center, cardBounds.center)
    }
    // Closing, the card keeps the accounts it closed on: a switch that lands
    // meanwhile doesn't reshuffle it on its way out.
    val closingCards = remember(visible) { cards }
    val shownCards = if (visible) cards else closingCards
    val active = shownCards.firstOrNull { it.isActive } ?: shownCards.firstOrNull()
    // A switch's flight, once its close begins: the row it leaves (that row's
    // avatar is the one flying) and where it took off, this window's px.
    var flightRow by remember { mutableStateOf<String?>(null) }
    var takeOff by remember { mutableStateOf(Rect.Zero) }
    // Where that flight lands (the header avatar's centre, this window's px):
    // taken as it leaves, then chased on the spatial spring if the header
    // moves under it (the next feed coming in) — never jumped to.
    val landing = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val landingSpec = YoinMotion.defaultSpatialSpec<Offset>()
    val pending by rememberUpdatedState(pendingTarget)

    // Still a switch: it hasn't failed (or been turned away) since the tap.
    fun stillSwitching(): Boolean = switchFrom != null && switchInto.let { it != null && it.id != active?.id }
    // The picked row turns selected from the tap (the frame its target
    // pends), and back once the switch is over — on the card's paced frames.
    LaunchedEffect(selection, pendingTarget) { selection.follow(pendingTarget, effects, spatial) }
    // Paced: the card's springs land on busy frames — a switch's first ones,
    // the next feed coming in, a Reconnect's source going out to every screen
    // — and a late frame holds them where they were instead of showing them
    // already settled (a cut). Same springs, every step of them drawn.
    LaunchedEffect(visible) {
        withPacedFrames {
            if (visible) {
                flightRow = null
                // The dialog's window takes a few frames to come up: start once the
                // card is laid out and two frames have drawn, or the spring would be
                // nearly done before anything is on screen.
                snapshotFlow { cardBounds }.first { !it.isEmpty }
                withFrameNanos {}
                withFrameNanos {}
                // Those frames have the avatar's twin on the header's own: the
                // header's steps aside now, not as the window was asked for (that
                // left its spot bare until the window drew).
                avatarTaken()
            } else if (stillSwitching()) {
                // The picked row turns selected first: its colour's effects
                // spring ([selection], from the tap) gets to the beat before the
                // card goes, so the tap reads as a choice. A switch that is over
                // meanwhile (it failed) doesn't wait for it.
                snapshotFlow {
                    val picked = pending
                    !stillSwitching() || picked == null || selection.colourOf(picked) >= SWITCH_SELECTION_BEAT
                }.first { it }
                // Still a switch: the row's avatar goes up. Otherwise the card
                // closes like any other close.
                val into = switchInto
                val from = switchFrom
                if (stillSwitching() && into != null && from != null && !reduced) {
                    takeOff = from.translate(-origin.x, -origin.y)
                    landing.snapTo(from().center)
                    flightRow = into.id
                }
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
                // A flight that is still following the header's spot gets there first.
                if (flightRow != null) snapshotFlow { landing.isRunning }.first { !it }
                if (!home) cardHome()
                // The avatar goes back to the header (or Home's stand-in) under
                // this window's twin of it, at rest on the same spot, and the
                // window goes once Home's has drawn it: no frame without it.
                avatarHandedBack(!reduced)
                withFrameNanos {}
                withFrameNanos {}
                dismissed(!reduced)
            }
        }
    }
    LaunchedEffect(flightRow) {
        if (flightRow == null) return@LaunchedEffect
        withPacedFrames {
            snapshotFlow { from().center }.collectLatest { spot ->
                if (spot != landing.targetValue) landing.animateTo(spot, landingSpec)
            }
        }
    }
    // Once a switch is pending, a Reconnect is running, or the card is going: nothing on it starts anything.
    val takesInput = visible && pendingTarget == null && !reconnecting
    val surface = MaterialTheme.colorScheme.surfaceContainer
    val scrim = MaterialTheme.colorScheme.scrim
    val density = LocalDensity.current
    val cardCorner = with(density) { HomeAccountCardCorner.toPx() }
    val bigAvatarPx = with(density) { HomeAccountBigAvatar.toPx() }
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
                    cards = shownCards,
                    onClose = onRequestClose,
                    onSwitch = onSwitch,
                    onManageAccounts = onManageAccounts,
                    onAddAccount = onAddAccount,
                    onOpenSettings = onOpenSettings,
                    onEditHome = onEditHome,
                    containerColor = Color.Transparent,
                    enabled = takesInput,
                    pendingTarget = pendingTarget,
                    selection = selection,
                    flightRow = flightRow,
                    needsReconnect = needsReconnect,
                    reconnecting = reconnecting,
                    onReconnect = onReconnect,
                    // The big avatar stays hidden while its twin flies in, and reports where it is.
                    // A switch's flight is the picked row's: the big one (the old account) goes with the contents.
                    avatarModifier = Modifier
                        .onGloballyPositioned { bigAvatarBounds = it.boundsInWindow().translate(-origin.x, -origin.y) }
                        .graphicsLayer { alpha = if (flying && flightRow == null) 0f else 1f },
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
                val into = closeInto
                if (flying && flightRow != null && into != null) {
                    // A switch's flight, from the picked row into the header avatar.
                    HomeAccountSwitchFlight(
                        card = into,
                        progress = { progress.value },
                        takeOff = takeOff,
                        landing = { landing.value },
                        // The row it leaves wears its service's colour (its selected segment).
                        rowRing = into.provider.serviceIdentity.hue.tone().container,
                        headerRing = surface
                    )
                } else if (flying && active != null && !reduced) {
                    // The avatar's flight, from the header to the big avatar —
                    // on the header's spot from the window's first frame, the
                    // big avatar laid out or not: the header's twin is up there
                    // before the header's own steps aside.
                    val identity = active.provider.serviceIdentity
                    AccountAvatar(
                        monogram = monogramOf(active.title, stringResource(identity.nameRes)),
                        shapeIndex = active.avatarShape,
                        identity = identity,
                        ringColor = surface,
                        photoUrl = active.photoUrl,
                        size = HomeAccountBigAvatar,
                        modifier = Modifier
                            // Window px (the layer's translation): the same whatever the layout direction.
                            .align(AbsoluteAlignment.TopLeft)
                            .testTag(HOME_ACCOUNT_FLIGHT_TAG)
                            .semantics { homeAccountShown = active.id }
                            .graphicsLayer {
                                val p = progress.value
                                val start = from()
                                // Not laid out yet: no flight to make, it waits where it starts.
                                val big = bigAvatarBounds
                                val r = if (big.isEmpty) start else lerpRect(start, big, p)
                                transformOrigin = TransformOrigin(0f, 0f)
                                translationX = r.left
                                translationY = r.top
                                val scale = r.width / (if (big.isEmpty) bigAvatarPx else big.width)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeAccountCard(
    cards: List<ProfileCard>,
    onClose: () -> Unit,
    onSwitch: (card: ProfileCard, avatar: Rect) -> Unit,
    onManageAccounts: () -> Unit,
    onAddAccount: () -> Unit,
    onOpenSettings: () -> Unit,
    onEditHome: () -> Unit,
    modifier: Modifier = Modifier,
    avatarModifier: Modifier = Modifier,
    // The dialog paints the card's surface itself (it grows out of the avatar).
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    // False while a switch is pending or the card is going: nothing on it starts
    // anything (its looks stay as they are; the card is already on its way).
    enabled: Boolean = true,
    // A switch under way: its row is the selected one, the other accounts wait.
    pendingTarget: String? = null,
    // How far the picked row's selection has got (the dialog springs it).
    selection: HomeAccountSelection = remember { HomeAccountSelection() },
    // The row whose avatar is flying up to the header (it leaves the row).
    flightRow: String? = null,
    // The account in use needs its owner: Reconnect beside Manage accounts.
    needsReconnect: Boolean = false,
    reconnecting: Boolean = false,
    onReconnect: () -> Unit = {}
) {
    // Taken only while the card takes input.
    fun gated(action: () -> Unit): () -> Unit = { if (enabled) action() }
    val role = LocalYoinMotionRole.current
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
                IconButton(onClick = gated(onClose)) {
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
            // Manage accounts, with Reconnect ahead of it while the account in
            // use needs its owner: it opens and closes its width on the
            // spatial spring and fades on the effects spring. A row that wraps
            // under a large font rather than clip.
            FlowRow(
                modifier = Modifier.padding(top = 12.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AnimatedVisibility(
                    visible = needsReconnect,
                    enter = YoinMotion.fadeIn(role) + YoinMotion.expandHorizontally(role),
                    exit = YoinMotion.fadeOut(role) + YoinMotion.shrinkHorizontally(role)
                ) {
                    HomeAccountReconnectButton(
                        reconnecting = reconnecting,
                        onClick = gated(onReconnect),
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
                OutlinedButton(onClick = gated(onManageAccounts)) {
                    Text(stringResource(R.string.home_account_manage))
                }
            }
            SettingsSegments {
                others.forEach { card ->
                    // Settings' selectable segment: the picked row detaches at once and
                    // fills with its service's colour — Settings' colour for the
                    // account in use (design.md, 颜色系统 5), the one it is becoming.
                    // Drawn from the card's own springs (paced), not the segment's.
                    item(key = card.id, paintsOwnSegment = true) {
                        SelectableSegment(
                            selected = card.id == pendingTarget,
                            selectedColor = card.provider.serviceIdentity.hue.tone().container,
                            springs = SegmentSelection(
                                colour = { selection.colourOf(card.id) },
                                corners = { selection.cornersOf(card.id) }
                            )
                        ) {
                            AccountRow(
                                card = card,
                                enabled = enabled,
                                contentAlpha = { selection.alphaOf(card.id) },
                                avatarHidden = card.id == flightRow,
                                onClick = { avatar -> onSwitch(card, avatar) }
                            )
                        }
                    }
                }
                item(key = "add") {
                    SettingsItem(
                        icon = YoinSymbols.Add,
                        title = stringResource(R.string.home_account_add),
                        onClick = gated(onAddAccount),
                    )
                }
            }
            Spacer(Modifier.size(12.dp))
            SettingsSegments {
                item(key = "settings") {
                    SettingsItem(
                        icon = YoinSymbols.Settings,
                        title = stringResource(R.string.home_account_settings),
                        onClick = gated(onOpenSettings),
                    )
                }
                item(key = "edit") {
                    SettingsItem(
                        icon = YoinSymbols.Edit,
                        title = stringResource(R.string.home_edit_title),
                        onClick = gated(onEditHome),
                    )
                }
            }
        }
    }
}

/**
 * Another account: avatar, name, service line (or why it can't be used now).
 * [onClick] gets the avatar's bounds in window px. While a switch is under way
 * (or the card is going) the row is disabled ([enabled]) and, during a switch,
 * if it isn't the one picked, dimmed ([contentAlpha], the card's selection's
 * effects spring) so a second tap is plainly not taken.
 */
@Composable
private fun AccountRow(
    card: ProfileCard,
    onClick: (avatar: Rect) -> Unit,
    enabled: Boolean = true,
    contentAlpha: () -> Float = { 1f },
    avatarHidden: Boolean = false
) {
    val identity = card.provider.serviceIdentity
    val haptics = rememberYoinHaptics()
    var avatarBounds by remember { mutableStateOf(Rect.Zero) }
    // The colour its segment is drawn in: how far the selection has got.
    val segmentColor = LocalSettingsRowColor.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(homeAccountRowTag(card.id))
            .semantics { homeAccountRowColor = segmentColor }
            .clickable(enabled = enabled, role = Role.Button) {
                haptics.performClick()
                onClick(avatarBounds)
            }
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .graphicsLayer { alpha = contentAlpha() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AccountAvatar(
            monogram = monogramOf(card.title, stringResource(identity.nameRes)),
            shapeIndex = card.avatarShape,
            identity = identity,
            // Ring-cut against the row, whatever colour its segment is taking.
            ringColor = segmentColor.takeOrElse { MaterialTheme.colorScheme.surfaceBright },
            photoUrl = card.photoUrl,
            size = HomeAccountRowAvatar,
            modifier = Modifier
                .onGloballyPositioned { avatarBounds = it.unclippedBoundsInWindow() }
                .graphicsLayer { alpha = if (avatarHidden) 0f else 1f }
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
                    // In the app's language, as Settings' pill says it.
                    text = accountIssueLabel(reason),
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

/**
 * Reconnect, tonal beside Manage accounts' outline. While the account is
 * tried again ([reconnecting]) its label gives way, on the effects spring, to
 * T7's loading indicator (effects fade, spatial scale); the button keeps its
 * size and colours.
 */
@Suppress("ktlint:standard:function-naming") // a Composable
@Composable
private fun HomeAccountReconnectButton(reconnecting: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val role = LocalYoinMotionRole.current
    val labelAlpha by animateFloatAsState(
        targetValue = if (reconnecting) 0f else 1f,
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "reconnectLabel"
    )
    FilledTonalButton(onClick = onClick, modifier = modifier.testTag(HOME_ACCOUNT_RECONNECT_TAG)) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.home_account_reconnect),
                modifier = Modifier.graphicsLayer { alpha = labelAlpha }
            )
            // The plain one: the button's RowScope is not this Box's to use.
            androidx.compose.animation.AnimatedVisibility(
                visible = reconnecting,
                enter = YoinMotion.fadeIn(role) + YoinMotion.scaleIn(role, initialScale = HOME_LOADING_INDICATOR_SCALE),
                exit = YoinMotion.fadeOut(role) + YoinMotion.scaleOut(role, targetScale = HOME_LOADING_INDICATOR_SCALE)
            ) {
                YoinLoadingIndicator(size = HomeAccountReconnectIndicator)
            }
        }
    }
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
            onSwitch = { _, _ -> },
            onManageAccounts = {},
            onAddAccount = {},
            onOpenSettings = {},
            onEditHome = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Suppress("ktlint:standard:function-naming") // a Composable
@Preview(showBackground = true, widthDp = 412, heightDp = 520)
@Composable
private fun HomeAccountCardReconnectPreview() {
    val account = ProfileCard(
        id = "a",
        displayName = "qa",
        subtitle = "music.example.com",
        provider = ProviderKind.SUBSONIC,
        isActive = true
    )
    YoinTheme {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            HomeAccountButton(card = account, onClick = {}, needsReconnect = true)
            HomeAccountCard(
                cards = listOf(account),
                onClose = {},
                onSwitch = { _, _ -> },
                onManageAccounts = {},
                onAddAccount = {},
                onOpenSettings = {},
                onEditHome = {},
                needsReconnect = true,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

/** Closing progress at which the card reads as back in the avatar ([HomeAccountSwitcherDialog]'s onCardHome). */
private const val CardHomeProgress = 0.04f

/** How far the picked row's selection (its effects spring) gets before a switch closes the card. */
internal const val SWITCH_SELECTION_BEAT = 0.9f

/** M3's disabled list-item content opacity: the accounts waiting out a switch. */
internal const val ACCOUNT_ROW_DIMMED_ALPHA = 0.38f

/** An avatar giving way to another account's leaves to (and the new one settles from) this scale. */
private const val ACCOUNT_SWAP_SCALE = 0.8f

/** The header avatar on its way into (or back out of) the card, or a switch's on its way up. */
internal const val HOME_ACCOUNT_FLIGHT_TAG = "home-account-flight"

/** Home's stand-in for the header avatar through a switch ([HomeAccountAvatarHold]). */
internal const val HOME_ACCOUNT_HOLD_TAG = "home-account-hold"

/** The header avatar's dot: the account in use needs its owner. */
internal const val HOME_ACCOUNT_RECONNECT_DOT_TAG = "home-account-reconnect-dot"

/** The card's Reconnect button. */
internal const val HOME_ACCOUNT_RECONNECT_TAG = "home-account-reconnect"

/** An account's row in the switcher card. */
internal fun homeAccountRowTag(profileId: String) = "home-account-row-$profileId"

/** The account an avatar in flight (or Home's stand-in) is showing. */
internal val HomeAccountShownKey = SemanticsPropertyKey<String>("HomeAccountShown")
internal var SemanticsPropertyReceiver.homeAccountShown by HomeAccountShownKey

/** The colour an account row's segment is drawn in on the card (how far its selection has got). */
internal val HomeAccountRowColorKey = SemanticsPropertyKey<Color>("HomeAccountRowColor")
internal var SemanticsPropertyReceiver.homeAccountRowColor by HomeAccountRowColorKey

/** Home's stand-in is up: the switcher's window has gone and it hasn't lost its spot. */
internal val HomeAccountHoldUpKey = SemanticsPropertyKey<Boolean>("HomeAccountHoldUp")
internal var SemanticsPropertyReceiver.homeAccountHoldUp by HomeAccountHoldUpKey
