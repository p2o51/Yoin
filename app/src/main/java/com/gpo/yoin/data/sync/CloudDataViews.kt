package com.gpo.yoin.data.sync

import com.gpo.yoin.data.backup.PlaylistBackupCodec
import com.gpo.yoin.data.sync.adapters.requiredLong
import com.gpo.yoin.data.sync.adapters.requiredString
import com.gpo.yoin.data.sync.backup.ReadableNames
import com.gpo.yoin.data.transfer.memories.MemoryItems
import com.gpo.yoin.data.transfer.memories.SourceRef

/*
 * Read-only views of what Cloud sync holds, for the 云同步 page's 云端数据 section (docs/cloud-sync.md):
 * Yoin's Drive files per device, per-account counts, and a browser of one account's (or the global) records.
 * Built from this device's sync replica, which mirrors what every device published; nothing here writes.
 */

/** One Yoin file in the sync location. [kind] is the file's [SyncFileProps.KIND]. */
data class CloudFile(
    val name: String,
    val sizeBytes: Long?,
    val kind: String,
    val modifiedTime: Long?,
)

/** One device's files in the sync location. */
data class CloudDeviceFiles(
    val deviceId: String,
    val deviceName: String,
    val isThisDevice: Boolean,
    val lastSyncAt: Long?,
    val files: List<CloudFile>,
) {
    val totalBytes: Long get() = files.sumOf { it.sizeBytes ?: 0L }
}

/** What one account holds in the cloud. [albums] = albums with a rating or a review. */
data class CloudScopeCounts(
    val notes: Int = 0,
    val albums: Int = 0,
    val songRatings: Int = 0,
    val memoryTitles: Int = 0,
    /** Songs with a note, a rating or an album link. */
    val songsWithData: Int = 0,
) {
    val total: Int get() = notes + albums + songRatings + memoryTitles
}

data class CloudGlobalCounts(
    val playlistBackups: Int = 0,
    val translations: Int = 0,
    val songAbout: Int = 0,
) {
    val total: Int get() = playlistBackups + translations + songAbout
}

/** Counts per account scope (syncProfileId) and of the global scope. */
data class CloudDataSummary(
    val scopes: Map<String, CloudScopeCounts> = emptyMap(),
    val global: CloudGlobalCounts = CloudGlobalCounts(),
)

data class CloudNote(
    val id: String,
    val title: String,
    val artist: String,
    val content: String,
    val positionMs: Long?,
    val updatedAt: Long,
)

/** An album's rating and review together. Names are null when nothing on this device knows them. */
data class CloudAlbumEntry(
    val albumId: String,
    val name: String?,
    val artist: String?,
    val rating: Float?,
    val review: String?,
    val updatedAt: Long,
)

data class CloudSongRating(
    val trackId: String,
    val title: String?,
    val artist: String?,
    val rating: Float,
    val updatedAt: Long,
)

/** A Memories title: the user's own ([custom]) or the AI's. [subject] names the album when known. */
data class CloudMemoryTitle(
    val title: String,
    val custom: Boolean,
    val subject: String?,
    val updatedAt: Long,
)

data class CloudAccountContent(
    val notes: List<CloudNote> = emptyList(),
    val albums: List<CloudAlbumEntry> = emptyList(),
    val songRatings: List<CloudSongRating> = emptyList(),
    val titles: List<CloudMemoryTitle> = emptyList(),
)

data class CloudPlaylistBackupEntry(
    val name: String,
    val trackCount: Int,
    val provider: String,
    val accountLabel: String,
    val backedUpAt: Long?,
)

data class CloudTranslationEntry(
    val title: String?,
    val artist: String?,
    val trackId: String,
    val language: String,
)

data class CloudAboutEntry(
    val title: String,
    val artist: String,
    val question: String?,
    val answer: String,
    val updatedAt: Long?,
)

data class CloudGlobalContent(
    val playlistBackups: List<CloudPlaylistBackupEntry> = emptyList(),
    val translations: List<CloudTranslationEntry> = emptyList(),
    val about: List<CloudAboutEntry> = emptyList(),
)

/** Pure builders from sync records; unit-tested on their own. */
object CloudDataViews {

    fun summary(records: List<WireRecord>): CloudDataSummary {
        val live = records.filter { !it.deleted }
        val scopes = live.map { it.scope }.filter { it != SyncFormat.GLOBAL_SCOPE }.toSet()
        val byScope = scopes.associateWith { scope -> countsOf(MemoryItems.fromRecords(live, scope)) }
            .filterValues { it.total > 0 || it.songsWithData > 0 }
        val global = live.filter { it.scope == SyncFormat.GLOBAL_SCOPE }
        return CloudDataSummary(
            scopes = byScope,
            global = CloudGlobalCounts(
                playlistBackups = global.count { it.kind == SyncKinds.PLAYLIST_BACKUP },
                translations = translationKeys(global).size,
                songAbout = global.count { it.kind == SyncKinds.SONG_ABOUT },
            ),
        )
    }

    fun countsOf(items: MemoryItems): CloudScopeCounts = CloudScopeCounts(
        notes = items.songNotes.size,
        albums = (items.albumRatings.map { it.album } + items.albumReviews.map { it.album }).toSet().size,
        songRatings = items.trackRatings.size,
        memoryTitles = items.albumTitles.size + items.memoryTitles.size,
        songsWithData = items.tracks.size,
    )

    /**
     * One account's records for the browser, newest first. Song names come from the account's own notes, then
     * [names] (this device's plays, for an account signed in here); album names from [names].
     */
    suspend fun account(
        records: List<WireRecord>,
        scope: String,
        names: ReadableNames? = null,
        localProfileId: String? = null,
    ): CloudAccountContent {
        val items = MemoryItems.fromRecords(records, scope)
        val noted = items.songNotes.associate { it.track to (it.title to it.artist) }
        suspend fun trackName(ref: SourceRef): Pair<String?, String?> {
            noted[ref]?.let { (title, artist) -> if (title.isNotBlank()) return title to artist }
            val known = runCatching { names?.track(localProfileId, ref.provider, ref.id) }.getOrNull()
            return known?.title to known?.artist
        }
        suspend fun albumName(ref: SourceRef): Pair<String?, String?> {
            items.albumFacts[ref]?.let { return it.first to it.second }
            val known = runCatching { names?.album(localProfileId, ref.provider, ref.id) }.getOrNull()
            return known?.name to known?.artist
        }
        val albumRefs = LinkedHashSet<SourceRef>().apply {
            items.albumRatings.forEach { add(it.album) }
            items.albumReviews.forEach { add(it.album) }
        }
        val albums = albumRefs.map { ref ->
            val rating = items.albumRatings.firstOrNull { it.album == ref }
            val review = items.albumReviews.firstOrNull { it.album == ref }
            val (name, artist) = albumName(ref)
            CloudAlbumEntry(
                albumId = ref.id,
                name = name,
                artist = artist,
                rating = rating?.rating,
                review = review?.review,
                updatedAt = maxOf(rating?.updatedAt ?: 0L, review?.updatedAt ?: 0L),
            )
        }
        val songRatings = items.trackRatings.map { rating ->
            val (title, artist) = trackName(rating.track)
            CloudSongRating(rating.track.id, title, artist, rating.rating, rating.updatedAt)
        }
        val titles = items.albumTitles.map { title ->
            CloudMemoryTitle(title.title, custom = true, subject = albumName(title.album).first, updatedAt = title.updatedAt)
        } + items.memoryTitles.map { title ->
            val subject = if (title.entityType == ALBUM_ENTITY) albumName(SourceRef(title.provider, title.entityId)).first else null
            CloudMemoryTitle(title.title, custom = false, subject = subject, updatedAt = title.updatedAt)
        }
        return CloudAccountContent(
            notes = items.songNotes
                .map { CloudNote(it.id, it.title, it.artist, it.content, it.positionMs, it.updatedAt) }
                .sortedByDescending { it.updatedAt },
            albums = albums.sortedByDescending { it.updatedAt },
            songRatings = songRatings.sortedByDescending { it.updatedAt },
            titles = titles.sortedByDescending { it.updatedAt },
        )
    }

    /** One entry per song and language (several models may have translated the same song): what both views count. */
    private fun translationKeys(global: List<WireRecord>): Set<Pair<String, String>> = global
        .filter { it.kind == SyncKinds.LYRICS_TRANSLATION }
        .mapNotNull { record ->
            val p = record.payload ?: return@mapNotNull null
            val provider = p.requiredString("trackProvider") ?: return@mapNotNull null
            val rawId = p.requiredString("trackRawId") ?: return@mapNotNull null
            "$provider:$rawId" to p.requiredString("targetLanguage").orEmpty()
        }
        .toSet()

    /** The global scope's playlist backups, translations and song About entries, newest first. */
    fun global(records: List<WireRecord>): CloudGlobalContent {
        val live = records.filter { !it.deleted }
        val noted = HashMap<SourceRef, Pair<String, String>>()
        live.map { it.scope }.distinct().filter { it != SyncFormat.GLOBAL_SCOPE }.forEach { scope ->
            MemoryItems.fromRecords(live, scope).songNotes.forEach { note ->
                if (note.title.isNotBlank()) noted[note.track] = note.title to note.artist
            }
        }
        val global = live.filter { it.scope == SyncFormat.GLOBAL_SCOPE }
        val backups = global.filter { it.kind == SyncKinds.PLAYLIST_BACKUP }.mapNotNull { record ->
            val p = record.payload ?: return@mapNotNull null
            val name = p.requiredString("name") ?: return@mapNotNull null
            val tracks = p.requiredString("content")
                ?.let { runCatching { PlaylistBackupCodec.decodeContent(it).tracks.size }.getOrNull() } ?: 0
            CloudPlaylistBackupEntry(
                name = name,
                trackCount = tracks,
                provider = p.requiredString("sourceProvider").orEmpty(),
                accountLabel = p.requiredString("sourceProfileLabel").orEmpty(),
                backedUpAt = p.requiredLong("lastBackedUpAt") ?: record.ts,
            )
        }.sortedByDescending { it.backedUpAt ?: 0L }
        val translations = global.filter { it.kind == SyncKinds.LYRICS_TRANSLATION }.mapNotNull { record ->
            val p = record.payload ?: return@mapNotNull null
            val provider = p.requiredString("trackProvider") ?: return@mapNotNull null
            val rawId = p.requiredString("trackRawId") ?: return@mapNotNull null
            val known = noted[SourceRef(provider, rawId)]
            CloudTranslationEntry(known?.first, known?.second, "$provider:$rawId", p.requiredString("targetLanguage").orEmpty())
        }
            // One row per song and language (several models may have translated it).
            .distinctBy { it.trackId to it.language }
        val about = global.filter { it.kind == SyncKinds.SONG_ABOUT }.mapNotNull { record ->
            val p = record.payload ?: return@mapNotNull null
            CloudAboutEntry(
                title = p.requiredString("titleDisplay") ?: return@mapNotNull null,
                artist = p.requiredString("artistDisplay").orEmpty(),
                question = p.requiredString("titleText")?.takeIf(String::isNotBlank)
                    ?: p.requiredString("promptText")?.takeIf(String::isNotBlank),
                answer = p.requiredString("answerText").orEmpty(),
                updatedAt = p.requiredLong("updatedAt") ?: record.ts,
            )
        }.sortedByDescending { it.updatedAt ?: 0L }
        return CloudGlobalContent(backups, translations, about)
    }

    /**
     * Drive files per device, this device first, then by last sync. Reset and purge markers are listed under
     * the device that wrote them; a file without a device id is skipped.
     */
    fun deviceFiles(
        files: List<RemoteFile>,
        thisDeviceId: String,
        thisDeviceName: String,
        knownNames: Map<String, String?>,
        fallbackName: String,
    ): List<CloudDeviceFiles> = files
        .filter { it.appProperties[SyncFileProps.KIND] != null }
        .groupBy { it.appProperties[SyncFileProps.DEVICE_ID].orEmpty() }
        .filterKeys { it.isNotEmpty() }
        .map { (deviceId, own) ->
            val mine = deviceId == thisDeviceId
            val state = own.firstOrNull { it.appProperties[SyncFileProps.KIND] == SyncFileProps.KIND_STATE }
            CloudDeviceFiles(
                deviceId = deviceId,
                deviceName = if (mine) thisDeviceName else knownNames[deviceId]?.takeIf(String::isNotBlank) ?: fallbackName,
                isThisDevice = mine,
                lastSyncAt = state?.modifiedTime ?: own.maxOfOrNull { it.modifiedTime ?: 0L },
                files = own
                    .sortedWith(compareBy({ kindOrder(it.appProperties[SyncFileProps.KIND]) }, { it.name }))
                    .map { CloudFile(it.name, it.size, it.appProperties[SyncFileProps.KIND].orEmpty(), it.modifiedTime) },
            )
        }
        .sortedWith(compareByDescending<CloudDeviceFiles> { it.isThisDevice }.thenByDescending { it.lastSyncAt ?: 0L })

    private fun kindOrder(kind: String?): Int = when (kind) {
        SyncFileProps.KIND_STATE -> 0
        SyncFileProps.KIND_ARTIFACTS -> 1
        else -> 2
    }

    private const val ALBUM_ENTITY = "album"
}
