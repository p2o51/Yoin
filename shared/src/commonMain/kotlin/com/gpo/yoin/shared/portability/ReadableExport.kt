package com.gpo.yoin.shared.portability

import com.gpo.yoin.shared.backup.PlaylistBackupCodec
import com.gpo.yoin.shared.sync.SyncFormat
import com.gpo.yoin.shared.sync.SyncKinds
import com.gpo.yoin.shared.sync.WireRecord
import com.gpo.yoin.shared.sync.adapters.Field
import com.gpo.yoin.shared.sync.adapters.optionalLong
import com.gpo.yoin.shared.sync.adapters.rating
import com.gpo.yoin.shared.sync.adapters.requiredLong
import com.gpo.yoin.shared.sync.adapters.requiredString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** One text file of a backup: its path inside the zip and its UTF-8 text. */
data class BackupTextFile(val path: String, val text: String)

/** A name for an album or song, as far as this device knows it. */
data class ReadableAlbum(val name: String, val artist: String?)

data class ReadableTrack(val title: String, val artist: String?, val albumId: String?)

/**
 * Names for the readable part of a backup. Records carry ids, not names (a rating says "song 123"); the app
 * looks them up in what it already has (its own plays), never on the network. [localProfileId] is null for
 * accounts the device only knows from Drive.
 */
interface ReadableNames {
    suspend fun album(localProfileId: String?, provider: String, albumId: String): ReadableAlbum?

    suspend fun track(localProfileId: String?, provider: String, trackId: String): ReadableTrack?

    object None : ReadableNames {
        override suspend fun album(localProfileId: String?, provider: String, albumId: String): ReadableAlbum? = null

        override suspend fun track(localProfileId: String?, provider: String, trackId: String): ReadableTrack? = null
    }
}

/** Wording of the readable Markdown. "Memories" stays English in both (docs/i18n/zh-glossary.md). */
data class ReadableLabels(
    val account: String,
    val rating: String,
    val yourTitle: String,
    val aiTitle: String,
    val review: String,
    val albumNotes: String,
    val songs: String,
    val notes: String,
    val unknownAlbum: String,
    val unknownSong: String,
    val songsWithoutAlbum: String,
    val songsWithoutAlbumFile: String,
    val playlistBackups: String,
    val playlistBackupsFile: String,
    val tracksCount: String,
    val lastBackedUp: String,
    val from: String,
    val notRated: String,
    val writtenAt: String,
) {
    companion object {
        val English = ReadableLabels(
            account = "Account",
            rating = "Rating",
            yourTitle = "Your title",
            aiTitle = "Yoin's title",
            review = "Review",
            albumNotes = "Album notes",
            songs = "Songs",
            notes = "Notes",
            unknownAlbum = "Album",
            unknownSong = "Song",
            songsWithoutAlbum = "Songs whose album Yoin doesn't know",
            songsWithoutAlbumFile = "_songs-without-album",
            playlistBackups = "Playlist backups",
            playlistBackupsFile = "playlist-backups",
            tracksCount = "songs",
            lastBackedUp = "Last backed up",
            from = "From",
            notRated = "not rated",
            writtenAt = "Written",
        )

        val Chinese = ReadableLabels(
            account = "账号",
            rating = "评分",
            yourTitle = "你起的标题",
            aiTitle = "Yoin 的标题",
            review = "乐评",
            albumNotes = "专辑笔记",
            songs = "歌曲",
            notes = "笔记",
            unknownAlbum = "专辑",
            unknownSong = "歌曲",
            songsWithoutAlbum = "还不知道专辑的歌曲",
            songsWithoutAlbumFile = "_未归入专辑的歌曲",
            playlistBackups = "歌单备份",
            playlistBackupsFile = "歌单备份",
            tracksCount = "首",
            lastBackedUp = "最后备份",
            from = "来自",
            notRated = "未评分",
            writtenAt = "写于",
        )

        /** Chinese for a "zh" language code (any region or script), English otherwise. */
        fun forLanguage(language: String): ReadableLabels =
            if (language.substringBefore('-').substringBefore('_').lowercase() == "zh") Chinese else English
    }
}

/**
 * The readable/ part of a backup: per account a folder, per album one Markdown file (album → its songs with
 * scores and timestamped notes, the review, album notes, titles), songs whose album is unknown in one more
 * file, and readable/global/ for the playlist backups. Pure apart from [ReadableNames]; never read back.
 */
object ReadableExport {
    private const val ENTITY_ALBUM = "album"

    private data class TrackKey(val provider: String, val trackId: String)

    private data class AlbumKey(val provider: String, val albumId: String)

    private class Song(val key: TrackKey) {
        var title: String? = null
        var artist: String? = null
        var rating: Float? = null
        var ratedAt: Long? = null
        val notes = ArrayList<JsonObject>()
    }

    private class AlbumPage(val key: AlbumKey) {
        var rating: Float? = null
        var review: String? = null
        var reviewAt: Long? = null
        var userTitle: String? = null
        var aiTitle: String? = null
        var noteName: String? = null
        var noteArtist: String? = null
        val albumNotes = ArrayList<JsonObject>()
        val songs = LinkedHashMap<TrackKey, Song>()
        val isEmpty: Boolean
            get() = rating == null && review == null && userTitle == null && aiTitle == null &&
                albumNotes.isEmpty() && songs.isEmpty()
    }

    /**
     * [accounts] as [BackupContent.accounts] lists them; each gets [BackupAccount.readableDir] filled in.
     * Dates are written in the time zone [zoneId] (see [BackupDates]).
     */
    suspend fun render(
        accounts: List<BackupAccount>,
        localProfiles: Map<String, String>,
        records: List<WireRecord>,
        names: ReadableNames,
        labels: ReadableLabels,
        zoneId: String,
    ): Pair<List<BackupTextFile>, List<BackupAccount>> {
        val files = ArrayList<BackupTextFile>()
        val usedDirs = HashSet<String>()
        val live = records.filter { !it.deleted && it.payload != null }
        val byScope = live.groupBy { it.scope }
        val dated = { ms: Long -> BackupDates.dateTime(ms, zoneId) }
        val updated = accounts.map { account ->
            val scoped = byScope[account.scope].orEmpty() +
                live.filter { it.kind == SyncKinds.ACCOUNT && it.key == account.scope }
            val pages = pagesOf(localProfiles[account.scope], scoped, names)
            if (pages.isEmpty()) return@map account
            val dir = unique(sanitize(account.displayName.ifBlank { account.scope }), usedDirs)
            val usedFiles = HashSet<String>()
            for (page in pages) {
                val profileId = localProfiles[account.scope]
                val known = if (page.key.albumId.isEmpty()) {
                    null
                } else {
                    names.album(profileId, page.key.provider, page.key.albumId)
                }
                val title = known?.name ?: page.noteName?.takeIf(String::isNotBlank)
                val artist = known?.artist ?: page.noteArtist?.takeIf(String::isNotBlank)
                val fileName = when {
                    page.key.albumId.isEmpty() -> labels.songsWithoutAlbumFile
                    title != null -> listOfNotNull(artist, title).joinToString(" - ")
                    else -> "${labels.unknownAlbum} ${page.key.albumId}"
                }
                val text = albumMarkdown(page, title, artist, account, labels, dated)
                files += BackupTextFile("${BackupFormat.READABLE_DIR}$dir/${unique(sanitize(fileName), usedFiles)}.md", text)
            }
            account.copy(readableDir = "${BackupFormat.READABLE_DIR}$dir/")
        }
        playlistMarkdown(live.filter { it.kind == SyncKinds.PLAYLIST_BACKUP }, labels, dated)?.let {
            files += BackupTextFile("${BackupFormat.READABLE_GLOBAL_DIR}${sanitize(labels.playlistBackupsFile)}.md", it)
        }
        return files to updated
    }

    private suspend fun pagesOf(
        profileId: String?,
        records: List<WireRecord>,
        names: ReadableNames,
    ): List<AlbumPage> {
        val pages = LinkedHashMap<AlbumKey, AlbumPage>()
        fun page(key: AlbumKey) = pages.getOrPut(key) { AlbumPage(key) }
        val links = HashMap<TrackKey, String>()
        records.filter { it.kind == BackupKinds.TRACK_ALBUM_LINK }.forEach { record ->
            val payload = record.payload!!
            val provider = payload.requiredString(BackupFields.PROVIDER) ?: return@forEach
            val trackId = payload.requiredString(BackupFields.TRACK_ID) ?: return@forEach
            payload.requiredString(BackupFields.ALBUM_ID)?.takeIf(String::isNotBlank)?.let { links[TrackKey(provider, trackId)] = it }
        }
        val songs = LinkedHashMap<TrackKey, Song>()
        for (record in records.sortedBy { it.ts }) {
            val payload = record.payload!!
            when (record.kind) {
                SyncKinds.SONG_NOTE -> {
                    val key = TrackKey(payload.requiredString("provider") ?: continue, payload.requiredString("trackId") ?: continue)
                    val song = songs.getOrPut(key) { Song(key) }
                    song.title = song.title ?: payload.requiredString("title")?.takeIf(String::isNotBlank)
                    song.artist = song.artist ?: payload.requiredString("artist")?.takeIf(String::isNotBlank)
                    song.notes += payload
                }
                SyncKinds.TRACK_RATING -> {
                    val key = TrackKey(payload.requiredString("provider") ?: continue, payload.requiredString("songId") ?: continue)
                    val wire = payload.rating("rating") ?: continue
                    val zero = (payload["zero"] as? JsonPrimitive)?.booleanOrNull == true
                    if (wire == 0f && !zero) continue
                    songs.getOrPut(key) { Song(key) }.apply {
                        rating = wire
                        ratedAt = record.ts
                    }
                }
                SyncKinds.ALBUM_RATING -> albumKey(record)?.let { key ->
                    payload.rating("rating")?.takeIf { it > 0f }?.let { page(key).rating = it }
                }
                SyncKinds.ALBUM_REVIEW -> albumKey(record)?.let { key ->
                    payload.requiredString("review")?.takeIf(String::isNotBlank)?.let {
                        page(key).review = it
                        page(key).reviewAt = record.ts
                    }
                }
                SyncKinds.ALBUM_TITLE -> albumKey(record)?.let { key ->
                    payload.requiredString("title")?.takeIf(String::isNotBlank)?.let { page(key).userTitle = it }
                }
                SyncKinds.MEMORY_TITLE -> {
                    val parts = record.key.split(SyncFormat.KEY_SEPARATOR)
                    if (parts.size == 3 && parts[1] == ENTITY_ALBUM) {
                        payload.requiredString("title")?.takeIf(String::isNotBlank)?.let {
                            page(AlbumKey(parts[0], parts[2])).aiTitle = it
                        }
                    }
                }
                SyncKinds.ALBUM_NOTE -> {
                    val provider = payload.requiredString(BackupFields.PROVIDER) ?: continue
                    val albumId = payload.requiredString(BackupFields.ALBUM_ID) ?: continue
                    page(AlbumKey(provider, albumId)).apply {
                        albumNotes += payload
                        noteName = noteName ?: payload.requiredString(BackupFields.ALBUM_NAME)
                        noteArtist = noteArtist ?: payload.requiredString(BackupFields.ARTIST)
                    }
                }
            }
        }
        val loose = AlbumPage(AlbumKey("", ""))
        for ((key, song) in songs) {
            val known = names.track(profileId, key.provider, key.trackId)
            song.title = song.title ?: known?.title
            song.artist = song.artist ?: known?.artist
            val albumId = links[key] ?: known?.albumId?.takeIf(String::isNotBlank)
            val target = if (albumId == null) loose else page(AlbumKey(key.provider, albumId))
            target.songs[key] = song
        }
        val out = pages.values.filterNot { it.isEmpty }.toMutableList()
        if (loose.songs.isNotEmpty()) out += loose
        return out
    }

    private fun albumKey(record: WireRecord): AlbumKey? {
        val parts = record.key.split(SyncFormat.KEY_SEPARATOR)
        return if (parts.size == 2) AlbumKey(parts[0], parts[1]) else null
    }

    private fun albumMarkdown(
        page: AlbumPage,
        title: String?,
        artist: String?,
        account: BackupAccount,
        labels: ReadableLabels,
        dated: (Long) -> String,
    ): String = buildString {
        if (page.key.albumId.isEmpty()) {
            appendLine("# ${labels.songsWithoutAlbum}")
        } else {
            appendLine("# ${page.userTitle ?: title ?: "${labels.unknownAlbum} ${page.key.albumId}"}")
            if (page.userTitle != null && title != null) appendLine("_${title}_")
            if (artist != null) appendLine(artist)
        }
        appendLine()
        appendLine("- ${labels.account}: ${account.displayName} (${account.provider})")
        page.rating?.let { appendLine("- ${labels.rating}: ${score(it)} / 10") }
        page.userTitle?.let { appendLine("- ${labels.yourTitle}: $it") }
        page.aiTitle?.let { appendLine("- ${labels.aiTitle}: $it") }
        page.review?.let { review ->
            appendLine()
            appendLine("## ${labels.review}")
            appendLine()
            appendLine(review.trim())
            page.reviewAt?.let {
                appendLine()
                appendLine("_${labels.writtenAt} ${dated(it)}_")
            }
        }
        if (page.albumNotes.isNotEmpty()) {
            appendLine()
            appendLine("## ${labels.albumNotes}")
            for (note in page.albumNotes.sortedBy { it.requiredLong(BackupFields.CREATED_AT) ?: 0L }) {
                appendLine()
                appendLine("### ${dated(note.requiredLong(BackupFields.CREATED_AT) ?: 0L)}")
                appendLine()
                appendLine(note.requiredString(BackupFields.CONTENT).orEmpty().trim())
            }
        }
        if (page.songs.isNotEmpty()) {
            appendLine()
            appendLine("## ${labels.songs}")
            for (song in page.songs.values.sortedBy { (it.title ?: it.key.trackId).lowercase() }) {
                appendLine()
                val name = song.title ?: "${labels.unknownSong} ${song.key.trackId}"
                appendLine("### $name" + (song.artist?.let { " — $it" } ?: ""))
                appendLine()
                appendLine("- ${labels.rating}: " + (song.rating?.let { "${score(it)} / 10" } ?: labels.notRated))
                if (song.notes.isNotEmpty()) {
                    appendLine("- ${labels.notes}:")
                    for (note in song.notes.sortedBy { it.requiredLong("createdAt") ?: 0L }) {
                        val at = dated(note.requiredLong("createdAt") ?: 0L)
                        val position = (note.optionalLong("positionMs") as? Field.Ok)?.value?.let { " · ${clock(it)}" }.orEmpty()
                        val lines = note.requiredString("content").orEmpty().trim().lines()
                        appendLine("  - $at$position")
                        lines.forEach { appendLine("    > $it") }
                    }
                }
            }
        }
    }

    private fun playlistMarkdown(records: List<WireRecord>, labels: ReadableLabels, dated: (Long) -> String): String? {
        if (records.isEmpty()) return null
        return buildString {
            appendLine("# ${labels.playlistBackups}")
            for (record in records.sortedBy { it.payload?.requiredString("name")?.lowercase() }) {
                val payload = record.payload ?: continue
                val content = payload.requiredString("content")?.let { PlaylistBackupCodec.decodeContentOrNull(it) }
                appendLine()
                appendLine("## ${payload.requiredString("name").orEmpty()}")
                appendLine()
                appendLine(
                    "- ${labels.from}: ${payload.requiredString("sourceProfileLabel").orEmpty()} " +
                        "(${payload.requiredString("sourceProvider").orEmpty()})",
                )
                content?.let { appendLine("- ${it.tracks.size} ${labels.tracksCount}") }
                payload.requiredLong("lastBackedUpAt")?.let { appendLine("- ${labels.lastBackedUp}: ${dated(it)}") }
                content?.description?.takeIf(String::isNotBlank)?.let {
                    appendLine()
                    appendLine(it.trim())
                }
                if (content != null && content.tracks.isNotEmpty()) {
                    appendLine()
                    content.tracks.forEachIndexed { index, track ->
                        val credit = track.artists.joinToString(", ").takeIf(String::isNotBlank)?.let { " — $it" }.orEmpty()
                        appendLine("${index + 1}. ${track.title}$credit")
                    }
                }
            }
        }
    }

    /** "8", "7.5": whole scores without a decimal, others to one decimal, rounded half up (Java's %.1f). */
    internal fun score(value: Float): String = if (value % 1f == 0f) value.toInt().toString() else oneDecimal(value)

    /** Java's `String.format(Locale.ROOT, "%.1f", value)`: the shortest decimal of the double, half up. */
    private fun oneDecimal(value: Float): String {
        val text = value.toDouble().toString()
        val negative = text.startsWith('-')
        val body = text.removePrefix("-")
        if (body.any { it == 'E' || it == 'e' } || body.any { !it.isDigit() && it != '.' }) {
            val tenths = kotlin.math.floor(kotlin.math.abs(value.toDouble()) * 10 + 0.5).toLong()
            return (if (negative && tenths != 0L) "-" else "") + "${tenths / 10}.${tenths % 10}"
        }
        val dot = body.indexOf('.')
        val whole = if (dot < 0) body else body.substring(0, dot)
        val fraction = if (dot < 0) "" else body.substring(dot + 1)
        var tenths = whole.toLong() * 10 + (fraction.getOrElse(0) { '0' } - '0')
        if (fraction.getOrElse(1) { '0' } >= '5') tenths += 1
        return (if (negative) "-" else "") + "${tenths / 10}.${tenths % 10}"
    }

    /** "m:ss" of a position in ms (minutes not wrapped into hours). */
    internal fun clock(ms: Long): String {
        val total = ms / 1000
        return "${total / 60}:" + (total % 60).toString().padStart(2, '0')
    }

    /** A file or folder name that is safe on every file system and zip tool, at most 80 characters. */
    fun sanitize(name: String): String {
        val cleaned = buildString {
            for (char in name.trim()) {
                append(if (char < ' ' || char in "/\\:*?\"<>|") '_' else char)
            }
        }.trim().trim('.').take(80).trim()
        return cleaned.ifEmpty { "_" }
    }

    private fun unique(name: String, used: MutableSet<String>): String {
        if (used.add(name.lowercase())) return name
        var n = 2
        while (!used.add("$name ($n)".lowercase())) n++
        return "$name ($n)"
    }
}
