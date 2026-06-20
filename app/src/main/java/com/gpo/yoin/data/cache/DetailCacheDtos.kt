package com.gpo.yoin.data.cache

import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.ArtistDetail
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.Track
import kotlinx.serialization.Serializable

/**
 * JSON-serializable mirrors of the detail domain models, used only by
 * [DetailCacheStore]. `MediaId` is flattened to its `provider:rawId` string and
 * `CoverRef` to its storage key (both lossless — see those types). Kept separate
 * from the domain models so the on-disk format is decoupled; `ignoreUnknownKeys`
 * + default values make it forward-compatible across model changes.
 */
@Serializable
internal data class TrackDto(
    val id: String,
    val title: String? = null,
    val artist: String? = null,
    val artistId: String? = null,
    val album: String? = null,
    val albumId: String? = null,
    val coverArt: String? = null,
    val durationSec: Int? = null,
    val trackNumber: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val userRating: Int? = null,
    val isStarred: Boolean = false,
    val extras: Map<String, String> = emptyMap(),
    val addedAt: String? = null,
) {
    fun toDomain(): Track = Track(
        id = MediaId.parse(id),
        title = title,
        artist = artist,
        artistId = MediaId.parseOrNull(artistId),
        album = album,
        albumId = MediaId.parseOrNull(albumId),
        coverArt = CoverRef.fromStorageKey(coverArt),
        durationSec = durationSec,
        trackNumber = trackNumber,
        year = year,
        genre = genre,
        userRating = userRating,
        isStarred = isStarred,
        extras = extras,
        addedAt = addedAt,
    )

    companion object {
        fun from(t: Track): TrackDto = TrackDto(
            id = t.id.toString(),
            title = t.title,
            artist = t.artist,
            artistId = t.artistId?.toString(),
            album = t.album,
            albumId = t.albumId?.toString(),
            coverArt = CoverRef.toStorageKey(t.coverArt),
            durationSec = t.durationSec,
            trackNumber = t.trackNumber,
            year = t.year,
            genre = t.genre,
            userRating = t.userRating,
            isStarred = t.isStarred,
            extras = t.extras,
            addedAt = t.addedAt,
        )
    }
}

@Serializable
internal data class AlbumDto(
    val id: String,
    val name: String,
    val artist: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int? = null,
    val durationSec: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val isStarred: Boolean = false,
    val tracks: List<TrackDto> = emptyList(),
    val addedAt: String? = null,
) {
    fun toDomain(): Album = Album(
        id = MediaId.parse(id),
        name = name,
        artist = artist,
        artistId = MediaId.parseOrNull(artistId),
        coverArt = CoverRef.fromStorageKey(coverArt),
        songCount = songCount,
        durationSec = durationSec,
        year = year,
        genre = genre,
        isStarred = isStarred,
        tracks = tracks.map(TrackDto::toDomain),
        addedAt = addedAt,
    )

    companion object {
        fun from(a: Album): AlbumDto = AlbumDto(
            id = a.id.toString(),
            name = a.name,
            artist = a.artist,
            artistId = a.artistId?.toString(),
            coverArt = CoverRef.toStorageKey(a.coverArt),
            songCount = a.songCount,
            durationSec = a.durationSec,
            year = a.year,
            genre = a.genre,
            isStarred = a.isStarred,
            tracks = a.tracks.map(TrackDto::from),
            addedAt = a.addedAt,
        )
    }
}

@Serializable
internal data class ArtistDetailDto(
    val id: String,
    val name: String,
    val albumCount: Int? = null,
    val coverArt: String? = null,
    val isStarred: Boolean = false,
    val albums: List<AlbumDto> = emptyList(),
) {
    fun toDomain(): ArtistDetail = ArtistDetail(
        id = MediaId.parse(id),
        name = name,
        albumCount = albumCount,
        coverArt = CoverRef.fromStorageKey(coverArt),
        isStarred = isStarred,
        albums = albums.map(AlbumDto::toDomain),
    )

    companion object {
        fun from(a: ArtistDetail): ArtistDetailDto = ArtistDetailDto(
            id = a.id.toString(),
            name = a.name,
            albumCount = a.albumCount,
            coverArt = CoverRef.toStorageKey(a.coverArt),
            isStarred = a.isStarred,
            albums = a.albums.map(AlbumDto::from),
        )
    }
}

@Serializable
internal data class PlaylistDto(
    val id: String,
    val name: String,
    val owner: String? = null,
    val coverArt: String? = null,
    val songCount: Int? = null,
    val durationSec: Int? = null,
    val tracks: List<TrackDto> = emptyList(),
    val canWrite: Boolean = false,
    val snapshotId: String? = null,
) {
    fun toDomain(): Playlist = Playlist(
        id = MediaId.parse(id),
        name = name,
        owner = owner,
        coverArt = CoverRef.fromStorageKey(coverArt),
        songCount = songCount,
        durationSec = durationSec,
        tracks = tracks.map(TrackDto::toDomain),
        canWrite = canWrite,
        snapshotId = snapshotId,
    )

    companion object {
        fun from(p: Playlist): PlaylistDto = PlaylistDto(
            id = p.id.toString(),
            name = p.name,
            owner = p.owner,
            coverArt = CoverRef.toStorageKey(p.coverArt),
            songCount = p.songCount,
            durationSec = p.durationSec,
            tracks = p.tracks.map(TrackDto::from),
            canWrite = p.canWrite,
            snapshotId = p.snapshotId,
        )
    }
}
