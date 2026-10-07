package com.gpo.yoin.ui.experience

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import com.gpo.yoin.ui.component.PlaybackWaveState
import com.gpo.yoin.ui.navigation.YoinSection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the Home section shows. Only `HomeEditController` writes [Edit]. */
enum class HomeSurface {
    Feed,
    Memories,

    /** Home's in-place edit mode (long-press). */
    Edit,
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

data class ExperienceSessionState(
    val selectedSection: YoinSection = YoinSection.HOME,
    val homeSurface: HomeSurface = HomeSurface.Feed,
    val nowPlayingExpanded: Boolean = false,
    /**
     * True from the moment a detail launch is tapped until the last detail
     * window has left the screen: the shell bar wears its DETAIL chrome
     * (Play split + short pill) so the cross-window hand-off — and the
     * predictive-back preview on return — reads as one persistent bar.
     */
    val detailChromeActive: Boolean = false,
    val memories: MemoriesSessionState = MemoriesSessionState(),
)

/** Phase of a detail page's in-window predictive back gesture. */
enum class DetailBackPhase { Idle, Gesture, Committed }

class ExperienceSessionStore {
    val playbackWave = PlaybackWaveState()

    // One forward bar pose for both translucent windows. The shell owns its
    // animation; the incoming detail reads it until its back controller takes
    // over. Keeping the Animatable here avoids a delayed per-frame copy.
    internal val shellBarChromeMorph = Animatable(0f)

    /**
     * Home edit mode's progress P, beside the surface it animates. Only
     * `HomeEditController` writes it (and [HomeSurface.Edit]); Home and the
     * bar read it in draw and layout.
     */
    val homeEditProgress = HomeEditProgress()

    // ── Detail predictive-back pose bridge ─────────────────────────────────
    // Snapshot states, NOT part of [state]: written per gesture FRAME by the
    // top detail window and read inside graphicsLayer lambdas by the window
    // beneath (the AOSP "entering target" movement) — layer invalidation
    // only, zero recomposition at 60Hz.
    val detailBackPhase = mutableStateOf(DetailBackPhase.Idle)
    val detailBackProgress = mutableFloatStateOf(0f)
    val detailBackTouchYDelta = mutableFloatStateOf(0f)

    /**
     * The key of the window a detail's back is showing (the shell's, or the detail's beneath), from the gesture's
     * first frame (or a button back's commit) until the cancel settles or the detail is gone; null when nothing is
     * being revealed. Written for EVERY origin — unlike [detailBackPhase], which only shell-bridged details drive —
     * so [CoveredWindowAnimationGate] restarts exactly that window's animations before any of it is seen. A
     * detail clears only its own reveal (compare-and-set), so a finishing page cannot cancel the next one's.
     */
    val windowBeneathRevealed = MutableStateFlow<String?>(null)

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

    /** Flip the shell bar between nav chrome and detail (Play-split) chrome. */
    fun setDetailChromeActive(active: Boolean) {
        _state.update { current ->
            if (current.detailChromeActive == active) current
            else current.copy(detailChromeActive = active)
        }
    }

    /**
     * Ticks when the outer, shell-launched detail Activity has actually LEFT
     * the screen (its finishing onStop — the system holds that until the exit
     * animation ends). Inner detail windows do not publish this tick, so they
     * cannot retire the shell hand-off while their parent detail remains.
     */
    private val _detailWindowSettledTick = MutableStateFlow(0L)
    val detailWindowSettledTick: StateFlow<Long> = _detailWindowSettledTick.asStateFlow()

    fun noteDetailWindowSettled() {
        _detailWindowSettledTick.update { it + 1L }
    }

    /**
     * Ticks when a detail Activity's content actually STARTS its enter slide
     * (i.e. after launch latency + the 200ms bar-handoff window hold). The
     * shell's −96dp recede waits on this so it runs in lockstep with the
     * incoming slide instead of racing ahead at tap time — otherwise the
     * recede's 450ms ride finishes before the detail window has even faded
     * in, and the user watches Home slide into blank space.
     */
    private val _detailEnterSlideTick = MutableStateFlow(0L)
    val detailEnterSlideTick: StateFlow<Long> = _detailEnterSlideTick.asStateFlow()

    /**
     * Tick captured synchronously when the shell arms a forward detail hand-off.
     * The shell effect waits for a committed-frame tick newer than this value;
     * capturing it here avoids missing a fast commit before recomposition starts
     * the waiter.
     */
    var detailEnterSlideBaseline: Long = 0L
        private set

    fun prepareDetailEnterSlide() {
        detailEnterSlideBaseline = _detailEnterSlideTick.value
    }

    fun noteDetailEnterSlideStarted() {
        _detailEnterSlideTick.update { it + 1L }
    }

    /**
     * In-memory "newest memory timestamp the deck has already stamped".
     * Seeded to the current deck's max on first open, so only memories born
     * AFTER that read as new (and earn the seal-stamp moment on their card).
     * Session-scoped on purpose: a cold start must never false-stamp.
     */
    var memoriesStampedTimestamp: Long = Long.MAX_VALUE

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
