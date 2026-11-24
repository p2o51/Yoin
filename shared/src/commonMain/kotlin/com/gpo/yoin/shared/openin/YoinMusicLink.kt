package com.gpo.yoin.shared.openin

/*
 * "Open in Yoin" (docs/open-in-yoin.md): a Spotify or Apple Music link found in shared text or on the
 * clipboard, parsed here so Android, iOS and HarmonyOS read links the same way. Pure: no network. A short
 * link (spotify.link) is only recognised; the app follows its redirect and parses where it lands.
 *
 * Swift sees flat names: YoinMusicLink, YoinMusicLinkService, YoinMusicLinkKind, YoinMusicLinkParser.
 */

/** The service a link belongs to. */
enum class YoinMusicLinkService {
    Spotify,
    AppleMusic,
}

/** What a link points at. [ShortLink]: a redirect (spotify.link) whose target isn't known yet. */
enum class YoinMusicLinkKind {
    Track,
    Album,
    Playlist,
    Artist,
    ShortLink,
}

/**
 * One music link. [id] is the service's own id: a Spotify base-62 id, an Apple Music catalog id (digits) or
 * catalog playlist id (`pl.…`), or for a [YoinMusicLinkKind.ShortLink] its code. [storefront] is the Apple
 * Music storefront the URL names ("us", "jp"), null when it names none and for Spotify. [url] is the link as
 * it was found, what a short link is resolved from.
 */
data class YoinMusicLink(
    val service: YoinMusicLinkService,
    val kind: YoinMusicLinkKind,
    val id: String,
    val storefront: String?,
    val url: String,
) {
    /** A short link: follow its redirect, then parse the URL it lands on. */
    val needsRedirectResolution: Boolean get() = kind == YoinMusicLinkKind.ShortLink

    /** The same item whatever the link's decorations (`si=`, `intl-de`, the slug): what de-duplication keys on. */
    val key: String get() = "${service.name}:${kind.name}:$id"

    /** The service's canonical web URL for the item; [url] for a short link. */
    val canonicalUrl: String
        get() = when {
            kind == YoinMusicLinkKind.ShortLink -> url
            service == YoinMusicLinkService.Spotify -> "https://open.spotify.com/${spotifyPath(kind)}/$id"
            else -> "https://music.apple.com/${storefront ?: DEFAULT_STOREFRONT}/${appleMusicPath(kind)}/$id"
        }

    companion object {
        /** The storefront a catalog read uses when the link names none. */
        const val DEFAULT_STOREFRONT = "us"
    }
}

private fun spotifyPath(kind: YoinMusicLinkKind): String = when (kind) {
    YoinMusicLinkKind.Track -> "track"
    YoinMusicLinkKind.Album -> "album"
    YoinMusicLinkKind.Playlist -> "playlist"
    YoinMusicLinkKind.Artist -> "artist"
    YoinMusicLinkKind.ShortLink -> ""
}

private fun appleMusicPath(kind: YoinMusicLinkKind): String = when (kind) {
    YoinMusicLinkKind.Track -> "song"
    YoinMusicLinkKind.Album -> "album"
    YoinMusicLinkKind.Playlist -> "playlist"
    YoinMusicLinkKind.Artist -> "artist"
    YoinMusicLinkKind.ShortLink -> ""
}

/**
 * Reads Spotify and Apple Music links.
 *
 * Spotify: `open.spotify.com/{track|album|playlist|artist}/<id>` (also `play.spotify.com`, `/intl-xx/`,
 * `/embed/`, the old `/user/<name>/playlist/<id>`, any query such as `si=`), `spotify:track:<id>` URIs, and
 * `spotify.link` / `spoti.fi` / `spotify.app.link` short links ([YoinMusicLinkKind.ShortLink]).
 *
 * Apple Music: `music.apple.com/<storefront>/{song|album|playlist|artist}/<slug>/<id>` (slug optional, also
 * `geo.` and `embed.` hosts); an album URL with `?i=<trackId>` is that song; the legacy
 * `itunes.apple.com/<storefront>/album/<slug>/id<id>?i=<trackId>` likewise.
 */
object YoinMusicLinkParser {

    /** The first supported link in [text] (shared text, a clipboard), or null. Never throws. */
    fun findFirst(text: String): YoinMusicLink? {
        if (text.isBlank()) return null
        val candidates = (CANDIDATE.findAll(text) + SPOTIFY_URI_CANDIDATE.findAll(text))
            .sortedBy { it.range.first }
        for (match in candidates) {
            parse(match.value)?.let { return it }
        }
        return null
    }

    /** Whether [text] holds a supported link: [findFirst] without keeping it. */
    fun containsLink(text: String): Boolean = findFirst(text) != null

    /** [value] read as one link, or null when it is none Yoin supports. Never throws. */
    fun parse(value: String): YoinMusicLink? {
        val trimmed = value.trim().trimEnd(*TRAILING_PUNCTUATION)
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("spotify:", ignoreCase = true)) return parseSpotifyUri(trimmed)
        val url = splitUrl(trimmed) ?: return null
        return when (url.host) {
            "open.spotify.com", "play.spotify.com" -> parseSpotifyWeb(url, trimmed)
            "spotify.link", "spoti.fi", "spotify.app.link" -> parseShortLink(url)
            "music.apple.com", "geo.music.apple.com", "embed.music.apple.com" -> parseAppleMusic(url, trimmed)
            "itunes.apple.com", "geo.itunes.apple.com" -> parseAppleMusic(url, trimmed)
            else -> null
        }
    }

    private fun parseSpotifyUri(value: String): YoinMusicLink? {
        // spotify:track:<id>, and the old spotify:user:<name>:playlist:<id>.
        val parts = value.substringBefore('?').split(':')
        val kindIndex = parts.indexOfFirst { spotifyKind(it) != null }.takeIf { it > 0 } ?: return null
        val kind = spotifyKind(parts[kindIndex]) ?: return null
        val id = parts.getOrNull(kindIndex + 1)?.takeIf(SPOTIFY_ID::matches) ?: return null
        return YoinMusicLink(YoinMusicLinkService.Spotify, kind, id, storefront = null, url = value)
    }

    private fun parseSpotifyWeb(url: ParsedUrl, original: String): YoinMusicLink? {
        val segments = url.segments.toMutableList()
        if (segments.firstOrNull()?.startsWith("intl-", ignoreCase = true) == true) segments.removeAt(0)
        if (segments.firstOrNull().equals("embed", ignoreCase = true)) segments.removeAt(0)
        // The old /user/<name>/playlist/<id>.
        if (segments.firstOrNull().equals("user", ignoreCase = true) && segments.size >= 4) {
            segments.removeAt(0)
            segments.removeAt(0)
        }
        val kind = spotifyKind(segments.getOrNull(0)) ?: return null
        val id = segments.getOrNull(1)?.takeIf(SPOTIFY_ID::matches) ?: return null
        return YoinMusicLink(YoinMusicLinkService.Spotify, kind, id, storefront = null, url = original)
    }

    private fun parseShortLink(url: ParsedUrl): YoinMusicLink? {
        val code = url.segments.firstOrNull()?.takeIf(SHORT_CODE::matches) ?: return null
        return YoinMusicLink(
            YoinMusicLinkService.Spotify,
            YoinMusicLinkKind.ShortLink,
            code,
            storefront = null,
            url = "https://${url.host}/$code",
        )
    }

    private fun parseAppleMusic(url: ParsedUrl, original: String): YoinMusicLink? {
        val segments = url.segments.toMutableList()
        val storefront = segments.firstOrNull()?.lowercase()?.takeIf(STOREFRONT::matches)
        if (storefront != null) segments.removeAt(0)
        val kindSegment = segments.firstOrNull()?.lowercase() ?: return null
        val rest = segments.drop(1)
        // The id is the last segment; a slug may sit before it. Legacy iTunes ids carry an "id" prefix.
        val last = rest.lastOrNull()?.removePrefix("id") ?: return null
        val trackId = url.query["i"]?.takeIf(APPLE_ID::matches)
        val link = { kind: YoinMusicLinkKind, id: String ->
            YoinMusicLink(YoinMusicLinkService.AppleMusic, kind, id, storefront, original)
        }
        return when (kindSegment) {
            "song" -> last.takeIf(APPLE_ID::matches)?.let { link(YoinMusicLinkKind.Track, it) }
            "album" -> when {
                trackId != null -> link(YoinMusicLinkKind.Track, trackId)
                APPLE_ID.matches(last) -> link(YoinMusicLinkKind.Album, last)
                else -> null
            }
            "playlist" -> rest.lastOrNull()?.takeIf(APPLE_PLAYLIST_ID::matches)
                ?.let { link(YoinMusicLinkKind.Playlist, it) }
            "artist" -> last.takeIf(APPLE_ID::matches)?.let { link(YoinMusicLinkKind.Artist, it) }
            else -> null
        }
    }

    private fun spotifyKind(segment: String?): YoinMusicLinkKind? = when (segment?.lowercase()) {
        "track" -> YoinMusicLinkKind.Track
        "album" -> YoinMusicLinkKind.Album
        "playlist" -> YoinMusicLinkKind.Playlist
        "artist" -> YoinMusicLinkKind.Artist
        else -> null
    }

    private class ParsedUrl(val host: String, val segments: List<String>, val query: Map<String, String>)

    /** scheme://host[:port]/path?query#fragment, a missing scheme read as https. Only http(s). */
    private fun splitUrl(value: String): ParsedUrl? {
        val schemeEnd = value.indexOf("://")
        val rest = if (schemeEnd >= 0) {
            val scheme = value.substring(0, schemeEnd).lowercase()
            if (scheme != "https" && scheme != "http") return null
            value.substring(schemeEnd + 3)
        } else {
            value
        }
        val withoutFragment = rest.substringBefore('#')
        val authorityEnd = withoutFragment.indexOfAny(charArrayOf('/', '?'))
            .let { if (it < 0) withoutFragment.length else it }
        val authority = withoutFragment.substring(0, authorityEnd)
        if (authority.contains('@')) return null
        val host = authority.substringBefore(':').lowercase().removePrefix("www.").trimEnd('.')
        if (host.isEmpty()) return null
        val pathAndQuery = withoutFragment.substring(authorityEnd)
        val path = pathAndQuery.substringBefore('?')
        val query = pathAndQuery.substringAfter('?', missingDelimiterValue = "")
        val segments = path.split('/').filter(String::isNotEmpty)
        val params = query.split('&').mapNotNull { pair ->
            val name = pair.substringBefore('=').takeIf(String::isNotEmpty) ?: return@mapNotNull null
            name to pair.substringAfter('=', missingDelimiterValue = "")
        }.toMap()
        return ParsedUrl(host, segments, params)
    }

    private val SPOTIFY_ID = Regex("[A-Za-z0-9]{22}")
    private val SHORT_CODE = Regex("[A-Za-z0-9_-]{4,64}")
    private val STOREFRONT = Regex("[a-z]{2}")
    private val APPLE_ID = Regex("[0-9]{1,15}")
    private val APPLE_PLAYLIST_ID = Regex("pl\\.[A-Za-z0-9-]{4,64}")
    private val TRAILING_PUNCTUATION = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '>', '"', '\'')

    // A link to one of the supported hosts, scheme optional. It ends at whitespace, quotes, angle brackets
    // and CJK / full-width punctuation (「听这首：https://…，很好听」); a raw Unicode slug in an Apple Music
    // URL (album/夜に駆ける/…) stays in.
    private val CANDIDATE = Regex(
        "(?i)(?:https?://)?(?:[a-z0-9-]+\\.)*(?:spotify\\.com|spotify\\.link|spoti\\.fi|spotify\\.app\\.link|" +
            "apple\\.com)(?:[/?][^\\s<>\"'\\u3000-\\u303F\\uFF01-\\uFF0F\\uFF1A-\\uFF20\\uFF3B-\\uFF40\\uFF5B-\\uFF65]*)?",
    )
    private val SPOTIFY_URI_CANDIDATE =
        Regex("(?i)spotify:(?:user:[^\\s:]+:)?(?:track|album|playlist|artist):[A-Za-z0-9]{22}")
}
