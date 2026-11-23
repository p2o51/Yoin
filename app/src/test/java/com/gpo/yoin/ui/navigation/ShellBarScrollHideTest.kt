package com.gpo.yoin.ui.navigation

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import com.gpo.yoin.ui.experience.CoveredWindowAnimationGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Test

class ShellBarScrollHideTest {
    private fun scrolledAway(): ShellBarScrollHide = ShellBarScrollHide(CoroutineScope(Job()), DistancePx).apply {
        connection.onPostScroll(
            consumed = Offset(0f, -DistancePx),
            available = Offset.Zero,
            source = NestedScrollSource.UserInput,
        )
        assertEquals(1f, fraction, 0f)
    }

    @Test
    fun should_putBarBackUnseen_when_detailBackRevealsShell() {
        val hide = scrolledAway()

        hide.onWindowRevealed(CoveredWindowAnimationGate.ShellWindowKey)

        assertEquals(0f, hide.fraction, 0f)
    }

    @Test
    fun should_putBarBackUnseen_when_unkeyedDetailRevealsAnyWindow() {
        val hide = scrolledAway()

        hide.onWindowRevealed(CoveredWindowAnimationGate.AnyWindow)

        assertEquals(0f, hide.fraction, 0f)
    }

    @Test
    fun should_keepBarAway_when_nestedDetailRevealsAnotherDetail() {
        val hide = scrolledAway()

        hide.onWindowRevealed("some-detail-window-key")

        assertEquals(1f, hide.fraction, 0f)
    }

    @Test
    fun should_keepBarAway_when_revealIsCleared() {
        val hide = scrolledAway()

        hide.onWindowRevealed(null)

        assertEquals(1f, hide.fraction, 0f)
    }

    private companion object {
        const val DistancePx = 100f
    }
}
