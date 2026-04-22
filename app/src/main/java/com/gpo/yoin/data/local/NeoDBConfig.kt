package com.gpo.yoin.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 单行配置，对应 NeoDB 的 **非敏感** 参数（instance 域名）。access token
 * 不放这里 —— token 走
 * [com.gpo.yoin.data.integration.neodb.NeoDbTokenStore]，落在
 * `noBackupFilesDir/neodb/token.bin`，不会被 Android Auto Backup 打包。
 *
 * 拆开存的原因：Room 数据库整体被 `data_extraction_rules.xml` 包进云备份，
 * 评分 / 笔记这些用户数据需要备份；但 access token 跟设备绑定，跨设备
 * 恢复后应该强制重新登录，不该沾上备份流。
 *
 * 0.3 版本引入 accessToken 字段后随即意识到这个语义冲突，v12 migration
 * 把 accessToken 列清掉；token 迁到加密文件里。
 */
@Entity(tableName = "neodb_config")
data class NeoDBConfig(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(defaultValue = DEFAULT_INSTANCE)
    val instance: String = DEFAULT_INSTANCE,
) {
    companion object {
        const val DEFAULT_INSTANCE = "https://neodb.social"
    }
}
