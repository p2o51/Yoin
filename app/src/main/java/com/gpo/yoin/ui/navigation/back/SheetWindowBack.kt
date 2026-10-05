package com.gpo.yoin.ui.navigation.back

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.NavigationEventHandler
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.findViewTreeNavigationEventDispatcherOwner

/*
 * System back for a sheet — an M3 ModalBottomSheet, which is its own dialog
 * window — opened from a subtree that PROVIDES LocalNavigationEventDispatcherOwner.
 * The Wide detail column does (YoinNavHost: its gated child dispatcher, see
 * ShellBackResolver). A sheet's composition inherits that owner, so M3's own
 * back handler lands on the SHELL window's dispatcher, while system back goes to
 * the focused window — the sheet's — which then has no handler (the sheet
 * dialog's cancel() is a no-op): back and the back gesture did nothing on the
 * album column's sheets (device QA 2026-10-05, Pixel Tablet landscape).
 *
 * The bridge gives the sheet a dispatcher of its own (M3 registers there) and,
 * from inside the sheet's window, forwards every phase of that window's back
 * into it — so the sheet's own predictive shrink still follows the finger and
 * a commit still dismisses it. Where nothing is provided (an Activity-hosted
 * page) it changes nothing: the sheet's window is the input either way.
 */

/** A sheet's own back dispatcher, fed from the sheet's window by [SheetWindowBackInput]. */
@Stable
class SheetBackBridge internal constructor() : NavigationEventDispatcherOwner {
    private val input = DirectNavigationEventInput()

    override val navigationEventDispatcher: NavigationEventDispatcher =
        NavigationEventDispatcher().apply { addInput(input) }

    /** Registered on the sheet window's dispatcher: hands each back phase to [navigationEventDispatcher]. */
    internal fun forwarder(): NavigationEventHandler<NavigationEventInfo.None> =
        object : NavigationEventHandler<NavigationEventInfo.None>(NavigationEventInfo.None, true) {
            override fun onBackStarted(event: NavigationEvent) = input.backStarted(event)

            override fun onBackProgressed(event: NavigationEvent) = input.backProgressed(event)

            override fun onBackCompleted() = input.backCompleted()

            override fun onBackCancelled() = input.backCancelled()
        }

    internal fun dispose() = navigationEventDispatcher.dispose()
}

@Composable
fun rememberSheetBackBridge(): SheetBackBridge {
    val bridge = remember { SheetBackBridge() }
    DisposableEffect(bridge) { onDispose { bridge.dispose() } }
    return bridge
}

/** Around the sheet call: the sheet's own back handler registers on [bridge]. */
@Composable
fun ProvideSheetBack(bridge: SheetBackBridge, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides bridge, content = content)
}

/** First thing inside the sheet's content (its own window): forwards that window's back into [bridge]. */
@Composable
fun SheetWindowBackInput(bridge: SheetBackBridge) {
    val window = LocalView.current.findViewTreeNavigationEventDispatcherOwner()
    DisposableEffect(window, bridge) {
        if (window == null || window === bridge) return@DisposableEffect onDispose {}
        val forward = bridge.forwarder()
        window.navigationEventDispatcher.addHandler(forward)
        onDispose { forward.remove() }
    }
}
