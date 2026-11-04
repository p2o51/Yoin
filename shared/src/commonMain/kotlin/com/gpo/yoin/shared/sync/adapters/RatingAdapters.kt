package com.gpo.yoin.shared.sync.adapters

import com.gpo.yoin.shared.sync.ApplyOutcome
import com.gpo.yoin.shared.sync.ConflictPolicy
import com.gpo.yoin.shared.sync.DomainRow
import com.gpo.yoin.shared.sync.FileClass
import com.gpo.yoin.shared.sync.SkipReason
import com.gpo.yoin.shared.sync.SyncAdapter
import com.gpo.yoin.shared.sync.SyncFormat
import com.gpo.yoin.shared.sync.SyncKinds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Ports of Android's TrackRatingSyncAdapter, AlbumRatingSyncAdapter and
 * AlbumReviewSyncAdapter (app/src/main/java/com/gpo/yoin/data/sync/adapters/).
 * Same keys, projections, empty-value rules and expected-hash guards.
 *
 * Ratings: Android stores a Float; the domain here holds a Double. A Float is
 * carried to the Double through its decimal text (7.3f -> 7.3) and back with
 * toFloat(), so a rating read back after apply projects to the exact Float
 * it was written from.
 */

internal fun Double.asRating(): Float = toFloat()

internal fun Float.asDomainRating(): Double = toString().toDouble()

internal fun albumKey(provider: String, albumId: String) = SyncFormat.compositeKey(provider, albumId)

internal fun albumKeyParts(key: String): Pair<String, String>? =
    splitKey(key, 2)?.let { (provider, albumId) -> provider to albumId }

/** `local_ratings` <-> [SyncKinds.TRACK_RATING], key = provider␟songId. Only the 0..10 rating syncs. */
class TrackRatingSyncAdapter(private val domain: SyncDomain) : SyncAdapter {
    override val kind = SyncKinds.TRACK_RATING
    override val kindVersion = 1
    override val fileClass = FileClass.STATE
    override val perAccount = true
    override val propagatesDeletes = false
    override val conflictPolicy = ConflictPolicy.LWW

    override suspend fun readAll(localProfileId: String?): List<DomainRow> {
        val profileId = requireNotNull(localProfileId) { "track_rating is per-account" }
        return domain.trackRatings(profileId).map { row ->
            DomainRow(
                key = SyncFormat.compositeKey(row.provider, row.songId),
                projection = projection(row.songId, row.provider, row.rating.asRating()),
                rowTs = row.updatedAt,
            )
        }
    }

    override fun project(payload: JsonObject): JsonObject? {
        val songId = payload.requiredString("songId")?.takeIf(String::isNotEmpty) ?: return null
        val provider = payload.requiredString("provider")?.takeIf(String::isNotEmpty) ?: return null
        val rating = payload.rating("rating") ?: return null
        return projection(songId, provider, rating)
    }

    override suspend fun apply(
        localProfileId: String?,
        key: String,
        payload: JsonObject,
        versionTs: Long,
        expectedLocalHash: String?,
    ): ApplyOutcome {
        val profileId = requireNotNull(localProfileId) { "track_rating is per-account" }
        val (provider, songId) = splitKey(key, 2) ?: return ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, null)
        val existing = domain.trackRating(profileId, provider, songId)
        val currentHash = existing?.let { projection(it.songId, it.provider, it.rating.asRating()) }.hashOrNull()
        val rating = payload.rating("rating")
        val matchesKey = payload.requiredString("songId") == songId && payload.requiredString("provider") == provider
        if (rating == null || !matchesKey) return ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, currentHash)
        if (currentHash != expectedLocalHash) return ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, currentHash)
        domain.putTrackRating(profileId, TrackRatingRow(provider, songId, rating.asDomainRating(), versionTs))
        return ApplyOutcome.Applied(
            domain.trackRating(profileId, provider, songId)
                ?.let { projection(it.songId, it.provider, it.rating.asRating()) }
                .hashOrNull(),
        )
    }

    override suspend fun delete(localProfileId: String?, key: String, expectedLocalHash: String?): ApplyOutcome {
        val profileId = requireNotNull(localProfileId) { "track_rating is per-account" }
        val (provider, songId) = splitKey(key, 2) ?: return ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, null)
        val current = domain.trackRating(profileId, provider, songId) ?: return ApplyOutcome.Applied(null)
        val currentHash = projection(current.songId, current.provider, current.rating.asRating()).hashOrNull()
        if (currentHash != expectedLocalHash) return ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, currentHash)
        domain.deleteTrackRating(profileId, provider, songId)
        return ApplyOutcome.Applied(null)
    }

    private fun projection(songId: String, provider: String, rating: Float) = JsonObject(
        mapOf(
            "songId" to jsonString(songId),
            "provider" to jsonString(provider),
            "rating" to jsonRating(rating),
        ),
    )
}

/** `album_ratings.rating` <-> [SyncKinds.ALBUM_RATING], key = provider␟albumId. See Android's file header. */
class AlbumRatingSyncAdapter(private val domain: SyncDomain) : SyncAdapter {
    override val kind = SyncKinds.ALBUM_RATING
    override val kindVersion = 1
    override val fileClass = FileClass.STATE
    override val perAccount = true
    override val propagatesDeletes = false
    override val conflictPolicy = ConflictPolicy.LWW

    override suspend fun readAll(localProfileId: String?): List<DomainRow> {
        val profileId = requireNotNull(localProfileId) { "album_rating is per-account" }
        return domain.albumRatings(profileId).map { row ->
            DomainRow(
                key = albumKey(row.provider, row.albumId),
                projection = projection(row),
                rowTs = if (row.rating.asRating() == 0f) 0L else row.updatedAt,
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
        val (provider, albumId) = albumKeyParts(key) ?: return ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, null)
        val existing = domain.albumRating(profileId, provider, albumId)
        val currentHash = existing?.let(::projection).hashOrNull()
        val rating = payload.rating("rating")
        val matchesKey = payload.requiredString("albumId") == albumId && payload.requiredString("provider") == provider
        if (rating == null || !matchesKey) return ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, currentHash)
        val unchangedSinceExpected = currentHash == expectedLocalHash ||
            (expectedLocalHash == null && existing != null && existing.rating.asRating() == 0f)
        if (!unchangedSinceExpected) return ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, currentHash)
        val updated = existing?.copy(rating = rating.asDomainRating(), updatedAt = maxOf(existing.updatedAt, versionTs))
            // Even for rating 0: see Android's AlbumRatingSyncAdapter file header.
            ?: AlbumRatingRow(provider, albumId, rating.asDomainRating(), review = null, reviewUpdatedAt = null, updatedAt = versionTs)
        domain.putAlbumRating(profileId, updated)
        return ApplyOutcome.Applied(domain.albumRating(profileId, provider, albumId)?.let(::projection).hashOrNull())
    }

    override suspend fun delete(localProfileId: String?, key: String, expectedLocalHash: String?): ApplyOutcome {
        val profileId = requireNotNull(localProfileId) { "album_rating is per-account" }
        val (provider, albumId) = albumKeyParts(key) ?: return ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, null)
        val current = domain.albumRating(profileId, provider, albumId) ?: return ApplyOutcome.Applied(null)
        val currentHash = projection(current).hashOrNull()
        if (currentHash != expectedLocalHash) return ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, currentHash)
        if (current.review.isNullOrBlank()) {
            domain.deleteAlbumRating(profileId, provider, albumId)
        } else {
            domain.putAlbumRating(profileId, current.copy(rating = 0.0))
        }
        return ApplyOutcome.Applied(domain.albumRating(profileId, provider, albumId)?.let(::projection).hashOrNull())
    }

    private fun projection(row: AlbumRatingRow) = projection(row.albumId, row.provider, row.rating.asRating())

    private fun projection(albumId: String, provider: String, rating: Float) = JsonObject(
        mapOf(
            "albumId" to jsonString(albumId),
            "provider" to jsonString(provider),
            "rating" to jsonRating(rating),
        ),
    )
}

/**
 * `album_ratings.review` <-> [SyncKinds.ALBUM_REVIEW], key = provider␟albumId.
 * Clearing a review is a value (null), never a delete. Concurrent edits keep both texts.
 */
class AlbumReviewSyncAdapter(private val domain: SyncDomain) : SyncAdapter {
    override val kind = SyncKinds.ALBUM_REVIEW
    override val kindVersion = 1
    override val fileClass = FileClass.STATE
    override val perAccount = true
    override val propagatesDeletes = false
    override val conflictPolicy = ConflictPolicy.KEEP_BOTH_TEXT

    override suspend fun readAll(localProfileId: String?): List<DomainRow> {
        val profileId = requireNotNull(localProfileId) { "album_review is per-account" }
        return domain.albumRatings(profileId).map { row ->
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
        val (provider, albumId) = albumKeyParts(key) ?: return ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, null)
        val existing = domain.albumRating(profileId, provider, albumId)
        val currentHash = existing?.let(::projection).hashOrNull()
        val matchesKey = payload.requiredString("albumId") == albumId && payload.requiredString("provider") == provider
        val review = payload.optionalString("review")
        if (review !is Field.Ok || !matchesKey) return ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, currentHash)
        val unchangedSinceExpected = currentHash == expectedLocalHash ||
            (expectedLocalHash == null && existing != null && existing.review.isNullOrBlank())
        if (!unchangedSinceExpected) return ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, currentHash)
        val reviewUpdatedAt = when {
            review.value.isNullOrBlank() -> null
            existing?.review == review.value -> existing.reviewUpdatedAt ?: versionTs
            else -> versionTs
        }
        val updated = existing?.copy(
            review = review.value,
            reviewUpdatedAt = reviewUpdatedAt,
            updatedAt = maxOf(existing.updatedAt, versionTs),
        ) ?: AlbumRatingRow(provider, albumId, 0.0, review.value, reviewUpdatedAt, versionTs)
        domain.putAlbumRating(profileId, updated)
        return ApplyOutcome.Applied(domain.albumRating(profileId, provider, albumId)?.let(::projection).hashOrNull())
    }

    override suspend fun delete(localProfileId: String?, key: String, expectedLocalHash: String?): ApplyOutcome {
        val profileId = requireNotNull(localProfileId) { "album_review is per-account" }
        val (provider, albumId) = albumKeyParts(key) ?: return ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, null)
        val current = domain.albumRating(profileId, provider, albumId) ?: return ApplyOutcome.Applied(null)
        val currentHash = projection(current).hashOrNull()
        if (currentHash != expectedLocalHash) return ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, currentHash)
        if (current.rating.asRating() == 0f) {
            domain.deleteAlbumRating(profileId, provider, albumId)
        } else {
            domain.putAlbumRating(profileId, current.copy(review = null, reviewUpdatedAt = null))
        }
        return ApplyOutcome.Applied(domain.albumRating(profileId, provider, albumId)?.let(::projection).hashOrNull())
    }

    override fun mergeConcurrent(winner: JsonObject, loser: JsonObject, loserLabel: String): JsonObject? {
        val winnerText = winner.requiredString("review")
        val loserText = loser.requiredString("review")
        if (winnerText.isNullOrBlank() || loserText.isNullOrBlank() || winnerText == loserText) return null
        return winner.with("review", JsonPrimitive(mergedReview(winnerText, loserText, loserLabel)))
    }

    private fun projection(row: AlbumRatingRow) = projection(row.albumId, row.provider, row.review)

    private fun projection(albumId: String, provider: String, review: String?) = JsonObject(
        mapOf(
            "albumId" to jsonString(albumId),
            "provider" to jsonString(provider),
            "review" to jsonString(review),
        ),
    )

    companion object {
        /** Both texts, the loser under a "— <device · date> —" divider. */
        fun mergedReview(winner: String, loser: String, loserLabel: String): String =
            winner + "\n\n— " + loserLabel + " —\n" + loser
    }
}
