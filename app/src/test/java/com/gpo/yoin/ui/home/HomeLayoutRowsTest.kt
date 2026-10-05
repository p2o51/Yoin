package com.gpo.yoin.ui.home

import com.gpo.yoin.data.home.HomeSectionPref
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** [HomeLayout]'s row presets (D1): pure operations, defaults and reconcile. */
class HomeLayoutRowsTest {

    private fun rowsConfig(key: String) = JsonObject(mapOf("rows" to JsonPrimitive(key)))

    @Test
    fun should_offerRowsOnlyOnActivitiesAndJumpBackIn_when_askedPerSection() {
        assertEquals(
            setOf(HomeSection.Activities, HomeSection.JumpBackIn),
            HomeSection.entries.filter { it.supportsRows }.toSet(),
        )
    }

    @Test
    fun should_returnSameInstance_when_rowsUnchangedOrSectionUnsupported() {
        val layout = HomeLayout.Default
        assertSame(layout, layout.withRows(HomeSection.JumpBackIn, HomeRowPreset.L))
        assertSame(layout, layout.withRows(HomeSection.RecentlyAdded, HomeRowPreset.XL))
        assertSame(layout, layout.withRows(HomeSection.Rediscover, HomeRowPreset.S))
    }

    @Test
    fun should_changeOnlyThatSection_when_withRows() {
        val layout = HomeLayout.Default.withRows(HomeSection.Activities, HomeRowPreset.XL)

        assertEquals(HomeRowPreset.XL, layout.rowsOf(HomeSection.Activities))
        assertEquals(HomeRowPreset.L, layout.rowsOf(HomeSection.JumpBackIn))
        assertEquals(HomeLayout.Default.sections.map { it.section }, layout.sections.map { it.section })
        assertEquals(HomeLayout.Default.sections.map { it.enabled }, layout.sections.map { it.enabled })
    }

    @Test
    fun should_notBeDefault_when_aSectionIsResized() {
        val layout = HomeLayout.Default.withRows(HomeSection.JumpBackIn, HomeRowPreset.S)

        assertFalse(layout.isDefault)
        assertFalse(layout.sameSectionsAs(HomeLayout.Default))
        assertTrue(layout.withRows(HomeSection.JumpBackIn, HomeRowPreset.L).isDefault)
    }

    @Test
    fun should_clearRows_when_reset() {
        val layout = HomeLayout.Default
            .withRows(HomeSection.Activities, HomeRowPreset.S)
            .copy(retained = listOf(HomeSectionPref("your_tracks", true)))

        val reset = layout.reset()

        assertTrue(reset.sameSectionsAs(HomeLayout.Default))
        assertEquals(HomeRowPreset.L, reset.rowsOf(HomeSection.Activities))
        assertEquals(layout.retained, reset.retained)
    }

    @Test
    fun should_carryRows_when_sectionsAreReordered() {
        val layout = HomeLayout.Default.withRows(HomeSection.JumpBackIn, HomeRowPreset.XL)

        val moved = layout.moved(HomeSection.JumpBackIn, 3)

        assertEquals(HomeSection.JumpBackIn, moved.enabledSections.last())
        assertEquals(HomeRowPreset.XL, moved.rowsOf(HomeSection.JumpBackIn))
        assertEquals(HomeRowPreset.L, moved.rowsOf(HomeSection.Activities))
    }

    @Test
    fun should_writeNoConfig_when_rowsAtDefault() {
        HomeLayout.Default.toPrefs().forEach { assertNull(it.id, it.config) }
        val prefs = HomeLayout.Default.withRows(HomeSection.Activities, HomeRowPreset.M).toPrefs()
        assertEquals(rowsConfig("m"), prefs.first { it.id == "activities" }.config)
    }

    @Test
    fun should_readRows_when_reconcilingAConfig() {
        val layout = HomeLayout.reconcile(
            listOf(
                HomeSectionPref("jump_back_in", true, rowsConfig("xl")),
                HomeSectionPref("activities", true, rowsConfig("s")),
            ),
        )

        assertEquals(HomeRowPreset.XL, layout.rowsOf(HomeSection.JumpBackIn))
        assertEquals(HomeRowPreset.S, layout.rowsOf(HomeSection.Activities))
        assertTrue(layout.sameSectionsAs(HomeLayout.reconcile(layout.toPrefs())))
    }

    @Test
    fun should_renderDefaultAndKeepValue_when_rowsValueUnknown() {
        val layout = HomeLayout.reconcile(listOf(HomeSectionPref("activities", true, rowsConfig("xxl"))))

        assertEquals(HomeRowPreset.L, layout.rowsOf(HomeSection.Activities))
        assertEquals(rowsConfig("xxl"), layout.toPrefs().first { it.id == "activities" }.config)
        // Choosing a preset replaces the unreadable value.
        assertEquals(
            rowsConfig("s"),
            layout.withRows(HomeSection.Activities, HomeRowPreset.S).toPrefs().first { it.id == "activities" }.config,
        )
    }

    @Test
    fun should_matchStoredLayout_when_rowsSetBackToTheReadValue() {
        // The echo hold compares the draft with the VM's read: equal by value, whatever the path.
        val read = HomeLayout.reconcile(listOf(HomeSectionPref("jump_back_in", true, rowsConfig("xl"))))
        val draft = HomeLayout.reconcile(listOf(HomeSectionPref("jump_back_in", true)))
            .withRows(HomeSection.JumpBackIn, HomeRowPreset.XL)

        assertTrue(read.sameSectionsAs(draft))
    }

    @Test
    fun should_mapKeys_when_readingPresets() {
        assertEquals(HomeRowPreset.entries, listOf("s", "m", "l", "xl").map { HomeRowPreset.fromKey(it) })
        assertNull(HomeRowPreset.fromKey("xxl"))
        assertNull(HomeRowPreset.fromKey(null))
    }
}
