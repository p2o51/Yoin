package com.gpo.yoin.data.offline

import com.gpo.yoin.data.remote.ServerCredentials
import com.gpo.yoin.data.remote.SubsonicApiFactory
import com.gpo.yoin.data.remote.SubsonicStreamUrls
import com.gpo.yoin.testutil.signedWithSalt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineModelTest {

    private fun stream(
        songId: String = "tr-1",
        user: String = "qa",
        salt: String = "aaaa1111",
        server: String = "https://music.example.com/navidrome",
    ): String {
        val credentials = ServerCredentials(serverUrl = server, username = user, password = "pw")
        return signedWithSalt(SubsonicApiFactory.buildStreamUrl(credentials, songId), password = "pw", salt = salt)
    }

    @Test
    fun should_keepOneKey_when_saltOrStreamingQualityChanges() {
        val key = OfflineKeys.of(stream())
        assertNotNull(key)
        assertEquals(key, OfflineKeys.of(stream(salt = "bbbb2222")))
        val transcoded = SubsonicStreamUrls.withTranscode(stream(), maxBitRateKbps = 192, format = "mp3", timeOffsetSeconds = 30)
        assertEquals(key, OfflineKeys.of(transcoded))
        assertTrue(OfflineKeys.isOfflineKey(key))
    }

    @Test
    fun should_keepAccountsAndSongsApart_when_keyed() {
        val key = OfflineKeys.of(stream())
        assertNotEquals(key, OfflineKeys.of(stream(songId = "tr-2")))
        assertNotEquals(key, OfflineKeys.of(stream(user = "other")))
        assertNotEquals(key, OfflineKeys.of(stream(server = "https://other.example.com")))
    }

    @Test
    fun should_holdNoCredential_when_keyed() {
        val key = OfflineKeys.of(stream())!!
        assertFalse("t=" in key)
        assertFalse("s=" in key)
        assertFalse("pw" in key)
    }

    @Test
    fun should_giveNoKey_when_notASignedStream() {
        assertNull(OfflineKeys.of("https://example.com/song.mp3"))
        assertNull(OfflineKeys.of("https://music.example.com/rest/getCoverArt.view?id=1&t=x&s=y"))
    }

    @Test
    fun should_roundTripRequestUri_withoutCredential() {
        val key = OfflineKeys.of(stream(songId = "a b&c"))!!
        val uri = OfflineRequestUri.build("a b&c", key, DownloadQuality.Kbps192)
        assertFalse("t=" in uri.substringAfter("&k=").let { java.net.URLDecoder.decode(it, "UTF-8") })
        val parts = OfflineRequestUri.parse(uri)!!
        assertEquals("a b&c", parts.songRawId)
        assertEquals(key, parts.key)
        assertEquals(DownloadQuality.Kbps192, parts.quality)
        assertNull(OfflineRequestUri.parse("https://example.com"))
    }

    @Test
    fun should_askMp3AtCap_when_transcodedQuality() {
        assertNull(DownloadQuality.Original.format)
        assertEquals("mp3", DownloadQuality.Kbps320.format)
        assertEquals(DownloadQuality.Kbps320, DownloadSettings().quality)
        assertFalse(DownloadSettings().overMobileData)
    }

    private val album = OfflineCollection(OfflineCollection.Kind.Album, "subsonic:al-1", "Album", "Artist", "al-1")
    private val playlist = OfflineCollection(OfflineCollection.Kind.Playlist, "subsonic:pl-1", "Mix")

    @Test
    fun should_roundTripPayload_and_ignoreJunk() {
        val payload = OfflinePayload(profileId = "p1", songId = "subsonic:tr-1", title = "Song", quality = "192")
            .withCollection(album)
        val decoded = OfflinePayload.decode(payload.encode())
        assertEquals(payload, decoded)
        assertEquals(DownloadQuality.Kbps192, decoded!!.downloadQuality)
        assertNull(OfflinePayload.decode(null))
        assertNull(OfflinePayload.decode("not json".encodeToByteArray()))
    }

    @Test
    fun should_keepSong_until_lastCollectionGoes() {
        val payload = OfflinePayload(profileId = "p1", songId = "subsonic:tr-1")
            .withCollection(album)
            .withCollection(playlist)
            .withCollection(album.copy(title = "Renamed"))
        assertEquals(2, payload.collections.size)
        assertEquals("Renamed", payload.collections.first { it.key == album.key }.title)
        val rest = payload.withoutCollection(album.key)
        assertEquals(listOf(playlist), rest!!.collections)
        assertNull(rest.withoutCollection(playlist.key))
    }

    private fun song(id: String, state: OfflineSong.State, vararg collections: OfflineCollection, bytes: Long = 10) =
        OfflineSong(
            key = "offline:$id",
            payload = collections.fold(OfflinePayload(profileId = "p1", songId = "subsonic:$id")) { p, c -> p.withCollection(c) },
            state = state,
            percent = 0f,
            bytes = bytes,
        )

    @Test
    fun should_readDownloadsAsCollections_newestFirst() {
        val library = OfflineLibrary(
            listOf(
                song("tr-1", OfflineSong.State.Done, album),
                song("tr-2", OfflineSong.State.Downloading, album, playlist),
                song("tr-3", OfflineSong.State.Done, playlist),
            ),
        )
        assertEquals(listOf(playlist.key, album.key), library.entries.map { it.collection.key })
        assertEquals(OfflineStatus.Downloading(1, 2), library.statusOf(album.key))
        assertEquals(OfflineStatus.None, library.statusOf("album:none"))
        assertTrue(library.isDownloaded("subsonic:tr-1"))
        assertFalse(library.isDownloaded("subsonic:tr-2"))
        assertEquals(30L, library.bytes)
        assertEquals(20L, library.entries.first { it.collection.key == album.key }.bytes)
    }

    @Test
    fun should_sayDownloadedOrFailed_when_everySongEnded() {
        val done = OfflineLibrary(listOf(song("tr-1", OfflineSong.State.Done, album), song("tr-2", OfflineSong.State.Done, album)))
        assertEquals(OfflineStatus.Downloaded, done.statusOf(album.key))
        val failed = OfflineLibrary(listOf(song("tr-1", OfflineSong.State.Done, album), song("tr-2", OfflineSong.State.Failed, album)))
        assertEquals(OfflineStatus.Failed, failed.statusOf(album.key))
    }
}
