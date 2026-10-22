package com.gpo.yoin.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.player.dlna.DlnaDevice
import com.gpo.yoin.player.dlna.DlnaManager
import com.gpo.yoin.player.dlna.DlnaMedia3Player
import com.gpo.yoin.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PlaybackManagerDlnaHandoffTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private var provider = MediaId.PROVIDER_SUBSONIC
    private val selected = MutableStateFlow<DlnaDevice?>(null)
    private val castState = MutableStateFlow<CastState>(CastState.NotAvailable)
    private val renderer = mockk<DlnaDevice>(relaxed = true)
    private val remote = mockk<DlnaMedia3Player>(relaxed = true)
    private val dlna = mockk<DlnaManager>(relaxed = true).also {
        every { it.connectedDevice } returns selected
        every { it.player } returns remote
    }
    private val repository = mockk<YoinRepository>(relaxed = true).also {
        every { it.activeProviderId } returns MutableStateFlow(MediaId.PROVIDER_SUBSONIC)
        every { it.currentProviderId() } answers { provider }
    }
    private val manager by lazy {
        PlaybackManager(
            context = mockk<Context>(relaxed = true),
            repository = repository,
            castManager = mockk<CastManager>().also { every { it.castState } returns castState },
            dlnaManager = dlna,
            spotifyClientIdProvider = { "test-client" }
        )
    }

    @After
    fun tearDown() {
        val scope = PlaybackManager::class.java.getDeclaredField("scope").apply { isAccessible = true }
        (scope.get(manager) as CoroutineScope).cancel()
    }

    @Test
    fun should_rejectProtectedStreams_when_providerIsAppleMusic() = runTest {
        provider = MediaId.PROVIDER_APPLE_MUSIC

        assertTrue(manager.transferToDlna(renderer).isFailure)

        coVerify(exactly = 0) { dlna.connect(any(), any(), any(), any(), any()) }
    }

    @Test
    fun should_rejectCompetingOutput_when_chromecastIsConnected() = runTest {
        castState.value = CastState.Connected("Living room")

        assertTrue(manager.transferToDlna(renderer).isFailure)

        coVerify(exactly = 0) { dlna.connect(any(), any(), any(), any(), any()) }
    }

    @Test
    fun should_keepPhonePaused_when_speakerStopCannotBeConfirmed() = runTest {
        selected.value = renderer
        every { remote.playWhenReady } returns true
        every { remote.mediaItemCount } returns 1
        every { remote.getMediaItemAt(0) } returns mockk<MediaItem>()
        val local = mockk<MediaController>(relaxed = true)
        PlaybackManager::class.java.getDeclaredField("controller").apply {
            isAccessible = true
            set(manager, local)
        }
        PlaybackManager::class.java.getDeclaredField("activeBackend").apply {
            isAccessible = true
            set(manager, requireNotNull(type.enumConstants).first { (it as Enum<*>).name == "LOCAL" })
        }
        coEvery { dlna.disconnect(stopRemote = true) } throws IOException("Speaker unreachable")

        assertTrue(manager.transferFromDlna().isFailure)

        assertTrue(selected.value === renderer)
        coVerify(exactly = 1) { dlna.disconnect(stopRemote = true) }
        verify(exactly = 0) { local.play() }
        verify(exactly = 0) { local.prepare() }
    }

    @Test
    fun should_keepCurrentSessionReachable_when_profileChangeCannotStopSpeaker() = runTest {
        selected.value = renderer
        val current = mockk<MediaController>(relaxed = true)
        val controllerField = PlaybackManager::class.java.getDeclaredField("controller").apply {
            isAccessible = true
            set(manager, current)
        }
        coEvery { dlna.disconnect(stopRemote = true) } throws IOException("Speaker unreachable")

        assertTrue(runCatching { manager.disconnectForProfileChange() }.isFailure)

        assertTrue(controllerField.get(manager) === current)
        assertTrue(selected.value === renderer)
        verify(exactly = 0) { current.release() }
        verify(exactly = 0) { current.stop() }
    }
}
