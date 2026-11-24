package com.gpo.yoin.shared.portability

import com.gpo.yoin.shared.sync.SyncEnvelope
import com.gpo.yoin.shared.sync.engine.SyncJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ReadableExportTest {
    @Test
    fun should_keepNamesSafeOnEveryFileSystem() {
        assertEquals("a_b_c_d", ReadableExport.sanitize("a/b:c*d"))
        assertEquals("_", ReadableExport.sanitize("  ...  "))
        assertEquals("Bad_Name_ ______", ReadableExport.sanitize("  .Bad/Name: *?\"<>| "))
        assertEquals(80, ReadableExport.sanitize("x".repeat(200)).length)
    }

    @Test
    fun should_writeScoresLikeJavasFormatter() {
        assertEquals("8", ReadableExport.score(8f))
        assertEquals("7.5", ReadableExport.score(7.5f))
        assertEquals("7.3", ReadableExport.score(7.25f))
        assertEquals("7.3", ReadableExport.score(7.35f))
        assertEquals("0.1", ReadableExport.score(0.05f))
        assertEquals("1:01", ReadableExport.clock(61_000))
        assertEquals("62:03", ReadableExport.clock(3_723_000))
    }

    @Test
    fun should_renderAnAlbumPageWithItsSongs_when_namesAreKnown() = runTest {
        val envelope = SyncJson.decodeFromString(
            SyncEnvelope.serializer(),
            """{"deviceId":"d","deviceName":"P","fileClass":"backup","writtenAt":1,"appVersionCode":1,"records":[
            {"k":"album_rating","s":"s-a","id":"subsonic\u001fal1","ts":5,"d":"d","v":{"rating":8.0}},
            {"k":"song_note","s":"s-a","id":"n1","ts":1,"d":"d","v":{"trackId":"t1","provider":"subsonic","content":"hi\nthere","createdAt":0,"updatedAt":0,"title":"One","artist":"A","positionMs":61000}},
            {"k":"track_album_link","s":"s-a","id":"subsonic\u001ft1","ts":1,"d":"d","v":{"provider":"subsonic","trackId":"t1","albumId":"al1","source":"detail","updatedAt":1}}
            ]}""",
        )
        val account = BackupAccount(scope = "s-a", provider = "subsonic", displayName = "Alice", onDevice = true)
        val names = object : ReadableNames {
            override suspend fun album(localProfileId: String?, provider: String, albumId: String) =
                ReadableAlbum("Rain", "Band").takeIf { localProfileId == "p1" && albumId == "al1" }

            override suspend fun track(localProfileId: String?, provider: String, trackId: String): ReadableTrack? = null
        }

        val (files, accounts) = ReadableExport.render(
            listOf(account),
            mapOf("s-a" to "p1"),
            envelope.records,
            names,
            ReadableLabels.English,
            "UTC",
        )

        assertEquals("readable/Alice/", accounts.single().readableDir)
        val page = files.single()
        assertEquals("readable/Alice/Band - Rain.md", page.path)
        assertEquals(
            "# Rain\nBand\n\n- Account: Alice (subsonic)\n- Rating: 8 / 10\n\n## Songs\n\n### One — A\n\n" +
                "- Rating: not rated\n- Notes:\n  - 1970-01-01 00:00 · 1:01\n    > hi\n    > there\n",
            page.text,
        )
        assertTrue(ReadableLabels.forLanguage("zh-Hans-CN") == ReadableLabels.Chinese)
        assertTrue(ReadableLabels.forLanguage("en") == ReadableLabels.English)
    }
}
