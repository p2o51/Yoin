package com.gpo.yoin.data.sync.adapters

import androidx.room.withTransaction
import com.gpo.yoin.data.local.LocalRating
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.sync.ApplyOutcome
import com.gpo.yoin.data.sync.ConflictPolicy
import com.gpo.yoin.data.sync.DomainRow
import com.gpo.yoin.data.sync.FileClass
import com.gpo.yoin.data.sync.SkipReason
import com.gpo.yoin.data.sync.SyncAdapter
import com.gpo.yoin.data.sync.SyncFormat
import com.gpo.yoin.data.sync.SyncKinds
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * `local_ratings` <-> [SyncKinds.TRACK_RATING], key = provider␟songId.
 *
 * Only the 0..10 rating syncs. "Not rated" (a cleared row, rating null)
 * travels as `rating: 0`, the format's original reading of 0 (older builds
 * wrote 0 for a clear and read it as unrated); a real 0.0 score adds
 * `"zero": true`, which older builds ignore. See [ratingFrom] / [projection].
 * `serverRating` / `needsSync` are this device's
 * push state for the provider: a new row starts as already pushed (another
 * device owned the push), an existing row keeps its push state unless the
 * rating value itself changes.
 */
class TrackRatingSyncAdapter(private val db: YoinDatabase) : SyncAdapter {
    override val kind = SyncKinds.TRACK_RATING
    override val kindVersion = 1
    override val fileClass = FileClass.STATE
    override val perAccount = true
    override val propagatesDeletes = false
    override val conflictPolicy = ConflictPolicy.LWW

    private val dao get() = db.syncDomainDao()

    override suspend fun readAll(localProfileId: String?): List<DomainRow> {
        val profileId = requireNotNull(localProfileId) { "track_rating is per-account" }
        return dao.trackRatingsForProfile(profileId).map { row ->
            DomainRow(
                key = SyncFormat.compositeKey(row.provider, row.songId),
                projection = projection(row.songId, row.provider, row.rating),
                rowTs = row.updatedAt,
            )
        }
    }

    override fun project(payload: JsonObject): JsonObject? {
        val songId = payload.requiredString("songId")?.takeIf(String::isNotEmpty) ?: return null
        val provider = payload.requiredString("provider")?.takeIf(String::isNotEmpty) ?: return null
        val rating = payload.rating("rating") ?: return null
        return projection(songId, provider, ratingFrom(rating, payload))
    }

    override suspend fun apply(
        localProfileId: String?,
        key: String,
        payload: JsonObject,
        versionTs: Long,
        expectedLocalHash: String?,
    ): ApplyOutcome {
        val profileId = requireNotNull(localProfileId) { "track_rating is per-account" }
        return db.withTransaction {
            val (provider, songId) = splitKey(key, 2)
                ?: return@withTransaction ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, null)
            val existing = dao.trackRating(profileId, songId, provider)
            val currentHash = existing?.let { projection(it.songId, it.provider, it.rating) }.hashOrNull()
            val wire = payload.rating("rating")
            val matchesKey = payload.requiredString("songId") == songId &&
                payload.requiredString("provider") == provider
            if (wire == null || !matchesKey) {
                return@withTransaction ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, currentHash)
            }
            val rating = ratingFrom(wire, payload)
            if (currentHash != expectedLocalHash) {
                return@withTransaction ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, currentHash)
            }
            val updated = when {
                existing == null -> LocalRating(
                    profileId = profileId,
                    songId = songId,
                    provider = provider,
                    rating = rating,
                    serverRating = serverRatingFor(rating),
                    needsSync = false,
                    updatedAt = versionTs,
                )
                existing.rating != rating -> existing.copy(
                    rating = rating,
                    serverRating = serverRatingFor(rating),
                    needsSync = false,
                    updatedAt = versionTs,
                )
                else -> existing.copy(updatedAt = versionTs)
            }
            dao.upsertTrackRating(updated)
            ApplyOutcome.Applied(
                dao.trackRating(profileId, songId, provider)
                    ?.let { projection(it.songId, it.provider, it.rating) }
                    .hashOrNull(),
            )
        }
    }

    override suspend fun delete(localProfileId: String?, key: String, expectedLocalHash: String?): ApplyOutcome {
        val profileId = requireNotNull(localProfileId) { "track_rating is per-account" }
        return db.withTransaction {
            val (provider, songId) = splitKey(key, 2)
                ?: return@withTransaction ApplyOutcome.Skipped(SkipReason.UNSUPPORTED, null)
            val current = dao.trackRating(profileId, songId, provider)
                ?: return@withTransaction ApplyOutcome.Applied(null)
            val currentHash = projection(current.songId, current.provider, current.rating).hashOrNull()
            if (currentHash != expectedLocalHash) {
                return@withTransaction ApplyOutcome.Skipped(SkipReason.CHANGED_LOCALLY, currentHash)
            }
            dao.deleteTrackRating(profileId, songId, provider)
            ApplyOutcome.Applied(null)
        }
    }

    private fun projection(songId: String, provider: String, rating: Float?) = JsonObject(
        buildMap {
            put("songId", jsonString(songId))
            put("provider", jsonString(provider))
            put("rating", jsonRating(rating ?: 0f))
            if (rating == 0f) put(ZERO, JsonPrimitive(true))
        },
    )

    internal companion object {
        /** Marks a real 0.0 score; a bare `rating: 0` is "not rated". */
        const val ZERO = "zero"

        /** Provider-native 0..5 stars from Yoin's 0..10 scale (none: 0), as the repository computes it. */
        fun serverRatingFor(rating: Float?): Int = ((rating ?: 0f) / 2).roundToInt().coerceIn(0, 5)

        /** The local rating a synced [wire] value means: 0 is "not rated" unless the payload marks it a score. */
        fun ratingFrom(wire: Float, payload: JsonObject): Float? = when {
            wire != 0f -> wire
            (payload[ZERO] as? JsonPrimitive)?.booleanOrNull == true -> 0f
            else -> null
        }
    }
}
