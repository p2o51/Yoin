package com.gpo.yoin.data.source.subsonic

import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.AudioFormat
import com.gpo.yoin.data.model.ArtistDetail
import com.gpo.yoin.data.model.ArtistIndex
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.LyricLine
import com.gpo.yoin.data.model.Lyrics
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.ReleaseType
import com.gpo.yoin.data.model.ReplayGain
import com.gpo.yoin.data.model.SearchResults
import com.gpo.yoin.data.model.Starred
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.model.discTrackOrder
import com.gpo.yoin.data.remote.Album as SubsonicAlbum
import com.gpo.yoin.data.remote.Artist as SubsonicArtist
import com.gpo.yoin.data.remote.ArtistDetail as SubsonicArtistDetail
import com.gpo.yoin.data.remote.ArtistIndex as SubsonicArtistIndex
import com.gpo.yoin.data.remote.LyricsList as SubsonicLyricsList
import com.gpo.yoin.data.remote.Playlist as SubsonicPlaylist
import com.gpo.yoin.data.remote.SearchResult as SubsonicSearchResult
import com.gpo.yoin.data.remote.Song as SubsonicSong
import com.gpo.yoin.data.remote.StarredResponse as SubsonicStarredResponse
import com.gpo.yoin.data.model.Playlist as NeutralPlaylist

internal fun SubsonicSong.toTrack(): Track {
    val extras = mutableMapOf<String, String>()
    bitRate?.let { extras[EXTRA_BIT_RATE] = it.toString() }
    size?.let { extras[EXTRA_SIZE] = it.toString() }
    contentType?.let { extras[EXTRA_CONTENT_TYPE] = it }
    suffix?.let { extras[EXTRA_SUFFIX] = it }
    path?.let { extras[EXTRA_PATH] = it }
    // OpenSubsonic names each artist; carried only when there is more than
    // the one in `artist`, as Spotify's are.
    val credited = artists.filter { it.name.isNotBlank() }
    if (credited.size > 1) {
        extras[Track.EXTRA_ARTISTS] = Track.joinArtists(credited.map { it.name.trim() })
        extras[Track.EXTRA_ARTIST_IDS] = Track.joinArtists(credited.map { it.id.orEmpty() })
    }
    return Track(
        id = MediaId.subsonic(id),
        title = title,
        artist = displayArtist?.takeIf(String::isNotBlank) ?: artist,
        artistId = artistId?.let(MediaId::subsonic),
        album = album,
        albumId = albumId?.let(MediaId::subsonic),
        coverArt = coverArt?.let(CoverRef::SourceRelative),
        durationSec = duration,
        trackNumber = track,
        year = year,
        genre = genre,
        userRating = userRating,
        isStarred = !starred.isNullOrEmpty(),
        extras = extras,
        // `starred` is the per-user "when I starred this" timestamp (ISO-8601);
        // carry it as addedAt so it feeds the home "Recently Added" shelf, the
        // same field Spotify populates from a saved track's added_at.
        addedAt = starred,
        audioFormat = subsonicAudioFormat(),
        discNumber = discNumber?.takeIf { it > 0 },
        replayGain = replayGain?.let {
            ReplayGain.of(
                trackGainDb = it.trackGain,
                albumGainDb = it.albumGain,
                trackPeak = it.trackPeak,
                albumPeak = it.albumPeak,
                baseGainDb = it.baseGain,
                fallbackGainDb = it.fallbackGain,
            )
        },
    )
}

/** What the server says about the file's encoding; null when it says nothing usable. */
internal fun SubsonicSong.subsonicAudioFormat(): AudioFormat? {
    val depth = bitDepth?.takeIf { it > 0 }
    // An MP4 the server can't read (Navidrome and E-AC-3) comes with the bit
    // rate, depth, rate and channels all 0: that is no AAC, so say nothing
    // and let the decoder name it once it plays.
    if (AudioFormat.isMp4Suffix(suffix) &&
        listOf(bitRate, bitDepth, samplingRate, channelCount).all { (it ?: 0) <= 0 }
    ) {
        return null
    }
    val codec = AudioFormat.codecForSuffix(suffix, depth)
        ?: AudioFormat.codecForMime(contentType)
    val format = AudioFormat(
        codec = codec,
        bitRateKbps = bitRate?.takeIf { it > 0 },
        bitDepth = depth,
        sampleRateHz = samplingRate?.takeIf { it > 0 },
        channels = channelCount?.takeIf { it > 0 },
    )
    return format.takeIf { codec != null || format.bitRateKbps != null }
}

internal fun SubsonicAlbum.toAlbum(): Album = Album(
    id = MediaId.subsonic(id),
    name = name,
    artist = artist,
    artistId = artistId?.let(MediaId::subsonic),
    coverArt = (coverArt ?: id).let(CoverRef::SourceRelative),
    songCount = songCount,
    durationSec = duration,
    year = year,
    genre = genre,
    isStarred = !starred.isNullOrEmpty(),
    // Multi-disc albums by (disc, track): design.md Library Songs reads them in this order too.
    tracks = discTrackOrder(song.map { it.toTrack() }),
    // `starred` is the per-user "when I starred this" timestamp (ISO-8601);
    // carry it as addedAt so starred albums feed the home "Recently Added"
    // albums shelf, mirroring the track mapper above.
    addedAt = starred,
    releaseType = subsonicReleaseType(releaseTypes),
    // When the album came onto the server: Library's Recently added.
    libraryAddedAt = created,
    version = version?.trim()?.takeIf(String::isNotEmpty),
)

/** OpenSubsonic `releaseTypes` → [ReleaseType]; the first recognised value wins. */
internal fun subsonicReleaseType(releaseTypes: List<String>): ReleaseType? =
    releaseTypes.firstNotNullOfOrNull { type ->
        when (type.trim().lowercase()) {
            "album" -> ReleaseType.Album
            "ep" -> ReleaseType.EP
            "single" -> ReleaseType.Single
            "compilation" -> ReleaseType.Compilation
            else -> null
        }
    }

internal fun SubsonicArtist.toArtist(): Artist = Artist(
    id = MediaId.subsonic(id),
    name = name,
    albumCount = albumCount,
    coverArt = coverArt?.let(CoverRef::SourceRelative),
    isStarred = !starred.isNullOrEmpty(),
)

internal fun SubsonicArtistDetail.toArtistDetail(): ArtistDetail = ArtistDetail(
    id = MediaId.subsonic(id),
    name = name,
    albumCount = albumCount,
    coverArt = coverArt?.let(CoverRef::SourceRelative),
    isStarred = !starred.isNullOrEmpty(),
    albums = album.map { it.toAlbum() },
)

internal fun SubsonicArtistIndex.toArtistIndex(): ArtistIndex = ArtistIndex(
    name = name,
    artists = artist.map { it.toArtist() },
)

internal fun SubsonicPlaylist.toPlaylist(currentUsername: String? = null): NeutralPlaylist = NeutralPlaylist(
    id = MediaId.subsonic(id),
    name = name,
    owner = owner,
    coverArt = id.let(CoverRef::SourceRelative),
    songCount = songCount,
    durationSec = duration,
    tracks = entry.map { it.toTrack() },
    // getPlaylists returns playable playlists, including public playlists
    // owned by other users. An explicit OpenSubsonic readonly flag wins;
    // legacy servers may omit the optional owner and readonly fields.
    canWrite = isReadOnly != true &&
        (owner.isNullOrBlank() || owner == currentUsername),
    snapshotId = null,
    comment = comment,
    libraryAddedAt = created,
    // Ownership, not access: readonly says nothing about who made it. A
    // server may log in a name in any case and send back the stored one.
    ownedByMe = owner?.takeUnless(String::isBlank)?.let { playlistOwner ->
        currentUsername?.let { playlistOwner.equals(it, ignoreCase = true) }
    },
)

internal fun SubsonicSearchResult.toSearchResults(): SearchResults = SearchResults(
    tracks = song.map { it.toTrack() },
    albums = album.map { it.toAlbum() },
    artists = artist.map { it.toArtist() },
)

internal fun SubsonicStarredResponse.toStarred(): Starred = Starred(
    tracks = song.map { it.toTrack() },
    albums = album.map { it.toAlbum() },
    artists = artist.map { it.toArtist() },
)

internal fun SubsonicLyricsList.toLyrics(): Lyrics? {
    val structured = structuredLyrics.firstOrNull() ?: return null
    return if (structured.synced && structured.line.any { it.start != null }) {
        Lyrics.Synced(
            language = structured.lang,
            // Servers may emit lines out of order; ascending startMs is an
            // invariant the lyric-index scans (early-break) rely on.
            lines = structured.line
                .map { LyricLine(startMs = it.start ?: 0L, text = it.value) }
                .sortedBy { it.startMs },
        )
    } else {
        Lyrics.Unsynced(
            language = structured.lang,
            text = structured.line.joinToString("\n") { it.value },
        )
    }
}

internal const val EXTRA_BIT_RATE = "subsonic.bitRate"
internal const val EXTRA_SIZE = "subsonic.size"
internal const val EXTRA_CONTENT_TYPE = "subsonic.contentType"
internal const val EXTRA_SUFFIX = "subsonic.suffix"
internal const val EXTRA_PATH = "subsonic.path"
