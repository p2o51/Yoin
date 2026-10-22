package com.gpo.yoin.ui.detail

import androidx.activity.compose.BackHandler
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.ui.experience.DetailBackPhase
import com.gpo.yoin.ui.theme.YoinTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PlaylistDetailExitRequestTest {
    @get:Rule val rule = createComposeRule()

    @Test fun should_settlePageBackBeforeLeaving_whenPlaylistIsDeleted() {
        val store = ApplicationProvider.getApplicationContext<YoinApplication>()
            .container.experienceSessionStore
        store.setDetailChromeActive(true)
        store.detailBackPhase.value = DetailBackPhase.Idle
        store.detailBackProgress.floatValue = 0f
        val deleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var finishes = 0
        var overlayBacks = 0
        rule.mainClock.autoAdvance = false
        rule.setContent {
            YoinTheme {
                PlaylistDetailScreen(
                    uiState = PlaylistDetailUiState.Loading,
                    onBackClick = { error("The page must use its back-collapse controller") },
                    onLeavePage = { finishes++ },
                    leavePageRequests = deleted,
                    onPlayAllClick = {},
                    onSongClick = {},
                    onRetry = {},
                    morphBarOnBack = true,
                    bridgeBackToShell = true,
                )
                // Like Now Playing: a later window-back handler must not take
                // the successful deletion's request to leave the playlist.
                BackHandler { overlayBacks++ }
            }
        }
        rule.runOnIdle { assertTrue(deleted.tryEmit(Unit)) }
        rule.mainClock.advanceTimeBy(48)
        rule.runOnIdle {
            assertEquals(0, finishes)
            assertEquals(0, overlayBacks)
            assertEquals(DetailBackPhase.Committed, store.detailBackPhase.value)
            assertFalse(store.state.value.detailChromeActive)
            // A repeated outcome during the settle must not finish twice.
            assertTrue(deleted.tryEmit(Unit))
        }
        rule.mainClock.advanceTimeBy(1_500)
        rule.runOnIdle {
            assertEquals(1, finishes)
            assertEquals(0, overlayBacks)
            store.detailBackPhase.value = DetailBackPhase.Idle
        }
    }
}
