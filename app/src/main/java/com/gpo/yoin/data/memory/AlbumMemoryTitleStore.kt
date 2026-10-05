package com.gpo.yoin.data.memory

import com.gpo.yoin.data.local.AlbumMemoryTitle
import com.gpo.yoin.data.local.AlbumMemoryTitleDao
import com.gpo.yoin.data.model.MediaId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The user's own titles for album Memories (owner, 2026-10-05: "the AI title should leave room for the
 * user to change it"). Profile-aware through [activeProfileId] (ProfileManager.activeProfileId), as every
 * per-account store: reads follow the active profile, writes land in it, nothing ever crosses profiles.
 *
 * What a Memory shows is [resolveAlbumMemoryTitle]: the user's title here, else Yoin's AI title, else the
 * local motif, else the album name. Callers: Memories (card, diary, spread) and the album page's second
 * page. The cloud-sync adapter works on [AlbumMemoryTitleDao] directly under the same contract:
 *  - a title is stored exactly as entered, trimmed ([setTitle]); a blank one is a restore;
 *  - `updatedAt` is the user's edit time ([clock] at the write);
 *  - restoring Yoin's title is a hard delete of the row ([clearTitle]) — never a null title.
 *
 * Album ids are [MediaId]s: the row keys on (active profile, [MediaId.provider], raw id), the same raw id
 * the AI title's cache (memory_copy_cache) keys on.
 */
class AlbumMemoryTitleStore(
    private val dao: AlbumMemoryTitleDao,
    private val activeProfileId: StateFlow<String?>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The user's title for [albumId] in the active profile, live (follows a profile switch); null = none. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeTitle(albumId: MediaId): Flow<String?> = activeProfileId
        .flatMapLatest { profileId ->
            if (profileId.isNullOrBlank()) {
                flowOf(null)
            } else {
                dao.observe(profileId, albumId.provider, rawIdOf(albumId)).map { row -> row?.title }
            }
        }
        .distinctUntilChanged()

    /** One-shot read of [observeTitle]; null without an active profile. */
    suspend fun getTitle(albumId: MediaId): String? {
        val profileId = activeProfileId.value?.takeIf(String::isNotBlank) ?: return null
        return dao.get(profileId, albumId.provider, rawIdOf(albumId))?.title
    }

    /**
     * Every title of the active profile by album, live (Memories patches its open deck from it, so an edit
     * made on the album page or pulled by sync shows up there too). Empty without an active profile.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeTitles(): Flow<Map<MediaId, String>> = activeProfileId
        .flatMapLatest { profileId ->
            if (profileId.isNullOrBlank()) {
                flowOf(emptyMap())
            } else {
                dao.observeAllForProfile(profileId).map { rows ->
                    rows.associate { row -> MediaId(row.provider, row.albumId) to row.title }
                }
            }
        }
        .distinctUntilChanged()

    /**
     * The user names [albumId]'s Memory [title]: stored trimmed, with `updatedAt` = now. A blank title is a
     * restore ([clearTitle]); the same title as stored is not an edit and writes nothing (no new `updatedAt`
     * to sync). No-op without an active profile.
     */
    suspend fun setTitle(albumId: MediaId, title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) {
            clearTitle(albumId)
            return
        }
        val profileId = activeProfileId.value?.takeIf(String::isNotBlank) ?: return
        val rawId = rawIdOf(albumId)
        if (dao.get(profileId, albumId.provider, rawId)?.title == trimmed) return
        dao.upsert(
            AlbumMemoryTitle(
                profileId = profileId,
                provider = albumId.provider,
                albumId = rawId,
                title = trimmed,
                updatedAt = clock(),
            ),
        )
    }

    /** Back to Yoin's title: a hard delete of the row (the sync contract — never a null title). */
    suspend fun clearTitle(albumId: MediaId) {
        val profileId = activeProfileId.value?.takeIf(String::isNotBlank) ?: return
        dao.delete(profileId, albumId.provider, rawIdOf(albumId))
    }

    // a stored id may still carry the legacy "provider:" prefix; the row keys on the bare raw id
    private fun rawIdOf(albumId: MediaId): String = MediaId.storedRawId(albumId.provider, albumId.rawId)
}

/** Which title a Memory shows, in precedence order ([resolveAlbumMemoryTitle]). */
enum class AlbumMemoryTitleSource {
    /** The user's own title ([AlbumMemoryTitleStore]). */
    USER,

    /** Yoin's AI title (Gemini, cached in memory_copy_cache). */
    AI,

    /** Yoin's local motif line (no AI title). */
    MOTIF,

    /** Nothing else: the album name stands in. */
    ALBUM,
}

/**
 * The title a Memory shows and where it came from.
 *
 * [canRestoreGenerated]: the user's title is in use and Yoin has a title of its own (AI or motif) under it —
 * the only time a "Restore AI title" affordance means anything (restoring onto the album name alone is just
 * clearing the field).
 */
data class ResolvedTitle(
    val text: String,
    val source: AlbumMemoryTitleSource,
    val canRestoreGenerated: Boolean = false,
)

/**
 * A Memory's title: user > AI > motif > album name. Candidates are trimmed and blank ones don't count (a
 * blank user title is no title); the album name is used as given. Pure.
 */
fun resolveAlbumMemoryTitle(userTitle: String?, aiTitle: String?, motif: String?, albumName: String): ResolvedTitle {
    val user = userTitle?.trim()?.takeIf(String::isNotEmpty)
    val ai = aiTitle?.trim()?.takeIf(String::isNotEmpty)
    val motifLine = motif?.trim()?.takeIf(String::isNotEmpty)
    return when {
        user != null -> ResolvedTitle(
            text = user,
            source = AlbumMemoryTitleSource.USER,
            canRestoreGenerated = ai != null || motifLine != null,
        )
        ai != null -> ResolvedTitle(ai, AlbumMemoryTitleSource.AI)
        motifLine != null -> ResolvedTitle(motifLine, AlbumMemoryTitleSource.MOTIF)
        else -> ResolvedTitle(albumName, AlbumMemoryTitleSource.ALBUM)
    }
}
