package com.gpo.yoin.shared.openin

/**
 * What Spotify's public web page for a track, album, artist or playlist says about it, read from its
 * `<meta>` tags: no account, no API (docs/open-in-yoin.md). Any field the page doesn't give is null.
 * [durationSec]: a track's `music:duration`. [owner]: a playlist's curator as the description names it.
 */
data class YoinSpotifyPageMeta(
    val title: String?,
    val artist: String?,
    val album: String?,
    val owner: String?,
    val durationSec: Int?,
    val imageUrl: String?,
    val ogType: String?,
)

/**
 * Reads a [YoinSpotifyPageMeta] from an open.spotify.com page as served in English (`Accept-Language: en`).
 * The pages (2026-11) carry, for a track:
 *
 * - `og:title` "Mr. Brightside"; `og:description` "The Killers · Hot Fuss · Song · 2004"
 * - `music:musician_description` "The Killers"; `music:duration` "222"; `og:image`
 * - `<title>` "Mr. Brightside - Song, Lyrics, and Music Video by The Killers | Spotify"
 *
 * for an album `og:title` "Listen to After Hours - Album by The Weeknd | Spotify" and `og:description`
 * "The Weeknd · album · 2020 · 14 songs"; for an artist `og:title` "The Weeknd"; for a playlist
 * `og:description` "Playlist · Spotify · 50 items · 33.5M saves". Each field falls back through the others,
 * so a page that drops one tag still reads. Pure; never throws.
 */
object YoinSpotifyPageParser {

    fun parse(html: String): YoinSpotifyPageMeta {
        val meta = metaTags(html)
        fun first(name: String): String? = meta.firstOrNull { it.first == name }?.second?.takeIf(String::isNotBlank)

        val ogType = first("og:type")
        val description = first("og:description") ?: first("twitter:description")
        val parts = description?.split(" · ")?.map(String::trim).orEmpty()
        val pageTitle = TITLE_TAG.find(html)?.groupValues?.get(1)?.let(::decodeEntities)?.trim()
        val rawTitle = first("og:title") ?: first("twitter:title") ?: pageTitle
        val titled = rawTitle?.let(::splitTitle)
        val fromPageTitle = pageTitle?.let(::splitTitle)

        val isSong = ogType == "music.song" || parts.getOrNull(2).equals("Song", ignoreCase = true)
        val isAlbum = ogType == "music.album" || parts.getOrNull(1).equals("album", ignoreCase = true)
        val isPlaylist = ogType == "music.playlist" || parts.firstOrNull().equals("Playlist", ignoreCase = true)

        val artist = first("music:musician_description")
            ?: titled?.artist
            ?: fromPageTitle?.artist
            ?: parts.takeIf { isSong || isAlbum }?.let(::artistFromDescription)
        val album = if (isSong) {
            // "Artist · Album · Song · Year"
            parts.takeIf { it.size >= 4 && it[2].equals("Song", ignoreCase = true) }?.get(1)
        } else {
            null
        }
        val owner = if (isPlaylist) parts.getOrNull(1)?.takeIf(String::isNotBlank) else null
        return YoinSpotifyPageMeta(
            title = titled?.title?.takeIf(String::isNotBlank),
            artist = artist?.takeIf(String::isNotBlank),
            album = album?.takeIf(String::isNotBlank),
            owner = owner,
            durationSec = first("music:duration")?.toIntOrNull()?.takeIf { it > 0 },
            imageUrl = (first("og:image") ?: first("twitter:image"))?.takeIf { it.startsWith("https://") },
            ogType = ogType,
        )
    }

    private class Title(val title: String, val artist: String?)

    /**
     * "Listen to After Hours - Album by The Weeknd | Spotify" → After Hours / The Weeknd;
     * "Mr. Brightside - Song, Lyrics, and Music Video by The Killers | Spotify" → Mr. Brightside / The Killers;
     * "Today’s Top Hits | Spotify Playlist" → Today’s Top Hits; a plain og:title stays as it is.
     */
    private fun splitTitle(raw: String): Title {
        var text = raw.trim()
        SITE_SUFFIX.find(text)?.let { text = text.substring(0, it.range.first).trim() }
        text = text.removePrefix("Listen to ").trim()
        val by = BY_SUFFIX.find(text)
        return if (by != null) {
            Title(text.substring(0, by.range.first).trim(), by.groupValues[1].trim())
        } else {
            Title(text, null)
        }
    }

    private fun artistFromDescription(parts: List<String>): String? {
        val first = parts.firstOrNull() ?: return null
        // "Listen to X on Spotify. Song · Artist · 2020" (the plain description) names the artist second.
        if (first.endsWith("Song") || first.endsWith("album")) return parts.getOrNull(1)
        return first
    }

    /** (property-or-name, content) of every `<meta>` tag, attributes in any order, entities decoded. */
    private fun metaTags(html: String): List<Pair<String, String>> = META_TAG.findAll(html).mapNotNull { tag ->
        val attributes = ATTRIBUTE.findAll(tag.value).associate { attribute ->
            attribute.groupValues[1].lowercase() to (attribute.groupValues[3].ifEmpty { attribute.groupValues[4] })
        }
        val key = attributes["property"] ?: attributes["name"] ?: return@mapNotNull null
        val content = attributes["content"] ?: return@mapNotNull null
        key.lowercase() to decodeEntities(content).trim()
    }.toList()

    internal fun decodeEntities(text: String): String {
        if (!text.contains('&')) return text
        return ENTITY.replace(text) { match ->
            val body = match.groupValues[1]
            when {
                body.startsWith("#x", ignoreCase = true) -> body.substring(2).toIntOrNull(16)?.let(::codePointString)
                body.startsWith("#") -> body.substring(1).toIntOrNull()?.let(::codePointString)
                else -> NAMED_ENTITIES[body]
            } ?: match.value
        }
    }

    private fun codePointString(codePoint: Int): String? {
        if (codePoint !in 0..0x10FFFF) return null
        if (codePoint < 0x10000) return codePoint.toChar().toString()
        val offset = codePoint - 0x10000
        val high = (0xD800 + (offset shr 10)).toChar()
        val low = (0xDC00 + (offset and 0x3FF)).toChar()
        return charArrayOf(high, low).concatToString()
    }

    private val META_TAG = Regex("(?i)<meta\\b[^>]*>")
    private val ATTRIBUTE = Regex("(?i)([a-z:_-]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')")
    private val TITLE_TAG = Regex("(?is)<title[^>]*>(.*?)</title>")
    private val SITE_SUFFIX = Regex("\\s*\\|\\s*Spotify(?:\\s+Playlist)?\\s*$")
    private val BY_SUFFIX = Regex(
        "\\s-\\s(?:Song(?:,\\s*Lyrics)?(?:,?\\s*and\\s+Music\\s+Video)?|song and lyrics|Album|Single|EP|" +
            "Compilation|song)\\s+by\\s+(.+)$",
        RegexOption.IGNORE_CASE,
    )
    private val ENTITY = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});")
    private val NAMED_ENTITIES = mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "nbsp" to "\u00A0",
    )
}
