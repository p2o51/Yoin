package com.gpo.yoin.playground.trackmatch

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Locale
import java.util.Random
import kotlin.math.abs
import kotlin.math.min

private const val DEFAULT_INPUT = ".context/attachments/liked_songs.csv"
private const val DEFAULT_OUTPUT = "playground/track-match/build/reports/track-match-report.json"
private const val USER_AGENT = "YoinTrackMatchPlayground/0.1 (local research)"

fun main(args: Array<String>) {
    val config = PlaygroundConfig.from(args)
    val rows = parseLikedSongsCsv(File(config.input))
    val sample = deterministicSample(rows, config.sampleSize, config.seed)
    val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    val token = when (config.spotifyTokenSource) {
        TokenSource.NONE -> null
        TokenSource.ENV -> envBearerToken()
        TokenSource.YOIN_ADB -> yoinAdbToken(config, http) ?: envBearerToken()
    }

    val spotifyResults = if (token == null) {
        emptyMap()
    } else {
        fetchSpotifyTracks(sample.map { it.trackId }, token, http)
    }

    var accepted = 0
    var needsReview = 0
    val results = JsonArray()
    sample.forEachIndexed { index, row ->
        val spotify = spotifyResults[row.trackId]
        val mb = spotify?.isrc
            ?.takeIf(String::isNotBlank)
            ?.let { lookupMusicBrainzByIsrc(it, row, http) }
            ?: MatchResult.NoMatch("spotify-isrc-missing")
        when (mb.status) {
            "accepted" -> accepted += 1
            "needs-review" -> needsReview += 1
        }
        results.add(resultJson(index, row, spotify, mb))
        Thread.sleep(config.musicBrainzDelayMs)
    }

    val report = JsonObject().apply {
        addProperty("sample_size", sample.size)
        addProperty("seed", config.seed)
        addProperty("spotify_returned", spotifyResults.size)
        addProperty("isrc_found", spotifyResults.values.count { !it.isrc.isNullOrBlank() })
        add(
            "musicbrainz_status_counts",
            JsonObject().apply {
                addProperty("accepted", accepted)
                addProperty("needs-review", needsReview)
                addProperty("no-match", sample.size - accepted - needsReview)
            },
        )
        add("results", results)
    }

    val output = File(config.output)
    output.parentFile?.mkdirs()
    output.writeText(GsonBuilder().setPrettyPrinting().create().toJson(report))

    println("Report ${output.path}")
    println("Spotify returned ${spotifyResults.size}/${sample.size}; ISRC ${spotifyResults.values.count { !it.isrc.isNullOrBlank() }}/${sample.size}")
    println("MusicBrainz accepted=$accepted needs-review=$needsReview no-match=${sample.size - accepted - needsReview}")
}

data class PlaygroundConfig(
    val input: String = DEFAULT_INPUT,
    val output: String = DEFAULT_OUTPUT,
    val sampleSize: Int = 100,
    val seed: Long = 42L,
    val spotifyTokenSource: TokenSource = TokenSource.YOIN_ADB,
    val spotifyClientId: String? = System.getenv("SPOTIFY_CLIENT_ID")?.takeIf(String::isNotBlank),
    val adb: String = System.getenv("ADB")?.takeIf(String::isNotBlank)
        ?: "/Users/gpo/Library/Android/sdk/platform-tools/adb",
    val deviceSerial: String? = System.getenv("ANDROID_SERIAL")?.takeIf(String::isNotBlank),
    val musicBrainzDelayMs: Long = 1_050L,
) {
    companion object {
        fun from(args: Array<String>): PlaygroundConfig {
            val values = args.toList().chunked(2).associate { chunk ->
                require(chunk.size == 2 && chunk[0].startsWith("--")) {
                    "Expected --key value arguments, got: ${chunk.joinToString(" ")}"
                }
                chunk[0].removePrefix("--") to chunk[1]
            }
            return PlaygroundConfig(
                input = values["input"] ?: DEFAULT_INPUT,
                output = values["output"] ?: DEFAULT_OUTPUT,
                sampleSize = values["sample-size"]?.toInt() ?: 100,
                seed = values["seed"]?.toLong() ?: 42L,
                spotifyTokenSource = values["spotify-token-source"]
                    ?.let { TokenSource.from(it) }
                    ?: TokenSource.YOIN_ADB,
                spotifyClientId = values["spotify-client-id"]
                    ?: System.getenv("SPOTIFY_CLIENT_ID")?.takeIf(String::isNotBlank),
                adb = values["adb"]
                    ?: System.getenv("ADB")?.takeIf(String::isNotBlank)
                    ?: "/Users/gpo/Library/Android/sdk/platform-tools/adb",
                deviceSerial = values["device"]
                    ?: System.getenv("ANDROID_SERIAL")?.takeIf(String::isNotBlank),
                musicBrainzDelayMs = values["musicbrainz-delay-ms"]?.toLong() ?: 1_050L,
            )
        }
    }
}

enum class TokenSource {
    YOIN_ADB,
    ENV,
    NONE,
    ;

    companion object {
        fun from(value: String): TokenSource = when (value.lowercase(Locale.US)) {
            "yoin-adb" -> YOIN_ADB
            "env" -> ENV
            "none" -> NONE
            else -> error("Unknown token source: $value")
        }
    }
}

data class LikedSongRow(
    val savedAt: String,
    val trackId: String,
    val trackName: String,
    val artists: String,
    val albumName: String,
    val durationMs: Long?,
)

data class SpotifyTrackResult(
    val id: String,
    val name: String?,
    val artists: List<String>,
    val albumName: String?,
    val durationMs: Long?,
    val isrc: String?,
)

sealed class MatchResult(
    val status: String,
    val reason: String,
) {
    data class Matched(
        val score: Int,
        val mbid: String,
        val title: String?,
        val artists: List<String>,
        val releases: List<String>,
        val matchStatus: String,
    ) : MatchResult(matchStatus, "scored")

    class NoMatch(reason: String) : MatchResult("no-match", reason)
}

fun parseLikedSongsCsv(file: File): List<LikedSongRow> {
    val lines = file.readLines()
    if (lines.isEmpty()) return emptyList()
    val header = parseCsvLine(lines.first()).mapIndexed { index, name -> name to index }.toMap()
    fun col(row: List<String>, name: String): String = row.getOrNull(header.getValue(name)).orEmpty()
    return lines.drop(1)
        .filter(String::isNotBlank)
        .map { parseCsvLine(it) }
        .map { row ->
            LikedSongRow(
                savedAt = col(row, "saved_at"),
                trackId = col(row, "track_id"),
                trackName = col(row, "track_name"),
                artists = col(row, "artists"),
                albumName = col(row, "album_name"),
                durationMs = col(row, "duration_ms").toLongOrNull(),
            )
        }
        .filter { it.trackId.isNotBlank() && it.trackName.isNotBlank() }
}

fun deterministicSample(rows: List<LikedSongRow>, sampleSize: Int, seed: Long): List<LikedSongRow> =
    rows.shuffled(Random(seed)).take(min(sampleSize, rows.size))

fun parseCsvLine(line: String): List<String> {
    val fields = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    var index = 0
    while (index < line.length) {
        val char = line[index]
        when {
            char == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                current.append('"')
                index += 1
            }
            char == '"' -> quoted = !quoted
            char == ',' && !quoted -> {
                fields += current.toString()
                current.clear()
            }
            else -> current.append(char)
        }
        index += 1
    }
    fields += current.toString()
    return fields
}

private fun envBearerToken(): String? =
    System.getenv("SPOTIFY_BEARER_TOKEN")?.takeIf(String::isNotBlank)

private fun yoinAdbToken(config: PlaygroundConfig, http: HttpClient): String? {
    val uri = "content://com.gpo.yoin.debug.spotifytoken/access_token?includeRefreshToken=true"
    val command = buildList {
        add(config.adb)
        config.deviceSerial?.let {
            add("-s")
            add(it)
        }
        addAll(listOf("shell", "content", "query", "--uri", uri))
    }
    val process = ProcessBuilder(command)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText()
    if (process.waitFor() != 0) return null

    val row = parseContentQueryRow(output)
    val accessToken = row["access_token"]?.takeIf(String::isNotBlank)
    val refreshToken = row["refresh_token"]?.takeIf(String::isNotBlank)
    if (refreshToken != null && !config.spotifyClientId.isNullOrBlank()) {
        return refreshSpotifyToken(refreshToken, config.spotifyClientId, http)
    }
    return accessToken
}

fun parseContentQueryRow(output: String): Map<String, String> {
    val row = output.lineSequence().firstOrNull { it.startsWith("Row:") } ?: return emptyMap()
    return row.substringAfter(" ").split(", ").mapNotNull { part ->
        val separator = part.indexOf('=')
        if (separator <= 0) null else part.substring(0, separator) to part.substring(separator + 1)
    }.toMap()
}

private fun refreshSpotifyToken(refreshToken: String, clientId: String, http: HttpClient): String? {
    val body = form(
        "grant_type" to "refresh_token",
        "refresh_token" to refreshToken,
        "client_id" to clientId,
    )
    val request = HttpRequest.newBuilder(URI("https://accounts.spotify.com/api/token"))
        .timeout(Duration.ofSeconds(30))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
    val response = http.send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() !in 200..299) return null
    return JsonParser.parseString(response.body()).asJsonObject["access_token"]?.asString
}

private fun fetchSpotifyTracks(
    trackIds: List<String>,
    token: String,
    http: HttpClient,
): Map<String, SpotifyTrackResult> = trackIds
    .chunked(50)
    .flatMap { batch ->
        val request = HttpRequest.newBuilder(
            URI("https://api.spotify.com/v1/tracks?ids=${batch.joinToString(",")}"),
        )
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer $token")
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            error("Spotify tracks request failed: ${response.statusCode()} ${response.body()}")
        }
        JsonParser.parseString(response.body()).asJsonObject["tracks"].asJsonArray
            .mapNotNull { element ->
                if (element.isJsonNull) return@mapNotNull null
                val track = element.asJsonObject
                val id = track["id"]?.asString ?: return@mapNotNull null
                SpotifyTrackResult(
                    id = id,
                    name = track["name"]?.asString,
                    artists = track["artists"]?.asJsonArray
                        ?.mapNotNull { it.asJsonObject["name"]?.asString }
                        .orEmpty(),
                    albumName = track["album"]?.asJsonObject?.get("name")?.asString,
                    durationMs = track["duration_ms"]?.asLong,
                    isrc = track["external_ids"]?.asJsonObject?.get("isrc")?.asString,
                )
            }
    }
    .associateBy(SpotifyTrackResult::id)

private fun lookupMusicBrainzByIsrc(
    isrc: String,
    row: LikedSongRow,
    http: HttpClient,
): MatchResult {
    val request = HttpRequest.newBuilder(
        URI("https://musicbrainz.org/ws/2/isrc/$isrc?fmt=json&inc=artist-credits+releases"),
    )
        .timeout(Duration.ofSeconds(30))
        .header("User-Agent", USER_AGENT)
        .GET()
        .build()
    val response = http.send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() == 404) return MatchResult.NoMatch("isrc-not-found")
    if (response.statusCode() !in 200..299) {
        return MatchResult.NoMatch("musicbrainz-http-${response.statusCode()}")
    }
    val recordings = JsonParser.parseString(response.body())
        .asJsonObject["recordings"]
        ?.asJsonArray
        ?: return MatchResult.NoMatch("no-recordings")
    val candidates = recordings.mapNotNull { element ->
        val recording = element.asJsonObject
        val mbid = recording["id"]?.asString ?: return@mapNotNull null
        val title = recording["title"]?.asString
        val artists = recording["artist-credit"]?.asJsonArray
            ?.mapNotNull { credit -> credit.asJsonObject["artist"]?.asJsonObject?.get("name")?.asString }
            .orEmpty()
        val releases = recording["releases"]?.asJsonArray
            ?.mapNotNull { release -> release.asJsonObject["title"]?.asString }
            .orEmpty()
        val length = recording["length"]?.asLong
        val score = scoreCandidate(row, title, artists, releases, length)
        MatchResult.Matched(
            score = score,
            mbid = mbid,
            title = title,
            artists = artists,
            releases = releases.take(5),
            matchStatus = when {
                score >= 80 -> "accepted"
                score >= 60 -> "needs-review"
                else -> "no-match"
            },
        )
    }
    val best = candidates.maxByOrNull { it.score } ?: return MatchResult.NoMatch("no-candidates")
    return if (best.score >= 60) best else MatchResult.NoMatch("low-score")
}

fun scoreCandidate(
    row: LikedSongRow,
    title: String?,
    artists: List<String>,
    releases: List<String>,
    durationMs: Long?,
): Int {
    var score = 70
    if (normalize(row.trackName) == normalize(title.orEmpty())) score += 15
    val rowArtists = row.artists.split(";").flatMap { it.split(",") }.map(::normalize).filter(String::isNotBlank)
    val mbArtists = artists.map(::normalize).filter(String::isNotBlank)
    if (rowArtists.any { artist -> mbArtists.any { it == artist || it.contains(artist) || artist.contains(it) } }) {
        score += 10
    }
    val album = normalize(row.albumName)
    if (album.isNotBlank() && releases.any { normalize(it) == album }) score += 5
    if (row.durationMs != null && durationMs != null && abs(row.durationMs - durationMs) <= 3_000L) score += 5
    return score
}

fun normalize(value: String): String =
    value.lowercase(Locale.ROOT)
        .replace(Regex("[\\p{Punct}〜～・∙]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

private fun resultJson(
    index: Int,
    row: LikedSongRow,
    spotify: SpotifyTrackResult?,
    match: MatchResult,
): JsonObject = JsonObject().apply {
    addProperty("index", index)
    add("csv", JsonObject().apply {
        addProperty("saved_at", row.savedAt)
        addProperty("track_id", row.trackId)
        addProperty("track_name", row.trackName)
        addProperty("artists", row.artists)
        addProperty("album_name", row.albumName)
        row.durationMs?.let { addProperty("duration_ms", it) }
    })
    add("spotify", JsonObject().apply {
        spotify?.let {
            addProperty("status", "ok")
            addProperty("name", it.name)
            addProperty("artists", it.artists.joinToString("; "))
            addProperty("album_name", it.albumName)
            it.durationMs?.let { duration -> addProperty("duration_ms", duration) }
            addProperty("isrc", it.isrc)
        } ?: addProperty("status", "missing")
    })
    add("musicbrainz", JsonObject().apply {
        addProperty("status", match.status)
        addProperty("reason", match.reason)
        if (match is MatchResult.Matched) {
            addProperty("score", match.score)
            addProperty("recording_mbid", match.mbid)
            addProperty("title", match.title)
            addProperty("artists", match.artists.joinToString("; "))
            addProperty("releases", match.releases.joinToString("; "))
        }
    })
}

private fun form(vararg pairs: Pair<String, String>): String =
    pairs.joinToString("&") { (key, value) ->
        "${urlEncode(key)}=${urlEncode(value)}"
    }

private fun urlEncode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)
