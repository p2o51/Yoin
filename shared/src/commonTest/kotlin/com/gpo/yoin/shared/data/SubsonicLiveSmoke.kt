package com.gpo.yoin.shared.data

import com.gpo.yoin.shared.data.model.Lyrics
import com.gpo.yoin.shared.data.model.SongInfo
import com.gpo.yoin.shared.data.subsonic.AlbumListType
import com.gpo.yoin.shared.data.subsonic.SubsonicClient
import com.gpo.yoin.shared.data.subsonic.platformHttpEngine
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live check against a real server through the platform engine (OkHttp on
 * the JVM host, NSURLSession on the iOS simulator). Not a test by itself:
 * `SubsonicLiveSmokeTest` in androidHostTest / iosTest calls it only when
 * YOIN_SUBSONIC_SMOKE_URL is set, so normal runs skip it.
 *
 *     python3 …/promo-demo-2026-11-02/demo_subsonic.py 4977 &
 *     YOIN_SUBSONIC_SMOKE_URL=http://127.0.0.1:4977 \
 *       SIMCTL_CHILD_YOIN_SUBSONIC_SMOKE_URL=http://127.0.0.1:4977 \
 *       ./gradlew :shared:allTests --tests '*SubsonicLiveSmokeTest*'
 */
internal object SubsonicLiveSmoke {
    const val ENV = "YOIN_SUBSONIC_SMOKE_URL"

    /** Returns one line per step for the test log. */
    suspend fun run(baseUrl: String, username: String = "demo", password: String = "demo"): List<String> {
        val log = mutableListOf<String>()
        val c = SubsonicClient(baseUrl, username, password)
        try {
            val info = c.ping()
            log += "ping: api=${info.apiVersion} type=${info.type} openSubsonic=${info.openSubsonic}"

            val newest = c.getAlbumList2(AlbumListType.NEWEST, 5, 0)
            assertTrue(newest.isNotEmpty(), "newest albums")
            log += "getAlbumList2(newest): ${newest.size} → ${newest.first().name}"

            val album = assertNotNull(c.getAlbum(newest.first().id))
            assertTrue(album.songs.isNotEmpty(), "album songs")
            log += "getAlbum: ${album.name} by ${album.artist}, ${album.songs.size} songs, created=${album.created}"

            val index = c.getArtists()
            val firstArtist = index.flatMap { it.artists }.first()
            log += "getArtists: ${index.size} groups, ${index.sumOf { it.artists.size }} artists"
            val artist = assertNotNull(c.getArtist(firstArtist.id))
            log += "getArtist: ${artist.name}, ${artist.albums.size} albums"

            val playlists = c.getPlaylists()
            log += "getPlaylists: ${playlists.size}"
            playlists.firstOrNull()?.let { p ->
                val detail = assertNotNull(c.getPlaylist(p.id))
                log += "getPlaylist: ${detail.summary.name}, ${detail.entries.size} entries, canWrite=${detail.summary.canWrite}"
            }

            val search = c.search3(album.name.take(3), 5, 5, 5)
            log += "search3: ${search.artists.size}/${search.albums.size}/${search.songs.size}"

            val starred = c.getStarred2()
            log += "getStarred2: ${starred.artists.size}/${starred.albums.size}/${starred.songs.size}"

            val random = c.getRandomSongs(3)
            assertEquals(3, random.size)
            log += "getRandomSongs: ${random.map { it.title }}"

            val song = album.songs.first()
            c.star(song.id, null, null)
            c.unstar(song.id, null, null)
            c.setRating(song.id, 4)
            c.scrobble(song.id, false, null)
            log += "star/unstar/setRating/scrobble: ok"

            // Some albums have no lyrics (the demo's field recordings): try each newest album's first song.
            var withLyrics: Pair<SongInfo, Lyrics>? = null
            for (candidate in newest) {
                val s = c.getAlbum(candidate.id)?.songs?.firstOrNull() ?: continue
                val l = c.lyricsFor(s.id, s.artist, s.title) ?: continue
                withLyrics = s to l
                break
            }
            log += withLyrics?.let { (s, l) ->
                "lyricsFor(${s.title}): synced=${l.synced} lines=${l.lines.size} lang=${l.language} " +
                    "first=${l.lines.first().timeMs}ms \"${l.lines.first().text}\" lineIndexAt(30s)=${l.lineIndexAt(30_000)}"
            } ?: "lyricsFor: none on this server"

            HttpClient(platformHttpEngine()).use { http ->
                val art = http.get(c.coverArtUrl(album.coverArtId, 300))
                assertTrue(art.status.isSuccess(), "cover art ${art.status}")
                log += "coverArtUrl: ${art.status.value} ${art.headers["Content-Type"]} ${art.readRawBytes().size} bytes"
                val stream = http.get(c.streamUrl(song.id, 320, null))
                assertTrue(stream.status.isSuccess(), "stream ${stream.status}")
                log += "streamUrl: ${stream.status.value} ${stream.headers["Content-Type"]} ${stream.headers["Content-Length"]} bytes"
            }
        } finally {
            c.close()
        }
        return log
    }
}
