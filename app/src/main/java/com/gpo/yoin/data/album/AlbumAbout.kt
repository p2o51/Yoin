package com.gpo.yoin.data.album

import com.gpo.yoin.data.local.AlbumAboutEntry
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.ReleaseDate
import com.gpo.yoin.shared.albumabout.AlbumAboutContent
import com.gpo.yoin.shared.albumabout.AlbumAboutGrounding
import com.gpo.yoin.shared.albumabout.AlbumAboutRow
import com.gpo.yoin.shared.albumabout.YoinAlbumAbout

/*
 * An album's About (owner + Carbon 2026-11-25): one compact credits line under the album's title on the
 * scrapbook (label · producer · mastering · recorded, plus source and first release when they apply) and an
 * editorial background paragraph in a tertiary-role card. The label comes from the service's own field
 * first (zero AI); everything else comes from one AI call ([com.gpo.yoin.data.remote.GeminiService.runAlbumAbout]),
 * cached per album identity in `album_about_entries`.
 *
 * The rules live in :shared ([YoinAlbumAbout]: prompts, reply parser, stored credits, the credits line), so
 * iOS and HarmonyOS answer the same; this file only maps Android's models onto them.
 */

/** The rows of one album ([com.gpo.yoin.data.local.AlbumAboutEntryDao.observe]) as the page reads them. */
fun albumAboutContent(rows: List<AlbumAboutEntry>): AlbumAboutContent = YoinAlbumAbout.content(
    rows.map { row ->
        AlbumAboutRow(
            kind = row.kind,
            entryKey = row.entryKey,
            promptText = row.promptText,
            titleText = row.titleText,
            answerText = row.answerText,
            createdAt = row.createdAt,
        )
    },
)

/** The service's facts about this release, for the prompt. */
fun Album.toAboutGrounding(): AlbumAboutGrounding = AlbumAboutGrounding(
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
    serviceNotes = editorialNotes,
)

/** "2023-10-27" / "2023-10" / "2023", at the date's own precision (the ISO form :shared reads). */
internal fun ReleaseDate.toIso(): String = buildString {
    append("%04d".format(year))
    month?.let { append("-%02d".format(it)) }
    day?.let { append("-%02d".format(it)) }
}
