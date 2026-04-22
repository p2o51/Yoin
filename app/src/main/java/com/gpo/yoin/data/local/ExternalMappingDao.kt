package com.gpo.yoin.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface ExternalMappingDao {
    /**
     * 反查：某个 Yoin 实体在某个外部服务下的映射。唯一 index 保证最多
     * 一条；查不到就返回 null，调用方走搜索兜底 / 新建。
     */
    @Query(
        "SELECT * FROM external_mappings " +
            "WHERE provider = :provider " +
            "AND entityType = :entityType " +
            "AND entityId = :entityId " +
            "AND externalService = :service " +
            "LIMIT 1",
    )
    suspend fun findForYoinEntity(
        provider: String,
        entityType: String,
        entityId: String,
        service: String,
    ): ExternalMapping?

    /**
     * 正查：某个外部 uuid 下挂的所有 Yoin 实体。用于推送前检测「跨 provider
     * 复用」—— 比如 Spotify 版推之前可以看看同一个 uuid 是否已经被 Subsonic
     * 版关联过，避免同一张专辑被当成两条独立记录处理。
     */
    @Query(
        "SELECT * FROM external_mappings " +
            "WHERE externalService = :service AND externalId = :externalId",
    )
    suspend fun findAllForExternalId(
        service: String,
        externalId: String,
    ): List<ExternalMapping>

    @Upsert
    suspend fun upsert(mapping: ExternalMapping)

    /**
     * 只解除某一条 Yoin ↔ external 的关联，不影响该 uuid 下挂着的其它 Yoin
     * 实体（和其它 provider 版的绑定照旧）。
     */
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
