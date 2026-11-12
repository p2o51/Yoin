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

    @Test
    fun should_closeTheLastOpenedFirst_when_panelColumnAndMemoriesAreOpen() {
        // Opened: column, Now Playing panel, Memories — back undoes them in reverse.
        val recency = mutableListOf<ShellBackOwner>()
        recency.noteShellSurface(ShellBackOwner.DetailPane, open = true)
        recency.noteShellSurface(ShellBackOwner.NowPlaying, open = true)
        recency.noteShellSurface(ShellBackOwner.Memories, open = true)
        fun owner(np: Boolean, pane: Boolean, memories: Boolean) = resolveShellBackOwner(
            showNowPlaying = np,
            selectedSection = YoinSection.HOME,
            homeSurface = if (memories) HomeSurface.Memories else HomeSurface.Feed,
            detailPaneOpen = pane,
            nowPlayingIsPanel = true,
            recency = recency,
        )

        assertEquals(ShellBackOwner.Memories, owner(np = true, pane = true, memories = true))
        recency.noteShellSurface(ShellBackOwner.Memories, open = false)
        assertEquals(ShellBackOwner.NowPlaying, owner(np = true, pane = true, memories = false))
        recency.noteShellSurface(ShellBackOwner.NowPlaying, open = false)
        assertEquals(ShellBackOwner.DetailPane, owner(np = false, pane = true, memories = false))
    }

    @Test
    fun should_keepTheFullPlayerFirst_when_memoriesOpenedAfterIt() {
        val owner = resolveShellBackOwner(
            showNowPlaying = true,
            selectedSection = YoinSection.HOME,
            homeSurface = HomeSurface.Memories,
            nowPlayingIsPanel = false,
            recency = listOf(ShellBackOwner.NowPlaying, ShellBackOwner.Memories),
        )

        assertEquals(ShellBackOwner.NowPlaying, owner)
    }

    @Test
    fun should_closeTheColumnFirst_when_itOpenedFromMemories() {
        val owner = resolveShellBackOwner(
            showNowPlaying = false,
            selectedSection = YoinSection.HOME,
            homeSurface = HomeSurface.Memories,
            detailPaneOpen = true,
            recency = listOf(ShellBackOwner.Memories, ShellBackOwner.DetailPane),
        )

        assertEquals(ShellBackOwner.DetailPane, owner)
    }

    @Test
    fun should_closeMemoriesFirst_when_itOpenedBesideAnOpenColumn() {
        val owner = resolveShellBackOwner(
            showNowPlaying = false,
            selectedSection = YoinSection.HOME,
            homeSurface = HomeSurface.Memories,
            detailPaneOpen = true,
            recency = listOf(ShellBackOwner.DetailPane, ShellBackOwner.Memories),
        )

        assertEquals(ShellBackOwner.Memories, owner)
    }
}
