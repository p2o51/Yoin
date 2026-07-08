package com.gpo.yoin.ui.home

import com.gpo.yoin.data.home.HomeSectionPref
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLayoutTest {

    @Test
    fun reconcile_null_or_empty_yields_catalog_defaults() {
        assertEquals(HomeLayout.Default, HomeLayout.reconcile(null))
        assertEquals(HomeLayout.Default, HomeLayout.reconcile(emptyList()))
    }

    @Test
    fun reconcile_keeps_saved_order_and_flags() {
        val prefs = listOf(
            HomeSectionPref(id = "jump_back_in", enabled = true),
            HomeSectionPref(id = "activities", enabled = false),
            HomeSectionPref(id = "memory_teaser", enabled = true),
            HomeSectionPref(id = "recently_added", enabled = false),
        )
        val layout = HomeLayout.reconcile(prefs)
        assertEquals(
            // Memories wasn't in the saved layout, so reconcile appends it at
            // its catalog default after the known sections.
            listOf(
                HomeSection.JumpBackIn,
                HomeSection.Activities,
                HomeSection.MemoryTeaser,
                HomeSection.RecentlyAdded,
                HomeSection.Memories,
            ),
            layout.sections.map { it.section },
        )
        assertEquals(listOf(true, false, true, false, true), layout.sections.map { it.enabled })
    }

    @Test
    fun reconcile_drops_removed_ids_and_appends_new_sections_at_defaults() {
        // A layout saved before RecentlyAdded shipped, containing a section id
        // that no longer exists in the catalog.
        val prefs = listOf(
            HomeSectionPref(id = "ghost_section", enabled = true),
            HomeSectionPref(id = "jump_back_in", enabled = false),
        )
        val layout = HomeLayout.reconcile(prefs)
        assertEquals(
            listOf(
                HomeSection.JumpBackIn,
                HomeSection.MemoryTeaser,
                HomeSection.Activities,
                HomeSection.Memories,
                HomeSection.RecentlyAdded,
            ),
            layout.sections.map { it.section },
        )
        // Saved flag survives; appended catalog sections arrive at their defaults.
        assertFalse(layout.sections.first().enabled)
        assertTrue(layout.sections.drop(1).all { it.enabled })
    }

    @Test
    fun reconcile_dedupes_repeated_ids_keeping_first() {
        val prefs = listOf(
            HomeSectionPref(id = "activities", enabled = false),
            HomeSectionPref(id = "activities", enabled = true),
        )
        val layout = HomeLayout.reconcile(prefs)
        assertEquals(1, layout.sections.count { it.section == HomeSection.Activities })
        assertFalse(layout.sections.first { it.section == HomeSection.Activities }.enabled)
    }

    @Test
    fun toPrefs_round_trips_through_reconcile() {
        // Every catalog section must be present, or reconcile would append the
        // missing one and the round-trip wouldn't be an identity.
        val layout = HomeLayout(
            listOf(
                HomeSectionState(HomeSection.RecentlyAdded, enabled = false),
                HomeSectionState(HomeSection.JumpBackIn, enabled = true),
                HomeSectionState(HomeSection.Activities, enabled = true),
                HomeSectionState(HomeSection.Memories, enabled = true),
                HomeSectionState(HomeSection.MemoryTeaser, enabled = false),
            ),
        )
        assertEquals(layout, HomeLayout.reconcile(layout.toPrefs()))
    }

    @Test
    fun enabled_sections_filters_and_preserves_order() {
        val layout = HomeLayout(
            listOf(
                HomeSectionState(HomeSection.JumpBackIn, enabled = true),
                HomeSectionState(HomeSection.Activities, enabled = false),
                HomeSectionState(HomeSection.RecentlyAdded, enabled = true),
                HomeSectionState(HomeSection.MemoryTeaser, enabled = false),
            ),
        )
        assertEquals(
            listOf(HomeSection.JumpBackIn, HomeSection.RecentlyAdded),
            layout.enabledSections,
        )
    }
}
