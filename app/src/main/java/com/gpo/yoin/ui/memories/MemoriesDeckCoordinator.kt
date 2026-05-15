package com.gpo.yoin.ui.memories

import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.data.local.LocalRating
import com.gpo.yoin.data.memory.AlbumMemoryCandidate
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.ui.experience.ExperienceSessionStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import java.util.Locale
import kotlin.random.Random

class MemoriesDeckCoordinator(
    private val repository: YoinRepository,
    private val sessionStore: ExperienceSessionStore,
    randomSeed: Long = System.currentTimeMillis(),
) {
    private val random = Random(randomSeed)
    private val resolvedMemoryCache = mutableMapOf<Long, MemoryEntry?>()
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

    fun invalidate() {
        candidateAlbums = null
        resolvedMemoryCache.clear()
    }

    private suspend fun ensureCandidates(): List<AlbumMemoryCandidate> {
        val existing = candidateAlbums
        if (existing != null) return existing
        return repository.getAlbumMemoryCandidates(limit = 48).also { loaded ->
            candidateAlbums = loaded
        }
    }

    private suspend fun resolveDeck(activityIds: List<Long>): List<MemoryEntry> = coroutineScope {
        activityIds
            .mapNotNull(::findCandidateById)
            .map { candidate ->
                async { resolveMemoryCached(candidate) }
            }
            .awaitAll()
            .filterNotNull()
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
        val memory = resolveAlbumMemory(candidate)
        resolvedMemoryCache[candidate.sessionId] = memory
        return memory
    }

    private fun rawEntityId(raw: String): String =
        if (':' in raw) raw.substringAfter(':') else raw

    private suspend fun resolveSongMemory(activity: ActivityEvent): MemoryEntry {
        val provider = activity.provider
        val rawSongId = rawEntityId(activity.songId ?: activity.entityId)
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
        val rawAlbumId = rawEntityId(candidate.albumId)
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
        // 「余音 Gemini 文案」：没专辑元数据（冷加载失败）时跳过，避免给
        // Gemini 送空名字；有 API key 时命中缓存或后台生成。失败静默降级。
        val narrative = album?.let { resolved ->
            runCatching {
                repository.getOrGenerateAlbumMemoryCopy(
                    album = resolved,
                    averageRating = averageRating,
                    ratedSongCount = rated.size,
                    totalSongCount = songs.size,
                )
            }.getOrNull()
        }
        val reasonChips = buildAlbumReasonChips(candidate)

        return MemoryEntry(
            stableId = "album:${candidate.profileId}:${candidate.provider}:$rawAlbumId",
            sourceActivityId = candidate.sessionId,
            entityType = MemoryEntityType.ALBUM,
            entityId = rawAlbumId,
            entityProvider = candidate.provider,
            title = album?.name ?: candidate.albumName,
            supportingText = buildString {
                val year = album?.year ?: candidate.year
                if (year != null) {
                    append(year)
                    append("  ·  ")
                }
                append("Album")
                val artistName = album?.artist ?: candidate.artistName
                if (!artistName.isNullOrBlank()) {
                    append(" by ")
                    append(artistName)
                }
            },
            metaText = null,
            coverArtUrl = album?.coverArt?.let { repository.resolveCoverUrl(it, size = 480) }
                ?: candidate.coverArtUrl
                ?: album?.id?.takeIf { it.provider == MediaId.PROVIDER_SUBSONIC }
                    ?.rawId?.let(::sourceRelativeCoverArtUrl),
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
            reasonChips = reasonChips,
            narrativeCopy = narrative ?: buildAlbumFallbackCopy(candidate, reasonChips),
            playbackSongs = songs,
            tracks = songs.mapIndexed { index, song ->
                MemoryTrack(
                    stableId = "album:$rawAlbumId:song:${song.id}",
                    title = song.title.orEmpty(),
                    artist = song.artist.orEmpty(),
                    durationSeconds = song.durationSec,
                    rating = ratings[song.id]?.rating?.takeIf { rating -> rating > 0f },
                ).withIndexFallback(index)
            },
        )
    }

    private suspend fun resolvePlaylistMemory(activity: ActivityEvent): MemoryEntry {
        val rawPlaylistId = rawEntityId(activity.entityId)
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

internal fun Float?.formatScore(): String = if (this != null && this > 0f) {
    String.format(Locale.US, "%.1f", this)
} else {
    "N/A"
}

internal fun List<LocalRating>.averageScoreText(): String =
    map(LocalRating::rating)
        .filter { rating -> rating > 0f }
        .takeIf(List<Float>::isNotEmpty)
        ?.average()
        ?.let { average -> String.format(Locale.US, "%.1f", average) }
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

private fun buildAlbumFallbackCopy(
    candidate: AlbumMemoryCandidate,
    reasonChips: List<String>,
): String = when {
    candidate.hasAlbumReview && candidate.noteCount > 0 ->
        "A reviewed album with saved notes, ready to revisit."
    candidate.hasAlbumReview ->
        "A reviewed album worth coming back to."
    candidate.noteCount >= 2 ->
        "Notes around this album are starting to form a memory."
    candidate.ratingCoverage >= 0.6f ->
        "You have rated most of this album; it is ready to revisit."
    "Recently revisited" in reasonChips ->
        "You recently came back to this album."
    candidate.askAiCount > 0 ->
        "Ask AI context is waiting beside this album memory."
    else ->
        "A small album memory from your listening history."
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
