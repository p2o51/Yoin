package com.gpo.yoin.data.source.applemusic

import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.remote.applemusic.AppleMusicSong
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleMusicNameResolverTest {
    private data class Read(val segments: List<String>, val query: Map<String, String>)

    private val reads = mutableListOf<Read>()
    private val isrcReads = mutableListOf<Triple<String, List<String>, String>>()
    private var answers: (Read) -> String = { """{"data":[]}""" }
    private var isrcAnswers: Map<String, List<AppleMusicSong>> = emptyMap()

    private val resolver = AppleMusicNameResolver(
        catalog = { segments, query ->
            val read = Read(segments, query)
            reads += read
            Json.parseToJsonElement(answers(read)).jsonObject
        },
        songsByIsrc = { storefront, isrcs, language ->
            isrcReads += Triple(storefront, isrcs, language)
            isrcAnswers
        },
    )

    private fun track(id: String, title: String, artist: String, genre: String? = null, isrc: String? = null) = Track(
        id = MediaId(MediaId.PROVIDER_APPLE_MUSIC, id),
        title = title,
        artist = artist,
        artistId = null,
        album = "Album $id",
        albumId = null,
        coverArt = null,
        durationSec = null,
        trackNumber = null,
        year = null,
        genre = genre,
        userRating = null,
        extras = buildMap { isrc?.let { put(Track.EXTRA_ISRC, it) } },
    )

    private fun song(id: String, name: String, artist: String, album: String) =
        """{"id":"$id","type":"songs","attributes":{"name":"$name","artistName":"$artist","albumName":"$album"}}"""

    @Test fun detect_goesByGenre_thenIsrc_thenScript() {
        assertEquals(OriginalNameTarget.Japan, OriginalNameTarget.detect("Sunny", "Yorushika", "J-Pop", "USXXX0000001"))
        assertEquals(OriginalNameTarget.Korea, OriginalNameTarget.detect("Love", "Someone", null, "KRA401500001"))
        assertEquals(OriginalNameTarget.MainlandChina, OriginalNameTarget.detect("x", "y", "华语流行", null))
        assertEquals(OriginalNameTarget.Japan, OriginalNameTarget.detect("夜に駆ける", "YOASOBI", null, null))
        assertEquals(OriginalNameTarget.MainlandChina, OriginalNameTarget.detect("晴天", "周杰伦", null, null))
        assertNull(OriginalNameTarget.detect("Bad Guy", "Billie Eilish", "Alternative", "USUM71900764"))
    }

    @Test fun accepts_originalTakesOnlyANameInItsOwnScript() {
        val original = AppleMusicNameRegion.Original
        assertTrue(OriginalNameTarget.accepts(original, OriginalNameTarget.Japan, "Sunny", "晴る"))
        // The storefront answered in English: nothing original about it.
        assertEquals(false, OriginalNameTarget.accepts(original, OriginalNameTarget.Japan, "Sunny", "Haru"))
        // A Chinese name has Han characters; kana alone is not one.
        assertEquals(false, OriginalNameTarget.accepts(original, OriginalNameTarget.MainlandChina, "x", "かな"))
        // A fixed region shows what its storefront calls the song.
        assertTrue(
            OriginalNameTarget.accepts(AppleMusicNameRegion.Japan, OriginalNameTarget.Japan, "Kim Petras", "キム"),
        )
    }

    @Test fun original_asksTheSongsOwnStorefront_andRenamesWhatItAnswers() = runTest {
        answers = { song("1", "晴る", "ヨルシカ", "晴る - Single").let { """{"data":[$it]}""" } }
        val tracks = listOf(
            track("1", "Sunny", "Yorushika", genre = "J-Pop"),
            track("2", "Bad Guy", "Billie Eilish", genre = "Alternative"),
        )

        val renamed = resolver.tracks(AppleMusicNameRegion.Original, "us", tracks)

        assertEquals(listOf(Read(listOf("v1", "catalog", "jp", "songs"), mapOf("ids" to "1", "l" to "ja"))), reads)
        assertEquals("晴る", renamed[0].title)
        assertEquals("ヨルシカ", renamed[0].artist)
        assertEquals("晴る - Single", renamed[0].album)
        assertEquals(tracks[1], renamed[1])
    }

    @Test fun answers_areKept_soAListReadAgainAsksNothing() = runTest {
        answers = { """{"data":[]}""" }
        val tracks = listOf(track("1", "Sunny", "Yorushika", genre = "J-Pop"))

        resolver.tracks(AppleMusicNameRegion.Original, "us", tracks)
        val again = resolver.tracks(AppleMusicNameRegion.Original, "us", tracks)

        assertEquals(1, reads.size)
        assertEquals(tracks, again)
    }

    @Test fun theAccountsOwnStorefront_isNeverAsked() = runTest {
        val tracks = listOf(track("1", "夜に駆ける", "YOASOBI", genre = "J-Pop"))

        assertEquals(tracks, resolver.tracks(AppleMusicNameRegion.Original, "jp", tracks))
        assertEquals(tracks, resolver.tracks(AppleMusicNameRegion.Japan, "jp", tracks))
        assertTrue(reads.isEmpty())
    }

    @Test fun aSongMissingByItsId_isAskedForByIsrc() = runTest {
        isrcAnswers = mapOf(
            "JPXX01900001" to listOf(
                AppleMusicSong("9", null, "9", "夜に駆ける", "YOASOBI", "THE BOOK", null, null, null),
            ),
        )
        val tracks = listOf(track("1", "Racing Into the Night", "YOASOBI", isrc = "JPXX01900001"))

        val renamed = resolver.tracks(AppleMusicNameRegion.Original, "us", tracks)

        assertEquals(listOf(Triple("jp", listOf("JPXX01900001"), "ja")), isrcReads)
        assertEquals("夜に駆ける", renamed[0].title)
    }

    @Test fun mainlandChina_asksTheCnStorefrontForEverySong_andAlbums() = runTest {
        answers = { read ->
            if (read.segments.last() == "albums") {
                """{"data":[{"id":"10","type":"albums","attributes":{"name":"范特西","artistName":"周杰伦"}}]}"""
            } else {
                """{"data":[${song("1", "爱在西元前", "周杰伦", "范特西")}]}"""
            }
        }
        val album = Album(
            id = MediaId(MediaId.PROVIDER_APPLE_MUSIC, "10"),
            name = "Fantasy",
            artist = "Jay Chou",
            artistId = null,
            coverArt = null,
            songCount = 1,
            durationSec = null,
            year = null,
            genre = "Mandopop",
            tracks = listOf(track("1", "Love Before BC", "Jay Chou")),
        )

        val renamed = resolver.album(AppleMusicNameRegion.MainlandChina, "us", album)

        assertTrue(reads.all { it.segments[2] == "cn" && it.query["l"] == "zh-Hans-CN" })
        assertEquals("范特西", renamed.name)
        assertEquals("周杰伦", renamed.artist)
        assertEquals("爱在西元前", renamed.tracks.single().title)
    }

    @Test fun aFailedRead_keepsTheShownNames() = runTest {
        answers = { error("offline") }
        val tracks = listOf(track("1", "Sunny", "Yorushika", genre = "J-Pop"))

        assertEquals(tracks, resolver.tracks(AppleMusicNameRegion.Original, "us", tracks))
    }

    @Test fun off_asksNothing() = runTest {
        val tracks = listOf(track("1", "Sunny", "Yorushika", genre = "J-Pop"))

        assertEquals(tracks, resolver.tracks(AppleMusicNameRegion.Off, "us", tracks))
        assertTrue(reads.isEmpty())
    }
}
