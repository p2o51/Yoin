package com.gpo.yoin.ui.detail

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.gpo.yoin.MainActivity
import com.gpo.yoin.R
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.ui.component.BarExtraAction
import com.gpo.yoin.ui.component.BarPlaySplitActions
import com.gpo.yoin.ui.component.YoinChromeGroup
import com.gpo.yoin.ui.experience.CoveredWindowAnimationGate
import com.gpo.yoin.ui.experience.EdgeSplitSide
import com.gpo.yoin.ui.experience.LayoutMode
import com.gpo.yoin.ui.experience.LocalYoinWindowInfo
import com.gpo.yoin.ui.experience.ShellChromeForm
import com.gpo.yoin.ui.experience.rememberEdgeSplitSide
import com.gpo.yoin.ui.experience.windowChromeInfo
import com.gpo.yoin.ui.navigation.YoinSection
import com.gpo.yoin.ui.navigation.back.OverlayChromeVisibility
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import java.util.UUID

/**
 * The detail pages' bottom bar — the shell Button Group's morph target.
 *
 * Same [FloatingBottomBar] scaffold and [NowPlayingPill] as the shell, with
 * the nav buttons swapped for the Play split button: the shell bar morphs
 * into exactly this composition during the shell→detail hand-off. Both
 * windows read the same forward pose until predictive back takes over. Present in
 * ALL page states (Loading/Error too) — the bar never waits for page data;
 * Play simply no-ops until the tracks arrive.
 *
 * Pill tap opens Now Playing in place over this window (the Activity hosts
 * NowPlayingOverlayHost — the same Closed → Panel → Full chain as the
 * shell); back collapses it onto this page. A page hosted in the shell's
 * detail column ([DetailHostMode.Pane]) draws no bar: the shell's one bar
 * carries its Play split.
 */
@Composable
fun DetailBottomBar(
    playContainer: Color,
    playContent: Color,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onOpenNowPlaying: () -> Unit,
    miniPlayer: DetailMiniPlayerState?,
    playbackProgress: Float,
    modifier: Modifier = Modifier,
    nowPlayingOpen: Boolean = false,

    interactionsEnabled: Boolean = true,
    // Predictive-back scrub (0 = resting detail chrome, 1 = fully nav): the
    // gesture drives the split⇄nav morph interactively when this page will
    // reveal the shell. Pages stacked over another detail keep 0 — the bar
    // beneath is identical, so the correct read is "the bar doesn't move".
    backMorphProgress: () -> Float = { 0f },
    // Reads the shell's live pose during a forward hand-off. The local back
    // controller takes over on gesture/commit, including its cancel settle.
    enterChromeProgress: () -> Float = { 1f },
    // The shell tab the back scrub reveals — carried from the launch (the
    // shell's selectedSection at tap time) so a Library-origin back doesn't
    // preview a Home-selected bar.
    navSection: YoinSection = YoinSection.HOME,
    // Predictive-back EXIT (NP-origin pages): the reveal is the expanded
    // player, which has no bar — so instead of morphing, the whole bar rides
    // the gesture DOWN off-screen 1:1 (cancel springs it back, commit
    // finishes the ride). Mutually exclusive with backMorphProgress.
    backExitProgress: () -> Float = { 0f },
    // Page actions that may leave ▾ for their own buttons where the bar has
    // room (Go to artist, Share — 断点交接 §2.3); menu rows elsewhere.
    promotable: List<BarExtraAction> = emptyList(),
    menuItems: @Composable ColumnScope.(dismissMenu: () -> Unit) -> Unit = {},
) {
    // A page hosted in the shell's detail column has no bar of its own: the
    // shell's ONE bar spans both columns and carries this page's Play split
    // (adaptive principle 2 — one window, one bar).
    if (LocalDetailHostMode.current == DetailHostMode.Pane) return
    // The bar belongs to the WINDOW (adaptive principle 2): beside an open
    // Now Playing panel the page reads its narrowed width, the bar does not —
    // it exits in the form it was shown in.
    val windowInfo = windowChromeInfo
    val form = windowInfo.chromeForm
    // Same choreography as the shell bar when NP expands over it: the bar
    // slides out of the way (down, or left for the edge capsules) while the
    // player rises.
    val edge = form == ShellChromeForm.EdgeSplit
    // The capsules slide over their own edge — the cutout's.
    val edgeOut = if (rememberEdgeSplitSide() == EdgeSplitSide.Right) 1 else -1
    OverlayChromeVisibility(
        expanded = nowPlayingOpen,

        enter = YoinMotion.fadeIn(role = YoinMotionRole.Standard) + if (edge) {
            YoinMotion.slideInHorizontally(role = YoinMotionRole.Standard) { edgeOut * it }
        } else {
            YoinMotion.slideInVertically(role = YoinMotionRole.Standard) { it }
        },
        exit = YoinMotion.fadeOut(role = YoinMotionRole.Standard) + if (edge) {
            YoinMotion.slideOutHorizontally(role = YoinMotionRole.Standard) { edgeOut * it }
        } else {
            YoinMotion.slideOutVertically(role = YoinMotionRole.Standard) { it }
        },
        modifier = modifier.then(
            if (interactionsEnabled) {
                Modifier
            } else {
                // The hidden detail window still draws this pixel-identical
                // bar to keep its surface alive. It must not steal a residual
                // pointer from the source window or expose duplicate a11y
                // actions before the page itself is committed.
                Modifier
                    .clearAndSetSemantics { }
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial)
                                    .changes
                                    .forEach { it.consume() }
                            }
                        }
                    }
            },
        ),
    ) {
        // LITERALLY the shell's group composable, in the window's form —
        // pixel identity between the two windows by construction, plus the
        // nav side of the morph for the predictive-back scrub. The exit scrub
        // is read per frame inside the group's graphicsLayer.
        YoinChromeGroup(
            form = form,
            wide = windowInfo.layoutMode == LayoutMode.Wide,
            exitProgress = backExitProgress,
            selectedSection = navSection,
            // Real id, not null: the pill's track-change push animation keys
            // on it — with null the detail pages never animated song changes.
            currentTrackId = miniPlayer?.trackId,
            currentTrackTitle = miniPlayer?.title,
            currentTrackArtist = miniPlayer?.artist,
            currentTrackCoverArtUrl = miniPlayer?.coverArtUrl,
            isPlaybackReady = true,
            connectionErrorMessage = null,
            playbackProgress = playbackProgress,
            isPlaying = miniPlayer?.isPlaying == true,
            chromeProgress = {
                (enterChromeProgress() * (1f - backMorphProgress())).coerceIn(0f, 1f)
            },
            playSplitActions = BarPlaySplitActions(
                playContainer = playContainer,
                playContent = playContent,
                onPlay = if (interactionsEnabled) onPlay else ({}),
                onShuffle = if (interactionsEnabled) onShuffle else ({}),
                menuItems = if (interactionsEnabled) menuItems else ({ _ -> }),
                // Same count while hidden: on the centred bar the extras set
                // the Play width, so dropping them would jump the bar on reveal.
                promotable = if (interactionsEnabled) promotable else promotable.map { BarExtraAction(it.icon, it.label) {} },
            ),
            onHomeClick = {},
            onNowPlayingClick = if (interactionsEnabled) onOpenNowPlaying else ({}),
            onLibraryClick = {},
        )
    }
}

/**
 * The bar's forward pose source for this window.
 *
 *  - [followShell] (FullChoreography): read the shell's live nav→split morph
 *    through the store, so the window's bar is the shell's twin from its
 *    first frame.
 *  - [inWindowMorph] (PlainPush over a visible shell bar — the centred bar on
 *    Medium): the shell never morphs, so this window's bar starts in the nav
 *    pose (pixel twin of the shell bar beneath, same tab, same pill) and
 *    morphs to the detail pose on its own spring the moment the page is
 *    revealed — the same beat as the 96dp content slide. Back scrubs it
 *    home again (the page's `backMorphProgress`), so the dissolve lands on
 *    the identical nav bar.
 *  - neither: settled in the detail pose (nested pushes, NP / Memories origins).
 */
@Composable
internal fun rememberDetailBarEnterProgress(
    followShell: Boolean,
    back: DetailBackCollapseState,
    inWindowMorph: Boolean = false,
    intro: DetailEnterIntroState? = null,
): () -> Float {
    val context = LocalContext.current
    val store = remember(context) {
        (context.applicationContext as YoinApplication).container.experienceSessionStore
    }
    // A restored detail after process death has no live shell hand-off. Nested
    // details, rail/split layouts and Now Playing origins also start settled.
    val hasShellHandoff = remember(store, followShell) {
        followShell && store.state.value.detailChromeActive
    }
    // In-window morph: one Animatable, driven once the page is on screen.
    // Starts settled when the window is restored (rotation) so it never replays.
    val morphInWindow = inWindowMorph && !hasShellHandoff
    var played by rememberSaveable { mutableStateOf(!morphInWindow) }
    val localMorph = remember { Animatable(if (played) 1f else 0f) }
    // The slow spatial token: the morph rides WITH the page's 96dp / 450ms
    // slide-in (the default token settles in ~150ms and reads as a snap).
    val spec = YoinMotion.slowSpatialSpec<Float>(role = YoinMotionRole.Standard)
    // It starts on the slide's own beat — once the page's first, heavy frame
    // has committed — not at reveal, while that frame is still being built
    // (device-measured: started at reveal, the spring's first two frames came
    // 163ms and 130ms apart and the morph jumped 0 → 0.9). A page settled
    // without a slide (timeout) still gets its detail pose.
    val pageVisible = intro?.let { it.slideReleased || (it.entranceResolved && it.pageVisible) } == true
    // A back gesture (or a button back's commit) arriving mid-morph freezes
    // the spring where it is — the scrub then runs from that pose, one driver
    // at a time, no snap to the full detail layout; a cancelled gesture
    // resumes it to the detail pose.
    LaunchedEffect(morphInWindow, pageVisible, played, back.gestureActive, back.committed) {
        if (!morphInWindow || played) return@LaunchedEffect
        if (back.gestureActive || back.committed) {
            localMorph.stop()
        } else if (pageVisible) {
            localMorph.animateTo(1f, spec)
            played = true
        }
    }
    return remember(store, hasShellHandoff, morphInWindow, back, localMorph) {
        {
            when {
                morphInWindow -> localMorph.value
                back.gestureActive || back.committed -> 1f
                hasShellHandoff -> store.shellBarChromeMorph.value
                else -> 1f
            }
        }
    }
}

/** Bars over Now Playing or Memories enter and retreat with the detail page. */
internal fun detailBarExitProgress(
    intro: DetailEnterIntroState,
    back: DetailBackCollapseState,
): Float {
    val entering = if (intro.pageVisible) intro.slide.value.coerceIn(0f, 1f) else 1f
    return entering + (1f - entering) * back.progress.coerceIn(0f, 1f)
}

/** Nested pushes retain the actual source surface and use only Compose motion. */
internal fun launchDetailFromDetail(context: Context, intent: Intent, fromNowPlaying: Boolean) {
    intent.putExtra(DETAIL_EXTRA_FROM_NOW_PLAYING, fromNowPlaying)
    stampDetailWindowKeys(context, intent)
    val options = ActivityOptions.makeCustomAnimation(
        context,
        R.anim.detail_bar_handoff_enter,
        R.anim.detail_bar_handoff_exit,
    )
    context.startActivity(intent, options.toBundle())
}

/**
 * Gives the detail being launched its own window key and records the key of the window it opens over (the
 * shell's, or the launching detail's), so its back reveals — and wakes — only that window
 * ([CoveredWindowAnimationGate]). Extras survive the Activity's recreation.
 */
private fun stampDetailWindowKeys(context: Context, intent: Intent) {
    val launcher = context.findActivityOrNull()
    val beneath = when {
        launcher is MainActivity -> CoveredWindowAnimationGate.ShellWindowKey
        else -> launcher?.intent?.getStringExtra(DETAIL_EXTRA_WINDOW_KEY) ?: CoveredWindowAnimationGate.AnyWindow
    }
    intent.putExtra(DETAIL_EXTRA_WINDOW_KEY, UUID.randomUUID().toString())
    intent.putExtra(DETAIL_EXTRA_BENEATH_KEY, beneath)
}

/** This detail window's key ([stampDetailWindowKeys]); a detail started any other way has none of its own. */
fun Intent.detailWindowKey(): String = getStringExtra(DETAIL_EXTRA_WINDOW_KEY) ?: UnkeyedDetailWindow

/** The key of the window this detail opened over; unknown → every covered window thaws on its back. */
fun Intent.detailBeneathWindowKey(): String =
    getStringExtra(DETAIL_EXTRA_BENEATH_KEY) ?: CoveredWindowAnimationGate.AnyWindow

private const val UnkeyedDetailWindow = "detail"

/** Compose 的 LocalContext 到宿主 Activity 的解包（ContextWrapper 链）。 */
tailrec fun Context.findActivityOrNull(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivityOrNull()
    else -> null
}

/**
 * How a detail Activity leaves the shell. On a Wide + tall window the shell
 * never launches one at all — the page opens in its detail column
 * (ui/navigation/pane); these modes cover every narrower window.
 *
 * INVARIANT：跨窗口 bar 编舞（[FullChoreography]）只在 shell 与 detail 窗口
 * 画同一套组几何、且 shell 将被完全覆盖时存在 —— 竖屏底栏（Compact、非
 * Tabletop）或手机横屏的分离式挖孔带（`YoinWindowInfo.hasChromeHandoff`）。
 * 居中底栏（Medium 整窗）和 Tabletop 选 [PlainPush]；Wide + 高窗的详情是
 * shell 里的列，不经过这里。shell 侧 armDetailChrome 的门控与这里一一对应。
 */
enum class DetailLaunchMode {
    /**
     * Compact shell、底部 bar 即将被完全覆盖：完整交接 —— fromShell /
     * fromNowPlaying + barHandoff extras + detail_bar_handoff_enter 的
     * 透明窗口 hold（shell bar 的 nav→split morph 先演），随后内容不透明推入。
     */
    FullChoreography,

    /**
     * Medium+ full-window shell: no nav-bar morph or bar hold. Preserve an
     * expanded Now Playing origin and let Compose own the page slide, just
     * as on compact windows (no second platform translation).
     */
    PlainPush,
}

/**
 * Launch a detail Activity from the shell (every window WITHOUT a detail
 * column — Wide + tall windows host details in-window instead).
 *
 *  - [DetailLaunchMode.FullChoreography] (Compact bar / edge capsules): the
 *    incoming window holds transparent while the shell bar morphs nav→split,
 *    then slides in opaque (res/anim/detail_bar_handoff_enter.xml); the shell
 *    arms its morph (detailChromeActive) first and the detail bar follows
 *    that same progress from its first frame.
 *  - [DetailLaunchMode.PlainPush] (the centred bar on Medium): no shell
 *    choreography. Over a visible bar the window's own bar morphs nav→detail
 *    in-window ([DETAIL_EXTRA_BAR_MORPH]); over Now Playing or Memories it
 *    rides the back gesture away instead ([DETAIL_EXTRA_FROM_NOW_PLAYING] /
 *    [DETAIL_EXTRA_FROM_MEMORIES]).
 *
 * Reads the session store at launch time to stamp the intent with the true
 * origin; the current shell tab rides along so the back preview highlights
 * the section the user actually left.
 */
fun launchDetailFromShell(
    context: Context,
    intent: Intent,
    mode: DetailLaunchMode = DetailLaunchMode.FullChoreography,
) {
    val session = (context.applicationContext as YoinApplication)
        .container.experienceSessionStore.state.value
    when (mode) {
        DetailLaunchMode.PlainPush -> {
            intent.putExtra(DETAIL_EXTRA_ORIGIN_SECTION, session.selectedSection.name)
            // The shell bar stays in its nav pose beneath this window, so the
            // window's bar plays the nav→detail morph itself (and scrubs it
            // back on return). Over an overlay there is no bar to meet: the
            // bar rides the back gesture away instead.
            if (!session.hasOverlayHidingBottomBar) {
                intent.putExtra(DETAIL_EXTRA_BAR_MORPH, true)
            } else if (!session.nowPlayingExpanded) {
                intent.putExtra(DETAIL_EXTRA_FROM_MEMORIES, true)
            }
            launchDetailFromDetail(context, intent, fromNowPlaying = session.nowPlayingExpanded)
        }

        DetailLaunchMode.FullChoreography -> {
            if (!session.hasOverlayHidingBottomBar) {
                intent.putExtra(DETAIL_EXTRA_FROM_SHELL, true)
                intent.putExtra(DETAIL_EXTRA_ORIGIN_SECTION, session.selectedSection.name)
            } else if (session.nowPlayingExpanded) {
                intent.putExtra(DETAIL_EXTRA_FROM_NOW_PLAYING, true)
            } else {
                intent.putExtra(DETAIL_EXTRA_FROM_MEMORIES, true)
            }
            intent.putExtra(DETAIL_EXTRA_BAR_HANDOFF, !session.hasOverlayHidingBottomBar)
            stampDetailWindowKeys(context, intent)
            val options = ActivityOptions.makeCustomAnimation(
                context,
                R.anim.detail_bar_handoff_enter,
                R.anim.detail_bar_handoff_exit,
            )
            context.startActivity(intent, options.toBundle())
        }
    }
}

/** [DETAIL_EXTRA_ORIGIN_SECTION] → [YoinSection], defaulting to HOME. */
fun Intent.detailOriginSection(): YoinSection =
    getStringExtra(DETAIL_EXTRA_ORIGIN_SECTION)
        ?.let { name -> YoinSection.entries.firstOrNull { it.name == name } }
        ?: YoinSection.HOME

/**
 * Detail Activities call this in onCreate: the CLOSE transition becomes an
 * in-place dissolve so the window's bar stays pixel-aligned over the bar
 * beneath it (shell or another detail page) — the bar reads as one fixed
 * element while only the page content fades. The shell's split→nav reverse
 * morph then plays in full view after the window settles. Pre-34 keeps the
 * system close animation (no per-gesture hook exists there).
 */
fun Activity.applyDetailCloseTransition() {
    if (Build.VERSION.SDK_INT >= 34) {
        overrideActivityTransition(
            Activity.OVERRIDE_TRANSITION_CLOSE,
            R.anim.detail_bar_close_enter,
            R.anim.detail_bar_close_exit,
        )
    }
}

/**
 * Set by [launchDetailFromShell]: this detail window sits directly over the
 * SHELL, so its predictive-back scrub should morph the bar toward nav
 * chrome. Detail→detail pushes lack it — the bar beneath is identical.
 */
/** This detail window's key, for the covered-window freeze ([CoveredWindowAnimationGate]). */
const val DETAIL_EXTRA_WINDOW_KEY = "detailWindowKey"

/** The key of the window this detail opened over. */
const val DETAIL_EXTRA_BENEATH_KEY = "detailBeneathKey"

const val DETAIL_EXTRA_FROM_SHELL = "fromShell"

/** Shell tab at launch time (enum name) — the back scrub's revealed selection. */
const val DETAIL_EXTRA_ORIGIN_SECTION = "originSection"

/**
 * Set when the page opened OVER the expanded Now Playing: the back reveal has
 * no bar, so the page's bar rides the back gesture down off-screen instead of
 * morphing (and never lingers over the player after the dissolve).
 */
const val DETAIL_EXTRA_FROM_NOW_PLAYING = "fromNowPlaying"

/**
 * Set on every launch that uses the bar hand-off window animation (200ms
 * transparent hold): the page's content slide-in delays to match. Absent on
 * detail→detail pushes, whose window appears immediately.
 */
const val DETAIL_EXTRA_BAR_HANDOFF = "barHandoff"

/** Memories remains mounted underneath; its hidden bar is not a morph target. */
const val DETAIL_EXTRA_FROM_MEMORIES = "fromMemories"

/**
 * Set on a plain push over a visible shell bar (the centred bar on Medium):
 * this window's bar morphs nav→detail in-window on reveal and scrubs back
 * on return. Unlike [DETAIL_EXTRA_FROM_SHELL] nothing is bridged to the shell.
 */
const val DETAIL_EXTRA_BAR_MORPH = "barMorph"

internal fun Intent.detailBarExitsOnBack(): Boolean =
    getBooleanExtra(DETAIL_EXTRA_FROM_NOW_PLAYING, false) ||
        getBooleanExtra(DETAIL_EXTRA_FROM_MEMORIES, false)
