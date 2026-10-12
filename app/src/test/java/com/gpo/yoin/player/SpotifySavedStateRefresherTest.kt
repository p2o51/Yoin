package com.gpo.yoin.player

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.source.spotify.EXTRA_SPOTIFY_URI_TYPE
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PlaybackManager's Spotify heart check (P4): once per track change, App
 * Remote first, the Web API only when App Remote fails on a track change and
 * the track stayed current; later PlayerState events recheck at most every
 * 30 s, over App Remote only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpotifySavedStateRefresherTest {

    private val appRemoteAsks = mutableListOf<String>()
    private var appRemoteAnswer: suspend (String) -> Boolean? = { true }
    private val sink = FakeSink()

    @Test
    fun should_askOnce_when_theSameTrackIsReportedAgainAndAgain() = runTest {
        val refresher = refresher()

        repeat(5) { refresher.onPlayerState(track("a")) }
        runCurrent()

        assertEquals(listOf("spotify:track:a"), appRemoteAsks)
        assertEquals(listOf(Triple(PROFILE, MediaId.spotify("a"), true)), sink.recorded)
    }

    @Test
    fun should_askAgainOnlyAfterTheInterval_when_laterPlayerStatesArrive() = runTest {
        val refresher = refresher()
        refresher.onPlayerState(track("a"))
        runCurrent()

        advanceTimeBy(RECHECK_MS - 1)
        refresher.onPlayerState(track("a"))
        runCurrent()
        assertEquals(1, appRemoteAsks.size)

        advanceTimeBy(1)
        refresher.onPlayerState(track("a"))
        runCurrent()
        assertEquals(2, appRemoteAsks.size)
    }

    @Test
    fun should_askAtOnce_when_theTrackChanges() = runTest {
        val refresher = refresher()

        listOf("a", "b", "a").forEach { id ->
            refresher.onPlayerState(track(id))
            runCurrent()
        }

        assertEquals(listOf("spotify:track:a", "spotify:track:b", "spotify:track:a"), appRemoteAsks)
    }

    @Test
    fun should_fallBackToTheWebApi_when_appRemoteFailsOnATrackChange() = runTest {
        appRemoteAnswer = { null }
        val refresher = refresher()

        refresher.onPlayerState(track("a"))
        advanceTimeBy(FALLBACK_DELAY_MS - 1)
        runCurrent()
        assertEquals(emptyList<MediaId>(), sink.webChecks)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(MediaId.spotify("a")), sink.webChecks)
    }

    @Test
    fun should_spendNoWebRequest_when_skippingPastATrackBeforeTheFallback() = runTest {
        appRemoteAnswer = { null }
        val refresher = refresher()

        refresher.onPlayerState(track("a"))
        advanceTimeBy(FALLBACK_DELAY_MS / 2)
        refresher.onPlayerState(track("b"))
        advanceTimeBy(FALLBACK_DELAY_MS)
        runCurrent()

        assertEquals(listOf(MediaId.spotify("b")), sink.webChecks)
    }

    @Test
    fun should_neverUseTheWebApi_when_aRecheckFails() = runTest {
        val refresher = refresher()
        refresher.onPlayerState(track("a"))
        runCurrent()

        appRemoteAnswer = { null }
        advanceTimeBy(RECHECK_MS)
        refresher.onPlayerState(track("a"))
        advanceTimeBy(FALLBACK_DELAY_MS * 2)
        runCurrent()

        assertEquals(2, appRemoteAsks.size)
        assertEquals(emptyList<MediaId>(), sink.webChecks)
    }

    @Test
    fun should_askNothing_when_spotifyPlaysAnEpisodeOrALocalFile() = runTest {
        val refresher = refresher()

        refresher.onPlayerState(track("ep1", uriType = "episode"))
        refresher.onPlayerState(track("215", uriType = "local"))
        refresher.onPlayerState(track("spotify-remote-unknown"))
        advanceTimeBy(FALLBACK_DELAY_MS * 2)
        runCurrent()

        assertEquals(emptyList<String>(), appRemoteAsks)
        assertEquals(emptyList<MediaId>(), sink.webChecks)
    }

    @Test
    fun should_dropTheFallback_when_theAccountChangesBeforeIt() = runTest {
        appRemoteAnswer = { null }
        val refresher = refresher()

        refresher.onPlayerState(track("a"))
        runCurrent()
        sink.profileId = "other"
        advanceTimeBy(FALLBACK_DELAY_MS)
        runCurrent()

        assertEquals(emptyList<MediaId>(), sink.webChecks)
    }

    @Test
    fun should_askAgain_when_theSameTrackReturnsAfterAReset() = runTest {
        val gate = CompletableDeferred<Boolean?>()
        appRemoteAnswer = { gate.await() }
        val refresher = refresher()
        refresher.onPlayerState(track("a"))
        runCurrent()

        // Account switch: the check out is cancelled and its answer never lands.
        refresher.reset()
        gate.complete(true)
        runCurrent()
        assertEquals(emptyList<Triple<String, MediaId, Boolean>>(), sink.recorded)

        appRemoteAnswer = { false }
        refresher.onPlayerState(track("a"))
        runCurrent()
        assertEquals(listOf(Triple(PROFILE, MediaId.spotify("a"), false)), sink.recorded)
    }

    private fun TestScope.refresher() = SpotifySavedStateRefresher(
        scope = backgroundScope,
        clock = { testScheduler.currentTime },
        appRemoteState = { uri ->
            appRemoteAsks += uri
            appRemoteAnswer(uri)
        },
        sink = sink,
        recheckIntervalMs = RECHECK_MS,
        webFallbackDelayMs = FALLBACK_DELAY_MS
    )

    private fun track(rawId: String, uriType: String? = null) = Track(
        id = MediaId.spotify(rawId),
        title = rawId,
        artist = null,
        artistId = null,
        album = null,
        albumId = null,
        coverArt = null,
        durationSec = null,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null,
        extras = uriType?.let { mapOf(EXTRA_SPOTIFY_URI_TYPE to it) }.orEmpty()
    )

    private class FakeSink : SpotifySavedStateSink {
        var profileId: String? = PROFILE
        val recorded = mutableListOf<Triple<String, MediaId, Boolean>>()
        val webChecks = mutableListOf<MediaId>()

        override fun currentProfileId(): String? = profileId

        override fun recordAppRemoteState(profileId: String, trackId: MediaId, saved: Boolean) {
            recorded += Triple(profileId, trackId, saved)
        }

        override suspend fun checkWithWebApi(track: Track) {
            webChecks += track.id
        }
    }

    private companion object {
        const val PROFILE = "spotify-a"
        const val RECHECK_MS = 30_000L
        const val FALLBACK_DELAY_MS = 800L
    }
}
