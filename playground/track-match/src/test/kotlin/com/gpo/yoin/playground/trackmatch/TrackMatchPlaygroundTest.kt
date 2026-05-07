package com.gpo.yoin.playground.trackmatch

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMatchPlaygroundTest {

    @Test
    fun should_parse_quoted_csv_fields() {
        val row = parseCsvLine("2026,id,\"Name, With Comma\",\"Artist \"\"A\"\"\",Album,123")

        assertEquals(
            listOf("2026", "id", "Name, With Comma", "Artist \"A\"", "Album", "123"),
            row,
        )
    }

    @Test
    fun should_sample_deterministically() {
        val rows = (0 until 20).map {
            LikedSongRow("$it", "track-$it", "Track $it", "Artist", "Album", 1000L)
        }

        assertEquals(
            deterministicSample(rows, sampleSize = 5, seed = 42L),
            deterministicSample(rows, sampleSize = 5, seed = 42L),
        )
    }

    @Test
    fun should_score_exact_candidate_as_accepted() {
        val row = LikedSongRow(
            savedAt = "",
            trackId = "spotify-track",
            trackName = "Time Today",
            artists = "Kero Kero Bonito",
            albumName = "Civilisation",
            durationMs = 180_000L,
        )

        val score = scoreCandidate(
            row = row,
            title = "Time Today",
            artists = listOf("Kero Kero Bonito"),
            releases = listOf("Civilisation"),
            durationMs = 181_000L,
        )

        assertTrue(score >= 100)
    }

    @Test
    fun should_parse_adb_content_query_row() {
        val parsed = parseContentQueryRow(
            "Row: 0 status=ok, profile_id=abc, access_token=tok, refresh_token=ref, expires_at_epoch_ms=1",
        )

        assertEquals("tok", parsed["access_token"])
        assertEquals("ref", parsed["refresh_token"])
    }

    @Test
    fun should_parse_liked_songs_csv_file() {
        val file = File.createTempFile("liked-songs", ".csv")
        file.writeText(
            """
            saved_at,track_id,track_name,artists,album_name,duration_ms
            2026-01-01,t1,Song,A; B,Album,123
            """.trimIndent(),
        )

        val rows = parseLikedSongsCsv(file)

        assertEquals(1, rows.size)
        assertEquals("t1", rows.single().trackId)
    }
}
