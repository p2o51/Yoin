package com.gpo.yoin.ui.navigation.back

import com.gpo.yoin.ui.experience.HomeSurface
import com.gpo.yoin.ui.navigation.YoinSection
import org.junit.Assert.assertEquals
import org.junit.Test

class ShellBackResolverTest {

    @Test
    fun should_prioritize_now_playing_over_memories() {
        val owner = resolveShellBackOwner(
            showNowPlaying = true,
            selectedSection = YoinSection.HOME,
            homeSurface = HomeSurface.Memories,
        )

        assertEquals(ShellBackOwner.NowPlaying, owner)
    }

    @Test
    fun should_return_memories_when_home_overlay_is_active() {
        val owner = resolveShellBackOwner(
            showNowPlaying = false,
            selectedSection = YoinSection.HOME,
            homeSurface = HomeSurface.Memories,
        )

        assertEquals(ShellBackOwner.Memories, owner)
    }

    @Test
    fun should_giveTheDetailPaneBack_when_itIsOpenOverMemories() {
        val owner = resolveShellBackOwner(
            showNowPlaying = false,
            selectedSection = YoinSection.HOME,
            homeSurface = HomeSurface.Memories,
            detailPaneOpen = true,
        )

        assertEquals(ShellBackOwner.DetailPane, owner)
    }

    @Test
    fun should_keepNowPlayingFirst_when_theDetailPaneIsAlsoOpen() {
        val owner = resolveShellBackOwner(
            showNowPlaying = true,
            selectedSection = YoinSection.LIBRARY,
            homeSurface = HomeSurface.Feed,
            detailPaneOpen = true,
        )

        assertEquals(ShellBackOwner.NowPlaying, owner)
    }

    @Test
    fun should_returnHomeEdit_when_editingOnHome() {
        val owner = resolveShellBackOwner(
            showNowPlaying = false,
            selectedSection = YoinSection.HOME,
            homeSurface = HomeSurface.Edit,
        )

        assertEquals(ShellBackOwner.HomeEdit, owner)
    }

    @Test
    fun should_keepNowPlayingFirst_when_homeEditingBesideThePanel() {
        val owner = resolveShellBackOwner(
            showNowPlaying = true,
            selectedSection = YoinSection.HOME,
            homeSurface = HomeSurface.Edit,
            detailPaneOpen = true,
        )

        assertEquals(ShellBackOwner.NowPlaying, owner)
    }

    @Test
    fun should_giveHomeEditBack_when_theDetailPaneIsAlsoOpen() {
        val owner = resolveShellBackOwner(
            showNowPlaying = false,
            selectedSection = YoinSection.HOME,
            homeSurface = HomeSurface.Edit,
            detailPaneOpen = true,
        )

        assertEquals(ShellBackOwner.HomeEdit, owner)
    }

    @Test
    fun should_ignoreEditSurface_when_libraryIsSelected() {
        assertEquals(
            ShellBackOwner.None,
            resolveShellBackOwner(
                showNowPlaying = false,
                selectedSection = YoinSection.LIBRARY,
                homeSurface = HomeSurface.Edit,
            ),
        )
        assertEquals(
            ShellBackOwner.DetailPane,
            resolveShellBackOwner(
                showNowPlaying = false,
                selectedSection = YoinSection.LIBRARY,
                homeSurface = HomeSurface.Edit,
                detailPaneOpen = true,
            ),
        )
    }

    @Test
    fun should_return_none_when_no_shell_overlay_owns_back() {
        val owner = resolveShellBackOwner(
            showNowPlaying = false,
            selectedSection = YoinSection.LIBRARY,
            homeSurface = HomeSurface.Memories,
        )

        assertEquals(ShellBackOwner.None, owner)
    }
}
