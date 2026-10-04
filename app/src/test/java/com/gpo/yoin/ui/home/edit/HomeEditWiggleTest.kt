package com.gpo.yoin.ui.home.edit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gpo.yoin.testutil.virtualUptimeMs
import com.gpo.yoin.ui.home.HomeSection
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The card hooks: cheap at rest (critique 20), banded at the edges, "Edit Home" for TalkBack outside edit mode. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HomeEditWiggleTest {

    @get:Rule
    val rule = createComposeRule()

    private val specs = HomeEditSpecs.create(MotionScheme.expressive(), reduced = false)
    private val density = Density(2f)
    private val card = Size(200f, 200f)

    private var editing by mutableStateOf(false)
    private var layoutReads = 0
    private var safeReads = 0
    private var entered = 0

    private fun TestScope.cardScope(scope: CoroutineScope, top: Float = 600f): HomeEditCardScope {
        val motion = HomeEditMotion(
            scope = scope,
            specs = specs,
            style = HomeWiggleStyle(),
            reduced = { false },
            progress = { if (editing) 1f else 0f },
            editing = { editing },
            feedback = HomeEditFeedback.None,
            uptimeMs = virtualUptimeMs(),
        )
        return HomeEditCardScope(
            section = HomeSection.JumpBackIn,
            motion = motion,
            interactive = { !editing },
            enterEdit = { entered++ },
            blockTopInBox = {
                layoutReads++
                top
            },
            blockCoordinates = { null },
            safeArea = {
                safeReads++
                HomeEditSafeArea(top = 100f, bottom = 2_000f)
            },
            liftGain = { 1f },
        )
    }

    private fun TestScope.frames(ms: Long) {
        var elapsed = 0L
        while (elapsed < ms) {
            Snapshot.sendApplyNotifications()
            advanceTimeBy(16)
            runCurrent()
            elapsed += 16
        }
    }

    @Test
    fun should_skipLayoutReads_when_cardIsAtRest() = runEditClockTest { scope ->
        val cardScope = cardScope(scope)
        assertEquals(0f, cardScope.cardWiggleDeg(0, card, topInBlock = 0f, density), 0f)
        assertEquals(0f, cardScope.blockWiggleDeg(card, { card }, density), 0f)
        assertEquals(0, layoutReads)
        assertEquals(0, safeReads)

        // A kick: now the card needs its position for the edge band.
        cardScope.motion.impulse(HomeSection.JumpBackIn, 1f)
        frames(48)
        assertNotEquals(0f, cardScope.cardWiggleDeg(0, card, topInBlock = 0f, density), 0f)
        assertTrue(layoutReads > 0)
        assertTrue(safeReads > 0)
    }

    @Test
    fun should_alternateCards_when_blockKicked() = runEditClockTest { scope ->
        val cardScope = cardScope(scope)
        cardScope.motion.impulse(HomeSection.JumpBackIn, 1f)
        frames(48)
        val even = cardScope.cardWiggleDeg(0, card, topInBlock = 0f, density)
        val odd = cardScope.cardWiggleDeg(1, card, topInBlock = 0f, density)
        assertEquals(even, -odd, 1e-6f)
        // 100dp wide: A_c 1.1°, and the kick is near its first peak.
        assertTrue(abs(even) in .6f..1.1f)
    }

    @Test
    fun should_stayStill_when_cardTouchesTheBand() = runEditClockTest { scope ->
        // Its top sits on the safe top: no swing however hard it is kicked.
        val cardScope = cardScope(scope, top = 100f)
        cardScope.motion.impulse(HomeSection.JumpBackIn, 1f)
        frames(48)
        assertEquals(0f, cardScope.cardWiggleDeg(0, card, topInBlock = 0f, density), 0f)
        assertNotEquals(0f, cardScope.cardWiggleDeg(0, card, topInBlock = 200f, density), 0f)
    }

    @Test
    fun should_offerEditHomeLongClick_onlyWhenNotEditing() {
        rule.setContent {
            val scope = rememberCoroutineScope()
            val cardScope = remember {
                HomeEditCardScope(
                    section = HomeSection.Activities,
                    motion = HomeEditMotion(
                        scope = scope,
                        specs = specs,
                        style = HomeWiggleStyle(),
                        reduced = { false },
                        progress = { 0f },
                        editing = { editing },
                        feedback = HomeEditFeedback.None,
                    ),
                    interactive = { !editing },
                    enterEdit = { entered++ },
                    blockTopInBox = { 0f },
                    blockCoordinates = { null },
                    safeArea = { HomeEditSafeArea(top = 0f, bottom = 10_000f) },
                    liftGain = { 1f },
                )
            }
            CompositionLocalProvider(LocalHomeEditCardScope provides cardScope) {
                Box(Modifier.testTag(Card).homeEditCard(0).size(100.dp))
            }
        }
        rule.onNodeWithTag(Card)
            .assert(
                SemanticsMatcher("long-click labelled Edit Home") {
                    it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "Edit Home"
                },
            )
            .performSemanticsAction(SemanticsActions.OnLongClick)
        assertEquals(1, entered)

        rule.runOnIdle { editing = true }
        rule.onNodeWithTag(Card).assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))

        rule.runOnIdle { editing = false }
        rule.onNodeWithTag(Card).assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
    }

    private companion object {
        const val Card = "card"
    }
}
