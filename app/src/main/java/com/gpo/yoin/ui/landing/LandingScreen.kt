package com.gpo.yoin.ui.landing

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.yoin.AppContainer
import com.gpo.yoin.R
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.data.profile.ProviderKind
import com.gpo.yoin.ui.experience.LocalYoinWindowInfo
import com.gpo.yoin.ui.experience.LayoutMode
import com.gpo.yoin.ui.experience.ShellChromeForm
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.experience.voteHighFrameRate
import com.gpo.yoin.ui.landing.guide.ConnectGuide
import com.gpo.yoin.ui.navigation.back.LandingPredictiveBack
import com.gpo.yoin.ui.settings.assignAvatarShapes
import com.gpo.yoin.ui.settings.service.SetupService
import com.gpo.yoin.ui.theme.YoinMotion
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * What MainActivity knows about the landing: whether the first-run decision is made (the splash holds until
 * it is, so an existing user never sees the landing flash) and re-run requests from Settings › About.
 */
@Stable
class LandingGate {
    /** The splash may go: the first-run check is done (or took too long to wait for). */
    @Volatile
    var decided: Boolean = false

    /** The activity was recreated: the landing's own saved state says whether it shows, no new check. */
    var restored: Boolean = false

    /** The splash stopped waiting before the check finished: the landing fades in over the shell instead. */
    @Volatile
    var splashTimedOut: Boolean = false

    /** The landing covers the shell (MainActivity hides the shell from accessibility meanwhile). */
    var showing by mutableStateOf(false)

    /**
     * Don't compose the shell yet: a first launch until the check is done, then while the first-run landing
     * shows — until it nears the end and the shell warms up beneath it for the hand-off. Nothing in the shell
     * is reachable meanwhile, and composing it would delay the landing's first frame.
     */
    var holdShell by mutableStateOf(false)

    private val requests = MutableStateFlow(0)
    val rerunRequests: StateFlow<Int> = requests.asStateFlow()

    fun requestRerun() {
        requests.value += 1
    }
}

/**
 * Mounted by MainActivity ABOVE the shell, so it covers the bar too and its back handler registers after the
 * shell's (and is the only one enabled while it shows). Shows on a first launch with no account, and when
 * Settings › About › Welcome guide asks for a re-run. It removes itself after the hand-off into Home.
 */
@Composable
fun LandingHost(gate: LandingGate) {
    val context = LocalContext.current
    val container = (context.applicationContext as YoinApplication).container
    val store = remember(context) { SharedPrefsLandingStore(context) }
    val startupDone by container.profileStartupDone.collectAsState()
    val profiles by container.profileManager.profiles.collectAsState(initial = null)
    val rerun by gate.rerunRequests.collectAsState()
    var mode by rememberSaveable { mutableStateOf<LandingMode?>(null) }
    // Lives exactly as long as the gate's counter (a field of this activity instance): saved across a process
    // death it would sit ahead of a fresh counter and swallow the next Welcome guide request.
    var handledRerun by remember { mutableIntStateOf(0) }
    var session by rememberSaveable { mutableIntStateOf(0) }

    var checked by rememberSaveable { mutableStateOf(false) }
    var lateStart by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(startupDone, profiles) {
        // A recreated activity keeps the landing's saved state; the check runs once per launch.
        if (checked || gate.restored) {
            gate.decided = true
            return@LaunchedEffect
        }
        if (store.isDone()) {
            checked = true
            gate.holdShell = false
            gate.decided = true
            return@LaunchedEffect
        }
        val list = profiles ?: return@LaunchedEffect
        if (!startupDone) return@LaunchedEffect
        checked = true
        if (list.isEmpty()) {
            lateStart = gate.splashTimedOut
            mode = LandingMode.FirstRun
            session += 1
        } else {
            // An existing install: it never sees the landing, even after deleting every account.
            store.markDone()
            gate.holdShell = false
        }
        gate.decided = true
    }
    LaunchedEffect(rerun) {
        if (rerun > handledRerun) {
            handledRerun = rerun
            mode = LandingMode.Rerun
            session += 1
        }
    }

    val showing = mode != null
    SideEffect { gate.showing = showing }
    val current = mode ?: return
    val scope: LandingScope = viewModel(key = "landing-scope")
    androidx.compose.runtime.key(session) {
        CompositionLocalProvider(LocalViewModelStoreOwner provides scope) {
            LandingScreen(
                mode = current,
                container = container,
                store = store,
                fadeIn = lateStart || current == LandingMode.Rerun,
                onShellWanted = { gate.holdShell = false },
                onClosed = {
                    gate.holdShell = false
                    mode = null
                    scope.reset()
                    ConnectGuide.finishIfOpen()
                },
            )
        }
    }
}

/** The landing's own ViewModel store: cleared when a landing closes, kept across rotation. */
class LandingScope : ViewModel(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()

    fun reset() = viewModelStore.clear()

    override fun onCleared() = viewModelStore.clear()
}

@Composable
private fun rememberReducedMotion(): Boolean {
    val scale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor
    return scale == 0f
}

@Composable
internal fun LandingScreen(
    mode: LandingMode,
    container: AppContainer,
    store: LandingStore,
    fadeIn: Boolean,
    onShellWanted: () -> Unit,
    onClosed: () -> Unit,
) {
    val context = LocalContext.current
    val vm: LandingViewModel = viewModel(key = "landing", factory = LandingViewModel.Factory(context, container, mode, store))
    val state by vm.state.collectAsState()
    val motion = rememberLandingMotion()
    val haptics = rememberYoinHaptics()
    val reduced = rememberReducedMotion()
    val mascot = rememberLandingMascotState(haptics, reduced)
    val tail = remember { BubbleTailState() }
    val windowSpec = remember { LandingWindowSpec() }
    // Over the splash it is simply there; over a visible shell (a slow first launch, a re-run) it fades in.
    val reveal = remember { Animatable(if (fadeIn && !vm.introPlayed) 0f else 1f) }
    val effects = YoinMotion.defaultEffectsSpec<Float>()
    LaunchedEffect(Unit) { if (reveal.value < 1f) reveal.animateTo(1f, effects) }
    val scope = rememberCoroutineScope()
    val spatial = YoinMotion.defaultSpatialSpec<Float>()
    val fastSpatial = YoinMotion.fastSpatialSpec<Float>()
    val slowSpatial = YoinMotion.slowSpatialSpec<Float>()
    val slowEffects = YoinMotion.slowEffectsSpec<Float>()
    val windowInfo = LocalYoinWindowInfo.current
    val profiles by container.profileManager.profiles.collectAsState(initial = emptyList())
    val activeId by container.profileManager.activeProfileId.collectAsState()

    // The shell warms up beneath from the scroll-edge step on, so Home is ready when the hand-off reveals it.
    val currentOnShellWanted by rememberUpdatedState(onShellWanted)
    LaunchedEffect(state.step, state.finishing) {
        if (state.finishing || state.index >= state.steps.indexOf(LandingStep.Edge)) currentOnShellWanted()
    }

    // The motion follows the ViewModel's step; the first frame (and after rotation) lands on it directly.
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(state.step) {
        if (!placed) {
            motion.snap(state.step)
            placed = true
        } else {
            motion.go(state.step, spatial)
        }
    }
    LaunchedEffect(motion) {
        snapshotFlow { motion.bubbleStep }.drop(1).collect { motion.popBubble(fastSpatial) }
    }
    LaunchedEffect(Unit) {
        if (vm.introPlayed || reduced) {
            vm.introPlayed = true
            return@LaunchedEffect
        }
        vm.introPlayed = true
        motion.pillIn.snapTo(0f)
        motion.bubblePop.snapTo(0f)
        delay(IntroDelayMs)
        mascot.intro(
            onLanded = {
                scope.launch { motion.pillIn.animateTo(1f, spatial) }
                scope.launch {
                    delay(BubbleAfterLandMs)
                    motion.bubblePop.snapTo(0.2f)
                    motion.bubblePop.animateTo(1f, fastSpatial)
                }
            },
        )
    }

    val reactions = remember(vm, mascot, haptics) {
        object : LandingReactions {
            override fun refused() {
                haptics.performReject()
                mascot.shake()
            }

            override fun connected(service: SetupService) {
                haptics.performConfirm()
                vm.onConnected(service)
                mascot.cheer()
            }
        }
    }

    fun finish() {
        haptics.performConfirm()
        ConnectGuide.finishIfOpen()
        vm.finish {
            scope.launch {
                mascot.wave(times = 1, ticks = false)
                launch { motion.handoff.animateTo(1f, spatial) }
                launch {
                    delay(FlyAfterMs)
                    motion.fly.animateTo(1f, slowSpatial)
                }
                delay(RevealAfterMs)
                reveal.animateTo(0f, slowEffects)
                onClosed()
            }
        }
    }

    fun close() {
        // A re-run closed from its first step: fade back to Home.
        scope.launch {
            reveal.animateTo(0f, slowEffects)
            onClosed()
        }
    }

    LandingPredictiveBack(
        enabled = !state.finishing && (state.canGoBack || mode == LandingMode.Rerun),
        preview = motion.preview,
        onBack = { if (state.canGoBack) vm.back() else close() },
    )

    val dark = isSystemInDarkTheme()
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val topInset = WindowInsets.statusBars.getTop(density)
    val bottomInset = WindowInsets.navigationBars.getBottom(density)
    val ime = WindowInsets.ime
    val twoColumns = windowInfo.layoutMode == LayoutMode.Wide || windowInfo.isCompactHeight
    val centeredBar = windowInfo.chromeForm == ShellChromeForm.CenteredBar
    val accounts = remember(profiles, activeId) { landingAccounts(profiles.map { AccountRow(it.id, it.displayName, it.provider, it.createdAt) }, activeId) }
    val moving = !motion.settled || motion.handoff.isRunning

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = reveal.value }
            // The landing is opaque: touches never reach the shell beneath.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .voteHighFrameRate(moving),
    ) {
        LandingBackdrop(dark = dark, base = scheme.surface, animate = !reduced)
        LandingStage(
            motion = motion,
            windowSpec = windowSpec,
            tail = tail,
            topInset = topInset,
            bottomInset = bottomInset,
            imeBottom = { ime.getBottom(density) },
            centeredBar = centeredBar,
            twoColumns = twoColumns,
            window = {
                LandingWindow(
                    motion = motion,
                    spec = windowSpec,
                    shape = RoundedCornerShape(28.dp),
                    color = scheme.surfaceContainer,
                    scene = { step ->
                        LandingScene(
                            step = step,
                            state = state,
                            container = container,
                            accounts = accounts,
                            reactions = reactions,
                            edgePlaying = motion.to == LandingStep.Edge,
                            onToggleService = { service ->
                                if (service !in state.picked) mascot.nod()
                                vm.toggleService(service)
                            },
                            vm = vm,
                        )
                    },
                )
            },
            mascot = {
                LandingMascot(
                    state = mascot,
                    breathing = !reduced && !moving,
                    modifier = Modifier.pointerInput(Unit) {
                        detectTapGestures {
                            haptics.performTick()
                            mascot.wave(times = 1, ticks = false)
                        }
                    },
                )
            },
            bubble = {
                val (title, body) = bubbleLines(motion.bubbleStep, accounts.isEmpty())
                LandingBubble(
                    title = stringResource(title),
                    body = body?.let { stringResource(it) },
                    tail = tail,
                    background = if (dark) scheme.primaryFixed else scheme.surfaceBright,
                    content = if (dark) scheme.onPrimaryFixed else scheme.onSurface,
                )
            },
            pill = {
                val step = state.step
                LandingPill(
                    hello = step == LandingStep.Hello,
                    primary = primaryFor(step, state, accounts.isNotEmpty()),
                    dotCount = state.steps.size - 1,
                    dotIndex = (state.index - 1).coerceAtLeast(0),
                    centered = centeredBar,
                    onBack = { if (state.canGoBack) vm.back() else if (mode == LandingMode.Rerun) close() },
                    onPrimary = {
                        when {
                            step == LandingStep.Ready -> finish()
                            step == LandingStep.Pick && state.picked.isEmpty() && !state.hasAccount -> reactions.refused()
                            else -> vm.next()
                        }
                    },
                    contentAlpha = { (1f - motion.handoff.value * 2.2f).coerceIn(0f, 1f) },
                )
            },
        )
    }
}

@Composable
private fun LandingScene(
    step: LandingStep,
    state: LandingUiState,
    container: AppContainer,
    accounts: List<LandingAccount>,
    reactions: LandingReactions,
    edgePlaying: Boolean,
    onToggleService: (SetupService) -> Unit,
    vm: LandingViewModel,
) {
    when (step) {
        LandingStep.Hello -> Unit
        LandingStep.Pick -> PickScene(picked = state.picked, onToggle = onToggleService)
        LandingStep.About -> AboutScene(picked = state.picked)
        LandingStep.Subsonic -> SubsonicConnectScene(container, SetupService.Subsonic in state.connected, reactions)
        LandingStep.Spotify -> SpotifyConnectScene(container, SetupService.Spotify in state.connected, reactions)
        LandingStep.AppleMusic -> AppleConnectScene(SetupService.AppleMusic in state.connected, reactions)
        LandingStep.Home -> HomeSectionsScene(state.layout, vm::setSectionEnabled, vm::moveSection)
        LandingStep.Edge -> ScrollEdgeScene(selected = state.edge, playing = edgePlaying, onSelect = vm::setEdge)
        LandingStep.Ready -> ReadyScene(accounts)
    }
}

private fun primaryFor(step: LandingStep, state: LandingUiState, hasAccount: Boolean): LandingPrimary = when {
    step == LandingStep.Hello -> LandingPrimary(R.string.landing_get_started, LandingPrimary.Style.Filled, arrow = true)
    step == LandingStep.Pick -> when {
        state.picked.isNotEmpty() -> LandingPrimary(R.string.landing_next, LandingPrimary.Style.Filled)
        // An account already exists (a re-run): nothing new to add is fine, Home and the edge are still ahead.
        hasAccount -> LandingPrimary(R.string.landing_skip, LandingPrimary.Style.Tonal)
        else -> LandingPrimary(R.string.landing_next, LandingPrimary.Style.Unavailable)
    }
    step.service != null -> if (step.service in state.connected) {
        LandingPrimary(R.string.landing_next, LandingPrimary.Style.Filled)
    } else {
        LandingPrimary(R.string.landing_skip, LandingPrimary.Style.Tonal)
    }
    step == LandingStep.Ready -> LandingPrimary(R.string.landing_open_yoin, LandingPrimary.Style.Filled, arrow = true)
    else -> LandingPrimary(R.string.landing_next, LandingPrimary.Style.Filled)
}

private fun bubbleLines(step: LandingStep, noAccounts: Boolean): Pair<Int, Int?> = when (step) {
    LandingStep.Hello -> R.string.landing_hello_title to R.string.landing_hello_body
    LandingStep.Pick -> R.string.landing_pick_title to null
    LandingStep.About -> R.string.landing_about_title to null
    LandingStep.Subsonic -> R.string.landing_subsonic_title to null
    LandingStep.Spotify -> R.string.landing_spotify_title to R.string.landing_spotify_body
    LandingStep.AppleMusic -> R.string.landing_apple_title to R.string.landing_apple_body
    LandingStep.Home -> R.string.landing_home_title to null
    LandingStep.Edge -> R.string.landing_edge_title to null
    LandingStep.Ready -> if (noAccounts) R.string.landing_ready_none_title to R.string.landing_ready_none_body else R.string.landing_ready_title to null
}

private data class AccountRow(val id: String, val name: String, val provider: String, val createdAt: Long)

private fun landingAccounts(rows: List<AccountRow>, activeId: String?): List<LandingAccount> {
    val shapes = assignAvatarShapes(rows.map { it.id to it.createdAt })
    return rows.map { row ->
        LandingAccount(
            id = row.id,
            name = row.name,
            provider = ProviderKind.fromKeyOrSubsonic(row.provider),
            shapeIndex = shapes[row.id] ?: 0,
            inUse = row.id == activeId,
        )
    }.sortedByDescending { it.inUse }
}

/**
 * The landing's ground: the page colour with the launcher icon's navy glow and two slow brand blooms (violet,
 * pink). One drawBehind; the drift runs only when motion is allowed.
 */
@Composable
private fun LandingBackdrop(dark: Boolean, base: Color, animate: Boolean) {
    val phase = if (animate) {
        val transition = rememberInfiniteTransition(label = "landingBackdrop")
        val value by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(BackdropPeriodMs, easing = LinearEasing), RepeatMode.Reverse), label = "backdropPhase")
        ({ value })
    } else {
        ({ 0.5f })
    }
    val glow = if (dark) Color(0xF2092163) else Color(0x1A192396)
    val violet = Color(0xFF9113FF).copy(alpha = if (dark) 0.30f else 0.16f)
    val pink = Color(0xFFFF59CD).copy(alpha = if (dark) 0.17f else 0.15f)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(base)
            .drawBehind {
                val p = phase()
                val w = size.width
                val h = size.height
                fun bloom(color: Color, cx: Float, cy: Float, r: Float) =
                    drawCircle(Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), Offset(cx, cy), r), r, Offset(cx, cy))
                bloom(glow, w * (0.5f + 0.04f * p), h * (0.46f + 0.05f * p), maxOf(w, h) * 0.62f)
                bloom(violet, w * (0.05f + 0.18f * p), h * (0.12f + 0.1f * p), w * 0.62f)
                bloom(pink, w * (1.0f - 0.14f * p), h * (0.5f + 0.16f * p), w * 0.55f)
            },
    )
}

private const val IntroDelayMs = 220L
private const val BubbleAfterLandMs = 150L
private const val FlyAfterMs = 350L
private const val RevealAfterMs = 520L
private const val BackdropPeriodMs = 24_000
