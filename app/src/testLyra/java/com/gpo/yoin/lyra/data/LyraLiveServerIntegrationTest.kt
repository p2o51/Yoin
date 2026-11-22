package com.gpo.yoin.lyra.data

import com.gpo.yoin.data.sync.SyncEnvelope
import com.gpo.yoin.data.sync.SyncFileProps
import com.gpo.yoin.data.sync.SyncKinds
import com.gpo.yoin.data.sync.SyncTransportException
import com.gpo.yoin.data.sync.WireRecord
import com.gpo.yoin.data.sync.engine.SnapshotCodec
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in live end-to-end integration test against a running Lyra Go server (`claude/lyra-sync-api`).
 *
 * Skipped automatically unless `-DlyraLiveUrl=http://127.0.0.1:<port>` is passed to Gradle.
 */
class LyraLiveServerIntegrationTest {
    @Test
    fun endToEndAgainstLiveLyraServer() = runTest {
        val url = (System.getProperty("lyraLiveUrl") ?: System.getenv("LYRA_LIVE_URL"))?.trim().orEmpty()
        assumeTrue("Pass -DlyraLiveUrl=... or LYRA_LIVE_URL=... to run live Lyra integration test", url.isNotEmpty())

        val auth = LyraServerAuth(
            serverUrl = url,
            username = (System.getProperty("lyraLiveUser") ?: System.getenv("LYRA_LIVE_USER")).orEmpty(),
            password = (System.getProperty("lyraLivePass") ?: System.getenv("LYRA_LIVE_PASS")).orEmpty(),
            adminToken = (System.getProperty("lyraLiveToken") ?: System.getenv("LYRA_LIVE_TOKEN"))
                ?.takeIf { it.isNotBlank() },
        )
        val api = LyraApiClient()
        val transport = LyraSyncTransport(authProvider = { auth })

        // 1. Capabilities + native_sync advertised
        val caps = api.capabilities(auth)
        assertTrue("native_sync must be enabled", caps.isEnabled("native_sync"))
        assertTrue("protocol_version >= 2", caps.protocolVersion >= 2)

        // 2. Jobs endpoint works with v1Auth
        val jobs = api.listJobs(auth)
        assertTrue(jobs.isEmpty() || jobs.all { it.kind == "download" })

        // 3. Events ticket works with v1Auth
        val ticket = api.issueEventsTicket(auth)
        assertTrue(ticket.ticket.isNotBlank())

        // 4. Full SyncTransport 7-method round-trip with a real Yoin gzip SyncEnvelope
        val about = transport.about()
        assertTrue(about.permissionId.startsWith("lyra:"))

        val ids = transport.generateIds(2)
        assertEquals(2, ids.size)
        val fileId = ids.first()

        val envelope = SyncEnvelope(
            deviceId = "dev-android-it",
            deviceName = "Android Integration",
            fileClass = "state",
            shard = 0,
            writtenAt = 1_795_329_839_000L,
            appVersionCode = 30,
            records = listOf(
                WireRecord(
                    kind = SyncKinds.ALBUM_RATING,
                    scope = "s-a1b2c3d4e5f60718a1b2c3d4",
                    key = "la_test_album",
                    ts = 1_795_329_839_000L,
                    device = "dev-android-it",
                    payload = buildJsonObject { put("rating", 4.5) },
                ),
            ),
        )
        val gzipBytes = SnapshotCodec.encode(envelope)
        val created = transport.create(
            fileId = fileId,
            name = SyncFileProps.stateFileName("dev-android-it"),
            appProperties = mapOf(
                SyncFileProps.KIND to SyncFileProps.KIND_STATE,
                SyncFileProps.DEVICE_ID to "dev-android-it",
                SyncFileProps.SCHEMA to "1",
                SyncFileProps.EPOCH to "0",
            ),
            mimeType = "application/gzip",
            bytes = gzipBytes,
        )
        assertEquals(fileId, created.id)
        assertEquals(1L, created.version)

        // Duplicate create must return AlreadyExists (409)
        val dup = runCatching {
            transport.create(
                fileId = fileId,
                name = SyncFileProps.stateFileName("dev-android-it"),
                appProperties = mapOf(SyncFileProps.KIND to SyncFileProps.KIND_STATE),
                mimeType = "application/gzip",
                bytes = gzipBytes,
            )
        }.exceptionOrNull()
        assertTrue("Expected AlreadyExists, got $dup", dup is SyncTransportException.AlreadyExists)

        // List + download verify exact bytes
        val listed = transport.list()
        assertTrue(listed.any { it.id == fileId && it.version == 1L })
        assertArrayEquals(gzipBytes, transport.download(fileId))

        // Update bumps version to 2
        val updated = transport.update(fileId, gzipBytes)
        assertEquals(2L, updated?.version)

        // Delete removes file and subsequent download returns null
        assertTrue(transport.delete(fileId))
        assertNull(transport.download(fileId))
    }
}
