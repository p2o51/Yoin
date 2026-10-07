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
import com.gpo.yoin.data.sync.SyncKinds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `album_ratings.review` <-> [SyncKinds.ALBUM_REVIEW], key = provider␟albumId.
 * Clearing a review is a value (null), never a delete. Concurrent edits keep
 * both texts ([mergeConcurrent]). Shares its row with [AlbumRatingSyncAdapter];
 * see that file's header for the empty-value rules.
 */
class AlbumReviewSyncAdapter(private val db: YoinDatabase) : SyncAdapter {
    override val kind = SyncKinds.ALBUM_REVIEW
    override val kindVersion = 1
    override val fileClass = FileClass.STATE
    override val perAccount = true
    override val propagatesDeletes = false
    override val conflictPolicy = ConflictPolicy.KEEP_BOTH_TEXT

    private val dao get() = db.syncDomainDao()

    override suspend fun readAll(localProfileId: String?): List<DomainRow> {
        val profileId = requireNotNull(localProfileId) { "album_review is per-account" }
        return dao.albumRatingsForProfile(profileId).map { row ->
            DomainRow(
                key = albumKey(row.provider, row.albumId),
                projection = projection(row),
                rowTs = if (row.review.isNullOrBlank()) 0L else row.updatedAt,
            )
        }
    }

    override fun project(payload: JsonObject): JsonObject? {
        val albumId = payload.requiredString("albumId")?.takeIf(String::isNotEmpty) ?: return null
        val provider = payload.requiredString("provider")?.takeIf(String::isNotEmpty) ?: return null
        val review = payload.optionalString("review").orElse { return null }
        return projection(albumId, provider, review)
    }

    override suspend fun apply(
        localProfileId: String?,
        key: String,
        payload: JsonObject,
        versionTs: Long,
        expectedLocalHash: String?,
    ): ApplyOutcome {
        val profileId = requireNotNull(localProfileId) { "album_review is per-account" }
        return db.withTransaction {
            val (provider, albumId) = albumKeyParts(key)
                ?: return@withTransaction ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, null)
            val existing = dao.albumRating(profileId, albumId, provider)
            val currentHash = existing?.let(::projection).hashOrNull()
            val matchesKey = payload.requiredString("albumId") == albumId &&
                payload.requiredString("provider") == provider
            val review = payload.optionalString("review")
            if (review !is Field.Ok || !matchesKey) {
                return@withTransaction ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, currentHash)
            }
            val unchangedSinceExpected = currentHash == expectedLocalHash ||
                (expectedLocalHash == null && existing != null && existing.review.isNullOrBlank())
            if (!unchangedSinceExpected) {
                return@withTransaction ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, currentHash)
            }
            // The review's writing date (album page): the incoming version's time when the text
            // changes, null once cleared; an identical text keeps the date it already has.
            val reviewUpdatedAt = when {
                review.value.isNullOrBlank() -> null
                existing?.review == review.value -> existing.reviewUpdatedAt ?: versionTs
                else -> versionTs
            }
            if (existing != null) {
                dao.updateAlbumReviewValue(
                    profileId = profileId,
                    albumId = albumId,
                    provider = provider,
                    review = review.value,
                    reviewUpdatedAt = reviewUpdatedAt,
                    updatedAt = maxOf(existing.updatedAt, versionTs),
                )
            } else {
                // Even for a cleared review: see AlbumRatingSyncAdapter's file header.
                dao.insertAlbumRatingIfAbsent(
                    newAlbumRow(profileId, albumId, provider, review = review.value, updatedAt = versionTs)
                        .copy(reviewUpdatedAt = reviewUpdatedAt),
                )
            }
            ApplyOutcome.Applied(dao.albumRating(profileId, albumId, provider)?.let(::projection).hashOrNull())
        }
    }

    override suspend fun delete(localProfileId: String?, key: String, expectedLocalHash: String?): ApplyOutcome {
        val profileId = requireNotNull(localProfileId) { "album_review is per-account" }
        return db.withTransaction {
            val (provider, albumId) = albumKeyParts(key)
                ?: return@withTransaction ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, null)
            val current = dao.albumRating(profileId, albumId, provider)
                ?: return@withTransaction ApplyOutcome.Applied(null)
            val currentHash = projection(current).hashOrNull()
            if (currentHash != expectedLocalHash) {
                return@withTransaction ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, currentHash)
            }
            if (current.rating == 0f) {
                dao.deleteAlbumRating(profileId, albumId, provider)
            } else {
                dao.updateAlbumReviewValue(
                    profileId,
                    albumId,
                    provider,
                    review = null,
                    reviewUpdatedAt = null,
                    updatedAt = current.updatedAt,
                )
            }
            ApplyOutcome.Applied(dao.albumRating(profileId, albumId, provider)?.let(::projection).hashOrNull())
        }
    }

    override fun mergeConcurrent(winner: JsonObject, loser: JsonObject, loserLabel: String): JsonObject? {
        val winnerText = winner.requiredString("review")
        val loserText = loser.requiredString("review")
        if (winnerText.isNullOrBlank() || loserText.isNullOrBlank() || winnerText == loserText) return null
        return winner.with("review", JsonPrimitive(mergedReview(winnerText, loserText, loserLabel)))
    }

    private fun projection(row: AlbumRating) = projection(row.albumId, row.provider, row.review)

    private fun projection(albumId: String, provider: String, review: String?) = JsonObject(
        mapOf(
            "albumId" to jsonString(albumId),
            "provider" to jsonString(provider),
            "review" to jsonString(review),
        ),
    )

    internal companion object {
        /** Both texts, the loser under a "— <device · date> —" divider. */
        fun mergedReview(winner: String, loser: String, loserLabel: String): String =
            winner + "\n\n— " + loserLabel + " —\n" + loser
    }
}
