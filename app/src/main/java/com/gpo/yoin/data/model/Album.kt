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
)

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
