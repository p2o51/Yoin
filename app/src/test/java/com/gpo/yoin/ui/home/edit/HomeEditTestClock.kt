package com.gpo.yoin.ui.home.edit

import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * [runTest] with a 16ms frame clock that, like the app's, drops the frame
 * callback of a cancelled animation. `TestMonotonicFrameClock` still runs it
 * once, which writes a stale value over a snap made in between (a lift
 * stopping a ringing kick, a snap exit).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun runEditClockTest(block: suspend TestScope.(scope: CoroutineScope) -> Unit): TestResult = runTest {
    val driver = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
    lateinit var clock: BroadcastFrameClock
    clock = BroadcastFrameClock {
        driver.launch {
            delay(FrameMs)
            clock.sendFrame(testScheduler.currentTime * 1_000_000L)
        }
    }
    val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]) + clock)
    try {
        block(scope)
    } finally {
        scope.cancel()
        driver.cancel()
    }
}

private const val FrameMs = 16L
