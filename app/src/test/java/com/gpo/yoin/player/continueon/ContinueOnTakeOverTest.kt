package com.gpo.yoin.player.continueon

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import com.gpo.yoin.data.local.Profile
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.profile.ProfileManager
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.data.source.MusicLibrary
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.player.PlaybackManager
import com.gpo.yoin.player.PlaybackState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.net.InetAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [ContinueOn] end to end with the player and the accounts mocked: what the card says about a
 * received hand-off, and what "Continue here" starts (docs/continue-on.md).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("DEPRECATION") // ConnectivityManager.allNetworks, as ContinueOn reads it.
class ContinueOnTakeOverTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val home = profile("p-home", "Home")
    private val work = profile("p-work", "Work")
    private val accountKeys = mapOf("p-home" to "k-home", "p-work" to "k-work")

    private val homeLibrary = mockk<MusicLibrary>()
    private val homeSource = source("p-home", homeLibrary)
    private val workLibrary = mockk<MusicLibrary>()
    private val workSource = source("p-work", workLibrary)

    private val activeProfileId = MutableStateFlow<String?>("p-home")
    private val activeProfile = MutableStateFlow<Profile?>(home)
    private val activeSource = MutableStateFlow<MusicSource?>(homeSource)
    private val playbackState = MutableStateFlow(PlaybackState())
    private val switchingState = MutableStateFlow<ProfileManager.SwitchState>(ProfileManager.SwitchState.Idle)

    private val profiles = mockk<ProfileManager>(relaxed = true).also { manager ->
        every { manager.profiles } returns flowOf(listOf(home, work))
        every { manager.activeProfileId } returns activeProfileId
        every { manager.activeProfile } returns activeProfile
        every { manager.activeSource } returns activeSource
        every { manager.switchingState } returns switchingState
        every { manager.acknowledgeSwitchError() } answers { switchingState.value = ProfileManager.SwitchState.Idle }
        coEvery { manager.switchTo("p-work") } coAnswers {
            activeProfileId.value = "p-work"
            activeProfile.value = work
            activeSource.value = workSource
        }
    }

    private val playback = mockk<PlaybackManager>(relaxed = true).also { manager ->
        every { manager.playbackState } returns playbackState
        every { manager.currentActivityContext } returns MutableStateFlow(ActivityContext.None)
    }

    private var nowPlayingRequests = 0

    private val connectivity = mockk<ConnectivityManager>(relaxed = true)
    private val context = mockk<Context>(relaxed = true).also { context ->
        every { context.getSystemService(ConnectivityManager::class.java) } returns connectivity
    }

    /** What the device that handed off answers when it is asked to pause, and what it was asked. */
    private var pauseAnswer: ContinueOnPauseClient.Answer = ContinueOnPauseClient.Answer.Unreachable
    private val pauseRequests = mutableListOf<String>()
    private val pauseChannel = ContinueOnCursor.PauseChannel(listOf("192.168.1.20"), 40_123, "tok")

    private val continueOn = ContinueOn(
        context = context,
        playback = playback,
        profiles = profiles,
        accountKey = { accountKeys[it.id] },
        requestNowPlaying = { nowPlayingRequests++ },
        scope = scope,
        io = dispatcher,
        askToPause = { _, trackId, _ ->
            pauseRequests += trackId
            pauseAnswer
        },
    )

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun should_loadThePausedSongWhereItStopped_when_continueHereIsTapped() {
        homeLibrary.has("t2", "t3")

        continueOn.receive(cursor(playing = false).toEntries()::get)
        assertEquals(ContinueOnPrompt.Account.Found("p-home", "Home"), continueOn.prompt.value?.account)
        continueOn.confirm()

        // The song and what came after it, read again here; nothing plays by itself.
        verify {
            playback.play(
                tracks = match { tracks -> tracks.map { it.id.rawId } == listOf("t2", "t3") },
                startIndex = 0,
                source = homeSource,
                activityContext = ActivityContext.None,
                shuffled = false,
                explicitStart = true,
                startPositionMs = 61_000L,
                startPaused = true,
            )
        }
        coVerify(exactly = 0) { profiles.switchTo(any()) }
        assertEquals(1, nowPlayingRequests)
        assertNull(continueOn.prompt.value)
    }

    @Test
    fun should_switchToTheAccountTheSongCameFrom_when_anotherOneIsInUse() {
        workLibrary.has("t2", "t3")

        continueOn.receive(cursor(accountKey = "k-work", playing = false).toEntries()::get)
        assertEquals(ContinueOnPrompt.Account.Found("p-work", "Work"), continueOn.prompt.value?.account)
        continueOn.confirm()

        coVerify { profiles.switchTo("p-work") }
        verify { playback.play(any(), 0, workSource, any(), any(), any(), 61_000L, true) }
    }

    @Test
    fun should_startNothing_when_theAccountChangesWhileTheSongsAreRead() {
        // The person switches accounts while this one's songs are still being read.
        coEvery { homeLibrary.getTrack(any()) } coAnswers {
            activeProfileId.value = "p-work"
            activeSource.value = workSource
            track(firstArg<MediaId>().rawId)
        }

        continueOn.receive(cursor(playing = false).toEntries()::get)
        continueOn.confirm()

        verify(exactly = 0) { playback.play(any(), any(), any(), any(), any(), any(), any(), any()) }
        assertEquals(ContinueOnPrompt.Stage.Failed, continueOn.prompt.value?.stage)
        assertEquals(0, nowPlayingRequests)
    }

    @Test
    fun should_sayTheOtherDeviceMayStillPlay_when_itLeftNoWayToPauseIt() {
        homeLibrary.has("t2", "t3")

        // Playing there, and no pause channel in the cursor (the other device had no local address).
        continueOn.receive(cursor(playing = true).toEntries()::get)
        continueOn.confirm()

        verify { playback.play(any(), 0, homeSource, any(), any(), any(), any(), false) }
        assertEquals(ContinueOnPrompt.Stage.OtherStillPlaying, continueOn.prompt.value?.stage)
        // The note leaves by itself.
        dispatcher.scheduler.advanceUntilIdle()
        assertNull(continueOn.prompt.value)
    }

    @Test
    fun should_startTheSongTheOtherDeviceIsOnByNow_when_itHasMovedOnSinceTheCursorWasRead() {
        homeLibrary.has("t2", "t3")
        // The cursor was read at t2 as the phone's screen went off; by the tap the phone plays t3.
        pauseAnswer = ContinueOnPauseClient.Answer.Paused(positionMs = 12_000L, trackId = "t3")

        continueOn.receive(cursor(playing = true, pause = pauseChannel).toEntries()::get)
        continueOn.confirm()

        assertEquals(listOf("t2"), pauseRequests)
        verify {
            playback.play(
                tracks = match { tracks -> tracks.map { it.id.rawId } == listOf("t3") },
                startIndex = 0,
                source = homeSource,
                activityContext = ActivityContext.None,
                shuffled = false,
                explicitStart = true,
                startPositionMs = 12_000L,
                startPaused = false,
            )
        }
        // The other device paused: nothing more to say.
        assertNull(continueOn.prompt.value)
    }

    @Test
    fun should_findTheSongInItsAlbum_when_theOtherDeviceHasMovedOnInIt() {
        val album = listOf(track("t1"), track("t2"), track("t3"), track("t4"))
        coEvery { homeLibrary.getAlbum(MediaId.subsonic("al-1")) } returns album(album)
        pauseAnswer = ContinueOnPauseClient.Answer.Paused(positionMs = 5_000L, trackId = "t4")
        val fromAlbum = cursor(playing = true, pause = pauseChannel)
            .copy(context = ContinueOnCursor.Context(ContinueOnCursor.Context.Kind.Album, "al-1", "Album"))

        continueOn.receive(fromAlbum.toEntries()::get)
        continueOn.confirm()

        verify {
            playback.play(
                tracks = album,
                startIndex = 3,
                source = homeSource,
                activityContext = ActivityContext.Album(albumId = "subsonic:al-1", albumName = "Album"),
                shuffled = false,
                explicitStart = true,
                startPositionMs = 5_000L,
                startPaused = false,
            )
        }
    }

    @Test
    fun should_startTheCursorsSong_when_theOneTheOtherDeviceIsOnCannotBeReadHere() {
        homeLibrary.has("t2", "t3")
        pauseAnswer = ContinueOnPauseClient.Answer.Paused(positionMs = 12_000L, trackId = "gone")

        continueOn.receive(cursor(playing = true, pause = pauseChannel).toEntries()::get)
        continueOn.confirm()

        // Not the other song's position: the cursor's song, where the card showed it.
        verify {
            playback.play(
                tracks = match { tracks -> tracks.map { it.id.rawId } == listOf("t2", "t3") },
                startIndex = 0,
                source = homeSource,
                activityContext = ActivityContext.None,
                shuffled = false,
                explicitStart = true,
                startPositionMs = 61_000L,
                startPaused = false,
            )
        }
    }

    @Test
    fun should_leaveTheOtherDevicePlayingAndTheErrorTold_when_theAccountCannotBeSwitchedTo() {
        workLibrary.has("t2", "t3")
        coEvery { profiles.switchTo("p-work") } coAnswers {
            switchingState.value = ProfileManager.SwitchState.Error("p-work", "Server did not respond within 8s")
        }

        continueOn.receive(cursor(accountKey = "k-work", playing = true, pause = pauseChannel).toEntries()::get)
        continueOn.confirm()

        // The card tells it; Settings must not open on this error later.
        assertEquals(ContinueOnPrompt.Stage.Failed, continueOn.prompt.value?.stage)
        verify { profiles.acknowledgeSwitchError() }
        assertEquals(ProfileManager.SwitchState.Idle, switchingState.value)
        // Nothing was taken over, so the other device was never asked to stop.
        assertEquals(emptyList<String>(), pauseRequests)
        verify(exactly = 0) { playback.play(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun should_leaveAnotherSwitchsErrorAlone_when_theSwitchWasTurnedAway() {
        // Settings' own failed switch to another account is still waiting to be told there.
        val settingsError = ProfileManager.SwitchState.Error("p-other", "Profile has invalid credentials")
        switchingState.value = settingsError
        coEvery { profiles.switchTo("p-work") } coAnswers { }

        continueOn.receive(cursor(accountKey = "k-work", playing = false).toEntries()::get)
        continueOn.confirm()

        assertEquals(ContinueOnPrompt.Stage.Failed, continueOn.prompt.value?.stage)
        verify(exactly = 0) { profiles.acknowledgeSwitchError() }
        assertSame(settingsError, switchingState.value)
    }

    @Test
    fun should_sayTheAccountIsMissing_when_noAccountOfThatServiceIsHere() {
        continueOn.receive(cursor(provider = MediaId.PROVIDER_SPOTIFY, playing = false).toEntries()::get)

        assertEquals(ContinueOnPrompt.Account.Missing, continueOn.prompt.value?.account)
        continueOn.confirm()
        verify(exactly = 0) { playback.play(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun should_showNoCard_when_theWindowWasStartedWithoutACursor() {
        assertEquals(false, continueOn.receive { null })
        assertNull(continueOn.prompt.value)
    }

    @Test
    fun should_handOverTheSongItsQueueAndTheAccount_when_theSystemAsks() {
        val queue = listOf(track("t1"), track("t2"), track("t3"))
        every { playback.livePositionMs() } returns 42_000L
        playbackState.value = PlaybackState(currentTrack = queue[1], queue = queue, currentIndex = 1)

        val handedOver = ContinueOnCursor.fromEntries(continueOn.handoffEntries(activeRequest = true)!!::get)!!

        assertEquals(MediaId.PROVIDER_SUBSONIC, handedOver.provider)
        assertEquals("k-home", handedOver.accountKey)
        assertEquals("t2", handedOver.trackId)
        assertEquals(listOf("t1", "t2", "t3"), handedOver.queueIds)
        assertEquals(1, handedOver.index)
        assertEquals(42_000L, handedOver.positionMs)
        // Nothing is playing (the default state): no port is opened for a pause.
        assertNull(handedOver.pause)
    }

    @Test
    fun should_handOverNothing_when_noSongIsLoaded() {
        assertNull(continueOn.handoffEntries(activeRequest = true))
    }

    @Test
    fun should_giveTheWifisAddress_when_aVpnIsTheNetworkInUse() {
        // A VPN reports the Wi-Fi under it as its transport, and is the network in use.
        val vpn = network("10.8.0.2", vpn = true)
        val wifi = network("192.168.1.20", vpn = false)
        every { connectivity.activeNetwork } returns vpn
        every { connectivity.allNetworks } returns arrayOf(vpn, wifi)
        val song = track("t1")
        playbackState.value =
            PlaybackState(currentTrack = song, isPlaying = true, queue = listOf(song), currentIndex = 0)

        val handedOver = ContinueOnCursor.fromEntries(continueOn.handoffEntries(activeRequest = false)!!::get)!!

        assertEquals(listOf("192.168.1.20"), handedOver.pause?.hosts)
        assertSame(wifi, connectivity.localNetwork())
        // The port it opened goes with the account.
        activeProfile.value = null
    }

    @Test
    fun should_giveTheAddressOfTheNetworkInUse_when_itIsTheWifiItself() {
        val wifi = network("192.168.1.20", vpn = false)
        val ethernet = network("192.168.7.3", vpn = false)
        every { connectivity.activeNetwork } returns wifi
        every { connectivity.allNetworks } returns arrayOf(ethernet, wifi)

        assertSame(wifi, connectivity.localNetwork())
    }

    @Test
    fun should_findNoLocalNetwork_when_onlyMobileDataIsUp() {
        val mobile = mockk<Network>()
        every { connectivity.getNetworkCapabilities(mobile) } returns mockk<NetworkCapabilities> {
            every { hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) } returns true
            every { hasTransport(any()) } returns false
        }
        every { connectivity.activeNetwork } returns mobile
        every { connectivity.allNetworks } returns arrayOf(mobile)

        assertNull(connectivity.localNetwork())
    }

    /** A Wi-Fi network at [ip], or a VPN over Wi-Fi at it. */
    private fun network(ip: String, vpn: Boolean): Network = mockk<Network>().also { network ->
        every { connectivity.getNetworkCapabilities(network) } returns mockk<NetworkCapabilities> {
            every { hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) } returns !vpn
            every { hasTransport(any()) } returns false
            every { hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns true
        }
        every { connectivity.getLinkProperties(network) } returns mockk<LinkProperties> {
            every { linkAddresses } returns listOf(
                mockk<LinkAddress> { every { address } returns InetAddress.getByName(ip) },
            )
        }
    }

    private fun album(tracks: List<Track>) = Album(
        id = MediaId.subsonic("al-1"),
        name = "Album",
        artist = "Artist",
        artistId = null,
        coverArt = null,
        songCount = tracks.size,
        durationSec = null,
        year = null,
        genre = null,
        tracks = tracks,
    )

    private fun MusicLibrary.has(vararg rawIds: String) {
        coEvery { getTrack(any()) } returns null
        rawIds.forEach { rawId -> coEvery { getTrack(MediaId.subsonic(rawId)) } returns track(rawId) }
    }

    private fun cursor(
        provider: String = MediaId.PROVIDER_SUBSONIC,
        accountKey: String? = "k-home",
        playing: Boolean,
        pause: ContinueOnCursor.PauseChannel? = null,
    ) = ContinueOnCursor(
        provider = provider,
        accountKey = accountKey,
        trackId = "t2",
        title = "Song",
        artist = "Artist",
        album = "Album",
        cover = null,
        queueIds = listOf("t1", "t2", "t3"),
        index = 1,
        positionMs = 61_000L,
        capturedAtMs = 0L,
        playing = playing,
        repeatMode = 0,
        shuffle = false,
        context = null,
        deviceName = "Pixel 9",
        pause = pause,
    )

    private fun profile(id: String, name: String) =
        Profile(id = id, provider = MediaId.PROVIDER_SUBSONIC, displayName = name, credentialsJson = "")

    private fun source(profileId: String, library: MusicLibrary) = mockk<MusicSource>(relaxed = true).also { source ->
        every { source.id } returns MediaId.PROVIDER_SUBSONIC
        every { source.profileId } returns profileId
        every { source.library() } returns library
    }

    private fun track(rawId: String) = Track(
        id = MediaId.subsonic(rawId),
        title = "Track $rawId",
        artist = "Artist",
        artistId = null,
        album = "Album",
        albumId = null,
        coverArt = null,
        durationSec = 180,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null,
    )
}
