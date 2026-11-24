package com.gpo.yoin.shared.search

import com.gpo.yoin.shared.data.model.MediaRef

/*
 * Play from a note's second (#Yoin:#18), as the Memories diary and the album scrapbook do it: a note tile
 * on a song the service returned plays that song and, once it is current and prepared, seeks to where the
 * note was written; a note on a song that isn't there opens its album's scrapbook at the song, in the
 * note's own account. The platform plays, seeks, switches accounts and navigates.
 */

enum class NoteOpenAction {
    /** Play [NoteOpenTarget.song] (the returned song), then seek to [NoteOpenTarget.seekToMs]. */
    PlaySong,

    /** Open [NoteOpenTarget.album]'s scrapbook in [NoteOpenTarget.profileId]'s account, at [NoteOpenTarget.song]. */
    OpenAlbum,

    /** Nothing to open: the song isn't returned and its album is unknown. */
    None,
}

data class NoteOpenTarget(
    val action: NoteOpenAction,
    /** PlaySong: the song to play. OpenAlbum: the song to focus on the scrapbook, when the note knows it. */
    val song: MediaRef?,
    /** OpenAlbum: the album whose scrapbook opens. */
    val album: MediaRef?,
    /** The note's account. */
    val profileId: String,
    /** OpenAlbum: the note is another account's; switch to it first (when that account still exists). */
    val switchAccount: Boolean,
    /** PlaySong: where to seek once the song is current (ms); null plays it from the start. */
    val seekToMs: Long?,
    /** OpenAlbum: what an account-switch prompt names: the song, else the album. */
    val subject: String?,
)

/**
 * Where opening [note] leads. [returnedSong] = the service's song the note's tile is on (it joined that
 * tile), null for a note standing alone. [activeProfileId] = the account in use.
 */
fun noteOpenTarget(note: NoteSearchHit, returnedSong: MediaRef?, activeProfileId: String?): NoteOpenTarget {
    if (returnedSong != null) {
        return NoteOpenTarget(
            action = NoteOpenAction.PlaySong,
            song = returnedSong,
            album = null,
            profileId = note.profileId,
            switchAccount = false,
            seekToMs = note.positionMs?.takeIf { it > 0L },
            subject = null,
        )
    }
    val album = note.album ?: return NoteOpenTarget(
        action = NoteOpenAction.None,
        song = null,
        album = null,
        profileId = note.profileId,
        switchAccount = false,
        seekToMs = null,
        subject = null,
    )
    val song = if (note.provider != null && note.songId != null) MediaRef(note.provider, note.songId) else null
    return NoteOpenTarget(
        action = NoteOpenAction.OpenAlbum,
        song = song,
        album = MediaRef(album.provider, album.albumId),
        profileId = note.profileId,
        switchAccount = note.profileId != activeProfileId,
        seekToMs = null,
        subject = note.trackTitle ?: album.name,
    )
}

/** How long to wait for the song to become current before giving up on the seek (a provider that never reports it). */
const val NOTE_SEEK_TIMEOUT_MS = 4_000L

/** Whether the player is ready for the note's seek: [targetRawId] is current and its duration known. */
fun noteSeekReady(currentRawId: String?, durationMs: Long, targetRawId: String): Boolean =
    currentRawId == targetRawId && durationMs > 0L
