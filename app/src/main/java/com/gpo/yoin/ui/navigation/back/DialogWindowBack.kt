package com.gpo.yoin.ui.navigation.back

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventHandler
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.findViewTreeNavigationEventDispatcherOwner

/*
 * Predictive back for a Compose Dialog's own window. A dialog is a window of
 * its own: system back goes to IT, but a back handler composed inside the
 * dialog registers on the Activity's dispatcher (the composition inherits the
 * Activity's owner) — the same trap as SheetWindowBack. This registers on the
 * dialog window's dispatcher directly, so the gesture's progress reaches the
 * dialog. Pass `dismissOnBackPress = false` to the dialog so its own
 * all-or-nothing back doesn't take the event first.
 *
 * Callers own the motion: snap a preview to the eased progress in
 * [onProgress] (YoinMotion.backGestureEasing), run the close on [onCommitted],
 * spring the preview home on [onCancelled] (invariants 2, 7). A button back
 * (3-button nav, a11y) arrives as a bare commit with no progress (invariant 8).
 */
@Composable
fun DialogWindowPredictiveBack(
    enabled: Boolean,
    onProgress: (NavigationEvent) -> Unit,
    onCommitted: () -> Unit,
    onCancelled: () -> Unit,
) {
    val owner = LocalView.current.findViewTreeNavigationEventDispatcherOwner()
    val progress by rememberUpdatedState(onProgress)
    val committed by rememberUpdatedState(onCommitted)
    val cancelled by rememberUpdatedState(onCancelled)
    val handler = androidx.compose.runtime.remember {
        object : NavigationEventHandler<NavigationEventInfo.None>(NavigationEventInfo.None, enabled) {
            override fun onBackStarted(event: NavigationEvent) = progress(event)

            override fun onBackProgressed(event: NavigationEvent) = progress(event)

            override fun onBackCompleted() = committed()

            override fun onBackCancelled() = cancelled()
        }
    }
    handler.isBackEnabled = enabled
    DisposableEffect(owner, handler) {
        if (owner == null) return@DisposableEffect onDispose {}
        owner.navigationEventDispatcher.addHandler(handler)
        onDispose { handler.remove() }
    }
}
