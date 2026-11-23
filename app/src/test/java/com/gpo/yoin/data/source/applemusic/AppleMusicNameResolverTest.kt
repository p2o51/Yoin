package com.gpo.yoin.data.source.applemusic

import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
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
        assertEquals(OriginalNameTarget.Taiwan, OriginalNameTarget.detect("x", "y", "华语流行", null))
        assertEquals(OriginalNameTarget.MainlandChina, OriginalNameTarget.detect("x", "y", "Mandopop", "CNXXX1900001"))
        assertEquals(OriginalNameTarget.Taiwan, OriginalNameTarget.detect("Deserts Chang", null, "Mandopop", null))
        assertEquals(OriginalNameTarget.Taiwan, OriginalNameTarget.detect("Sunny Day", "Jay Chou", "Mandopop", "TWA011900001"))
        assertEquals(OriginalNameTarget.HongKong, OriginalNameTarget.detect("x", "y", "Cantopop", null))
        assertEquals(OriginalNameTarget.Japan, OriginalNameTarget.detect("夜に駆ける", "YOASOBI", null, null))
        // Han-only names without Chinese genre/ISRC (e.g. Japanese Kanji "藤井 風") must not route to CN.
        assertNull(OriginalNameTarget.detect("晴天", "周杰伦", null, null))
        assertNull(OriginalNameTarget.detect(null, "藤井 風", null, null))
        // Already-Han Traditional Chinese items stay put instead of being simplified via CN.
        assertNull(OriginalNameTarget.detect("我要給世界最悠長的濕吻", "徐佳瑩", "Mandopop", "TWA011900001"))
        assertNull(OriginalNameTarget.detect(null, "張懸", "Mandopop", null))
        assertNull(OriginalNameTarget.detect("Bad Guy", "Billie Eilish", "Alternative", "USUM71900764"))
    }

    @Test fun accepts_originalTakesOnlyANameInItsOwnScript() {
        val original = AppleMusicNameRegion.Original
        assertTrue(OriginalNameTarget.accepts(original, OriginalNameTarget.Japan, "Sunny", "晴る"))
        // The storefront answered in English: nothing original about it.
        assertEquals(false, OriginalNameTarget.accepts(original, OriginalNameTarget.Japan, "Sunny", "Haru"))
        // A Chinese name has Han characters; kana alone is not one.
        assertEquals(false, OriginalNameTarget.accepts(original, OriginalNameTarget.MainlandChina, "x", "かな"))
        // Already-Han names (Japanese Kanji or Traditional Chinese) are never overwritten by CN/TW/HK in Original mode.
        assertEquals(false, OriginalNameTarget.accepts(original, OriginalNameTarget.MainlandChina, "藤井 風", "藤井风"))
        assertEquals(false, OriginalNameTarget.accepts(original, OriginalNameTarget.MainlandChina, "張懸", "张悬"))
        assertTrue(OriginalNameTarget.accepts(original, OriginalNameTarget.Taiwan, "Rainie Yang", "楊丞琳"))
        // K-Pop artist names stay international in Original mode while Korean song titles still restore.
        assertEquals(false, OriginalNameTarget.accepts(original, OriginalNameTarget.Korea, "NewJeans", "뉴진스", isArtist = true))
        assertEquals(false, OriginalNameTarget.accepts(original, OriginalNameTarget.Korea, "ROSÉ", "로제", isArtist = true))
        assertEquals(false, OriginalNameTarget.accepts(original, OriginalNameTarget.Korea, "少女時代", "소녀시대", isArtist = true))
        assertTrue(OriginalNameTarget.accepts(original, OriginalNameTarget.Korea, "Good Day", "좋은 날", isArtist = false))
        // A fixed region shows what its storefront calls the song/artist.
        assertTrue(
            OriginalNameTarget.accepts(AppleMusicNameRegion.Japan, OriginalNameTarget.Japan, "Kim Petras", "キム"),
        )
        assertTrue(
            OriginalNameTarget.accepts(
                AppleMusicNameRegion.MainlandChina,
                OriginalNameTarget.MainlandChina,
                "張懸",
                "张悬",
            ),
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

    @Test fun curatedStorefronts_roundTripKeys_andResolveAnyRegion() = runTest {
        assertEquals(AppleMusicNameRegion.MainlandChina, AppleMusicNameRegion.fromKey("cn"))
        assertEquals(AppleMusicNameRegion.Japan, AppleMusicNameRegion.fromKey("jp"))
        assertEquals(AppleMusicNameRegion.Off, AppleMusicNameRegion.fromKey("unknown"))

        val taiwan = AppleMusicNameRegion.fromKey("tw") as AppleMusicNameRegion.Storefront
        assertEquals("tw", taiwan.key)
        assertEquals("zh-Hant-TW", taiwan.language)
        assertEquals("China mainland", AppleMusicNameRegion.displayName("cn", java.util.Locale.US))
        assertEquals("中国大陆", AppleMusicNameRegion.displayName("cn", java.util.Locale.SIMPLIFIED_CHINESE))

        answers = { song("1", "晴天", "周杰倫", "葉惠美").let { """{"data":[$it]}""" } }
        val renamed = resolver.tracks(taiwan, "us", listOf(track("1", "Sunny Day", "Jay Chou")))
        assertEquals(listOf(Read(listOf("v1", "catalog", "tw", "songs"), mapOf("ids" to "1", "l" to "zh-Hant-TW"))), reads)
        assertEquals("周杰倫", renamed.single().artist)
    }

    @Test fun original_recoversEnglishNamesForKatakanaOrTranslatedWesternSongs() = runTest {
        assertEquals(
            OriginalNameTarget.UnitedStates,
            OriginalNameTarget.detect("Bad Guy", "ビリー・アイリッシュ", "Alternative", "USUM71900764"),
        )
        assertEquals(
            OriginalNameTarget.UnitedStates,
            OriginalNameTarget.detect("ミッドナイツ", "テイラー・スウィフト", "Pop", null),
        )
        assertEquals(
            OriginalNameTarget.UnitedStates,
            OriginalNameTarget.detect("红", "泰勒·斯威夫特", "Pop", "USCJY1231001"),
        )
        assertEquals(
            OriginalNameTarget.Japan,
            OriginalNameTarget.detect("晴る", "ヨルシカ", "J-Pop", "JPPO02400001"),
        )
        assertTrue(
            OriginalNameTarget.accepts(
                AppleMusicNameRegion.Original,
                OriginalNameTarget.UnitedStates,
                "ビリー・アイリッシュ",
                "Billie Eilish",
            ),
        )
        assertEquals(
            false,
            OriginalNameTarget.accepts(
                AppleMusicNameRegion.Original,
                OriginalNameTarget.UnitedStates,
                "Bad Guy",
                "bad guy",
            ),
        )

        answers = {
            song("1", "bad guy", "Billie Eilish", "WHEN WE ALL FALL ASLEEP").let { """{"data":[$it]}""" }
        }
        val tracks = listOf(
            track("1", "Bad Guy", "ビリー・アイリッシュ", genre = "Alternative", isrc = "USUM71900764"),
            track("2", "晴る", "ヨルシカ", genre = "J-Pop", isrc = "JPPO02400001"),
        )

        val renamed = resolver.tracks(AppleMusicNameRegion.Original, "jp", tracks)

        assertEquals(listOf(Read(listOf("v1", "catalog", "us", "songs"), mapOf("ids" to "1", "l" to "en-US"))), reads)
        assertEquals("Bad Guy", renamed[0].title)
        assertEquals("Billie Eilish", renamed[0].artist)
        assertEquals(tracks[1], renamed[1])
    }

    @Test fun artists_resolvesKatakanaWesternArtists_andLibraryCatalogIds() = runTest {
        answers = {
            """{"data":[{"id":"159260351","type":"artists","attributes":{"name":"Taylor Swift"}}]}"""
        }
        val libraryTaylor = Artist(
            id = MediaId(MediaId.PROVIDER_APPLE_MUSIC, "library:r.1"),
            name = "テイラー・スウィフト",
            albumCount = null,
            coverArt = null,
        )
        val yorushika = Artist(
            id = MediaId(MediaId.PROVIDER_APPLE_MUSIC, "12345"),
            name = "ヨルシカ",
            albumCount = null,
            coverArt = null,
        )

        val renamed = resolver.artists(AppleMusicNameRegion.Original, "jp", listOf(libraryTaylor, yorushika)) { a ->
            when (a.id.rawId) {
                "library:r.1" -> AppleMusicNameResolver.ArtistHint(catalogId = "159260351", genre = "Pop")
                else -> AppleMusicNameResolver.ArtistHint(catalogId = a.id.rawId, genre = "J-Pop")
            }
        }

        assertEquals(
            listOf(
                Read(
                    listOf("v1", "catalog", "us", "artists"),
                    mapOf("ids" to "159260351", "l" to "en-US", "views" to "top-songs"),
                ),
            ),
            reads,
        )
        assertEquals("Taylor Swift", renamed[0].name)
        assertEquals("ヨルシカ", renamed[1].name)
    }

    @Test fun original_onJpStorefront_recoversSingleWordKatakana_andProtectsJpTwAndKpopArtists() = runTest {
        answers = { read ->
            when (read.segments.last()) {
                "artists" -> """{"data":[
                    {"id":"101","type":"artists","attributes":{"name":"Muse"}},
                    {"id":"102","type":"artists","attributes":{"name":"Björk"}}
                ]}"""
                "albums" -> """{"data":[{"id":"201","type":"albums","attributes":{"name":"Absolution","artistName":"Muse"}}]}"""
                else -> """{"data":[${song("301", "Time Is Running Out", "Muse", "Absolution")}]}"""
            }
        }
        val artists = listOf(
            Artist(MediaId(MediaId.PROVIDER_APPLE_MUSIC, "101"), "ミューズ", null, null),
            Artist(MediaId(MediaId.PROVIDER_APPLE_MUSIC, "102"), "ビョーク", null, null),
            Artist(MediaId(MediaId.PROVIDER_APPLE_MUSIC, "103"), "藤井 風", null, null),
            Artist(MediaId(MediaId.PROVIDER_APPLE_MUSIC, "104"), "張懸", null, null),
            Artist(MediaId(MediaId.PROVIDER_APPLE_MUSIC, "105"), "NewJeans", null, null),
            Artist(MediaId(MediaId.PROVIDER_APPLE_MUSIC, "106"), "ヨルシカ", null, null),
        )
        val renamedArtists = resolver.artists(AppleMusicNameRegion.Original, "jp", artists) { a ->
            val genre = when (a.id.rawId) {
                "101" -> "Alternative"
                "102" -> "Electronic"
                "104" -> "Mandopop"
                "105" -> "K-Pop"
                "106" -> "アニメ"
                else -> null
            }
            AppleMusicNameResolver.ArtistHint(catalogId = a.id.rawId, genre = genre)
        }
        assertEquals(listOf("Muse", "Björk", "藤井 風", "張懸", "NewJeans", "ヨルシカ"), renamedArtists.map { it.name })
        assertEquals(1, reads.size)
        assertEquals("us", reads.single().segments[2])

        val album = Album(
            id = MediaId(MediaId.PROVIDER_APPLE_MUSIC, "201"),
            name = "Absolution",
            artist = "ミューズ",
            artistId = null,
            coverArt = null,
            songCount = 1,
            durationSec = null,
            year = null,
            genre = "Alternative",
            tracks = listOf(track("301", "Time Is Running Out", "ミューズ", genre = "Alternative", isrc = "GBAHT0300001")),
        )
        val renamedAlbum = resolver.album(AppleMusicNameRegion.Original, "jp", album)
        assertEquals("Muse", renamedAlbum.artist)
        assertEquals("Muse", renamedAlbum.tracks.single().artist)
    }

    @Test fun albumPage_syncsHeaderArtistFromRenamedTracksWhenAlbumLookupMisses() = runTest {
        answers = { read ->
            if (read.segments.last() == "albums") {
                """{"data":[]}"""
            } else {
                """{"data":[${song("301", "Time Is Running Out", "Muse", "Absolution")}]}"""
            }
        }
        val album = Album(
            id = MediaId(MediaId.PROVIDER_APPLE_MUSIC, "201"),
            name = "Absolution",
            artist = "ミューズ",
            artistId = null,
            coverArt = null,
            songCount = 1,
            durationSec = null,
            year = null,
            genre = "Alternative",
            tracks = listOf(track("301", "Time Is Running Out", "ミューズ", genre = "Alternative", isrc = "GBAHT0300001")),
        )

        val renamed = resolver.album(AppleMusicNameRegion.Original, "jp", album)

        assertEquals("Muse", renamed.artist)
        assertEquals("Muse", renamed.tracks.single().artist)
    }

    @Test fun original_protectsNativeJapaneseKatakanaArtistsAndAlbumsViaTopSongsAndTrackIsrcs() = runTest {
        answers = { read ->
            when {
                read.segments == listOf("v1", "catalog", "us", "artists") -> """{"data":[
                    {"id":"1250709916","type":"artists","attributes":{"name":"Yorushika","genreNames":["Alternative"]},
                     "views":{"top-songs":{"data":[{"id":"s1","attributes":{"name":"晴る","isrc":"JPPO02400001"}}]}}
                    },
                    {"id":"252312257","type":"artists","attributes":{"name":"sakanaction","genreNames":["Rock"]},
                     "views":{"top-songs":{"data":[{"id":"s2","attributes":{"name":"新宝島","isrc":"JPVI01500001"}}]}}
                    },
                    {"id":"164483115","type":"artists","attributes":{"name":"HAIM","genreNames":["Alternative"]},
                     "views":{"top-songs":{"data":[{"id":"s3","attributes":{"name":"The Wire","isrc":"USUM71300001"}}]}}
                    }
                ]}"""
                read.segments == listOf("v1", "catalog", "us", "albums") -> """{"data":[
                    {"id":"a-yoru","type":"albums","attributes":{"name":"Elma","artistName":"Yorushika","genreNames":["Alternative"]},
                     "relationships":{"tracks":{"data":[{"id":"t1","attributes":{"name":"車窓","isrc":"JPPO01900001"}}]}}
                    }
                ]}"""
                else -> """{"data":[]}"""
            }
        }
        val artists = listOf(
            Artist(MediaId(MediaId.PROVIDER_APPLE_MUSIC, "1250709916"), "ヨルシカ", null, null),
            Artist(MediaId(MediaId.PROVIDER_APPLE_MUSIC, "252312257"), "サカナクション", null, null),
            Artist(MediaId(MediaId.PROVIDER_APPLE_MUSIC, "164483115"), "ハイム", null, null),
        )
        val renamedArtists = resolver.artists(AppleMusicNameRegion.Original, "jp", artists) { a ->
            AppleMusicNameResolver.ArtistHint(catalogId = a.id.rawId, genre = "Alternative")
        }
        assertEquals(listOf("ヨルシカ", "サカナクション", "HAIM"), renamedArtists.map { it.name })

        val elma = Album(
            id = MediaId(MediaId.PROVIDER_APPLE_MUSIC, "a-yoru".replace("a-yoru", "9001")),
            name = "エルマ",
            artist = "ヨルシカ",
            artistId = null,
            coverArt = null,
            songCount = 1,
            durationSec = null,
            year = null,
            genre = "Alternative",
            tracks = emptyList(),
        )
        answers = { read ->
            if (read.segments == listOf("v1", "catalog", "us", "albums")) {
                """{"data":[
                    {"id":"9001","type":"albums","attributes":{"name":"Elma","artistName":"Yorushika","genreNames":["Alternative"]},
                     "relationships":{"tracks":{"data":[{"id":"t1","attributes":{"name":"車窓","isrc":"JPPO01900001"}}]}}
                    }
                ]}"""
            } else {
                """{"data":[]}"""
            }
        }
        val renamedElma = resolver.albums(AppleMusicNameRegion.Original, "jp", listOf(elma)).single()
        assertEquals("エルマ", renamedElma.name)
        assertEquals("ヨルシカ", renamedElma.artist)
    }

    @Test fun original_recoversMissedJpRegionalEditionAlbumsViaGlobalArtistIdAndTrackIsrc() = runTest {
        answers = { read ->
            when {
                // Regional JP album IDs (Red, Pure Heroine, Tanya Chua) miss in target storefront.
                read.segments == listOf("v1", "catalog", "us", "albums") -> """{"data":[]}"""
                read.segments == listOf("v1", "catalog", "tw", "albums") -> """{"data":[]}"""
                // Source storefront (jp) returns global catalog artist IDs & first-track ISRCs in one batch.
                read.segments == listOf("v1", "catalog", "jp", "albums") -> """{"data":[
                    {"id":"5001","type":"albums","attributes":{"name":"Red","artistName":"テイラー・スウィフト","genreNames":["Pop"]},
                     "relationships":{"artists":{"data":[{"id":"159260351"}]},"tracks":{"data":[{"id":"t1","attributes":{"name":"State of Grace","isrc":"USCJY1231001"}}]}}
                    },
                    {"id":"5002","type":"albums","attributes":{"name":"我要給世界最悠長的濕吻","artistName":"タニア・チュア","genreNames":["Mandopop"]},
                     "relationships":{"artists":{"data":[{"id":"300999"}]},"tracks":{"data":[{"id":"t2","attributes":{"name":"遺書","isrc":"TWA011800001"}}]}}
                    }
                ]}"""
                read.segments == listOf("v1", "catalog", "us", "artists") -> """{"data":[
                    {"id":"159260351","type":"artists","attributes":{"name":"Taylor Swift"}}
                ]}"""
                read.segments == listOf("v1", "catalog", "tw", "artists") -> """{"data":[
                    {"id":"300999","type":"artists","attributes":{"name":"蔡健雅"}}
                ]}"""
                else -> """{"data":[]}"""
            }
        }
        val red = Album(MediaId(MediaId.PROVIDER_APPLE_MUSIC, "5001"), "Red", "テイラー・スウィフト", null, null, 1, null, null, "Pop")
        val tanya = Album(MediaId(MediaId.PROVIDER_APPLE_MUSIC, "5002"), "我要給世界最悠長的濕吻", "タニア・チュア", null, null, 1, null, null, "Mandopop")

        val renamed = resolver.albums(AppleMusicNameRegion.Original, "jp", listOf(red, tanya))

        assertEquals("Taylor Swift", renamed[0].artist)
        assertEquals("Red", renamed[0].name)
        assertEquals("蔡健雅", renamed[1].artist)
        assertEquals("我要給世界最悠長的濕吻", renamed[1].name)
    }

    @Test
    fun should_reclassifyMainlandMandopopByCnIsrc_while_keepingTaiwanTraditional_and_fixingHiraganaTypo() = runTest {
        val queries = mutableListOf<Pair<String, Map<String, String>>>()
        answers = { read ->
            val key = "${read.segments[2]}/${read.segments[3]}"
            queries += key to read.query
            when (key) {
                "tw/artists" -> """{"data":[
                    {"id":"1546181214","type":"artists","attributes":{"name":"單依純"},
                     "views":{"top-songs":{"data":[{"id":"s1","attributes":{"name":"永不失联的爱","isrc":"CNA022000001"}}]}}
                    },
                    {"id":"387317532","type":"artists","attributes":{"name":"徐佳瑩"},
                     "views":{"top-songs":{"data":[{"id":"s2","attributes":{"name":"身騎白馬","isrc":"TWB430900001"}}]}}
                    }
                ]}"""
                "cn/artists" -> """{"data":[
                    {"id":"1546181214","type":"artists","attributes":{"name":"单依纯"}}
                ]}"""
                "us/albums" -> """{"data":[
                    {"id":"1730001","type":"albums","attributes":{"name":"Lives Outgrown","artistName":"Beth Gibbons"},
                     "relationships":{"tracks":{"data":[{"id":"t1","attributes":{"name":"Tell Me Who You Are Today","isrc":"GBCEL2400001"}}]}}
                    }
                ]}"""
                "us/artists" -> """{"data":[
                    {"id":"540328","type":"artists","attributes":{"name":"Björk"},
                     "views":{"top-songs":{"data":[{"id":"s3","attributes":{"name":"Army of Me","isrc":"GBBKS9500001"}}]}}
                    }
                ]}"""
                else -> """{"data":[]}"""
            }
        }

        // 1. Mainland Mandopop artist (CN ISRC) gets Simplified Chinese; Taiwan (TW ISRC) stays Traditional
        val shan = Artist(MediaId(MediaId.PROVIDER_APPLE_MUSIC, "library:r.shan"), "シャン・イーチュン", null, null)
        val lala = Artist(MediaId(MediaId.PROVIDER_APPLE_MUSIC, "library:r.lala"), "ララ・スー", null, null)
        val bjork = Artist(MediaId(MediaId.PROVIDER_APPLE_MUSIC, "library:r.bjork"), "ビョーク", null, null)
        val renamedArtists = resolver.artists(AppleMusicNameRegion.Original, "jp", listOf(shan, lala, bjork)) {
            when (it.id.rawId) {
                "library:r.shan" -> AppleMusicNameResolver.ArtistHint("1546181214", "マンドポップ")
                "library:r.lala" -> AppleMusicNameResolver.ArtistHint("387317532", "マンドポップ")
                else -> AppleMusicNameResolver.ArtistHint("540328", "Electronic")
            }
        }
        assertEquals("单依纯", renamedArtists[0].name)
        assertEquals("徐佳瑩", renamedArtists[1].name)
        assertEquals("Björk", renamedArtists[2].name)

        // 2. Hiragana typo in Katakana artist ("べス・ギボンズ") resolves to Beth Gibbons;
        //    delisted album ("Utopia | ビョーク") whose ID 404s on both us & jp still gets cached Björk!
        val bethAlbum = Album(MediaId(MediaId.PROVIDER_APPLE_MUSIC, "1730001"), "Lives Outgrown", "べス・ギボンズ", null, null, 1, null, null, "Alternative")
        val utopia = Album(MediaId(MediaId.PROVIDER_APPLE_MUSIC, "9999999"), "Utopia", "ビョーク", null, null, 1, null, null, "Electronic")
        val renamedAlbums = resolver.albums(AppleMusicNameRegion.Original, "jp", listOf(bethAlbum, utopia))
        assertEquals("Beth Gibbons", renamedAlbums[0].artist)
        assertEquals("Björk", renamedAlbums[1].artist)

        // 3. Fixed storefront (US) missed-album fallback uses include=artists ONLY (no tracks/ISRC storm)
        queries.clear()
        val missedUs = Album(MediaId(MediaId.PROVIDER_APPLE_MUSIC, "8888888"), "Some JP Album", "オアシス", null, null, 1, null, null, "Rock")
        resolver.albums(AppleMusicNameRegion.Storefront("us", "en-US"), "jp", listOf(missedUs))
        val jpFallback = queries.firstOrNull { it.first == "jp/albums" }
        assertEquals("artists", jpFallback?.second?.get("include"))
    }

    @Test fun should_protectVariousArtistsFromQunxing_preserveMultiArtists_and_healEarlierDelistedAlbums() = runTest {
        answers = { read ->
            when ("${read.segments[2]}/${read.segments[3]}") {
                "cn/albums" -> """{"data":[
                    {"id":"7001","type":"albums","attributes":{"name":"华语合辑","artistName":"群星"}}
                ]}"""
                "tw/albums" -> """{"data":[
                    {"id":"7001","type":"albums","attributes":{"name":"華語合輯","artistName":"群星"},
                     "relationships":{"tracks":{"data":[{"id":"t1","attributes":{"name":"歌","isrc":"CN0012400001"}}]}}
                    },
                    {"id":"7004","type":"albums","attributes":{"name":"莫文蔚精選","artistName":"莫文蔚"}}
                ]}"""
                "jp/albums" -> """{"data":[
                    {"id":"7005","type":"albums","attributes":{"name":"${"$"}ome ${"$"}exy ${"$"}ongs 4 U","artistName":"PARTYNEXTDOOR & ドレイク","genreNames":["R&B"]},
                     "relationships":{"artists":{"data":[{"id":"66001"},{"id":"66002"}]}}
                    }
                ]}"""
                "us/artists" -> """{"data":[
                    {"id":"66001","type":"artists","attributes":{"name":"PARTYNEXTDOOR"}},
                    {"id":"66002","type":"artists","attributes":{"name":"Drake"}}
                ]}"""
                else -> """{"data":[]}"""
            }
        }
        val cnComp = Album(MediaId(MediaId.PROVIDER_APPLE_MUSIC, "7001"), "Mandopop Hits", "Various Artists", null, null, 1, null, null, "マンドポップ")
        val deComp = Album(MediaId(MediaId.PROVIDER_APPLE_MUSIC, "7002"), "Liebe und Euphorie", "Various Artists", null, null, 1, null, null, "Electronic")
        val earlierKaren = Album(MediaId(MediaId.PROVIDER_APPLE_MUSIC, "7003"), "一朵金花", "カレン・モク", null, null, 1, null, null, "マンドポップ")
        val laterKaren = Album(MediaId(MediaId.PROVIDER_APPLE_MUSIC, "7004"), "莫文蔚精選", "カレン・モク", null, null, 1, null, null, "マンドポップ")
        val collab = Album(MediaId(MediaId.PROVIDER_APPLE_MUSIC, "7005"), "\$ome \$exy \$ongs 4 U", "PARTYNEXTDOOR & ドレイク", null, null, 1, null, null, "R&B")

        val renamed = resolver.albums(
            AppleMusicNameRegion.Original,
            "jp",
            listOf(cnComp, deComp, earlierKaren, laterKaren, collab),
        )
        assertEquals("Various Artists", renamed[0].artist)
        assertEquals("Various Artists", renamed[1].artist)
        assertEquals("莫文蔚", renamed[2].artist)
        assertEquals("莫文蔚", renamed[3].artist)
        assertEquals("PARTYNEXTDOOR & Drake", renamed[4].artist)
    }
}
