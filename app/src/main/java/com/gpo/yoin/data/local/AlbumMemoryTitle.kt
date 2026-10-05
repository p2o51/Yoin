package com.gpo.yoin.data.local

import androidx.room.Entity

/**
 * The title the user gave an album's Memory, replacing Yoin's own (the AI title, else the local motif,
 * else the album name — see `resolveAlbumMemoryTitle`). One row per (profile, provider, album): two
 * profiles on the same server never share a title, and the raw album id lives in its provider's
 * namespace ([com.gpo.yoin.data.model.MediaId.rawId]).
 *
 * Sync contract (cloud sync, last writer wins):
 *  - [title] is stored exactly as entered, trimmed, never blank;
 *  - [updatedAt] is the moment the user edited it (epoch ms), not a server or sync time;
 *  - "Restore the AI title" is a hard DELETE of the row — there is no null / tombstone title.
 *
 * Written by `AlbumMemoryTitleStore` only (Memories, and the album page's second page).
 */
@Entity(
    tableName = "album_memory_titles",
    primaryKeys = ["profileId", "provider", "albumId"],
)
data class AlbumMemoryTitle(
    val profileId: String,
    val provider: String,
    /** The album's raw id in [provider]'s namespace (MediaId.rawId). */
    val albumId: String,
    /** Trimmed, never blank. */
    val title: String,
    /** When the user last edited the title (epoch ms). */
    val updatedAt: Long,
)
