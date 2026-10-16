package com.gpo.yoin.data.migration.spotoolfy

/**
 * What Yoin reads from a Spotoolfy v1 export (docs/spotoolfy-migration.md §13): the `tracks` and `records`
 * tables. Translations and play contexts are never imported (D8), so the parser only checks they are there.
 */
data class SpotoolfyExport(
    val tracks: List<SpotoolfyTrack>,
    val records: List<SpotoolfyRecord>,
    /** Rows with no track id or no time. Counted, never logged: they may hold the user's words. */
    val unreadableRecords: Int,
)

/** One `tracks` row: what Spotoolfy kept about a song it has a record on. */
data class SpotoolfyTrack(
    val trackId: String,
    val name: String?,
    /** Spotoolfy joins several artists with ", ". */
    val artist: String?,
    val album: String?,
    val coverUrl: String?,
)

/** One `records` row: a note, a rating, or both, written on one song at one moment. */
data class SpotoolfyRecord(
    val trackId: String,
    val note: String?,
    /** 0 👎, 3 😐 or 5 🔥 as written; any other value is dropped by the mapper. */
    val rating: Int?,
    /** Playhead when the record was written. */
    val positionMs: Long?,
    val recordedAt: Long,
    /** Up to three lyric lines, sometimes still carrying LRC tags (§4.5). */
    val lyricsSnapshot: String?,
)

sealed interface SpotoolfyParseResult {
    data class Parsed(val export: SpotoolfyExport) : SpotoolfyParseResult

    /** Readable JSON, but not Spotoolfy's four-table export. */
    data object NotSpotoolfy : SpotoolfyParseResult

    /** Not JSON, or cut short. */
    data object Corrupt : SpotoolfyParseResult

    /** Over [SpotoolfyExportParser.MAX_BYTES]. */
    data object TooLarge : SpotoolfyParseResult
}
