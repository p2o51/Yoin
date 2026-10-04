package com.gpo.yoin.ui.detail

import com.gpo.yoin.ui.experience.ExperienceSessionState
import com.gpo.yoin.ui.experience.HomeSurface
import com.gpo.yoin.ui.navigation.YoinSection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellDetailOriginTest {
    @Test
    fun should_skipBottomBarHandoff_whenReturningToMemories() {
        assertTrue(ExperienceSessionState(homeSurface = HomeSurface.Memories).hasOverlayHidingBottomBar)
    }

    @Test
    fun should_keepBottomBarHandoff_whenMemoriesIsInactiveBehindLibrary() {
        assertFalse(
            ExperienceSessionState(
                selectedSection = YoinSection.LIBRARY,
                homeSurface = HomeSurface.Memories,
            ).hasOverlayHidingBottomBar,
        )
    }

    @Test
    fun should_keepBottomBarHandoff_whenReturningToHomeFeed() {
        assertFalse(ExperienceSessionState().hasOverlayHidingBottomBar)
    }

    @Test
    fun should_keepBottomBarHandoff_whenHomeEditing() {
        // Edit mode keeps the bar on screen (in its edit pose); the shell snaps
        // out of it before arming the detail chrome.
        assertFalse(ExperienceSessionState(homeSurface = HomeSurface.Edit).hasOverlayHidingBottomBar)
    }

    @Test
    fun should_skipBottomBarHandoff_whenReturningToNowPlaying() {
        for (section in YoinSection.entries) {
            assertTrue(
                ExperienceSessionState(selectedSection = section, nowPlayingExpanded = true)
                    .hasOverlayHidingBottomBar,
            )
        }
    }
}
