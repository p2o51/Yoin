package com.gpo.yoin.ui.component

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.window.SurfaceSyncGroup
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Translucent detail windows retain the source bar beneath their own bar.
 * Their opaque fills can overlap safely; their translucent shadows cannot.
 * Only the frontmost overlapping host casts a shadow. Disjoint split panes
 * retain their own shadows.
 *
 * Two windows present frames independently, so a shadow swap between them is
 * frame-exact only when both frames commit together. The hand-off is
 * therefore arranged so that neither window's timing can show a gap or a
 * doubled shadow:
 *  - Open: while a detail window shows only its bar (the bar-hold), its bar
 *    stays bare and the shell's shadow shows through the transparent window.
 *    The detail's own shadow switches on in the SAME frame its opaque page
 *    appears (one window, so exact), which also hides the shell's shadow
 *    beneath the page. Only after that frame is on screen does the shell
 *    drop its now-covered shadow.
 *  - Close: the leaving window hands the shadow BACK before it finishes
 *    ([handBack]). Its page has already dissolved, so only its bar remains,
 *    over the identical bar beneath; the two windows swap shadows in one
 *    committed frame (API 34+), or crossfade them on one spring. The
 *    system's window dissolve then carries a bare bar over an identical one:
 *    nothing visible is left for it to animate. (Riding that dissolve
 *    instead meant phase-matching an app-clock fade to another process's
 *    animation; any drift showed as a doubled shadow, then a missing one.)
 *    A window that finishes without that choreography still fades the bars
 *    beneath back in from its release.
 */
class BottomBarShadowRegistry {
    private data class Bar(val host: Long, val bounds: Rect)
    private val bars = mutableStateMapOf<Any, Bar>()

    // Casting: the host draws its own bar shadow (its page covers what lies
    // beneath). Active: additionally on screen, so it hides the shadows of
    // overlapping bars beneath it. Pending hosts are neither.
    private val castingHosts = mutableStateMapOf<Long, Unit>()
    private val activeHosts = mutableStateMapOf<Long, Unit>()
    private val pendingHosts = mutableStateMapOf<Long, Unit>()
    private val releasedHosts = HashSet<Long>()
    private var nextHost = 0L

    // Per leaving host: the share of its shadow already handed back to the
    // overlapping bars beneath it (0..1), written only by [handBack].
    private val handedBack = mutableStateMapOf<Long, Float>()

    // Each host's window, so a hand-back can commit both windows' frames as one.
    private val hostViews = HashMap<Long, WeakReference<View>>()

    /** When the last host started leaving; bars beneath fade back in from here. */
    internal var lastReleaseUptimeMs by mutableLongStateOf(Long.MIN_VALUE / 2)
        private set

    internal fun newHost(): Long = (++nextHost).also { pendingHosts[it] = Unit }

    /** The host's page now covers the bars beneath it; draw its own shadow. */
    internal fun startCasting(host: Long) {
        if (host in releasedHosts) return
        castingHosts[host] = Unit
    }

    /** The host's casting frame is on screen: hide overlapping shadows beneath. */
    internal fun activate(host: Long) {
        if (host in releasedHosts) return
        castingHosts[host] = Unit
        pendingHosts.remove(host)
        activeHosts[host] = Unit
    }

    /**
     * The host's window is leaving (finishing) or gone, and stops hiding the
     * bars beneath. After a [handBack] its bars stay bare; otherwise they
     * keep their shadow, which dissolves with the window while the bars
     * beneath fade theirs back in. Idempotent: finishing reports once at
     * pause and again at disposal, and a second stamp would restart the
     * fade beneath mid-way.
     */
    internal fun release(host: Long) {
        if (!releasedHosts.add(host)) return
        if (host !in handedBack) lastReleaseUptimeMs = SystemClock.uptimeMillis()
        pendingHosts.remove(host)
        activeHosts.remove(host)
        castingHosts[host] = Unit
    }

    internal fun attachView(host: Long, view: View) {
        hostViews[host] = WeakReference(view)
    }

    internal fun detachView(host: Long) {
        hostViews.remove(host)
    }

    /** The hosts whose casting bars beneath [host] its own bars overlap. */
    private fun hostsBeneath(host: Long): Set<Long> {
        val own = bars.values.filter { it.host == host }
        return bars.values
            .filter { other ->
                other.host < host && other.host in castingHosts && own.any { it.bounds.overlaps(other.bounds) }
            }
            .mapTo(HashSet()) { it.host }
    }

    /**
     * Starts handing [host]'s shadow back to the casting bars beneath it
     * that its own bars overlap. False when there are none (e.g. a page over
     * Now Playing, whose bar is gone): the host keeps its shadow.
     */
    internal fun beginHandBack(host: Long): Boolean {
        if (host in releasedHosts || host in handedBack) return false
        if (hostsBeneath(host).isEmpty()) return false
        // The bars beneath rise by exactly the share handed back — never
        // through a release fade still in flight from an earlier window.
        lastReleaseUptimeMs = Long.MIN_VALUE / 2
        handedBack[host] = 0f
        return true
    }

    internal fun setHandBackShare(host: Long, share: Float) {
        if (host in handedBack) handedBack[host] = share.coerceIn(0f, 1f)
    }

    /**
     * Before [host]'s window finishes, moves its bar shadows onto the
     * identical bars beneath it. The two windows present frames
     * independently, so a swap drawn in one pass can still land a vsync
     * apart — a doubled shadow for a frame, or a missing one. On API 34+ both
     * windows' frames commit as ONE transaction ([SurfaceSyncGroup]), so the
     * swap is instant and exact. Otherwise (or if the windows can't be
     * synced) the share crossfades on [animationSpec]: both windows read it
     * in their draw layers, the shadows sum to one in every frame, and a
     * frame landing late misses by one small step rather than a whole
     * shadow. Returns once the bars beneath own the shadow, or at once if
     * nothing beneath overlaps.
     */
    internal suspend fun handBack(host: Long, animationSpec: AnimationSpec<Float>) {
        if (!beginHandBack(host)) return
        val ownView = hostViews[host]?.get()
        val windows = (listOf(host) + hostsBeneath(host)).map { hostViews[it]?.get() }
        when {
            ownView == null || null in windows -> Unit
            windows.distinct().size == 1 -> {
                // One window: any single frame is already atomic.
                setHandBackShare(host, 1f)
                return
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                swapInOneTransaction(windows.filterNotNull().distinct()) { setHandBackShare(host, 1f) } -> {
                // Hold the finish until the synced frame is on its way, so the
                // window dissolve starts from the bare bar.
                awaitFrameCommit(ownView)
                withFrameNanos { }
                return
            }
        }
        Animatable(handedBack[host] ?: 0f).animateTo(1f, animationSpec) { setHandBackShare(host, value) }
        setHandBackShare(host, 1f)
    }

    /** Hosts still waiting for their first on-screen frame (test hook). */
    internal val pendingHostCount: Int get() = pendingHosts.size

    internal fun isCasting(host: Long): Boolean = host in castingHosts

    internal fun place(token: Any, host: Long, bounds: Rect) {
        val bar = Bar(host, bounds)
        if (bars[token] != bar) bars[token] = bar
    }

    internal fun remove(token: Any) {
        bars.remove(token)
    }

    /** How much of its shadow the bar draws (0..1). Read in the draw layer. */
    internal fun shadowShare(token: Any): Float {
        // Not positioned yet: cast by default, so a lone bar never starts bare.
        val bar = bars[token] ?: return 1f
        // Not casting yet: defer to any on-screen bar it overlaps, above or
        // below; a lone bar still casts. A host handing back hides only by
        // the share it still holds.
        val casting = bar.host in castingHosts
        var hidden = 0f
        var beneath = false
        for (other in bars.values) {
            if (other.host == bar.host || !other.bounds.overlaps(bar.bounds)) continue
            if (other.host in activeHosts && (!casting || other.host > bar.host)) {
                hidden = max(hidden, 1f - (handedBack[other.host] ?: 0f))
            }
            if (other.host < bar.host && other.host in castingHosts && other.host !in handedBack) {
                beneath = true
            }
        }
        // Handing back: keep only what the bars beneath haven't taken yet.
        val kept = if (beneath) 1f - (handedBack[bar.host] ?: 0f) else 1f
        return (1f - hidden) * kept
    }

    internal fun ownsShadow(token: Any): Boolean = shadowShare(token) > 0f
}

private data class BottomBarShadowHost(val registry: BottomBarShadowRegistry, val order: Long)
private val LocalBottomBarShadowHost = staticCompositionLocalOf<BottomBarShadowHost?> { null }

/**
 * Remember the window's order even when its bar is removed by Now Playing.
 *
 * @param deferUntilPageCover the window first shows only its bar over the
 *   previous window's identical bar (detail bar-hold). Its bar stays bare
 *   until [BottomBarShadowPageCoverEffect] reports the opaque page is up.
 */
@Composable
internal fun ProvideBottomBarShadowHost(
    registry: BottomBarShadowRegistry?,
    deferUntilPageCover: Boolean = false,
    content: @Composable () -> Unit,
) {
    val host = remember(registry) {
        registry?.let { r ->
            BottomBarShadowHost(r, r.newHost()).also { if (!deferUntilPageCover) r.startCasting(it.order) }
        }
    }
    if (host != null) {
        val view = LocalView.current
        val activity = LocalContext.current.findActivity()
        LaunchedEffect(host) {
            awaitFrameCommit(view)
            if (deferUntilPageCover) {
                // Safety net only: a page that never reports still takes over.
                delay(PAGE_COVER_FALLBACK_MS)
                host.registry.startCasting(host.order)
                awaitFrameCommit(view)
            }
            withFrameNanos { }
            host.registry.activate(host.order)
        }
        DisposableEffect(host, view) {
            host.registry.attachView(host.order, view)
            onDispose { host.registry.detachView(host.order) }
        }
        DisposableEffect(host, activity) {
            val lifecycle = (activity as? LifecycleOwner)?.lifecycle
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE && activity?.isFinishing == true) {
                    host.registry.release(host.order)
                }
            }
            lifecycle?.addObserver(observer)
            onDispose {
                lifecycle?.removeObserver(observer)
                host.registry.release(host.order)
            }
        }
    }
    CompositionLocalProvider(LocalBottomBarShadowHost provides host, content = content)
}

/**
 * Call from INSIDE the conditionally mounted, opaque page subtree of a
 * [ProvideBottomBarShadowHost] with `deferUntilPageCover`. The host's bar
 * starts casting in the very frame the page is first drawn; once that frame
 * is on screen the bars beneath (now covered) drop their shadows.
 */
@Composable
internal fun BottomBarShadowPageCoverEffect() {
    val host = LocalBottomBarShadowHost.current ?: return
    val view = LocalView.current
    // SideEffect applies before this frame draws: same-frame as the page.
    SideEffect { host.registry.startCasting(host.order) }
    LaunchedEffect(host) {
        awaitFrameCommit(view)
        withFrameNanos { }
        host.registry.activate(host.order)
    }
}

/**
 * For a window about to finish over the identical bar it covered (a detail
 * page's back commit): suspends while this window's bar shadows move onto
 * the bars beneath ([BottomBarShadowRegistry.handBack]). Call once its page
 * has dissolved and before `finish()`.
 */
@Composable
internal fun rememberBottomBarShadowHandBack(): suspend (AnimationSpec<Float>) -> Unit {
    val host = LocalBottomBarShadowHost.current
    return remember(host) {
        { animationSpec -> host?.registry?.handBack(host.order, animationSpec) }
    }
}

@Composable
internal fun Modifier.bottomBarShadow(shape: Shape, elevation: Dp): Modifier {
    val host = LocalBottomBarShadowHost.current
    val token = remember { Any() }
    val view = LocalView.current
    val screenLocation = remember { IntArray(2) }
    val windowLocation = remember { IntArray(2) }
    DisposableEffect(host, token) {
        onDispose { host?.registry?.remove(token) }
    }
    // The share is read in the draw layer, so a hand-off lands in the same
    // frame as whatever triggered it — in every window reading it. The
    // fallback timed case: a bar hidden by a window above that leaves
    // WITHOUT handing back (it finished outside the back choreography) fades
    // back in while that window's shadow dissolves with its close animation.
    // The fade runs the same curve in reverse from the release time
    // (computed at draw, not started by a recomposition a few frames late),
    // so the two sum to about one shadow when the platform starts its
    // dissolve on time; the choreographed close never depends on that.
    val returning = remember { mutableStateOf(false) }
    val frameTick = remember { mutableIntStateOf(0) }
    LaunchedEffect(host, token) {
        if (host == null) return@LaunchedEffect
        snapshotFlow { host.registry.isCasting(host.order) && !host.registry.ownsShadow(token) }
            .collect { suppressed ->
                if (suppressed) {
                    returning.value = true
                } else if (returning.value) {
                    // Keep redrawing until the fade is done; alpha itself is
                    // computed from the clock in the draw layer.
                    val start = host.registry.lastReleaseUptimeMs
                    while (SystemClock.uptimeMillis() - start < CLOSE_DISSOLVE_MS) {
                        withFrameNanos { frameTick.intValue++ }
                    }
                    returning.value = false
                }
            }
    }
    val placement = if (host == null) {
        Modifier
    } else {
        Modifier.onGloballyPositioned { coordinates ->
            // Compare actual display positions, not pane-local coordinates:
            // two Activity Embedding panes may both have a local x of zero.
            view.getLocationOnScreen(screenLocation)
            view.getLocationInWindow(windowLocation)
            val bounds = coordinates.boundsInWindow().translate(
                Offset(
                    (screenLocation[0] - windowLocation[0]).toFloat(),
                    (screenLocation[1] - windowLocation[1]).toFloat(),
                ),
            )
            host.registry.place(token, host.order, bounds)
        }
    }
    return then(placement).graphicsLayer {
        this.shape = shape
        clip = false
        // Read in the draw layer: a hand-off redraws, never relayouts.
        val share = host?.registry?.shadowShare(token) ?: 1f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            frameTick.intValue // redraw every frame while returning
            val alpha = if (returning.value && host != null && share > 0f) {
                val elapsed = SystemClock.uptimeMillis() - host.registry.lastReleaseUptimeMs
                share * FastOutSlowInEasing.transform((elapsed / CLOSE_DISSOLVE_MS.toFloat()).coerceIn(0f, 1f))
            } else {
                share
            }
            shadowElevation = if (alpha > 0.001f) elevation.toPx() else 0f
            ambientShadowColor = Color.Black.copy(alpha = alpha)
            spotShadowColor = Color.Black.copy(alpha = alpha)
        } else {
            // Shadow colours need API 28; older devices step instead of fade,
            // both sides of a hand-back crossing at the same share.
            shadowElevation = if (share >= 0.5f) elevation.toPx() else 0f
        }
    }
}

/**
 * Applies [swap] so that every window in [windows] presents it in the same
 * frame: each window's next frame is captured into one [SurfaceSyncGroup]
 * and the group commits them as a single transaction. False if a window
 * can't join, in which case [swap] has not run.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private fun swapInOneTransaction(windows: List<View>, swap: () -> Unit): Boolean {
    val roots = windows.map { it.rootSurfaceControl ?: return false }
    val group = SurfaceSyncGroup("YoinBottomBarShadow")
    var swapped = false
    if (roots.dropLast(1).all { group.add(it, null) }) {
        group.add(roots.last()) {
            swap()
            // Invalidate the shadow layers now, while every window is paused
            // for the sync, so the frames the group captures are the swapped ones.
            Snapshot.sendApplyNotifications()
            swapped = true
        }
    }
    // Always release the group: windows that joined draw on normally.
    group.markSyncReady()
    return swapped
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private suspend fun awaitFrameCommit(view: View) {
    withTimeoutOrNull(FRAME_COMMIT_TIMEOUT_MS) {
        if (!view.isAttachedToWindow) return@withTimeoutOrNull
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !view.isHardwareAccelerated) {
            suspendCancellableCoroutine { continuation ->
                view.postOnAnimation { if (continuation.isActive) continuation.resume(Unit) }
            }
            return@withTimeoutOrNull
        }
        suspendCancellableCoroutine { continuation ->
            val observer = view.viewTreeObserver
            if (!observer.isAlive) {
                continuation.resume(Unit)
                return@suspendCancellableCoroutine
            }
            val callback = Runnable { if (continuation.isActive) continuation.resume(Unit) }
            continuation.invokeOnCancellation {
                if (observer.isAlive) runCatching { observer.unregisterFrameCommitCallback(callback) }
            }
            try {
                observer.registerFrameCommitCallback(callback)
                view.postInvalidateOnAnimation()
            } catch (_: IllegalStateException) {
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }
}

/** Bounds the first-frame wait so a stalled window still takes over. */
private const val FRAME_COMMIT_TIMEOUT_MS = 1_500L

/** Must match res/anim/detail_bar_close_exit.xml (duration + fast_out_slow_in). */
private const val CLOSE_DISSOLVE_MS = 220

/** Longest a deferring host waits for its page before casting anyway. */
private const val PAGE_COVER_FALLBACK_MS = 2_000L
