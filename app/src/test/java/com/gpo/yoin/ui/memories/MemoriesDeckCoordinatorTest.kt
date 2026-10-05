package com.gpo.yoin.ui.memories

import com.gpo.yoin.data.local.AlbumNote
import com.gpo.yoin.data.local.AlbumRating
import com.gpo.yoin.data.local.LocalRating
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.data.memory.AlbumMemoryCandidate
import com.gpo.yoin.data.memory.AlbumMemoryTitleStore
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.ui.experience.ExperienceSessionStore
import com.gpo.yoin.ui.memories.copy.MemoryNarrationBrief
import com.gpo.yoin.ui.memories.copy.MemoryProseLanguage
import com.gpo.yoin.ui.memories.copy.MemoryTitleKind
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoriesDeckCoordinatorTest {

    private val repository = mockk<YoinRepository>()
    private val sessionStore = ExperienceSessionStore()

    @Test
    fun should_load_candidates_only_once_per_session() = runTest {
        val candidates = buildAlbumCandidates(count = 8)
        val coordinator = buildCoordinator(candidates)

        val firstDeck = coordinator.ensureDeck()
        val secondDeck = coordinator.ensureDeck()

        assertEquals(
            firstDeck.map(MemoryEntry::sourceActivityId),
            secondDeck.map(MemoryEntry::sourceActivityId),
        )
        coVerify(exactly = 1) { repository.getAlbumMemoryCandidates(limit = 48) }
    }

    @Test
    fun should_replace_current_deck_when_advancing() = runTest {
        val candidates = buildAlbumCandidates(count = 8)
        val coordinator = buildCoordinator(candidates)

        val initialDeck = coordinator.ensureDeck()
        val nextDeck = coordinator.advanceDeck(MemoryDeckDirection.Forward)

        assertNotEquals(
            initialDeck.map(MemoryEntry::sourceActivityId),
            nextDeck.map(MemoryEntry::sourceActivityId),
        )
        assertEquals(
            nextDeck.map(MemoryEntry::sourceActivityId),
            sessionStore.state.value.memories.currentDeckActivityIds,
        )
        assertEquals(0, sessionStore.state.value.memories.currentPage)
    }

    @Test
    fun should_land_on_last_page_when_advancing_backward() = runTest {
        val candidates = buildAlbumCandidates(count = 8)
        val coordinator = buildCoordinator(candidates)

        coordinator.ensureDeck()
        val nextDeck = coordinator.advanceDeck(MemoryDeckDirection.Backward)

        assertEquals(nextDeck.lastIndex, sessionStore.state.value.memories.currentPage)
    }

    @Test
    fun should_prefer_album_rating_over_average_track_rating() = runTest {
        val candidate = buildAlbumCandidates(count = 1).single().copy(
            averageSongRating = 6.5f,
            albumRating = 9f,
            hasAlbumReview = true,
            noteCount = 2,
            askAiCount = 1,
        )
        val coordinator = buildCoordinator(listOf(candidate))

        val memory = coordinator.ensureDeck().single()

        assertEquals("9.0", memory.scoreText)
        assertEquals(MemoryScoreKind.ALBUM_RATING, memory.scoreKind)
        assertEquals("Album rating", memory.scoreSupportingText)
        assertTrue(memory.reasonChips.contains("Album review"))
        assertTrue(memory.reasonChips.contains("7/10 songs rated"))
        assertTrue(memory.reasonChips.contains("2 notes"))
        assertTrue(memory.reasonChips.contains("Ask AI references"))
        assertTrue(memory.reasonChips.contains("Recently revisited"))
        assertTrue(memory.reasonChips.contains("NeoDB ready"))
        // no review row resolved: Yoin asks about the album score
        assertEquals("You gave it a 9.0. What earned it?", memory.yoinQuestion)
    }

    @Test
    fun should_use_average_track_rating_when_album_rating_is_missing() = runTest {
        val candidate = buildAlbumCandidates(count = 1).single().copy(
            averageSongRating = 7.25f,
            albumRating = null,
            hasAlbumReview = false,
        )
        val coordinator = buildCoordinator(listOf(candidate))

        val memory = coordinator.ensureDeck().single()

        assertEquals("7.3", memory.scoreText)
        assertEquals(MemoryScoreKind.AVERAGE_TRACK_RATING, memory.scoreKind)
        assertEquals("Track average", memory.scoreSupportingText)
        assertTrue(memory.yoinQuestion?.isNotBlank() == true)
    }

    @Test
    fun should_notCacheEmptyPool_when_firstBuildIsEmpty() = runTest {
        // Cold start: the first build races the active source and comes back empty.
        val coordinator = buildCoordinator(emptyList())
        assertTrue(coordinator.ensureDeck().isEmpty())

        coEvery { repository.getAlbumMemoryCandidates(limit = 48) } returns buildAlbumCandidates(count = 4)
        val deck = coordinator.ensureDeck()
        coordinator.ensureDeck()

        assertEquals(4, deck.size)
        // Empty build not cached → rebuilt once; the non-empty pool IS cached.
        coVerify(exactly = 2) { repository.getAlbumMemoryCandidates(limit = 48) }
    }

    @Test
    fun should_rebuildPool_when_focusRequestedAfterEmptyPool() = runTest {
        val coordinator = buildCoordinator(emptyList())
        assertTrue(coordinator.ensureDeck().isEmpty())

        val candidates = buildAlbumCandidates(count = 8)
        coEvery { repository.getAlbumMemoryCandidates(limit = 48) } returns candidates
        val focusSessionId = candidates[5].sessionId
        val deck = coordinator.ensureDeckFocused(focusSessionId)

        assertEquals(focusSessionId, deck.first().sourceActivityId)
        val session = sessionStore.state.value.memories
        assertEquals(focusSessionId, session.currentDeckActivityIds[session.currentPage])
        coVerify(exactly = 2) { repository.getAlbumMemoryCandidates(limit = 48) }
    }

    @Test
    fun should_resolveFocusedCardFresh_when_ratingChangedSinceLastOpen() = runTest {
        val candidates = buildAlbumCandidates(count = 3).map { candidate ->
            candidate.copy(albumRating = 7f)
        }
        val coordinator = buildCoordinator(candidates)
        val focus = candidates[1]
        val staleCard = coordinator.ensureDeck().single { memory ->
            memory.sourceActivityId == focus.sessionId
        }
        assertEquals("7.0", staleCard.scoreText)

        // The user re-rates the album after the deck was opened; Home's pill now
        // shows 9.0 and the tap must land on a card that agrees.
        coEvery { repository.getAlbumMemoryCandidates(limit = 48) } returns candidates.map { candidate ->
            if (candidate.sessionId == focus.sessionId) candidate.copy(albumRating = 9f) else candidate
        }
        val deck = coordinator.ensureDeckFocused(focus.sessionId)

        val focusedCard = deck[sessionStore.state.value.memories.currentPage]
        assertEquals(focus.sessionId, focusedCard.sourceActivityId)
        assertEquals("9.0", focusedCard.scoreText)
        assertEquals(MemoryScoreKind.ALBUM_RATING, focusedCard.scoreKind)
        coVerify(exactly = 2) { repository.getAlbumMemoryCandidates(limit = 48) }
    }

    @Test
    fun should_keep_stale_entry_when_candidate_leaves_pool_after_write() = runTest {
        val candidates = buildAlbumCandidates(count = 8).map { candidate ->
            candidate.copy(albumRating = 7f)
        }
        val coordinator = buildCoordinator(candidates)
        val deck = coordinator.ensureDeck()
        sessionStore.setMemoriesCurrentPage(3)
        val sessionBefore = sessionStore.state.value.memories
        val rerated = deck[0]
        val leaving = deck[1]

        // One write re-rates the first card; another drops the second card's
        // album out of the eligible pool.
        coEvery { repository.getAlbumMemoryCandidates(limit = 48) } returns candidates
            .filterNot { candidate -> candidate.sessionId == leaving.sourceActivityId }
            .map { candidate ->
                if (candidate.sessionId == rerated.sourceActivityId) candidate.copy(albumRating = 9f) else candidate
            }
        val refreshed = coordinator.refreshDeck(deck)

        // Same cards, same order: the departed album keeps its old card so no
        // later page shifts under the user.
        assertEquals(
            deck.map(MemoryEntry::sourceActivityId),
            refreshed.map(MemoryEntry::sourceActivityId),
        )
        assertSame(leaving, refreshed[1])
        assertEquals("9.0", refreshed[0].scoreText)
        assertEquals("7.0", refreshed[2].scoreText)
        // In place: the session's deck identity and page are untouched.
        assertEquals(sessionBefore, sessionStore.state.value.memories)
        coVerify(exactly = 2) { repository.getAlbumMemoryCandidates(limit = 48) }
    }

    @Test
    fun should_return_previous_deck_when_refresh_rebuilds_empty_pool() = runTest {
        val coordinator = buildCoordinator(buildAlbumCandidates(count = 8))
        val deck = coordinator.ensureDeck()

        // The source went away mid-session: nothing to re-resolve against.
        coEvery { repository.getAlbumMemoryCandidates(limit = 48) } returns emptyList()
        val refreshed = coordinator.refreshDeck(deck)

        assertEquals(deck, refreshed)
        assertEquals(
            deck.map(MemoryEntry::sourceActivityId),
            sessionStore.state.value.memories.currentDeckActivityIds,
        )
    }

    // ── P4: copy and data model ────────────────────────────────────────
    // These avoid asserting play facts through the coordinator: historyOf()
    // still reads the pre-history candidate fields until they reach HEAD, and
    // a visit-only album hides its play facts under both.

    @Test
    fun should_group_notes_under_tracks_by_track_id() = runTest {
        val candidate = visitOnlyCandidate()
        stubAlbum(
            candidate = candidate,
            songNotes = listOf(
                songNote(id = "n1", track = "t2", text = "Late anchor", positionMs = 30_000L, at = day(3)),
                songNote(id = "n2", track = "t2", text = "No anchor", positionMs = null, at = day(1)),
                songNote(id = "n3", track = "t2", text = "Early anchor", positionMs = 10_000L, at = day(2)),
                songNote(id = "n4", track = "t4", text = "Outro note", positionMs = 5_000L, at = day(4)),
            ),
            albumNotes = listOf(albumNote(id = "a1", text = "Whole album", at = day(5))),
        )
        val memory = buildCoordinator(listOf(candidate), stubAlbumDefaults = false).ensureDeck().single()

        assertEquals(listOf("t2", "t4"), memory.diaryTracks.map { it.track.trackId })
        assertEquals(
            listOf("Early anchor", "Late anchor", "No anchor"),
            memory.diaryTracks[0].notes.map(MemoryWriting::text),
        )
        assertTrue(memory.diaryTracks[0].notes.all { it.trackId == "t2" })
        assertEquals(listOf("Outro note"), memory.diaryTracks[1].notes.map(MemoryWriting::text))
        assertEquals(listOf("Whole album"), memory.diaryAlbumNotes.map(MemoryWriting::text))
        assertEquals("a1", memory.diaryAlbumNotes.single().noteId)
        assertEquals(10_000L, memory.diaryTracks[0].notes.first().positionMs)
    }

    @Test
    fun should_list_only_rated_or_noted_tracks_when_mode_a() = runTest {
        val candidate = visitOnlyCandidate()
        stubAlbum(
            candidate = candidate,
            ratings = mapOf("t1" to 8f, "t3" to 0f),
            songNotes = listOf(
                songNote(id = "n1", track = "t4", text = "Only a note", positionMs = 1_000L, at = day(1)),
            ),
        )
        val memory = buildCoordinator(listOf(candidate), stubAlbumDefaults = false).ensureDeck().single()

        // t1 rated, t4 noted; t2 untouched and t3's 0 rating count as neither
        assertEquals(listOf("t1", "t4"), memory.diaryTracks.map { it.track.trackId })
        val first = memory.diaryTracks[0].track
        assertEquals(1, first.number)
        assertEquals(0, first.playbackIndex)
        assertEquals("Song 1", first.title)
        assertEquals(8f, first.rating)
        assertEquals(4, memory.diaryTracks[1].track.number)
        assertEquals(3, memory.diaryTracks[1].track.playbackIndex)
        assertNull(memory.diaryTracks[1].track.rating)
        // the full list keeps every song for playback
        assertEquals(4, memory.tracks.size)
    }

    @Test
    fun should_ignore_visits_when_mapping_last_heard_and_plays() = runTest {
        // Visited twice, never played: the builder folds the visits into
        // first/lastPlayedAt, but there is no play.
        val candidate = visitOnlyCandidate()
        val memory = buildCoordinator(listOf(candidate)).ensureDeck().single()

        assertNull(memory.playsInYoin)
        assertNull(memory.firstHeardAt)
        assertNull(memory.lastHeardAt)

        assertNull(memoryPlayHistory(plays = 0, firstPlayedAt = day(1), lastPlayedAt = day(2)))
        assertNull(memoryPlayHistory(plays = 3, firstPlayedAt = day(1), lastPlayedAt = null))
        assertEquals(
            MemoryPlayHistory(plays = 3, firstHeardAt = day(1), lastHeardAt = day(2)),
            memoryPlayHistory(plays = 3, firstPlayedAt = day(1), lastPlayedAt = day(2)),
        )
        assertEquals(
            MemoryPlayHistory(plays = 1, firstHeardAt = day(2), lastHeardAt = day(2)),
            memoryPlayHistory(plays = 1, firstPlayedAt = null, lastPlayedAt = day(2)),
        )
    }

    @Test
    fun should_take_last_heard_and_first_heard_from_play_history_when_visits_surround_the_plays() = runTest {
        // Played three times (days 2–4), visited before (day 1) and after (day 6): the Memory-side fields
        // absorb both visits, the play_history fields don't, and only those reach the card.
        val played = buildAlbumCandidates(count = 1).single().copy(
            playCount = 3,
            firstPlayedAt = day(1),
            lastPlayedAt = day(6),
            playCountFromHistory = 3,
            firstPlayedFromHistoryAt = day(2),
            lastPlayedFromHistoryAt = day(4),
        )
        val memory = buildCoordinator(listOf(played)).ensureDeck().single()

        assertEquals(3, memory.playsInYoin)
        assertEquals(day(2), memory.firstHeardAt)
        assertEquals(day(4), memory.lastHeardAt)
    }

    @Test
    fun should_show_no_plays_and_no_last_heard_when_history_fields_are_null() = runTest {
        // No play_history row: the history fields are null / 0 even though the Memory-side count is set.
        val candidate = buildAlbumCandidates(count = 1).single().copy(
            playCount = 5,
            firstPlayedAt = day(1),
            lastPlayedAt = day(6),
            playCountFromHistory = 0,
            firstPlayedFromHistoryAt = null,
            lastPlayedFromHistoryAt = null,
        )
        val memory = buildCoordinator(listOf(candidate)).ensureDeck().single()

        assertNull(memory.playsInYoin)
        assertNull(memory.firstHeardAt)
        assertNull(memory.lastHeardAt)
    }

    @Test
    fun should_hide_play_facts_when_album_has_no_play_history() = runTest {
        val candidate = visitOnlyCandidate().copy(albumRating = 9f)
        stubAlbum(candidate = candidate)
        val memory = buildCoordinator(listOf(candidate), stubAlbumDefaults = false).ensureDeck().single()

        // no "N plays since …" motif: nothing else fits, so the title is the album name
        assertEquals(MemoryTitleKind.ALBUM, memory.memoryTitleKind)
        assertEquals("Visited Album", memory.memoryTitle)
        // ③ keeps the question about the score but says nothing about listening
        assertNull(memory.yoinNarration)
        assertNull(memory.narrativeCopy)
        assertEquals("You gave it a 9.0. What earned it?", memory.yoinQuestion)
        assertNull(memory.playsInYoin)
        assertNull(memory.lastHeardAt)
    }

    @Test
    fun should_fall_back_to_motif_title_when_ai_title_missing() = runTest {
        val candidate = visitOnlyCandidate()
        stubAlbum(
            candidate = candidate,
            songNotes = listOf(
                songNote(id = "n1", track = "t1", text = "The intro hums", positionMs = 4_000L, at = day(1)),
                songNote(id = "n2", track = "t3", text = "Drums come in late", positionMs = 9_000L, at = day(3)),
            ),
        )
        val memory = buildCoordinator(listOf(candidate), stubAlbumDefaults = false).ensureDeck().single()

        assertEquals(MemoryTitleKind.MOTIF, memory.memoryTitleKind)
        assertEquals("Three days, two notes", memory.memoryTitle)
        assertEquals(MemoryProseLanguage.EN, memory.proseLanguage)
        // the motif already said the count and the span: the narration only adds the latest note
        assertEquals("The latest was on Song 3.", memory.yoinNarration)
        assertEquals("Put together, what would you say about the album?", memory.yoinQuestion)
        // the excerpt opens with the first song note in album order
        assertEquals("The intro hums", memory.excerptCandidates.first().text)
        assertEquals("Your note · Song 1 0:04", memory.excerptCandidates.first().attribution)
    }

    @Test
    fun should_keep_ai_title_and_write_in_chinese_when_notes_are_chinese() = runTest {
        val candidate = visitOnlyCandidate()
        stubAlbum(
            candidate = candidate,
            aiTitle = "雨天里的水底吉他",
            songNotes = listOf(
                songNote(id = "n1", track = "t1", text = "前奏的吉他像在水底。", positionMs = 12_000L, at = day(1)),
                songNote(id = "n2", track = "t2", text = "副歌一直在脑子里转。", positionMs = 64_000L, at = day(2)),
            ),
        )
        val memory = buildCoordinator(listOf(candidate), stubAlbumDefaults = false).ensureDeck().single()

        assertEquals(MemoryTitleKind.AI, memory.memoryTitleKind)
        assertEquals("雨天里的水底吉他", memory.memoryTitle)
        assertEquals(MemoryProseLanguage.ZH, memory.proseLanguage)
        assertEquals("两天里记了两条笔记，最近一条写在《Song 2》。", memory.yoinNarration)
        assertEquals("合起来看，这张专辑你会怎么说？", memory.yoinQuestion)
    }

    @Test
    fun should_use_gemini_narration_when_source_answers() = runTest {
        val candidate = visitOnlyCandidate()
        stubAlbum(
            candidate = candidate,
            songNotes = listOf(
                songNote(id = "n1", track = "t1", text = "The intro hums", positionMs = 4_000L, at = day(1)),
                songNote(id = "n2", track = "t3", text = "Drums come in late", positionMs = 9_000L, at = day(3)),
            ),
        )
        val briefs = mutableListOf<MemoryNarrationBrief>()
        val coordinator = buildCoordinator(listOf(candidate), stubAlbumDefaults = false).apply {
            narrationSource = MemoryNarrationSource { _, brief ->
                briefs += brief
                MemoryNarration("You wrote on two songs in three days.", "Which one pulled you back?")
            }
        }
        val memory = coordinator.ensureDeck().single()

        assertEquals("You wrote on two songs in three days.", memory.yoinNarration)
        assertEquals("Which one pulled you back?", memory.yoinQuestion)
        assertEquals("You wrote on two songs in three days.", memory.narrativeCopy)
        val brief = briefs.single()
        assertEquals(MemoryProseLanguage.EN, brief.language)
        assertEquals("Three days, two notes", brief.alreadySaid)
        // listening facts only: never the user's words
        assertTrue(brief.facts.none { fact -> "intro hums" in fact || "Drums" in fact })
    }

    @Test
    fun should_fall_back_to_template_when_narration_source_fails() = runTest {
        val candidate = visitOnlyCandidate()
        stubAlbum(
            candidate = candidate,
            songNotes = listOf(
                songNote(id = "n1", track = "t1", text = "The intro hums", positionMs = 4_000L, at = day(1)),
                songNote(id = "n2", track = "t3", text = "Drums come in late", positionMs = 9_000L, at = day(3)),
            ),
        )
        val coordinator = buildCoordinator(listOf(candidate), stubAlbumDefaults = false).apply {
            narrationSource = MemoryNarrationSource { _, _ -> error("offline") }
        }
        val memory = coordinator.ensureDeck().single()

        // the local template, under the notes motif ("Three days, two notes"): only the latest note
        assertEquals("The latest was on Song 3.", memory.yoinNarration)
        assertEquals("Put together, what would you say about the album?", memory.yoinQuestion)
    }

    @Test
    fun should_not_narrate_when_review_present() = runTest {
        val candidate = visitOnlyCandidate().copy(albumRating = 8f, hasAlbumReview = true)
        stubAlbum(
            candidate = candidate,
            review = "Quiet start. The bridge lifts. Then it ends.",
        )
        var asked = false
        val coordinator = buildCoordinator(listOf(candidate), stubAlbumDefaults = false).apply {
            narrationSource = MemoryNarrationSource { _, _ ->
                asked = true
                null
            }
        }
        val memory = coordinator.ensureDeck().single()

        assertEquals(false, asked)
        assertNull(memory.yoinNarration)
        assertNull(memory.yoinQuestion)
        assertEquals("Quiet start. The bridge lifts. Then it ends.", memory.excerptCandidates.first().text)
        assertEquals(
            listOf("Quiet start. The bridge lifts.", "Quiet start."),
            memory.excerptCandidatesMedium.filterNot { it.attributionOnly }.map { it.text },
        )
        assertNotNull(memory.review)
    }

    /** Visited, rated a little, never played in Yoin (playCount 0). */
    private fun visitOnlyCandidate(): AlbumMemoryCandidate = buildAlbumCandidates(count = 1).single().copy(
        albumId = "al-visit",
        albumName = "Visited Album",
        totalTracks = 4,
        ratedTrackCount = 0,
        ratingCoverage = 0f,
        averageSongRating = null,
        playCount = 0,
        firstPlayedAt = day(1),
        lastPlayedAt = day(6),
    )

    private fun stubAlbum(
        candidate: AlbumMemoryCandidate,
        ratings: Map<String, Float> = emptyMap(),
        songNotes: List<SongNote> = emptyList(),
        albumNotes: List<AlbumNote> = emptyList(),
        review: String? = null,
        aiTitle: String? = null,
    ) {
        val albumId = MediaId(candidate.provider, candidate.albumId)
        val songs = (1..4).map { n ->
            Track(
                id = MediaId(candidate.provider, "t$n"),
                title = "Song $n",
                artist = "Artist",
                artistId = null,
                album = candidate.albumName,
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
            name = candidate.albumName,
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
        coEvery { repository.getRatings(any()) } returns ratings.entries.associate { (raw, rating) ->
            MediaId(candidate.provider, raw) to LocalRating(
                songId = raw,
                provider = candidate.provider,
                rating = rating,
                serverRating = 0,
            )
        }
        coEvery { repository.getAlbumRatingRow(albumId) } returns review?.let { text ->
            AlbumRating(
                albumId = candidate.albumId,
                provider = candidate.provider,
                rating = candidate.albumRating ?: 0f,
                review = text,
                neoDbReviewUuid = null,
                updatedAt = day(5),
            )
        }
        coEvery { repository.getAlbumNotesOnce(albumId) } returns albumNotes
        coEvery { repository.getSongNotesOnce(any()) } returns songNotes
        coEvery { repository.getOrGenerateAlbumMemoryTitle(any(), any(), any()) } returns aiTitle
        every { repository.resolveCoverUrl(any(), any()) } returns null
    }

    private fun songNote(id: String, track: String, text: String, positionMs: Long?, at: Long): SongNote = SongNote(
        id = id,
        trackId = track,
        provider = "subsonic",
        content = text,
        createdAt = at,
        updatedAt = at,
        title = "Song ${track.removePrefix("t")}",
        artist = "Artist",
        positionMs = positionMs,
    )

    private fun albumNote(id: String, text: String, at: Long): AlbumNote = AlbumNote(
        id = id,
        albumId = "al-visit",
        provider = "subsonic",
        content = text,
        createdAt = at,
        updatedAt = at,
        albumName = "Visited Album",
        artist = "Artist",
    )

    /** Noon UTC on the [n]th of September 2026. */
    private fun day(n: Int): Long = LocalDate.of(2026, 9, n).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun buildCoordinator(
        candidates: List<AlbumMemoryCandidate>,
        stubAlbumDefaults: Boolean = true,
        titleStore: AlbumMemoryTitleStore? = null,
    ): MemoriesDeckCoordinator {
        coEvery { repository.getAlbumMemoryCandidates(limit = 48) } returns candidates
        if (stubAlbumDefaults) {
            coEvery { repository.getAlbum(any()) } returns null
            coEvery { repository.getRatings(any()) } returns emptyMap()
        }

        return MemoriesDeckCoordinator(
            repository = repository,
            sessionStore = sessionStore,
            randomSeed = 42L,
            titleStore = titleStore,
            clock = { day(10) },
            zone = { ZoneId.of("UTC") },
        )
    }

    private fun titleStore(): AlbumMemoryTitleStore =
        AlbumMemoryTitleStore(FakeAlbumMemoryTitleDao(), MutableStateFlow("profile-a"), clock = { day(10) })

    private fun twoNoteAlbum(): AlbumMemoryCandidate = visitOnlyCandidate().also { candidate ->
        stubAlbum(
            candidate = candidate,
            songNotes = listOf(
                songNote(id = "n1", track = "t1", text = "The intro hums", positionMs = 4_000L, at = day(1)),
                songNote(id = "n2", track = "t3", text = "Drums come in late", positionMs = 9_000L, at = day(3)),
            ),
        )
    }

    @Test
    fun should_show_user_title_over_motif_when_user_named_album() = runTest {
        val candidate = twoNoteAlbum()
        val store = titleStore()
        store.setTitle(MediaId("subsonic", "al-visit"), "Night bus")

        val memory = buildCoordinator(listOf(candidate), stubAlbumDefaults = false, titleStore = store)
            .ensureDeck()
            .single()

        assertEquals(MemoryTitleKind.USER, memory.memoryTitleKind)
        assertEquals("Night bus", memory.memoryTitle)
        // Yoin's own title waits underneath, so the card can offer it back
        assertEquals("Three days, two notes", memory.generatedMemoryTitle)
        assertEquals(MemoryTitleKind.MOTIF, memory.generatedMemoryTitleKind)
        assertTrue(memory.canRestoreGeneratedTitle())
        // the user's title never steers Yoin's prose: same narration, same language as without it
        assertEquals("The latest was on Song 3.", memory.yoinNarration)
        assertEquals(MemoryProseLanguage.EN, memory.proseLanguage)
    }

    @Test
    fun should_follow_title_store_when_card_comes_from_cache() = runTest {
        val candidate = twoNoteAlbum()
        val store = titleStore()
        val coordinator = buildCoordinator(listOf(candidate), stubAlbumDefaults = false, titleStore = store)
        assertEquals(MemoryTitleKind.MOTIF, coordinator.ensureDeck().single().memoryTitleKind)

        // the resolve is cached (no second resolve); the title is read fresh on every deal
        store.setTitle(MediaId("subsonic", "al-visit"), "Night bus")
        val named = coordinator.ensureDeck().single()
        coVerify(exactly = 1) { repository.getOrGenerateAlbumMemoryTitle(any(), any(), any()) }
        assertEquals("Night bus", named.memoryTitle)
        assertEquals(MemoryTitleKind.USER, named.memoryTitleKind)

        store.clearTitle(MediaId("subsonic", "al-visit"))
        val restored = coordinator.refreshDeck(listOf(named)).single()
        assertEquals("Three days, two notes", restored.memoryTitle)
        assertEquals(MemoryTitleKind.MOTIF, restored.memoryTitleKind)
    }

    @Test
    fun should_lay_user_title_over_album_name_and_take_it_off_in_place() {
        val plain = buildMemoryEntry("Visited Album", memoryTitle = "Visited Album", kind = MemoryTitleKind.ALBUM)

        val named = plain.withUserMemoryTitle("Night bus")
        assertEquals("Night bus", named.memoryTitle)
        assertEquals(MemoryTitleKind.USER, named.memoryTitleKind)
        // only the album name under it: nothing of Yoin's to restore
        assertFalse(named.canRestoreGeneratedTitle())

        val back = named.withUserMemoryTitle(null)
        assertEquals("Visited Album", back.memoryTitle)
        assertEquals(MemoryTitleKind.ALBUM, back.memoryTitleKind)
    }

    @Test
    fun should_keep_ai_title_underneath_when_entry_predates_generated_fields() {
        // an entry built without the generated fields counts its own AI title as Yoin's
        val legacy = buildMemoryEntry(title = "Album", memoryTitle = "Rain on the glass", kind = MemoryTitleKind.AI)

        val named = legacy.withUserMemoryTitle("Mine")
        assertEquals(MemoryTitleKind.USER, named.memoryTitleKind)
        assertEquals("Rain on the glass", named.generatedMemoryTitle)
        assertTrue(named.canRestoreGeneratedTitle())

        val restored = named.withUserMemoryTitle(null)
        assertEquals("Rain on the glass", restored.memoryTitle)
        assertEquals(MemoryTitleKind.AI, restored.memoryTitleKind)
        assertEquals(restored, restored.withUserMemoryTitle(null))
    }

    private fun buildMemoryEntry(title: String, memoryTitle: String, kind: MemoryTitleKind): MemoryEntry = MemoryEntry(
        stableId = "album:profile-a:subsonic:al-visit",
        sourceActivityId = 1L,
        entityType = MemoryEntityType.ALBUM,
        entityId = "al-visit",
        entityProvider = "subsonic",
        title = title,
        supportingText = "Artist",
        metaText = null,
        coverArtUrl = null,
        timestamp = 0L,
        scoreText = "N/A",
        scoreSupportingText = null,
        footerText = null,
        memoryTitle = memoryTitle,
        memoryTitleKind = kind,
        playbackSongs = emptyList(),
        tracks = emptyList(),
    )

    private fun buildAlbumCandidates(count: Int): List<AlbumMemoryCandidate> =
        (1..count).map { index ->
            AlbumMemoryCandidate(
                profileId = "profile-a",
                provider = "subsonic",
                albumId = "album-$index",
                albumName = "Album $index",
                artistName = "Artist $index",
                totalTracks = 10,
                ratedTrackCount = 7,
                ratingCoverage = 0.7f,
                averageSongRating = 7f,
                albumRating = null,
                hasAlbumReview = false,
                noteCount = 0,
                askAiCount = 0,
                firstPlayedAt = 1000L + index,
                lastPlayedAt = 2000L + index,
                playCount = 1,
                neoDbSynced = false,
                isMemoryEligible = true,
                year = null,
                durationSeconds = null,
                coverArtUrl = null,
            )
        }
}
