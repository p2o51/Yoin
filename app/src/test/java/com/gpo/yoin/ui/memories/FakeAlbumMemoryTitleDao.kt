package com.gpo.yoin.ui.memories

import com.gpo.yoin.data.local.AlbumMemoryTitle
import com.gpo.yoin.data.local.AlbumMemoryTitleDao
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** An in-memory [AlbumMemoryTitleDao] for JVM tests; [failWrites] makes every write throw. */
internal class FakeAlbumMemoryTitleDao : AlbumMemoryTitleDao {
    val rows = MutableStateFlow<List<AlbumMemoryTitle>>(emptyList())
    var failWrites = false

    private fun AlbumMemoryTitle.matches(profileId: String, provider: String, albumId: String) =
        this.profileId == profileId && this.provider == provider && this.albumId == albumId

    override fun observe(profileId: String, provider: String, albumId: String): Flow<AlbumMemoryTitle?> =
        rows.map { list -> list.firstOrNull { it.matches(profileId, provider, albumId) } }

    override suspend fun get(profileId: String, provider: String, albumId: String): AlbumMemoryTitle? =
        rows.value.firstOrNull { it.matches(profileId, provider, albumId) }

    override suspend fun upsert(title: AlbumMemoryTitle) {
        if (failWrites) throw IOException("disk full")
        rows.value = rows.value.filterNot { it.matches(title.profileId, title.provider, title.albumId) } + title
    }

    override suspend fun delete(profileId: String, provider: String, albumId: String): Int {
        if (failWrites) throw IOException("disk full")
        val before = rows.value.size
        rows.value = rows.value.filterNot { it.matches(profileId, provider, albumId) }
        return before - rows.value.size
    }

    override suspend fun getAllForProfile(profileId: String): List<AlbumMemoryTitle> =
        rows.value.filter { it.profileId == profileId }

    override fun observeAllForProfile(profileId: String): Flow<List<AlbumMemoryTitle>> =
        rows.map { list -> list.filter { it.profileId == profileId } }
}
