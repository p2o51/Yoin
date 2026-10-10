package com.gpo.yoin.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gpo.yoin.ui.experience.LocalPaneWidthInMotion
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * When Library counts as coming into view ([rememberLibraryInView]): the
 * moments Recents take in what was opened and played since, and the only
 * ones, so a list on screen never re-sorts under the user.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp")
class LibraryInViewTest {

    @get:Rule
    val rule = createComposeRule()

    private var shown = 0
    private var columnWidth by mutableStateOf(400.dp)
    private val widthInMotion = mutableStateOf(false)
    private val owner = TestOwner()

    private fun setLibrary(state: Lifecycle.State = Lifecycle.State.STARTED) {
        owner.registry.currentState = state
        rule.setContent {
            CompositionLocalProvider(
                LocalLifecycleOwner provides owner,
                LocalPaneWidthInMotion provides widthInMotion
            ) {
                Box(Modifier.width(columnWidth).height(200.dp).then(rememberLibraryInView { shown++ }))
            }
        }
        rule.waitForIdle()
    }

    private fun update(block: () -> Unit) {
        rule.runOnIdle(block)
        rule.waitForIdle()
    }

    @Test
    fun should_countOnce_when_libraryComposesInAResumedWindow() {
        setLibrary(Lifecycle.State.RESUMED)

        // Its tab chosen with the window in front: that one moment, not its first width as well.
        assertEquals(1, shown)
    }

    @Test
    fun should_countEachResume_when_theWindowComesBackToTheFront() {
        setLibrary()
        assertEquals(0, shown)

        update { owner.registry.currentState = Lifecycle.State.RESUMED }
        assertEquals(1, shown)

        // A detail page (an Activity on Compact) or the background, then back.
        update { owner.registry.currentState = Lifecycle.State.STARTED }
        update { owner.registry.currentState = Lifecycle.State.RESUMED }
        assertEquals(2, shown)
    }

    @Test
    fun should_countOnlyAWiderRest_when_theColumnWidthChanges() {
        setLibrary()
        // The column's first width is where it starts, not a return.
        assertEquals(0, shown)

        // The detail column closed: Library rests wider.
        update { columnWidth = 600.dp }
        assertEquals(1, shown)

        // Narrower (a detail column opened) never counts.
        update { columnWidth = 500.dp }
        assertEquals(1, shown)

        update { columnWidth = 700.dp }
        assertEquals(2, shown)
    }

    @Test
    fun should_countOnceAtRest_when_theWidthMovesOnASpring() {
        setLibrary()

        // Now Playing's panel closing: every frame wider, none of them a rest.
        update { widthInMotion.value = true }
        update { columnWidth = 450.dp }
        update { columnWidth = 520.dp }
        update { columnWidth = 600.dp }
        assertEquals(0, shown)

        update { widthInMotion.value = false }
        assertEquals(1, shown)
    }

    @Test
    fun should_notCount_when_aMotionComesToRestNarrower() {
        setLibrary()

        update { widthInMotion.value = true }
        update { columnWidth = 300.dp }
        update { widthInMotion.value = false }
        assertEquals(0, shown)

        // Wider than where it last rested, not where it started.
        update { columnWidth = 350.dp }
        assertEquals(1, shown)
    }

    private class TestOwner : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)

        override val lifecycle: Lifecycle get() = registry
    }
}
