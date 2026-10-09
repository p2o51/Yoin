package com.gpo.yoin.ui.landing

import com.gpo.yoin.data.home.HomeSectionPref
import com.gpo.yoin.testutil.MainDispatcherRule
import com.gpo.yoin.ui.component.SeamTopStyle
import com.gpo.yoin.ui.home.HomeLayout
import com.gpo.yoin.ui.home.HomeSection
import com.gpo.yoin.ui.settings.service.SetupService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LandingViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private class FakeLayouts : LandingLayoutGateway {
        val stored = mutableMapOf<String, List<HomeSectionPref>?>()
        val cleared = mutableListOf<String>()
        override suspend fun read(profileId: String) = stored[profileId]
        override suspend fun write(profileId: String, sections: List<HomeSectionPref>) {
            stored[profileId] = sections
        }
        override suspend fun clear(profileId: String) {
            cleared += profileId
            stored.remove(profileId)
        }
    }

    private val profiles = MutableStateFlow<List<String>>(emptyList())
    private val active = MutableStateFlow<String?>(null)
    private val layouts = FakeLayouts()
    private val store = InMemoryLandingStore()
    private val edges = mutableListOf<SeamTopStyle>()

    private fun viewModel(mode: LandingMode = LandingMode.FirstRun) = LandingViewModel(
        mode = mode,
        profileIds = profiles,
        activeProfileId = active,
        layouts = layouts,
        initialEdge = SeamTopStyle.Tide,
        selectEdge = { edges += it },
        store = store,
    )

    @Test
    fun should_list_one_connect_step_per_picked_service_in_catalog_order_when_services_are_picked() {
        val steps = landingSteps(setOf(SetupService.AppleMusic, SetupService.Subsonic), hasAccount = false)
        assertEquals(
            listOf(
                LandingStep.Hello, LandingStep.Pick, LandingStep.About,
                LandingStep.Subsonic, LandingStep.AppleMusic, LandingStep.Edge, LandingStep.Ready,
            ),
            steps,
        )
    }

    @Test
    fun should_offer_the_home_step_only_when_an_account_exists() {
        assertFalse(LandingStep.Home in landingSteps(emptySet(), hasAccount = false))
        assertTrue(LandingStep.Home in landingSteps(emptySet(), hasAccount = true))
    }

    @Test
    fun should_keep_the_current_step_when_picking_rebuilds_the_list() {
        val vm = viewModel()
        vm.next()
        assertEquals(LandingStep.Pick, vm.state.value.step)
        vm.toggleService(SetupService.Spotify)
        assertEquals(LandingStep.Pick, vm.state.value.step)
        vm.next()
        vm.next()
        assertEquals(LandingStep.Spotify, vm.state.value.step)
    }

    @Test
    fun should_add_the_home_step_without_moving_when_the_first_account_appears() {
        val vm = viewModel()
        vm.next()
        vm.toggleService(SetupService.Subsonic)
        vm.next()
        vm.next()
        assertEquals(LandingStep.Subsonic, vm.state.value.step)
        profiles.value = listOf("p1")
        assertEquals(LandingStep.Subsonic, vm.state.value.step)
        vm.next()
        assertEquals(LandingStep.Home, vm.state.value.step)
    }

    @Test
    fun should_not_go_back_past_the_first_step() {
        val vm = viewModel()
        vm.back()
        assertEquals(0, vm.state.value.index)
        assertFalse(vm.state.value.canGoBack)
    }

    @Test
    fun should_reorder_hidden_sections_too_when_a_row_moves() {
        val vm = viewModel()
        vm.setSectionEnabled(HomeSection.Activities, false)
        vm.moveSection(0, 2)
        val order = vm.state.value.layout.sections.map { it.section }
        assertEquals(HomeSection.Activities, order[2])
        assertFalse(vm.state.value.layout.sections[2].enabled)
    }

    @Test
    fun should_apply_the_edge_style_at_once_when_one_is_picked() {
        val vm = viewModel()
        vm.setEdge(SeamTopStyle.Cookie)
        vm.setEdge(SeamTopStyle.Cookie)
        assertEquals(listOf(SeamTopStyle.Cookie), edges)
        assertEquals(SeamTopStyle.Cookie, vm.state.value.edge)
    }

    @Test
    fun should_write_the_layout_to_accounts_added_here_and_mark_done_when_finishing() = runTest {
        profiles.value = listOf("old")
        val vm = viewModel()
        profiles.value = listOf("old", "new")
        vm.setSectionEnabled(HomeSection.Rediscover, false)
        var ready = false
        vm.finish { ready = true }
        assertTrue(ready)
        assertTrue(store.isDone())
        assertTrue("new" in layouts.stored)
        assertFalse("old" in layouts.stored)
        val rediscover = layouts.stored.getValue("new")!!.first { it.id == HomeSection.Rediscover.id }
        assertFalse(rediscover.enabled)
    }

    @Test
    fun should_clear_instead_of_writing_when_the_layout_is_the_default() = runTest {
        val vm = viewModel(LandingMode.Rerun)
        active.value = "active"
        vm.finish {}
        assertEquals(listOf("active"), layouts.cleared)
    }

    @Test
    fun should_start_a_rerun_from_the_active_accounts_layout() = runTest {
        active.value = "active"
        layouts.stored["active"] = HomeLayout.Default.withEnabled(HomeSection.JumpBackIn, false).toPrefs()
        val vm = viewModel(LandingMode.Rerun)
        val jumpBackIn = vm.state.value.layout.sections.first { it.section == HomeSection.JumpBackIn }
        assertFalse(jumpBackIn.enabled)
    }
}
