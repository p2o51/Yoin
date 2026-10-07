package com.gpo.yoin.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

/** What the feed renders and the keys it emits (plan §2.5). */
class HomeFeedBlocksTest {

    // Only Activities and Recently Added have something to show.
    private val hasContent: (HomeSection) -> Boolean = {
        it == HomeSection.Activities || it == HomeSection.RecentlyAdded
    }

    private fun blocks(
        layout: HomeLayout = HomeLayout.Default,
        hiding: Set<HomeSection> = emptySet(),
        editing: Boolean = false,
        held: HomeSection? = null,
        open: Boolean = true,
    ) = homeFeedBlocks(layout, hiding, editing, held, open, hasContent)

    @Test
    fun should_skipEmptySections_when_notEditing() {
        assertEquals(
            listOf(
                HomeFeedBlock(HomeSection.Activities, placeholder = false),
                HomeFeedBlock(HomeSection.RecentlyAdded, placeholder = false),
            ),
            blocks(),
        )
    }

    @Test
    fun should_renderEmptySectionsAsPlaceholders_when_editing() {
        assertEquals(
            listOf(
                HomeFeedBlock(HomeSection.Activities, placeholder = false),
                HomeFeedBlock(HomeSection.JumpBackIn, placeholder = true),
                HomeFeedBlock(HomeSection.RecentlyAdded, placeholder = false),
                HomeFeedBlock(HomeSection.Rediscover, placeholder = true),
                HomeFeedBlock(HomeSection.RecentlyPlayed, placeholder = true),
                HomeFeedBlock(HomeSection.YourPlaylists, placeholder = true),
            ),
            blocks(editing = true),
        )
    }

    @Test
    fun should_holdPlaceholdersAboveLiftedBlock_when_placeholdersNotOpen() {
        // Recently Added lifted at entry: the empty Jump Back In above it waits, Rediscover below doesn't.
        val held = blocks(editing = true, held = HomeSection.RecentlyAdded, open = false)
        assertEquals(
            listOf(HomeSection.Activities, HomeSection.RecentlyAdded, HomeSection.Rediscover, HomeSection.RecentlyPlayed, HomeSection.YourPlaylists),
            held.map { it.section },
        )

        val opened = blocks(editing = true, held = HomeSection.RecentlyAdded, open = true)
        assertEquals(HomeSection.JumpBackIn, opened[1].section)
    }

    @Test
    fun should_keepFadingSection_when_hidden() {
        val layout = HomeLayout.Default.withEnabled(HomeSection.RecentlyAdded, false)
        assertEquals(listOf(HomeSection.Activities), blocks(layout).map { it.section })
        assertEquals(
            listOf(HomeSection.Activities, HomeSection.RecentlyAdded),
            blocks(layout, hiding = setOf(HomeSection.RecentlyAdded)).map { it.section },
        )
    }

    @Test
    fun should_emitTrayKeysAfterBlocks_when_trayMounted() {
        val keys = homeFeedKeys(
            blocks = blocks(editing = true).take(2),
            trayMounted = true,
            hidden = listOf(HomeSection.Rediscover),
            allHidden = false,
        )
        assertEquals(
            listOf(
                "home-header",
                "section-activities",
                "section-jump_back_in",
                "tray-title",
                "tray-rediscover",
                "edit-footer",
            ),
            keys,
        )
    }

    @Test
    fun should_emitAllHiddenBeforeFooterEntry_when_everySectionDisabled() {
        val keys = homeFeedKeys(blocks = emptyList(), trayMounted = false, hidden = emptyList(), allHidden = true)
        assertEquals(listOf("home-header", "home-all-hidden", "home-edit-entry"), keys)
    }
}
