package com.gpo.yoin.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.gpo.yoin.data.model.MediaId

/**
 * 专辑级笔记条目。与 [SongNote] 同形 —— 多条 per album，追加式写入，
 * 本地私密。聚合展示在 AlbumDetail / Memory；作为 NeoDB Review.body 的
 * 默认草稿来源（用户仍可手动改写 album_ratings.review）。
 */
@Entity(
    tableName = "album_notes",
    indices = [
        Index(value = ["albumId", "provider"]),
        Index(value = ["albumName", "artist"]),
    ],
)
data class AlbumNote(
    @PrimaryKey val id: String,
    val albumId: String,
    @ColumnInfo(defaultValue = MediaId.PROVIDER_SUBSONIC)
    val provider: String = MediaId.PROVIDER_SUBSONIC,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
    val albumName: String,
    val artist: String,
)

data class AlbumNoteKey(
    val albumId: String,
    val provider: String,
)
