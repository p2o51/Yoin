package com.gpo.yoin.data.source.applemusic

import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.remote.applemusic.AppleMusicSong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Renames songs and albums after another storefront's names, as an account's [AppleMusicNameRegion] asks.
 * Items are grouped by the storefront they ask ([OriginalNameTarget.forRegion]); each group is one request
 * per batch of catalog ids (`GET /v1/catalog/{storefront}/songs?ids=…&l=…`), and songs that storefront
 * doesn't know by their id are asked for once more by ISRC. Answers, misses included, are kept in memory,
 * so a list read again costs nothing. Names are a nicety: a batch that fails or takes longer than
 * [BATCH_TIMEOUT_MS] keeps the names it had, and a storefront the account is in already is never asked.
 */
internal class AppleMusicNameResolver(
    /** A catalog read: the path segments and query, answered with Apple's response document. */
    private val catalog: suspend (segments: List<String>, query: Map<String, String>) -> JsonObject,
    /** Catalog songs by ISRC in a storefront and language; see AppleMusicApiClient.catalogSongsByIsrc. */
    private val songsByIsrc: suspend (storefront: String, isrcs: List<String>, language: String) ->
    Map<String, List<AppleMusicSong>>,
) {
    /** One item's names in one storefront. A null field: the storefront named it nothing there. */
    data class Names(val title: String?, val artist: String?, val album: String?)

    private val cache = object : LinkedHashMap<String, Names?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Names?>?) = size > CACHE_SIZE
    }

    suspend fun tracks(region: AppleMusicNameRegion, accountStorefront: String?, tracks: List<Track>): List<Track> {
        if (region == AppleMusicNameRegion.Off || tracks.isEmpty()) return tracks
        val requests = tracks.mapNotNull { track ->
            val id = track.catalogId() ?: return@mapNotNull null
            val target = OriginalNameTarget.forRegion(
                region, track.title, track.artist, track.genre, track.extras[Track.EXTRA_ISRC],
            ) ?: return@mapNotNull null
            if (target.storefront == accountStorefront) return@mapNotNull null
            Request(id, target, track.extras[Track.EXTRA_ISRC])
        }
        if (requests.isEmpty()) return tracks
        val names = resolve(Kind.Songs, requests)
        val byId = requests.associateBy { it.id }
        return tracks.map { track ->
            val id = track.catalogId() ?: return@map track
            val request = byId[id] ?: return@map track
            val found = names[id] ?: return@map track
            track.copy(
                title = pick(region, request.target, track.title, found.title),
                artist = pick(region, request.target, track.artist, found.artist),
                album = pick(region, request.target, track.album, found.album),
            )
        }
    }

    suspend fun albums(region: AppleMusicNameRegion, accountStorefront: String?, albums: List<Album>): List<Album> {
        if (region == AppleMusicNameRegion.Off || albums.isEmpty()) return albums
        val requests = albums.mapNotNull { album ->
            val id = album.id.rawId.takeIf { album.id.provider == MediaId.PROVIDER_APPLE_MUSIC && it.isCatalogId() }
                ?: return@mapNotNull null
            // An album's genre says its language as a song's does; its tracks' ISRCs aren't in a list's answer.
            val target = OriginalNameTarget.forRegion(region, album.name, album.artist, album.genre, isrc = null)
                ?: return@mapNotNull null
            if (target.storefront == accountStorefront) return@mapNotNull null
            Request(id, target, isrc = null)
        }
        if (requests.isEmpty()) return albums
        val names = resolve(Kind.Albums, requests)
        val byId = requests.associateBy { it.id }
        return albums.map { album ->
            val request = byId[album.id.rawId] ?: return@map album
            val found = names[album.id.rawId] ?: return@map album
            album.copy(
                name = pick(region, request.target, album.name, found.title) ?: album.name,
                artist = pick(region, request.target, album.artist, found.artist),
            )
        }
    }

    /** An album page: the album and its tracklist, renamed together. */
    suspend fun album(region: AppleMusicNameRegion, accountStorefront: String?, album: Album): Album {
        if (region == AppleMusicNameRegion.Off) return album
        return coroutineScope {
            val tracks = async { tracks(region, accountStorefront, album.tracks) }
            val renamed = albums(region, accountStorefront, listOf(album)).first()
            renamed.copy(tracks = tracks.await())
        }
    }

    private fun pick(region: AppleMusicNameRegion, target: OriginalNameTarget, shown: String?, found: String?) =
        if (OriginalNameTarget.accepts(region, target, shown, found)) found?.trim() else shown

    /** Catalog id → its names, for every request that has an answer (cached or read now). */
    private suspend fun resolve(kind: Kind, requests: List<Request>): Map<String, Names> = coroutineScope {
        requests.distinctBy { it.id }.groupBy { it.target }.map { (target, group) ->
            async {
                val known = mutableMapOf<String, Names>()
                val unread = mutableListOf<Request>()
                synchronized(cache) {
                    group.forEach { request ->
                        val key = key(kind, target, request.id)
                        when {
                            !cache.containsKey(key) -> unread += request
                            else -> cache[key]?.let { known[request.id] = it }
                        }
                    }
                }
                // Batch by batch, each kept as it lands: a long list that runs out of time keeps what it read.
                for (batch in unread.chunked(kind.batch)) {
                    val read = try {
                        withTimeoutOrNull(BATCH_TIMEOUT_MS) { read(kind, target, batch) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    } ?: break
                    synchronized(cache) {
                        batch.forEach { cache[key(kind, target, it.id)] = read[it.id] }
                    }
                    known += read
                }
                known
            }
        }.awaitAll().fold(emptyMap()) { all, group -> all + group }
    }

    /** One batch's names: by id, then the songs missing by ISRC. */
    private suspend fun read(kind: Kind, target: OriginalNameTarget, requests: List<Request>): Map<String, Names> {
        val found = mutableMapOf<String, Names>()
        val response = catalog(
            listOf("v1", "catalog", target.storefront, kind.path),
            mapOf("ids" to requests.joinToString(",") { it.id }, "l" to target.language),
        )
        (response["data"] as? JsonArray).orEmpty().forEach { resource ->
            val item = resource as? JsonObject ?: return@forEach
            val id = item.text("id") ?: return@forEach
            val attributes = item["attributes"] as? JsonObject ?: return@forEach
            found[id] = Names(
                title = attributes.text("name"),
                artist = attributes.text("artistName"),
                album = attributes.text("albumName"),
            )
        }
        // A storefront may carry a song under an id of its own; the recording's ISRC still finds it.
        if (kind == Kind.Songs) {
            val byIsrc = requests.filter { it.id !in found && it.isrc != null }
            if (byIsrc.isNotEmpty()) {
                // The ids' answer stands even when this second read fails.
                val songs = try {
                    songsByIsrc(target.storefront, byIsrc.mapNotNull { it.isrc }, target.language)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyMap()
                }
                byIsrc.forEach { request ->
                    val song = songs[request.isrc]?.firstOrNull() ?: return@forEach
                    found[request.id] = Names(song.title, song.artist, song.album)
                }
            }
        }
        return found
    }

    /** [batch]: Apple's most ids per request for the type (songs 300, albums 100). */
    private enum class Kind(val path: String, val batch: Int) { Songs("songs", 300), Albums("albums", 100) }

    private data class Request(val id: String, val target: OriginalNameTarget, val isrc: String?)

    private fun key(kind: Kind, target: OriginalNameTarget, id: String) =
        "${kind.path}|${target.storefront}|${target.language}|$id"

    private fun Track.catalogId(): String? {
        if (id.provider != MediaId.PROVIDER_APPLE_MUSIC) return null
        return (extras[AppleMusicSong.EXTRA_CATALOG_ID] ?: id.rawId).takeIf { it.isCatalogId() }
    }

    private fun String.isCatalogId() = isNotEmpty() && all(Char::isDigit)

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull

    private companion object {
        /** Items whose names are kept: a large playlist's worth. */
        const val CACHE_SIZE = 4_000

        /** How long a list waits on one batch of names before it opens under the names it has. */
        const val BATCH_TIMEOUT_MS = 4_000L
    }
}
