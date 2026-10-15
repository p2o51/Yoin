package com.gpo.yoin.data.migration.spotoolfy

import java.security.MessageDigest
import java.util.UUID

/** A song note as it will be written: Spotify track id, the user's words, and the time they were written. */
data class ImportedNote(
    /** [SpotoolfyImportMapper.noteId]: the same note from the same file always gets the same id. */
    val id: String,
    val trackId: String,
    /** The note body Yoin stores: the user's text, then the lyric snippet (§4.5). */
    val content: String,
    /** Only the user's text, for the recap's keywords. */
    val text: String,
    /** Lyric lines with LRC tags removed; empty when the record had none. */
    val lyricLines: List<String>,
    val recordedAt: Long,
    val positionMs: Long?,
    val title: String?,
    val artist: String?,
)

/** A track's latest Spotoolfy rating on Yoin's 0–10 scale (D5). */
data class ImportedRating(
    val trackId: String,
    val rating: Float,
    val recordedAt: Long,
)

/** What one Spotoolfy file puts into an account. */
data class SpotoolfyImportPlan(
    val notes: List<ImportedNote>,
    val ratings: List<ImportedRating>,
    /** Records left out: unreadable rows, Spotoolfy's sample rows, ids that are not Spotify tracks, empty records. */
    val skipped: Int,
    /** Earliest and latest record that put a note or a rating in the plan; 0 when the plan is empty. */
    val firstRecordAt: Long,
    val lastRecordAt: Long,
    /** Spotoolfy's `tracks` rows for the plan's tracks, by Spotify track id. */
    val tracks: Map<String, SpotoolfyTrack>,
) {
    val isEmpty: Boolean get() = notes.isEmpty() && ratings.isEmpty()
}

/**
 * Maps a parsed export to what Yoin stores (docs/spotoolfy-migration.md §4.3–§4.5). Pure: no clock, no profile, no
 * database. Only what the user wrote moves: notes and ratings. Contexts are dropped, as are sample rows.
 */
object SpotoolfyImportMapper {
    private val trackIdPattern = Regex("^[0-9A-Za-z]{22}$")
    private val leadingTags = Regex("^(\\[[^\\]]*])+")

    fun map(export: SpotoolfyExport): SpotoolfyImportPlan {
        val tracks = HashMap<String, SpotoolfyTrack>()
        export.tracks.forEach { track -> normalizeTrackId(track.trackId)?.let { id -> tracks[id] = track } }

        var skipped = export.unreadableRecords
        val notes = LinkedHashMap<String, ImportedNote>()
        val latestRating = HashMap<String, ImportedRating>()
        var first = Long.MAX_VALUE
        var last = Long.MIN_VALUE
        for (record in export.records) {
            val trackId = normalizeTrackId(record.trackId)
            val text = record.note?.trim().orEmpty()
            val rating = record.rating?.let(::mapRating)
            if (trackId == null || (text.isEmpty() && rating == null)) {
                skipped++
                continue
            }
            first = minOf(first, record.recordedAt)
            last = maxOf(last, record.recordedAt)
            if (rating != null) {
                val previous = latestRating[trackId]
                // The newest record holds the track's current rating; on a tie the later row in the file wins.
                if (previous == null || record.recordedAt >= previous.recordedAt) {
                    latestRating[trackId] = ImportedRating(trackId, rating, record.recordedAt)
                }
            }
            if (text.isNotEmpty()) {
                val track = tracks[trackId]
                val lines = lyricLines(record.lyricsSnapshot)
                val id = noteId(trackId, record.recordedAt, record.note.orEmpty())
                notes.putIfAbsent(
                    id,
                    ImportedNote(
                        id = id,
                        trackId = trackId,
                        content = composeContent(text, lines),
                        text = text,
                        lyricLines = lines,
                        recordedAt = record.recordedAt,
                        positionMs = record.positionMs?.takeIf { it > 0 },
                        title = track?.name?.takeIf { it.isNotBlank() },
                        artist = track?.artist?.takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
        val used = (notes.values.map { it.trackId } + latestRating.keys).toSet()
        return SpotoolfyImportPlan(
            notes = notes.values.sortedBy { it.recordedAt },
            ratings = latestRating.values.sortedBy { it.recordedAt },
            skipped = skipped,
            firstRecordAt = if (first == Long.MAX_VALUE) 0L else first,
            lastRecordAt = if (last == Long.MIN_VALUE) 0L else last,
            tracks = tracks.filterKeys { it in used },
        )
    }

    /** A bare Spotify track id, or null: the `spotify:track:` prefix is dropped, and samples like `sample1` fail. */
    fun normalizeTrackId(raw: String): String? =
        raw.trim().removePrefix("spotify:track:").takeIf { trackIdPattern.matches(it) }

    /** 🔥 5 → 10, 😐 3 → 5, 👎 0 → 1 (D5): 👎 stays above 0, which Yoin reads as "not rated". */
    fun mapRating(spotoolfy: Int): Float? = when (spotoolfy) {
        5 -> 10f
        3 -> 5f
        0 -> 1f
        else -> null
    }

    /**
     * A name-based UUID over the track, the time and the note's text. Not Spotoolfy's row id: that is a per-device
     * counter. The same file imported on a second device yields the same ids, so cloud sync never doubles a note.
     */
    fun noteId(trackId: String, recordedAt: Long, note: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(note.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { byte -> "%02x".format(byte) }
        return UUID.nameUUIDFromBytes("spotoolfy:v1|$trackId|$recordedAt|$hex".toByteArray(Charsets.UTF_8)).toString()
    }

    /** The snapshot's lines without LRC time or metadata tags, blank lines dropped. */
    fun lyricLines(snapshot: String?): List<String> =
        snapshot.orEmpty().lines()
            .map { line -> line.trim().replace(leadingTags, "").trim() }
            .filter { it.isNotEmpty() }

    /** The user's text, then a blank line and the lyric snippet in corner quotes. */
    fun composeContent(text: String, lyricLines: List<String>): String =
        if (lyricLines.isEmpty()) text else "$text\n\n「${lyricLines.joinToString("\n")}」"
}
