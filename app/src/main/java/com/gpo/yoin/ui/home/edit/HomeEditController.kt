package com.gpo.yoin.ui.home.edit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.gpo.yoin.ui.component.BarEditLeftSlot
import com.gpo.yoin.ui.component.resolveEditLeftSlot
import com.gpo.yoin.ui.experience.ExperienceSessionStore
import com.gpo.yoin.ui.experience.HomeEditProgress
import com.gpo.yoin.ui.experience.HomeSurface
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.home.HomeEditSessionHints
import com.gpo.yoin.ui.home.HomeLayout
import com.gpo.yoin.ui.home.HomeRowPreset
import com.gpo.yoin.ui.home.HomeSection
import com.gpo.yoin.ui.home.HomeViewModel
import com.gpo.yoin.ui.navigation.YoinSection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred

/** What the Home layer plugs in while composed. All calls are synchronous, on the main thread. */
interface HomeEditLayer {
    /** A carry session is in Drag, Settle or Unfold. */
    val isCarrying: Boolean

    /** Reset the session visuals: kicked set, ripple origin, envelope, tray in. */
    fun onEnter(origin: HomeSection?, lifted: Boolean)

    /** An animated exit started: tray out, then footer in; envelope to 0. */
    fun onExit(reason: HomeEditExitReason)

    /** Snap every Home-layer value to rest. */
    fun onSnapExit()

    /** A bar click: envelope back to 1, idle timer reset. */
    fun onTouch()

    /** Animate one applied edit (hide, show, reset, undo, move, order). */
    fun onLayoutChange(change: HomeEditChange)

    /** Exit asked mid-carry: release as cancelled; the carry's finish calls [HomeEditController.finishDeferredExit]. */
    fun deferExit(reason: HomeEditExitReason)

    /** A snap exit mid-carry: commit a changed order now, then tear the carry down. */
    fun abortCarry()

    /** The bar's Add. */
    fun scrollToTray()
}

/**
 * Home edit mode's shell-level owner: the only writer of [HomeSurface.Edit],
 * of P ([progress]) and of the session (draft, undo, echo hold, hints).
 * Hoisted in the shell so the bar, back and the exit triggers reach it; the
 * Home layer registers itself as [layer] while composed.
 *
 * Edits apply to [draft] and persist one by one; entering and exiting never
 * write. After an exit the draft is held as the rendered layout until the
 * view model's layout catches up with it ([layoutToRender]), so the feed
 * never flashes the pre-write layout.
 */
@Stable
class HomeEditController(
    private val store: ExperienceSessionStore,
    private val scope: CoroutineScope,
    private val applyLayout: (HomeLayout) -> Unit,
    private val onEditingChanged: (Boolean) -> Unit,
    private val startSession: () -> HomeEditSessionHints,
    private val feedback: HomeEditFeedback,
    private val reducedMotion: () -> Boolean,
) {
    val progress: HomeEditProgress = store.homeEditProgress

    /** P for draw and layout lambdas. One instance for the controller's life. */
    val progressReader: () -> Float = { progress.value }

    /** Snapshot mirror of `homeSurface == Edit`. */
    var isEditing by mutableStateOf(false)
        private set

    /** This session's layout; null outside edit mode. */
    var draft by mutableStateOf<HomeLayout?>(null)
        private set

    // The last exited draft, rendered until the view model's layout matches it.
    private var echoHold by mutableStateOf<HomeLayout?>(null)
    private var latestVmLayout by mutableStateOf<HomeLayout?>(null)

    private val undoStack = mutableStateListOf<HomeLayout>()

    val undoDepth: Int get() = undoStack.size

    /** Hidden sections of the layout on screen (it outlives the draft through the exit fade). */
    val trayCount: Int get() = (draft ?: echoHold ?: latestVmLayout)?.hiddenSections?.size ?: 0

    val canReset: Boolean get() = draft?.isDefault == false

    private val leftSlotState = derivedStateOf { resolveEditLeftSlot(undoDepth, trayCount) }

    /** What the bar's left slot offers. */
    val leftSlot: BarEditLeftSlot get() = leftSlotState.value

    var sessionHints by mutableStateOf(HomeEditSessionHints())
        private set

    /**
     * Set and cleared by Home while composed. A layer that leaves while an
     * exit waits on its carry hands that exit over, so it still lands.
     */
    var layer: HomeEditLayer? = null
        set(value) {
            val previous = field
            field = value
            if (previous != null && previous !== value) deferredExitReason?.let(::finishDeferredExit)
        }

    private var serial = 0
    private var deferredExitReason: HomeEditExitReason? = null
    private var exitCompletion: CompletableDeferred<Unit>? = null

    // Recreated while editing: the next VM layout seeds the lost draft.
    private var healDraft = false

    /** Completes when an exit deferred by a carry lands; null when none is waiting. */
    val deferredExit: Deferred<Unit>? get() = exitCompletion

    init {
        // Activity recreation keeps the store (and its surface) but not this
        // controller: put P where the surface says, without animating.
        val editing = store.state.value.homeSurface == HomeSurface.Edit
        progress.snapTo(if (editing) 1f else 0f)
        if (editing) {
            isEditing = true
            healDraft = true
            onEditingChanged(true)
        }
    }

    /** Clears [layer] only while it is still [layer]: two Home instances can overlap in a section fade. */
    fun detachLayer(layer: HomeEditLayer) {
        if (this.layer === layer) this.layer = null
    }

    /** The layout Home renders: the draft, then the echo hold, then [vmLayout]. */
    fun layoutToRender(vmLayout: HomeLayout): HomeLayout = draft ?: echoHold ?: vmLayout

    /** The view model's layout arrived (persisted or reconciled). */
    fun onVmLayout(layout: HomeLayout) {
        latestVmLayout = layout
        if (echoHold?.sameSectionsAs(layout) == true) echoHold = null
        if (healDraft) {
            healDraft = false
            if (isEditing && draft == null) draft = layout
        }
    }

    /** The draft and echo belong to the old profile: snap out and forget them. */
    fun onProfileSwitched() {
        snapExit()
        echoHold = null
    }

    /** Enters edit mode. A no-op unless Home shows its feed. */
    fun enter(origin: HomeSection?, lifted: Boolean) {
        val session = store.state.value
        if (isEditing || session.homeSurface != HomeSurface.Feed || session.selectedSection != YoinSection.HOME) {
            return
        }
        draft = echoHold ?: latestVmLayout ?: HomeLayout.Default
        undoStack.clear()
        isEditing = true
        store.setHomeSurface(HomeSurface.Edit)
        onEditingChanged(true)
        sessionHints = startSession()
        progress.animateTo(scope, 1f, homeEditStageSpec(reducedMotion()))
        layer?.onEnter(origin, lifted)
    }

    /** Animated exit. Mid-carry it waits for the carry to finish (see [finishDeferredExit]). */
    fun commitAndExit(reason: HomeEditExitReason = HomeEditExitReason.Done) {
        if (!isEditing) return
        val current = layer
        if (current?.isCarrying == true) {
            deferredExitReason = reason
            if (exitCompletion == null) exitCompletion = CompletableDeferred()
            current.deferExit(reason)
            return
        }
        exit(reason)
    }

    /** [commitAndExit], then suspends until P has settled at 0 (a deferred exit included). */
    suspend fun commitAndExitAndAwait(reason: HomeEditExitReason = HomeEditExitReason.Programmatic) {
        commitAndExit(reason)
        exitCompletion?.await()
        progress.awaitSettled()
    }

    /** The carry finished with an exit pending. */
    fun finishDeferredExit(reason: HomeEditExitReason) {
        if (isEditing) exit(reason) else completeExit()
    }

    /** Leaves edit mode in this call: surface Feed and P 0 together. */
    fun snapExit() = snapOut(writeSurface = true)

    /** Something else replaced the Edit surface: reset everything without writing it back. */
    fun onSurfaceLost() = snapOut(writeSurface = false)

    fun touch() {
        layer?.onTouch()
    }

    fun hide(section: HomeSection): Boolean =
        change(HomeEditChangeKind.Hide, section, haptic = { feedback.toggle(false) }) {
            it.withEnabled(section, false)
        }

    fun show(section: HomeSection): Boolean =
        change(HomeEditChangeKind.Show, section, haptic = { feedback.toggle(true) }) {
            it.withEnabled(section, true)
        }

    fun reset(): Boolean = change(HomeEditChangeKind.Reset, haptic = feedback::reject) { it.reset() }

    fun undo(): Boolean {
        val current = draft ?: return false
        if (layer?.isCarrying == true || undoStack.isEmpty()) return false
        feedback.click()
        apply(HomeEditChangeKind.Undo, current, undoStack.removeAt(undoStack.lastIndex), subject = null)
        return true
    }

    /** One step up (-1) or down (+1) in the enabled order (TalkBack). */
    fun move(section: HomeSection, delta: Int): Boolean =
        change(HomeEditChangeKind.Move, section, haptic = feedback::segmentTick) { layout ->
            val enabled = layout.enabledSections
            val from = enabled.indexOf(section)
            val to = from + delta
            if (from < 0 || to !in enabled.indices) layout else layout.moved(section, to)
        }

    /**
     * [section] at row preset [rows] (D1). Persisted at once, one Undo step;
     * no haptic of its own — the resize plays CONFIRM on a drop, SEGMENT_TICK
     * on a key or TalkBack step. False when nothing changed.
     */
    fun setRows(section: HomeSection, rows: HomeRowPreset): Boolean =
        change(HomeEditChangeKind.Rows, section, haptic = {}) { it.withRows(section, rows) }

    /** A carry dropped. Confirms only when the order changed; runs mid-carry by design. */
    fun commitOrder(enabledOrder: List<HomeSection>): Boolean =
        change(HomeEditChangeKind.Order, haptic = feedback::confirm, duringCarry = true) {
            it.withEnabledOrder(enabledOrder)
        }

    /** Undo, Add (scroll to the tray) or nothing for the dimmed Undo. Haptics are the controller's, not the bar's. */
    fun barLeftSlotClick() {
        touch()
        if (!isEditing || layer?.isCarrying == true) return
        when (leftSlot) {
            BarEditLeftSlot.Undo -> undo()
            BarEditLeftSlot.Add -> {
                feedback.click()
                layer?.scrollToTray()
            }
            BarEditLeftSlot.UndoDisabled -> Unit
        }
    }

    fun barDone() {
        touch()
        commitAndExit(HomeEditExitReason.Done)
    }

    private fun exit(reason: HomeEditExitReason) {
        if (reason == HomeEditExitReason.Done) feedback.confirm()
        isEditing = false
        if (store.state.value.homeSurface == HomeSurface.Edit) store.setHomeSurface(HomeSurface.Feed)
        progress.animateTo(scope, 0f, homeEditStageSpec(reducedMotion()))
        endSession()
        layer?.onExit(reason)
        completeExit()
    }

    private fun snapOut(writeSurface: Boolean) {
        val wasEditing = isEditing
        if (!wasEditing && progress.value == 0f && !progress.isAnimating) {
            completeExit()
            return
        }
        // Commits a changed order while the draft still exists.
        if (wasEditing) layer?.abortCarry()
        isEditing = false
        if (writeSurface && store.state.value.homeSurface == HomeSurface.Edit) {
            store.setHomeSurface(HomeSurface.Feed)
        }
        progress.snapTo(0f)
        if (wasEditing) endSession()
        layer?.onSnapExit()
        completeExit()
    }

    private fun endSession() {
        deferredExitReason = null
        healDraft = false
        draft?.let { last ->
            // Unchanged from the VM's latest: no write, so no echo will ever come.
            echoHold = if (latestVmLayout?.sameSectionsAs(last) == true) null else last
        }
        draft = null
        undoStack.clear()
        onEditingChanged(false)
    }

    private fun completeExit() {
        deferredExitReason = null
        exitCompletion?.complete(Unit)
        exitCompletion = null
    }

    private fun change(
        kind: HomeEditChangeKind,
        subject: HomeSection? = null,
        haptic: () -> Unit,
        duringCarry: Boolean = false,
        transform: (HomeLayout) -> HomeLayout,
    ): Boolean {
        val current = draft ?: return false
        if (!duringCarry && layer?.isCarrying == true) return false
        val next = transform(current)
        if (next === current) return false
        haptic()
        undoStack.add(current)
        if (undoStack.size > HomeEditTokens.UndoMax) undoStack.removeAt(0)
        apply(kind, current, next, subject)
        return true
    }

    private fun apply(kind: HomeEditChangeKind, previous: HomeLayout, next: HomeLayout, subject: HomeSection?) {
        draft = next
        applyLayout(next)
        layer?.onLayoutChange(HomeEditChange(++serial, kind, previous, next, subject))
    }
}

/** The shell's controller, writing through [viewModel]. Call once, in the shell. */
@Composable
fun rememberHomeEditController(store: ExperienceSessionStore, viewModel: HomeViewModel): HomeEditController {
    val scope = rememberCoroutineScope()
    val haptics = rememberYoinHaptics()
    val reduced by rememberUpdatedState(rememberHomeEditReducedMotion())
    return remember(store, viewModel) {
        HomeEditController(
            store = store,
            scope = scope,
            applyLayout = viewModel::setHomeLayout,
            onEditingChanged = viewModel::setEditing,
            startSession = viewModel::onEditSessionStarted,
            feedback = haptics.asHomeEditFeedback(),
            reducedMotion = { reduced },
        ).apply { onVmLayout(viewModel.homeLayout.value) }
    }
}

/**
 * Previews, the debug harness, androidTests: own [ExperienceSessionStore] and
 * an in-memory layout (an applied layout lives on in the echo hold, which no
 * view model ever clears). No freeze; the header hint always shows.
 */
@Composable
fun rememberStandaloneHomeEditController(initial: HomeLayout = HomeLayout.Default): HomeEditController {
    val scope = rememberCoroutineScope()
    val haptics = rememberYoinHaptics()
    val reduced by rememberUpdatedState(rememberHomeEditReducedMotion())
    val controller = remember {
        HomeEditController(
            store = ExperienceSessionStore(),
            scope = scope,
            applyLayout = {},
            onEditingChanged = {},
            startSession = { HomeEditSessionHints(showHeaderHint = true) },
            feedback = haptics.asHomeEditFeedback(),
            reducedMotion = { reduced },
        ).apply { onVmLayout(initial) }
    }
    LaunchedEffect(controller, initial) { controller.onVmLayout(initial) }
    return controller
}
