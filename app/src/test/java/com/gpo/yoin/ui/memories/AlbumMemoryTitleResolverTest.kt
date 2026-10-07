package com.gpo.yoin.ui.memories

import app.cash.turbine.test
import com.gpo.yoin.data.local.AlbumPlayHistoryAggregate
import com.gpo.yoin.data.local.AlbumRating
import com.gpo.yoin.data.local.PlayHistoryDao
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.data.memory.AlbumMemoryCandidate
import com.gpo.yoin.data.memory.AlbumMemoryTitleSource
import com.gpo.yoin.data.memory.AlbumMemoryTitleStore
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.ui.experience.ExperienceSessionStore
import com.gpo.yoin.ui.memories.copy.MemoryProseLanguage
import com.gpo.yoin.ui.memories.copy.MemoryTitleKind
import com.gpo.yoin.ui.memories.showcase.restoreTitleLabel
import com.gpo.yoin.ui.memories.showcase.titleDraftSeed
import com.gpo.yoin.ui.memories.showcase.titlePlaceholder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumMemoryTitleResolverTest {

    private val repository = mockk<YoinRepository>()
    private val playHistoryDao = mockk<PlayHistoryDao>()
    private val activeProfileId = MutableStateFlow<String?>("profile-a")
    private val albumId = MediaId("subsonic", "al-visit")

    // ── the same title as the Memories card ─────────────────────────────

    @Test
    fun should_matchMemoriesCard_when_onlyNotesAreWritten() = runTest {
        // no counted motif line any more (owner, 2026-10-06): without an AI title the album's name stands in
        val candidate = candidate()
        stubAlbum(songNotes = twoNotes())

        val (card, resolved) = cardAndResolved(candidate)

        assertSameTitle(card, resolved)
        assertEquals("Visited Album", resolved.text)
        assertEquals(AlbumMemoryTitleSource.ALBUM, resolved.source)
    }

    @Test
    fun should_matchMemoriesCard_when_aiTitleIsCached() = runTest {
        val candidate = candidate()
        // the deck generated it (getOrGenerate); the resolver reads the row it left in the cache
        stubAlbum(songNotes = chineseNotes(), aiTitle = "雨天里的水底吉他")

        val (card, resolved) = cardAndResolved(candidate)

        assertSameTitle(card, resolved)
        assertEquals("雨天里的水底吉他", resolved.text)
        assertEquals(AlbumMemoryTitleSource.AI, resolved.source)
        assertEquals(MemoryProseLanguage.ZH, resolved.proseLanguage)
    }

    @Test
    fun should_matchMemoriesCard_when_onlyTheAlbumNameFits() = runTest {
        // rated, never played in Yoin, nothing written: no motif fits
        val candidate = candidate().copy(albumRating = 9f)
        stubAlbum(ratingRow = ratingRow(rating = 9f))

        val (card, resolved) = cardAndResolved(candidate)

        assertSameTitle(card, resolved)
        assertEquals("Visited Album", resolved.text)
        assertEquals(AlbumMemoryTitleSource.ALBUM, resolved.source)
        assertNull(resolved.generatedText)
    }

    @Test
    fun should_matchMemoriesCard_when_onlyPlaysExist() = runTest {
        val candidate = playedCandidate()
        stubAlbum()

        val (card, resolved) = cardAndResolved(candidate)

        assertSameTitle(card, resolved)
        // never "3 plays since September"
        assertEquals("Visited Album", resolved.text)
    }

    @Test
    fun should_matchMemoriesCard_when_userNamedTheAlbum() = runTest {
        val candidate = candidate()
        stubAlbum(songNotes = twoNotes())
        val store = titleStore()
        store.setTitle(albumId, "Night bus")

        val (card, resolved) = cardAndResolved(candidate, store)

        assertSameTitle(card, resolved)
        assertEquals("Night bus", resolved.text)
        assertEquals(AlbumMemoryTitleSource.USER, resolved.source)
    }

    // ── precedence ──────────────────────────────────────────────────────

    @Test
    fun should_rankUserOverAiOverAlbumName() = runTest {
        val store = titleStore()
        val resolver = resolver(store)

        // nothing of Yoin's fits: the album name
        stubAlbum()
        assertEquals(AlbumMemoryTitleSource.ALBUM, resolver.resolve(candidate()).source)

        // two notes are no title of their own
        stubAlbum(songNotes = twoNotes())
        assertEquals(AlbumMemoryTitleSource.ALBUM, resolver.resolve(candidate()).source)

        // a cached AI title beats the album name
        stubAlbum(songNotes = twoNotes(), aiTitle = "Rain on the glass")
        val ai = resolver.resolve(candidate())
        assertEquals(AlbumMemoryTitleSource.AI, ai.source)
        assertEquals("Rain on the glass", ai.text)

        // the user's title beats them all
        store.setTitle(albumId, "Night bus")
        val user = resolver.resolve(candidate())
        assertEquals(AlbumMemoryTitleSource.USER, user.source)
        assertEquals("Night bus", user.text)
        assertEquals("Rain on the glass", user.generatedText)
    }

    @Test
    fun should_offerRestoreAiTitle_when_userTitleSitsOverAiTitle() = runTest {
        val store = titleStore()
        store.setTitle(albumId, "Night bus")
        stubAlbum(songNotes = twoNotes(), aiTitle = "Rain on the glass")

        val resolved = resolver(store).resolve(candidate())

        assertTrue(resolved.canRestoreGenerated)
        assertEquals(AlbumMemoryTitleSource.AI, resolved.generatedSource)
        assertEquals("Restore AI title", resolved.restoreLabel)
        assertTrue(resolved.isSerif)
        assertEquals("Night bus", resolved.userTitle)
    }

    @Test
    fun should_notOfferRestore_when_userTitleSitsOverAlbumNameOnly() = runTest {
        val store = titleStore()
        store.setTitle(albumId, "Night bus")
        stubAlbum()

        val resolved = resolver(store).resolve(candidate())

        assertEquals(AlbumMemoryTitleSource.USER, resolved.source)
        assertFalse(resolved.canRestoreGenerated)
        assertNull(resolved.generatedSource)
        assertEquals("Visited Album", resolved.placeholder)
    }

    @Test
    fun should_notOfferRestore_when_noUserTitle() = runTest {
        stubAlbum(songNotes = twoNotes())

        val resolved = resolver(titleStore()).resolve(candidate())

        assertFalse(resolved.canRestoreGenerated)
        assertNull(resolved.userTitle)
        assertFalse(resolved.isSerif)
        assertEquals(MemoryTitleKind.ALBUM, resolved.kind)
        // the album name standing in is not put in the rename field
        assertEquals("", resolved.draftSeed)
    }

    // ── never Gemini ────────────────────────────────────────────────────

    @Test
    fun should_neverAskGemini_when_aiTitleIsNotCached() = runTest {
        // an occupant writing is there (the deck would generate from it), the cache is empty
        stubAlbum(songNotes = twoNotes(), aiTitle = null)
        val resolver = resolver(titleStore())

        val byCandidate = resolver.resolve(candidate())
        val byId = resolver.resolve(albumId)
        every { repository.observeMemorySignalStamp() } returns MutableStateFlow(1L)
        resolver.observe(albumId).test {
            assertEquals(AlbumMemoryTitleSource.ALBUM, awaitItem().source)
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(AlbumMemoryTitleSource.ALBUM, byCandidate.source)
        assertEquals(AlbumMemoryTitleSource.ALBUM, byId.source)
        coVerify(exactly = 0) { repository.getOrGenerateAlbumMemoryTitle(any(), any(), any()) }
        coVerify(exactly = 0) { repository.getOrGenerateAlbumMemoryCopy(any(), any(), any(), any()) }
        coVerify(atLeast = 1) { repository.getCachedAlbumMemoryTitle(albumId) }
    }

    // ── by album id, no candidate ───────────────────────────────────────

    @Test
    fun should_resolveById_when_thereIsNoCandidate() = runTest {
        stubAlbum(ratingRow = ratingRow(rating = 9f))
        stubPlays(plays = 3, first = day(2), last = day(4))

        val resolved = resolver(titleStore()).resolve(albumId)

        assertEquals("Visited Album", resolved.text)
        assertEquals(AlbumMemoryTitleSource.ALBUM, resolved.source)
        assertEquals("Visited Album", resolved.albumName)
        coVerify(exactly = 0) { repository.getAlbumMemoryCandidates(any(), any()) }
    }

    @Test
    fun should_resolveById_likeTheCandidate_when_albumIsInThePool() = runTest {
        val candidate = playedCandidate().copy(albumRating = 9f)
        stubAlbum(ratingRow = ratingRow(rating = 9f), songNotes = twoNotes())
        stubPlays(plays = 3, first = day(2), last = day(4))
        val store = titleStore()
        val resolver = resolver(store)

        assertEquals(resolver.resolve(candidate), resolver.resolve(albumId))
        // a stored id with the legacy "provider:" prefix is the same album
        assertEquals(resolver.resolve(albumId), resolver.resolve(MediaId("subsonic", "subsonic:al-visit")))
        store.setTitle(albumId, "Night bus")
        assertEquals(resolver.resolve(candidate), resolver.resolve(albumId))
    }

    @Test
    fun should_fallToAlbumName_when_readsFail() = runTest {
        stubAlbum(songNotes = twoNotes())
        coEvery { repository.getRatings(any()) } throws IllegalStateException("db closed")
        coEvery { repository.getCachedAlbumMemoryTitle(any()) } throws IllegalStateException("db closed")
        val store = titleStore()
        store.setTitle(albumId, "Night bus")
        val resolver = resolver(store)

        val byCandidate = resolver.resolve(candidate())
        assertEquals("Night bus", byCandidate.text)
        assertFalse(byCandidate.canRestoreGenerated)
        assertEquals("Visited Album", byCandidate.albumName)

        store.clearTitle(albumId)
        val byId = resolver.resolve(albumId)
        assertEquals(AlbumMemoryTitleSource.ALBUM, byId.source)
    }

    // ── live ────────────────────────────────────────────────────────────

    @Test
    fun should_followTitleEditsAndMemorySignals_when_observed() = runTest {
        stubAlbum(songNotes = twoNotes())
        val stamp = MutableStateFlow(1L)
        every { repository.observeMemorySignalStamp() } returns stamp
        val store = titleStore()

        resolver(store).observe(albumId).test {
            assertEquals("Visited Album", awaitItem().text)

            store.setTitle(albumId, "Night bus")
            val named = awaitItem()
            assertEquals("Night bus", named.text)
            assertFalse(named.canRestoreGenerated)

            store.clearTitle(albumId)
            assertEquals("Visited Album", awaitItem().text)

            // the deck caches an AI title, then a signal lands: Yoin's title is composed again
            stubAlbum(songNotes = twoNotes(), aiTitle = "Rain on the glass")
            stamp.value = 2L
            assertEquals("Rain on the glass", awaitItem().text)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /** The deck's card for [candidate] and the resolver's title for it, against the same rows. */
    private suspend fun cardAndResolved(
        candidate: AlbumMemoryCandidate,
        store: AlbumMemoryTitleStore? = null,
    ): Pair<MemoryEntry, ResolvedMemoryTitle> {
        coEvery { repository.getAlbumMemoryCandidates(limit = 48) } returns listOf(candidate)
        val coordinator = MemoriesDeckCoordinator(
            repository = repository,
            sessionStore = ExperienceSessionStore(),
            randomSeed = 42L,
            titleStore = store,
            clock = { day(10) },
            zone = { ZoneId.of("UTC") },
        )
        val card = coordinator.ensureDeck().single()
        return card to resolver(store).resolve(candidate)
    }

    private fun assertSameTitle(card: MemoryEntry, resolved: ResolvedMemoryTitle) {
        assertEquals(card.memoryTitle, resolved.text)
        assertEquals(card.memoryTitleKind, resolved.kind)
        assertEquals(card.generatedMemoryTitle, resolved.generatedText)
        assertEquals(card.generatedMemoryTitleKind, resolved.generatedSource?.toTitleKind())
        assertEquals(card.canRestoreGeneratedTitle(), resolved.canRestoreGenerated)
        assertEquals(card.proseLanguage, resolved.proseLanguage)
        assertEquals(card.title, resolved.albumName)
        assertEquals(card.restoreTitleLabel(), resolved.restoreLabel)
        assertEquals(card.titleDraftSeed(), resolved.draftSeed)
        assertEquals(card.titlePlaceholder(), resolved.placeholder)
    }

    private fun resolver(store: AlbumMemoryTitleStore?): AlbumMemoryTitleResolver = AlbumMemoryTitleResolver(
        repository = repository,
        titleStore = store,
        playHistoryDao = playHistoryDao,
        activeProfileId = activeProfileId,
        clock = { day(10) },
        zone = { ZoneId.of("UTC") },
    )

    private fun titleStore(): AlbumMemoryTitleStore =
        AlbumMemoryTitleStore(FakeAlbumMemoryTitleDao(), activeProfileId, clock = { day(10) })

    /** Visited, nothing rated, never played in Yoin; the rows come from [stubAlbum]. */
    private fun candidate(): AlbumMemoryCandidate = AlbumMemoryCandidate(
        profileId = "profile-a",
        provider = "subsonic",
        albumId = "al-visit",
        albumName = "Visited Album",
        artistName = "Artist",
        totalTracks = 4,
        ratedTrackCount = 0,
        ratingCoverage = 0f,
        averageSongRating = null,
        albumRating = null,
        hasAlbumReview = false,
        noteCount = 0,
        askAiCount = 0,
        firstPlayedAt = day(1),
        lastPlayedAt = day(6),
        playCount = 0,
        neoDbSynced = false,
        isMemoryEligible = true,
        year = 2026,
        durationSeconds = 800,
        coverArtUrl = null,
    )

    /** Played three times in Yoin, days 2–4 (play_history), visited around them. */
    private fun playedCandidate(): AlbumMemoryCandidate = candidate().copy(
        playCount = 3,
        playCountFromHistory = 3,
        firstPlayedFromHistoryAt = day(2),
        lastPlayedFromHistoryAt = day(4),
    )

    /** The album's rows, for the deck and the resolver alike; play_history is empty unless [stubPlays]. */
    private fun stubAlbum(
        songNotes: List<SongNote> = emptyList(),
        ratingRow: AlbumRating? = null,
        aiTitle: String? = null,
    ) {
        val songs = (1..4).map { n ->
            Track(
                id = MediaId("subsonic", "t$n"),
                title = "Song $n",
                artist = "Artist",
                artistId = null,
                album = "Visited Album",
                albumId = albumId,
                coverArt = null,
                durationSec = 200,
                trackNumber = n,
                year = null,
                genre = null,
                userRating = null,
            )
        }
        val album = Album(
            id = albumId,
            name = "Visited Album",
            artist = "Artist",
            artistId = null,
            coverArt = null,
            songCount = songs.size,
            durationSec = 800,
            year = 2026,
            genre = null,
            tracks = songs,
        )
        coEvery { repository.getAlbum(albumId) } returns album
        coEvery { repository.getRatings(any()) } returns emptyMap()
        coEvery { repository.getAlbumRatingRow(albumId) } returns ratingRow
        coEvery { repository.getAlbumNotesOnce(albumId) } returns emptyList()
        coEvery { repository.getSongNotesOnce(any()) } returns songNotes
        coEvery { repository.getOrGenerateAlbumMemoryTitle(any(), any(), any()) } returns aiTitle
        coEvery { repository.getCachedAlbumMemoryTitle(albumId) } returns aiTitle
        every { repository.resolveCoverUrl(any(), any()) } returns null
        coEvery { playHistoryDao.getAlbumAggregatesFor(any(), any(), any()) } returns emptyList()
    }

    private fun stubPlays(plays: Int, first: Long, last: Long) {
        coEvery { playHistoryDao.getAlbumAggregatesFor("profile-a", "subsonic", listOf("al-visit")) } returns listOf(
            AlbumPlayHistoryAggregate(
                albumId = "al-visit",
                provider = "subsonic",
                albumName = "Visited Album",
                artistName = "Artist",
                coverArtId = null,
                playCount = plays,
                firstPlayedAt = first,
                lastPlayedAt = last,
            ),
        )
    }

    private fun ratingRow(rating: Float): AlbumRating = AlbumRating(
        albumId = "al-visit",
        provider = "subsonic",
        rating = rating,
        review = null,
        neoDbReviewUuid = null,
        updatedAt = day(5),
    )

    private fun twoNotes(): List<SongNote> = listOf(
        songNote("n1", "t1", "The intro hums", day(1)),
        songNote("n2", "t3", "Drums come in late", day(3)),
    )

    private fun chineseNotes(): List<SongNote> = listOf(
        songNote("n1", "t1", "前奏的吉他像在水底。", day(1)),
        songNote("n2", "t2", "副歌一直在脑子里转。", day(2)),
    )

    private fun songNote(id: String, track: String, text: String, at: Long): SongNote = SongNote(
        id = id,
        trackId = track,
        provider = "subsonic",
        content = text,
        createdAt = at,
        updatedAt = at,
        title = "Song ${track.removePrefix("t")}",
        artist = "Artist",
        positionMs = 1_000L,
    )

    /** Noon UTC on the [n]th of September 2026. */
    private fun day(n: Int): Long = LocalDate.of(2026, 9, n).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
}
