package com.gpo.yoin.data.sync

import com.gpo.yoin.data.sync.backup.ReadableAlbum
import com.gpo.yoin.data.sync.backup.ReadableNames
import com.gpo.yoin.data.sync.backup.ReadableTrack
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudDataViewsTest {

    private fun record(kind: String, scope: String, key: String, ts: Long = 1_000L, deleted: Boolean = false, payload: JsonObject?) =
        WireRecord(kind = kind, scope = scope, key = key, ts = ts, device = "d1", deleted = deleted, payload = payload)

    private fun note(scope: String, id: String, track: String, title: String = "Andromeda", updatedAt: Long = 10L) = record(
        SyncKinds.SONG_NOTE,
        scope,
        id,
        payload = buildJsonObject {
            put("provider", "subsonic")
            put("trackId", track)
            put("content", "note $id")
            put("createdAt", 1L)
            put("updatedAt", updatedAt)
            put("title", title)
            put("artist", "Weyes Blood")
            put("positionMs", 72_000L)
        },
    )

    private fun trackRating(scope: String, track: String, rating: Double) = record(
        SyncKinds.TRACK_RATING,
        scope,
        "subsonic${SyncFormat.KEY_SEPARATOR}$track",
        payload = buildJsonObject {
            put("provider", "subsonic")
            put("songId", track)
            put("rating", rating)
        },
    )

    private fun albumRating(scope: String, album: String, rating: Double, ts: Long = 5L) = record(
        SyncKinds.ALBUM_RATING,
        scope,
        SyncFormat.compositeKey("subsonic", album),
        ts = ts,
        payload = buildJsonObject { put("rating", rating) },
    )

    private fun albumReview(scope: String, album: String, text: String, ts: Long = 6L) = record(
        SyncKinds.ALBUM_REVIEW,
        scope,
        SyncFormat.compositeKey("subsonic", album),
        ts = ts,
        payload = buildJsonObject { put("review", text) },
    )

    @Test
    fun should_countEachAccountAndTheGlobalData_when_summarized() {
        val records = listOf(
            note("s-a", "n1", "t1"),
            note("s-a", "n2", "t2"),
            note("s-a", "n3", "t3", updatedAt = 3).copy(deleted = true, payload = null),
            trackRating("s-a", "t1", 8.0),
            albumRating("s-a", "al1", 9.0),
            albumReview("s-a", "al1", "great"),
            albumReview("s-a", "al2", "fine"),
            note("s-b", "n9", "t9"),
            record(SyncKinds.LYRICS_TRANSLATION, SyncFormat.GLOBAL_SCOPE, "x", payload = buildJsonObject { put("trackProvider", "subsonic") }),
            record(SyncKinds.SONG_ABOUT, SyncFormat.GLOBAL_SCOPE, "y", payload = buildJsonObject { put("titleDisplay", "A") }),
        )

        val summary = CloudDataViews.summary(records)

        val a = summary.scopes.getValue("s-a")
        assertEquals(2, a.notes)
        // An album with both a rating and a review counts once.
        assertEquals(2, a.albums)
        assertEquals(1, a.songRatings)
        assertEquals(1, summary.scopes.getValue("s-b").notes)
        assertEquals(CloudGlobalCounts(playlistBackups = 0, translations = 1, songAbout = 1), summary.global)
    }

    @Test
    fun should_nameSongsFromNotesThenPlays_when_browsingAnAccount() = runTest {
        val names = object : ReadableNames {
            override suspend fun album(localProfileId: String?, provider: String, albumId: String) =
                if (albumId == "al1") ReadableAlbum("Titanic Rising", "Weyes Blood") else null

            override suspend fun track(localProfileId: String?, provider: String, trackId: String) =
                if (trackId == "t5") ReadableTrack("Movies", "Weyes Blood", null) else null
        }
        val records = listOf(
            note("s-a", "n1", "t1", title = "Andromeda", updatedAt = 10),
            note("s-a", "n2", "t2", title = "Everyday", updatedAt = 20),
            trackRating("s-a", "t1", 9.0),
            trackRating("s-a", "t5", 8.5),
            trackRating("s-a", "t6", 7.0),
            albumRating("s-a", "al1", 9.0),
            albumReview("s-a", "al2", "only a review"),
        )

        val content = CloudDataViews.account(records, "s-a", names, localProfileId = "p1")

        assertEquals(listOf("Everyday", "Andromeda"), content.notes.map { it.title })
        assertEquals(72_000L, content.notes.first().positionMs)
        val songs = content.songRatings.associateBy { it.trackId }
        assertEquals("Andromeda", songs.getValue("t1").title)
        assertEquals("Movies", songs.getValue("t5").title)
        assertNull(songs.getValue("t6").title)
        val albums = content.albums.associateBy { it.albumId }
        assertEquals("Titanic Rising", albums.getValue("al1").name)
        assertEquals(9.0f, albums.getValue("al1").rating)
        assertNull(albums.getValue("al1").review)
        assertNull(albums.getValue("al2").rating)
        assertEquals("only a review", albums.getValue("al2").review)
    }

    @Test
    fun should_listBackupsTranslationsAndAbout_when_browsingTheGlobalData() {
        val records = listOf(
            note("s-a", "n1", "t1", title = "Andromeda"),
            record(
                SyncKinds.LYRICS_TRANSLATION,
                SyncFormat.GLOBAL_SCOPE,
                "k1",
                payload = buildJsonObject {
                    put("trackProvider", "subsonic")
                    put("trackRawId", "t1")
                    put("targetLanguage", "zh-Hans")
                },
            ),
            record(
                SyncKinds.LYRICS_TRANSLATION,
                SyncFormat.GLOBAL_SCOPE,
                "k2",
                payload = buildJsonObject {
                    put("trackProvider", "subsonic")
                    put("trackRawId", "t1")
                    put("targetLanguage", "zh-Hans")
                },
            ),
            record(
                SyncKinds.SONG_ABOUT,
                SyncFormat.GLOBAL_SCOPE,
                "a1",
                payload = buildJsonObject {
                    put("titleDisplay", "Andromeda")
                    put("artistDisplay", "Weyes Blood")
                    put("titleText", "What is it about?")
                    put("answerText", "Love as a far galaxy.")
                    put("updatedAt", 9L)
                },
            ),
            record(
                SyncKinds.PLAYLIST_BACKUP,
                SyncFormat.GLOBAL_SCOPE,
                "b1",
                payload = buildJsonObject {
                    put("name", "Night drive")
                    put("sourceProvider", "spotify")
                    put("sourceProfileLabel", "Mika")
                    put("lastBackedUpAt", 42L)
                    put("content", """{"tracks":[{"title":"A","sourceId":"spotify:1"},{"title":"B","sourceId":"spotify:2"}]}""")
                },
            ),
        )

        val global = CloudDataViews.global(records)

        assertEquals(1, global.translations.size)
        assertEquals("Andromeda", global.translations.single().title)
        assertEquals("What is it about?", global.about.single().question)
        val backup = global.playlistBackups.single()
        assertEquals("Night drive", backup.name)
        assertEquals(2, backup.trackCount)
        assertEquals(42L, backup.backedUpAt)
    }

    @Test
    fun should_groupFilesByDevice_withThisDeviceFirst() {
        fun file(id: String, device: String, kind: String, size: Long, modified: Long) = RemoteFile(
            id = id,
            name = "yoin-$kind-$device",
            version = 1,
            modifiedTime = modified,
            size = size,
            appProperties = mapOf(SyncFileProps.KIND to kind, SyncFileProps.DEVICE_ID to device),
        )
        val files = listOf(
            file("1", "peer", SyncFileProps.KIND_ARTIFACTS, 300, 50),
            file("2", "peer", SyncFileProps.KIND_STATE, 200, 60),
            file("3", "me", SyncFileProps.KIND_STATE, 100, 10),
            RemoteFile("4", "stray.txt", 1, null, 5, emptyMap()),
        )

        val devices = CloudDataViews.deviceFiles(files, "me", "Pixel Tablet", mapOf("peer" to "iPad Air"), "Unknown")

        assertEquals(listOf("Pixel Tablet", "iPad Air"), devices.map { it.deviceName })
        assertTrue(devices.first().isThisDevice)
        val peer = devices[1]
        assertEquals(listOf(SyncFileProps.KIND_STATE, SyncFileProps.KIND_ARTIFACTS), peer.files.map { it.kind })
        assertEquals(500L, peer.totalBytes)
        assertEquals(60L, peer.lastSyncAt)
    }
}
