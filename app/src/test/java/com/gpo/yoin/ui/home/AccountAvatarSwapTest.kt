package com.gpo.yoin.ui.home

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.MonotonicFrameClock
import com.gpo.yoin.data.profile.ProviderKind
import com.gpo.yoin.ui.settings.ProfileCard
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import kotlin.coroutines.resume
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An avatar giving way to another account (a failed switch's flight and
 * Home's stand-in going back to the account in use, QA P9b-1/P9b-2): the
 * swap lands on the busy frames of the old feed coming back, and on the real
 * clock the frame after a stall showed it done — one account, then the other.
 * Paced, a late frame moves the crossfade a step, and it goes on from there.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalMaterial3ExpressiveApi::class)
class AccountAvatarSwapTest {

    /** Frames on demand: every awaiter of a frame gets its time. */
    private class SteppedFrames : MonotonicFrameClock {
        private val awaiters = mutableListOf<(Long) -> Unit>()

        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R =
            suspendCancellableCoroutine { continuation ->
                awaiters += { time -> continuation.resume(onFrame(time)) }
            }

        fun frame(timeNanos: Long) {
            val now = awaiters.toList()
            awaiters.clear()
            now.forEach { it(timeNanos) }
        }
    }

    /** One frame: each account's alpha, by id. */
    private data class Look(val alphas: Map<String, Float>, val idle: Boolean)

    private val effects = YoinMotion.defaultEffectsSpec<Float>(
        role = YoinMotionRole.Standard,
        expressiveScheme = MotionScheme.expressive()
    )
    private val spatial = YoinMotion.defaultSpatialSpec<Float>(
        role = YoinMotionRole.Standard,
        expressiveScheme = MotionScheme.expressive()
    )

    @Test
    fun should_crossfadePartWay_when_theFrameAfterTheSwapIsLate() = runTest {
        val swap = AccountAvatarSwap(target)

        val looks = framesOf(swap, inUse, STALLED)

        // At the late frame both are still there, part-way: no cut.
        val late = looks[LATE]
        assertFalse(late.idle)
        assertTrue("late frame: $late", late.alphas.getValue("b") in PART_WAY)
        assertTrue("late frame: $late", late.alphas.getValue("a") in PART_WAY)
        // It goes on from there, and ends on the account in use alone.
        assertTrue(looks[LATE + 1].alphas.getValue("a") > late.alphas.getValue("a"))
        assertEquals(Look(mapOf("a" to 1f), idle = true), looks.last())
    }

    @Test
    fun should_comeBackFromWhereItWas_when_theSwapTurnsBackMidWay() = runTest {
        val swap = AccountAvatarSwap(target)
        val frames = SteppedFrames()
        val away = launch(frames) { swap.showIn(inUse, effects, spatial) }
        listOf(0L, 16L, 32L).forEach { frame(frames, it) }
        val midWay = swap.alphaOf("b")
        away.cancel()

        val looks = framesOf(swap, target, ON_TIME.map { it + 48L })

        // The target comes back up from where it had got to, not from nothing.
        assertTrue("mid-way: $midWay, then ${looks.first()}", looks.first().alphas.getValue("b") >= midWay)
        assertEquals(Look(mapOf("b" to 1f), idle = true), looks.last())
    }

    @Test
    fun should_stayIdle_when_theSameAccountIsShownAgain() = runTest {
        val swap = AccountAvatarSwap(target)

        val looks = framesOf(swap, target.copy(displayName = "Spotify (renamed)"), ON_TIME.take(3))

        assertTrue(looks.all { it.idle && it.alphas == mapOf("b" to 1f) })
        assertEquals("Spotify (renamed)", swap.shown.single().card.displayName)
    }

    private fun AccountAvatarSwap.alphaOf(id: String): Float = shown.first { it.card.id == id }.alpha.value

    private fun TestScope.frame(frames: SteppedFrames, time: Long) {
        runCurrent()
        frames.frame(time * 1_000_000L)
        runCurrent()
    }

    /** [swap] showing [card] on frames at [times] (ms), and how each frame looks. */
    private fun TestScope.framesOf(swap: AccountAvatarSwap, card: ProfileCard, times: List<Long>): List<Look> {
        val frames = SteppedFrames()
        val job = launch(frames) { swap.showIn(card, effects, spatial) }
        val looks = times.map { time ->
            frame(frames, time)
            Look(swap.shown.associate { it.card.id to it.alpha.value }, swap.idle)
        }
        assertTrue("settled within the frames", job.isCompleted)
        return looks
    }

    private val inUse = ProfileCard(
        id = "a",
        displayName = "qa",
        subtitle = "music.example.com",
        provider = ProviderKind.SUBSONIC,
        isActive = true
    )
    private val target = ProfileCard(
        id = "b",
        displayName = "Spotify",
        subtitle = null,
        provider = ProviderKind.SPOTIFY,
        isActive = false,
        avatarShape = 1
    )

    private companion object {
        // Two frames on time after the swap, then the main thread stalls for 600 ms.
        val STALLED = listOf(0L, 16L) + (616L..3_000L step 16L)
        const val LATE = 2
        val ON_TIME = (0L..3_000L step 16L).toList()
        val PART_WAY = 0.05f..0.95f
    }
}
