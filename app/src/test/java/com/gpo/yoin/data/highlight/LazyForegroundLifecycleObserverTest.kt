package com.gpo.yoin.data.highlight

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LazyForegroundLifecycleObserverTest {

    private class FakeOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun should_neverCallOnForeground_when_onStopArrivesBeforeLazyBuildCompletes() {
        val main = StandardTestDispatcher()
        val scope = TestScope(main)
        val owner = FakeOwner()
        val inFactory = CountDownLatch(1)
        val releaseFactory = CountDownLatch(1)
        val builtCount = AtomicInteger(0)
        val events = mutableListOf<String>()
        val ioExecutor = Executors.newSingleThreadExecutor()

        try {
            val observer = LazyForegroundLifecycleObserver(
                scope = scope,
                mainDispatcher = main,
                buildDispatcher = ioExecutor.asCoroutineDispatcher(),
                factory = {
                    builtCount.incrementAndGet()
                    inFactory.countDown()
                    releaseFactory.await(5, TimeUnit.SECONDS)
                    "processor"
                },
                onForeground = { events += "fg:$it" },
                onBackground = { events += "bg:$it" },
            )

            // Scenario A: app starts, begins building off-thread, then immediately stops.
            observer.onStart(owner)
            scope.runCurrent()
            assertTrue(inFactory.await(5, TimeUnit.SECONDS))

            observer.onStop(owner)
            releaseFactory.countDown()
            Thread.sleep(50)
            scope.runCurrent()

            assertEquals(1, builtCount.get())
            assertEquals("must not start a visit while already backgrounded", emptyList<String>(), events)

            // Next foreground transition reuses the built instance synchronously on Main.
            observer.onStart(owner)
            assertEquals(listOf("fg:processor"), events)
            observer.onStop(owner)
            assertEquals(listOf("fg:processor", "bg:processor"), events)
            assertEquals(1, builtCount.get())
        } finally {
            ioExecutor.shutdownNow()
        }
    }

    @Test
    fun should_callOnForegroundOnce_when_stopThenStartArrivesWhileLazyBuildIsInFlight() = runBlocking {
        val main = StandardTestDispatcher()
        val scope = TestScope(main)
        val owner = FakeOwner()
        val gate = CompletableDeferred<Unit>()
        val factoryCalls = AtomicInteger(0)
        val events = mutableListOf<String>()
        val buildDispatcher = StandardTestDispatcher(scope.testScheduler)

        val observer = LazyForegroundLifecycleObserver(
            scope = scope,
            mainDispatcher = main,
            buildDispatcher = buildDispatcher,
            factory = {
                factoryCalls.incrementAndGet()
                runBlocking { gate.await() }
                "processor"
            },
            onForeground = { events += "fg" },
            onBackground = { events += "bg" },
        )

        observer.onStart(owner)
        observer.onStop(owner)
        observer.onStart(owner)
        gate.complete(Unit)
        scope.runCurrent()

        assertEquals(1, factoryCalls.get())
        assertEquals(listOf("fg"), events)
    }
}
