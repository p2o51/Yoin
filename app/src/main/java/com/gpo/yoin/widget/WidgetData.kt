package com.gpo.yoin.widget

import android.content.Context
import com.gpo.yoin.AppContainer
import com.gpo.yoin.R
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.data.local.ActivityActionType
import com.gpo.yoin.data.local.ActivityEntityType
import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.data.memory.AlbumMemoryCandidate
import com.gpo.yoin.data.memory.AlbumMemoryTitleSource
import com.gpo.yoin.data.memory.memoryEligible
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/** What a widget item points at; decides the backdrop shape and the image shape (artist = circle). */
enum class WidgetEntity { ALBUM, PLAYLIST, ARTIST }

/** One "keep listening" item for the Play widget, newest first. */
data class PlayItem(
    val entity: WidgetEntity,
    val id: MediaId,
    val title: String,
    val subtitle: String,
    val coverUrl: String?,
)

/** One album memory for the Memory widget, most recently heard first. */
data class MemoryItem(
    val sessionId: Long,
    val albumId: MediaId,
    val albumName: String,
    val artistName: String?,
    val coverUrl: String?,
    /** Album rating, else the track average; null = unrated (no score anywhere). */
    val score: Float?,
    /** Last real play in Yoin (visits don't count); null = never played here. */
    val lastHeardAt: Long?,
    /** The generated (or the user's own) title, when there is one. */
    val generatedTitle: String?,
)

internal val Context.yoinContainer: AppContainer
    get() = (applicationContext as YoinApplication).container

/**
 * A cold widget process has no active source yet ([com.gpo.yoin.data.profile.ProfileManager] fills it
 * asynchronously); every repository read returns empty until it lands.
 */
private suspend fun AppContainer.awaitActiveSource(): Boolean = repository.awaitActiveSource(SOURCE_TIMEOUT_MS) != null

private const val SOURCE_TIMEOUT_MS = 8_000L

internal object WidgetData {

    suspend fun playItems(context: Context, limit: Int = 8): List<PlayItem> {
        val container = context.yoinContainer
        if (!container.awaitActiveSource()) return emptyList()
        val repository = container.repository
        val events = runCatching { repository.getRecentActivities(limit * 3).first() }.getOrDefault(emptyList())
        // Real plays lead (that is what "keep listening" resumes); visits only fill the gaps.
        val ordered = events.filter { it.actionType == ActivityActionType.PLAYED.name } +
            events.filter { it.actionType != ActivityActionType.PLAYED.name }
        return ordered
            .mapNotNull { it.toPlayItem(container) }
            .distinctBy { it.id }
            .take(limit)
    }

    private fun ActivityEvent.toPlayItem(container: AppContainer): PlayItem? {
        val entity = when (entityType) {
            ActivityEntityType.ALBUM.name -> WidgetEntity.ALBUM
            ActivityEntityType.PLAYLIST.name -> WidgetEntity.PLAYLIST
            ActivityEntityType.ARTIST.name -> WidgetEntity.ARTIST
            else -> return null
        }
        val rawId = MediaId.storedRawId(provider, entityId).takeIf { it.isNotEmpty() } ?: return null
        val cover = coverArtId?.let { key ->
            container.repository.resolveCoverUrl(CoverRef.fromStorageKey(key), COVER_REQUEST_PX)
        }
        return PlayItem(
            entity = entity,
            id = MediaId(provider, rawId),
            title = title,
            subtitle = subtitle,
            coverUrl = cover,
        )
    }

    suspend fun memoryItems(context: Context, limit: Int = 8): List<MemoryItem> {
        val container = context.yoinContainer
        if (!container.awaitActiveSource()) return emptyList()
        val candidates = runCatching {
            container.repository.getAlbumMemoryCandidates(limit = 48, includeIneligible = true).memoryEligible(48)
        }.getOrDefault(emptyList())
        return candidates
            .sortedByDescending { it.lastPlayedFromHistoryAt ?: it.lastPlayedAt ?: it.firstPlayedAt ?: 0L }
            .take(limit)
            .map { it.toMemoryItem(container) }
    }

    private suspend fun AlbumMemoryCandidate.toMemoryItem(container: AppContainer): MemoryItem {
        val title = runCatching { container.albumMemoryTitleResolver.resolve(this) }.getOrNull()
            ?.takeIf { it.source != AlbumMemoryTitleSource.ALBUM }
            ?.text
        return MemoryItem(
            sessionId = sessionId,
            albumId = MediaId(provider, MediaId.storedRawId(provider, albumId)),
            albumName = albumName,
            artistName = artistName,
            coverUrl = coverArtUrl,
            score = albumRating?.takeIf { it > 0f } ?: averageSongRating?.takeIf { it > 0f },
            lastHeardAt = lastPlayedFromHistoryAt,
            generatedTitle = title,
        )
    }

    private const val COVER_REQUEST_PX = 300
}

/** "9.2" — one decimal, half up, the same reading as the Memories seal. */
internal fun Float.widgetScoreText(): String =
    String.format(java.util.Locale.US, "%.1f", (Math.round(this * 10.0) / 10.0))

/** "3 Months Ago", "Today" — how long since you last heard it. Null when it was never played in Yoin. */
internal fun Context.lastHeardText(lastHeardAt: Long?, now: Long = System.currentTimeMillis()): String? {
    lastHeardAt ?: return null
    val days = TimeUnit.MILLISECONDS.toDays((now - lastHeardAt).coerceAtLeast(0))
    val res = resources
    return when {
        days < 1 -> res.getString(R.string.widget_last_heard_today)
        days < 31 -> res.getQuantityString(R.plurals.widget_last_heard_days, days.toInt(), days.toInt())
        days < 365 -> (days / 30).toInt().coerceAtLeast(1).let { res.getQuantityString(R.plurals.widget_last_heard_months, it, it) }
        else -> (days / 365).toInt().let { res.getQuantityString(R.plurals.widget_last_heard_years, it, it) }
    }
}
