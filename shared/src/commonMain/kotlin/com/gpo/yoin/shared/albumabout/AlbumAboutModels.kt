package com.gpo.yoin.shared.albumabout

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * An album's About (owner + Carbon 2026-11-25; docs/design.md, album page): one compact credits line under the
 * album's title on the scrapbook (label · producer · mastering · recorded, plus source and first release when
 * they apply) and an editorial background paragraph on a tertiary card. The label comes from the service's own
 * field first (zero AI); everything else comes from one AI call, cached per album identity in
 * `album_about_entries` (Android Room v37; synced as `album_about`).
 *
 * The models here are plain on purpose (lists, strings, nullable strings and ints): Swift reads them through
 * YoinShared as they are, and the HarmonyOS Dart port mirrors them field for field.
 */

/**
 * The album-level credits the AI could vouch for (high or medium confidence), already cleaned: no
 * placeholders, no blanks. Every field is optional; an empty value is "not known" and never shown.
 *
 * Stored ([encode]) as the `answerText` of the `canonical` / `credits` row, and synced in that form: every
 * platform must read and write the same JSON (property names as below, empty lists and nulls left out).
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

    /** The stored form: compact JSON without defaults (`{}` for [None]). */
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

/**
 * What the service says about the release, sent with the prompt as ground truth (owner: "include the
 * source-provided label/date/version/track list as grounding"). Every field the service doesn't give is
 * null or empty and is then left out of the prompt.
 */
data class AlbumAboutGrounding(
    val album: String,
    val artist: String,
    val version: String? = null,
    /** This release's date, ISO at the precision the service gave ("2023-10-27", "2023-10", "2023"). */
    val releaseDate: String? = null,
    /** The first release's date when the service gives it apart from [releaseDate], ISO like it. */
    val originalReleaseDate: String? = null,
    val labels: List<String> = emptyList(),
    /** "Album", "EP", "Single", "Compilation"; null when the service doesn't say. */
    val releaseType: String? = null,
    val genre: String? = null,
    /** Track titles in album order. */
    val tracks: List<String> = emptyList(),
    val totalDurationSec: Int? = null,
    /**
     * The service's own notes on the album (Apple Music's editorial notes, as plain text): the paragraph is
     * already written, so the AI is asked for the credits only, with these notes as one more source.
     */
    val serviceNotes: String? = null,
)

/**
 * One About reply as read. [answered] tells "the model answered in the format and knew nothing" (a
 * [CREDITS] or [ABOUT] block, even an empty or N/A one: stored, so the page doesn't ask again) from a reply
 * that isn't an answer at all (empty, refused, off-format: a failure, nothing stored, asked again next visit).
 */
data class AlbumAboutReply(
    val credits: AlbumAiCredits,
    /** The editorial paragraph; null when the AI wrote N/A, nothing, or when the service's notes are the paragraph. */
    val blurb: String?,
    val answered: Boolean,
) {
    /** Answered, but with nothing in it: a regenerate that comes back like this keeps what was there. */
    val isEmpty: Boolean get() = blurb == null && credits.isEmpty
}

/** One stored About row of an album (Android's `AlbumAboutEntry`), as much as reading it needs. */
data class AlbumAboutRow(
    /** [YoinAlbumAbout.KIND_CANONICAL] or [YoinAlbumAbout.KIND_ASK]. */
    val kind: String,
    /** Canonical: [YoinAlbumAbout.CANON_CREDITS] / [YoinAlbumAbout.CANON_BLURB]; ask: the question's key. */
    val entryKey: String,
    val promptText: String?,
    val titleText: String?,
    val answerText: String,
    val createdAt: Long,
)

/** One question about the album and the AI's answer. */
data class AlbumAboutAsk(
    val question: String,
    val title: String?,
    val answer: String,
)

/** What the album page reads of an album's About: the AI's credits, its paragraph and the questions asked. */
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
    }
}
