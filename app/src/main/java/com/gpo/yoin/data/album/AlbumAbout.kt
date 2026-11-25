package com.gpo.yoin.data.album

import com.gpo.yoin.data.local.AlbumAboutEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * An album's About (owner + Carbon 2026-11-25): one compact credits line under the album's title on the
 * scrapbook (label · producer · mastering · recorded, plus source and first release when they apply) and an
 * editorial background paragraph in a secondary-role card. The label comes from the service's own field
 * first (zero AI); everything else comes from one AI call ([com.gpo.yoin.data.remote.GeminiService.runAlbumAbout]),
 * cached per album identity in `album_about_entries`.
 */

/**
 * The album-level credits the AI could vouch for (high or medium confidence), already cleaned: no
 * placeholders, no blanks. Every field is optional; an empty value is "not known" and never shown.
 */
@Serializable
data class AlbumAiCredits(
    val label: List<String> = emptyList(),
    val producers: List<String> = emptyList(),
    val mastering: List<String> = emptyList(),
    val mixing: List<String> = emptyList(),
    /** When it was recorded, in the AI language ("1975年1月", "Jan–Mar 1975"). */
    val recordedTime: String? = null,
    /** Where: studio names with their city. */
    val recordedStudios: List<String> = emptyList(),
    /** The film, game, anime, show or artist a soundtrack or tribute album belongs to. */
    val source: String? = null,
    /** A reissue's first release, in the AI language. */
    val firstReleased: String? = null,
) {
    val isEmpty: Boolean
        get() = label.isEmpty() && producers.isEmpty() && mastering.isEmpty() && mixing.isEmpty() &&
            recordedTime == null && recordedStudios.isEmpty() && source == null && firstReleased == null

    fun encode(): String = CreditsJson.encodeToString(serializer(), this)

    companion object {
        val None = AlbumAiCredits()

        /** The stored form ([encode]); [None] for anything unreadable (an older or damaged row). */
        fun decode(text: String?): AlbumAiCredits {
            if (text.isNullOrBlank()) return None
            return runCatching { CreditsJson.decodeFromString(serializer(), text) }.getOrDefault(None)
        }

        private val CreditsJson = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = false
        }
    }
}

/** What the scrapbook reads of an album's About: the AI's credits, its paragraph and the questions asked. */
data class AlbumAboutContent(
    val credits: AlbumAiCredits = AlbumAiCredits.None,
    /** The editorial paragraph; null until written (or when the AI knew too little to write one). */
    val blurb: String? = null,
    /** Questions about the album, newest first; blank (removed) answers left out. */
    val asks: List<AlbumAboutAsk> = emptyList(),
    /** Canonical rows exist: the AI has been asked (an empty result is still an answer). */
    val generated: Boolean = false,
) {
    companion object {
        val None = AlbumAboutContent()

        /** The rows of one album ([com.gpo.yoin.data.local.AlbumAboutEntryDao.observe]) as the page reads them. */
        fun from(rows: List<AlbumAboutEntry>): AlbumAboutContent {
            val canonical = rows.filter { it.kind == AlbumAboutEntry.KIND_CANONICAL }.associateBy { it.entryKey }
            return AlbumAboutContent(
                credits = AlbumAiCredits.decode(canonical[AlbumAboutEntry.CANON_CREDITS]?.answerText),
                blurb = canonical[AlbumAboutEntry.CANON_BLURB]?.answerText?.trim()?.takeIf(String::isNotEmpty),
                asks = rows
                    .filter { it.kind == AlbumAboutEntry.KIND_ASK && it.answerText.isNotBlank() }
                    .sortedByDescending { it.createdAt }
                    .map { row ->
                        AlbumAboutAsk(
                            question = row.promptText?.takeIf(String::isNotBlank) ?: row.entryKey,
                            title = row.titleText?.takeIf(String::isNotBlank),
                            answer = row.answerText.trim(),
                        )
                    },
                generated = canonical.isNotEmpty(),
            )
        }
    }
}

/** One question about the album and Gemini's answer. */
data class AlbumAboutAsk(
    val question: String,
    val title: String?,
    val answer: String,
)

/**
 * What the service says about the release, sent with the prompt as ground truth (owner: "include the
 * source-provided label/date/version/track list as grounding").
 */
data class AlbumAboutGrounding(
    val album: String,
    val artist: String,
    val version: String? = null,
    /** This release's date, ISO at the precision the service gave ("2023-10-27", "2023-10", "2023"). */
    val releaseDate: String? = null,
    /** The first release's date when the service gives it apart from [releaseDate]. */
    val originalReleaseDate: String? = null,
    val labels: List<String> = emptyList(),
    /** "Album", "EP", "Single", "Compilation"; null when the service doesn't say. */
    val releaseType: String? = null,
    val genre: String? = null,
    /** Track titles in album order. */
    val tracks: List<String> = emptyList(),
    val totalDurationSec: Int? = null,
)

/** The service's facts about this release, for the prompt. */
fun com.gpo.yoin.data.model.Album.toAboutGrounding(): AlbumAboutGrounding = AlbumAboutGrounding(
    album = name,
    artist = artist.orEmpty(),
    version = version,
    releaseDate = releaseDate?.toIso(),
    originalReleaseDate = originalReleaseDate?.toIso(),
    labels = recordLabels,
    releaseType = releaseType?.name,
    genre = genre,
    tracks = tracks.mapNotNull { it.title?.trim()?.takeIf(String::isNotEmpty) },
    totalDurationSec = durationSec ?: tracks.sumOf { it.durationSec ?: 0 }.takeIf { it > 0 },
)

/** "2023-10-27" / "2023-10" / "2023", at the date's own precision. */
internal fun com.gpo.yoin.data.model.ReleaseDate.toIso(): String = buildString {
    append("%04d".format(year))
    month?.let { append("-%02d".format(it)) }
    day?.let { append("-%02d".format(it)) }
}
