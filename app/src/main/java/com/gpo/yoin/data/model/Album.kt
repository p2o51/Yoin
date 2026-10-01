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
