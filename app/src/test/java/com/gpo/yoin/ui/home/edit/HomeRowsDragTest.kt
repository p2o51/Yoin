package com.gpo.yoin.ui.home.edit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.experience.ExperienceSessionStore
import com.gpo.yoin.ui.home.HomeEditSessionHints
import com.gpo.yoin.ui.home.HomeLayout
import com.gpo.yoin.ui.home.HomeRowPreset
import com.gpo.yoin.ui.home.HomeSection
import com.gpo.yoin.ui.home.homeRowLadder
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The row resize end to end on a real edit block: the ⌟ handle drags the
 * block's height 1:1, lands on a stop and writes its preset (one Undo step),
 * and TalkBack's "More rows" / "Fewer rows" step it.
 */
@RunWith(RobolectricTestRunner::class)
class HomeRowsDragTest {

    @get:Rule
    val rule = createComposeRule()

    private val specs = HomeEditSpecs.create(MotionScheme.expressive(), reduced = true)
    private val feedback = RecordingHomeEditFeedback()
    private val applied = mutableListOf<HomeLayout>()
    private lateinit var controller: HomeEditController
    private lateinit var rows: HomeRowsEngine
    private lateinit var motion: HomeEditMotion

    /** The body's height at each preset: S 100, M 150, L 200, XL 300dp. */
    private val stopHeights = mapOf(
        HomeRowPreset.S to 100.dp,
        HomeRowPreset.M to 150.dp,
        HomeRowPreset.L to 200.dp,
        HomeRowPreset.XL to 300.dp,
    )
    private val ladder = homeRowLadder { preset -> stopHeights.getValue(preset) to preset.ordinal + 1 }

    /** [cards]: each stop is a column of 50dp tap-target cards (S 2, M 3, L 4, XL 6) instead of one box. */
    private fun setBlock(cards: Boolean = false) {
        rule.setContent {
            YoinTheme {
                val scope = rememberCoroutineScope()
                val density = LocalDensity.current
                val deps = remember {
                    controller = HomeEditController(
                        store = ExperienceSessionStore(),
                        scope = scope,
                        applyLayout = { applied += it },
                        onEditingChanged = {},
                        startSession = { HomeEditSessionHints() },
                        feedback = feedback,
                        reducedMotion = { true },
                    ).apply { onVmLayout(HomeLayout.Default) }
                    motion = HomeEditMotion(
                        scope = scope,
                        specs = specs,
                        style = HomeWiggleStyle(),
                        reduced = { true },
                        progress = controller.progressReader,
                        editing = { controller.isEditing },
                        feedback = HomeEditFeedback.None,
                    )
                    val engine = HomeCarryEngine(
                        scope = scope,
                        specs = specs,
                        motion = motion,
                        feedback = HomeEditFeedback.None,
                        density = density,
                        commitOrder = controller::commitOrder,
                        finishDeferredExit = controller::finishDeferredExit,
                    )
                    rows = HomeRowsEngine(
                        scope = scope,
                        specs = specs,
                        feedback = feedback,
                        density = density,
                        editing = { controller.isEditing },
                        carrying = { false },
                        currentPreset = { controller.draft?.rowsOf(it) ?: HomeRowPreset.Default },
                        commit = controller::setRows,
                    )
                    HomeEditDeps(
                        controller = controller,
                        motion = motion,
                        press = HomeEditPressState(),
                        engine = engine,
                        targets = HomeEditTargets(),
                        specs = specs,
                        rows = rows,
                    )
                }
                DisposableEffect(deps) {
                    controller.layer = object : HomeEditLayer {
                        override val isCarrying: Boolean = false
                        override fun onEnter(origin: HomeSection?, lifted: Boolean) = Unit
                        override fun onExit(reason: HomeEditExitReason) = rows.onExit()
                        override fun onSnapExit() = rows.snapAll()
                        override fun onTouch() = Unit
                        override fun onLayoutChange(change: HomeEditChange) =
                            rows.onLayoutChange(change.previous, change.next)
                        override fun deferExit(reason: HomeEditExitReason) = Unit
                        override fun abortCarry() = Unit
                        override fun scrollToTray() = Unit
                    }
                    onDispose { controller.layer = null }
                }
                val layout = controller.layoutToRender(HomeLayout.Default)
                Box(Modifier.padding(48.dp)) {
                    HomeEditBlock(
                        section = HomeSection.JumpBackIn,
                        displayIndex = 0,
                        displayCount = 2,
                        deps = deps,
                        placeholder = false,
                        wholeBlockWiggle = false,
                        itemSpacing = 18.dp,
                        blockTopInBox = { 0f },
                        safeArea = { HomeEditSafeArea(top = 0f, bottom = 10_000f) },
                        modifier = Modifier.testTag(BlockTag),
                    ) {
                        HomeRowsBody(
                            track = rows.track(HomeSection.JumpBackIn),
                            engine = rows,
                            ladder = ladder,
                            preset = layout.rowsOf(HomeSection.JumpBackIn),
                            reduced = true,
                        ) { stop ->
                            if (cards) {
                                Column(Modifier.fillMaxWidth()) {
                                    repeat((stop.payload / CardHeight).toInt()) { index ->
                                        Box(
                                            Modifier
                                                .homeRowsCard(index, "card")
                                                .homeEditCard(index)
                                                .fillMaxWidth()
                                                .height(CardHeight)
                                                .testTag("card-$index"),
                                        )
                                    }
                                }
                            } else {
                                Box(Modifier.fillMaxWidth().height(stop.payload))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun enterEdit() {
        rule.runOnIdle { controller.enter(HomeSection.JumpBackIn, lifted = false) }
        rule.waitForIdle()
    }

    private fun blockHeight(): Dp = rule.onNodeWithTag(BlockTag).getBoundsInRoot().let { it.bottom - it.top }

    private fun handle(): SemanticsNodeInteraction =
        rule.onNodeWithTag(homeRowsHandleTag(HomeSection.JumpBackIn), useUnmergedTree = true)

    private fun dp(value: Dp): Float = with(rule.density) { value.toPx() }

    /** Down on the handle, move by [dy] in steps, rest, lift: no fling. */
    private fun drag(dy: Dp, rest: Long = 200L) {
        handle().performTouchInput {
            down(center)
            val steps = 8
            repeat(steps) { moveBy(Offset(0f, dp(dy) / steps)) }
            advanceEventTime(rest)
            up()
        }
        rule.waitForIdle()
    }

    private fun assertHeight(expected: Dp) {
        assertEquals(expected.value, blockHeight().value, 1f)
    }

    @Test
    fun should_notShowTheHandle_when_notEditing() {
        setBlock()
        handle().assertDoesNotExist()
        assertHeight(200.dp)
    }

    @Test
    fun should_keepTheHandlesTouchTargetOnThePlate_when_editing() {
        setBlock()
        enterEdit()

        val block = rule.onNodeWithTag(BlockTag).getBoundsInRoot()
        val handle = handle().getBoundsInRoot()
        // The plate: the block outset by the shipped plate's outsets (V1 at rest, itemSpacing 18dp).
        val plate = HomePlateLook.Default
        val plateRight = block.right + plate.plateOutsetH()
        val plateBottom = block.bottom + plate.outsetV(18.dp)

        // Landscape QA: a touch box reaching past the plate caught edge swipes meant to scroll.
        assertTrue("right ${handle.right} past $plateRight", handle.right.value <= plateRight.value + .5f)
        assertTrue("bottom ${handle.bottom} past $plateBottom", handle.bottom.value <= plateBottom.value + .5f)
        // …and still a full 48dp target.
        assertEquals(HomeRowsTokens.HandleTouch.value, (handle.right - handle.left).value, .5f)
        assertEquals(HomeRowsTokens.HandleTouch.value, (handle.bottom - handle.top).value, .5f)
        // The glyph stays HandleInset inside the plate's corner.
        assertEquals(
            HomeRowsTokens.HandleInset.value,
            (handle.left + HomeRowsTokens.HandleCornerInTouch).let { plateRight - it }.value,
            .5f,
        )
    }

    @Test
    fun should_landOnXlAndWriteIt_when_draggedDownPastTheLastStop() {
        setBlock()
        enterEdit()
        assertHeight(200.dp)

        drag(150.dp)

        assertEquals(HomeRowPreset.XL, controller.draft!!.rowsOf(HomeSection.JumpBackIn))
        assertEquals(HomeRowPreset.XL, applied.last().rowsOf(HomeSection.JumpBackIn))
        assertHeight(300.dp)
        assertTrue(feedback.events.contains("confirm"))
        assertTrue(feedback.events.contains("threshold"))
        assertEquals(1, controller.undoDepth)
    }

    @Test
    fun should_landOnTheNearestStop_when_releasedBetweenStops() {
        setBlock()
        enterEdit()

        // 200 − 60 = 140: nearer M (150) than S (100) or L (200).
        drag((-60).dp)

        assertEquals(HomeRowPreset.M, controller.draft!!.rowsOf(HomeSection.JumpBackIn))
        assertHeight(150.dp)
    }

    @Test
    fun should_springBack_when_releasedOnTheStartingStop() {
        setBlock()
        enterEdit()

        drag(20.dp)

        assertEquals(HomeRowPreset.L, controller.draft!!.rowsOf(HomeSection.JumpBackIn))
        assertEquals(0, controller.undoDepth)
        assertTrue(applied.isEmpty())
        assertHeight(200.dp)
    }

    @Test
    fun should_restoreThePreviousRows_when_undone() {
        setBlock()
        enterEdit()
        drag(150.dp)
        assertHeight(300.dp)

        rule.runOnIdle { controller.undo() }
        rule.waitForIdle()

        assertEquals(HomeRowPreset.L, controller.draft!!.rowsOf(HomeSection.JumpBackIn))
        assertHeight(200.dp)
    }

    @Test
    fun should_stepAStop_when_talkBackAsksForMoreOrFewerRows() {
        setBlock()
        enterEdit()
        val block = rule.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Section 1 of 2, 3 rows"),
        )
        assertEquals(listOf("Move down", "More rows", "Fewer rows", "Hide"), block.actionLabels())

        block.runAction("More rows")
        assertEquals(HomeRowPreset.XL, controller.draft!!.rowsOf(HomeSection.JumpBackIn))
        assertHeight(300.dp)

        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Section 1 of 2, 4 rows"))
            .runAction("Fewer rows")
        assertEquals(HomeRowPreset.L, controller.draft!!.rowsOf(HomeSection.JumpBackIn))
        assertHeight(200.dp)
        assertEquals(2, controller.undoDepth)
    }

    @Test
    fun should_findEachCardItself_when_tappedAfterGrowingTheRows() {
        setBlock(cards = true)
        enterEdit()

        drag(150.dp)

        assertHeight(300.dp)
        assertEachCardIsItsOwnTapTarget(count = 6)
    }

    @Test
    fun should_findEachCardItself_when_tappedAfterShrinkingTheRows() {
        setBlock(cards = true)
        enterEdit()

        drag((-100).dp)

        assertHeight(100.dp)
        assertEachCardIsItsOwnTapTarget(count = 2)
    }

    /** An edit-mode tap on each card finds that card, not none (which would kick the whole block). */
    private fun assertEachCardIsItsOwnTapTarget(count: Int) {
        val block = rule.onNodeWithTag(BlockTag).getBoundsInRoot()
        repeat(count) { index ->
            val card = rule.onNodeWithTag("card-$index", useUnmergedTree = true).getBoundsInRoot()
            val local = Offset(
                dp((card.left + card.right) / 2 - block.left),
                dp((card.top + card.bottom) / 2 - block.top),
            )
            assertEquals(index, rule.runOnIdle { motion.cardAt(HomeSection.JumpBackIn, local) })
        }
    }

    private fun SemanticsNodeInteraction.actionLabels(): List<String> =
        fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }

    private fun SemanticsNodeInteraction.runAction(label: String) {
        val action = fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == label }
        rule.runOnIdle { action.action() }
        rule.waitForIdle()
    }

    private companion object {
        const val BlockTag = "rows-block"
        val CardHeight = 50.dp
    }
}
