package com.gpo.yoin.ui.memories

import com.gpo.yoin.data.local.ActivityEntityType
import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.data.local.LocalRating
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
    private var candidateActivities: List<ActivityEvent>? = null

    suspend fun ensureDeck(): List<MemoryEntry> {
        val candidates = ensureCandidates()
        if (candidates.isEmpty()) {
            sessionStore.clearMemories()
            return emptyList()
        }

        val session = sessionStore.state.value.memories
        val desiredActivityIds = if (session.currentDeckActivityIds.isNotEmpty()) {
            session.currentDeckActivityIds
        } else {
            sampleDeckActivities(
                candidates = candidates,
                excludedActivityIds = emptySet(),
            ).map(ActivityEvent::id)
        }

        val memories = resolveDeck(desiredActivityIds)
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
            sampleDeckActivities(
                candidates = candidates,
                excludedActivityIds = sessionStore.state.value.memories.currentDeckActivityIds.toSet(),
            ).map(ActivityEvent::id),
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
        candidateActivities = null
        resolvedMemoryCache.clear()
    }

    private suspend fun ensureCandidates(): List<ActivityEvent> {
        val existing = candidateActivities
        if (existing != null) return existing
        return repository.getRecentMemoryActivities(limit = 48).also { loaded ->
            candidateActivities = loaded
        }
    }

    private suspend fun resolveDeck(activityIds: List<Long>): List<MemoryEntry> = coroutineScope {
        activityIds
            .mapNotNull(::findActivityById)
            .map { activity ->
                async { resolveMemoryCached(activity) }
            }
            .awaitAll()
            .filterNotNull()
    }

    private fun findActivityById(activityId: Long): ActivityEvent? =
        candidateActivities?.firstOrNull { it.id == activityId }

    private fun sampleDeckActivities(
        candidates: List<ActivityEvent>,
        excludedActivityIds: Set<Long>,
    ): List<ActivityEvent> {
        val prioritized = candidates
            .filterNot { activity -> activity.id in excludedActivityIds }
            .shuffled(random)
        val fallback = candidates
            .filter { activity -> activity.id in excludedActivityIds }
            .shuffled(random)

        return (prioritized + fallback)
            .take(MEMORY_DECK_SIZE)
    }

    private suspend fun resolveMemoryCached(activity: ActivityEvent): MemoryEntry? {
        resolvedMemoryCache[activity.id]?.let { return it }
        val memory = resolveMemory(activity)
        resolvedMemoryCache[activity.id] = memory
        return memory
    }

    private suspend fun resolveMemory(activity: ActivityEvent): MemoryEntry? = when (activity.entityType) {
        ActivityEntityType.ALBUM.name -> resolveAlbumMemory(activity)
        ActivityEntityType.PLAYLIST.name -> resolvePlaylistMemory(activity)
        ActivityEntityType.SONG.name -> resolveSongMemory(activity)
        else -> null
    }

    private suspend fun resolveSongMemory(activity: ActivityEvent): MemoryEntry {
        val provider = activity.provider
        val rawSongId = activity.songId ?: activity.entityId
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

    private suspend fun resolveAlbumMemory(activity: ActivityEvent): MemoryEntry {
        val albumId = MediaId(activity.provider, activity.entityId)
        val album = runCatching { repository.getAlbum(albumId) }.getOrNull()
        val songs = album?.tracks.orEmpty()
        val ratings = repository.getRatings(songs.map(Track::id))
        val rated = songs.mapNotNull { song ->
            ratings[song.id]?.takeIf { localRating -> localRating.rating > 0f }
        }

        return MemoryEntry(
            stableId = "album:${activity.entityId}:${activity.id}",
            sourceActivityId = activity.id,
            entityType = MemoryEntityType.ALBUM,
            entityId = activity.entityId,
            title = album?.name ?: activity.title,
            supportingText = buildString {
                if (album?.year != null) {
                    append(album.year)
                    append("  ·  ")
                }
                append("Album")
                val artistName = album?.artist ?: activity.subtitle
                if (!artistName.isNullOrBlank()) {
                    append(" by ")
                    append(artistName)
                }
            },
            metaText = null,
            coverArtUrl = album?.coverArt?.let { repository.resolveCoverUrl(it, size = 480) }
                ?: activity.coverArtId?.let(::resolveStorageKeyCoverUrl)
                ?: album?.id?.takeIf { it.provider == MediaId.PROVIDER_SUBSONIC }
                    ?.rawId?.let(::sourceRelativeCoverArtUrl),
            timestamp = activity.timestamp,
            scoreText = rated.averageScoreText(),
            scoreSupportingText = ratedSummaryText(rated.size, songs.size),
            footerText = buildCollectionFooter(
                songCount = album?.songCount ?: songs.size,
                durationSeconds = album?.durationSec,
            ),
            playbackSongs = songs,
            tracks = songs.mapIndexed { index, song ->
                MemoryTrack(
                    stableId = "album:${activity.entityId}:song:${song.id}",
                    title = song.title.orEmpty(),
                    artist = song.artist.orEmpty(),
                    durationSeconds = song.durationSec,
                    rating = ratings[song.id]?.rating?.takeIf { rating -> rating > 0f },
                ).withIndexFallback(index)
            },
        )
    }

    private suspend fun resolvePlaylistMemory(activity: ActivityEvent): MemoryEntry {
        val playlistId = MediaId(activity.provider, activity.entityId)
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
            entityId = activity.entityId,
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
