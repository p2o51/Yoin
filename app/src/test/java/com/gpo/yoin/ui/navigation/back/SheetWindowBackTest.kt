package com.gpo.yoin.ui.navigation.back

import androidx.activity.ComponentDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.NavigationEventHandler
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.gpo.yoin.ui.component.YoinModalBottomSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowDialog

/**
 * A sheet opened inside a subtree that provides its own back dispatcher (the Wide detail column)
 * must still close on system back. Device QA 2026-10-05 (Pixel Tablet, landscape): the album
 * column's Rate & comment sheet and page 2's notes / About sheets ignored back — M3 registered its
 * handler on the column's dispatcher (shell window) while back went to the sheet's own window.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
class SheetWindowBackTest {

    @get:Rule
    val rule = createComposeRule()

    /** Stands in for the column's gated child dispatcher (any provided, foreign owner). */
    private val columnOwner = object : NavigationEventDispatcherOwner {
        override val navigationEventDispatcher = NavigationEventDispatcher()
    }

    @Test
    fun should_dismissSheet_when_backReachesItsWindowUnderAProvidedOwner() {
        var open by mutableStateOf(true)
        var dismissed = false
        rule.setContent {
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides columnOwner) {
                if (open) {
                    YoinModalBottomSheet(onDismissRequest = {
                        dismissed = true
                        open = false
                    }) { Text("sheet") }
                }
            }
        }
        rule.waitForIdle()

        rule.runOnUiThread { (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressed() }
        rule.waitForIdle()

        assertTrue("back on the sheet's window must dismiss it", dismissed)
    }

    @Test
    fun should_ignoreBack_when_rawSheetInheritsAProvidedOwner() {
        // The root cause, pinned: a raw M3 sheet under a provided owner never hears its window's back.
        var dismissed = false
        rule.setContent {
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides columnOwner) {
                ModalBottomSheet(onDismissRequest = { dismissed = true }) { Text("sheet") }
            }
        }
        rule.waitForIdle()

        rule.runOnUiThread { (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressed() }
        rule.waitForIdle()

        assertFalse(dismissed)
    }

    @Test
    fun should_forwardEveryBackPhaseInOrder_when_bridgeIsFedFromTheWindow() {
        val bridge = SheetBackBridge()
        val window = NavigationEventDispatcher()
        val windowInput = DirectNavigationEventInput()
        window.addInput(windowInput)
        window.addHandler(bridge.forwarder())
        val seen = mutableListOf<String>()
        bridge.navigationEventDispatcher.addHandler(
            object : NavigationEventHandler<NavigationEventInfo.None>(NavigationEventInfo.None, true) {
                override fun onBackStarted(event: NavigationEvent) {
                    seen += "started"
                }

                override fun onBackProgressed(event: NavigationEvent) {
                    seen += "progress ${event.progress}"
                }

                override fun onBackCompleted() {
                    seen += "completed"
                }

                override fun onBackCancelled() {
                    seen += "cancelled"
                }
            },
        )

        windowInput.backStarted(NavigationEvent(progress = 0f))
        windowInput.backProgressed(NavigationEvent(progress = 0.5f))
        windowInput.backCancelled()
        windowInput.backStarted(NavigationEvent(progress = 0f))
        windowInput.backCompleted()

        assertEquals(listOf("started", "progress 0.5", "cancelled", "started", "completed"), seen)
    }
}
