package com.gpo.yoin.data.repository

import com.gpo.yoin.data.source.Capability

/**
 * Whether an album is in the active account's library, as the album page's
 * ▾ Save to library / Remove from library row reads it
 * ([YoinRepository.observeAlbumSaved]).
 */
enum class AlbumSavedState {
    /** The service can't save an album ([Capability.ALBUM_SAVE] — Subsonic, Apple Music): no row. */
    Unsupported,

    /**
     * The service saves albums but nothing says yet whether this one is saved:
     * it isn't in the saved-albums mirror (Spotify's newest 200), and there is
     * no answer from the service (not asked yet, still out, failed, or held
     * back by the rate-limit gate) and no write of Yoin's. No row either: a
     * "Save" here might save an album that is already saved, or be bound to
     * fail behind the gate.
     */
    Unknown,
    Saved,
    NotSaved;

    /** The row: true says Remove, false says Save; null leaves it out. */
    val savedOrNull: Boolean?
        get() = when (this) {
            Saved -> true
            NotSaved -> false
            Unsupported, Unknown -> null
        }
}
