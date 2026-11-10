/*
 * SubsonicClient — Yoin's shared Subsonic / OpenSubsonic client (iOS today;
 * Android still uses its own Retrofit client in app/data/remote).
 *
 * Using it from Swift (framework `YoinShared`):
 *
 *     import YoinShared
 *
 *     let client = SubsonicClient(baseUrl: "https://music.example.com",
 *                                 username: "alice", password: "secret")
 *     // or, with a token you saved instead of the password:
 *     // SubsonicClient(baseUrl:username:token:salt:)
 *
 *     do {
 *         let info = try await client.ping()                       // ServerInfo
 *         let newest = try await client.getAlbumList2(type: .newest, size: 20, offset: 0)
 *         let album = try await client.getAlbum(id: newest[0].id)   // AlbumInfo?
 *         let url = client.streamUrl(id: album!.songs[0].id, maxBitRate: nil, format: nil)
 *         let art = client.coverArtUrl(id: album!.coverArtId, size: 600)
 *         let lyrics = try await client.lyricsFor(songId: song.id, artist: song.artist, title: song.title)
 *     } catch {
 *         // Every failure is a SubsonicException; CancellationException on Task cancel.
 *         if let e = (error as NSError).kotlinException as? SubsonicException {
 *             switch e.kind {
 *             case .wrongCredentials, .tokenAuthNotSupported: break // ask to sign in again
 *             case .notFound: break
 *             case .network: break
 *             default: break
 *             }
 *         }
 *     }
 *     client.close()   // when the profile goes away
 *
 * Notes for Swift:
 * - Every `suspend fun` here is `async throws` in Swift and is annotated
 *   @Throws(SubsonicException, CancellationException): network, HTTP and
 *   parse failures are all wrapped, so nothing else escapes (an undeclared
 *   Kotlin exception would abort the process).
 * - Kotlin default arguments aren't exported: pass every argument (`nil` for
 *   optional ones).
 * - Optional numbers arrive as `KotlinInt?` / `KotlinLong?`; pass them with
 *   `KotlinInt(int: 320)` / `KotlinLong(value: ms)`.
 * - Concurrency: the client is immutable after construction (token / salt
 *   fixed) and Ktor's HttpClient is thread-safe, so any number of calls may
 *   run at once from any thread / Task (`async let`, TaskGroup) — they don't
 *   serialise. The framework is built with
 *   `objcExportSuspendFunctionLaunchThreadRestriction=none`, so calls can
 *   start off the main thread. Kotlin classes don't carry Swift's
 *   `Sendable`; under Swift 6 strict concurrency declare it once in Swift:
 *   `extension SubsonicClient: @retroactive @unchecked Sendable {}` (and the
 *   same for the immutable models if they cross actors).
 * - The client is cheap but owns an NSURLSession: keep one per server
 *   profile, `close()` it when the profile is removed.
 */
package com.gpo.yoin.shared.data.subsonic

import com.gpo.yoin.shared.data.model.AlbumInfo
import com.gpo.yoin.shared.data.model.ArtistIndex
import com.gpo.yoin.shared.data.model.ArtistInfo
import com.gpo.yoin.shared.data.model.Lyrics
import com.gpo.yoin.shared.data.model.PlaylistDetail
import com.gpo.yoin.shared.data.model.PlaylistSummary
import com.gpo.yoin.shared.data.model.SearchResult
import com.gpo.yoin.shared.data.model.ServerInfo
import com.gpo.yoin.shared.data.model.SongInfo
import com.gpo.yoin.shared.data.model.Starred
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

/** `getAlbumList2` list types. Swift: `AlbumListType.newest`, `.alphabeticalByName`, … */
enum class AlbumListType(val apiValue: String) {
    NEWEST("newest"),
    RECENT("recent"),
    FREQUENT("frequent"),
    RANDOM("random"),
    ALPHABETICAL_BY_NAME("alphabeticalByName"),
    ALPHABETICAL_BY_ARTIST("alphabeticalByArtist"),
    STARRED("starred"),
    HIGHEST("highest"),
}

class SubsonicClient private constructor(
    /** Server root as configured, e.g. `https://host/navidrome` (`/rest/...` is appended). */
    val baseUrl: String,
    val username: String,
    private val auth: TokenPair,
    engine: HttpClientEngine?,
) {
    /** Password auth: a fresh 16-char salt per client, `t = md5(password + salt)`. */
    constructor(baseUrl: String, username: String, password: String) :
        this(baseUrl, username, TokenPair.fromPassword(password, SubsonicAuth.newSalt()), null)

    /** Token auth with a token / salt pair you already have (see [token], [salt]). */
    constructor(baseUrl: String, username: String, token: String, salt: String) :
        this(baseUrl, username, TokenPair(token, salt), null)

    /** Tests: a MockEngine and a fixed salt. */
    internal constructor(
        baseUrl: String,
        username: String,
        password: String,
        salt: String,
        engine: HttpClientEngine,
    ) : this(baseUrl, username, TokenPair.fromPassword(password, salt), engine)

    /** `md5(password + salt)`; save it with [salt] to rebuild the client without the password. */
    val token: String get() = auth.token
    val salt: String get() = auth.salt

    internal class TokenPair(val token: String, val salt: String) {
        companion object {
            fun fromPassword(password: String, salt: String) = TokenPair(SubsonicAuth.token(password, salt), salt)
        }
    }

    private val http = HttpClient(engine ?: platformHttpEngine()) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
        }
    }

    // ---------------------------------------------------------------- library

    /** `ping`: checks URL + credentials and reports what the server is. */
    @Throws(SubsonicException::class, CancellationException::class)
    suspend fun ping(): ServerInfo {
        val body = call("ping")
        return ServerInfo(
            apiVersion = body.version,
            type = body.type,
            serverVersion = body.serverVersion,
            openSubsonic = body.openSubsonic == true,
        )
    }

    /**
     * OpenSubsonic `getOpenSubsonicExtensions`: the extension names the server lists (e.g. `transcodeOffset`,
     * which lets `stream` take `timeOffset`). Empty for a plain Subsonic server or any failure to answer:
     * an unknown server is treated as having none (Android `SubsonicServerTraits.Plain`).
     */
    @Throws(CancellationException::class)
    suspend fun getOpenSubsonicExtensions(): List<String> = try {
        call("getOpenSubsonicExtensions").openSubsonicExtensions.orEmpty().map { it.name }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        emptyList()
    }

    @Throws(SubsonicException::class, CancellationException::class)
    suspend fun getAlbumList2(type: AlbumListType, size: Int, offset: Int): List<AlbumInfo> =
        call("getAlbumList2", "type" to type.apiValue, "size" to size.toString(), "offset" to offset.toString())
            .albumList2?.album.orEmpty().map { it.toAlbumInfo() }

    /** Album with its songs; null when the server answers ok without one. A missing id throws [SubsonicErrorKind.NOT_FOUND]. */
    @Throws(SubsonicException::class, CancellationException::class)
    suspend fun getAlbum(id: String): AlbumInfo? =
        call("getAlbum", "id" to id).album?.toAlbumInfo()

    @Throws(SubsonicException::class, CancellationException::class)
    suspend fun getArtists(): List<ArtistIndex> =
        call("getArtists").artists?.index.orEmpty().map { it.toArtistIndex() }

    /** Artist with its albums (no songs). */
    @Throws(SubsonicException::class, CancellationException::class)
    suspend fun getArtist(id: String): ArtistInfo? =
        call("getArtist", "id" to id).artist?.toArtistInfo()

    @Throws(SubsonicException::class, CancellationException::class)
    suspend fun getPlaylists(): List<PlaylistSummary> =
        call("getPlaylists").playlists?.playlist.orEmpty().map { it.toPlaylistSummary(username) }

    @Throws(SubsonicException::class, CancellationException::class)
    suspend fun getPlaylist(id: String): PlaylistDetail? =
        call("getPlaylist", "id" to id).playlist?.toPlaylistDetail(username)

    /** `search3`. Counts are per kind; Subsonic's own default is 20. */
    @Throws(SubsonicException::class, CancellationException::class)
    suspend fun search3(query: String, artistCount: Int, albumCount: Int, songCount: Int): SearchResult {
        val body = call(
            "search3",
            "query" to query,
            "artistCount" to artistCount.toString(),
            "albumCount" to albumCount.toString(),
            "songCount" to songCount.toString(),
        )
        return body.searchResult3?.toSearchResult() ?: SearchResult(emptyList(), emptyList(), emptyList())
    }

    @Throws(SubsonicException::class, CancellationException::class)
    suspend fun getStarred2(): Starred =
        call("getStarred2").starred2?.toStarred() ?: Starred(emptyList(), emptyList(), emptyList())

    @Throws(SubsonicException::class, CancellationException::class)
    suspend fun getRandomSongs(size: Int): List<SongInfo> =
        call("getRandomSongs", "size" to size.toString()).randomSongs?.song.orEmpty().map { it.toSongInfo() }

    /** One song by id (`getSong`); null when the server answers ok without one. A missing id throws [SubsonicErrorKind.NOT_FOUND]. */
    @Throws(SubsonicException::class, CancellationException::class)
    suspend fun getSong(id: String): SongInfo? =
        call("getSong", "id" to id).song?.toSongInfo()

    // ----------------------------------------------------------- write actions

    /**
     * `star`: pass exactly the id kind you mean — a song in [id], an album in
     * [albumId], an artist in [artistId] (spec-strict servers only star an
     * artist through artistId). The others are nil.
     */
    @Throws(SubsonicException::class, CancellationException::class)
    suspend fun star(id: String?, albumId: String?, artistId: String?) {
        call("star", *starParams(id, albumId, artistId))
    }

    @Throws(SubsonicException::class, CancellationException::class)
    suspend fun unstar(id: String?, albumId: String?, artistId: String?) {
        call("unstar", *starParams(id, albumId, artistId))
    }

    /** `setRating`: 1–5, 0 clears. Out-of-range values are clamped like Android. */
    @Throws(SubsonicException::class, CancellationException::class)
    suspend fun setRating(id: String, rating: Int) {
        call("setRating", "id" to id, "rating" to rating.coerceIn(0, 5).toString())
    }

    /**
     * `scrobble`: [submission] false = "now playing", true = a finished play.
     * [timeMs] is when it was played (ms since 1970); nil lets the server use now.
     */
    @Throws(SubsonicException::class, CancellationException::class)
    suspend fun scrobble(id: String, submission: Boolean, timeMs: Long?) {
        val params = buildList {
            add("id" to id)
            add("submission" to submission.toString())
            timeMs?.let { add("time" to it.toString()) }
        }
        call("scrobble", *params.toTypedArray())
    }

    // -------------------------------------------------------- playlist writes

    /**
     * `createPlaylist` with [name] and, in order, [songIds] (repeated `songId`;
     * may be empty). Subsonic's createPlaylist has no comment parameter, so a
     * non-blank [comment] is set by a follow-up `updatePlaylist` (Android's
     * `createPlaylist(name, description)`). Returns the new playlist as the
     * server sent it (API 1.14+); null when an older server answers ok without
     * one — the comment can't be set then. The returned detail predates the
     * comment follow-up, so its `summary.comment` is null; refetch for it.
     */
    @Throws(SubsonicException::class, CancellationException::class)
    suspend fun createPlaylist(name: String, comment: String?, songIds: List<String>): PlaylistDetail? {
        val params = buildList {
            add("name" to name)
            songIds.forEach { add("songId" to it) }
        }
        val created = call("createPlaylist", *params.toTypedArray()).playlist ?: return null
        if (!comment.isNullOrBlank()) {
            call("updatePlaylist", "playlistId" to created.id, "comment" to comment)
        }
        return created.toPlaylistDetail(username)
    }

    /**
     * `updatePlaylist`, Subsonic's swiss-army edit. Every part is optional:
     * [name] renames, [comment] re-describes, [isPublic] shares / unshares,
     * [songIdsToAdd] appends (repeated `songIdToAdd`), [songIndexesToRemove]
     * removes by zero-based position in the current list (repeated
     * `songIndexToRemove`, sent in descending order like Android so earlier
     * removals don't shift later ones). Subsonic is last-writer-wins: there's
     * no snapshot token.
     */
    @Throws(SubsonicException::class, CancellationException::class)
    suspend fun updatePlaylist(
        id: String,
        name: String?,
        comment: String?,
        isPublic: Boolean?,
        songIdsToAdd: List<String>,
        songIndexesToRemove: List<Int>,
    ) {
        val params = buildList {
            add("playlistId" to id)
            name?.let { add("name" to it) }
            comment?.let { add("comment" to it) }
            isPublic?.let { add("public" to it.toString()) }
            songIdsToAdd.forEach { add("songIdToAdd" to it) }
            songIndexesToRemove.distinct().sortedDescending().forEach { add("songIndexToRemove" to it.toString()) }
        }
        call("updatePlaylist", *params.toTypedArray())
    }

    /** `deletePlaylist`. Only the owner (or an admin) may; others get [SubsonicErrorKind.NOT_AUTHORIZED]. */
    @Throws(SubsonicException::class, CancellationException::class)
    suspend fun deletePlaylist(id: String) {
        call("deletePlaylist", "id" to id)
    }

    // ----------------------------------------------------------------- shares

    /**
     * `createShare`: a public link to [ids] (songs, an album; a playlist id only on some servers,
     * e.g. Navidrome), named [description]. Returns the link; a server that answers without one
     * throws (Android SubsonicMusicSource.createShareLink).
     */
    @Throws(SubsonicException::class, CancellationException::class)
    suspend fun createShare(ids: List<String>, description: String?): String {
        val params = buildList {
            ids.forEach { add("id" to it) }
            description?.takeIf { it.isNotBlank() }?.let { add("description" to it) }
        }
        return call("createShare", *params.toTypedArray()).shares?.share?.firstOrNull()?.url?.takeIf { it.isNotBlank() }
            ?: throw SubsonicException(SubsonicErrorKind.GENERIC, SubsonicErrorKind.GENERIC.code, "createShare returned no share")
    }

    // ----------------------------------------------------------------- lyrics

    /** OpenSubsonic `getLyricsBySongId` (structured, usually synced). Null when the server has none. */
    @Throws(SubsonicException::class, CancellationException::class)
    suspend fun getLyricsBySongId(id: String): Lyrics? =
        call("getLyricsBySongId", "id" to id).lyricsList?.toLyrics()

    /** Classic `getLyrics(artist, title)`: plain text (parsed as LRC when it carries timestamps). */
    @Throws(SubsonicException::class, CancellationException::class)
    suspend fun getLyrics(artist: String?, title: String?): Lyrics? {
        val params = buildList {
            artist?.let { add("artist" to it) }
            title?.let { add("title" to it) }
        }
        return call("getLyrics", *params.toTypedArray()).lyrics?.toLyrics()
    }

    /**
     * Best lyrics the server has: [getLyricsBySongId] first; when that's
     * empty or the server doesn't know the endpoint (plain Subsonic), the
     * classic [getLyrics] with artist + title. Auth and network failures
     * are not swallowed.
     */
    @Throws(SubsonicException::class, CancellationException::class)
    suspend fun lyricsFor(songId: String, artist: String?, title: String?): Lyrics? {
        val structured = try {
            getLyricsBySongId(songId)
        } catch (e: SubsonicException) {
            if (e.isAuthFailure || e.kind == SubsonicErrorKind.NETWORK) throw e
            null
        }
        if (structured != null && structured.lines.isNotEmpty()) return structured
        if (artist.isNullOrBlank() && title.isNullOrBlank()) return structured
        return getLyrics(artist, title)?.takeIf { it.lines.isNotEmpty() } ?: structured
    }

    // ------------------------------------------------------------ URL builders

    /** Authenticated `stream.view` URL for AVPlayer. No network. */
    fun streamUrl(id: String, maxBitRate: Int?, format: String?): String {
        val params = buildList {
            add("id" to id)
            maxBitRate?.let { add("maxBitRate" to it.toString()) }
            format?.let { add("format" to it) }
        }
        return restUrl("stream", params)
    }

    /** Authenticated `getCoverArt.view` URL. [size] is the longest edge in px; nil = original. No network. */
    fun coverArtUrl(id: String, size: Int?): String {
        val params = buildList {
            add("id" to id)
            size?.let { add("size" to it.toString()) }
        }
        return restUrl("getCoverArt", params)
    }

    /** Releases the HTTP engine. Calls after this fail. */
    fun close() {
        http.close()
    }

    // --------------------------------------------------------------- plumbing

    private fun starParams(id: String?, albumId: String?, artistId: String?): Array<Pair<String, String>> =
        buildList {
            id?.let { add("id" to it) }
            albumId?.let { add("albumId" to it) }
            artistId?.let { add("artistId" to it) }
        }.toTypedArray()

    /** `<base>/rest/<endpoint>.view?<params>&u&t&s&v&c&f` — the Android URL shape and order. */
    internal fun restUrl(endpoint: String, params: List<Pair<String, String>>): String {
        val builder = URLBuilder(baseUrl.trim().trimEnd('/'))
        builder.appendPathSegments("rest", "$endpoint.view")
        params.forEach { (k, v) -> builder.parameters.append(k, v) }
        builder.parameters.append("u", username)
        builder.parameters.append("t", token)
        builder.parameters.append("s", salt)
        builder.parameters.append("v", API_VERSION)
        builder.parameters.append("c", CLIENT_NAME)
        builder.parameters.append("f", RESPONSE_FORMAT)
        return builder.buildString()
    }

    private suspend fun call(endpoint: String, vararg params: Pair<String, String>): SubsonicBody {
        val url = try {
            restUrl(endpoint, params.toList())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw SubsonicException(SubsonicErrorKind.NETWORK, SubsonicErrorKind.NETWORK.code, "Invalid server URL: $baseUrl", cause = e)
        }
        val response = try {
            http.get(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Ktor's timeout messages quote the request URL, which carries u/t/s: never pass them on.
            throw SubsonicException(
                SubsonicErrorKind.NETWORK,
                SubsonicErrorKind.NETWORK.code,
                redactCredentials(e.message ?: "Network error"),
                cause = e,
            )
        }
        val status = response.status.value
        val text = try {
            response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw SubsonicException(
                SubsonicErrorKind.NETWORK,
                SubsonicErrorKind.NETWORK.code,
                redactCredentials(e.message ?: "Network error"),
                status,
                e,
            )
        }
        val body = try {
            json.decodeFromString(SubsonicEnvelope.serializer(), text).response
        } catch (e: Throwable) {
            if (!response.status.isSuccess()) {
                throw SubsonicException(SubsonicErrorKind.HTTP, SubsonicErrorKind.HTTP.code, "HTTP $status", status)
            }
            throw SubsonicException(
                SubsonicErrorKind.PARSE,
                SubsonicErrorKind.PARSE.code,
                redactCredentials("Not a Subsonic response: ${e.message}"),
                status,
                e,
            )
        }
        if (body.status != "ok") {
            val error = body.error
            throw SubsonicException.api(error?.code ?: SubsonicErrorKind.GENERIC.code, error?.message, status)
        }
        if (!response.status.isSuccess()) {
            throw SubsonicException(SubsonicErrorKind.HTTP, SubsonicErrorKind.HTTP.code, "HTTP $status", status)
        }
        return body
    }

    companion object {
        const val API_VERSION: String = "1.16.1"
        const val CLIENT_NAME: String = "Yoin"
        const val RESPONSE_FORMAT: String = "json"
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val REQUEST_TIMEOUT_MS = 30_000L

        internal val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        private val credentialParams = Regex("""([?&](?:u|t|s|p|apiKey)=)[^&#\s\],)]*""")

        /**
         * [text] with the value of every credential query parameter (`u`, `t`, `s`, `p`, `apiKey`) blanked:
         * the token and salt together sign in as the user. Error messages and logs go through this.
         */
        fun redactCredentials(text: String): String = credentialParams.replace(text) { it.groupValues[1] + "REDACTED" }
    }
}
