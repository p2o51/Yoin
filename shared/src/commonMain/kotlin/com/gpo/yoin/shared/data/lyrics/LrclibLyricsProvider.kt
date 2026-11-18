package com.gpo.yoin.shared.data.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.http.URLBuilder
import kotlin.math.roundToLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * LRCLIB 歌词源（https://lrclib.net）—— FOSS、无 key、无速率限制，LRC 同步歌词。
 * 两次 HTTP GET：
 * 1. 搜索：`/api/search?track_name=&artist_name=`，返回数组（LRCLIB 的搜索是模糊的）。
 *    自动匹配先去掉 `instrumental` 的条目，再交给 [LyricCandidateMatcher] 认标题、艺人、专辑，
 *    同档里挑长度对得上的——同一首歌在 LRCLIB 常有单曲、专辑、合辑好几条，第一条不一定是
 *    正在播的那个版本；长度只在认出来的条目里起作用，后排同长的伴奏、翻唱抢不走前排的原版。
 *    匹配器一条都认不出时，照旧取第一条。
 * 2. 取词：`/api/get/{id}`，返回 `{syncedLyrics, plainLyrics, ...}`
 *    优先 `syncedLyrics`（带时间戳），兜底 `plainLyrics`。
 *
 * LRCLIB 的 rate-limit 文档建议带上可识别 User-Agent（虽然目前没强制），补上以免
 * 将来被 429。
 */
internal class LrclibLyricsProvider(
    engine: HttpClientEngine? = null,
    private val json: Json = LyricJson,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : LyricProvider() {

    private val client: HttpClient = newLyricHttpClient(engine)

    override fun close() = client.close()

    override val name: String = "lrclib"

    private val matcher = LyricCandidateMatcher()

    override suspend fun search(title: String, artist: String, durationMs: Long?): SongMatch? {
        val tracks = findTracks(title, artist, limit = SEARCH_PAGE_SIZE).filterNot(LrclibTrack::instrumental)
        val picked = matcher.pick(
            candidates = tracks.map { track ->
                LyricCandidateMatcher.Candidate(
                    title = track.match.title,
                    artists = artistsOf(track.match.artist),
                    album = track.album,
                    durationMs = track.durationMs,
                )
            },
            title = title,
            artist = artist,
            durationMs = durationMs,
        )
        return (picked?.let(tracks::get) ?: tracks.firstOrNull())?.match
    }

    /** LRCLIB 的艺人是一整串（`Sam Smith, Kim Petras`）：整串和拆开的每一位都交给匹配器。 */
    private fun artistsOf(credit: String): List<String> =
        (listOf(credit) + credit.split(ARTIST_CREDIT_SEPARATOR))
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()

    override suspend fun searchMultiple(
        title: String,
        artist: String,
        limit: Int,
    ): List<SongMatch> = findTracks(title, artist, limit).map(LrclibTrack::match)

    private suspend fun findTracks(
        title: String,
        artist: String,
        limit: Int,
    ): List<LrclibTrack> {
        val url = URLBuilder("$baseUrl/api/search").apply {
            parameters.append("track_name", title)
            parameters.append("artist_name", artist)
        }.build()

        return attempt(onError = { emptyList() }) {
            val reply = client.getText(url, HEADERS)
            if (!reply.isSuccessful) return@attempt emptyList<LrclibTrack>()
            val arr = json.parseToJsonElement(reply.text).jsonArray
            arr.asSequence().take(limit).mapNotNull { el ->
                val obj = el.jsonObject
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val songTitle = obj["trackName"]?.jsonPrimitive?.contentOrNull ?: title
                val songArtist = obj["artistName"]?.jsonPrimitive?.contentOrNull ?: artist
                // `duration`：秒，可能带小数。
                val seconds = obj["duration"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                LrclibTrack(
                    match = SongMatch(songId = id, title = songTitle, artist = songArtist),
                    album = obj["albumName"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank),
                    durationMs = seconds?.takeIf { it > 0.0 }?.let { (it * 1_000).roundToLong() },
                    instrumental = obj["instrumental"]?.jsonPrimitive?.booleanOrNull == true,
                )
            }.toList()
        }
    }

    private class LrclibTrack(
        val match: SongMatch,
        val album: String?,
        val durationMs: Long?,
        val instrumental: Boolean,
    )

    override suspend fun fetchLyric(songId: String): String? {
        val url = URLBuilder("$baseUrl/api/get/$songId").build()

        return attempt(onError = { null }) {
            val reply = client.getText(url, HEADERS)
            if (!reply.isSuccessful) return@attempt null
            val obj = json.parseToJsonElement(reply.text).jsonObject
            val synced = obj["syncedLyrics"]?.jsonPrimitive?.contentOrNull
            val plain = obj["plainLyrics"]?.jsonPrimitive?.contentOrNull
            synced?.takeIf { it.isNotBlank() } ?: plain?.takeIf { it.isNotBlank() }
        }
    }

    companion object {
        private const val DEFAULT_BASE_URL = "https://lrclib.net"

        /** 自动匹配看这么多条，在里面找长度对得上的。 */
        private const val SEARCH_PAGE_SIZE = 10

        /** 艺人串里的多人分隔：`,`、`&`、`/`、`;`、`feat.` / `ft.`。 */
        private val ARTIST_CREDIT_SEPARATOR = Regex(
            """\s*(?:[,&/;]|\s(?:feat\.?|ft\.|featuring)\s)\s*""",
            RegexOption.IGNORE_CASE,
        )

        private val HEADERS: List<Pair<String, String>> = listOf(
            "user-agent" to "Yoin/0.1.0 (https://github.com/p2o51/Yoin)",
            "accept" to "application/json",
        )
    }
}
