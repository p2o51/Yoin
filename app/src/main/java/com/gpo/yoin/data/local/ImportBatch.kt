package com.gpo.yoin.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One imported file (docs/spotoolfy-migration.md §4.7). Staged when the file is read, before an account exists to
 * own it; committed once the notes and ratings are written to [profileId]. Local bookkeeping, never synced.
 */
@Entity(tableName = "import_batches")
data class ImportBatch(
    @PrimaryKey val batchId: String,
    /** [SOURCE_SPOTOOLFY]. */
    val source: String,
    /** [STATUS_STAGED] or [STATUS_COMMITTED]. */
    val status: String,
    /** The account the batch was written to; null while staged. */
    val profileId: String?,
    val fileSha256: String,
    val importedAt: Long,
    val noteCount: Int,
    val ratingCount: Int,
    /** The recap's data ([com.gpo.yoin.data.migration.spotoolfy.SpotoolfyRecap]) as JSON. */
    val summaryJson: String,
) {
    companion object {
        const val SOURCE_SPOTOOLFY = "spotoolfy"
        const val STATUS_STAGED = "staged"
        const val STATUS_COMMITTED = "committed"
    }
}

/**
 * A row a committed batch wrote, so "use another file" can take exactly those rows back out. A row belongs to one
 * batch at most: the key is the target's own identity.
 */
@Entity(
    tableName = "import_items",
    primaryKeys = ["targetKind", "targetKey"],
    indices = [Index(value = ["batchId"])],
)
data class ImportItem(
    val batchId: String,
    /** [KIND_SONG_NOTE] (key: the note id) or [KIND_LOCAL_RATING] (key: [ratingKey]). */
    val targetKind: String,
    val targetKey: String,
) {
    companion object {
        const val KIND_SONG_NOTE = "song_note"
        const val KIND_LOCAL_RATING = "local_rating"

        fun ratingKey(profileId: String, provider: String, songId: String): String = "$profileId|$provider|$songId"
    }
}
