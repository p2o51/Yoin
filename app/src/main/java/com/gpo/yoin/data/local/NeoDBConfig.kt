package com.gpo.yoin.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 单行配置，对应 NeoDB BYOK。[instance] 是实例域名
 * （默认 `https://neodb.social`），[accessToken] 是用户在
 * NeoDB → Settings → Developer 创建的 personal access token。
 *
 * 不和 [Profile] 绑 —— NeoDB 账号是跨 music provider 共享的。
 */
@Entity(tableName = "neodb_config")
data class NeoDBConfig(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(defaultValue = DEFAULT_INSTANCE)
    val instance: String = DEFAULT_INSTANCE,
    @ColumnInfo(defaultValue = "")
    val accessToken: String = "",
) {
    companion object {
        const val DEFAULT_INSTANCE = "https://neodb.social"
    }
}
