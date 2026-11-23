package com.gpo.yoin.ui.home.edit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.experience.ExperienceSessionStore
import com.gpo.yoin.ui.home.HomeEditSessionHints
import com.gpo.yoin.ui.home.HomeLayout
import com.gpo.yoin.ui.home.HomeSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The Initial-pass detector on a real page: taps and scrolls pass through, a long press owns the finger. */
@RunWith(RobolectricTestRunner::class)
class HomeEditGesturesTest {

    @get:Rule
    val rule = createComposeRule()

    private val specs = HomeEditSpecs.create(MotionScheme.expressive(), reduced = false)
    private val feedback = RecordingHomeEditFeedback()
    private var clicks = 0
    private lateinit var controller: HomeEditController
    private lateinit var engine: HomeDragEngine
    private lateinit var listState: LazyListState

    // Three sections in Robolectric's 470dp-tall window: at 120dp they leave the page's bottom blank,
    // at 300dp they scroll.
    private fun setPage(sectionHeight: Int = 120, follow: Boolean = false) {
        rule.setContent {
            val scope = rememberCoroutineScope()
            val density = LocalDensity.current
            listState = rememberLazyListState()
            controller = remember {
                HomeEditController(
                    store = ExperienceSessionStore(),
                    scope = scope,
                    applyLayout = {},
                    onEditingChanged = {},
                    startSession = { HomeEditSessionHints() },
                    feedback = feedback,
                    reducedMotion = { false },
                ).apply { onVmLayout(HomeLayout.Default) }
            }
            val motion = remember {
                HomeEditMotion(
                    scope = scope,
                    specs = specs,
                    style = HomeWiggleStyle(),
                    reduced = { false },
                    progress = controller.progressReader,
                    editing = { controller.isEditing },
                    feedback = feedback,
                )
            }
            val press = remember { HomeEditPressState() }
            engine = remember {
                HomeDragEngine(
                    scope = scope,
                    specs = specs,
                    motion = motion,
                    feedback = feedback,
                    density = density,
                    commitOrder = { true },
                    finishDeferredExit = {},
                )
            }
            val targets = remember { HomeEditTargets() }
            if (follow) {
                engine.host = remember {
                    LazyListDragHost(
                        listState = listState,
                        order = { listOf(HomeSection.Activities, HomeSection.JumpBackIn, HomeSection.RecentlyAdded) },
                        laidOutPairs = { null },
                        contentLeft = { 0f },
                        contentWidth = { 1000f },
                        columnGapPx = 0f,
                        scrollArea = { HomeEditSafeArea(-10_000f, 10_000f) },
                    )
                }
            }
            val hitTester = remember {
                HomeEditHitTester(
                    listState = listState,
                    targets = targets,
                    contentBounds = { 0f..Float.MAX_VALUE },
                    plateOutsetPx = { Size.Zero },
                    editing = { controller.isEditing },
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .onPlaced(targets::attachBox)
                    .homeEditGestures(
                        controller = controller,
                        motion = motion,
                        press = press,
                        engine = engine,
                        hitTester = hitTester,
                        feedback = feedback,
                        isFlingInProgress = { listState.isScrollInProgress },
                        specs = specs,
                    )
                    .testTag("page"),
            ) {
                LazyColumn(state = listState) {
                    item(key = "section-activities") {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(sectionHeight.dp)
                                .then(
                                    if (follow) {
                                        Modifier.dragFollow(engine, HomeSection.Activities, inList = false, box = targets::boxCoordinates)
                                    } else {
                                        Modifier
                                    },
                                )
                                .testTag("card")
                                .clickable { clicks++ },
                        )
                    }
                    item(key = "section-jump_back_in") { Box(Modifier.fillMaxWidth().height(sectionHeight.dp)) }
                    item(key = "section-recently_added") { Box(Modifier.fillMaxWidth().height(sectionHeight.dp)) }
                }
            }
        }
    }

    @Test
    fun should_letTapClickCard_when_notEditing() {
        setPage()

        rule.onNodeWithTag("card").performTouchInput { click() }
        rule.waitForIdle()

        assertEquals(1, clicks)
        assertFalse(controller.isEditing)
    }

    @Test
    fun should_letListScroll_when_swipedInNormalMode() {
        setPage(sectionHeight = 300)

        rule.onNodeWithTag("page").performTouchInput { swipeUp() }
        rule.waitForIdle()

        assertTrue(listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)
        assertFalse(controller.isEditing)
    }

    @Test
    fun should_enterAndLiftWithoutClick_when_cardLongPressed() {
        setPage()

        rule.onNodeWithTag("card").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(600L)

        assertTrue(controller.isEditing)
        assertEquals(HomeSection.Activities, engine.liftSection)
        assertEquals(listOf("longPress"), feedback.events)

        rule.onNodeWithTag("card").performTouchInput { up() }
        rule.waitForIdle()

        assertEquals(0, clicks)
        assertTrue(controller.isEditing)
    }

    @Test
    fun should_kickNotClickNorExit_when_cardTappedInEdit() {
        setPage()
        rule.onNodeWithTag("card").performTouchInput { longClick() }
        rule.waitForIdle()

        rule.onNodeWithTag("card").performTouchInput { click() }
        rule.waitForIdle()

        assertTrue(controller.isEditing)
        assertEquals(0, clicks)
    }

    @Test
    fun should_exit_when_blankTappedInEdit() {
        setPage()
        rule.onNodeWithTag("card").performTouchInput { longClick() }
        rule.waitForIdle()

        // Below the last section: blank page.
        rule.onNodeWithTag("page").performTouchInput { click(Offset(centerX, bottom - 4f)) }
        rule.waitForIdle()

        assertFalse(controller.isEditing)
        assertEquals(0, clicks)
    }

    @Test
    fun should_notLift_when_cardPressedWhileFeedFlings() {
        setPage(sectionHeight = 300)
        rule.onNodeWithTag("card").performTouchInput { longClick() }
        rule.waitForIdle()
        assertTrue(controller.isEditing)

        // A fast swipe flings the feed; the next press lands while it still runs and is held past the body hold.
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("page").performTouchInput { swipeUp(startY = bottom - 10f, endY = centerY, durationMillis = 60L) }
        rule.mainClock.advanceTimeBy(32L)
        assertTrue(listState.isScrollInProgress)
        rule.onNodeWithTag("page").performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(500L)

        assertEquals(null, engine.liftSection)
        rule.onNodeWithTag("page").performTouchInput { up() }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertTrue(controller.isEditing)
        assertEquals(null, engine.dragging)
    }

    @Test
    fun should_keepDraggedBlockUnderFinger_onTheFrameTheListScrolls() {
        setPage(sectionHeight = 300, follow = true)
        rule.waitForIdle()
        // A drag runs a frame loop for as long as it lasts: frame by frame from here.
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("card").performTouchInput { down(Offset(centerX, 100f)) }
        rule.mainClock.advanceTimeBy(600L)
        rule.onNodeWithTag("page").performTouchInput { moveBy(Offset(0f, 40f)) }
        rule.mainClock.advanceTimeBy(100L)
        assertEquals(HomeSection.Activities, engine.dragging)
        val top = rule.onNodeWithTag("card").fetchSemanticsNode().boundsInRoot.top

        // The list scrolls under the resting finger; the next frame lays it out and draws the block.
        rule.runOnUiThread { listState.dispatchRawDelta(60f) }
        rule.mainClock.advanceTimeByFrame()

        assertEquals(top, rule.onNodeWithTag("card").fetchSemanticsNode().boundsInRoot.top, 1f)
        rule.onNodeWithTag("page").performTouchInput { up() }
        rule.mainClock.advanceTimeBy(1_000L)
        rule.mainClock.autoAdvance = true
    }
}
