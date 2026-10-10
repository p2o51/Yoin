package com.gpo.yoin.ui.library

import android.content.Context
import androidx.core.content.edit
import com.gpo.yoin.data.repository.LibraryRecents
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What a Library cell opens. */
enum class LibraryOpenKind { Artist, Album, Playlist }

/**
 * When this profile last opened each artist, album and playlist from Library,
 * by the id Library lists it under: the half of Recents ("last opened") that
 * Yoin's own activity rows can't give Library.
 * - Apple Music's library search lists albums by library id (`library:l.…`)
 *   and opens them as their catalog albums, whose pages record visits and
 *   plays under the catalog id, which Library can't match back.
 * - A playlist page records no visit at all (activity_events would put it in
 *   Home's Activities); only playing from it does.
 *
 * Kept on this device, out of Room and the cloud sync: the newest
 * [MAX_PER_PROFILE] per profile and service.
 */
interface LibraryOpenStore {
    fun observe(profileId: String, provider: String): Flow<LibraryRecents>

    suspend fun recordOpened(profileId: String, provider: String, kind: LibraryOpenKind, rawId: String, at: Long)

    /** Forgets everything [profileId] opened, on every service (the profile was deleted). */
    suspend fun clear(profileId: String)

    /** Process-local, for previews and tests. */
    class InMemory : LibraryOpenStore {
        private val opened = MutableStateFlow<Map<String, Long>>(emptyMap())

        override fun observe(profileId: String, provider: String): Flow<LibraryRecents> =
            opened.map { entries -> libraryRecentsFromOpens(entries, profileId, provider) }.distinctUntilChanged()

        override suspend fun recordOpened(
            profileId: String,
            provider: String,
            kind: LibraryOpenKind,
            rawId: String,
            at: Long
        ) {
            opened.update { entries ->
                entries.withOpened(openPrefix(profileId, provider), openKey(profileId, provider, kind, rawId), at)
            }
        }

        override suspend fun clear(profileId: String) {
            opened.update { entries -> entries.filterKeys { key -> !key.startsWith("$profileId/") } }
        }
    }

    companion object {
        const val MAX_PER_PROFILE = 300
    }
}

/**
 * [LibraryOpenStore] in its own SharedPreferences file (`yoin_library_opens`):
 * out of Room and the cloud sync. Auto Backup leaves it out on Android 12+
 * (data_extraction_rules.xml lists only what it backs up); Android 8–11 back
 * up every SharedPreferences file, this one too (its keys are profile ids,
 * restored with the database).
 */
class SharedPrefsLibraryOpenStore(
    context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO
) : LibraryOpenStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val changes = MutableStateFlow(0L)
    private val writes = Mutex()

    override fun observe(profileId: String, provider: String): Flow<LibraryRecents> = changes
        .map { libraryRecentsFromOpens(storedOpens(), profileId, provider) }
        .flowOn(io)
        .distinctUntilChanged()

    override suspend fun recordOpened(
        profileId: String,
        provider: String,
        kind: LibraryOpenKind,
        rawId: String,
        at: Long
    ) {
        // One read-trim-write at a time, so two quick opens can't outgrow the cap.
        writes.withLock {
            withContext(io) {
                val prefix = openPrefix(profileId, provider)
                val key = openKey(profileId, provider, kind, rawId)
                val own = storedOpens().filterKeys { it.startsWith(prefix) }
                val kept = own.withOpened(prefix, key, at)
                prefs.edit {
                    kept[key]?.let { putLong(key, it) }
                    (own.keys - kept.keys).forEach(::remove)
                }
            }
        }
        changes.update { it + 1 }
    }

    override suspend fun clear(profileId: String) {
        writes.withLock {
            withContext(io) {
                val own = prefs.all.keys.filter { key -> key.startsWith("$profileId/") }
                if (own.isNotEmpty()) prefs.edit { own.forEach(::remove) }
            }
        }
        changes.update { it + 1 }
    }

    private fun storedOpens(): Map<String, Long> =
        prefs.all.mapNotNull { (key, value) -> (value as? Long)?.let { key to it } }.toMap()

    private companion object {
        const val PREFS_NAME = "yoin_library_opens"
    }
}

/** `profile/provider/Kind/rawId`: the raw id last, so a `/` inside it stays in it. */
private fun openKey(profileId: String, provider: String, kind: LibraryOpenKind, rawId: String): String =
    openPrefix(profileId, provider) + kind.name + "/" + rawId

private fun openPrefix(profileId: String, provider: String): String = "$profileId/$provider/"

/**
 * [this] with [key] opened [at] (a later open of it stays), keeping only the
 * newest [LibraryOpenStore.MAX_PER_PROFILE] under [prefix], its profile and service.
 */
private fun Map<String, Long>.withOpened(prefix: String, key: String, at: Long): Map<String, Long> {
    val updated = this + (key to maxOf(at, this[key] ?: Long.MIN_VALUE))
    val own = updated.filterKeys { it.startsWith(prefix) }
    if (own.size <= LibraryOpenStore.MAX_PER_PROFILE) return updated
    val dropped = own.entries
        .sortedByDescending { it.value }
        .drop(LibraryOpenStore.MAX_PER_PROFILE)
        .mapTo(HashSet()) { it.key }
    return updated - dropped
}

internal fun libraryRecentsFromOpens(entries: Map<String, Long>, profileId: String, provider: String): LibraryRecents {
    val prefix = openPrefix(profileId, provider)
    val albums = HashMap<String, Long>()
    val artists = HashMap<String, Long>()
    val playlists = HashMap<String, Long>()
    entries.forEach { (key, at) ->
        if (!key.startsWith(prefix)) return@forEach
        val rest = key.substring(prefix.length)
        val kind = rest.substringBefore('/', missingDelimiterValue = "")
        val rawId = rest.substringAfter('/', missingDelimiterValue = "")
        if (rawId.isEmpty()) return@forEach
        when (kind) {
            LibraryOpenKind.Album.name -> albums[rawId] = at
            LibraryOpenKind.Artist.name -> artists[rawId] = at
            LibraryOpenKind.Playlist.name -> playlists[rawId] = at
        }
    }
    return LibraryRecents(albums = albums, artists = artists, playlists = playlists)
}
