package com.gpo.yoin.shared.data.lyrics

import com.gpo.yoin.shared.sync.CanonicalJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * How Android's `lyrics_translation_cache` names a translation, so a row this
 * device writes is the row Android writes for the same lyrics (cloud sync
 * carries them as `lyrics_translation` records, keyed by these fields).
 *
 * A row is (trackProvider, trackRawId, [sourceHash], targetLanguage, model):
 * model is Gemini's model name (Android `GeminiService.MODEL`) for a paid
 * translation, or [PROVIDER_MODEL_PREFIX] + provider name for a lyrics
 * provider's own one (free, never synced).
 *
 * Swift: `LyricsTranslationIdentity.shared.sourceHash(...)`.
 */
object LyricsTranslationIdentity {
    /** Android LyricsCachePolicy.PROVIDER_TRANSLATION_MODEL_PREFIX. */
    const val PROVIDER_MODEL_PREFIX = "provider:"

    /** Android `lyricTranslationCacheJson` (a default [Json]). */
    private val json = Json

    /**
     * Android YoinRepository `buildLyricsTranslationSourceHash`, exactly: SHA-256
     * (lowercase hex) of `title␟artist␟` then `length:line␞` per line, where length
     * is the line's UTF-16 length (Kotlin `String.length`). Android passes the
     * trimmed title / artist and the trimmed, non-blank lines: see [geminiSourceHash].
     */
    fun sourceHash(title: String, artist: String, lines: List<String>): String = CanonicalJson.sha256Hex(
        buildString {
            append(title).append('\u001F')
            append(artist).append('\u001F')
            lines.forEach { line ->
                append(line.length).append(':').append(line).append('\u001E')
            }
        },
    )

    /**
     * Android `translateLyrics`' hash: title and artist trimmed (null = ""),
     * lines trimmed with blanks dropped ([sourceLines]).
     */
    fun geminiSourceHash(title: String?, artist: String?, lines: List<String>): String =
        sourceHash(title?.trim().orEmpty(), artist?.trim().orEmpty(), sourceLines(lines))

    /** The lines Android translates: each trimmed, blanks dropped. */
    fun sourceLines(lines: List<String>): List<String> = lines.map(String::trim).filter(String::isNotEmpty)

    /** `translationsJson`: one string per source line ("" = none), as Android encodes it. */
    fun encodeTranslations(translations: List<String>): String =
        json.encodeToString(ListSerializer(String.serializer()), translations)

    /** The list in a `translationsJson`, or null when it isn't a JSON array of strings. */
    fun decodeTranslations(translationsJson: String): List<String>? =
        runCatching { json.decodeFromString(ListSerializer(String.serializer()), translationsJson) }.getOrNull()

    fun isProviderModel(model: String): Boolean = model.startsWith(PROVIDER_MODEL_PREFIX)
}
