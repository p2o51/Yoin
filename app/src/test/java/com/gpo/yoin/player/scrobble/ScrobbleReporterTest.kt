package com.gpo.yoin.player.scrobble

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ScrobbleReporterTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    private class MemoryStore(var saved: List<PendingScrobble> = emptyList()) : PendingScrobbleStore {
        override fun load() = saved
        override fun save(queue: List<PendingScrobble>) {
            saved = queue
        }
    }

    private class FakeTarget(override val profileId: String, var reports: Boolean = false) : ScrobbleTarget {
        var offline = false
        val rejected = mutableSetOf<String>()
        val nowPlaying = mutableListOf<String>()
        val submitted = mutableListOf<List<PendingScrobble>>()
        val reported = mutableListOf<Pair<String, PlaybackReportState>>()

        override suspend fun nowPlaying(songId: String) {
            if (offline) throw IOException("offline")
            nowPlaying += songId
        }

        override suspend fun submit(plays: List<PendingScrobble>) {
            if (offline) throw IOException("offline")
            if (plays.any { it.songId in rejected }) throw ScrobbleRejectedException("not found")
            submitted += plays
        }

        override suspend fun supportsPlaybackReport() = reports

        override suspend fun reportPlayback(songId: String, state: PlaybackReportState, positionMs: Long) {
            reported += songId to state
        }
    }

    private val store = MemoryStore()
    private var target: ScrobbleTarget? = FakeTarget("p1")
    private val fake get() = target as FakeTarget

    private val reporter = ScrobbleReporter(
        scope = scope,
        store = store,
        target = { target },
        monotonicMs = { scope.testScheduler.currentTime },
        wallMs = { 5_000L + scope.testScheduler.currentTime },
        ioDispatcher = dispatcher,
    )

    private fun play(song: String, playing: Boolean = true, profile: String = "p1") = ScrobbleSnapshot(
        profileId = profile,
        songId = song,
        playKey = "$song#0",
        isPlaying = playing,
        positionMs = 0L,
        durationMs = 200_000L,
    )

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `now playing then a play once the timer runs out`() {
        reporter.onState(play("s1"))
        scope.runCurrent()
        assertEquals(listOf("s1"), fake.nowPlaying)

        scope.advanceTimeBy(100_001L)
        scope.runCurrent()
        assertEquals(listOf(listOf(PendingScrobble("p1", "s1", 5_000L))), fake.submitted)
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun `plays heard offline wait and go out on flush`() {
        fake.offline = true
        reporter.onState(play("s1"))
        scope.advanceTimeBy(100_001L)
        reporter.onState(play("s2"))
        scope.advanceTimeBy(100_001L)
        scope.runCurrent()
        assertEquals(listOf("s1", "s2"), store.saved.map { it.songId })

        fake.offline = false
        reporter.flush()
        scope.runCurrent()
        assertEquals(listOf(listOf("s1", "s2")), fake.submitted.map { batch -> batch.map { it.songId } })
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun `plays wait for their own account`() {
        target = null
        reporter.onState(play("s1"))
        scope.advanceTimeBy(100_001L)
        scope.runCurrent()
        assertEquals(1, store.saved.size)

        target = FakeTarget("p2")
        reporter.flush()
        scope.runCurrent()
        assertTrue(fake.submitted.isEmpty())

        target = FakeTarget("p1")
        reporter.flush()
        scope.runCurrent()
        assertEquals(1, fake.submitted.size)
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun `a play the server refuses is dropped without holding back the rest`() {
        store.saved = listOf(PendingScrobble("p1", "gone", 1L), PendingScrobble("p1", "ok", 2L))
        fake.rejected += "gone"
        reporter.flush()
        scope.runCurrent()
        assertEquals(listOf(listOf(PendingScrobble("p1", "ok", 2L))), fake.submitted)
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun `servers with reportPlayback get live states instead of now playing`() {
        fake.reports = true
        reporter.onState(play("s1"))
        scope.runCurrent()
        reporter.onState(play("s1", playing = false))
        scope.runCurrent()
        assertTrue(fake.nowPlaying.isEmpty())
        assertEquals(
            listOf(PlaybackReportState.STARTING, PlaybackReportState.PLAYING, PlaybackReportState.PAUSED),
            fake.reported.map { it.second },
        )
    }
}
