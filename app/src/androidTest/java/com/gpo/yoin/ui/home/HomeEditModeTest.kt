package com.gpo.yoin.ui.home

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.experience.ExperienceSessionStore
import com.gpo.yoin.ui.experience.LayoutMode
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.LocalYoinWindowInfo
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.experience.RevealState
import com.gpo.yoin.ui.experience.ShellChromeForm
import com.gpo.yoin.ui.experience.YoinWindowInfo
import com.gpo.yoin.ui.experience.rememberRevealState
import com.gpo.yoin.ui.experience.rememberYoinWindowInfo
import com.gpo.yoin.ui.home.edit.HomeEditController
import com.gpo.yoin.ui.home.edit.HomeEditFeedback
import com.gpo.yoin.ui.sampleHomeContent
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Home edit mode end to end on a device (spec P0-4, P0-6, P0-8): the
 * page-wide long-press detector against cards, shelves, margins and the
 * Memories pull; the strip carry; TalkBack actions. Reduced motion parks the
 * wiggle clock so the rule can idle. Every write comes back as the next
 * layout, as the view model's would.
 *
 *   am instrument -w -e class com.gpo.yoin.ui.home.HomeEditModeTest \
 *     com.gpo.yoin.test/androidx.test.runner.AndroidJUnitRunner
 */
@OptIn(ExperimentalTestApi::class)
class HomeEditModeTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var controller: HomeEditController
    private lateinit var revealState: RevealState
    private var persisted by mutableStateOf(HomeLayout.Default)
    private val applied = mutableListOf<HomeLayout>()
    private val albumClicks = mutableListOf<String>()
    private var memoriesOpened = 0
    private var memoriesCommitted = 0

    private fun setHome(
        content: HomeUiState.Content = sampleHomeContent(),
        layout: HomeLayout = HomeLayout.Default,
        // An 800dp Medium window, whatever this device is.
        tabletWidth: Boolean = false,
        // A short page, so the feed always has room to scroll.
        height: Dp? = null,
    ) {
        persisted = layout
        rule.setContent {
            if (tabletWidth) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(TabletSize)) {
                    HomePage(content, TabletWindow, height)
                }
            } else {
                HomePage(content, windowInfo = null, height = height)
            }
        }
        rule.waitForIdle()
    }

    @Composable
    private fun HomePage(content: HomeUiState.Content, windowInfo: YoinWindowInfo?, height: Dp?) {
        YoinTheme {
            CompositionLocalProvider(
                LocalYoinWindowInfo provides (windowInfo ?: rememberYoinWindowInfo()),
                LocalMotionProfile provides MotionProfile.AdaptiveReduced,
            ) {
                val scope = rememberCoroutineScope()
                controller = remember {
                    HomeEditController(
                        store = ExperienceSessionStore(),
                        scope = scope,
                        applyLayout = { layout ->
                            applied += layout
                            persisted = layout
                        },
                        onEditingChanged = {},
                        startSession = { HomeEditSessionHints(showHeaderHint = true) },
                        feedback = HomeEditFeedback.None,
                        reducedMotion = { true },
                    ).apply { onVmLayout(persisted) }
                }
                LaunchedEffect(persisted) { controller.onVmLayout(persisted) }
                revealState = rememberRevealState()
                HomeContent(
                    uiState = content,
                    sections = persisted.sections,
                    editController = controller,
                    isPlaying = false,
                    playbackSignal = 0f,
                    onNavigateToSettings = {},
                    onNavigateToMemories = { memoriesOpened++ },
                    memoriesRevealState = revealState,
                    onCommitMemoriesReveal = { memoriesCommitted++ },
                    onAlbumClick = { albumId, _ -> albumClicks += albumId },
                    onArtistClick = {},
                    onPlaylistClick = {},
                    onSongClick = {},
                    onRetry = {},
                    buildCoverArtUrl = { "" },
                    modifier = if (height == null) Modifier.fillMaxSize() else Modifier.fillMaxWidth().height(height),
                )
            }
        }
    }

    @Test
    fun should_notFireOnClick_when_cardLongPressed() {
        setHome()

        hero().performTouchInput { longClick() }
        rule.waitForIdle()

        assertTrue(controller.isEditing)
        assertEquals(emptyList<String>(), albumClicks)
    }

    @Test
    fun should_openCard_when_tapped() {
        setHome()

        hero().performTouchInput { click() }
        rule.waitForIdle()

        assertEquals(1, albumClicks.size)
        assertFalse(controller.isEditing)
    }

    @Test
    fun should_scroll_when_draggedBeforeThreshold() {
        setHome(height = 560.dp)

        // 200ms is well inside the long-press timeout: the slop gives the finger to the list.
        feed().performTouchInput { swipeUp(startY = height * .8f, endY = height * .2f, durationMillis = 200) }
        rule.waitForIdle()

        assertTrue(feed().scrollValue(SemanticsProperties.VerticalScrollAxisRange) > 0f)
        assertFalse(controller.isEditing)
        assertEquals(emptyList<String>(), albumClicks)
    }

    @Test
    fun should_enterEdit_when_longPressInPageMargin() {
        setHome(tabletWidth = true)
        // The header starts at the content's left edge; the page margin lies before it.
        val contentLeft = title("Home").boundsInRoot().left
        val y = hero().boundsInRoot().center.y

        rule.onRoot().performTouchInput { longClick(Offset(contentLeft / 2f, y)) }
        rule.waitForIdle()

        assertTrue(contentLeft > 0f)
        assertTrue(controller.isEditing)
        assertEquals(emptyList<String>(), albumClicks)
    }

    @Test
    fun should_scrollShelf_when_raSwipedHorizontally() {
        setHome(layout = HomeLayout.Default.moved(HomeSection.RecentlyAdded, 0))

        // By node id: once panned, the first album may have left the shelf's composition.
        val shelfId = recentlyAddedShelf().fetchSemanticsNode().id
        val shelf = rule.onNode(SemanticsMatcher("Recently Added's shelf") { it.id == shelfId })

        shelf.performTouchInput {
            swipeLeft(startX = right * .9f, endX = left + width * .2f, durationMillis = 200)
        }
        rule.waitForIdle()

        assertTrue(shelf.scrollValue(SemanticsProperties.HorizontalScrollAxisRange) > 0f)
        assertFalse(controller.isEditing)
        assertEquals(emptyList<String>(), albumClicks)
    }

    @Test
    fun should_notOpenMemories_when_longPressAtTop() {
        setHome()
        val top = title("Home").boundsInRoot().center
        val timeout = longPressTimeout()

        rule.onRoot().performTouchInput { down(top) }
        rule.mainClock.advanceTimeBy(timeout + 100L)
        // Still held, the finger pulls down the way Memories opens.
        rule.onRoot().performTouchInput {
            repeat(20) { moveBy(Offset(0f, 15.dp.toPx())) }
            up()
        }
        rule.waitForIdle()

        assertTrue(controller.isEditing)
        assertEquals(1f, revealState.fraction)
        assertEquals(0, memoriesOpened)
        assertEquals(0, memoriesCommitted)
    }

    @Test
    fun should_enterEdit_when_mouseSecondaryClick() {
        setHome()

        hero().performMouseInput { rightClick() }
        rule.waitForIdle()

        assertTrue(controller.isEditing)
        assertEquals(emptyList<String>(), albumClicks)
    }

    @Test
    fun should_notShiftHeldBlock_when_jbiEmptyAndRaLongPressed() {
        // Jump Back In has nothing, so editing gives it a placeholder right above Recently Added.
        setHome(content = sampleHomeContent(widgetGrid = emptyList()))
        val restTop = title("Recently Added").positionInRoot().y
        val press = card("Copper Bell").boundsInRoot().center
        val timeout = longPressTimeout()
        val tolerance = with(rule.density) { HeldBlockTolerance.toPx() }

        rule.onRoot().performTouchInput { down(press) }
        rule.mainClock.advanceTimeBy(timeout + 600L)

        assertTrue(controller.isEditing)
        rule.onNodeWithText(JbiPlaceholder, useUnmergedTree = true).assertDoesNotExist()
        assertEquals(restTop, title("Recently Added").positionInRoot().y, tolerance)

        // The first finger-up lets the placeholder in.
        rule.onRoot().performTouchInput { up() }
        rule.waitForIdle()

        rule.onNodeWithText(JbiPlaceholder, useUnmergedTree = true).assertExists()
        assertTrue(title("Recently Added").positionInRoot().y > restTop + tolerance)
    }

    @Test
    fun should_foldIntoStripsAndPersistOrder_when_liftedAndDragged() {
        setHome()
        val press = hero().boundsInRoot().center
        val timeout = longPressTimeout()

        rule.onRoot().performTouchInput { down(press) }
        rule.mainClock.advanceTimeBy(timeout + 200L)
        assertTrue(controller.isEditing)
        assertEquals(emptyList<HomeLayout>(), applied)

        // Down past every other strip: the first step crosses the slop and folds the feed.
        rule.onRoot().performTouchInput { repeat(24) { moveBy(Offset(0f, 12.dp.toPx())) } }
        assertTrue(rule.runOnIdle { controller.layer?.isCarrying == true })

        // A resting finger lifts with no fling.
        rule.mainClock.advanceTimeBy(200L)
        rule.onRoot().performTouchInput { up() }
        rule.waitForIdle()

        assertFalse(controller.layer!!.isCarrying)
        val order = controller.draft!!.enabledSections
        assertTrue(order.indexOf(HomeSection.Activities) > 0)
        assertEquals(HomeLayout.Default.enabledSections.toSet(), order.toSet())
        // One write per drop, and it is the order on screen.
        assertEquals(1, applied.size)
        assertEquals(order, applied.single().enabledSections)
        assertEquals(order, persisted.enabledSections)

        rule.runOnIdle { controller.commitAndExit() }
        rule.waitForIdle()
        assertFalse(controller.isEditing)
        assertEquals(1, applied.size)
        assertEquals(order, persisted.enabledSections)
    }

    @Test
    fun should_moveHideShow_viaCustomActions() {
        setHome()
        rule.runOnIdle { controller.enter(null, lifted = false) }
        rule.waitForIdle()

        block(1, 4).performCustomAccessibilityActionWithLabel("Move down")
        rule.waitForIdle()
        val moved = listOf(
            HomeSection.JumpBackIn,
            HomeSection.Activities,
            HomeSection.RecentlyAdded,
            HomeSection.Rediscover,
        )
        assertEquals(moved, controller.draft!!.enabledSections)

        block(2, 4).performCustomAccessibilityActionWithLabel("Hide")
        rule.waitForIdle()
        assertEquals(listOf(HomeSection.Activities), controller.draft!!.hiddenSections)

        // The tray row brings it back to the slot it left.
        feed().performScrollToKey("tray-${HomeSection.Activities.id}")
        rule.onNodeWithContentDescription("Show Activities").performClick()
        rule.waitForIdle()

        assertEquals(moved, controller.draft!!.enabledSections)
        assertEquals(3, applied.size)
        assertEquals(controller.draft, applied.last())
        assertTrue(controller.isEditing)
    }

    @Test
    fun should_exit_when_blankTapped() {
        setHome()
        val y = hero().boundsInRoot().center.y
        rule.runOnIdle { controller.enter(null, lifted = false) }
        rule.waitForIdle()

        // The page's very edge beside Activities: off every plate.
        rule.onRoot().performTouchInput { click(Offset(1.dp.toPx(), y)) }
        rule.waitForIdle()

        assertFalse(controller.isEditing)
        assertEquals(emptyList<String>(), albumClicks)
        assertEquals(emptyList<HomeLayout>(), applied)
    }

    @Test
    fun should_exposeEditHomeLongClick_onCards() {
        setHome()
        val cards = rule.onAllNodes(EditHomeLongClick)
        assertTrue(cards.fetchSemanticsNodes().size >= 4)

        cards.onFirst().performSemanticsAction(SemanticsActions.OnLongClick)
        rule.waitForIdle()

        assertTrue(controller.isEditing)
        // Cards are inert while editing, the long-click with them.
        rule.onAllNodes(EditHomeLongClick).assertCountEquals(0)
    }

    // Activities' hero: the album "Blue Hour".
    private fun hero(): SemanticsNodeInteraction = card("Blue Hour")

    private fun card(title: String): SemanticsNodeInteraction = rule.onNode(hasText(title) and hasClickAction())

    private fun title(text: String): SemanticsNodeInteraction = rule.onNodeWithText(text, useUnmergedTree = true)

    // The feed list: the outermost list that scrolls to a key vertically.
    private fun feed(): SemanticsNodeInteraction = rule.onAllNodes(
        hasScrollToKeyAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
    ).onFirst()

    // The horizontal list that holds the first Recently Added album.
    private fun recentlyAddedShelf(): SemanticsNodeInteraction = rule.onNode(
        hasScrollToKeyAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange) and
            hasAnyDescendant(hasText("Copper Bell")),
    )

    // A block's TalkBack stop while editing, by its "Section j of N" state.
    private fun block(position: Int, total: Int): SemanticsNodeInteraction =
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Section $position of $total"))

    private fun longPressTimeout(): Long {
        var timeout = 0L
        rule.onRoot().performTouchInput { timeout = viewConfiguration.longPressTimeoutMillis }
        return timeout
    }

    private fun SemanticsNodeInteraction.boundsInRoot(): Rect = fetchSemanticsNode().boundsInRoot

    private fun SemanticsNodeInteraction.positionInRoot(): Offset = fetchSemanticsNode().positionInRoot

    private fun SemanticsNodeInteraction.scrollValue(axis: SemanticsPropertyKey<ScrollAxisRange>): Float =
        fetchSemanticsNode().config[axis].value()

    private companion object {
        val TabletSize = DpSize(800.dp, 1280.dp)
        val TabletWindow = YoinWindowInfo(
            layoutMode = LayoutMode.Medium,
            isWidthAtLeastMedium = true,
            isHeightAtLeastMedium = true,
            hingeBounds = null,
            chromeForm = ShellChromeForm.CenteredBar,
        )
        const val JbiPlaceholder = "Nothing to jump back into yet"

        // The lift's 1.02 about the press point moves the title a few dp; a placeholder would move it ~130.
        val HeldBlockTolerance = 12.dp

        val EditHomeLongClick = SemanticsMatcher("long-clicks to Edit Home") {
            it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "Edit Home"
        }
    }
}
