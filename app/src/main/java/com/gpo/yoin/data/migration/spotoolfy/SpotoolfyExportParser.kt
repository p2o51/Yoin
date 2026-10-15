package com.gpo.yoin.data.migration.spotoolfy

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Streams a Spotoolfy v1 export (docs/spotoolfy-migration.md §4.9): one pass, no tree in memory, so a 50 000
 * record file fits. As forgiving as Spotoolfy's own importer: a rating written as text counts as 😐, times may be
 * numbers or text, unknown keys and malformed rows are skipped rather than failing the file.
 */
object SpotoolfyExportParser {
    /** The largest file Yoin accepts, checked again here for files that did not come through the share sheet. */
    const val MAX_BYTES: Long = 32L * 1024 * 1024

    private val requiredKeys = setOf(KEY_TRACKS, KEY_RECORDS, KEY_TRANSLATIONS, KEY_PLAY_CONTEXTS)

    fun parse(input: InputStream): SpotoolfyParseResult {
        val limited = SizeLimitedInputStream(input, MAX_BYTES)
        return try {
            JsonReader(InputStreamReader(skipBom(limited), Charsets.UTF_8)).use { reader -> readExport(reader) }
        } catch (_: FileTooLargeException) {
            SpotoolfyParseResult.TooLarge
        } catch (_: IOException) {
            SpotoolfyParseResult.Corrupt
        } catch (_: IllegalStateException) {
            SpotoolfyParseResult.Corrupt
        } catch (_: NumberFormatException) {
            SpotoolfyParseResult.Corrupt
        }
    }

    private fun readExport(reader: JsonReader): SpotoolfyParseResult {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return SpotoolfyParseResult.NotSpotoolfy
        }
        val seen = HashSet<String>()
        var tracks = emptyList<SpotoolfyTrack>()
        var records = emptyList<SpotoolfyRecord>()
        var unreadable = 0
        reader.beginObject()
        while (reader.hasNext()) {
            val key = reader.nextName()
            if (key !in requiredKeys) {
                reader.skipValue()
                continue
            }
            if (reader.peek() != JsonToken.BEGIN_ARRAY) return SpotoolfyParseResult.NotSpotoolfy
            seen += key
            when (key) {
                KEY_TRACKS -> tracks = readArray(reader) { readTrack(it) }.first
                KEY_RECORDS -> readArray(reader) { readRecord(it) }.let { (rows, dropped) ->
                    records = rows
                    unreadable = dropped
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        if (reader.peek() != JsonToken.END_DOCUMENT) return SpotoolfyParseResult.Corrupt
        if (seen != requiredKeys) return SpotoolfyParseResult.NotSpotoolfy
        return SpotoolfyParseResult.Parsed(SpotoolfyExport(tracks, records, unreadable))
    }

    /** The rows [read] could use, and how many it could not. A row that is not an object counts as unusable. */
    private inline fun <T> readArray(reader: JsonReader, read: (JsonReader) -> T?): Pair<List<T>, Int> {
        val rows = ArrayList<T>()
        var dropped = 0
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue()
                dropped++
                continue
            }
            val row = read(reader)
            if (row == null) dropped++ else rows += row
        }
        reader.endArray()
        return rows to dropped
    }

    private fun readTrack(reader: JsonReader): SpotoolfyTrack? {
        var trackId: String? = null
        var name: String? = null
        var artist: String? = null
        var album: String? = null
        var cover: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "trackId" -> trackId = reader.stringOrNull()
                "trackName" -> name = reader.stringOrNull()
                "artistName" -> artist = reader.stringOrNull()
                "albumName" -> album = reader.stringOrNull()
                "albumCoverUrl" -> cover = reader.stringOrNull()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return SpotoolfyTrack(
            trackId = trackId?.takeIf { it.isNotBlank() } ?: return null,
            name = name,
            artist = artist,
            album = album,
            coverUrl = cover?.takeIf { it.startsWith("https://") },
        )
    }

    private fun readRecord(reader: JsonReader): SpotoolfyRecord? {
        var trackId: String? = null
        var note: String? = null
        var rating: Int? = null
        var position: Long? = null
        var recordedAt: Long? = null
        var lyrics: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "trackId" -> trackId = reader.stringOrNull()
                "noteContent" -> note = reader.stringOrNull()
                "rating" -> rating = reader.ratingOrNull()
                "songTimestampMs" -> position = reader.longOrNull()
                "recordedAt" -> recordedAt = reader.longOrNull()
                "lyricsSnapshot" -> lyrics = reader.stringOrNull()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return SpotoolfyRecord(
            trackId = trackId?.takeIf { it.isNotBlank() } ?: return null,
            note = note,
            rating = rating,
            positionMs = position,
            recordedAt = recordedAt ?: return null,
            lyricsSnapshot = lyrics,
        )
    }

    private fun JsonReader.stringOrNull(): String? = when (peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> nextString()
        JsonToken.NULL -> null.also { nextNull() }
        else -> null.also { skipValue() }
    }

    /** Whole numbers written as numbers or text; a fraction is cut, anything else is null. */
    private fun JsonReader.longOrNull(): Long? = when (peek()) {
        JsonToken.NUMBER, JsonToken.STRING -> nextString().trim().let { text ->
            text.toLongOrNull() ?: text.toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong()
        }
        JsonToken.NULL -> null.also { nextNull() }
        else -> null.also { skipValue() }
    }

    /** Spotoolfy's own importer rule: a rating written as text means 😐 (3); a number must be whole. */
    private fun JsonReader.ratingOrNull(): Int? = when (peek()) {
        JsonToken.STRING -> RATING_NEUTRAL.also { skipValue() }
        JsonToken.NUMBER -> nextString().let { text ->
            text.toIntOrNull() ?: text.toDoubleOrNull()?.takeIf { it % 1.0 == 0.0 }?.toInt()
        }
        JsonToken.NULL -> null.also { nextNull() }
        else -> null.also { skipValue() }
    }

    private fun skipBom(input: InputStream): InputStream {
        val buffered = BufferedInputStream(input)
        buffered.mark(UTF8_BOM.size)
        val head = ByteArray(UTF8_BOM.size)
        var read = 0
        while (read < head.size) {
            val n = buffered.read(head, read, head.size - read)
            if (n < 0) break
            read += n
        }
        if (read < head.size || !head.contentEquals(UTF8_BOM)) buffered.reset()
        return buffered
    }

    private class FileTooLargeException : IOException()

    private class SizeLimitedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int = super.read().also { if (it >= 0) count(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) count(it) }

        override fun skip(n: Long): Long = super.skip(n).also { count(it) }

        private fun count(n: Number) {
            count += n.toLong()
            if (count > limit) throw FileTooLargeException()
        }
    }

    private const val KEY_TRACKS = "tracks"
    private const val KEY_RECORDS = "records"
    private const val KEY_TRANSLATIONS = "translations"
    private const val KEY_PLAY_CONTEXTS = "play_contexts"
    private const val RATING_NEUTRAL = 3
    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
}
