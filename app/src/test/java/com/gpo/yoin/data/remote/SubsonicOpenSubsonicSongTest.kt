package com.gpo.yoin.data.remote

import com.gpo.yoin.data.model.AudioFormat
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.source.subsonic.toTrack
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubsonicOpenSubsonicSongTest {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun song(fields: String): Song = json.decodeFromString(
        SubsonicResponse.serializer(),
        """{"subsonic-response":{"status":"ok","album":{"id":"al","name":"A","song":[{"id":"s1"$fields}]}}}""",
    ).subsonicResponse.album!!.song.single()

    @Test
    fun should_parseEveryOpenSubsonicField_when_serverSendsThem() {
        val parsed = song(
            ""","suffix":"flac","bitRate":2116,"bitDepth":16,"samplingRate":44100,"channelCount":6,
            "discNumber":2,"displayArtist":"A & B","displayAlbumArtist":"A",
            "artists":[{"id":"ar1","name":"A"},{"id":"ar2","name":"B"}]""",
        )
        assertEquals(16, parsed.bitDepth)
        assertEquals(44100, parsed.samplingRate)
        assertEquals(6, parsed.channelCount)
        assertEquals(2, parsed.discNumber)
        assertEquals("A & B", parsed.displayArtist)
        assertEquals("A", parsed.displayAlbumArtist)
        assertEquals(listOf(ArtistRef("ar1", "A"), ArtistRef("ar2", "B")), parsed.artists)
    }

    @Test
    fun should_leaveFieldsNull_when_plainSubsonicServerOmitsThem() {
        val parsed = song(""","suffix":"mp3","bitRate":320""")
        assertNull(parsed.bitDepth)
        assertNull(parsed.samplingRate)
        assertNull(parsed.channelCount)
        assertNull(parsed.discNumber)
        assertNull(parsed.displayArtist)
        assertTrue(parsed.artists.isEmpty())
    }

    @Test
    fun should_parseArtistWithoutId_when_serverOmitsIt() {
        val parsed = song(""","artists":[{"name":"Guest"}]""")
        assertEquals(listOf(ArtistRef(null, "Guest")), parsed.artists)
    }

    @Test
    fun should_mapFormatDiscAndCredits_when_songIsOpenSubsonic() {
        val track = song(
            ""","artist":"A","artistId":"ar1","suffix":"flac","bitRate":2116,"bitDepth":16,
            "samplingRate":44100,"channelCount":6,"discNumber":2,"displayArtist":"A & B",
            "artists":[{"id":"ar1","name":"A"},{"id":"ar2","name":"B"}]""",
        ).toTrack()
        assertEquals(AudioFormat("flac", 2116, 16, 44100, 6), track.audioFormat)
        assertEquals(2, track.discNumber)
        assertEquals("A & B", track.artist)
        assertEquals(listOf("A", "B"), track.creditedArtists)
        assertEquals(
            listOf(MediaId.subsonic("ar1"), MediaId.subsonic("ar2")),
            track.artistCredits.map { it.id },
        )
    }

    @Test
    fun should_keepOneCredit_when_songHasOneArtist() {
        val track = song(""","artist":"A","artistId":"ar1","artists":[{"id":"ar1","name":"A"}]""").toTrack()
        assertEquals(listOf("A"), track.creditedArtists)
        assertEquals(MediaId.subsonic("ar1"), track.artistCredits.single().id)
    }

    @Test
    fun should_haveNoFormatOrDisc_when_serverSaysNothing() {
        val track = song(""","title":"T"""").toTrack()
        assertNull(track.audioFormat)
        assertNull(track.discNumber)
    }

    @Test
    fun should_readTraits_when_extensionsAnswer() {
        val body = json.decodeFromString(
            SubsonicResponse.serializer(),
            """{"subsonic-response":{"status":"ok","type":"navidrome","openSubsonic":true,
            "openSubsonicExtensions":[{"name":"transcodeOffset","versions":[1]},{"name":"songLyrics","versions":[1]}]}}""",
        ).subsonicResponse
        assertEquals("navidrome", body.type)
        assertEquals(listOf("transcodeOffset", "songLyrics"), body.openSubsonicExtensions.map { it.name })
    }
}
