package com.gpo.yoin.data.home

import com.gpo.yoin.data.cache.AlbumDto
import com.gpo.yoin.data.cache.PlaylistDto
import com.gpo.yoin.data.cache.TrackDto
import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.Track
import kotlinx.serialization.Serializable

/*
 * Home's feed as its snapshot keeps it ([HomeSnapshotStore]). Everything a
 * cold start needs to draw the feed's text and layout, and every cover as its
 * CoverRef storage key — never a resolved URL: a Subsonic cover URL carries
 * the account's credentials (u/t/s), and the key resolves again through the
 * account's own source. Ids travel as `provider:rawId`, enums by name; app
 * copy is rebuilt from its fields, never stored as a resource id (those move
 * between builds). Additive changes stay readable (unknown keys are ignored,
 * missing ones take their defaults); anything else bumps
 * [HomeSnapshotStore.FORMAT_VERSION].
 */

/** One snapshot file: whose feed it is (the account and its provider), when it was written, and the feed. */
@Serializable
internal data class HomeSnapshotFileDto(
    val version: Int,
    val profileId: String,
    val provider: String,
    val savedAt: Long,
    val feed: HomeFeedDto
)

/** The feed: HomeUiState.Content's fields, covers as keys. */
@Serializable
internal data class HomeFeedDto(
    val activities: List<HomeActivityDto> = emptyList(),
    val activitiesFromRemote: Boolean = false,
    val heroFootnote: String? = null,
    val heroYear: Int? = null,
    val heroSongCount: Int? = null,
    val heroMinutes: Int? = null,
    val widgetGrid: List<HomeCardDto> = emptyList(),
    val recentlyAddedTracks: List<TrackDto> = emptyList(),
    val recentlyAddedAlbums: List<AlbumDto> = emptyList(),
    val memoryPill: HomeMemoryPillDto? = null,
    val rediscover: List<HomeRediscoverDto> = emptyList(),
    val recentlyPlayed: List<AlbumDto> = emptyList(),
    val playlists: List<PlaylistDto> = emptyList()
)

/** An activity-log row as the feed shows it ([ActivityEvent]; its cover id is already a storage key). */
@Serializable
internal data class HomeActivityDto(
    val id: Long = 0,
    val entityType: String,
    val actionType: String,
    val entityId: String,
    val profileId: String = "",
    val provider: String,
    val title: String,
    val subtitle: String = "",
    val coverArtId: String? = null,
    val songId: String? = null,
    val albumId: String? = null,
    val artistId: String? = null,
    val timestamp: Long
) {
    fun toDomain(): ActivityEvent = ActivityEvent(
        id = id,
        entityType = entityType,
        actionType = actionType,
        entityId = entityId,
        profileId = profileId,
        provider = provider,
        title = title,
        subtitle = subtitle,
        coverArtId = credentialFreeCoverKey(coverArtId),
        songId = songId,
        albumId = albumId,
        artistId = artistId,
        timestamp = timestamp
    )

    companion object {
        fun from(event: ActivityEvent): HomeActivityDto = HomeActivityDto(
            id = event.id,
            entityType = event.entityType,
            actionType = event.actionType,
            entityId = event.entityId,
            profileId = event.profileId,
            provider = event.provider,
            title = event.title,
            subtitle = event.subtitle,
            coverArtId = credentialFreeCoverKey(event.coverArtId),
            songId = event.songId,
            albumId = event.albumId,
            artistId = event.artistId,
            timestamp = event.timestamp
        )
    }
}

/**
 * A Jump Back In card. [target] is where a tap leads; [ratingBasisTracks] is
 * the "Based on rated/total tracks" line's two counts (app copy, rebuilt).
 */
@Serializable
internal data class HomeCardDto(
    val stableId: String,
    val entityType: String,
    val title: String,
    val subtitle: String = "",
    val subtitleText: String? = null,
    val coverKey: String? = null,
    val ratingText: String? = null,
    val ratingBasis: String? = null,
    val ratingBasisTracks: List<Int>? = null,
    val ratingBasisDateMillis: Long? = null,
    val ratingUnavailable: Boolean = false,
    val comment: String? = null,
    val commentIsHeadline: Boolean = false,
    val commentSerif: Boolean = true,
    val expanded: Boolean = false,
    val target: HomeCardTargetDto
)

/** A card's tap: [kind] is album | playlist | song | memory, with its [id], [song] or [sessionId]. */
@Serializable
internal data class HomeCardTargetDto(
    val kind: String,
    val id: String? = null,
    val song: TrackDto? = null,
    val sessionId: Long? = null
) {
    companion object {
        const val ALBUM = "album"
        const val PLAYLIST = "playlist"
        const val SONG = "song"
        const val MEMORY = "memory"
    }
}

/** A Rediscover card (an album, or a song with [song] set). */
@Serializable
internal data class HomeRediscoverDto(
    val albumId: String,
    val albumName: String,
    val artistName: String? = null,
    val coverKey: String? = null,
    val score: Float? = null,
    val scoreText: String? = null,
    val scoreKind: String,
    val lastPlayedAt: Long,
    val firstPlayedAt: Long? = null,
    val playCount: Int = 0,
    val hasReview: Boolean = false,
    val noteCount: Int = 0,
    val ratedTrackCount: Int = 0,
    val song: TrackDto? = null,
    val noteSnippet: String? = null
)

/** The header's Memories pill. */
@Serializable
internal data class HomeMemoryPillDto(
    val latest: Latest? = null,
    val noteCount: Int = 0,
    val scope: String = "",
    val newsKey: String = ""
) {
    @Serializable
    data class Latest(
        val sessionId: Long,
        val albumId: String,
        val albumName: String,
        val artistName: String? = null,
        val coverKey: String? = null,
        val scoreKind: String,
        val scoreText: String? = null,
        val writtenAtMillis: Long? = null,
        val memoryTitle: String? = null
    )
}

/** [track] as a snapshot keeps it: its cover key checked ([credentialFreeCoverKey]). */
internal fun snapshotTrack(track: Track): TrackDto =
    TrackDto.from(track).let { dto -> dto.copy(coverArt = credentialFreeCoverKey(dto.coverArt)) }

/** [album] without its track list (no shelf reads it), its cover key checked. */
internal fun snapshotAlbum(album: Album): AlbumDto = AlbumDto.from(album.copy(tracks = emptyList())).let { dto ->
    dto.copy(coverArt = credentialFreeCoverKey(dto.coverArt))
}

/** [playlist] without its track list (no shelf reads it), its cover key checked. */
internal fun snapshotPlaylist(playlist: Playlist): PlaylistDto =
    PlaylistDto.from(playlist.copy(tracks = emptyList())).let { dto ->
        dto.copy(coverArt = credentialFreeCoverKey(dto.coverArt))
    }

/**
 * [key] when a snapshot may keep it: a source-relative cover id (resolved
 * again through the account's source), or a URL that carries no credentials.
 * Covers are stored as keys, so a URL with an auth parameter — a resolved
 * Subsonic cover (u, t, s, p, apiKey) that found its way into a key — is
 * never written; its cover simply shows the type icon until the fresh feed.
 */
internal fun credentialFreeCoverKey(key: String?): String? {
    if (key.isNullOrBlank()) return null
    val isUrl = key.startsWith("http://", ignoreCase = true) || key.startsWith("https://", ignoreCase = true)
    if (!isUrl) return key
    val query = key.substringAfter('?', missingDelimiterValue = "").substringBefore('#')
    if (query.isEmpty()) return key
    val names = query.split('&').map { parameter -> parameter.substringBefore('=').lowercase() }
    return key.takeIf { names.none { name -> name in CredentialParameters } }
}

private val CredentialParameters = setOf(
    "u",
    "p",
    "t",
    "s",
    "apikey",
    "api_key",
    "token",
    "access_token",
    "password"
)
