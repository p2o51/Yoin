package com.gpo.yoin.data.integration.neodb

import android.util.Log
import com.gpo.yoin.data.local.NeoDBConfigDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Reads what other listeners on NeoDB wrote about an album (the "别人怎么说" page). Works signed out: NeoDB's
 * catalogue and public posts need no token, so signed out it reads neodb.social anonymously; signed in, the
 * user's own instance with their token (so posts their account may see show up too).
 *
 * It never writes and never touches the sync mapping: the page finds its own item each time it opens.
 */
class NeoDbVoicesService internal constructor(
    private val api: NeoDBApi,
    private val configDao: NeoDBConfigDao,
    private val tokenStore: NeoDbTokenStore,
    private val client: OkHttpClient,
    private val json: Json,
) {
    private data class Access(val instance: String, val token: String?)

    private suspend fun access(): Access = withContext(Dispatchers.IO) {
        // The token is a file read and a decrypt: off the main thread.
        val token = tokenStore.readToken()?.takeUnless(String::isBlank)
        val configured = configDao.get()?.instance?.takeUnless(String::isBlank)
        // A token only means something on its own instance.
        if (token != null && configured != null) Access(configured, token) else Access(DEFAULT_INSTANCE, null)
    }

    /** The NeoDB item for the album, with its spread and outside links; null when NeoDB has nothing like it. */
    suspend fun findAlbum(albumName: String, artist: String?): Result<NeoDbAlbumVoices?> = runCatching {
        val access = access()
        val query = NeoDbVoicesParsing.query(albumName, artist)
        if (query.isBlank()) return@runCatching null
        val items = api.searchAlbumPublic(access.instance, access.token, query)
        NeoDbVoicesParsing.pickAlbumItem(albumName, artist, items)
            ?.let { NeoDbVoicesParsing.albumVoices(access.instance, it) }
    }

    /** One page (1-based, 20 posts) of short comments and reviews, newest first. */
    suspend fun voices(itemUuid: String, page: Int): Result<NeoDbVoicePage> = runCatching {
        val access = access()
        NeoDbVoicesParsing.page(page, api.listItemPosts(access.instance, access.token, itemUuid, page))
    }

    /** Where to search NeoDB when no item matched. */
    suspend fun searchUrl(albumName: String, artist: String?): String =
        NeoDbVoicesParsing.neoDbSearchUrl(access().instance, albumName, artist)

    /**
     * Scores from catalogues that publish them (Discogs, MusicBrainz), found through the links NeoDB keeps on
     * the item. RYM and AOTY have no API and turn automated reads away, so they are links on the page only.
     * Each source fails quietly: a missing card, never an error.
     */
    suspend fun referenceScores(album: NeoDbAlbumVoices): List<NeoDbReferenceScore> = coroutineScope {
        val discogs = async { album.discogsUrl?.let { runCatching { discogsScore(it) }.logged("discogs") } }
        val musicBrainz = async {
            album.musicBrainzUrl?.let { runCatching { musicBrainzScore(it) }.logged("musicbrainz") }
        }
        listOfNotNull(discogs.await(), musicBrainz.await())
    }

    private fun Result<NeoDbReferenceScore?>.logged(source: String): NeoDbReferenceScore? =
        onFailure { Log.w(TAG, "reference score from $source failed", it) }.getOrNull()

    private suspend fun discogsScore(url: String): NeoDbReferenceScore? {
        val (kind, id) = NeoDbVoicesParsing.discogsId(url) ?: return null
        val releaseId = if (kind == "master") {
            getJson("https://api.discogs.com/masters/$id")["main_release"]?.jsonPrimitive?.longOrNull ?: return null
        } else {
            id
        }
        val community = getJson("https://api.discogs.com/releases/$releaseId")["community"]?.jsonObject
        val rating = community?.get("rating")?.jsonObject ?: return null
        val value = rating["average"]?.jsonPrimitive?.doubleOrNull ?: return null
        val votes = rating["count"]?.jsonPrimitive?.intOrNull ?: 0
        if (votes == 0) return null
        return NeoDbReferenceScore(source = "Discogs", value = value, scale = 5, votes = votes, url = url)
    }

    private suspend fun musicBrainzScore(url: String): NeoDbReferenceScore? {
        val (kind, mbid) = NeoDbVoicesParsing.musicBrainzId(url) ?: return null
        val group = if (kind == "release") {
            getJson("https://musicbrainz.org/ws/2/release/$mbid?inc=release-groups+ratings&fmt=json")["release-group"]
                ?.jsonObject
        } else {
            getJson("https://musicbrainz.org/ws/2/release-group/$mbid?inc=ratings&fmt=json")
        }
        val rating = group?.get("rating")?.jsonObject ?: return null
        val value = rating["value"]?.let { runCatching { it.jsonPrimitive.doubleOrNull }.getOrNull() } ?: return null
        val votes = rating["votes-count"]?.jsonPrimitive?.intOrNull ?: 0
        if (votes == 0) return null
        return NeoDbReferenceScore(source = "MusicBrainz", value = value, scale = 5, votes = votes, url = url)
    }

    private suspend fun getJson(url: String): JsonObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            // Both services ask callers to name themselves.
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(request).execute().use { res ->
            val body = res.body?.string().orEmpty()
            check(res.isSuccessful) { "HTTP ${res.code} from ${request.url.host}" }
            json.parseToJsonElement(body).jsonObject
        }
    }

    private companion object {
        const val TAG = "NeoDbVoices"
        const val DEFAULT_INSTANCE = "https://neodb.social"
        const val USER_AGENT = "Yoin/1 ( https://github.com/p2o51/Yoin )"
    }
}
