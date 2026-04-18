package com.gpo.yoin.data.model

/**
 * Thin compatibility shims for UI/view-model migration while the repo moves
 * from Subsonic-shaped DTOs onto provider-agnostic domain models.
 *
 * These are intentionally narrow and presentation-facing only. New code should
 * prefer the canonical neutral names (`tracks`, `albums`, `artists`,
 * `durationSec`, `trackNumber`, `isStarred`).
 */

val ArtistDetail.album: List<Album>
    get() = albums

val ArtistIndex.artist: List<Artist>
    get() = artists

val Album.song: List<Track>
    get() = tracks

val Album.duration: Int?
    get() = durationSec

val Playlist.entry: List<Track>
    get() = tracks

val Playlist.duration: Int?
    get() = durationSec

val Playlist.comment: String?
    get() = null

val Playlist.isPublic: Boolean?
    get() = null

val Track.track: Int?
    get() = trackNumber

val Track.duration: Int?
    get() = durationSec

val Track.starred: Boolean?
    get() = isStarred.takeIf { it }

val SearchResults.song: List<Track>
    get() = tracks

val SearchResults.album: List<Album>
    get() = albums

val SearchResults.artist: List<Artist>
    get() = artists

val Starred.song: List<Track>
    get() = tracks

val Starred.album: List<Album>
    get() = albums

val Starred.artist: List<Artist>
    get() = artists
