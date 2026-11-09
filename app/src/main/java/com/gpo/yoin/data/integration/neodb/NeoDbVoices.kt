package com.gpo.yoin.data.integration.neodb

import java.net.URLEncoder
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * What other listeners on NeoDB make of an album: the page "别人怎么说" (owner, 2026-11-09). It is a reference,
 * not a feature of the album page: only the score spread, what people wrote, and ways out (NeoDB, Douban, RYM,
 * AOTY). No comparison with the user's own score.
 */
data class NeoDbAlbumVoices(
    val itemUuid: String,
    /** The item's page on the instance. */
    val itemUrl: String,
    val title: String,
    val rating: Double?,
    val ratingCount: Int,
    /** Five buckets (1 to 5 moons), percent. Empty when NeoDB gave none. */
    val distribution: List<Int>,
    val doubanUrl: String?,
    val discogsUrl: String?,
    val musicBrainzUrl: String?,
)

/** One listener's words on the album: a short comment, or a review's title and opening. */
data class NeoDbVoice(
    val id: String,
    val author: String,
    /** user@instance — the instance says where the listener lives (NeoDB is federated). */
    val handle: String,
    val avatarUrl: String?,
    /** 1–10 (NeoDB's grade), or null when they left words only. */
    val rating: Int?,
    val text: String,
    /** Non-null for a long review: its title; [text] is then its opening. */
    val reviewTitle: String?,
    /** The writer folded it (sensitive or a content warning): shown only on a tap. */
    val folded: Boolean,
    val foldNote: String?,
    val url: String?,
    val createdAtMillis: Long?,
)

data class NeoDbVoicePage(
    val voices: List<NeoDbVoice>,
    val page: Int,
    val pages: Int,
    val count: Int,
)

/** A score from another catalogue, shown beside NeoDB's spread for reference. */
data class NeoDbReferenceScore(
    val source: String,
    val value: Double,
    val scale: Int,
    val votes: Int?,
    val url: String,
)

internal object NeoDbVoicesParsing {
    private const val SEARCH_WINDOW = 8
    private const val REVIEW_OPENING_CHARS = 180

    /**
     * Which NeoDB item stands for the album. The same release often has several items (OK Computer: 510 ratings
     * on one, 4 on the Apple Music edition), so among the first search results whose title is the album's, the
     * one most people rated wins. No title match: the first result, as before.
     */
    fun pickAlbumItem(albumName: String, items: List<ShelfItem.Item>): ShelfItem.Item? {
        val candidates = items.take(SEARCH_WINDOW).filter { !it.uuid.isNullOrBlank() }
        if (candidates.isEmpty()) return null
        val key = normalizeTitle(albumName)
        val matching = if (key.isEmpty()) {
            emptyList()
        } else {
            candidates.filter { normalizeTitle(it.title ?: it.displayTitle.orEmpty()) == key }
        }
        if (matching.isEmpty()) return candidates.first()
        // maxByOrNull keeps the first of equal counts: search order breaks ties.
        return matching.maxByOrNull { it.ratingCount ?: 0 }
    }

    /** Case, punctuation, spaces and a trailing "(Remastered)" / "[Deluxe]" don't make a different album. */
    fun normalizeTitle(title: String): String {
        var t = title.trim()
        while (true) {
            val stripped = t.replace(Regex("""\s*[(\[（【][^()\[\]（）【】]*[)\]）】]\s*$"""), "")
            if (stripped == t || stripped.isBlank()) break
            t = stripped
        }
        return t.lowercase().filter { it.isLetterOrDigit() }
    }

    fun albumVoices(instance: String, item: ShelfItem.Item): NeoDbAlbumVoices? {
        val uuid = item.uuid?.takeUnless(String::isBlank) ?: return null
        val links = item.externalResources.orEmpty().map { it.url }
        return NeoDbAlbumVoices(
            itemUuid = uuid,
            itemUrl = itemUrl(instance, item.url, uuid),
            title = item.title ?: item.displayTitle.orEmpty(),
            rating = item.rating,
            ratingCount = item.ratingCount ?: 0,
            distribution = item.ratingDistribution?.takeIf { it.size == 5 }.orEmpty(),
            doubanUrl = links.firstOrNull { "douban.com/" in it },
            discogsUrl = links.firstOrNull { "discogs.com/master/" in it }
                ?: links.firstOrNull { "discogs.com/release/" in it },
            musicBrainzUrl = links.firstOrNull { "musicbrainz.org/release-group/" in it }
                ?: links.firstOrNull { "musicbrainz.org/release/" in it },
        )
    }

    private fun itemUrl(instance: String, path: String?, uuid: String): String {
        val base = instance.trimEnd('/')
        return when {
            path.isNullOrBlank() -> "$base/album/$uuid"
            path.startsWith("http") -> path
            else -> base + (if (path.startsWith("/")) path else "/$path")
        }
    }

    fun page(page: Int, body: NeoDbPostPage): NeoDbVoicePage = NeoDbVoicePage(
        voices = body.data.mapNotNull(::voice),
        page = page,
        pages = body.pages,
        count = body.count,
    )

    /** A post becomes a voice only when it carries words: a Comment's text or a Review. */
    fun voice(post: NeoDbPost): NeoDbVoice? {
        val related = post.extNeodb?.relatedWith.orEmpty()
        val comment = related.firstOrNull { it.string("type") == "Comment" }
        val review = related.firstOrNull { it.string("type") == "Review" }
        val rating = related.firstOrNull { it.string("type") == "Rating" }
            ?.let { it["value"]?.jsonPrimitive?.doubleOrNull }
            ?.toInt()
            ?.takeIf { it in 1..10 }
        val reviewTitle = review?.string("name")?.trim()?.takeIf(String::isNotEmpty)
        val text = when {
            review != null -> review.string("content").orEmpty().let(::reviewOpening)
            else -> comment?.string("content").orEmpty().trim()
        }
        if (text.isEmpty() && reviewTitle == null) return null
        val account = post.account
        val handle = account?.acct.orEmpty()
        val spoiler = post.spoilerText?.trim()?.takeIf(String::isNotEmpty)
        return NeoDbVoice(
            id = post.id.ifEmpty { review?.string("id") ?: comment?.string("id").orEmpty() },
            author = account?.displayName?.trim()?.takeIf(String::isNotEmpty)
                ?: account?.username
                ?: handle.substringBefore('@'),
            handle = handle,
            avatarUrl = account?.avatar,
            rating = rating,
            text = text,
            reviewTitle = reviewTitle,
            folded = post.sensitive || spoiler != null,
            foldNote = spoiler,
            url = review?.string("href") ?: post.url,
            createdAtMillis = post.createdAt?.let { at ->
                runCatching { Instant.parse(at).toEpochMilli() }.getOrNull()
            },
        )
    }

    /** A review is Markdown: its first lines as plain text, cut at a sentence when it can be. */
    fun reviewOpening(markdown: String): String {
        val plain = markdown
            .replace(Regex("""!\[[^\]]*]\([^)]*\)"""), "")
            .replace(Regex("""\[([^\]]*)]\([^)]*\)"""), "$1")
            .replace(Regex("""[*_#>`~]+"""), "")
            .lines()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .joinToString(" ")
        if (plain.length <= REVIEW_OPENING_CHARS) return plain
        val cut = plain.take(REVIEW_OPENING_CHARS)
        val sentenceEnd = cut.indexOfLast { it in "。！？.!?" }
        return if (sentenceEnd >= REVIEW_OPENING_CHARS / 2) cut.take(sentenceEnd + 1) else "$cut…"
    }

    fun rymSearchUrl(albumName: String, artist: String?): String =
        "https://rateyourmusic.com/search?searchtype=l&searchterm=" + encode(query(albumName, artist))

    fun aotySearchUrl(albumName: String, artist: String?): String =
        "https://www.albumoftheyear.org/search/albums/?q=" + encode(query(albumName, artist))

    fun neoDbSearchUrl(instance: String, albumName: String, artist: String?): String =
        instance.trimEnd('/') + "/search?c=music&q=" + encode(query(albumName, artist))

    fun query(albumName: String, artist: String?): String =
        listOfNotNull(albumName.trim(), artist?.trim()?.takeIf(String::isNotEmpty)).joinToString(" ")

    /** `https://www.discogs.com/master/21491-Radiohead-OK-Computer` → ("master", 21491). */
    fun discogsId(url: String): Pair<String, Long>? {
        val match = Regex("""discogs\.com/(?:[a-z]{2}/)?(master|release)/(\d+)""").find(url) ?: return null
        return match.groupValues[1] to match.groupValues[2].toLong()
    }

    /** `https://musicbrainz.org/release/<mbid>` → ("release", mbid). */
    fun musicBrainzId(url: String): Pair<String, String>? {
        val match = Regex("""musicbrainz\.org/(release-group|release)/([0-9a-fA-F-]{36})""").find(url) ?: return null
        return match.groupValues[1] to match.groupValues[2]
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun JsonObject.string(key: String): String? =
        this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
}
