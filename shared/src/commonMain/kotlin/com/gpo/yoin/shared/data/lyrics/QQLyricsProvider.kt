package com.gpo.yoin.shared.data.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

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
@OptIn(ExperimentalAtomicApi::class, ExperimentalEncodingApi::class)
internal class QQLyricsProvider(
    engine: HttpClientEngine? = null,
    webBaseUrls: List<String> = DEFAULT_WEB_BASE_URLS,
    gatewayBaseUrls: List<String> = DEFAULT_GATEWAY_BASE_URLS,
) : LyricProvider() {

    private val client: HttpClient = newLyricHttpClient(engine)

    override fun close() = client.close()

    override val name: String = "qq"

    override val nativeTranslation: NativeTranslation = NativeTranslation.SimplifiedChinese

    private val webHosts = HostRotation(webBaseUrls)
    private val gatewayHosts = HostRotation(gatewayBaseUrls)
    private val matcher = LyricCandidateMatcher()
    private val midsById = SongMidMemory(MID_MEMORY_CAPACITY)

    override suspend fun search(title: String, artist: String, durationMs: Long?): SongMatch? {
        val songs = findSongs(title, artist, SEARCH_PAGE_SIZE) ?: return null
        val picked = matcher.pick(
            candidates = songs.map {
                LyricCandidateMatcher.Candidate(it.title, it.artists, it.album, it.durationMs)
            },
            title = title,
            artist = artist,
            durationMs = durationMs,
        ) ?: return null
        return songs[picked].toMatch()
    }

    override suspend fun searchMultiple(
        title: String,
        artist: String,
        limit: Int,
    ): List<SongMatch> {
        if (limit <= 0) return emptyList()
        val songs = findSongs(title, artist, limit) ?: throw LyricsSourceUnavailableException(UNAVAILABLE_MESSAGE)
        return songs.take(limit).map { it.toMatch() }
    }

    /** 只认正整数 songid；旧缓存里的 songmid（字母数字混排）一律拒绝。 */
    override fun canFetch(songId: String): Boolean =
        songId.isNotEmpty() &&
            songId.all { it in '0'..'9' } &&
            (songId.toLongOrNull() ?: 0L) > 0L

    override suspend fun fetchLyric(songId: String): String? = fetchPayload(songId)?.lyric

    override suspend fun fetchLyricWithTranslation(songId: String): LyricPayload? = fetchPayload(songId)

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
        val url = endpoint(
            base,
            WEB_SEARCH_PATH,
            "w" to query,
            "format" to "json",
            "n" to pageSize.toString(),
            "p" to "1",
            "t" to "0",
            "cr" to "1",
            "new_json" to "1",
        ) ?: return null
        val root = fetchJson(url, "search", referer = true) ?: return null
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
            // `interval`：秒。
            durationMs = item.string("interval")?.toLongOrNull()?.takeIf { it > 0L }?.times(1_000L),
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
        val url = endpoint(
            base,
            SONG_DETAIL_PATH,
            "songid" to songId.toString(),
            "format" to "json",
        ) ?: return null
        val root = fetchJson(url, "song detail", referer = true) ?: return null
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
        val url = endpoint(
            base,
            LEGACY_LYRIC_PATH,
            "songmid" to mid,
            "format" to "json",
            "nobase64" to "1",
        ) ?: return null
        val root = fetchJson(url, "legacy lyric", referer = true) ?: return null
        val retcode = (root.primitive("retcode") ?: root.primitive("code"))?.intOrNull
        return when (retcode) {
            0 -> when (val lyric = decodeLyricField(root.string("lyric"))) {
                LyricField.Empty -> Answer(null)
                LyricField.Unreadable -> null
                is LyricField.Text -> Answer(lyric.value)
            }
            NO_LYRIC_RETCODE -> Answer(null)
            else -> null
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
            // Java's decoder takes unpadded input; so does this one.
            Base64.Default.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL).decode(value)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: Exception) {
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
        val root = fetchJson(url, what, postBody = body.toString()) ?: return null
        if (!root.codeIsZero()) return null
        val reply = root.obj(GATEWAY_REQUEST_KEY) ?: return null
        val code = reply.primitive("code")?.intOrNull
        if (code != 0) {
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

    /**
     * 2xx 且能解析成 JSON 对象的应答体；状态码、传输、空体、解析失败都返回 null。
     * [postBody] 非空 = POST 这段 JSON；[referer] = 带上 Web 主机要求的 Referer。
     */
    private suspend fun fetchJson(
        url: Url,
        what: String,
        postBody: String? = null,
        referer: Boolean = false,
    ): JsonObject? {
        val headers = if (referer) listOf("Referer" to WEB_REFERER) else emptyList()
        val text = attempt(onError = { null }) {
            val reply = if (postBody != null) client.postJsonText(url, postBody, headers) else client.getText(url, headers)
            if (!reply.isSuccessful) null else reply.text
        } ?: return null
        if (text.isBlank()) return null
        return try {
            LyricJson.parseToJsonElement(unwrapJsonp(text)) as? JsonObject
        } catch (e: Exception) {
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
    private fun endpoint(base: String, path: String, vararg query: Pair<String, String>): Url? {
        val url = parseHttpUrl("$base$path") ?: return null
        if (query.isEmpty()) return url
        return URLBuilder(url).apply { query.forEach { (k, v) -> parameters.append(k, v) } }.build()
    }

    private fun JsonObject.codeIsZero(): Boolean {
        val code = this["code"] ?: return true
        return (code as? JsonPrimitive)?.intOrNull == 0
    }

    // ── 内部类型 ─────────────────────────────────────────────────────────────

    /** [album] 只给匹配器认现场 / 访谈专辑用，[durationMs] 只给匹配器挑同名录音用。 */
    private data class QQSong(
        val id: Long,
        val mid: String?,
        val title: String,
        val artists: List<String>,
        val album: String?,
        val durationMs: Long? = null,
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
        private val preferred = AtomicInt(0)

        suspend fun <T> firstAnswer(attempt: suspend (base: String) -> Answer<T>?): Answer<T>? {
            val start = preferred.load()
            for (offset in bases.indices) {
                val index = (start + offset) % bases.size
                val answer = attempt(bases[index]) ?: continue
                preferred.store(index)
                return answer
            }
            return null
        }
    }

    /** 搜索结果里见过的 songid → songmid，供网关不可用时走 Web 旧接口。有界 LRU。 */
    private class SongMidMemory(capacity: Int) {
        private val mids = LruMemory<Long, String>(capacity)

        suspend fun remember(songId: Long, mid: String?) {
            if (!mid.isNullOrBlank()) mids.remember(songId, mid)
        }

        suspend fun recall(songId: Long): String? = mids.recall(songId)
    }

    companion object {
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

        private val HEX_TEXT = Regex("[0-9A-Fa-f]+")
        private val LEADING_TIMESTAMPS = Regex("""^(?:\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?])+""")
        private val ID_TAG = Regex("""^\[[A-Za-z]+:[^\]]*]$""")
        private val KANA_TAG = Regex("""^\[kana:.*]$""")
        private val JSONP_CALLBACK = Regex("""[A-Za-z_$][\w$.]*""")
    }
}
