package com.gpo.yoin.data.sync.adapters

import androidx.room.withTransaction
import com.gpo.yoin.data.local.AlbumRating
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.sync.ApplyOutcome
import com.gpo.yoin.data.sync.ConflictPolicy
import com.gpo.yoin.data.sync.DomainRow
import com.gpo.yoin.data.sync.FileClass
import com.gpo.yoin.data.sync.SkipReason
import com.gpo.yoin.data.sync.SyncAdapter
import com.gpo.yoin.data.sync.SyncFormat
import com.gpo.yoin.data.sync.SyncKinds
import kotlinx.serialization.json.JsonObject

/*
 * album_rating and album_review are two synced kinds over ONE album_ratings
 * row. Each adapter reads and writes only its own column, never the NeoDB
 * state (neoDbReviewUuid, *NeedsSync), and treats the other kind's leftovers
 * as "nothing here":
 *  - An empty value (rating 0 / blank review) reports rowTs = 0, so a row
 *    that exists only because the OTHER field was written never outranks a
 *    real value from another device when this device first joins.
 *  - apply with expectedLocalHash = null also accepts a row whose own field
 *    is still empty (it was created by the sibling kind).
 *  - apply always leaves a row behind, even for an empty value: the record
 *    exists because the writing device has the row. Writing nothing would
 *    hand back "absent" (null hash) as the local state, and the sibling kind
 *    creating the row later would then read as a local edit absent -> empty,
 *    which the next capture publishes over a newer real value.
 *  - delete clears only this kind's field and drops the row only when the
 *    sibling field is empty too.
 *  - updatedAt is shared, so apply moves it forward only (max with the
 *    version ts): writing one field never makes the other look older.
 */

internal fun albumKey(provider: String, albumId: String) = SyncFormat.compositeKey(provider, albumId)

/** (provider, albumId) of an album key. */
internal fun albumKeyParts(key: String): Pair<String, String>? =
    splitKey(key, 2)?.let { (provider, albumId) -> provider to albumId }

/** A fresh row for one field; everything that isn't synced starts empty. */
internal fun newAlbumRow(
    profileId: String,
    albumId: String,
    provider: String,
    rating: Float = 0f,
    review: String? = null,
    updatedAt: Long,
) = AlbumRating(
    profileId = profileId,
    albumId = albumId,
    provider = provider,
    rating = rating,
    review = review,
    neoDbReviewUuid = null,
    ratingNeedsSync = false,
    reviewNeedsSync = false,
    updatedAt = updatedAt,
)

/** `album_ratings.rating` <-> [SyncKinds.ALBUM_RATING], key = provider␟albumId. See the file header. */
class AlbumRatingSyncAdapter(private val db: YoinDatabase) : SyncAdapter {
    override val kind = SyncKinds.ALBUM_RATING
    override val kindVersion = 1
    override val fileClass = FileClass.STATE
    override val perAccount = true
    override val propagatesDeletes = false
    override val conflictPolicy = ConflictPolicy.LWW

    private val dao get() = db.syncDomainDao()

    override suspend fun readAll(localProfileId: String?): List<DomainRow> {
        val profileId = requireNotNull(localProfileId) { "album_rating is per-account" }
        return dao.albumRatingsForProfile(profileId).map { row ->
            DomainRow(
                key = albumKey(row.provider, row.albumId),
                projection = projection(row),
                rowTs = if (row.rating == 0f) 0L else row.updatedAt,
            )
        }
    }

    override fun project(payload: JsonObject): JsonObject? {
        val albumId = payload.requiredString("albumId")?.takeIf(String::isNotEmpty) ?: return null
        val provider = payload.requiredString("provider")?.takeIf(String::isNotEmpty) ?: return null
        val rating = payload.rating("rating") ?: return null
        return projection(albumId, provider, rating)
    }

    override suspend fun apply(
        localProfileId: String?,
        key: String,
        payload: JsonObject,
        versionTs: Long,
        expectedLocalHash: String?,
    ): ApplyOutcome {
        val profileId = requireNotNull(localProfileId) { "album_rating is per-account" }
        return db.withTransaction {
            val (provider, albumId) = albumKeyParts(key)
                ?: return@withTransaction ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, null)
            val existing = dao.albumRating(profileId, albumId, provider)
            val currentHash = existing?.let(::projection).hashOrNull()
            val rating = payload.rating("rating")
            val matchesKey = payload.requiredString("albumId") == albumId &&
                payload.requiredString("provider") == provider
            if (rating == null || !matchesKey) {
                return@withTransaction ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, currentHash)
            }
            val unchangedSinceExpected = currentHash == expectedLocalHash ||
                (expectedLocalHash == null && existing != null && existing.rating == 0f)
            if (!unchangedSinceExpected) {
                return@withTransaction ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, currentHash)
            }
            if (existing != null) {
                dao.updateAlbumRatingValue(
                    profileId = profileId,
                    albumId = albumId,
                    provider = provider,
                    rating = rating,
                    updatedAt = maxOf(existing.updatedAt, versionTs),
                )
            } else {
                // Even for rating 0: see the file header.
                dao.insertAlbumRatingIfAbsent(
                    newAlbumRow(profileId, albumId, provider, rating = rating, updatedAt = versionTs),
                )
            }
            ApplyOutcome.Applied(dao.albumRating(profileId, albumId, provider)?.let(::projection).hashOrNull())
        }
    }

    override suspend fun delete(localProfileId: String?, key: String, expectedLocalHash: String?): ApplyOutcome {
        val profileId = requireNotNull(localProfileId) { "album_rating is per-account" }
        return db.withTransaction {
            val (provider, albumId) = albumKeyParts(key)
                ?: return@withTransaction ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, null)
            val current = dao.albumRating(profileId, albumId, provider)
                ?: return@withTransaction ApplyOutcome.Applied(null)
            val currentHash = projection(current).hashOrNull()
            if (currentHash != expectedLocalHash) {
                return@withTransaction ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, currentHash)
            }
            if (current.review.isNullOrBlank()) {
                dao.deleteAlbumRating(profileId, albumId, provider)
            } else {
                dao.updateAlbumRatingValue(profileId, albumId, provider, rating = 0f, updatedAt = current.updatedAt)
            }
            ApplyOutcome.Applied(dao.albumRating(profileId, albumId, provider)?.let(::projection).hashOrNull())
        }
    }

    private fun projection(row: AlbumRating) = projection(row.albumId, row.provider, row.rating)

    private fun projection(albumId: String, provider: String, rating: Float) = JsonObject(
        mapOf(
            "albumId" to jsonString(albumId),
            "provider" to jsonString(provider),
            "rating" to jsonRating(rating),
        ),
    )
}
