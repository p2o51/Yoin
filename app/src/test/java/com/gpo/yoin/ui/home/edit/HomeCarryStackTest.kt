package com.gpo.yoin.ui.home.edit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.home.HomeSection
import com.gpo.yoin.ui.home.HomeSection.Activities
import com.gpo.yoin.ui.home.HomeSection.JumpBackIn
import com.gpo.yoin.ui.home.HomeSection.RecentlyAdded
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The strip overlay: plate colours (critique 8) and its life with the carry. */
@RunWith(RobolectricTestRunner::class)
class HomeCarryStackTest {

    @get:Rule
    val rule = createComposeRule()

    private val high = Color(0xFF303030)
    private val highest = Color(0xFF505050)
    private val cover = Color(0xFFB04020)

    @Test
    fun should_followLiftTint_when_carried() {
        assertEquals(high, stripPlateColor(true, 0f, high, highest, null, 0f))
        assertEquals(highest, stripPlateColor(true, 1f, high, highest, null, 0f))
        assertEquals(lerp(high, highest, .5f), stripPlateColor(true, .5f, high, highest, null, 0f))
        // The others keep the feed plate's colour whatever the lift does.
        assertEquals(high, stripPlateColor(false, 1f, high, highest, null, 0f))
    }

    @Test
    fun should_leanEveryStripTowardItsCover_when_tinted() {
        assertEquals(lerp(high, cover, .3f), stripPlateColor(false, 0f, high, highest, cover, .3f))
        // The carried strip leans from its lifted colour.
        assertEquals(lerp(highest, cover, .3f), stripPlateColor(true, 1f, high, highest, cover, .3f))
    }

    @Test
    fun should_keepPlateColour_when_noCoverOrTintTooSmall() {
        assertEquals(high, stripPlateColor(false, 0f, high, highest, null, .3f))
        // Tint is a 0–.3 weight: the prototype's 0.05% floor is .0005.
        assertEquals(high, stripPlateColor(false, 0f, high, highest, cover, .0004f))
        assertEquals(lerp(high, cover, .001f), stripPlateColor(false, 0f, high, highest, cover, .001f))
    }

    @Test
    fun should_composeOnlyWhileCarrying() {
        lateinit var engine: HomeCarryEngine
        rule.setContent {
            engine = rememberTestEngine()
            Box(Modifier.size(412.dp, 760.dp)) {
                HomeCarryStack(
                    engine = engine,
                    covers = { listOf(StripCover(null, CircleShape, cover)) },
                    modifier = Modifier.testTag(StackTag),
                )
            }
        }
        rule.onNodeWithTag(StackTag).assertDoesNotExist()

        rule.runOnIdle {
            engine.liftBlock(JumpBackIn, Offset.Zero)
            engine.start(fingerY = 600f, uptimeMs = 0L)
        }
        rule.onNodeWithTag(StackTag).assertExists()

        rule.runOnIdle {
            engine.move(760f, 0L)
            engine.release(cancelled = false, uptimeMs = 1000L)
        }
        rule.waitUntil(timeoutMillis = 3000) { !engine.isCarrying }
        rule.onNodeWithTag(StackTag).assertDoesNotExist()
    }

    @Test
    fun should_composeAheadOfACarry_when_warm() {
        lateinit var engine: HomeCarryEngine
        var warm by mutableIntStateOf(0)
        rule.setContent {
            engine = rememberTestEngine()
            Box(Modifier.size(412.dp, 760.dp)) {
                HomeCarryStack(
                    engine = engine,
                    covers = { listOf(StripCover(null, CircleShape, cover)) },
                    sections = StillHost.displayedOrder(),
                    warmCount = { warm },
                    modifier = Modifier.testTag(StackTag),
                )
            }
        }
        rule.onNodeWithTag(StackTag).assertDoesNotExist()

        // Edit mode at rest: composed before any carry.
        rule.runOnIdle { warm = 1 }
        rule.onNodeWithTag(StackTag).assertExists()

        // A carry keeps it, and so does the warmth going while it runs.
        rule.runOnIdle {
            engine.liftBlock(JumpBackIn, Offset.Zero)
            engine.start(fingerY = 600f, uptimeMs = 0L)
            warm = 0
        }
        rule.onNodeWithTag(StackTag).assertExists()

        rule.runOnIdle {
            engine.move(760f, 0L)
            engine.release(cancelled = false, uptimeMs = 1000L)
        }
        rule.waitUntil(timeoutMillis = 3000) { !engine.isCarrying }
        rule.onNodeWithTag(StackTag).assertDoesNotExist()
    }

    @Test
    fun should_warmTheStrips_when_editModeIsAtRest() {
        assertEquals(StripWarmth.Warm, stripWarmth(editing = true, moving = false))
        // Entering: wait for P to settle; exiting: keep them until it has.
        assertEquals(StripWarmth.Keep, stripWarmth(editing = true, moving = true))
        assertEquals(StripWarmth.Keep, stripWarmth(editing = false, moving = true))
        assertEquals(StripWarmth.Cold, stripWarmth(editing = false, moving = false))
    }

    @Composable
    private fun rememberTestEngine(): HomeCarryEngine {
        val scope = rememberCoroutineScope()
        val density = Density(2f)
        return remember {
            val specs = HomeEditSpecs.create(MotionScheme.expressive(), reduced = true)
            val motion = HomeEditMotion(
                scope = scope,
                specs = specs,
                style = HomeWiggleStyle(),
                reduced = { true },
                progress = { 1f },
                editing = { true },
                feedback = HomeEditFeedback.None,
                uptimeMs = { 0L },
            )
            HomeCarryEngine(
                scope = scope,
                specs = specs,
                motion = motion,
                feedback = HomeEditFeedback.None,
                density = density,
                commitOrder = { true },
                finishDeferredExit = {},
                uptimeMs = { 0L },
            ).apply { host = StillHost }
        }
    }

    private object StillHost : CarryHost {
        override fun displayedOrder(): List<HomeSection> = listOf(Activities, JumpBackIn, RecentlyAdded)

        override fun plateRect(section: HomeSection): Rect? = when (section) {
            Activities -> Rect(16f, 100f, 808f, 500f)
            JumpBackIn -> Rect(16f, 520f, 808f, 900f)
            else -> null
        }

        override fun isAbove(section: HomeSection): Boolean = false

        override fun safeArea(): HomeEditSafeArea = HomeEditSafeArea(top = 88f, bottom = 1344f)

        override fun contentLeft(): Float = 32f

        override fun contentWidth(): Float = 760f

        override suspend fun anchorAndAwaitLayout(dropped: HomeSection, order: List<HomeSection>, slotTop: Float) = Unit
    }

    private companion object {
        const val StackTag = "carry-stack"
    }
}
