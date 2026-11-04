package com.gpo.yoin.data.offline

import com.gpo.yoin.data.remote.SubsonicCacheKey
import com.gpo.yoin.data.remote.SubsonicStreamUrls
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Offline downloads (owner 2026-11-04, the 推广前调研 gap #1): a song, an album
 * or a playlist kept on the device until the listener removes it, played
 * without a network. Subsonic only: Spotify plays in its own app, and neither
 * Spotify nor Apple Music lets an app store their songs.
 *
 * Everything in this file is plain Kotlin with no Android in it: what to
 * download, at what quality, under which key, and how the downloads read as
 * albums and playlists. It is the part the iOS app takes over once :shared is
 * on main; Android runs it through Media3's DownloadManager (OfflineDownloads).
 */

/**
 * What a downloaded song is stored as (Settings › Downloads, like Spotify's
 * Download quality). [Original] is the file as it is on the server; the rest
 * ask the server for an mp3 at no more than [maxBitRateKbps], as streaming
 * does (constant bit rate, so a stored transcode seeks).
 */
enum class DownloadQuality(val key: String, val maxBitRateKbps: Int?) {
    Original("original", null),
    Kbps320("320", 320),
    Kbps192("192", 192),
    Kbps128("128", 128),
    ;

    /** The server's transcode format for this quality; null for the file as it is. */
    val format: String? get() = if (maxBitRateKbps == null) null else TRANSCODE_FORMAT

    companion object {
        /** Like streaming's: a cbr mp3 is what seeks with one request. */
        const val TRANSCODE_FORMAT = "mp3"

        fun fromKey(key: String?): DownloadQuality? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Settings › Downloads. The defaults follow Spotify's: a high quality you can
 * change, and nothing downloads over mobile data until the listener allows it.
 */
data class DownloadSettings(
    val quality: DownloadQuality = DefaultQuality,
    val overMobileData: Boolean = false,
) {
    companion object {
        val DefaultQuality = DownloadQuality.Kbps320
    }
}

/** Where a downloaded song came from: the album or playlist the listener downloaded, or the song alone. */
@Serializable
data class OfflineCollection(
    val kind: Kind,
    /** The album's or playlist's MediaId string; the song's own for [Kind.Song]. */
    val id: String,
    val title: String,
    val subtitle: String? = null,
    /** A CoverRef storage key (CoverRef.toStorageKey). */
    val cover: String? = null,
) {
    @Serializable
    enum class Kind { Album, Playlist, Song }

    /** What a download is held under: one entry per album, playlist, or lone song. */
    val key: String get() = "${kind.name.lowercase()}:$id"
}

/**
 * What a download carries besides its bytes (Media3 DownloadRequest.data):
 * enough to list it with no network and to play it as a Track. It holds no
 * credential: the stream url is signed only when the download runs.
 */
@Serializable
data class OfflinePayload(
    val version: Int = VERSION,
    /** The profile the song was downloaded from; another profile's downloads wait. */
    val profileId: String,
    /** The song's MediaId string. */
    val songId: String,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumId: String? = null,
    val cover: String? = null,
    val durationSec: Int? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val quality: String = DownloadSettings.DefaultQuality.key,
    /** Every album, playlist or lone-song download that holds this song; it goes when the last one does. */
    val collections: List<OfflineCollection> = emptyList(),
) {
    fun encode(): ByteArray = json.encodeToString(serializer(), this).encodeToByteArray()

    val downloadQuality: DownloadQuality get() = DownloadQuality.fromKey(quality) ?: DownloadSettings.DefaultQuality

    /** This payload also held by [collection] (its metadata refreshed). */
    fun withCollection(collection: OfflineCollection): OfflinePayload =
        copy(collections = collections.filterNot { it.key == collection.key } + collection)

    /** This payload without [collectionKey]; null when nothing holds the song any more. */
    fun withoutCollection(collectionKey: String): OfflinePayload? =
        copy(collections = collections.filterNot { it.key == collectionKey }).takeIf { it.collections.isNotEmpty() }

    companion object {
        const val VERSION = 1

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun decode(bytes: ByteArray?): OfflinePayload? = bytes
            ?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { json.decodeFromString(serializer(), it.decodeToString()) }.getOrNull() }
    }
}

/**
 * The keys a downloaded song is stored and found by. The key is the song on
 * its server for its user ([SubsonicCacheKey] of the plain stream url, with no
 * transcode in it), so a song downloaded at 320 is found whatever the
 * streaming quality is now, and two accounts never share one.
 */
object OfflineKeys {
    private const val PREFIX = "offline:"

    /** The download key of the song a signed stream [url] plays; null when [url] is not one. */
    fun of(streamUrl: String): String? {
        if (SubsonicStreamUrls.songIdOf(streamUrl) == null) return null
        val plain = SubsonicStreamUrls.withTranscode(streamUrl, maxBitRateKbps = null, format = null)
        return SubsonicCacheKey.of(plain)?.let { PREFIX + it }
    }

    fun isOfflineKey(key: String?): Boolean = key?.startsWith(PREFIX) == true
}

/**
 * The address a download is queued under, in place of the signed stream url:
 * it names the song, the key and the quality, and holds no credential.
 * [OfflineDownloads] turns it into a signed url as the download opens.
 */
object OfflineRequestUri {
    const val SCHEME = "yoin-offline"
    private const val PREFIX = "$SCHEME://song?"

    fun build(songRawId: String, key: String, quality: DownloadQuality): String =
        PREFIX + "id=" + encode(songRawId) + "&q=" + quality.key + "&k=" + encode(key)

    data class Parts(val songRawId: String, val key: String, val quality: DownloadQuality)

    fun parse(uri: String): Parts? {
        if (!uri.startsWith(PREFIX)) return null
        val params = uri.removePrefix(PREFIX).split('&').mapNotNull { pair ->
            val at = pair.indexOf('=')
            if (at <= 0) null else pair.substring(0, at) to decode(pair.substring(at + 1))
        }.toMap()
        val id = params["id"]?.takeIf(String::isNotEmpty) ?: return null
        val key = params["k"]?.takeIf(String::isNotEmpty) ?: return null
        val quality = DownloadQuality.fromKey(params["q"]) ?: return null
        return Parts(id, key, quality)
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private fun decode(value: String): String = java.net.URLDecoder.decode(value, "UTF-8")
}

/** One downloaded or downloading song as the download index has it. */
data class OfflineSong(
    val key: String,
    val payload: OfflinePayload,
    val state: State,
    /** 0..100, or -1 when unknown (a transcode has no length until it ends). */
    val percent: Float,
    val bytes: Long,
) {
    enum class State { Queued, Downloading, Done, Failed, Waiting }
}

/** An album, playlist or lone song as the listener downloaded it, with how far its songs are. */
data class OfflineEntry(
    val collection: OfflineCollection,
    val total: Int,
    val done: Int,
    val failed: Int,
    val bytes: Long,
    /** When it was downloaded: the newest of its songs' addition order, for listing newest first. */
    val order: Int,
) {
    val complete: Boolean get() = total > 0 && done == total
    val status: OfflineStatus
        get() = when {
            complete -> OfflineStatus.Downloaded
            failed > 0 && done + failed == total -> OfflineStatus.Failed
            else -> OfflineStatus.Downloading(done, total)
        }
}

/** What a page's Download row shows. */
sealed interface OfflineStatus {
    data object None : OfflineStatus
    data class Downloading(val done: Int, val total: Int) : OfflineStatus
    data object Downloaded : OfflineStatus
    data object Failed : OfflineStatus
}

/** Every download of one profile, read as the albums, playlists and songs the listener downloaded. */
data class OfflineLibrary(val songs: List<OfflineSong> = emptyList()) {
    /** Newest first. */
    val entries: List<OfflineEntry> by lazy {
        val grouped = LinkedHashMap<String, MutableList<Pair<Int, OfflineSong>>>()
        val collections = HashMap<String, OfflineCollection>()
        songs.forEachIndexed { index, song ->
            song.payload.collections.forEach { collection ->
                grouped.getOrPut(collection.key) { mutableListOf() } += index to song
                collections[collection.key] = collection
            }
        }
        grouped.map { (key, members) ->
            OfflineEntry(
                collection = collections.getValue(key),
                total = members.size,
                done = members.count { it.second.state == OfflineSong.State.Done },
                failed = members.count { it.second.state == OfflineSong.State.Failed },
                bytes = members.sumOf { it.second.bytes },
                order = members.maxOf { it.first },
            )
        }.sortedByDescending { it.order }
    }

    /** Bytes on disk for this profile's downloads. */
    val bytes: Long get() = songs.sumOf { it.bytes }

    fun statusOf(collectionKey: String): OfflineStatus =
        entries.firstOrNull { it.collection.key == collectionKey }?.status ?: OfflineStatus.None

    /** Whether the song [songId] (a MediaId string) is fully on the device. */
    fun isDownloaded(songId: String): Boolean =
        songs.any { it.payload.songId == songId && it.state == OfflineSong.State.Done }

    fun songsOf(collectionKey: String): List<OfflineSong> =
        songs.filter { song -> song.payload.collections.any { it.key == collectionKey } }
}
