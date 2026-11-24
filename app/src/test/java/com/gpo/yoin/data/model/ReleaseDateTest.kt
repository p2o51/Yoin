package com.gpo.yoin.data.model

import com.gpo.yoin.data.cache.AlbumDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseDateTest {

    @Test
    fun should_readEachPrecision_when_parsingIsoText() {
        assertEquals(ReleaseDate(2023, 10, 27), ReleaseDate.parseIso("2023-10-27"))
        assertEquals(ReleaseDate(2023, 10), ReleaseDate.parseIso("2023-10"))
        assertEquals(ReleaseDate(2023), ReleaseDate.parseIso("2023"))
        assertEquals(ReleaseDate(2023, 10, 27), ReleaseDate.parseIso("2023-10-27T00:00:00Z"))
    }

    @Test
    fun should_capThePrecision_when_spotifySaysHowMuchIsReal() {
        assertEquals(ReleaseDate(1981), ReleaseDate.parseIso("1981-01-01", precision = "year"))
        assertEquals(ReleaseDate(1981, 12), ReleaseDate.parseIso("1981-12-01", precision = "month"))
        assertEquals(ReleaseDate(1981, 12, 3), ReleaseDate.parseIso("1981-12-03", precision = "day"))
    }

    @Test
    fun should_dropWhatIsNotARealDate_when_partsAreOutOfRange() {
        assertNull(ReleaseDate.parseIso("0000"))
        assertNull(ReleaseDate.parseIso(""))
        assertNull(ReleaseDate.parseIso(null))
        assertNull(ReleaseDate.parseIso("soon"))
        assertEquals(ReleaseDate(2023), ReleaseDate.parseIso("2023-13-01"))
        assertEquals(ReleaseDate(2023, 2), ReleaseDate.parseIso("2023-02-30"))
        assertEquals(ReleaseDate(2024, 2, 29), ReleaseDate.parseIso("2024-02-29"))
        assertEquals(ReleaseDate(2023), ReleaseDate.of(2023, null, 5))
        assertNull(ReleaseDate.of(0, 1, 1))
        assertNull(ReleaseDate.of(null, 1, 1))
    }

    @Test
    fun should_preferTheMorePreciseDate_when_comparing() {
        val year = ReleaseDate(2023)
        val day = ReleaseDate(2023, 10, 27)
        assertEquals(day, year.orMorePrecise(day))
        assertEquals(day, day.orMorePrecise(year))
        assertEquals(year, year.orMorePrecise(ReleaseDate(2022)))
        assertEquals(year, null.orMorePrecise(year))
        assertEquals(year, year.orMorePrecise(null))
    }

    @Test
    fun should_keepTheDate_when_anAlbumRoundTripsThroughTheDetailCache() {
        val album = Album(
            id = MediaId.subsonic("al-1"),
            name = "Album",
            artist = "Artist",
            artistId = null,
            coverArt = null,
            songCount = 1,
            durationSec = 60,
            year = 2022,
            genre = null,
            releaseDate = ReleaseDate(2022, 6, 12),
        )

        assertEquals(ReleaseDate(2022, 6, 12), AlbumDto.from(album).toDomain().releaseDate)
        assertNull(AlbumDto.from(album.copy(releaseDate = null)).toDomain().releaseDate)
        // Rows written now are marked; a row from before the date existed decodes without the mark.
        assertEquals(true, AlbumDto.from(album).dated)
        val old = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(AlbumDto.serializer(), """{"id":"subsonic:al-1","name":"Album","year":2022}""")
        assertEquals(false, old.dated)
    }
}
