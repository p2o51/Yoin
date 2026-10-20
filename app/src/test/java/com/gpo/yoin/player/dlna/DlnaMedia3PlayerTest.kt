package com.gpo.yoin.player.dlna

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class DlnaMedia3PlayerTest {
    @Test
    fun should_followOnePermutationInBothDirections_when_shuffleIsEnabled() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val transport = FakeTransport()
        val player = DlnaMedia3Player(transport) {}
        try {
            player.connect(device(), (0..5).map { item("track$it") }, 2, 0, true)
            player.shuffleModeEnabled = true
            val order = shuffledOrder(player)
            assertEquals(6, order.distinct().size)
            assertEquals(2, order.first())
            assertEquals(order, shuffledOrder(Timeline.fromBundle(player.currentTimeline.toBundle())))
            assertEquals(order[1], player.nextMediaItemIndex)
            player.seekToNextMediaItem()
            assertEquals(order[1], player.currentMediaItemIndex)
            player.seekToPreviousMediaItem()
            assertEquals(order[0], player.currentMediaItemIndex)
            val traversed = mutableListOf(player.currentMediaItemIndex)
            while (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                traversed.add(player.currentMediaItemIndex)
            }
            assertEquals(order, traversed)
            assertEquals(C.INDEX_UNSET, player.nextMediaItemIndex)
            player.repeatMode = Player.REPEAT_MODE_ALL
            player.seekToNextMediaItem()
            assertEquals(order.first(), player.currentMediaItemIndex)
            player.seekToPreviousMediaItem()
            assertEquals(order.last(), player.currentMediaItemIndex)
        } finally {
            player.release()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun should_matchManualNextAndStopAtCycleEnd_when_shuffledTracksFinishNaturally() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val transport = FakeTransport()
        val player = DlnaMedia3Player(transport) {}
        try {
            player.connect(device(), (0..3).map { item("track$it") }, 1, 0, true)
            player.shuffleModeEnabled = true
            val order = shuffledOrder(player)
            for (next in order.drop(1)) {
                assertEquals(next, player.nextMediaItemIndex)
                transport.position = 30000
                transport.state = "STOPPED"
                advanceTimeBy(1001)
                runCurrent()
                assertEquals(next, player.currentMediaItemIndex)
                assertTrue(player.isPlaying)
            }
            transport.position = 30000
            transport.state = "STOPPED"
            advanceTimeBy(1001)
            runCurrent()
            assertEquals(order.last(), player.currentMediaItemIndex)
            assertEquals(Player.STATE_ENDED, player.playbackState)
            assertFalse(player.isPlaying)
            player.repeatMode = Player.REPEAT_MODE_ALL
            player.seekTo(player.currentMediaItemIndex, 0)
            player.play()
            transport.position = 30000
            transport.state = "STOPPED"
            advanceTimeBy(1001)
            runCurrent()
            assertEquals(order.first(), player.currentMediaItemIndex)
            player.repeatMode = Player.REPEAT_MODE_ONE
            transport.position = 30000
            transport.state = "STOPPED"
            advanceTimeBy(1001)
            runCurrent()
            assertEquals(order.first(), player.currentMediaItemIndex)
        } finally {
            player.release()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun should_preserveCurrentEntryAndSurvivingOrder_when_shuffledQueueIsEdited() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val transport = FakeTransport()
        val player = DlnaMedia3Player(transport) {}
        try {
            player.connect(device(), (0..4).map { item("track$it") }, 2, 0, true)
            player.shuffleModeEnabled = true
            val initial = shuffledIds(player)
            player.moveMediaItem(0, 4)
            assertEquals("track2", player.currentMediaItem?.mediaId)
            assertEquals(initial, shuffledIds(player))
            player.addMediaItem(player.currentMediaItemIndex + 1, item("play-next"))
            assertEquals("track2", player.currentMediaItem?.mediaId)
            assertEquals(listOf(initial.first(), "play-next") + initial.drop(1), shuffledIds(player))
            val removeIndex = (0 until player.mediaItemCount).first {
                player.getMediaItemAt(it).mediaId == initial.last()
            }
            player.removeMediaItem(removeIndex)
            assertEquals("track2", player.currentMediaItem?.mediaId)
            assertEquals(
                (listOf(initial.first(), "play-next") + initial.drop(1)).filter { it != initial.last() },
                shuffledIds(player)
            )
            player.seekToNextMediaItem()
            assertEquals("play-next", player.currentMediaItem?.mediaId)
            val nextId = player.getMediaItemAt(player.nextMediaItemIndex).mediaId
            player.removeMediaItem(player.currentMediaItemIndex)
            assertEquals(nextId, player.currentMediaItem?.mediaId)
            player.shuffleModeEnabled = false
            assertEquals(nextId, player.currentMediaItem?.mediaId)
        } finally {
            player.release()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun should_controlRemoteQueueAndConfirmStop_when_transferringPlayback() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val transport = FakeTransport()
        val player = DlnaMedia3Player(transport) {}
        try {
            player.connect(device(), listOf(item("one"), item("two")), 0, 5000, true)
            assertTrue(player.isPlaying)
            assertEquals(5000L, player.currentPosition)
            assertEquals(listOf("uri:one", "seek:5000", "play"), transport.actions)
            player.pause()
            assertFalse(player.isPlaying)
            player.seekToNextMediaItem()
            assertEquals(1, player.currentMediaItemIndex)
            assertEquals("two", player.currentMediaItem?.mediaId)
            assertFalse(player.isPlaying)
            player.play()
            assertTrue(player.isPlaying)
            player.disconnect(stopRemote = true)
            assertFalse(player.hasDevice())
            assertEquals("STOPPED", transport.state)
            assertTrue(transport.stopObservations > 0)
        } finally {
            player.release()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun should_advanceAtNaturalEndAndRetainSelectionAfterStopFailure_when_speakerIsPlaying() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val transport = FakeTransport()
        val player = DlnaMedia3Player(transport) {}
        try {
            player.connect(device(), listOf(item("one"), item("two")), 0, 0, true)
            transport.position = 30000
            transport.state = "STOPPED"
            advanceTimeBy(1001)
            runCurrent()
            assertEquals(1, player.currentMediaItemIndex)
            assertTrue(player.isPlaying)
            transport.rejectStop = true
            assertTrue(runCatching { player.disconnect(stopRemote = true) }.isFailure)
            assertTrue(player.hasDevice())
        } finally {
            player.release()
            Dispatchers.resetMain()
        }
    }

    private fun item(id: String): MediaItem = MediaItem.Builder().setMediaId(id)
        .setUri(Uri.parse("http://192.0.2.1/$id"))
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(id).setExtras(
                Bundle().apply {
                    putInt("duration_secs", 30)
                }
            ).build()
        ).build()

    private fun shuffledOrder(player: Player): List<Int> = shuffledOrder(player.currentTimeline)

    private fun shuffledOrder(timeline: Timeline): List<Int> = buildList {
        var cursor = timeline.getFirstWindowIndex(true)
        while (cursor != C.INDEX_UNSET) {
            add(cursor)
            cursor = timeline.getNextWindowIndex(cursor, Player.REPEAT_MODE_OFF, true)
        }
    }

    private fun shuffledIds(player: Player): List<String> =
        shuffledOrder(player).map { player.getMediaItemAt(it).mediaId }

    private fun device(): DlnaDevice = DlnaDevice(
        "id",
        "Speaker",
        "",
        "",
        URI("http://192.0.2.2/device"),
        DlnaService("urn:schemas-upnp-org:service:AVTransport:1", URI("http://192.0.2.2/control")),
        null
    )

    private class FakeTransport : DlnaTransport {
        val actions = mutableListOf<String>()
        var state = "STOPPED"
        var position = 0L
        var uri: String? = null
        var rejectStop = false
        var stopObservations = 0
        override suspend fun setUri(device: DlnaDevice, metadata: DlnaMetadata) {
            uri = metadata.uri
            position = 0
            state = "STOPPED"
            actions.add("uri:${metadata.id}")
        }
        override suspend fun play(device: DlnaDevice) {
            state = "PLAYING"
            actions.add("play")
        }
        override suspend fun pause(device: DlnaDevice) {
            state = "PAUSED_PLAYBACK"
            actions.add("pause")
        }
        override suspend fun stop(device: DlnaDevice) {
            if (rejectStop) throw DlnaCommandException("Stop")
            state = "STOPPED"
            actions.add("stop")
        }
        override suspend fun seek(device: DlnaDevice, positionMs: Long) {
            position = positionMs
            actions.add("seek:$positionMs")
        }
        override suspend fun position(device: DlnaDevice): DlnaPosition = DlnaPosition(position, 30000, uri)
        override suspend fun transportInfo(device: DlnaDevice): DlnaTransportInfo {
            if (state == "STOPPED") stopObservations++
            return DlnaTransportInfo(state, "OK")
        }
        override suspend fun volume(device: DlnaDevice): Int = 20
        override suspend fun setVolume(device: DlnaDevice, volume: Int) = Unit
    }
}
