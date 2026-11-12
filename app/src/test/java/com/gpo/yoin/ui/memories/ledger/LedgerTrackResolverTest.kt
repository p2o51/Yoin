package com.gpo.yoin.ui.memories.ledger

import com.gpo.yoin.data.memory.MemoryLedgerEntry
import com.gpo.yoin.data.memory.MemoryLedgerKind
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.profile.ProfileManager
import com.gpo.yoin.data.source.MusicLibrary
import com.gpo.yoin.data.source.MusicSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The resolver's answers live for the process (one map for every visit), so each test names its own account.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LedgerTrackResolverTest {

    private val library = mockk<MusicLibrary>()
    private val activeSource = MutableStateFlow<MusicSource?>(null)
    private val activeProfileId = MutableStateFlow<String?>(null)
    private val profiles = mockk<ProfileManager>().also { manager ->
        every { manager.activeSource } returns activeSource
        every { manager.activeProfileId } returns activeProfileId
        every { manager.profiles } returns flowOf(emptyList())
    }

    private fun use(profileId: String, provider: String, builtFor: String? = profileId) {
        activeProfileId.value = profileId
        activeSource.value = mockk<MusicSource>().also {
            every { it.id } returns provider
            every { it.profileId } returns builtFor
            every { it.library() } returns library
        }
    }

    private fun track(provider: String, id: String, title: String, albumId: String?) = Track(
        id = MediaId(provider, id),
        title = title,
        artist = "Artist",
        artistId = null,
        album = albumId?.let { "Album $it" },
        albumId = albumId?.let { MediaId(provider, it) },
        coverArt = null,
        durationSec = null,
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null,
        isStarred = false,
    )

    private fun score(profileId: String, songId: String) = MemoryLedgerEntry(
        id = "track-rating:$profileId:$songId",
        kind = MemoryLedgerKind.TrackRating,
        at = 1L,
        profileId = profileId,
        album = null,
        songId = songId,
        score = 8f,
    )

    private fun wanted(profileId: String, provider: String, count: Int) =
        (1..count).map { LedgerTrackResolver.Wanted(profileId, provider, "s$it") }

    @Test
    fun should_reportASongsName_when_serviceNamesNoAlbum() = runTest {
        // A loose track on a Subsonic server: a name, no album. The page must still reload to show it.
        use("named", MediaId.PROVIDER_SUBSONIC)
        coEvery { library.getTrack(any()) } returns track(MediaId.PROVIDER_SUBSONIC, "s1", "Loose", albumId = null)
        val resolver = LedgerTrackResolver(profiles)

        val pass = resolver.resolve(wanted("named", MediaId.PROVIDER_SUBSONIC, 1))

        assertEquals(LedgerTrackResolver.Pass(answered = 1, found = true), pass)
        assertEquals("Loose", resolver.known("named", MediaId.PROVIDER_SUBSONIC, "s1")?.title)
        assertNull(resolver.known("named", MediaId.PROVIDER_SUBSONIC, "s1")?.albumId)
    }

    @Test
    fun should_notAskAgain_when_serviceDoesNotKnowTheSong() = runTest {
        use("gone", MediaId.PROVIDER_SUBSONIC)
        coEvery { library.getTrack(any()) } returns null
        val resolver = LedgerTrackResolver(profiles)
        val entries = listOf(score("gone", "s1"))
        val providers = mapOf("gone" to MediaId.PROVIDER_SUBSONIC)

        val pass = resolver.resolve(resolver.pending(entries, providers))

        // Answered, with nothing to show: no reload, and the song is no longer pending.
        assertEquals(LedgerTrackResolver.Pass(answered = 1, found = false), pass)
        assertNull(resolver.known("gone", MediaId.PROVIDER_SUBSONIC, "s1"))
        assertTrue(resolver.pending(entries, providers).isEmpty())
    }

    @Test
    fun should_leaveTheAccountAlone_when_aLookupFails() = runTest {
        use("offline", MediaId.PROVIDER_SUBSONIC)
        coEvery { library.getTrack(any()) } throws IOException("offline")
        val resolver = LedgerTrackResolver(profiles)
        val entries = (1..12).map { score("offline", "s$it") }
        val providers = mapOf("offline" to MediaId.PROVIDER_SUBSONIC)

        val pass = resolver.resolve(resolver.pending(entries, providers))

        // No answer ends the passes; the rest of the account isn't tried one by one against a dead server.
        assertEquals(LedgerTrackResolver.Pass(answered = 0, found = false), pass)
        coVerify(atMost = 4) { library.getTrack(any()) }
        // Nothing was kept: all of them are asked again when the ledger next reloads.
        assertEquals(12, resolver.pending(entries, providers).size)
    }

    @Test
    fun should_askAStreamingServiceAFewSongsOneAtATime() = runTest {
        use("metered", MediaId.PROVIDER_SPOTIFY)
        coEvery { library.getTrack(any()) } answers {
            track(MediaId.PROVIDER_SPOTIFY, firstArg<MediaId>().rawId, "Song", albumId = "al")
        }
        val resolver = LedgerTrackResolver(profiles)
        val entries = (1..25).map { score("metered", "s$it") }
        val providers = mapOf("metered" to MediaId.PROVIDER_SPOTIFY)

        val first = resolver.resolve(resolver.pending(entries, providers))

        // Twenty a pass, a breath (250 ms) between two lookups, newest first.
        assertEquals(LedgerTrackResolver.Pass(answered = 20, found = true), first)
        assertEquals(19 * 250L, currentTime)
        assertEquals("al", resolver.known("metered", MediaId.PROVIDER_SPOTIFY, "s1")?.albumId)
        assertNull(resolver.known("metered", MediaId.PROVIDER_SPOTIFY, "s21"))

        // The next pass takes the rest; then nothing is pending and a pass answers nothing.
        val second = resolver.resolve(resolver.pending(entries, providers))
        assertEquals(LedgerTrackResolver.Pass(answered = 5, found = true), second)
        val third = resolver.resolve(resolver.pending(entries, providers))
        assertEquals(LedgerTrackResolver.Pass(answered = 0, found = false), third)
        coVerify(exactly = 25) { library.getTrack(any()) }
    }

    @Test
    fun should_takeTheOtherReadersAnswers_withoutAskingAgain() = runTest {
        // Home's ledger and the page's each have a resolver; what one learned is news to the other.
        use("shared", MediaId.PROVIDER_SPOTIFY)
        coEvery { library.getTrack(any()) } answers {
            track(MediaId.PROVIDER_SPOTIFY, firstArg<MediaId>().rawId, "Song", albumId = "al")
        }
        val asking = wanted("shared", MediaId.PROVIDER_SPOTIFY, 3)

        val home = LedgerTrackResolver(profiles).resolve(asking)
        val page = LedgerTrackResolver(profiles).resolve(asking)

        assertEquals(LedgerTrackResolver.Pass(answered = 3, found = true), home)
        assertEquals(LedgerTrackResolver.Pass(answered = 3, found = true), page)
        coVerify(exactly = 3) { library.getTrack(any()) }
    }

    @Test
    fun should_notAskAnotherAccountsSource_when_aSwitchIsHalfDone() = runTest {
        // The id in use already names this account while the source in use still belongs to the other one.
        use("switching", MediaId.PROVIDER_SUBSONIC, builtFor = "the-other-account")
        val resolver = LedgerTrackResolver(profiles)

        val pass = resolver.resolve(wanted("switching", MediaId.PROVIDER_SUBSONIC, 3))

        assertEquals(LedgerTrackResolver.Pass(answered = 0, found = false), pass)
        coVerify(exactly = 0) { library.getTrack(any()) }
        assertNull(resolver.known("switching", MediaId.PROVIDER_SUBSONIC, "s1"))
    }
}
