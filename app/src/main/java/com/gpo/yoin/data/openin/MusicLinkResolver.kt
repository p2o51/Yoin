package com.gpo.yoin.data.openin

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.remote.applemusic.AppleMusicApiClient
import com.gpo.yoin.data.source.MusicSource
import com.gpo.yoin.shared.openin.YoinMusicLink
import com.gpo.yoin.shared.openin.YoinMusicLinkKind
import com.gpo.yoin.shared.openin.YoinMusicLinkParser
import com.gpo.yoin.shared.openin.YoinMusicLinkService
import com.gpo.yoin.shared.openin.YoinSpotifyPageParser
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** Follows short links and reads what a link names; see [MusicLinkResolver]. */
interface MusicLinkLookup {
    /** [link] with a short link followed to the item it names; null when it leads nowhere Yoin reads. */
    suspend fun resolve(link: YoinMusicLink): YoinMusicLink?

    /** What [link] names, or null when it can't be read now (offline, a timeout, a page without its tags). */
    suspend fun metadata(link: YoinMusicLink): MusicLinkMetadata?
}

/**
 * Reads music links without the user's login (docs/open-in-yoin.md):
 *
 * - Apple Music: the public catalog API with Yoin's developer token ([appleMusicCatalog]); no Music User
 *   Token is ever sent, so no account is involved.
 * - Spotify: the account's own Web API when a Spotify account is in use ([activeSpotify]); otherwise the
 *   public open.spotify.com page, whose `<meta>` tags [YoinSpotifyPageParser] reads. The request carries no
 *   cookie, token or identifier: a generic browser user agent and `Accept-Language: en`, so the page reads
 *   in the shape the parser knows.
 * - spotify.link short links: followed (redirects, then the landing page's first open.spotify.com link).
 *
 * Every read is short ([TIMEOUT_MS]) and failures come back as null: the card then shows its plain line.
 */
class MusicLinkResolver(
    transport: OkHttpClient,
    private val appleMusicCatalog: AppleMusicApiClient,
    private val activeSpotify: () -> MusicSource?,
) : MusicLinkLookup {
    // Its own client: no other provider's interceptors, auth or cookies ride along.
    private val http = transport.newBuilder()
        .cookieJar(okhttp3.CookieJar.NO_COOKIES)
        .followRedirects(true)
        .followSslRedirects(true)
        .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()
    private val redirectClient = http.newBuilder().followRedirects(false).followSslRedirects(false).build()

    override suspend fun resolve(link: YoinMusicLink): YoinMusicLink? {
        if (!link.needsRedirectResolution) return link
        return guarded { followShortLink(link) }
    }

    override suspend fun metadata(link: YoinMusicLink): MusicLinkMetadata? {
        if (link.needsRedirectResolution) return null
        return guarded {
            when (link.service) {
                YoinMusicLinkService.AppleMusic -> appleMusic(link)
                YoinMusicLinkService.Spotify -> spotifyApi(link) ?: spotifyPage(link)
            }
        }?.takeUnless { it.isEmpty }
    }

    /**
     * Each redirect followed by hand, so the first hop that names a supported link ends it (open.spotify.com
     * itself is never fetched for that). A landing page instead of a redirect (Branch serves one to some user
     * agents) is read for the first open.spotify.com link it holds.
     */
    private suspend fun followShortLink(link: YoinMusicLink): YoinMusicLink? = withContext(Dispatchers.IO) {
        var url = link.url.toHttpUrlOrNull() ?: return@withContext null
        repeat(MAX_REDIRECTS) {
            val request = Request.Builder().url(url).browserHeaders().get().build()
            redirectClient.newCall(request).execute().use { response ->
                if (response.isRedirect) {
                    val next = response.header("Location")?.let(url::resolve) ?: return@withContext null
                    YoinMusicLinkParser.parse(next.toString())
                        ?.takeUnless { it.needsRedirectResolution }
                        ?.let { return@withContext it }
                    url = next
                    return@repeat
                }
                if (!response.isSuccessful) return@withContext null
                val body = response.body.source().let { source ->
                    source.request(SHORT_LINK_BODY_BYTES)
                    source.buffer.clone().readUtf8()
                }
                return@withContext OPEN_SPOTIFY_URL.findAll(body)
                    .mapNotNull { YoinMusicLinkParser.parse(it.value) }
                    .firstOrNull { !it.needsRedirectResolution }
            }
        }
        null
    }

    private suspend fun appleMusic(link: YoinMusicLink): MusicLinkMetadata? {
        val type = when (link.kind) {
            YoinMusicLinkKind.Track -> "songs"
            YoinMusicLinkKind.Album -> "albums"
            YoinMusicLinkKind.Artist -> "artists"
            YoinMusicLinkKind.Playlist -> "playlists"
            YoinMusicLinkKind.ShortLink -> return null
        }
        val storefronts = listOfNotNull(link.storefront, YoinMusicLink.DEFAULT_STOREFRONT).distinct()
        for (storefront in storefronts) {
            val page = try {
                appleMusicCatalog.resourcePage(listOf("v1", "catalog", storefront, type, link.id), personal = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                // Another storefront may carry it (a link from a storefront this one lacks).
                continue
            }
            val resource = (page["data"] as? JsonArray)?.firstOrNull() as? JsonObject ?: continue
            return MusicLinkMetadata.fromAppleMusicResource(link.kind, resource)
        }
        return null
    }

    /** The Spotify account in use reads it through its API; a playlist (whole track list) stays with the page. */
    private suspend fun spotifyApi(link: YoinMusicLink): MusicLinkMetadata? {
        val source = activeSpotify()?.takeIf { it.id == MediaId.PROVIDER_SPOTIFY } ?: return null
        val id = MediaId(MediaId.PROVIDER_SPOTIFY, link.id)
        val library = source.library()
        return guarded {
            when (link.kind) {
                YoinMusicLinkKind.Track -> library.getTrack(id)?.let { track ->
                    MusicLinkMetadata(
                        kind = link.kind,
                        title = track.title,
                        artist = track.artist,
                        album = track.album,
                        isrc = track.extras[com.gpo.yoin.data.model.Track.EXTRA_ISRC],
                        durationSec = track.durationSec,
                        artworkUrl = track.coverArt?.let { source.resolveCoverUrl(it, ARTWORK_PX) },
                    )
                }
                YoinMusicLinkKind.Album -> library.getAlbum(id)?.let { album ->
                    MusicLinkMetadata(
                        kind = link.kind,
                        title = album.name,
                        artist = album.artist,
                        artworkUrl = album.coverArt?.let { source.resolveCoverUrl(it, ARTWORK_PX) },
                    )
                }
                YoinMusicLinkKind.Artist -> library.getArtist(id)?.let { artist ->
                    MusicLinkMetadata(
                        kind = link.kind,
                        title = artist.name,
                        artist = null,
                        artworkUrl = artist.coverArt?.let { source.resolveCoverUrl(it, ARTWORK_PX) },
                    )
                }
                YoinMusicLinkKind.Playlist, YoinMusicLinkKind.ShortLink -> null
            }
        }
    }

    private suspend fun spotifyPage(link: YoinMusicLink): MusicLinkMetadata? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(link.canonicalUrl).browserHeaders().get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val html = response.body.source().let { source ->
                source.request(SPOTIFY_PAGE_BYTES)
                source.buffer.clone().readUtf8()
            }
            MusicLinkMetadata.fromSpotifyPage(link.kind, YoinSpotifyPageParser.parse(html))
        }
    }

    private fun Request.Builder.browserHeaders(): Request.Builder = this
        .header("User-Agent", BROWSER_USER_AGENT)
        .header("Accept", "text/html,application/xhtml+xml")
        .header("Accept-Language", "en")

    private suspend fun <T> guarded(block: suspend () -> T?): T? = try {
        withTimeoutOrNull(TIMEOUT_MS) { block() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    companion object {
        const val TIMEOUT_MS = 6_000L
        private const val ARTWORK_PX = 600

        // The head of a page is what holds its tags; a page is ~300 KB, the tags sit in its first part.
        private const val SPOTIFY_PAGE_BYTES = 512L * 1024L
        private const val SHORT_LINK_BODY_BYTES = 64L * 1024L
        private const val MAX_REDIRECTS = 5

        // Generic, the same for everyone: nothing that tells one user from another.
        private const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

        private val OPEN_SPOTIFY_URL = Regex("https://open\\.spotify\\.com/[A-Za-z0-9/_-]+")
    }
}
