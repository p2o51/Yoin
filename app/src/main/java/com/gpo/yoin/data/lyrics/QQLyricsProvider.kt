package com.gpo.yoin.data.lyrics

import android.util.Log
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * QQ 音乐歌词源，免登录。两组主机、三类接口：
 *
 * - **Web 主机**（`c.y.qq.com` 一族）：`client_search_cp` 搜歌；`fcg_play_single_song`
 *   把数字 songid 换成 songmid；`fcg_query_lyric_new` 按 songmid 取 LRC（必须带
 *   `Referer: https://y.qq.com/`，只有原文没有翻译）。
 * - **网关主机**（`u.y.qq.com` 一族）：`musicu.fcg` 上的 `DoSearchForQQMusicDesktop`
 *   搜歌兜底；`GetPlayLyricInfo` 按数字 songID 取 base64 LRC 和翻译，是取词主路。
 *
 * 对外的 song id 一律是数字 songid（十进制字符串）。取词先走网关；网关全挂、被拒或
 * 只给密文时，用搜索时记下的 mid（没有就现查）走 Web 旧接口。每组主机记住上次答复的
 * 那台，下次先问它。所有接口的 Content-Type 都不可信，统一按文本解析 JSON（Web 旧接口
 * 可能包一层 JSONP）。
 *
 * 自动匹配（[search]）交给 [LyricCandidateMatcher] 校验，宁可 null 让下一个 provider
 * 试，也不拿同名别人的歌；手动搜索（[searchMultiple]）保持平台原序。
 */
class QQLyricsProvider(
    private val client: OkHttpClient = defaultClient(),
    webBaseUrls: List<String> = DEFAULT_WEB_BASE_URLS,
    gatewayBaseUrls: List<String> = DEFAULT_GATEWAY_BASE_URLS,
) : LyricProvider() {

    override val name: String = "qq"

    override val nativeTranslation: NativeTranslation = NativeTranslation.SimplifiedChinese

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val webHosts = HostRotation(webBaseUrls)
    private val gatewayHosts = HostRotation(gatewayBaseUrls)
    private val matcher = LyricCandidateMatcher()
    private val midsById = SongMidMemory(MID_MEMORY_CAPACITY)

    override suspend fun search(title: String, artist: String): SongMatch? =
        withContext(Dispatchers.IO) {
            val songs = findSongs(title, artist, SEARCH_PAGE_SIZE) ?: return@withContext null
            val picked = matcher.pick(
                candidates = songs.map { LyricCandidateMatcher.Candidate(it.title, it.artists, it.album) },
                title = title,
                artist = artist,
            ) ?: return@withContext null
            songs[picked].toMatch()
        }

    override suspend fun searchMultiple(
        title: String,
        artist: String,
        limit: Int,
    ): List<SongMatch> = withContext(Dispatchers.IO) {
        if (limit <= 0) return@withContext emptyList()
        val songs = findSongs(title, artist, limit) ?: throw IOException(UNAVAILABLE_MESSAGE)
        songs.take(limit).map { it.toMatch() }
    }

    /** 只认正整数 songid；旧缓存里的 songmid（字母数字混排）一律拒绝。 */
    override fun canFetch(songId: String): Boolean =
        songId.isNotEmpty() &&
            songId.all { it in '0'..'9' } &&
            (songId.toLongOrNull() ?: 0L) > 0L

    override suspend fun fetchLyric(songId: String): String? = withContext(Dispatchers.IO) {
        fetchPayload(songId)?.lyric
    }

    override suspend fun fetchLyricWithTranslation(songId: String): LyricPayload? =
        withContext(Dispatchers.IO) {
            fetchPayload(songId)
        }

    // ── 搜索 ─────────────────────────────────────────────────────────────────

    /** null = 所有主机都没答上；空列表是真实的"搜不到"，不再换路。 */
    private suspend fun findSongs(title: String, artist: String, pageSize: Int): List<QQSong>? {
        val query = listOf(title, artist).map(String::trim).filter(String::isNotEmpty).joinToString(" ")
        if (query.isEmpty()) return emptyList()
        val songs = webHosts.firstAnswer { base -> searchOnWeb(base, query, pageSize) }
            ?: gatewayHosts.firstAnswer { base -> searchOnGateway(base, query, pageSize) }
            ?: return null
        songs.value.forEach { midsById.remember(it.id, it.mid) }
        return songs.value
    }

    private suspend fun searchOnWeb(base: String, query: String, pageSize: Int): Answer<List<QQSong>>? {
        val url = endpoint(base, WEB_SEARCH_PATH)?.newBuilder()
            ?.addQueryParameter("w", query)
            ?.addQueryParameter("format", "json")
            ?.addQueryParameter("n", pageSize.toString())
            ?.addQueryParameter("p", "1")
            ?.addQueryParameter("t", "0")
            ?.addQueryParameter("cr", "1")
            ?.addQueryParameter("new_json", "1")
            ?.build()
            ?: return null
        val root = fetchJson(webGet(url), "search") ?: return null
        if (!root.codeIsZero()) return null
        val list = root.obj("data")?.obj("song")?.array("list") ?: return null
        return Answer(parseSongs(list))
    }

    private suspend fun searchOnGateway(base: String, query: String, pageSize: Int): Answer<List<QQSong>>? {
        val body = gatewayBody(module = SEARCH_MODULE, method = SEARCH_METHOD) {
            put("query", query)
            put("search_type", 0)
            put("num_per_page", pageSize)
            put("page_num", 1)
        }
        val data = callGateway(base, body, "gateway search") ?: return null
        val list = data.obj("body")?.obj("song")?.array("list") ?: return null
        return Answer(parseSongs(list))
    }

    /** 没有数字 id 的条目取不了词，直接跳过。 */
    private fun parseSongs(list: JsonArray): List<QQSong> = list.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val id = item.string("id")?.toLongOrNull()?.takeIf { it > 0L } ?: return@mapNotNull null
        val title = item.string("title")?.takeIf(String::isNotBlank)
            ?: item.string("name")?.takeIf(String::isNotBlank)
            ?: return@mapNotNull null
        val artists = item.array("singer").orEmpty().mapNotNull { singer ->
            (singer as? JsonObject)?.string("name")?.trim()?.takeIf(String::isNotEmpty)
        }
        val album = listOfNotNull(
            item.obj("album")?.string("name"),
            item.obj("album")?.string("title"),
            item.string("albumname"),
        ).firstOrNull(String::isNotBlank)
        QQSong(
            id = id,
            mid = item.string("mid")?.trim(),
            title = title,
            artists = artists,
            album = album?.trim()?.takeIf(String::isNotEmpty),
        )
    }

    // ── 取词 ─────────────────────────────────────────────────────────────────

    private suspend fun fetchPayload(songId: String): LyricPayload? {
        if (!canFetch(songId)) return null
        val id = songId.toLong()
        val gatewayReply = gatewayHosts.firstAnswer { base -> lyricFromGateway(base, id) }?.value
        val payload = when (gatewayReply) {
            is GatewayLyric.Found -> gatewayReply.payload
            // 网关明确说没词：不再查 Web，同一首歌那边也没有。
            GatewayLyric.Missing -> return null
            GatewayLyric.Encrypted, null -> lyricFromWeb(id) ?: return null
        }
        return payload.takeUnless { isInstrumentalPlaceholder(it.lyric) }
    }

    private suspend fun lyricFromGateway(base: String, songId: Long): Answer<GatewayLyric>? {
        val body = gatewayBody(module = LYRIC_MODULE, method = LYRIC_METHOD) {
            put("songID", songId)
            put("format", "json")
            put("crypt", 0)
            put("qrc", 0)
            put("roma", 0)
            put("trans", 1)
        }
        val data = callGateway(base, body, "lyric") ?: return null
        val lyricField = data.primitive("lyric") ?: return null
        val reply = when (val lyric = decodeLyricField(lyricField.contentOrNull)) {
            LyricField.Empty -> GatewayLyric.Missing
            // 只有逐字 QRC 的歌会无视 crypt:0 回密文；另一台网关是同一个后端，直接走 Web。
            LyricField.Unreadable -> GatewayLyric.Encrypted
            is LyricField.Text -> GatewayLyric.Found(
                LyricPayload(lyric = lyric.value, translatedLyric = cleanTranslation(data.string("trans"))),
            )
        }
        return Answer(reply)
    }

    private suspend fun lyricFromWeb(songId: Long): LyricPayload? {
        val mid = midsById.recall(songId)
            ?: webHosts.firstAnswer { base -> resolveMid(base, songId) }?.value
            ?: return null
        val lyric = webHosts.firstAnswer { base -> legacyLyric(base, mid) }?.value ?: return null
        return LyricPayload(lyric = lyric)
    }

    /** songid → songmid。查不到（含答非所问）是权威的 null。 */
    private suspend fun resolveMid(base: String, songId: Long): Answer<String?>? {
        val url = endpoint(base, SONG_DETAIL_PATH)?.newBuilder()
            ?.addQueryParameter("songid", songId.toString())
            ?.addQueryParameter("format", "json")
            ?.build()
            ?: return null
        val root = fetchJson(webGet(url), "song detail") ?: return null
        if (!root.codeIsZero()) return null
        val songs = root.array("data") ?: return null
        // 某些 id（比如 1）会回一首不相干的歌，只信 id 对得上的那条。
        val mid = (songs.firstOrNull() as? JsonObject)
            ?.takeIf { it.string("id")?.toLongOrNull() == songId }
            ?.string("mid")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        midsById.remember(songId, mid)
        return Answer(mid)
    }

    /** 按 songmid 取原文。`-1901` = 这首歌没词（权威）；其余非零码换下一台主机。 */
    private suspend fun legacyLyric(base: String, mid: String): Answer<String?>? {
        val url = endpoint(base, LEGACY_LYRIC_PATH)?.newBuilder()
            ?.addQueryParameter("songmid", mid)
            ?.addQueryParameter("format", "json")
            ?.addQueryParameter("nobase64", "1")
            ?.build()
            ?: return null
        val root = fetchJson(webGet(url), "legacy lyric") ?: return null
        val retcode = (root.primitive("retcode") ?: root.primitive("code"))?.intOrNull
        return when (retcode) {
            0 -> when (val lyric = decodeLyricField(root.string("lyric"))) {
                LyricField.Empty -> Answer(null)
                LyricField.Unreadable -> null
                is LyricField.Text -> Answer(lyric.value)
            }
            NO_LYRIC_RETCODE -> Answer(null)
            else -> {
                Log.w(TAG, "QQ legacy lyric refused: $retcode")
                null
            }
        }
    }

    // ── 载荷解码 ─────────────────────────────────────────────────────────────

    /**
     * `lyric` / `trans` 字段有三种形态：明文 LRC（以 `[` 开头）、base64 LRC、十六进制密文。
     * 十六进制字符同时也是合法 base64，所以必须先判密文再试 base64。
     */
    private fun decodeLyricField(raw: String?): LyricField {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return LyricField.Empty
        if (trimmed.startsWith('[')) return LyricField.Text(trimmed)
        val compact = trimmed.filterNot(Char::isWhitespace)
        if (compact.length % 2 == 0 && HEX_TEXT.matches(compact)) return LyricField.Unreadable
        val decoded = decodeBase64Utf8(compact) ?: return LyricField.Unreadable
        return if (decoded.isBlank()) LyricField.Empty else LyricField.Text(decoded)
    }

    private fun decodeBase64Utf8(value: String): String? {
        val bytes = try {
            Base64.getDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    /**
     * 网关翻译是与原文同时间轴的 LRC。去掉日语歌才有的注音行 `[kana:…]` 和 `//`
     * 占位行（"这一行没有翻译"，不只出现在制作人员行）；一行有效译文都不剩就当没翻译。
     */
    private fun cleanTranslation(raw: String?): String? {
        val text = (decodeLyricField(raw) as? LyricField.Text)?.value ?: return null
        val kept = text.lines().filterNot { line ->
            val trimmed = line.trim()
            KANA_TAG.matches(trimmed) || lineText(trimmed) == TRANSLATION_PLACEHOLDER
        }
        val hasTranslatedLine = kept.any { line ->
            LEADING_TIMESTAMPS.containsMatchIn(line.trim()) && lineText(line).isNotEmpty()
        }
        return if (hasTranslatedLine) kept.joinToString("\n") else null
    }

    /**
     * 纯音乐在两条取词路上都只回一行 `[00:00:00]此歌曲为没有填词的纯音乐，请您欣赏`
     * （时间戳还是坏的），没有可同步的内容，按"没词"处理。
     */
    private fun isInstrumentalPlaceholder(lyric: String): Boolean {
        val texts = lyric.lines()
            .map(::lineText)
            .filter { it.isNotEmpty() && !ID_TAG.matches(it) }
        return texts.isNotEmpty() && texts.all { it == INSTRUMENTAL_PLACEHOLDER }
    }

    /** 去掉行首所有时间戳后的正文。 */
    private fun lineText(line: String): String = line.trim().replace(LEADING_TIMESTAMPS, "").trim()

    // ── HTTP / JSON ──────────────────────────────────────────────────────────

    /** 网关应答里 `req_1.data`；任何一层 code 非零都算这台主机拒绝。 */
    private suspend fun callGateway(base: String, body: JsonObject, what: String): JsonObject? {
        val url = endpoint(base, GATEWAY_PATH) ?: return null
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val root = fetchJson(request, what) ?: return null
        if (!root.codeIsZero()) return null
        val reply = root.obj(GATEWAY_REQUEST_KEY) ?: return null
        val code = reply.primitive("code")?.intOrNull
        if (code != 0) {
            Log.w(TAG, "QQ $what refused: $code")
            return null
        }
        return reply.obj("data")
    }

    private fun gatewayBody(
        module: String,
        method: String,
        param: JsonObjectBuilder.() -> Unit,
    ): JsonObject = buildJsonObject {
        putJsonObject("comm") {
            put("ct", GATEWAY_CLIENT_TYPE)
            put("cv", GATEWAY_CLIENT_VERSION)
            put("uin", "0")
        }
        putJsonObject(GATEWAY_REQUEST_KEY) {
            put("module", module)
            put("method", method)
            putJsonObject("param", param)
        }
    }

    private fun webGet(url: HttpUrl): Request =
        Request.Builder().url(url).get().header("Referer", WEB_REFERER).build()

    /** 2xx 且能解析成 JSON 对象的应答体；状态码、传输、空体、解析失败都返回 null。 */
    private suspend fun fetchJson(request: Request, what: String): JsonObject? {
        val text = try {
            client.awaitResponse(request).use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "QQ $what failed: ${response.code}")
                    return null
                }
                response.body.string()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "QQ $what error: ${e.message}")
            return null
        }
        if (text.isBlank()) return null
        return try {
            json.parseToJsonElement(unwrapJsonp(text)) as? JsonObject
        } catch (e: Exception) {
            Log.w(TAG, "QQ $what parse error: ${e.message}")
            null
        }
    }

    /** `MusicJsonCallback({...})` / `callback({...});` → `{...}`；裸 JSON 原样返回。 */
    private fun unwrapJsonp(body: String): String {
        val text = body.trim()
        if (text.startsWith('{') || text.startsWith('[')) return text
        val open = text.indexOf('(')
        val close = text.lastIndexOf(')')
        if (open <= 0 || close <= open) return text
        if (!JSONP_CALLBACK.matches(text.substring(0, open).trim())) return text
        return text.substring(open + 1, close)
    }

    /** 拼接而不是 resolve：base 可能带路径前缀（测试里的 `/web1`）。 */
    private fun endpoint(base: String, path: String): HttpUrl? = "$base$path".toHttpUrlOrNull()

    private fun JsonObject.codeIsZero(): Boolean {
        val code = this["code"] ?: return true
        return (code as? JsonPrimitive)?.intOrNull == 0
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

    private fun JsonObject.primitive(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

    private fun JsonObject.string(key: String): String? = primitive(key)?.contentOrNull

    // ── 内部类型 ─────────────────────────────────────────────────────────────

    /** [album] 只给匹配器认现场 / 访谈专辑用。 */
    private data class QQSong(
        val id: Long,
        val mid: String?,
        val title: String,
        val artists: List<String>,
        val album: String?,
    ) {
        fun toMatch(): SongMatch = SongMatch(
            songId = id.toString(),
            title = title,
            artist = artists.joinToString(ARTIST_SEPARATOR),
        )
    }

    /** 某台主机给出的权威答复；[value] 本身可以是"没有"。null 的 Answer 表示该换主机了。 */
    private class Answer<out T>(val value: T)

    private sealed interface GatewayLyric {
        data class Found(val payload: LyricPayload) : GatewayLyric
        data object Missing : GatewayLyric
        data object Encrypted : GatewayLyric
    }

    private sealed interface LyricField {
        data object Empty : LyricField
        data object Unreadable : LyricField
        data class Text(val value: String) : LyricField
    }

    /** 一组同类主机，按"上次答复的那台优先"轮询。状态只属于这个 provider 实例。 */
    private class HostRotation(baseUrls: List<String>) {
        private val bases = baseUrls.map { it.trimEnd('/') }
        private val preferred = AtomicInteger(0)

        suspend fun <T> firstAnswer(attempt: suspend (base: String) -> Answer<T>?): Answer<T>? {
            val start = preferred.get()
            for (offset in bases.indices) {
                val index = (start + offset) % bases.size
                val answer = attempt(bases[index]) ?: continue
                preferred.set(index)
                return answer
            }
            return null
        }
    }

    /** 搜索结果里见过的 songid → songmid，供网关不可用时走 Web 旧接口。有界 LRU。 */
    private class SongMidMemory(private val capacity: Int) {
        private val mids = object : LinkedHashMap<Long, String>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>?): Boolean =
                size > capacity
        }

        @Synchronized
        fun remember(songId: Long, mid: String?) {
            if (!mid.isNullOrBlank()) mids[songId] = mid
        }

        @Synchronized
        fun recall(songId: Long): String? = mids[songId]
    }

    companion object {
        private const val TAG = "QQLyricsProvider"
        private const val UNAVAILABLE_MESSAGE = "QQ Music is unavailable right now"

        private val DEFAULT_WEB_BASE_URLS = listOf("https://c.y.qq.com", "https://shc.y.qq.com")
        private val DEFAULT_GATEWAY_BASE_URLS = listOf("https://u.y.qq.com", "https://u6.y.qq.com")

        private const val WEB_SEARCH_PATH = "/soso/fcgi-bin/client_search_cp"
        private const val SONG_DETAIL_PATH = "/v8/fcg-bin/fcg_play_single_song.fcg"
        private const val LEGACY_LYRIC_PATH = "/lyric/fcgi-bin/fcg_query_lyric_new.fcg"
        private const val GATEWAY_PATH = "/cgi-bin/musicu.fcg"
        private const val WEB_REFERER = "https://y.qq.com/"

        private const val GATEWAY_REQUEST_KEY = "req_1"
        private const val GATEWAY_CLIENT_TYPE = "19"
        private const val GATEWAY_CLIENT_VERSION = "1859"
        private const val SEARCH_MODULE = "music.search.SearchCgiService"
        private const val SEARCH_METHOD = "DoSearchForQQMusicDesktop"
        private const val LYRIC_MODULE = "music.musichallSong.PlayLyricInfo"
        private const val LYRIC_METHOD = "GetPlayLyricInfo"

        private const val SEARCH_PAGE_SIZE = 10
        private const val MID_MEMORY_CAPACITY = 512
        private const val NO_LYRIC_RETCODE = -1901
        private const val ARTIST_SEPARATOR = " / "
        private const val TRANSLATION_PLACEHOLDER = "//"
        private const val INSTRUMENTAL_PLACEHOLDER = "此歌曲为没有填词的纯音乐，请您欣赏"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val HEX_TEXT = Regex("[0-9A-Fa-f]+")
        private val LEADING_TIMESTAMPS = Regex("""^(?:\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?])+""")
        private val ID_TAG = Regex("""^\[[A-Za-z]+:[^\]]*]$""")
        private val KANA_TAG = Regex("""^\[kana:.*]$""")
        private val JSONP_CALLBACK = Regex("""[A-Za-z_$][\w$.]*""")

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
