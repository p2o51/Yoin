package com.gpo.yoin.shared.data.model

import kotlinx.serialization.Serializable

/*
 * Neutral, provider-agnostic models for the iOS app. They mirror the Android
 * app's data.model (Track / Album / Artist / Playlist / Lyrics) but stay
 * Swift-friendly: plain final classes, List, String, Int, Long, Boolean and
 * nullable boxes only — no sealed generics, no Result, no Flow.
 *
 * Optional numbers are `Int?` / `Long?`, which Swift sees as `KotlinInt?` /
 * `KotlinLong?` (`.intValue` / `.int64Value`).
 */

/** A remote id namespaced by provider, e.g. `MediaRef("subsonic", "al-1")`. Android: `MediaId`. */
@Serializable
data class MediaRef(
    val provider: String,
    val rawId: String,
) {
    /** `"<provider>:<rawId>"`, stable and unique across providers. */
    val key: String get() = "$provider:$rawId"

    companion object {
        const val PROVIDER_SUBSONIC: String = "subsonic"
    }
}

@Serializable
data class SongInfo(
    val ref: MediaRef,
    /** Raw Subsonic id (same as [ref].rawId). */
    val id: String,
    val title: String?,
    val artist: String?,
    val artistId: String?,
    val album: String?,
    val albumId: String?,
    val track: Int?,
    val disc: Int?,
    val durationSec: Int?,
    /** Pass to `SubsonicClient.coverArtUrl`. Null when the server sent none. */
    val coverArtId: String?,
    val year: Int?,
    val genre: String?,
    val starred: Boolean,
    /** ISO-8601 time the user starred it, as the server sent it. */
    val starredAt: String?,
    /** Raw server rating, 1–5; null when unrated. */
    val userRating: Int?,
    val suffix: String?,
    val contentType: String?,
    val bitRate: Int?,
    val size: Long?,
    val path: String?,
    val playCount: Int?,
    /** OpenSubsonic (Navidrome) `played`: ISO-8601 time of the user's last play, as the server sent it; null when absent. */
    val played: String?,
    /** Every credited artist when the server names more than one (OpenSubsonic `artists`); else empty. */
    val artists: List<SongArtist>,
) {
    /** The constructor from before [artists] existed (Swift: the init with `played:` and no `artists:`). */
    constructor(
        ref: MediaRef, id: String, title: String?, artist: String?, artistId: String?, album: String?,
        albumId: String?, track: Int?, disc: Int?, durationSec: Int?, coverArtId: String?, year: Int?,
        genre: String?, starred: Boolean, starredAt: String?, userRating: Int?, suffix: String?,
        contentType: String?, bitRate: Int?, size: Long?, path: String?, playCount: Int?, played: String?,
    ) : this(
        ref, id, title, artist, artistId, album, albumId, track, disc, durationSec, coverArtId, year,
        genre, starred, starredAt, userRating, suffix, contentType, bitRate, size, path, playCount, played,
        emptyList(),
    )

    /** The constructor from before [played] existed (Swift: the init without `played:`). */
    constructor(
        ref: MediaRef, id: String, title: String?, artist: String?, artistId: String?, album: String?,
        albumId: String?, track: Int?, disc: Int?, durationSec: Int?, coverArtId: String?, year: Int?,
        genre: String?, starred: Boolean, starredAt: String?, userRating: Int?, suffix: String?,
        contentType: String?, bitRate: Int?, size: Long?, path: String?, playCount: Int?,
    ) : this(
        ref, id, title, artist, artistId, album, albumId, track, disc, durationSec, coverArtId, year,
        genre, starred, starredAt, userRating, suffix, contentType, bitRate, size, path, playCount, null,
        emptyList(),
    )
}

/** One credited artist of a song; [id] is null where the server names none. */
@Serializable
data class SongArtist(
    val name: String,
    val id: String?,
)

@Serializable
data class AlbumInfo(
    val ref: MediaRef,
    val id: String,
    val name: String,
    val artist: String?,
    val artistId: String?,
    val year: Int?,
    val songCount: Int?,
    val durationSec: Int?,
    /** `coverArt ?: id`, like Android's mapper: Subsonic serves album art by album id. */
    val coverArtId: String,
    val starred: Boolean,
    val starredAt: String?,
    val userRating: Int?,
    val playCount: Int?,
    /** ISO-8601 time the album came onto the server (Library's "Recently added"). */
    val created: String?,
    val genre: String?,
    /** OpenSubsonic `releaseTypes` → "album" / "ep" / "single" / "compilation"; null when absent or unknown. */
    val releaseType: String?,
    /** Filled by `getAlbum`; empty in album lists. */
    val songs: List<SongInfo>,
    /** OpenSubsonic (Navidrome) `played`: ISO-8601 time of the user's last play of the album; null when absent. */
    val played: String?,
    /**
     * Which version of the release this is, as the server names it (OpenSubsonic `version`: "Deluxe",
     * "全景声"); shown as it comes, never translated. Null when the server doesn't say (Android Album.version).
     */
    val version: String? = null,
) {
    /** [name] without the " (version)" a server appends to it (Android `albumNameWithoutVersion`). */
    val nameWithoutVersion: String
        get() = albumNameWithoutVersion(name, version)

    /** The constructor from before [played] existed (Swift: the init without `played:`). */
    constructor(
        ref: MediaRef, id: String, name: String, artist: String?, artistId: String?, year: Int?,
        songCount: Int?, durationSec: Int?, coverArtId: String, starred: Boolean, starredAt: String?,
        userRating: Int?, playCount: Int?, created: String?, genre: String?, releaseType: String?,
        songs: List<SongInfo>,
    ) : this(
        ref, id, name, artist, artistId, year, songCount, durationSec, coverArtId, starred, starredAt,
        userRating, playCount, created, genre, releaseType, songs, null, null,
    )

    /** The constructor from before [version] existed (Swift: the init with `played:` and no `version:`). */
    constructor(
        ref: MediaRef, id: String, name: String, artist: String?, artistId: String?, year: Int?,
        songCount: Int?, durationSec: Int?, coverArtId: String, starred: Boolean, starredAt: String?,
        userRating: Int?, playCount: Int?, created: String?, genre: String?, releaseType: String?,
        songs: List<SongInfo>, played: String?,
    ) : this(
        ref, id, name, artist, artistId, year, songCount, durationSec, coverArtId, starred, starredAt,
        userRating, playCount, created, genre, releaseType, songs, played, null,
    )
}

/** [name] without a trailing " ([version])"; [name] unchanged unless it ends exactly so (Android Album.kt). */
fun albumNameWithoutVersion(name: String, version: String?): String {
    val v = version?.trim()?.takeIf(String::isNotEmpty) ?: return name
    val suffix = " ($v)"
    if (!name.endsWith(suffix)) return name
    return name.removeSuffix(suffix).trimEnd().ifEmpty { name }
}

@Serializable
data class ArtistInfo(
    val ref: MediaRef,
    val id: String,
    val name: String,
    val albumCount: Int?,
    val coverArtId: String?,
    val starred: Boolean,
    val starredAt: String?,
    /** Filled by `getArtist`; empty in indexes and search. */
    val albums: List<AlbumInfo>,
)

/** One letter group of `getArtists`. */
@Serializable
data class ArtistIndex(
    val name: String,
    val artists: List<ArtistInfo>,
)

@Serializable
data class PlaylistSummary(
    val ref: MediaRef,
    val id: String,
    val name: String,
    val owner: String?,
    val isPublic: Boolean?,
    val comment: String?,
    /** `coverArt ?: id` (Navidrome serves playlist art by playlist id). */
    val coverArtId: String,
    val songCount: Int?,
    val durationSec: Int?,
    val created: String?,
    val changed: String?,
    /** Same rule as Android: not readonly, and owner blank or the login name. */
    val canWrite: Boolean,
    /** True / false when the owner is known (case-insensitive), null when the server sent none. */
    val ownedByMe: Boolean?,
)

@Serializable
data class PlaylistDetail(
    val summary: PlaylistSummary,
    val entries: List<SongInfo>,
)

@Serializable
data class SearchResult(
    val artists: List<ArtistInfo>,
    val albums: List<AlbumInfo>,
    val songs: List<SongInfo>,
)

@Serializable
data class Starred(
    val artists: List<ArtistInfo>,
    val albums: List<AlbumInfo>,
    val songs: List<SongInfo>,
)

/** One lyric line. [timeMs] is 0 for every line of unsynced lyrics. */
@Serializable
data class LyricLine(
    val timeMs: Long,
    val text: String,
)

/**
 * Lyrics for one song. [synced] lines are sorted by [LyricLine.timeMs]
 * ascending with any offset already applied; unsynced lyrics have one line
 * per text line, all at 0.
 */
@Serializable
data class Lyrics(
    val synced: Boolean,
    val lines: List<LyricLine>,
    val language: String? = null,
) {
    /** The lines joined with "\n". */
    val text: String get() = lines.joinToString("\n") { it.text }

    /** Index of the line playing at [positionMs], or -1 before the first line / when unsynced. */
    fun lineIndexAt(positionMs: Long): Int {
        if (!synced) return -1
        var found = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= positionMs) found = i else break
        }
        return found
    }
}

/** What `ping` learns about the server. */
@Serializable
data class ServerInfo(
    val apiVersion: String?,
    /** OpenSubsonic server type, e.g. "navidrome"; null on plain Subsonic. */
    val type: String?,
    val serverVersion: String?,
    val openSubsonic: Boolean,
)
