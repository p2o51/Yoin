package com.gpo.yoin.ui.home

import com.gpo.yoin.data.home.HomeSectionPref
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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
            HomeSectionPref(id = "recently_added", enabled = false),
            HomeSectionPref(id = "jump_back_in", enabled = true),
            HomeSectionPref(id = "activities", enabled = false),
        )
        val layout = HomeLayout.reconcile(prefs)
        // A layout saved before Rediscover shipped: it appends hidden and new.
        assertEquals(
            listOf(
                HomeSection.RecentlyAdded,
                HomeSection.JumpBackIn,
                HomeSection.Activities,
                HomeSection.Rediscover,
                HomeSection.RecentlyPlayed,
                HomeSection.YourPlaylists,
            ),
            layout.sections.map { it.section },
        )
        // Your Playlists appends shown (asked for by name), not hidden-and-new.
        assertEquals(listOf(false, true, false, false, true, true), layout.sections.map { it.enabled })
        assertEquals(setOf(HomeSection.Rediscover), layout.newSections)
    }

    @Test
    fun reconcile_drops_removed_ids_and_appends_new_sections_at_defaults() {
        // A layout saved before the catalog slimmed down: the retired
        // memory_teaser / memories ids must drop out (never retained), the
        // legacy sections it never listed append visible, and a newly shipped
        // section appends hidden.
        val prefs = listOf(
            HomeSectionPref(id = "memory_teaser", enabled = true),
            HomeSectionPref(id = "jump_back_in", enabled = false),
            HomeSectionPref(id = "memories", enabled = true),
        )
        val layout = HomeLayout.reconcile(prefs)
        assertEquals(
            listOf(
                HomeSection.JumpBackIn,
                HomeSection.Activities,
                HomeSection.RecentlyAdded,
                HomeSection.Rediscover,
                HomeSection.RecentlyPlayed,
                HomeSection.YourPlaylists,
            ),
            layout.sections.map { it.section },
        )
        assertEquals(listOf(false, true, true, false, true, true), layout.sections.map { it.enabled })
        assertTrue(layout.retained.isEmpty())
        assertEquals(setOf(HomeSection.Rediscover), layout.newSections)
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
                HomeSectionState(HomeSection.Rediscover, enabled = true),
                HomeSectionState(HomeSection.JumpBackIn, enabled = true),
                HomeSectionState(HomeSection.Activities, enabled = true),
                HomeSectionState(HomeSection.RecentlyPlayed, enabled = true),
                HomeSectionState(HomeSection.YourPlaylists, enabled = false),
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
            ),
        )
        assertEquals(
            listOf(HomeSection.JumpBackIn, HomeSection.RecentlyAdded),
            layout.enabledSections,
        )
    }

    // ── Q6a: new sections, retained ids ────────────────────────────────

    @Test
    fun should_enableRediscover_when_prefsNeverCustomized() {
        val layout = HomeLayout.reconcile(null)

        assertTrue(HomeSection.Rediscover in layout.enabledSections)
        assertEquals(
            listOf(HomeSection.Rediscover, HomeSection.RecentlyPlayed, HomeSection.YourPlaylists),
            layout.sections.takeLast(3).map { it.section },
        )
        assertTrue(layout.newSections.isEmpty())
    }

    @Test
    fun should_appendRediscoverDisabledAndMarkNew_when_prefsCustomized() {
        val layout = HomeLayout.reconcile(legacyPrefs())

        assertEquals(
            HomeSectionState(HomeSection.Rediscover, enabled = false),
            layout.sections.first { it.section == HomeSection.Rediscover },
        )
        assertEquals(listOf(HomeSection.Rediscover), layout.hiddenSections)
        assertEquals(setOf(HomeSection.Rediscover), layout.newSections)
    }

    @Test
    fun should_notMarkNew_when_sectionAlreadyInPrefs() {
        val layout = HomeLayout.reconcile(
            legacyPrefs() + HomeSectionPref(id = "rediscover", enabled = false),
        )

        assertTrue(layout.newSections.isEmpty())
        assertFalse(layout.sections.first { it.section == HomeSection.Rediscover }.enabled)
    }

    @Test
    fun should_appendLegacySectionEnabled_when_itsEntryWasDropped() {
        // Lenient decode dropped the corrupt Activities entry.
        val layout = HomeLayout.reconcile(
            listOf(
                HomeSectionPref(id = "jump_back_in", enabled = true),
                HomeSectionPref(id = "recently_added", enabled = true),
                HomeSectionPref(id = "rediscover", enabled = true),
            ),
        )

        assertEquals(
            listOf(HomeSection.Activities, HomeSection.RecentlyPlayed, HomeSection.YourPlaylists),
            layout.sections.takeLast(3).map { it.section },
        )
        assertTrue(layout.sections.first { it.section == HomeSection.Activities }.enabled)
        assertTrue(layout.newSections.isEmpty())
    }

    @Test
    fun should_retainUnknownIdsInOrder_when_reconciling() {
        val layout = HomeLayout.reconcile(
            listOf(
                HomeSectionPref(id = "your_tracks", enabled = true),
                HomeSectionPref(id = "activities", enabled = true),
                HomeSectionPref(id = "top_three", enabled = false),
            ),
        )

        assertEquals(
            listOf(
                HomeSectionPref(id = "your_tracks", enabled = true),
                HomeSectionPref(id = "top_three", enabled = false),
            ),
            layout.retained,
        )
        assertEquals(HomeSection.Activities, layout.sections.first().section)
    }

    @Test
    fun should_appendRetainedAfterKnown_when_toPrefs() {
        val prefs = listOf(
            HomeSectionPref(id = "your_tracks", enabled = true),
            HomeSectionPref(id = "activities", enabled = true),
            HomeSectionPref(id = "jump_back_in", enabled = false),
            HomeSectionPref(id = "recently_added", enabled = true),
            HomeSectionPref(id = "rediscover", enabled = true),
        )

        assertEquals(
            listOf("activities", "jump_back_in", "recently_added", "rediscover", "recently_played", "your_playlists", "your_tracks"),
            HomeLayout.reconcile(prefs).toPrefs().map { it.id },
        )
    }

    @Test
    fun should_dropRetiredIds_when_reconciling() {
        val layout = HomeLayout.reconcile(
            legacyPrefs() + listOf(
                HomeSectionPref(id = "memories", enabled = true),
                HomeSectionPref(id = "memory_teaser", enabled = true),
                HomeSectionPref(id = "", enabled = true),
            ),
        )

        assertTrue(layout.retained.isEmpty())
        assertTrue(layout.toPrefs().none { it.id in setOf("memories", "memory_teaser", "") })
    }

    @Test
    fun should_keepFirstRetained_when_unknownIdRepeats() {
        val layout = HomeLayout.reconcile(
            legacyPrefs() + listOf(
                HomeSectionPref(id = "your_tracks", enabled = false),
                HomeSectionPref(id = "your_tracks", enabled = true),
            ),
        )

        assertEquals(listOf(HomeSectionPref(id = "your_tracks", enabled = false)), layout.retained)
    }

    @Test
    fun should_beDefault_when_sectionsMatchDefaultEvenWithRetained() {
        val layout = HomeLayout.Default.copy(retained = listOf(HomeSectionPref("your_tracks", true)))

        assertTrue(layout.isDefault)
        assertFalse(HomeLayout.Default.withEnabled(HomeSection.Activities, false).isDefault)
    }

    @Test
    fun should_keepRetained_when_reset() {
        val retained = listOf(HomeSectionPref("your_tracks", true))
        val layout = HomeLayout.reconcile(legacyPrefs() + retained)

        val reset = layout.reset()

        assertTrue(reset.sameSectionsAs(HomeLayout.Default))
        assertEquals(retained, reset.retained)
    }

    // ── Pure operations ────────────────────────────────────────────────

    @Test
    fun should_returnSameInstance_when_movedToCurrentIndex() {
        val layout = HomeLayout.Default

        assertSame(layout, layout.moved(HomeSection.JumpBackIn, 1))
        assertSame(layout, layout.moved(HomeSection.Activities, -3))
    }

    @Test
    fun should_keepDisabledSlots_when_movingEnabledSection() {
        val layout = HomeLayout.Default.withEnabled(HomeSection.JumpBackIn, false)

        val moved = layout.moved(HomeSection.Rediscover, 0)

        assertEquals(
            listOf(
                HomeSectionState(HomeSection.Rediscover, enabled = true),
                HomeSectionState(HomeSection.JumpBackIn, enabled = false),
                HomeSectionState(HomeSection.Activities, enabled = true),
                HomeSectionState(HomeSection.RecentlyAdded, enabled = true),
                HomeSectionState(HomeSection.RecentlyPlayed, enabled = true),
                HomeSectionState(HomeSection.YourPlaylists, enabled = true),
            ),
            moved.sections,
        )
    }

    @Test
    fun should_clampIndex_when_movedPastEnd() {
        val moved = HomeLayout.Default.moved(HomeSection.Activities, 99)

        assertEquals(
            listOf(
                HomeSection.JumpBackIn,
                HomeSection.RecentlyAdded,
                HomeSection.Rediscover,
                HomeSection.RecentlyPlayed,
                HomeSection.YourPlaylists,
                HomeSection.Activities,
            ),
            moved.enabledSections,
        )
    }

    @Test
    fun should_returnSameInstance_when_withEnabledUnchanged() {
        val layout = HomeLayout.Default

        assertSame(layout, layout.withEnabled(HomeSection.Activities, true))
        val hidden = layout.withEnabled(HomeSection.Activities, false)
        assertSame(hidden, hidden.withEnabled(HomeSection.Activities, false))
    }

    @Test
    fun should_restoreOriginalSlot_when_hiddenSectionShownAgain() {
        val hidden = HomeLayout.Default.withEnabled(HomeSection.JumpBackIn, false)
        val reordered = hidden.moved(HomeSection.Rediscover, 0)

        val shown = reordered.withEnabled(HomeSection.JumpBackIn, true)

        // JBI returns to its absolute slot (index 1), not to the end.
        assertEquals(HomeSection.JumpBackIn, shown.sections[1].section)
        assertTrue(shown.sections[1].enabled)
        assertTrue(hidden.withEnabled(HomeSection.JumpBackIn, true).sameSectionsAs(HomeLayout.Default))
    }

    @Test
    fun should_withEnabledOrderKeepDisabledSlots_when_orderPermuted() {
        // [A, b̶, C, D] + [D, A, C] → [D, b̶, A, C]
        val layout = HomeLayout(
            listOf(
                HomeSectionState(HomeSection.Activities, enabled = true),
                HomeSectionState(HomeSection.JumpBackIn, enabled = false),
                HomeSectionState(HomeSection.RecentlyAdded, enabled = true),
                HomeSectionState(HomeSection.Rediscover, enabled = true),
            ),
        )

        val next = layout.withEnabledOrder(
            listOf(HomeSection.Rediscover, HomeSection.Activities, HomeSection.RecentlyAdded),
        )

        assertEquals(
            listOf(
                HomeSectionState(HomeSection.Rediscover, enabled = true),
                HomeSectionState(HomeSection.JumpBackIn, enabled = false),
                HomeSectionState(HomeSection.Activities, enabled = true),
                HomeSectionState(HomeSection.RecentlyAdded, enabled = true),
            ),
            next.sections,
        )
    }

    @Test
    fun should_returnSameInstance_when_orderIsNotAPermutation() {
        val layout = HomeLayout.Default.withEnabled(HomeSection.JumpBackIn, false)

        // Missing one, a hidden one, a duplicate, and the current order.
        assertSame(layout, layout.withEnabledOrder(listOf(HomeSection.Rediscover, HomeSection.Activities)))
        assertSame(
            layout,
            layout.withEnabledOrder(
                listOf(HomeSection.JumpBackIn, HomeSection.Activities, HomeSection.RecentlyAdded),
            ),
        )
        assertSame(
            layout,
            layout.withEnabledOrder(
                listOf(HomeSection.Activities, HomeSection.Activities, HomeSection.RecentlyAdded),
            ),
        )
        assertSame(layout, layout.withEnabledOrder(layout.enabledSections))
    }

    /** A layout customized before Rediscover shipped. */
    private fun legacyPrefs(): List<HomeSectionPref> = listOf(
        HomeSectionPref(id = "activities", enabled = true),
        HomeSectionPref(id = "jump_back_in", enabled = true),
        HomeSectionPref(id = "recently_added", enabled = true),
    )
}
