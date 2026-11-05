package com.gpo.yoin.data.model

data class Album(
    val id: MediaId,
    val name: String,
    val artist: String?,
    val artistId: MediaId?,
    val coverArt: CoverRef?,
    val songCount: Int?,
    val durationSec: Int?,
    val year: Int?,
    val genre: String?,
    val isStarred: Boolean = false,
    val tracks: List<Track> = emptyList(),
    val addedAt: String? = null,
    /** What kind of release this is, when the provider says; null = unknown. */
    val releaseType: ReleaseType? = null,
    /**
     * When the album joined the user's library, as the provider dates it
     * (ISO-8601): Subsonic's `AlbumID3.created`, a saved Spotify album's
     * `added_at`, an Apple Music library album's `dateAdded`. Library sorts
     * Recently added by it. Not [addedAt], which on Subsonic is the star time
     * Home's Recently Added reads. Null where the provider gives no date.
     */
    val libraryAddedAt: String? = null,
    /**
     * Which version of the release this is, as the service names it
     * (OpenSubsonic `version`, from the files' ALBUMVERSION tag: "Deluxe",
     * "全景声"). Library data, shown as it comes, never translated. Null when
     * the service doesn't say.
     */
    val version: String? = null,
) {
    /**
     * [name] without the " (version)" a server appends to it (Navidrome's
     * Subsonic.AppendAlbumVersion, on by default), for where [version] is shown
     * beside it as a tag; [name] itself where it doesn't end that way.
     */
    val nameWithoutVersion: String
        get() = albumNameWithoutVersion(name, version)
}

/** [name] without a trailing " ([version])"; [name] unchanged unless it ends exactly so. */
fun albumNameWithoutVersion(name: String, version: String?): String {
    val v = version?.trim()?.takeIf(String::isNotEmpty) ?: return name
    val suffix = " ($v)"
    if (!name.endsWith(suffix)) return name
    return name.removeSuffix(suffix).trimEnd().ifEmpty { name }
}

/**
 * Release kinds a provider can report (Spotify `album_type`, OpenSubsonic
 * `releaseTypes`). Only ever set from provider data — never guessed from the
 * track count — so a null means "don't label it".
 */
enum class ReleaseType {
    Album,
    EP,
    Single,
    Compilation,
}
