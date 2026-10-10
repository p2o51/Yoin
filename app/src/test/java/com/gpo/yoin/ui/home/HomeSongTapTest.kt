package com.gpo.yoin.ui.home

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.source.MusicSource
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Home song card's tap: at once on the active source; tapped while a
 * snapshot is up ahead of the source (a cold start), once the source is in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeSongTapTest {

    private val played = mutableListOf<Pair<Track, MusicSource>>()

    @Test
    fun should_playAtOnce_when_aSourceIsActive() = runTest {
        val source = source(MediaId.PROVIDER_SUBSONIC)
        val tap = tap(current = { source }, await = { error("no wait with a source up") })

        tap.tap(song("now"))

        assertEquals(listOf("now" to source), played.map { (track, on) -> track.id.rawId to on })
    }

    @Test
    fun should_playOnceTheSourceArrives_when_tappedBeforeIt() = runTest {
        val arrival = CompletableDeferred<MusicSource?>()
        var waitedMs: Long? = null
        val tap = tap(
            current = { null },
            await = { timeoutMs ->
                waitedMs = timeoutMs
                arrival.await()
            }
        )

        tap.tap(song("early"))
        runCurrent()
        assertTrue(played.isEmpty())

        val source = source(MediaId.PROVIDER_SUBSONIC)
        arrival.complete(source)
        runCurrent()

        assertEquals(HomeSongTap.SOURCE_WAIT_MS, waitedMs)
        assertEquals(listOf("early" to source), played.map { (track, on) -> track.id.rawId to on })
    }

    @Test
    fun should_playOnlyTheLatestTap_when_tappedAgainBeforeTheSource() = runTest {
        val arrival = CompletableDeferred<MusicSource?>()
        val tap = tap(current = { null }, await = { arrival.await() })

        tap.tap(song("first"))
        runCurrent()
        tap.tap(song("second"))
        runCurrent()
        arrival.complete(source(MediaId.PROVIDER_SUBSONIC))
        runCurrent()

        assertEquals(listOf("second"), played.map { (track, _) -> track.id.rawId })
    }

    @Test
    fun should_notPlay_when_theSourceThatArrivesIsAnotherProviders() = runTest {
        val tap = tap(current = { null }, await = { source(MediaId.PROVIDER_SPOTIFY) })

        tap.tap(song("subsonic-song"))
        runCurrent()

        assertTrue(played.isEmpty())
    }

    @Test
    fun should_doNothing_when_noSourceArrives() = runTest {
        val tap = tap(current = { null }, await = { null })

        tap.tap(song("lost"))
        runCurrent()

        assertTrue(played.isEmpty())
    }

    private fun TestScope.tap(current: () -> MusicSource?, await: suspend (Long) -> MusicSource?): HomeSongTap =
        HomeSongTap(
            scope = backgroundScope,
            currentSource = current,
            awaitSource = await,
            play = { track, source -> played += track to source }
        )

    private fun source(provider: String): MusicSource = mockk {
        every { id } returns provider
    }

    private fun song(rawId: String): Track = Track(
        id = MediaId.subsonic(rawId),
        title = "Song $rawId",
        artist = "Artist",
        artistId = null,
        album = "Album",
        albumId = null,
        coverArt = null,
        durationSec = 180,
        trackNumber = 1,
        year = null,
        genre = null,
        userRating = null,
        isStarred = false
    )
}
