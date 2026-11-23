package com.gpo.yoin.lyra.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.profile.InMemoryProfileCredentialsStore
import com.gpo.yoin.data.profile.PlaintextProfileCredentialsCodec
import com.gpo.yoin.data.profile.ProfileActiveIdStore
import com.gpo.yoin.data.profile.ProfileCredentials
import com.gpo.yoin.data.profile.ProfileManager
import com.gpo.yoin.data.sync.CloudSyncManager
import com.gpo.yoin.data.sync.CloudSyncPhase
import com.gpo.yoin.data.sync.SyncBackendOverride
import com.gpo.yoin.data.sync.SyncFileProps
import com.gpo.yoin.data.sync.TurnOnStep
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * End-to-end tests running the real [CloudSyncManager] + [SyncBackendOverride] over
 * [LyraSyncTransport] and a fake `/api/v1/sync` [MockWebServer]:
 * - Full sync cycle & scope purge (`scope` query param round-trip)
 * - Reset (`deleteCloudData`)
 * - Account mismatch detection
 * - Pinned profile deletion turns sync off cleanly
 * - Zero "Google" wording leaks in Lyra error states (including HTTP 413)
 */
@RunWith(RobolectricTestRunner::class)
class LyraCloudSyncIntegrationTest {
    private val json = Json { encodeDefaults = true }
    private lateinit var server: MockWebServer
    private lateinit var fakeApi: FakeLyraSyncServer
    private lateinit var db: YoinDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var profileManager: ProfileManager
    private lateinit var manager: CloudSyncManager
    private lateinit var credentialsProvider: LyraCredentialsProvider
    private val clock = AtomicLong(1_700_000_000_000L)

    @Before
    fun setUp() {
        fakeApi = FakeLyraSyncServer(json)
        server = MockWebServer().apply {
            dispatcher = fakeApi
            start()
        }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, YoinDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        profileManager = ProfileManager(
            profileDao = db.profileDao(),
            activeIdStore = object : ProfileActiveIdStore {
                private var value: String? = null
                override fun read(): String? = value
                override fun write(id: String?) { value = id }
            },
            credentialsStore = InMemoryProfileCredentialsStore(),
            legacyCodec = PlaintextProfileCredentialsCodec(),
            scope = scope,
        )
        credentialsProvider = LyraCredentialsProvider(
            profileManager = profileManager,
            adminTokenStore = LyraAdminTokenStore(context),
        )
        val syncHttp = LyraSyncTransport.defaultHttpClient()
        manager = CloudSyncManager(
            context = context,
            database = db,
            profileManager = profileManager,
            versionName = "test",
            versionCode = 1,
            spotifyHttpClient = { OkHttpClient() },
            clock = { clock.addAndGet(1_000L) },
            backendOverride = SyncBackendOverride(
                transport = LyraSyncTransport(authProvider = credentialsProvider::currentAuth, http = syncHttp),
                authorizer = LyraSyncAuthorizer(authProvider = credentialsProvider::currentAuth),
                skipPlayServicesCheck = true,
                backendName = "Lyra",
                resolveTransport = { pinnedId ->
                    LyraSyncTransport.pinned(credentialsProvider.authForProfile(pinnedId), http = syncHttp)
                },
                profileIdFromToken = LyraSyncAuthorizer::profileIdFromToken,
            ),
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        server.shutdown()
    }

    private suspend fun addLyraProfile(name: String = "My Lyra", url: String = server.url("/").toString()): String {
        val created = profileManager.create(name, ProfileCredentials.Subsonic(url, "51", "secret"))
        profileManager.setActive(created.id)
        return created.id
    }

    @Test
    fun turnOnSyncPurgeResetAndProfileDeleteWorkEndToEndWithoutGoogleStrings() = runBlocking {
        val profileId = addLyraProfile()
        db.songNoteDao().insert(
            SongNote(
                id = "n-1",
                profileId = profileId,
                trackId = "track-1",
                provider = "subsonic",
                content = "Live at Budokan",
                createdAt = clock.addAndGet(1_000L),
                updatedAt = clock.addAndGet(1_000L),
                title = "Track 1",
                artist = "Artist 1",
            ),
        )

        val step = manager.beginTurnOn()
        assertEquals(TurnOnStep.Started, step)
        manager.syncNowAndWait()
        assertEquals(CloudSyncPhase.UpToDate, manager.state.value.phase)
        assertTrue(fakeApi.files.values.any { it.appProperties[SyncFileProps.KIND] == SyncFileProps.KIND_STATE })

        // Scope purge sends SyncFileProps.SCOPE on POST /api/v1/sync/files/{id}
        val syncProfileId = manager.state.value.accounts.first().syncProfileId!!
        val purgeResult = manager.removeAccountFromCloud(syncProfileId)
        assertTrue(purgeResult.isSuccess)
        val purgeFile = fakeApi.files.values.firstOrNull { it.appProperties[SyncFileProps.KIND] == SyncFileProps.KIND_PURGE }
        assertEquals(syncProfileId, purgeFile?.appProperties?.get(SyncFileProps.SCOPE))

        // Account mismatch when server vault permissionId changes
        fakeApi.permissionId = "lyra:vault-other"
        manager.syncNowAndWait()
        assertEquals(CloudSyncPhase.NeedsReauth, manager.state.value.phase)

        // Restore identity & test HTTP 413 error message contains "Lyra", never "Google"
        fakeApi.permissionId = "lyra:vault-01"
        fakeApi.forceListStatus = 413
        manager.syncNowAndWait()
        val phase413 = manager.state.value.phase as? CloudSyncPhase.Error
        assertTrue("Expected Error phase for 413, got ${manager.state.value.phase}", phase413 != null)
        assertTrue(phase413!!.message.contains("Lyra", ignoreCase = true))
        assertFalse(phase413.message.contains("Google", ignoreCase = true))

        // Reset (deleteCloudData) succeeds and turns sync off
        fakeApi.forceListStatus = null
        val resetResult = manager.deleteCloudData()
        assertTrue(resetResult.isSuccess)
        assertFalse(manager.state.value.enabled)
        assertTrue(fakeApi.files.values.any { it.appProperties[SyncFileProps.KIND] == SyncFileProps.KIND_RESET })

        // Turn back on, then verify deleting the pinned profile turns sync off cleanly
        assertEquals(TurnOnStep.Started, manager.beginTurnOn())
        manager.syncNowAndWait()
        assertTrue(manager.state.value.enabled)
        manager.onProfileDeleted(profileId)
        assertFalse(manager.state.value.enabled)
        assertEquals(CloudSyncPhase.Off, manager.state.value.phase)
    }

    private class FakeLyraSyncServer(
        private val json: Json,
    ) : Dispatcher() {
        @Volatile var permissionId: String = "lyra:vault-01"
        @Volatile var forceListStatus: Int? = null
        private val nextId = AtomicInteger(1)
        val files = ConcurrentHashMap<String, StoredFile>()

        data class StoredFile(
            val id: String,
            val name: String,
            val version: Long,
            val bytes: ByteArray,
            val appProperties: Map<String, String>,
        ) {
            fun toDto() = LyraSyncFileDto(
                id = id,
                name = name,
                version = version,
                modifiedTime = 1_700_000_000_000L,
                size = bytes.size.toLong(),
                appProperties = appProperties,
            )
        }

        override fun dispatch(request: RecordedRequest): MockResponse {
            val url = request.requestUrl ?: return MockResponse().setResponseCode(400)
            // Fail loudly if any sync request ever leaks an Authorization header.
            if (request.getHeader("Authorization") != null) {
                return MockResponse().setResponseCode(401).setBody("""{"error":"unexpected bearer on sync"}""")
            }
            val path = url.encodedPath
            return when {
                request.method == "GET" && path == "/api/v1/sync/about" ->
                    MockResponse().setBody(
                        json.encodeToString(
                            LyraSyncAboutResponse(permissionId = permissionId, email = "51@lyra.local", displayName = "Lyra"),
                        ),
                    )
                request.method == "GET" && path == "/api/v1/sync/files" -> {
                    forceListStatus?.let { return MockResponse().setResponseCode(it) }
                    MockResponse().setBody(
                        json.encodeToString(LyraSyncFilesResponse(files = files.values.map { it.toDto() })),
                    )
                }
                request.method == "POST" && path == "/api/v1/sync/files/generate-ids" -> {
                    val count = url.queryParameter("count")?.toIntOrNull() ?: 1
                    val ids = List(count) { "lyf_${nextId.getAndIncrement()}" }
                    MockResponse().setBody(json.encodeToString(LyraSyncGeneratedIdsResponse(ids)))
                }
                path.startsWith("/api/v1/sync/files/") -> {
                    val id = path.removePrefix("/api/v1/sync/files/")
                    when (request.method) {
                        "GET" -> {
                            val stored = files[id] ?: return MockResponse().setResponseCode(404)
                            MockResponse().setBody(Buffer().write(stored.bytes))
                        }
                        "POST" -> {
                            if (files.containsKey(id)) return MockResponse().setResponseCode(409)
                            val props = buildMap {
                                for (name in url.queryParameterNames) {
                                    if (name !in setOf("name", "u", "t", "s", "v", "c", "f")) {
                                        url.queryParameter(name)?.let { put(name, it) }
                                    }
                                }
                            }
                            val stored = StoredFile(
                                id = id,
                                name = url.queryParameter("name").orEmpty(),
                                version = 1L,
                                bytes = request.body.readByteArray(),
                                appProperties = props,
                            )
                            files[id] = stored
                            MockResponse().setResponseCode(201).setBody(json.encodeToString(stored.toDto()))
                        }
                        "PUT" -> {
                            val prev = files[id] ?: return MockResponse().setResponseCode(404)
                            val updated = prev.copy(version = prev.version + 1, bytes = request.body.readByteArray())
                            files[id] = updated
                            MockResponse().setBody(json.encodeToString(updated.toDto()))
                        }
                        "DELETE" -> {
                            files.remove(id)
                            MockResponse().setResponseCode(204)
                        }
                        else -> MockResponse().setResponseCode(405)
                    }
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
    }
}
