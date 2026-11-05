package com.gpo.yoin.data.remote

import com.gpo.yoin.data.model.ReplayGain
import com.gpo.yoin.data.source.subsonic.subsonicAudioFormat
import com.gpo.yoin.data.source.subsonic.toAlbum
import com.gpo.yoin.data.source.subsonic.toTrack
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SubsonicReplayGainVersionTest {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun album(albumFields: String = "", songFields: String = ""): Album = json.decodeFromString(
        SubsonicResponse.serializer(),
        """{"subsonic-response":{"status":"ok","album":{"id":"al","name":"再生 (全景声)"$albumFields,"song":[{"id":"s1"$songFields}]}}}""",
    ).subsonicResponse.album!!

    @Test
    fun should_parseEveryReplayGainField_when_serverSendsThem() {
        val song = album(
            songFields = ""","replayGain":{"trackGain":-9.6,"albumGain":-8.1,"trackPeak":0.9886,"albumPeak":0.99,""" +
                """"baseGain":0,"fallbackGain":-6}""",
        ).song.single()
        assertEquals(SubsonicReplayGain(-9.6f, -8.1f, 0.9886f, 0.99f, 0f, -6f), song.replayGain)
        assertEquals(
            ReplayGain(
                trackGainDb = -9.6f,
                albumGainDb = -8.1f,
                trackPeak = 0.9886f,
                albumPeak = 0.99f,
                baseGainDb = null,
                fallbackGainDb = -6f,
            ),
            song.toTrack().replayGain,
        )
    }

    @Test
    fun should_keepTrackGainOnly_when_albumGainIsMissing() {
        val track = album(songFields = ""","replayGain":{"trackGain":1.0,"trackPeak":0.335}""").song.single().toTrack()
        assertEquals(ReplayGain(trackGainDb = 1.0f, trackPeak = 0.335f), track.replayGain)
    }

    @Test
    fun should_haveNoReplayGain_when_serverOmitsTheObject() {
        val song = album().song.single()
        assertNull(song.replayGain)
        assertNull(song.toTrack().replayGain)
    }

    @Test
    fun should_haveNoReplayGain_when_objectIsEmptyOrAllZero() {
        assertNull(album(songFields = ""","replayGain":{}""").song.single().toTrack().replayGain)
        assertNull(
            album(songFields = ""","replayGain":{"trackGain":0,"albumGain":0,"trackPeak":0,"albumPeak":0}""")
                .song.single().toTrack().replayGain,
        )
    }

    @Test
    fun should_keepZeroGain_when_itHasAPeak() {
        val track = album(songFields = ""","replayGain":{"trackGain":0,"trackPeak":0.5}""").song.single().toTrack()
        assertEquals(ReplayGain(trackGainDb = 0f, trackPeak = 0.5f), track.replayGain)
    }

    @Test
    fun should_parseVersionAndStripItFromTheName_when_serverAppendsIt() {
        val mapped = album(albumFields = ""","version":"全景声"""").toAlbum()
        assertEquals("全景声", mapped.version)
        assertEquals("再生 (全景声)", mapped.name)
        assertEquals("再生", mapped.nameWithoutVersion)
    }

    @Test
    fun should_haveNoVersion_when_serverOmitsIt() {
        val mapped = album().toAlbum()
        assertNull(mapped.version)
        assertEquals("再生 (全景声)", mapped.nameWithoutVersion)
    }

    @Test
    fun should_haveNoFormat_when_m4aFieldsAreAllZero() {
        val song = album(
            songFields = ""","suffix":"m4a","contentType":"audio/mp4","bitRate":0,"bitDepth":0,"samplingRate":0,""" +
                """"channelCount":0""",
        ).song.single()
        assertNull(song.subsonicAudioFormat())
        assertNull(song.toTrack().audioFormat)
    }

    @Test
    fun should_keepAacAndAlac_when_m4aHasRealFields() {
        val aac = album(songFields = ""","suffix":"m4a","bitRate":256,"samplingRate":44100,"channelCount":2""")
            .song.single().subsonicAudioFormat()
        assertEquals("aac", aac?.codec)
        val alac = album(songFields = ""","suffix":"m4a","bitRate":1411,"bitDepth":24,"samplingRate":96000""")
            .song.single().subsonicAudioFormat()
        assertEquals("alac", alac?.codec)
        assertNotNull(album(songFields = ""","suffix":"flac","bitRate":0""").song.single().subsonicAudioFormat())
    }
}
