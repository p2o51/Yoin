package com.gpo.yoin.ui.home.edit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.experience.ExperienceSessionStore
import com.gpo.yoin.ui.home.HomeEditSessionHints
import com.gpo.yoin.ui.home.HomeSection
import com.gpo.yoin.ui.home.HomeSectionTitle
import com.gpo.yoin.ui.theme.YoinTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The edit block's chrome: badges gated on P, the lone section's missing handle, TalkBack, placeholders. */
@RunWith(RobolectricTestRunner::class)
class HomeEditBlockTest {

    @get:Rule
    val rule = createComposeRule()

    private val specs = HomeEditSpecs.create(MotionScheme.expressive(), reduced = true)
    private lateinit var deps: HomeEditDeps
    private var index by mutableIntStateOf(1)
    private var count by mutableIntStateOf(4)

    private fun setBlock(section: HomeSection = HomeSection.JumpBackIn, placeholder: Boolean = false) {
        rule.setContent {
            YoinTheme {
                val scope = rememberCoroutineScope()
                val density = LocalDensity.current
                deps = remember {
                    val controller = HomeEditController(
                        store = ExperienceSessionStore(),
                        scope = scope,
                        applyLayout = {},
                        onEditingChanged = {},
                        startSession = { HomeEditSessionHints() },
                        feedback = HomeEditFeedback.None,
                        reducedMotion = { true },
                    )
                    val motion = HomeEditMotion(
                        scope = scope,
                        specs = specs,
                        style = HomeWiggleStyle(),
                        reduced = { true },
                        progress = controller.progressReader,
                        editing = { controller.isEditing },
                        feedback = HomeEditFeedback.None,
                    )
                    HomeEditDeps(
                        controller = controller,
                        motion = motion,
                        press = HomeEditPressState(),
                        engine = HomeCarryEngine(
                            scope = scope,
                            specs = specs,
                            motion = motion,
                            feedback = HomeEditFeedback.None,
                            density = density,
                            commitOrder = controller::commitOrder,
                            finishDeferredExit = controller::finishDeferredExit,
                        ),
                        targets = HomeEditTargets(),
                        specs = specs,
                    )
                }
                HomeEditBlock(
                    section = section,
                    displayIndex = index,
                    displayCount = count,
                    deps = deps,
                    placeholder = placeholder,
                    wholeBlockWiggle = placeholder,
                    itemSpacing = 18.dp,
                    blockTopInBox = { 0f },
                    safeArea = { HomeEditSafeArea(top = 0f, bottom = 10_000f) },
                    modifier = Modifier.testTag(BlockTag),
                ) {
                    Column(Modifier.fillMaxWidth().testTag(ContentTag)) {
                        // The feed's own section title, which carries the "Edit Home" action.
                        HomeSectionTitle(text = section.title)
                        Text(text = BodyText)
                    }
                }
            }
        }
    }

    private fun enterEdit(origin: HomeSection = HomeSection.JumpBackIn) {
        rule.runOnIdle { deps.controller.enter(origin, lifted = false) }
        rule.waitForIdle()
    }

    private fun snapProgress(p: Float) {
        rule.runOnIdle { deps.controller.progress.snapTo(p) }
        rule.waitForIdle()
    }

    private fun block(position: Int, total: Int): SemanticsNodeInteraction =
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Section $position of $total"))

    private fun SemanticsNodeInteraction.actionLabels(): List<String> =
        fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }

    private fun SemanticsNodeInteraction.runAction(label: String) {
        val action = fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == label }
        rule.runOnIdle { action.action() }
        rule.waitForIdle()
    }

    @Test
    fun should_notComposeBadges_when_progressAtRest() {
        setBlock()

        rule.onNodeWithContentDescription("Hide Jump Back In").assertDoesNotExist()
        rule.onNodeWithTag(homeEditHandleTag(HomeSection.JumpBackIn), useUnmergedTree = true).assertDoesNotExist()

        // Just off rest they wait for this block's ripple (a lone block's ripple is P).
        snapProgress(.02f)
        rule.onNodeWithContentDescription("Hide Jump Back In").assertDoesNotExist()

        // Half way to where they pop in: both are composed, still invisible.
        snapProgress(.2f)
        rule.onNodeWithContentDescription("Hide Jump Back In").assertExists()
        rule.onNodeWithTag(homeEditHandleTag(HomeSection.JumpBackIn), useUnmergedTree = true).assertExists()

        snapProgress(.1f)
        rule.onNodeWithContentDescription("Hide Jump Back In").assertDoesNotExist()
    }

    @Test
    fun should_composeBadgesAhead_when_aBlockCharges() {
        setBlock()
        rule.onNodeWithContentDescription("Hide Jump Back In").assertDoesNotExist()

        // Part way into a long press: ready before the entry, which then composes none of it.
        rule.runOnIdle { runBlocking { deps.press.charge.snapTo(.5f) } }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Hide Jump Back In").assertExists()

        // Let go before the threshold: gone again.
        rule.runOnIdle { runBlocking { deps.press.charge.snapTo(0f) } }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Hide Jump Back In").assertDoesNotExist()
    }

    @Test
    fun should_hideHandle_when_singleSection() {
        index = 0
        count = 1
        setBlock()
        enterEdit()

        rule.onNodeWithContentDescription("Hide Jump Back In").assertExists()
        rule.onNodeWithTag(homeEditHandleTag(HomeSection.JumpBackIn), useUnmergedTree = true).assertDoesNotExist()

        count = 2
        rule.waitForIdle()
        rule.onNodeWithTag(homeEditHandleTag(HomeSection.JumpBackIn), useUnmergedTree = true).assertExists()
    }

    @Test
    fun should_exposeMoveAndHideActions_when_editing() {
        setBlock()
        // Outside edit mode the block is not a TalkBack stop of its own.
        block(2, 4).assertDoesNotExist()

        enterEdit()
        assertEquals(listOf("Move up", "Move down", "Hide"), block(2, 4).actionLabels())

        block(2, 4).runAction("Move up")
        assertEquals(
            listOf(HomeSection.JumpBackIn, HomeSection.Activities, HomeSection.RecentlyAdded, HomeSection.Rediscover),
            deps.controller.draft!!.enabledSections,
        )

        block(2, 4).runAction("Hide")
        assertFalse(HomeSection.JumpBackIn in deps.controller.draft!!.enabledSections)
        assertEquals(listOf(HomeSection.JumpBackIn), deps.controller.draft!!.hiddenSections)
    }

    @Test
    fun should_omitMoveUp_when_firstSection() {
        index = 0
        setBlock(section = HomeSection.Activities)
        enterEdit(HomeSection.Activities)
        assertEquals(listOf("Move down", "Hide"), block(1, 4).actionLabels())

        // The last block can't move down.
        rule.runOnIdle { index = 3 }
        rule.waitForIdle()
        assertEquals(listOf("Move up", "Hide"), block(4, 4).actionLabels())
    }

    @Test
    fun should_renderPlaceholderCopy_when_placeholder() {
        setBlock(placeholder = true)

        rule.onNodeWithText("Nothing to jump back into yet").assertExists()
        rule.onNodeWithText("Jump Back In").assertExists()
        // The placeholder stands in for the content.
        rule.onNodeWithText(BodyText).assertDoesNotExist()
        assertEquals("Nothing added this week", homeEditPlaceholderText(HomeSection.RecentlyAdded))
        // No score bar any more: any album or song rated or written about comes back.
        assertEquals(
            "Albums and songs you rated or wrote about come back here when it's been a while",
            homeEditPlaceholderText(HomeSection.Rediscover),
        )
    }

    @Test
    fun should_offerEditHomeOnSectionTitle_when_notEditing() {
        setBlock()
        val title = rule.onNodeWithText("Jump Back In")
        assertEquals(listOf("Edit Home"), title.actionLabels())

        title.runAction("Edit Home")
        assertTrue(deps.controller.isEditing)
        rule.waitForIdle()
        // Editing: the title is part of the block's own TalkBack stop, with no Edit Home.
        assertNull(
            rule.onNodeWithText("Jump Back In", useUnmergedTree = true)
                .fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions),
        )
    }

    @Test
    fun should_drawShippedV1Plate_when_noVariantIsProvided() {
        var variant: HomePlateVariant? = null
        rule.setContent { variant = LocalHomePlateVariant.current }
        rule.waitForIdle()
        // Production never provides the local (only the QA harness does): Home draws V1.
        assertEquals(HomePlateVariant.V1, variant)
    }

    @Test
    fun should_layOutAtItsContent_when_layerReachesPastIt() {
        setBlock()
        val content = rule.onNodeWithTag(ContentTag, useUnmergedTree = true).getBoundsInRoot()
        // The layer's outset is room to draw a fade in, not size: the block sits exactly on its content.
        assertEquals(content, rule.onNodeWithTag(BlockTag, useUnmergedTree = true).getBoundsInRoot())
        assertTrue(content.right > content.left && content.bottom > content.top)

        enterEdit()
        snapProgress(1f)
        assertEquals(content, rule.onNodeWithTag(ContentTag, useUnmergedTree = true).getBoundsInRoot())
        assertEquals(content, rule.onNodeWithTag(BlockTag, useUnmergedTree = true).getBoundsInRoot())
    }

    private companion object {
        const val BodyText = "Section body"
        const val BlockTag = "block"
        const val ContentTag = "content"
    }
}
