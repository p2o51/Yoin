package com.gpo.yoin.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface ExternalMappingDao {
    @Query(
        "SELECT * FROM external_mappings " +
            "WHERE provider = :provider " +
            "AND entityType = :entityType " +
            "AND entityId = :entityId " +
            "AND externalService = :service " +
            "LIMIT 1",
    )
    suspend fun get(
        provider: String,
        entityType: String,
        entityId: String,
        service: String,
    ): ExternalMapping?

    @Query(
        "SELECT * FROM external_mappings " +
            "WHERE externalService = :service AND externalId = :externalId " +
            "LIMIT 1",
    )
    suspend fun getByExternalId(
        service: String,
        externalId: String,
    ): ExternalMapping?

    @Upsert
    suspend fun upsert(mapping: ExternalMapping)

    @Query(
        "DELETE FROM external_mappings " +
            "WHERE provider = :provider " +
            "AND entityType = :entityType " +
            "AND entityId = :entityId " +
            "AND externalService = :service",
    )
    suspend fun delete(
        provider: String,
        entityType: String,
        entityId: String,
        service: String,
    )
}
