package com.gpo.yoin.ui.memories

import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.data.local.LocalRating
import com.gpo.yoin.data.memory.AlbumMemoryCandidate
import com.gpo.yoin.data.memory.AlbumMemoryTitleSource
import com.gpo.yoin.data.memory.AlbumMemoryTitleStore
import com.gpo.yoin.data.memory.resolveAlbumMemoryTitle
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.ui.experience.ExperienceSessionStore
import com.gpo.yoin.ui.memories.copy.MemoryCopyInput
import com.gpo.yoin.ui.memories.copy.MemoryCopyNote
import com.gpo.yoin.ui.memories.copy.MemoryCopyTrack
import com.gpo.yoin.ui.memories.copy.MemoryDates
import com.gpo.yoin.ui.memories.copy.MemoryExcerpt
import com.gpo.yoin.ui.memories.copy.MemoryListening
import com.gpo.yoin.ui.memories.copy.MemoryScores
import com.gpo.yoin.ui.memories.copy.MemoryTitleKind
import com.gpo.yoin.ui.memories.copy.MemoryVoice
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlin.random.Random

class MemoriesDeckCoordinator(
    private val repository: YoinRepository,
    private val sessionStore: ExperienceSessionStore,
    randomSeed: Long = System.currentTimeMillis(),
    narrationSource: MemoryNarrationSource? = null,
    /**
     * The user's own titles, laid over Yoin's on every card this hands out ([withUserMemoryTitle]); null =
     * Yoin's titles only (tests, previews). Read fresh per deal, never cached with the resolve.
     */
    private val titleStore: AlbumMemoryTitleStore? = null,
    /** "Today" for the copy (date grammar, "two days ago", days since first play). */
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    /**
     * Gemini narration; null = local templates only. Settable because the
     * coordinator is built in AppContainer, which another session is editing:
     * MemoriesViewModel.Factory attaches it until AppContainer can pass it to
     * the constructor.
     */
    @Volatile
    internal var narrationSource: MemoryNarrationSource? = narrationSource

    private val random = Random(randomSeed)
    private val resolvedMemoryCache = mutableMapOf<Long, MemoryEntry?>()
    // Bumped by invalidate(); a resolve only caches into the generation it
    // started in.
    private var cacheGeneration = 0L
    private var candidateAlbums: List<AlbumMemoryCandidate>? = null

    suspend fun ensureDeck(): List<MemoryEntry> {
        val candidates = ensureCandidates()
        if (candidates.isEmpty()) {
            sessionStore.clearMemories()
            return emptyList()
        }

        val session = sessionStore.state.value.memories
        val desiredCandidateIds = if (session.currentDeckActivityIds.isNotEmpty()) {
            session.currentDeckActivityIds
        } else {
            sampleDeckCandidates(
                candidates = candidates,
                excludedCandidateIds = emptySet(),
            ).map(AlbumMemoryCandidate::sessionId)
        }

        val memories = resolveDeck(desiredCandidateIds)
        if (memories.isEmpty()) {
            sessionStore.clearMemories()
            return emptyList()
        }

        val actualActivityIds = memories.map(MemoryEntry::sourceActivityId)
        val boundedPage = session.currentPage.coerceIn(0, memories.lastIndex)
        if (session.currentDeckActivityIds != actualActivityIds || session.deckId == 0L) {
            sessionStore.replaceMemoriesDeck(
                activityIds = actualActivityIds,
                currentPage = boundedPage,
            )
        }
        return memories
    }

    /**
     * Build a deck guaranteed to contain [focusSessionId] as its first card,
     * filling the rest from the usual sample. Used when the home teaser routes
     * the user into Memories — so the album the teaser showed is what they land
     * on, not a random card from a re-sampled deck.
     *
     * Always starts from a fresh pool and fresh resolves ([invalidate] first):
     * the Home pill / JBI card the user just tapped showed this memory's LIVE
     * score, so the focus tap must not land on a stale cached card (the cache
     * is only dropped on profile switch / force refresh, never on rating, note
     * or review writes) or on a cached empty pool. Costs one candidate build
     * per focus tap — mostly local reads, covered by the reveal animation. If
     * the focus album isn't in the fresh pool (rare — it ranks high by the same
     * builder), the deck degrades gracefully to its first resolved card.
     */
    suspend fun ensureDeckFocused(focusSessionId: Long): List<MemoryEntry> {
        invalidate()
        val candidates = ensureCandidates()
        if (candidates.isEmpty()) {
            sessionStore.clearMemories()
            return emptyList()
        }

        val desiredCandidateIds = (
            listOf(focusSessionId) +
                sampleDeckCandidates(
                    candidates = candidates,
                    excludedCandidateIds = setOf(focusSessionId),
                ).map(AlbumMemoryCandidate::sessionId)
            ).distinct().take(MEMORY_DECK_SIZE)

        val memories = resolveDeck(desiredCandidateIds)
        if (memories.isEmpty()) {
            sessionStore.clearMemories()
            return emptyList()
        }

        // Index from the RESOLVED list — resolveAlbumMemory can drop the focus
        // album on a cold getAlbum failure, so never assume index 0.
        val focusPage = memories
            .indexOfFirst { memory -> memory.sourceActivityId == focusSessionId }
            .coerceAtLeast(0)
        // Persist unconditionally: bumping deckId is what makes the keyed pager
        // re-read initialPage and jump to the focused card.
        sessionStore.replaceMemoriesDeck(
            activityIds = memories.map(MemoryEntry::sourceActivityId),
            currentPage = focusPage,
        )
        return memories
    }

    suspend fun advanceDeck(direction: MemoryDeckDirection): List<MemoryEntry> {
        val candidates = ensureCandidates()
        if (candidates.isEmpty()) {
            sessionStore.clearMemories()
            return emptyList()
        }

        val nextMemories = resolveDeck(
            sampleDeckCandidates(
                candidates = candidates,
                excludedCandidateIds = sessionStore.state.value.memories.currentDeckActivityIds.toSet(),
            ).map(AlbumMemoryCandidate::sessionId),
        )
        if (nextMemories.isEmpty()) {
            return emptyList()
        }

        sessionStore.replaceMemoriesDeck(
            activityIds = nextMemories.map(MemoryEntry::sourceActivityId),
            currentPage = when (direction) {
                MemoryDeckDirection.Backward -> nextMemories.lastIndex
                MemoryDeckDirection.Forward -> 0
            },
        )
        return nextMemories
    }

    /**
     * Re-resolve [currentDeck] in place after a memory write (rating, note,
     * review): drops every cache, rebuilds the pool, then re-resolves each card
     * in the deck's own order. Nothing is re-dealt and the session store is not
     * touched, so deckId / currentPage — and with them the pager — stay put.
     *
     * A card whose album has left the pool (the write made it ineligible), or
     * whose resolve fails, keeps its previous entry: dropping it would shift
     * every later page under the user's finger. An empty rebuild (source gone)
     * therefore hands the old deck back unchanged.
     */
    suspend fun refreshDeck(currentDeck: List<MemoryEntry>): List<MemoryEntry> {
        if (currentDeck.isEmpty()) return currentDeck
        invalidate()
        val pool = ensureCandidates().associateBy(AlbumMemoryCandidate::sessionId)
        return coroutineScope {
            currentDeck
                .map { previous ->
                    async {
                        val candidate = pool[previous.sourceActivityId] ?: return@async withStoredTitle(previous)
                        val resolved = try {
                            resolveMemoryCached(candidate) ?: previous
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Exception) {
                            previous
                        }
                        withStoredTitle(resolved)
                    }
                }
                .awaitAll()
        }
    }

    fun invalidate() {
        candidateAlbums = null
        resolvedMemoryCache.clear()
        cacheGeneration++
    }

    /**
     * Never caches an empty pool: a cold-start build can run before the active
     * source is up and come back empty, which would otherwise pin the deck on
     * "No memories yet" for the rest of the session. Every caller returns early
     * on an empty pool, so [findCandidateById] only runs against a cached one.
     */
    private suspend fun ensureCandidates(): List<AlbumMemoryCandidate> {
        val existing = candidateAlbums
        if (existing != null) return existing
        return repository.getAlbumMemoryCandidates(limit = 48).also { loaded ->
            if (loaded.isNotEmpty()) candidateAlbums = loaded
        }
    }

    private suspend fun resolveDeck(activityIds: List<Long>): List<MemoryEntry> = coroutineScope {
        activityIds
            .mapNotNull(::findCandidateById)
            .map { candidate ->
                async { resolveMemoryCached(candidate)?.let { memory -> withStoredTitle(memory) } }
            }
            .awaitAll()
            .filterNotNull()
    }

    /**
     * [memory] with the user's stored title laid over Yoin's (or taken off, when there is none any more).
     * The resolve cache keeps Yoin's titles only, so a title edited since the card was cached still shows.
     * A failed read keeps Yoin's title rather than the card.
     */
    private suspend fun withStoredTitle(memory: MemoryEntry): MemoryEntry {
        val store = titleStore ?: return memory
        if (memory.entityType != MemoryEntityType.ALBUM) return memory
        val userTitle = try {
            store.getTitle(MediaId(memory.entityProvider, memory.entityId))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return memory
        }
        return memory.withUserMemoryTitle(userTitle)
    }

    private fun findCandidateById(candidateId: Long): AlbumMemoryCandidate? =
        candidateAlbums?.firstOrNull { it.sessionId == candidateId }

    private fun sampleDeckCandidates(
        candidates: List<AlbumMemoryCandidate>,
        excludedCandidateIds: Set<Long>,
    ): List<AlbumMemoryCandidate> {
        val prioritized = candidates
            .filterNot { candidate -> candidate.sessionId in excludedCandidateIds }
            .shuffled(random)
        val fallback = candidates
            .filter { candidate -> candidate.sessionId in excludedCandidateIds }
            .shuffled(random)

        return (prioritized + fallback)
            .take(MEMORY_DECK_SIZE)
    }

    private suspend fun resolveMemoryCached(candidate: AlbumMemoryCandidate): MemoryEntry? {
        resolvedMemoryCache[candidate.sessionId]?.let { return it }
        val generation = cacheGeneration
        val memory = resolveAlbumMemory(candidate)
        // A resolve cancelled mid-flight (its runCatching swallowed the
        // cancellation) comes back degraded, and one that outlived an
        // invalidate() is stale: neither may land in the fresh cache a focus
        // tap just asked for.
        currentCoroutineContext().ensureActive()
        if (generation == cacheGeneration) resolvedMemoryCache[candidate.sessionId] = memory
        return memory
    }

    private fun rawEntityId(provider: String, raw: String): String = MediaId.storedRawId(provider, raw)

    /**
     * Bug 5 (PLAN §2): every "heard" fact (the top bar's Last heard, the
     * footer's two numerals, the narration's plays and dates, the plays motif)
     * comes from play_history only, never from VISITED events: the candidate's
     * *FromHistory fields, which are null / 0 for an album with no history row
     * (then: no footer, no Last heard, no plays in the copy).
     */
    private fun historyOf(candidate: AlbumMemoryCandidate): MemoryPlayHistory? = memoryPlayHistory(
        candidate.playCountFromHistory,
        candidate.firstPlayedFromHistoryAt,
        candidate.lastPlayedFromHistoryAt,
    )

    private suspend fun resolveSongMemory(activity: ActivityEvent): MemoryEntry {
        val provider = activity.provider
        val rawSongId = rawEntityId(provider, activity.songId ?: activity.entityId)
        val trackId = MediaId(provider, rawSongId)
        val rating = repository.getRating(trackId).first()?.rating
        val mostRecentPlay = repository.getMostRecentPlay(trackId)
        val rawAlbumId = activity.albumId
            ?.takeIf(String::isNotBlank)
            ?: mostRecentPlay?.albumId?.takeIf(String::isNotBlank)
        val rawArtistId = activity.artistId?.takeIf(String::isNotBlank)
        val coverArtId = activity.coverArtId ?: mostRecentPlay?.coverArtId
        val song = Track(
            id = trackId,
            title = activity.title,
            artist = activity.subtitle.takeIf { subtitle -> subtitle.isNotBlank() },
            artistId = rawArtistId?.let { MediaId(provider, it) },
            album = mostRecentPlay?.album?.takeIf(String::isNotBlank),
            albumId = rawAlbumId?.let { MediaId(provider, it) },
            // `coverArtId` is a storage key, not always a Subsonic raw id —
            // Spotify rows carry the full URL here.
            coverArt = CoverRef.fromStorageKey(coverArtId),
            durationSec = mostRecentPlay?.durationMs?.let { durationMs -> (durationMs / 1000L).toInt() },
            trackNumber = null,
            year = null,
            genre = null,
            userRating = null,
            isStarred = false,
        )

        return MemoryEntry(
            stableId = "song:$rawSongId:${activity.id}",
            sourceActivityId = activity.id,
            entityType = MemoryEntityType.SONG,
            entityId = rawSongId,
            entityProvider = provider,
            title = activity.title,
            supportingText = buildString {
                append("Single")
                if (activity.subtitle.isNotBlank()) {
                    append(" by ")
                    append(activity.subtitle)
                }
            },
            metaText = null,
            coverArtUrl = coverArtId?.let(::resolveStorageKeyCoverUrl)
                ?: rawAlbumId
                    ?.takeIf { provider == MediaId.PROVIDER_SUBSONIC }
                    ?.let(::sourceRelativeCoverArtUrl),
            timestamp = activity.timestamp,
            scoreText = rating.formatScore(),
            scoreSupportingText = null,
            footerText = mostRecentPlay?.durationMs
                ?.takeIf { durationMs -> durationMs > 0L }
                ?.let { durationMs -> formatDurationSeconds((durationMs / 1000L).toInt()) },
            playbackSongs = listOf(song),
            tracks = listOf(
                MemoryTrack(
                    stableId = "song:$rawSongId",
                    title = activity.title,
                    artist = activity.subtitle,
                    durationSeconds = mostRecentPlay?.durationMs?.let { durationMs ->
                        (durationMs / 1000L).toInt()
                    },
                    rating = rating,
                ),
            ),
        )
    }

    private suspend fun resolveAlbumMemory(candidate: AlbumMemoryCandidate): MemoryEntry {
        val rawAlbumId = rawEntityId(candidate.provider, candidate.albumId)
        val albumId = MediaId(candidate.provider, rawAlbumId)
        val album = runCatching { repository.getAlbum(albumId) }.getOrNull()
        val songs = album?.tracks.orEmpty()
        val ratings = repository.getRatings(songs.map(Track::id))
        val rated = songs.mapNotNull { song ->
            ratings[song.id]?.takeIf { localRating -> localRating.rating > 0f }
        }
        val averageRating = rated
            .map(LocalRating::rating)
            .takeIf(List<Float>::isNotEmpty)
            ?.average()
            ?.toFloat()
            ?: candidate.averageSongRating
        val scoreKind = when {
            candidate.albumRating != null -> MemoryScoreKind.ALBUM_RATING
            averageRating != null -> MemoryScoreKind.AVERAGE_TRACK_RATING
            else -> MemoryScoreKind.NONE
        }
        val score = when (scoreKind) {
            MemoryScoreKind.ALBUM_RATING -> candidate.albumRating
            MemoryScoreKind.AVERAGE_TRACK_RATING -> averageRating
            MemoryScoreKind.NONE -> null
        }

        // 用户的字：乐评行 + album/song 笔记，deck 每卡一次快照读取。
        val ratingRow = runCatching { repository.getAlbumRatingRow(albumId) }.getOrNull()
        val review = ratingRow?.review?.takeIf(String::isNotBlank)?.let { text ->
            MemoryWriting(
                kind = MemoryWriting.Kind.REVIEW,
                text = text,
                writtenAt = ratingRow.updatedAt,
            )
        }
        val writings = runCatching { loadAlbumWritings(albumId, songs) }.getOrDefault(emptyList())

        val neoDbState = when {
            candidate.neoDbSynced -> MemoryNeoDbState.SYNCED
            review != null && candidate.albumRating != null -> MemoryNeoDbState.READY
            candidate.albumRating != null -> MemoryNeoDbState.NEEDS_REVIEW
            review != null -> MemoryNeoDbState.NEEDS_RATING
            else -> MemoryNeoDbState.UNAVAILABLE
        }

        // 正文槽阶梯①②的占用者决定拟题输入（design.md 拟题豁免）。没有 AI 拟题时
        // 回退到本地动机短句（owner 2026-10-04），再不行才是专辑名。
        val titleOccupant = review ?: writings.firstOrNull()
        val aiTitle = album?.let { resolved ->
            runCatching {
                repository.getOrGenerateAlbumMemoryTitle(
                    album = resolved,
                    writingKind = when (titleOccupant?.kind) {
                        MemoryWriting.Kind.REVIEW -> "album review"
                        MemoryWriting.Kind.ALBUM_NOTE, MemoryWriting.Kind.SONG_NOTE -> "note"
                        null -> null
                    },
                    writingText = titleOccupant?.text,
                )
            }.getOrNull()
        }

        val tracks = songs.mapIndexed { index, song ->
            MemoryTrack(
                stableId = "album:$rawAlbumId:song:${song.id}",
                title = song.title.orEmpty(),
                artist = song.artist.orEmpty(),
                durationSeconds = song.durationSec,
                rating = ratings[song.id]?.rating?.takeIf { rating -> rating > 0f },
                number = song.trackNumber ?: (index + 1),
                trackId = song.id.rawId,
                playbackIndex = index,
            ).withIndexFallback(index)
        }
        val history = historyOf(candidate)
        val zone = zone()
        val today = MemoryDates.localDate(clock(), zone)
        val copyInput = memoryCopyInput(
            albumName = album?.name ?: candidate.albumName,
            aiTitle = aiTitle,
            review = review,
            writings = writings,
            tracks = tracks,
            ratedTracks = if (songs.isNotEmpty()) rated.size else candidate.ratedTrackCount,
            totalTracks = songs.size.takeIf { count -> count > 0 } ?: candidate.totalTracks,
            albumScore = candidate.albumRating?.takeIf { scoreKind == MemoryScoreKind.ALBUM_RATING },
            history = history,
            zone = zone,
        )
        val voice = MemoryVoice.compose(copyInput, today)
        // Yoin's narration (only without a review): Gemini in the prototype's
        // voice when a key is set, else the local template.
        val gemini = MemoryVoice.narrationBrief(copyInput, voice, today)?.let { brief ->
            narrationSource?.let { source ->
                try {
                    source.narrate(albumId, brief)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    null
                }
            }
        }
        val narration = gemini?.narration ?: voice.narration
        val question = gemini?.question ?: voice.question
        val reasonChips = buildAlbumReasonChips(candidate)

        return MemoryEntry(
            stableId = "album:${candidate.profileId}:${candidate.provider}:$rawAlbumId",
            sourceActivityId = candidate.sessionId,
            entityType = MemoryEntityType.ALBUM,
            entityId = rawAlbumId,
            entityProvider = candidate.provider,
            title = album?.name ?: candidate.albumName,
            // 印章卡标题区第二行：「艺人 · 年份」。Memories 是这个字段唯一的
            // 消费方，格式跟着卡走。
            supportingText = listOfNotNull(
                (album?.artist ?: candidate.artistName)?.takeIf(String::isNotBlank),
                (album?.year ?: candidate.year)?.toString(),
            ).joinToString(" · ").ifBlank { "Album" },
            metaText = null,
            coverArtUrl = album?.coverArt?.let { repository.resolveCoverUrl(it, size = 480) }
                ?: candidate.coverArtUrl
                ?: album?.id?.takeIf { it.provider == MediaId.PROVIDER_SUBSONIC }
                    ?.rawId?.let(::sourceRelativeCoverArtUrl),
            // the playback theme's own URL for this cover (resolveCoverUrl without a size)
            paletteCoverUrl = album?.coverArt?.let { cover -> repository.resolveCoverUrl(cover) },
            timestamp = candidate.lastPlayedAt ?: candidate.firstPlayedAt ?: 0L,
            scoreText = score.formatScore(),
            scoreKind = scoreKind,
            scoreSupportingText = scoreKind.label,
            footerText = buildCollectionFooter(
                songCount = album?.songCount ?: candidate.totalTracks.takeIf { count -> count > 0 },
                durationSeconds = album?.durationSec ?: candidate.durationSeconds,
            ),
            hasAlbumReview = candidate.hasAlbumReview,
            noteCount = candidate.noteCount,
            askAiCount = candidate.askAiCount,
            ratedTrackCount = candidate.ratedTrackCount,
            totalTrackCount = candidate.totalTracks,
            ratingCoverage = candidate.ratingCoverage,
            playCount = candidate.playCount,
            firstPlayedAt = candidate.firstPlayedAt,
            lastPlayedAt = candidate.lastPlayedAt,
            neoDbSynced = candidate.neoDbSynced,
            neoDbState = neoDbState,
            memoryTitle = voice.title,
            review = review,
            writings = writings,
            reasonChips = reasonChips,
            // The old card shows this only without a review, above its own prompt line.
            narrativeCopy = narration,
            playbackSongs = songs,
            tracks = tracks,
            playsInYoin = history?.plays,
            firstHeardAt = history?.firstHeardAt,
            lastHeardAt = history?.lastHeardAt,
            memoryTitleKind = voice.titleKind,
            // Yoin's own title, kept under a user title ([withStoredTitle] lays that over it)
            generatedMemoryTitle = voice.title.takeIf { voice.titleKind != MemoryTitleKind.ALBUM },
            generatedMemoryTitleKind = voice.titleKind.takeIf { it != MemoryTitleKind.ALBUM },
            proseLanguage = voice.language,
            yoinNarration = narration,
            yoinQuestion = question,
            excerptCandidates = MemoryExcerpt.candidates(copyInput, capped = false, today = today),
            excerptCandidatesMedium = MemoryExcerpt.candidates(copyInput, capped = true, today = today),
            diaryAlbumNotes = diaryAlbumNotes(writings),
            diaryTracks = diaryTracks(tracks, writings),
        )
    }

    private suspend fun resolvePlaylistMemory(activity: ActivityEvent): MemoryEntry {
        val rawPlaylistId = rawEntityId(activity.provider, activity.entityId)
        val playlistId = MediaId(activity.provider, rawPlaylistId)
        val playlist = runCatching { repository.getPlaylist(playlistId) }.getOrNull()
        val songs = playlist?.tracks.orEmpty()
        val ratings = repository.getRatings(songs.map(Track::id))
        val rated = songs.mapNotNull { song ->
            ratings[song.id]?.takeIf { localRating -> localRating.rating > 0f }
        }

        val coverArtUrl = playlist?.coverArt?.let { repository.resolveCoverUrl(it, size = 480) }
            ?: songs.firstNotNullOfOrNull(::trackCoverArtUrl)
            ?: activity.coverArtId?.let(::resolveStorageKeyCoverUrl)

        return MemoryEntry(
            stableId = "playlist:${activity.entityId}:${activity.id}",
            sourceActivityId = activity.id,
            entityType = MemoryEntityType.PLAYLIST,
            entityId = rawPlaylistId,
            entityProvider = activity.provider,
            title = playlist?.name ?: activity.title,
            supportingText = buildString {
                append("Playlist")
                val owner = playlist?.owner ?: activity.subtitle
                if (!owner.isNullOrBlank() && owner != "Playlist") {
                    append(" by ")
                    append(owner)
                }
            },
            metaText = null,
            coverArtUrl = coverArtUrl,
            timestamp = activity.timestamp,
            scoreText = rated.averageScoreText(),
            scoreSupportingText = ratedSummaryText(rated.size, songs.size),
            footerText = buildCollectionFooter(
                songCount = playlist?.songCount ?: songs.size,
                durationSeconds = playlist?.durationSec,
            ),
            playbackSongs = songs,
            tracks = songs.mapIndexed { index, song ->
                MemoryTrack(
                    stableId = "playlist:${activity.entityId}:song:${song.id}",
                    title = song.title.orEmpty(),
                    artist = song.artist.orEmpty(),
                    durationSeconds = song.durationSec,
                    rating = ratings[song.id]?.rating?.takeIf { rating -> rating > 0f },
                ).withIndexFallback(index)
            },
        )
    }

    /**
     * album/song 笔记合并成 newest-first 的一条流。UI 只画前几条，但这里
     * 不截断 —— 「全部 N 条笔记」sheet 要列全量，截断是渲染侧的事。
     */
    private suspend fun loadAlbumWritings(
        albumId: MediaId,
        songs: List<Track>,
    ): List<MemoryWriting> {
        val albumNotes = repository.getAlbumNotesOnce(albumId).map { note ->
            MemoryWriting(
                kind = MemoryWriting.Kind.ALBUM_NOTE,
                text = note.content,
                writtenAt = note.updatedAt,
                noteId = note.id,
            )
        }
        val titlesByRawId = songs.associate { song -> song.id.rawId to song.title }
        val songNotes = repository.getSongNotesOnce(songs.map(Track::id)).map { note ->
            MemoryWriting(
                kind = MemoryWriting.Kind.SONG_NOTE,
                text = note.content,
                writtenAt = note.updatedAt,
                trackTitle = titlesByRawId[note.trackId] ?: note.title,
                positionMs = note.positionMs,
                trackId = note.trackId,
                noteId = note.id,
            )
        }
        return (albumNotes + songNotes)
            .filter { writing -> writing.text.isNotBlank() }
            .sortedByDescending(MemoryWriting::writtenAt)
    }

    private fun sourceRelativeCoverArtUrl(rawId: String): String? =
        repository.resolveCoverUrl(CoverRef.SourceRelative(rawId), size = 480)

    /**
     * Resolve a stored `coverArtId` (ActivityEvent / PlayHistory column).
     * The column holds storage-key strings: direct URL for Spotify, raw id
     * for Subsonic. [CoverRef.fromStorageKey] routes each into the right
     * variant so the active source's `resolveCoverUrl` picks the correct
     * branch.
     */
    private fun resolveStorageKeyCoverUrl(key: String): String? =
        repository.resolveCoverUrl(CoverRef.fromStorageKey(key), size = 480)

    private fun trackCoverArtUrl(track: Track): String? =
        repository.resolveCoverUrl(track.coverArt, size = 480)
            ?: track.albumId
                ?.takeIf { it.provider == MediaId.PROVIDER_SUBSONIC }
                ?.rawId
                ?.let(::sourceRelativeCoverArtUrl)
}

internal const val MEMORY_DECK_SIZE = 6

/**
 * This album memory with [userTitle] as its title — or, with null, back on Yoin's own (AI > motif > album
 * name, [resolveAlbumMemoryTitle]). Pure and idempotent: Yoin's title stays in [MemoryEntry.generatedMemoryTitle]
 * so a user title can be laid over it and taken off again in place. Only the title and its kind change:
 * Yoin's narration and the prose language stay as they were (the user's title never steers Yoin's prose).
 * An entry built before the generated fields existed counts its own AI / motif title as Yoin's.
 */
internal fun MemoryEntry.withUserMemoryTitle(userTitle: String?): MemoryEntry {
    if (entityType != MemoryEntityType.ALBUM) return this
    val yoin = yoinOwnTitle()
    val resolved = resolveAlbumMemoryTitle(
        userTitle = userTitle,
        aiTitle = yoin?.text?.takeIf { yoin.kind == MemoryTitleKind.AI },
        motif = yoin?.text?.takeIf { yoin.kind == MemoryTitleKind.MOTIF },
        albumName = title,
    )
    return copy(
        memoryTitle = resolved.text,
        memoryTitleKind = resolved.source.toTitleKind(),
        generatedMemoryTitle = yoin?.text,
        generatedMemoryTitleKind = yoin?.kind,
    )
}

/** Yoin's own title for a memory (AI or motif) and its kind. */
internal data class YoinMemoryTitle(val text: String, val kind: MemoryTitleKind)

/**
 * Yoin's own title under whatever the card shows: [MemoryEntry.generatedMemoryTitle], or — for an entry built
 * before that field existed — its own AI / motif title. Null when Yoin has none (the album name stands in).
 */
internal fun MemoryEntry.yoinOwnTitle(): YoinMemoryTitle? {
    val generated = generatedMemoryTitle?.takeIf(String::isNotBlank)
    val generatedKind = generatedMemoryTitleKind
    if (generated != null && generatedKind != null) return YoinMemoryTitle(generated, generatedKind)
    val own = memoryTitle?.takeIf(String::isNotBlank) ?: return null
    return when (memoryTitleKind) {
        MemoryTitleKind.AI, MemoryTitleKind.MOTIF -> YoinMemoryTitle(own, memoryTitleKind)
        MemoryTitleKind.USER, MemoryTitleKind.ALBUM -> null
    }
}

/** The user's title is on this card and Yoin has one of its own under it (the "Restore" gate). */
internal fun MemoryEntry.canRestoreGeneratedTitle(): Boolean =
    memoryTitleKind == MemoryTitleKind.USER && yoinOwnTitle() != null

internal fun AlbumMemoryTitleSource.toTitleKind(): MemoryTitleKind = when (this) {
    AlbumMemoryTitleSource.USER -> MemoryTitleKind.USER
    AlbumMemoryTitleSource.AI -> MemoryTitleKind.AI
    AlbumMemoryTitleSource.MOTIF -> MemoryTitleKind.MOTIF
    AlbumMemoryTitleSource.ALBUM -> MemoryTitleKind.ALBUM
}

/** Plays in Yoin and the first / latest play, from play_history only. */
internal data class MemoryPlayHistory(
    val plays: Int,
    val firstHeardAt: Long,
    val lastHeardAt: Long,
)

/** Null when the album was never played in Yoin: no footer, no Last heard, no plays in the copy. */
internal fun memoryPlayHistory(plays: Int, firstPlayedAt: Long?, lastPlayedAt: Long?): MemoryPlayHistory? {
    if (plays <= 0 || lastPlayedAt == null) return null
    val first = firstPlayedAt?.coerceAtMost(lastPlayedAt) ?: lastPlayedAt
    return MemoryPlayHistory(plays = plays, firstHeardAt = first, lastHeardAt = lastPlayedAt)
}

/** What MemoryVoice / MemoryExcerpt read, with dates in the coordinator's zone. */
internal fun memoryCopyInput(
    albumName: String,
    aiTitle: String?,
    review: MemoryWriting?,
    writings: List<MemoryWriting>,
    tracks: List<MemoryTrack>,
    ratedTracks: Int,
    totalTracks: Int,
    albumScore: Float?,
    history: MemoryPlayHistory?,
    zone: ZoneId,
): MemoryCopyInput {
    val copyTracks = tracks.map { track ->
        MemoryCopyTrack(number = track.number ?: 0, title = track.title, rating = track.rating)
    }
    val copyTrackById = HashMap<String, MemoryCopyTrack>()
    tracks.zip(copyTracks).forEach { (track, copy) ->
        track.trackId?.let { id -> copyTrackById.putIfAbsent(id, copy) }
    }
    val notes = writings
        .filter { writing -> writing.kind != MemoryWriting.Kind.REVIEW }
        .sortedBy(MemoryWriting::writtenAt)
        .map { writing ->
            val track = if (writing.kind == MemoryWriting.Kind.SONG_NOTE) {
                writing.trackId?.let(copyTrackById::get)
                    ?: MemoryCopyTrack(number = Int.MAX_VALUE, title = writing.trackTitle.orEmpty(), rating = null)
            } else {
                null
            }
            MemoryCopyNote(
                text = writing.text,
                writtenOn = MemoryDates.localDate(writing.writtenAt, zone),
                track = track,
                positionMs = writing.positionMs,
            )
        }
    return MemoryCopyInput(
        albumName = albumName,
        aiTitle = aiTitle,
        review = review?.text,
        reviewWrittenOn = review?.let { writing -> MemoryDates.localDate(writing.writtenAt, zone) },
        notes = notes,
        tracks = copyTracks,
        ratedTracks = ratedTracks,
        totalTracks = totalTracks,
        albumScore = albumScore,
        listening = history?.let {
            MemoryListening(
                plays = it.plays,
                firstHeard = MemoryDates.localDate(it.firstHeardAt, zone),
                lastHeard = MemoryDates.localDate(it.lastHeardAt, zone),
            )
        },
    )
}

/**
 * The diary's album note, heading the liner. An album has one album note
 * (owner, 2026-10-05), so only the latest legacy album_notes row shows.
 */
internal fun diaryAlbumNotes(writings: List<MemoryWriting>): List<MemoryWriting> = listOfNotNull(
    writings
        .filter { writing -> writing.kind == MemoryWriting.Kind.ALBUM_NOTE }
        .maxByOrNull(MemoryWriting::writtenAt),
)

/**
 * Track rows, option A (owner default): only tracks that are rated or noted,
 * in album order. Song notes group under their track by [MemoryWriting.trackId]:
 * anchored notes by position, unanchored ones after them, oldest first.
 */
internal fun diaryTracks(tracks: List<MemoryTrack>, writings: List<MemoryWriting>): List<MemoryDiaryTrack> {
    val notesByTrack = writings
        .filter { writing -> writing.kind == MemoryWriting.Kind.SONG_NOTE && writing.trackId != null }
        .groupBy { writing -> writing.trackId }
    val placed = HashSet<String>()
    return tracks.mapNotNull { track ->
        // a duplicate id (the same song listed twice) keeps its notes under the first row only
        val notes = track.trackId
            ?.takeIf(placed::add)
            ?.let(notesByTrack::get)
            .orEmpty()
            .sortedWith(
                compareBy<MemoryWriting> { writing -> writing.positionMs ?: Long.MAX_VALUE }
                    .thenBy(MemoryWriting::writtenAt),
            )
        if (track.rating == null && notes.isEmpty()) null else MemoryDiaryTrack(track = track, notes = notes)
    }
}

/** The card's score with the shared Memories rounding ([MemoryScores]: 9.95 → "10.0"); "N/A" when unrated. */
internal fun Float?.formatScore(): String = if (this != null && this > 0f) {
    MemoryScores.text(this)
} else {
    "N/A"
}

internal fun List<LocalRating>.averageScoreText(): String =
    map(LocalRating::rating)
        .filter { rating -> rating > 0f }
        .takeIf(List<Float>::isNotEmpty)
        ?.average()
        ?.let(MemoryScores::text)
        ?: "N/A"

internal fun ratedSummaryText(
    ratedCount: Int,
    totalCount: Int,
): String? = if (totalCount > 0) {
    val noun = if (totalCount == 1) "song" else "songs"
    "Based on $ratedCount/$totalCount $noun"
} else {
    null
}

internal fun buildCollectionFooter(
    songCount: Int?,
    durationSeconds: Int?,
): String? {
    val parts = mutableListOf<String>()
    songCount?.takeIf { count -> count > 0 }?.let { count -> parts += "$count Songs" }
    durationSeconds?.takeIf { duration -> duration > 0 }?.let { duration ->
        parts += formatDurationSeconds(duration)
    }
    return parts.takeIf(List<String>::isNotEmpty)?.joinToString(", ")
}

private fun buildAlbumReasonChips(candidate: AlbumMemoryCandidate): List<String> {
    val chips = mutableListOf<String>()
    if (candidate.hasAlbumReview) {
        chips += "Album review"
    }
    if (candidate.totalTracks > 0 && candidate.ratedTrackCount > 0) {
        chips += "${candidate.ratedTrackCount}/${candidate.totalTracks} songs rated"
    }
    if (candidate.noteCount > 0) {
        val noun = if (candidate.noteCount == 1) "note" else "notes"
        chips += "${candidate.noteCount} $noun"
    }
    if (candidate.askAiCount > 0) {
        chips += "Ask AI references"
    }
    if (candidate.lastPlayedAt != null) {
        chips += "Recently revisited"
    }
    if (candidate.neoDbSynced) {
        chips += "Synced to NeoDB"
    } else if (candidate.hasAlbumReview && candidate.albumRating != null) {
        chips += "NeoDB ready"
    }
    return chips
}

internal fun formatDurationSeconds(seconds: Int): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return when {
        hours > 0 -> "%d H %02d Min %02d Sec".format(hours, minutes, secs)
        minutes > 0 -> "%d Min %02d Sec".format(minutes, secs)
        else -> "%d Sec".format(secs)
    }
}

private fun MemoryTrack.withIndexFallback(index: Int): MemoryTrack = copy(
    stableId = "$stableId:$index",
)
