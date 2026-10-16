package com.gpo.yoin.player.applemusic

import android.os.Bundle
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Apple Music's session callback hands the shared quick actions their
 * commands (owner 2026-10-15: no shuffle in the system controls): every
 * MediaSession.Callback member is a Java default method, which Kotlin's `by`
 * delegation never forwards, so each one the quick actions answer must be.
 */
@RunWith(RobolectricTestRunner::class)
class CatalogItemsCallbackTest {

    private val session = mockk<MediaSession>(relaxed = true)
    private val controller = mockk<MediaSession.ControllerInfo>(relaxed = true)

    @Test
    fun should_offerTheQuickActionsCommands_when_aControllerConnects() {
        val quickActions = mockk<MediaSession.Callback>()
        val accepted = mockk<MediaSession.ConnectionResult>()
        every { quickActions.onConnect(session, controller) } returns accepted

        val result = CatalogItemsCallback(quickActions).onConnect(session, controller)

        assertSame(accepted, result)
        verify(exactly = 1) { quickActions.onConnect(session, controller) }
    }

    @Test
    fun should_runTheQuickAction_when_aControllerSendsItsCommand() {
        val quickActions = mockk<MediaSession.Callback>()
        val command = SessionCommand("com.gpo.yoin.session.SHUFFLE", Bundle.EMPTY)
        val done = Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        every { quickActions.onCustomCommand(session, controller, command, any()) } returns done

        val result = CatalogItemsCallback(quickActions).onCustomCommand(session, controller, command, Bundle.EMPTY)

        assertSame(done, result)
        verify(exactly = 1) { quickActions.onCustomCommand(session, controller, command, any()) }
    }
}
