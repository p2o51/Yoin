package com.gpo.yoin.ui.landing

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.AppContainer
import com.gpo.yoin.data.home.HomeSectionPref
import com.gpo.yoin.ui.component.SeamTopPreference
import com.gpo.yoin.ui.component.SeamTopStyle
import com.gpo.yoin.ui.home.HomeLayout
import com.gpo.yoin.ui.home.HomeSection
import com.gpo.yoin.ui.settings.service.SetupService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the landing reads and writes a profile's Home layout (the same rows Home's edit mode writes). */
internal interface LandingLayoutGateway {
    suspend fun read(profileId: String): List<HomeSectionPref>?
    suspend fun write(profileId: String, sections: List<HomeSectionPref>)
    suspend fun clear(profileId: String)
}

/**
 * The landing's state machine: which step, what was picked and connected,
 * the Home layout and scroll-edge choices. Accounts are added by the
 * existing setup ViewModels; this only learns about them from the profile
 * list (any id that was not there when the landing opened is "ours").
 */
internal class LandingViewModel(
    mode: LandingMode,
    private val profileIds: Flow<List<String>>,
    private val activeProfileId: StateFlow<String?>,
    private val layouts: LandingLayoutGateway,
    initialEdge: SeamTopStyle,
    private val selectEdge: (SeamTopStyle) -> Unit,
    private val store: LandingStore,
) : ViewModel() {

    private val _state = MutableStateFlow(
        LandingUiState(
            mode = mode,
            steps = landingSteps(emptySet(), hasAccount = false),
            index = 0,
            picked = emptySet(),
            connected = emptySet(),
            hasAccount = false,
            layout = HomeLayout.Default,
            edge = initialEdge,
        ),
    )
    val state: StateFlow<LandingUiState> = _state.asStateFlow()

    /** The mascot's entrance plays once per landing, not again after a rotation. */
    var introPlayed = false

    private var profilesAtStart: Set<String>? = null
    private val createdProfileIds = linkedSetOf<String>()
    private var finishJob: Job? = null

    init {
        viewModelScope.launch {
            profileIds.collect { ids ->
                val start = profilesAtStart ?: ids.toSet().also { profilesAtStart = it }
                createdProfileIds += ids.filterNot { it in start }
                _state.update { it.rebuilt(hasAccount = ids.isNotEmpty()) }
            }
        }
        if (mode == LandingMode.Rerun) {
            viewModelScope.launch {
                val active = activeProfileId.value
                if (active != null) {
                    val stored = runCatching { layouts.read(active) }.getOrNull()
                    _state.update { it.copy(layout = HomeLayout.reconcile(stored)) }
                }
            }
        }
    }

    fun next() {
        _state.update { s -> if (s.index < s.steps.lastIndex) s.copy(index = s.index + 1) else s }
    }

    fun back() {
        _state.update { s -> if (s.index > 0) s.copy(index = s.index - 1) else s }
    }

    /** Jumps straight to [step] if it is in the current list (QA and the spec page's step list). */
    fun goTo(step: LandingStep) {
        _state.update { s -> s.steps.indexOf(step).takeIf { it >= 0 }?.let { s.copy(index = it) } ?: s }
    }

    fun toggleService(service: SetupService) {
        _state.update { s ->
            val picked = if (service in s.picked) s.picked - service else s.picked + service
            s.copy(picked = picked).rebuilt(hasAccount = s.hasAccount)
        }
    }

    fun onConnected(service: SetupService) {
        _state.update { it.copy(connected = it.connected + service) }
    }

    fun setSectionEnabled(section: HomeSection, enabled: Boolean) {
        _state.update { s ->
            s.copy(layout = s.layout.copy(sections = s.layout.sections.map { if (it.section == section) it.copy(enabled = enabled) else it }))
        }
    }

    /** Reorders the full list, hidden sections included (the landing shows them all). */
    fun moveSection(from: Int, to: Int) {
        _state.update { s ->
            val list = s.layout.sections.toMutableList()
            if (from !in list.indices || to !in list.indices || from == to) return@update s
            list.add(to, list.removeAt(from))
            s.copy(layout = s.layout.copy(sections = list))
        }
    }

    fun setEdge(style: SeamTopStyle) {
        if (_state.value.edge == style) return
        _state.update { it.copy(edge = style) }
        selectEdge(style)
    }

    /**
     * "Open Yoin": the layout goes to every account added here (and, on a
     * re-run, to the active one), the landing is marked seen, then
     * [onReady] starts the hand-off — Home's first frame is already the
     * chosen layout.
     */
    fun finish(onReady: () -> Unit) {
        if (finishJob?.isActive == true || _state.value.finishing) return
        _state.update { it.copy(finishing = true) }
        finishJob = viewModelScope.launch {
            val state = _state.value
            val targets = buildSet {
                addAll(createdProfileIds)
                if (state.mode == LandingMode.Rerun) activeProfileId.value?.let(::add)
            }
            targets.forEach { id ->
                runCatching {
                    if (state.layout.isDefault && state.layout.retained.isEmpty()) layouts.clear(id) else layouts.write(id, state.layout.toPrefs())
                }
            }
            store.markDone()
            onReady()
        }
    }

    private fun LandingUiState.rebuilt(hasAccount: Boolean): LandingUiState {
        val steps = landingSteps(picked, hasAccount)
        val keep = steps.indexOf(step).takeIf { it >= 0 } ?: index.coerceAtMost(steps.lastIndex)
        return copy(steps = steps, index = keep, hasAccount = hasAccount)
    }

    class Factory(
        private val context: Context,
        private val container: AppContainer,
        private val mode: LandingMode,
        private val store: LandingStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val store = container.homeLayoutStore
            val gateway = object : LandingLayoutGateway {
                override suspend fun read(profileId: String) = store.layoutFlow(profileId).first()
                override suspend fun write(profileId: String, sections: List<HomeSectionPref>) = store.setLayout(profileId, sections)
                override suspend fun clear(profileId: String) = store.clearLayout(profileId)
            }
            val appContext = context.applicationContext
            SeamTopPreference.ensureLoaded(appContext)
            return LandingViewModel(
                mode = mode,
                profileIds = container.profileManager.profiles.map { list -> list.map { it.id } },
                activeProfileId = container.profileManager.activeProfileId,
                layouts = gateway,
                initialEdge = SeamTopPreference.style,
                selectEdge = { style -> SeamTopPreference.select(appContext, style) },
                store = this.store,
            ) as T
        }
    }
}
