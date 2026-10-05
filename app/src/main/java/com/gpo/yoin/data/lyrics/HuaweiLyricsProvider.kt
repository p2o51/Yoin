package com.gpo.yoin.data.lyrics

import android.util.Log
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 华为音乐（国内区 drcn）歌词源，**只用匿名公开接口**：不带 Authorization、Cookie、
 * 设备 id 或任何 token。两步：
 *
 * 1. **搜索**：`POST …/music-search-service/v10/service/fuzzysearch`，JSON body 全是字符串
 *    （`contentType "1"` = 单曲）。`result.resultCode == "000000"` 才算答复；搜不到时同样
 *    是 `000000`，只是没有 `songSimpleInfos`。每首歌的 `contentExInfo` 是一段 JSON 字符串，
 *    里面的 `lyrics` 又是一段 JSON 字符串，带逐行歌词地址和 `lineByLineLyric_subType`。
 * 2. **取词**：直接 GET 歌词地址（dbankcdn 或 tingmall 上的静态文件，没有签名）。
 *    `subType` 含 `translate` 的文件在行内带简体中文译文，交给 [HuaweiLrc] 拆开。
 *
 * **id。** 歌词地址会随歌词更新换路径，按 id 取词时（几天后、换了进程、另一台设备）
 * 必须能重新找回地址，所以对外的 song id 是自带解析信息的 [HuaweiSongRef]：
 * `hw1|<contentID>|<查询提示>`。进程内用 LRU 记住 contentID → 地址；没记住、或者记住的
 * 地址已经 404，就用查询提示重新搜一次、按 contentID 精确找回，找不到就算没词——
 * 绝不换成同名的另一条录音。
 *
 * 只在手动搜索里出现（[automatic] = false）：接口没有公开文档、在国内区，自动链里
 * 每首歌都跑一次不划算。
 */
class HuaweiLyricsProvider(
    private val client: OkHttpClient = defaultClient(),
) : LyricProvider() {

    override val name: String = NAME

    override val automatic: Boolean = false

    override val nativeTranslation: NativeTranslation = NativeTranslation.SimplifiedChinese

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val matcher = LyricCandidateMatcher()
    private val lyricFiles = LyricFileMemory(LYRIC_MEMORY_CAPACITY)

    override suspend fun search(title: String, artist: String): SongMatch? =
        withContext(Dispatchers.IO) {
            val songs = findSongs(queryOf(title, artist), SEARCH_PAGE_SIZE)
                ?.filter(HuaweiSong::hasUsableLyric)
                ?: return@withContext null
            val picked = matcher.pick(
                candidates = songs.map {
                    LyricCandidateMatcher.Candidate(it.matcherTitle(), it.matcherArtists(), it.album)
                },
                title = title,
                artist = artist,
            ) ?: return@withContext null
            songs[picked].toMatch()
        }

    /**
     * 保持平台原序，只去掉肯定拿不到词的条目（没有歌词地址、纯音乐占位）——华为曲库里
     * 八音盒、伴奏版很多，不去掉的话三个名额常被它们占满。所以多要一些再截断。
     */
    override suspend fun searchMultiple(
        title: String,
        artist: String,
        limit: Int,
    ): List<SongMatch> = withContext(Dispatchers.IO) {
        if (limit <= 0) return@withContext emptyList()
        val pageSize = minOf(limit * 2, maxOf(limit, MAX_PAGE_SIZE))
        val songs = findSongs(queryOf(title, artist), pageSize) ?: throw IOException(UNAVAILABLE_MESSAGE)
        songs.filter(HuaweiSong::hasUsableLyric).take(limit).map(HuaweiSong::toMatch)
    }

    override fun canFetch(songId: String): Boolean = HuaweiSongRef.parse(songId) != null

    override suspend fun fetchLyric(songId: String): String? = withContext(Dispatchers.IO) {
        fetchPayload(songId)?.lyric
    }

    override suspend fun fetchLyricWithTranslation(songId: String): LyricPayload? =
        withContext(Dispatchers.IO) {
            fetchPayload(songId)
        }

    // ── 取词 ─────────────────────────────────────────────────────────────────

    private suspend fun fetchPayload(songId: String): LyricPayload? {
        val ref = HuaweiSongRef.parse(songId) ?: return null
        val remembered = lyricFiles.recall(ref.contentId)
        val file = remembered ?: resolve(ref) ?: return null
        return when (val download = download(file)) {
            is Download.Text -> HuaweiLrc.split(download.text, file.subTypes)
            // 网络错、5xx：不重新解析，也不重试，留给调用方下次再点。
            Download.Unavailable -> null
            Download.Gone -> {
                // 刚搜出来的地址就失效 = 这首歌现在真的没词。
                if (remembered == null) return null
                lyricFiles.forget(ref.contentId)
                val fresh = resolve(ref) ?: return null
                if (fresh.address == file.address) return null
                (download(fresh) as? Download.Text)?.let { HuaweiLrc.split(it.text, fresh.subTypes) }
            }
        }
    }

    /** 用 id 里的查询提示重新搜索，只认 contentID 完全相同的那一条（含同曲其他版本）。 */
    private suspend fun resolve(ref: HuaweiSongRef): LyricFile? {
        val songs = findSongs(ref.hint, SEARCH_PAGE_SIZE) ?: return null
        return songs
            .flatMap { listOf(it) + it.otherVersions }
            .firstOrNull { it.contentId == ref.contentId }
            ?.lyric
            ?.takeIf { it.usable }
    }

    private suspend fun download(file: LyricFile): Download {
        val url = file.address.toHttpUrlOrNull() ?: return Download.Gone
        val request = Request.Builder().url(url).get().header("User-Agent", USER_AGENT).build()
        return try {
            client.awaitResponse(request).use { response ->
                when {
                    response.code in GONE_STATUS_CODES -> Download.Gone
                    !response.isSuccessful -> {
                        Log.w(TAG, "Huawei lyric failed: ${response.code}")
                        Download.Unavailable
                    }
                    else -> {
                        val source = response.body.source()
                        if (source.request(MAX_LYRIC_BYTES + 1)) {
                            Log.w(TAG, "Huawei lyric larger than $MAX_LYRIC_BYTES bytes")
                            return@use Download.Unavailable
                        }
                        val text = source.buffer.readByteArray().toString(Charsets.UTF_8)
                        if (text.isBlank() || isMissingObject(text)) Download.Gone else Download.Text(text)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Huawei lyric error: ${e.message}")
            Download.Unavailable
        }
    }

    /** tingmall 上被删的文件回一段 S3 风格的 `<Code>NoSuchKey</Code>` XML。 */
    private fun isMissingObject(text: String): Boolean =
        text.trimStart().startsWith('<') && NO_SUCH_KEY in text

    // ── 搜索 ─────────────────────────────────────────────────────────────────

    /** null = 没答上（网络、非 2xx、解析失败、resultCode 不对）；空列表是真实的"搜不到"。 */
    private suspend fun findSongs(query: String, pageSize: Int): List<HuaweiSong>? {
        if (query.isEmpty()) return emptyList()
        val body = buildJsonObject {
            put("contentType", CONTENT_TYPE_SONG)
            put("queryWord", query)
            put("start", "0")
            put("limit", pageSize.toString())
        }
        val request = Request.Builder()
            .url(SEARCH_URL)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("User-Agent", USER_AGENT)
            .build()
        val root = fetchJson(request) ?: return null
        val resultCode = root.obj("result")?.string("resultCode")
        if (resultCode != SUCCESS_CODE) {
            Log.w(TAG, "Huawei search refused: $resultCode")
            return null
        }
        val songs = root.array("songSimpleInfos")?.mapNotNull(::parseSong).orEmpty()
        songs.flatMap { listOf(it) + it.otherVersions }.forEach { song ->
            song.lyric?.takeIf { it.usable }?.let { lyricFiles.remember(song.contentId, it) }
        }
        return songs
    }

    private fun parseSong(element: JsonElement): HuaweiSong? {
        val item = element as? JsonObject ?: return null
        val contentId = item.string("contentID")?.trim()?.takeIf(CONTENT_ID::matches) ?: return null
        val title = item.string("contentName")?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val extra = item.nested("contentExInfo")
        val artistLine = item.string("artistName")?.trim()?.takeIf(String::isNotEmpty)
            ?: extra?.string("artistNames")?.trim()
            ?: ""
        return HuaweiSong(
            contentId = contentId,
            title = title,
            artistLine = artistLine,
            album = item.string("albumName")?.trim()?.takeIf(String::isNotEmpty),
            lyric = lyricFileOf(item, extra),
            otherVersions = item.array("childContents")?.mapNotNull(::parseSong).orEmpty(),
        )
    }

    /** 地址按 `lyrics.lineByLineLyric` → `lyricAddress` → `contentExInfo.lyricAddres`（原文少个 s）取，只要 https。 */
    private fun lyricFileOf(item: JsonObject, extra: JsonObject?): LyricFile? {
        val lyrics = extra?.nested("lyrics")
        val address = listOfNotNull(
            lyrics?.string("lineByLineLyric"),
            item.string("lyricAddress"),
            extra?.string("lyricAddres"),
        ).map(String::trim).firstOrNull { it.toHttpUrlOrNull()?.isHttps == true } ?: return null
        val subTypes = lyrics?.string("lineByLineLyric_subType")
            ?.split('|')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.toSet()
            .orEmpty()
        return LyricFile(address = address, subTypes = subTypes)
    }

    // ── HTTP / JSON ──────────────────────────────────────────────────────────

    private suspend fun fetchJson(request: Request): JsonObject? {
        val text = try {
            client.awaitResponse(request).use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Huawei search failed: ${response.code}")
                    return null
                }
                response.body.string()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Huawei search error: ${e.message}")
            return null
        }
        return try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            Log.w(TAG, "Huawei search parse error: ${e.message}")
            null
        }
    }

    /** `contentExInfo`、`lyrics` 是"JSON 里的 JSON 字符串"；万一哪天直接给对象也认。 */
    private fun JsonObject.nested(key: String): JsonObject? {
        (this[key] as? JsonObject)?.let { return it }
        val text = string(key)?.takeIf(String::isNotBlank) ?: return null
        return try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        }
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    // ── 内部类型 ─────────────────────────────────────────────────────────────

    private class HuaweiSong(
        val contentId: String,
        val title: String,
        /** 平台原样的艺人串：`/` 分隔多人，括号里是别名，如 `米津玄師(よねづ けんし)/Daoko(ダヲコ)`。 */
        val artistLine: String,
        /** 专辑名，只给匹配器认现场 / 访谈专辑用。 */
        val album: String?,
        val lyric: LyricFile?,
        /** 同一首歌在别的专辑里的版本（`childContents`），只用来按 contentID 找回。 */
        val otherVersions: List<HuaweiSong>,
    ) {
        val hasUsableLyric: Boolean get() = lyric?.usable == true

        fun toMatch(): SongMatch = SongMatch(
            songId = HuaweiSongRef(
                contentId = contentId,
                hint = (listOf(title) + artistParts().map(::withoutAlias)).joinToString(" "),
            ).encode(),
            title = title,
            artist = artistLine,
        )

        /** 外文歌名后面附的中文译名不算版本注记，比对前去掉（见 [withoutTranslatedTitle]）。 */
        fun matcherTitle(): String = withoutTranslatedTitle(title)

        /** `Name(Alias)` 拆成两个名字，让 `NewJeans`、`뉴진스` 都能对上 `NewJeans(뉴진스)`。 */
        fun matcherArtists(): List<String> = artistParts().flatMap { part ->
            val alias = ARTIST_ALIAS.find(part)
            val name = alias?.groupValues?.get(1)?.trim().orEmpty()
            if (alias == null || name.isEmpty()) listOf(part) else listOf(name, alias.groupValues[2].trim())
        }

        private fun artistParts(): List<String> =
            artistLine.split('/', '／').map(String::trim).filter(String::isNotEmpty)
    }

    private class LyricFile(val address: String, val subTypes: Set<String>) {
        /** `abs` = 一行"纯音乐"占位，不值得下载。 */
        val usable: Boolean get() = SUBTYPE_INSTRUMENTAL !in subTypes
    }

    private sealed interface Download {
        data class Text(val text: String) : Download

        /** 403 / 404 / 410 / NoSuchKey / 空文件：这个地址不会再有内容了。 */
        data object Gone : Download
        data object Unavailable : Download
    }

    /** 搜索里见过的 contentID → 歌词文件。有界 LRU，只属于这个 provider 实例。 */
    private class LyricFileMemory(private val capacity: Int) {
        private val files = object : LinkedHashMap<String, LyricFile>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LyricFile>?): Boolean =
                size > capacity
        }

        @Synchronized
        fun remember(contentId: String, file: LyricFile) {
            files[contentId] = file
        }

        @Synchronized
        fun recall(contentId: String): LyricFile? = files[contentId]

        @Synchronized
        fun forget(contentId: String) {
            files.remove(contentId)
        }
    }

    companion object {
        const val NAME = "huawei"

        private const val TAG = "HuaweiLyricsProvider"
        private const val UNAVAILABLE_MESSAGE = "Huawei Music is unavailable right now"

        private const val SEARCH_URL =
            "https://api-drcn.music.dbankcloud.cn/music-search-service/v10/service/fuzzysearch"
        private const val USER_AGENT = "Yoin/0.1.0 (https://github.com/p2o51/Yoin)"
        private const val CONTENT_TYPE_SONG = "1"
        private const val SUCCESS_CODE = "000000"
        private const val NO_SUCH_KEY = "<Code>NoSuchKey</Code>"
        private const val SUBTYPE_INSTRUMENTAL = "abs"

        /** 一条结果约 10 KB（`contentExInfo` 很大），自动匹配和重新解析都只要一页 10 条。 */
        private const val SEARCH_PAGE_SIZE = 10
        private const val MAX_PAGE_SIZE = 20
        private const val LYRIC_MEMORY_CAPACITY = 256
        private const val MAX_LYRIC_BYTES = 512L * 1024L
        private val GONE_STATUS_CODES = setOf(403, 404, 410)

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val CONTENT_ID = Regex("[A-Za-z0-9_-]{1,64}")
        private val ARTIST_ALIAS = Regex("""^(.*?)\s*[(（]([^()（）]+)[)）]\s*$""")
        private val TRANSLATED_TITLE = Regex("""\s*[(（]([^()（）]+)[)）]\s*$""")

        /** 括号里出现这些字的是版本注记（伴奏、现场、翻唱……），不是歌名译文。 */
        private val VERSION_NOTE_WORDS = listOf(
            "伴奏", "纯音乐", "版", "现场", "混音", "翻", "原唱", "改编", "铃声", "片段",
            "演唱会", "剪辑", "合唱", "钢琴", "吉他", "八音盒", "音乐盒", "女声", "男声",
            "加速", "降调", "升调", "慢速",
        )

        private fun queryOf(title: String, artist: String): String =
            listOf(title, artist).map(String::trim).filter(String::isNotEmpty).joinToString(" ")

        private fun withoutAlias(artist: String): String {
            val alias = ARTIST_ALIAS.find(artist) ?: return artist
            return alias.groupValues[1].trim().ifEmpty { artist }
        }

        /**
         * `アイドル(偶像)`、`Pretender(假装者)` → `アイドル`、`Pretender`。只去掉结尾一段
         * **全是汉字**的括号，而且括号前面要有非汉字的字母（外文歌名）、括号里不能是
         * 版本注记。不去掉的话，带译名（也带译文）的原版会被当成注记版，输给排在后面、
         * 不带译名也不带译文的另一条录音。
         */
        internal fun withoutTranslatedTitle(title: String): String {
            val match = TRANSLATED_TITLE.find(title) ?: return title
            val core = title.substring(0, match.range.first)
            val note = match.groupValues[1].trim()
            val noteIsChineseName = note.isNotEmpty() &&
                note.all { isHan(it) || it == '·' || it == '・' || it.isWhitespace() } &&
                VERSION_NOTE_WORDS.none { it in note }
            val coreIsForeign = core.any { it.isLetter() && !isHan(it) }
            return if (noteIsChineseName && coreIsForeign) core.trimEnd() else title
        }

        private fun isHan(char: Char): Boolean =
            Character.UnicodeScript.of(char.code) == Character.UnicodeScript.HAN

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * 华为音乐歌曲的对外 id：`hw1|<contentID>|<查询提示>`。查询提示是华为自己的歌名加去掉
 * 别名的艺人名，URL 编码（所以不会含 `|`），编码前截到 [MAX_HINT_CODE_POINTS] 个码点。
 * 用它重新搜索，能在第一页里找回同一个 contentID（2026-10-05 从日本实测 24/24）。
 * 歌词地址不进 id：它对应的是某一版歌词文件，会换。
 */
internal data class HuaweiSongRef(val contentId: String, val hint: String) {

    fun encode(): String = "$PREFIX|$contentId|${URLEncoder.encode(hint.takeCodePoints(MAX_HINT_CODE_POINTS), UTF_8)}"

    companion object {
        private const val PREFIX = "hw1"
        private const val UTF_8 = "UTF-8"
        private const val MAX_HINT_CODE_POINTS = 100

        /** 100 个四字节码点 URL 编码后最长 1200 字符。 */
        private const val MAX_ENCODED_HINT = 1200
        private val CONTENT_ID = Regex("[A-Za-z0-9_-]{1,64}")

        fun parse(songId: String): HuaweiSongRef? {
            val parts = songId.split('|')
            if (parts.size != 3 || parts[0] != PREFIX) return null
            val (_, contentId, encodedHint) = parts
            if (!CONTENT_ID.matches(contentId) || encodedHint.length !in 1..MAX_ENCODED_HINT) return null
            val hint = try {
                URLDecoder.decode(encodedHint, UTF_8).trim()
            } catch (e: IllegalArgumentException) {
                return null
            }
            return if (hint.isEmpty()) null else HuaweiSongRef(contentId, hint)
        }

        private fun String.takeCodePoints(max: Int): String {
            if (codePointCount(0, length) <= max) return this
            return substring(0, offsetByCodePoints(0, max))
        }
    }
}
