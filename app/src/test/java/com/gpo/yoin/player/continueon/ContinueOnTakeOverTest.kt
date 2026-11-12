package com.gpo.yoin.player.continueon

import android.content.Context
import com.gpo.yoin.data.local.Profile
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
import org.junit.Test

/**
 * [ContinueOn] end to end with the player and the accounts mocked: what the card says about a
 * received hand-off, and what "Continue here" starts (docs/continue-on.md).
 */
@OptIn(ExperimentalCoroutinesApi::class)
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

    private val profiles = mockk<ProfileManager>(relaxed = true).also { manager ->
        every { manager.profiles } returns flowOf(listOf(home, work))
        every { manager.activeProfileId } returns activeProfileId
        every { manager.activeProfile } returns activeProfile
        every { manager.activeSource } returns activeSource
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

    private val continueOn = ContinueOn(
        context = mockk<Context>(relaxed = true),
        playback = playback,
        profiles = profiles,
        accountKey = { accountKeys[it.id] },
        requestNowPlaying = { nowPlayingRequests++ },
        scope = scope,
        io = dispatcher,
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

    private fun MusicLibrary.has(vararg rawIds: String) {
        coEvery { getTrack(any()) } returns null
        rawIds.forEach { rawId -> coEvery { getTrack(MediaId.subsonic(rawId)) } returns track(rawId) }
    }

    private fun cursor(
        provider: String = MediaId.PROVIDER_SUBSONIC,
        accountKey: String? = "k-home",
        playing: Boolean,
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
        pause = null,
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
