package com.gpo.yoin

import android.graphics.Bitmap
import android.graphics.Color
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.remote.ServerCredentials
import com.gpo.yoin.data.remote.SubsonicCacheKey
import com.gpo.yoin.data.source.subsonic.SubsonicMusicSource
import com.gpo.yoin.player.PlaybackState
import com.gpo.yoin.ui.theme.PlaybackThemeState
import java.util.Collections
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The root's playback art (ported from perf review C): the theme, its neighbour edges and Now
 * Playing load a cover from ONE url ([playbackCoverUrl]), and a new window's first frame starts
 * from the playback state as it is ([rootArtworkSnapshotOf]), never from an empty one.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlaybackArtworkRegressionTest {
    private val source = SubsonicMusicSource(ServerCredentials("https://music.example.com", "u", "p"))
    private val resolve: (CoverRef?, Int?) -> String? = { ref, size -> ref?.let { source.resolveCoverUrl(it, size) } }
    private val a = CoverRef.SourceRelative("al-A")
    private val c = CoverRef.SourceRelative("al-C")
    private val d = CoverRef.SourceRelative("al-D")

    /** What YoinActivityRoot loads the current song's cover from, and its neighbours'. */
    private fun url(ref: CoverRef) = playbackCoverUrl(resolve, ref)!!

    private fun solid(color: Int): Bitmap =
        Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun waitUntil(timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(10)
        }
    }

    private fun track(id: String, cover: CoverRef) = Track(
        id = MediaId.subsonic(id), title = id, artist = null, artistId = null, album = null, albumId = null,
        coverArt = cover, durationSec = 200, trackNumber = null, year = null, genre = null, userRating = null,
    )

    @Test
    fun neighbourReadUsesTheHeroUrl_soItWarmsTheNextSongsNowPlayingCover() {
        // Coil's disk key for a Subsonic cover is SubsonicCacheKey (ImageDiskCacheInterceptor).
        val nowPlaying = source.resolveCoverUrl(a, com.gpo.yoin.data.model.CoverSizes.heroPx)!!
        assertEquals(SubsonicCacheKey.of(nowPlaying), SubsonicCacheKey.of(url(a)))
    }

    @Test
    fun skipForwardReusesTheCoverThatJustPlayed_forThePreviousEdge() {
        val loads = Collections.synchronizedList(mutableListOf<String>())
        val loader: suspend (String) -> Bitmap? = { u -> loads += u; solid(Color.rgb(200, 30, 30)) }
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val state = PlaybackThemeState(CoroutineScope(SupervisorJob() + dispatcher))

        state.updateArtwork(url(a), clearWhenMissing = false, loadBitmap = loader)
        waitUntil { state.colorScheme(false) != null }
        loads.clear()

        // Skip forward to C: A becomes the previous neighbour, D the next.
        state.updateArtwork(url(c), clearWhenMissing = false, loadBitmap = loader)
        state.updateNeighbours(previous = url(a), next = url(d), loadBitmap = loader)
        waitUntil { state.neighbours.previous != null && state.neighbours.next != null }

        val refetchedA = loads.filter { it.contains("al-A") }
        assertTrue("A's cover was read again after a skip: $refetchedA", refetchedA.isEmpty())
        dispatcher.close()
    }

    /** What a new window's first frame sends to the app-wide theme, from the root's own snapshot. */
    private fun firstFrame(state: PlaybackThemeState, playback: PlaybackState, loader: suspend (String) -> Bitmap?) {
        val snapshot = rootArtworkSnapshotOf(playback)
        state.updateArtwork(snapshot.coverArt?.let(::url), clearWhenMissing = snapshot.queueEmpty, loadBitmap = loader)
        state.updateNeighbours(
            previous = snapshot.previousArt?.let(::url),
            next = snapshot.nextArt?.let(::url),
            loadBitmap = loader,
        )
    }

    private val playing = PlaybackState(
        currentTrack = track("s-a", a),
        queue = listOf(track("s-c", c), track("s-a", a), track("s-d", d)),
        currentIndex = 1,
        upcoming = listOf(2),
    )

    @Test
    fun aNewWindowsFirstFrameDoesNotResetSharedPlaybackColours() {
        val loader: suspend (String) -> Bitmap? = { solid(Color.rgb(30, 160, 60)) }
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val state = PlaybackThemeState(CoroutineScope(SupervisorJob() + dispatcher))
        firstFrame(state, playing, loader) // the shell
        waitUntil { state.colorScheme(false) != null && state.neighbours.next != null }

        firstFrame(state, playing, loader) // a detail Activity opening
        Thread.sleep(300) // a slow second frame (cold detail Activity)

        assertNotNull("neighbour edges wiped by a new window's first frame", state.neighbours.next)
        assertNotNull("app-wide playback palette cleared by a new window's first frame", state.colorScheme(false))
        assertNotNull("cover field cleared", state.coverField)
        dispatcher.close()
    }

    @Test
    fun theRootsSnapshotOfAPlayingStateIsNeverEmpty() {
        val snapshot = rootArtworkSnapshotOf(playing)
        assertEquals(a, snapshot.coverArt)
        assertEquals(false, snapshot.queueEmpty)
        assertEquals(c, snapshot.previousArt)
        assertEquals(d, snapshot.nextArt)
        // Position ticks don't change it, so they don't recompose the root.
        assertEquals(snapshot, rootArtworkSnapshotOf(playing.copy(position = 12_345, bufferedPosition = 20_000)))
    }
}
