package com.gpo.yoin.ui.experience

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import com.gpo.yoin.ui.navigation.YoinSection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class HomeSurface {
    Feed,
    Memories,
}

data class MemoryScrollPosition(
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleItemScrollOffset: Int = 0,
)

data class MemoriesSessionState(
    val deckId: Long = 0L,
    val currentDeckActivityIds: List<Long> = emptyList(),
    val currentPage: Int = 0,
    val perMemoryScrollOffsets: Map<Long, MemoryScrollPosition> = emptyMap(),
    // A pending request (from the home teaser) to open the deck stopped on a
    // specific album. Holds the candidate's sessionId. Consumed and cleared by
    // MemoriesViewModel once the focused deck is built. Reset by clearMemories().
    val pendingFocusSessionId: Long? = null,
)

/** Snapshot of the shell Button Group pill used by the detail dock morph. */
data class NavPillGeometry(
    val bounds: Rect,
    val color: Color,
)

data class ExperienceSessionState(
    val selectedSection: YoinSection = YoinSection.HOME,
    val homeSurface: HomeSurface = HomeSurface.Feed,
    val nowPlayingExpanded: Boolean = false,
    /**
     * Bumps when NP must appear ALREADY settled (dock-bloom reveal): the
     * shell re-seeds the overlay's transition state so the slide-in never
     * plays for that open. Normal opens don't touch it.
     */
    val nowPlayingSnapEpoch: Long = 0L,
    // A detail mini-player dock tap asked for Now Playing; the shell expands
    // AFTER a short stagger (once the detail window's dissolve has revealed
    // it) so the bar→NP rise plays in full view instead of behind the
    // still-opaque detail page. Consumed by the shell's stagger effect.
    val pendingNowPlayingExpand: Boolean = false,
    val memories: MemoriesSessionState = MemoriesSessionState(),
)

class ExperienceSessionStore {
    private val _state = MutableStateFlow(ExperienceSessionState())
    val state: StateFlow<ExperienceSessionState> = _state.asStateFlow()

    fun setSelectedSection(section: YoinSection) {
        _state.update { current -> current.copy(selectedSection = section) }
    }

    fun setHomeSurface(surface: HomeSurface) {
        _state.update { current -> current.copy(homeSurface = surface) }
    }

    /** Ask the Memories deck to open stopped on a specific album (by candidate sessionId). */
    fun requestMemoriesFocus(sessionId: Long) {
        _state.update { current ->
            current.copy(memories = current.memories.copy(pendingFocusSessionId = sessionId))
        }
    }

    /**
     * Retire a consumed focus request, but only if it still matches [expected] —
     * so a superseded load can't wipe a newer tap's pending focus.
     */
    fun clearMemoriesFocus(expected: Long) {
        _state.update { current ->
            if (current.memories.pendingFocusSessionId != expected) {
                current
            } else {
                current.copy(memories = current.memories.copy(pendingFocusSessionId = null))
            }
        }
    }

    fun setNowPlayingExpanded(expanded: Boolean) {
        _state.update { current -> current.copy(nowPlayingExpanded = expanded) }
    }

    /** Expand NP with NO enter transition — the dock bloom is the animation. */
    fun snapNowPlayingExpanded() {
        _state.update { current ->
            current.copy(
                nowPlayingExpanded = true,
                nowPlayingSnapEpoch = current.nowPlayingSnapEpoch + 1L,
            )
        }
    }

    /** A detail dock tap asked for NP; the shell's stagger effect consumes it. */
    fun requestNowPlayingExpand() {
        _state.update { current -> current.copy(pendingNowPlayingExpand = true) }
    }

    /**
     * Ticks when a detail Activity's window has actually LEFT the screen
     * (its onStop — the system holds that until the exit animation ends).
     * The shell's expand-stagger waits on this instead of guessing the
     * dissolve duration: OEMs replace or stretch activity animations, and a
     * fixed delay let the whole NP expansion play behind a still-opaque
     * detail window on those devices.
     */
    private val _detailWindowSettledTick = MutableStateFlow(0L)
    val detailWindowSettledTick: StateFlow<Long> = _detailWindowSettledTick.asStateFlow()

    fun noteDetailWindowSettled() {
        _detailWindowSettledTick.update { it + 1L }
    }

    /** Fulfil a pending expand request: NP opens, the request clears. */
    fun consumeNowPlayingExpandRequest() {
        _state.update { current ->
            if (!current.pendingNowPlayingExpand) current
            else current.copy(pendingNowPlayingExpand = false, nowPlayingExpanded = true)
        }
    }

    // ── Button Group → detail mini-player dock hand-off ────────────────────
    // Geometry and arming are transient main-thread hand-off data between the
    // shell's tap handler and the detail launch a few frames later — they are
    // deliberately NOT part of [state] (no recomposition should hang off them).

    /**
     * Latest window bounds + rendered surface color of the shell's bottom
     * Button Group pill. The color rides along because the detail Activity's
     * theme may not have resolved its cover wash yet when the morph starts.
     */
    @Volatile
    var navPill: NavPillGeometry? = null
        private set

    fun noteNavPill(bounds: Rect, color: Color) {
        navPill = NavPillGeometry(bounds, color)
    }

    /** Window bounds of the bar's mini artwork — the morph cover's origin. */
    @Volatile
    var navPillArtBounds: Rect? = null
        private set

    fun noteNavPillArt(bounds: Rect) {
        navPillArtBounds = bounds
    }

    private var dockHandoffArmed = false

    /**
     * Called by the shell's navigate wrappers BEFORE memories dismissal
     * mutates the surface state: records whether the Button Group is actually
     * the thing on screen (bar visible, something playing), i.e. whether the
     * upcoming detail launch should morph it into the mini-player dock.
     */
    fun armDockHandoff(eligible: Boolean) {
        dockHandoffArmed = eligible
    }

    /** One-shot read of the arm flag; consuming always disarms. */
    fun consumeDockHandoff(): Boolean {
        val armed = dockHandoffArmed
        dockHandoffArmed = false
        return armed
    }

    fun replaceMemoriesDeck(
        activityIds: List<Long>,
        currentPage: Int,
    ) {
        val sanitizedIds = activityIds.distinct()
        val retainedActivityIds = sanitizedIds.toSet()
        val boundedPage = currentPage.coerceIn(
            minimumValue = 0,
            maximumValue = sanitizedIds.lastIndex.coerceAtLeast(0),
        )

        _state.update { current ->
            current.copy(
                memories = current.memories.copy(
                    deckId = current.memories.deckId + 1L,
                    currentDeckActivityIds = sanitizedIds,
                    currentPage = boundedPage,
                    perMemoryScrollOffsets = current.memories.perMemoryScrollOffsets
                        .filterKeys { activityId -> activityId in retainedActivityIds },
                ),
            )
        }
    }

    fun setMemoriesCurrentPage(page: Int) {
        _state.update { current ->
            current.copy(
                memories = current.memories.copy(
                    currentPage = page.coerceIn(
                        minimumValue = 0,
                        maximumValue = current.memories.currentDeckActivityIds.lastIndex.coerceAtLeast(0),
                    ),
                ),
            )
        }
    }

    fun setMemoryScrollPosition(
        activityId: Long,
        position: MemoryScrollPosition,
    ) {
        _state.update { current ->
            current.copy(
                memories = current.memories.copy(
                    perMemoryScrollOffsets = current.memories.perMemoryScrollOffsets +
                        (activityId to position),
                ),
            )
        }
    }

    fun clearMemories() {
        _state.update { current ->
            current.copy(memories = MemoriesSessionState())
        }
    }
}
