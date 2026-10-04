package com.gpo.yoin.testutil

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.TestMonotonicFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * [runTest] with a virtual frame clock: [block] gets a scope whose
 * `withFrameNanos` ticks every 16ms of test time, so `Animatable`s and
 * `animate` run under `advanceTimeBy` / `runCurrent`. The scope is a
 * supervisor child of the test and is cancelled when [block] returns.
 */
@OptIn(ExperimentalTestApi::class)
fun runFrameClockTest(block: suspend TestScope.(scope: CoroutineScope) -> Unit): TestResult = runTest {
    val clock = TestMonotonicFrameClock(this)
    val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]) + clock)
    try {
        block(scope)
    } finally {
        scope.cancel()
    }
}

/**
 * The test's virtual time as an uptime clock, for code that takes an
 * injectable `uptimeMs` (`SystemClock.uptimeMillis()` is 0 in JVM tests).
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.virtualUptimeMs(): () -> Long = { testScheduler.currentTime }
