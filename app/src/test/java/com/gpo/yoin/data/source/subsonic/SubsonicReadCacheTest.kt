package com.gpo.yoin.data.source.subsonic

import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.remote.ServerCredentials
import com.gpo.yoin.data.remote.SubsonicApiFactory
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [SubsonicReadCache] alone and through [SubsonicMusicSource] (perf review A's failing cases included). */
class SubsonicReadCacheTest {

    private val server = MockWebServer()

    @After
    fun tearDown() = server.shutdown()

    private fun source(): SubsonicMusicSource = SubsonicMusicSource(
        credentials = ServerCredentials(server.url("/").toString().trimEnd('/'), "u", "p"),
        apiFactory = { p -> SubsonicApiFactory.create(credentialsProvider = p) },
    )

    @Test
    fun concurrentIdenticalReadsShareOneRequest() = runBlocking {
        val requests = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.incrementAndGet()
                return MockResponse().setBodyDelay(200, TimeUnit.MILLISECONDS).setBody(
                    """{"subsonic-response":{"status":"ok","version":"1.16.1","playlists":{"playlist":[{"id":"p1","name":"One","owner":"u","songCount":1,"duration":100}]}}}""",
                )
            }
        }
        server.start()
        val source = source()
        val results = List(3) { async { source.library().getPlaylists() } }.awaitAll()
        assertEquals(1, requests.get())
        assertTrue(results.all { it.single().name == "One" })
        // Within the TTL: answered from memory.
        source.library().getPlaylists()
        assertEquals(1, requests.get())
    }

    /** Review A #1: prime's 8 s timeout (or a cleared viewModelScope) must not cancel Library's read. */
    @Test
    fun cancellingTheFirstCallerDoesNotFailTheOthers() = runBlocking {
        val cache = SubsonicReadCache(ttlMs = 20_000, clock = System::currentTimeMillis)
        val gate = CompletableDeferred<Unit>()
        val leader = launch { cache.getOrFetch<String>("artists") { gate.await(); "fresh" } }
        delay(50)
        val follower = async { runCatching { cache.getOrFetch("artists") { "follower" } } }
        delay(50)
        leader.cancel()
        gate.complete(Unit)
        val result = withTimeout(5_000) { follower.await() }
        assertTrue("follower inherited the leader's cancellation: $result", result.isSuccess)
        assertEquals("fresh", result.getOrNull())
        // And the shared read was kept for the next caller.
        assertEquals("fresh", cache.getOrFetch("artists") { "refetched" })
    }

    /** Review A #2: a star confirmed while getStarred is in flight must show on the next read. */
    @Test
    fun aWriteDuringAnInFlightReadIsNotHiddenByIt() = runBlocking {
        val starred = AtomicBoolean(false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.contains("/rest/star") -> {
                        starred.set(true)
                        MockResponse().setBody("""{"subsonic-response":{"status":"ok","version":"1.16.1"}}""")
                    }
                    path.contains("getStarred2") -> {
                        val songs = if (starred.get()) {
                            """{"id":"s1","title":"Song","album":"A","albumId":"al1","artist":"B","artistId":"ar1","coverArt":"c","duration":200}"""
                        } else {
                            ""
                        }
                        MockResponse().setBodyDelay(400, TimeUnit.MILLISECONDS)
                            .setBody("""{"subsonic-response":{"status":"ok","version":"1.16.1","starred2":{"song":[$songs]}}}""")
                    }
                    else -> MockResponse().setBody("""{"subsonic-response":{"status":"ok","version":"1.16.1"}}""")
                }
            }
        }
        server.start()
        val source = source()
        val inFlight = async { source.library().getStarred() }
        delay(100)
        source.writeActions().setFavorite(MediaId.subsonic("s1"), true).getOrThrow()
        // A caller arriving after the write, while the old read is still in flight.
        val joinedAfterWrite = async { source.library().getStarred() }
        inFlight.await()
        assertTrue("joined the pre-write read", joinedAfterWrite.await().tracks.any { it.id.rawId == "s1" })
        val after = source.library().getStarred()
        assertTrue("starred list stale after a confirmed star", after.tracks.any { it.id.rawId == "s1" })
    }

    /**
     * Measured on the tablet: Library's All starts its reads together on Main.immediate and
     * publishes each as it lands. A hit that returned without suspending ran its publish before
     * the sibling's network read was even sent (covers settled ~100 ms later).
     */
    @Test
    fun aCacheHitLetsSiblingReadsStartFirst() = runBlocking {
        val cache = SubsonicReadCache(ttlMs = 20_000, clock = System::currentTimeMillis)
        cache.getOrFetch("artists") { "cached" }
        val order = mutableListOf<String>()
        coroutineScope {
            async(start = CoroutineStart.UNDISPATCHED) {
                cache.getOrFetch("artists") { "refetched" }
                order += "hit consumed"
            }
            async(start = CoroutineStart.UNDISPATCHED) { order += "sibling read started" }
        }
        assertEquals(listOf("sibling read started", "hit consumed"), order)
    }

    /** PR #331 review: a pull-to-refresh within the TTL returned the cached lists without asking. */
    @Test
    fun invalidateReadCacheMakesEveryListAskTheServerAgain() = runBlocking {
        val requests = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.incrementAndGet()
                return MockResponse().setBody(
                    """{"subsonic-response":{"status":"ok","version":"1.16.1","playlists":{"playlist":[]},"starred2":{},"artists":{"index":[]},"albumList2":{"album":[]}}}""",
                )
            }
        }
        server.start()
        val source = source()
        suspend fun readAll() {
            source.library().getPlaylists()
            source.library().getStarred()
            source.library().getArtists()
            source.library().getAlbumList("newest", 500, 0)
        }
        readAll()
        readAll()
        assertEquals("second pass served from the cache", 4, requests.get())
        source.invalidateReadCache()
        readAll()
        assertEquals("a refresh asks the server for every list", 8, requests.get())
    }

    /** ProfileManager disposes a source on switch/delete: its reads in flight stop, later reads go uncached. */
    @Test
    fun disposeCancelsReadsInFlight_andLaterReadsAskTheServer() = runBlocking {
        val cache = SubsonicReadCache(ttlMs = 20_000, clock = System::currentTimeMillis)
        val gate = CompletableDeferred<Unit>()
        val inFlight = async { runCatching { cache.getOrFetch<String>("artists") { gate.await(); "late" } } }
        delay(50)
        cache.close()
        val result = withTimeout(5_000) { inFlight.await() }
        assertTrue("read in flight not stopped: $result", result.exceptionOrNull() is IOException)
        val calls = AtomicInteger()
        cache.getOrFetch("artists") { calls.incrementAndGet(); "a" }
        cache.getOrFetch("artists") { calls.incrementAndGet(); "b" }
        assertEquals("uncached after dispose", 2, calls.get())
    }

    @Test
    fun aDisposedSourceNoLongerKeepsReads() = runBlocking {
        val requests = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.incrementAndGet()
                return MockResponse().setBody("""{"subsonic-response":{"status":"ok","version":"1.16.1","playlists":{"playlist":[]}}}""")
            }
        }
        server.start()
        val source = source()
        source.dispose()
        source.library().getPlaylists()
        source.library().getPlaylists()
        assertEquals(2, requests.get())
    }

    @Test
    fun errorsAreNotKept() = runBlocking {
        val cache = SubsonicReadCache(ttlMs = 20_000, clock = System::currentTimeMillis)
        val calls = AtomicInteger()
        val first = runCatching { cache.getOrFetch<String>("playlists") { calls.incrementAndGet(); throw IOException("down") } }
        assertTrue(first.exceptionOrNull() is IOException)
        assertEquals("ok", cache.getOrFetch("playlists") { calls.incrementAndGet(); "ok" })
        assertEquals(2, calls.get())
    }

    @Test
    fun entriesExpireAfterTheTtl() = runBlocking {
        var now = 0L
        val cache = SubsonicReadCache(ttlMs = 1_000, clock = { now })
        assertEquals("a", cache.getOrFetch("k") { "a" })
        now = 999
        assertEquals("a", cache.getOrFetch("k") { "b" })
        now = 1_000
        assertEquals("c", cache.getOrFetch("k") { "c" })
    }

    @Test
    fun prefixInvalidationReachesStoredAndInFlightKeys() = runBlocking {
        val cache = SubsonicReadCache(ttlMs = 20_000, clock = System::currentTimeMillis)
        cache.getOrFetch("albumList:recent:20:0") { "old" }
        val gate = CompletableDeferred<Unit>()
        val inFlight = async { cache.getOrFetch("albumList:recent:40:0") { gate.await(); "old-in-flight" } }
        delay(50)
        cache.invalidatePrefix("albumList:recent:")
        gate.complete(Unit)
        inFlight.await()
        assertEquals("new", cache.getOrFetch("albumList:recent:20:0") { "new" })
        assertEquals("new", cache.getOrFetch("albumList:recent:40:0") { "new" })
    }

    @Test
    fun aPlaylistWriteRereadsThePlaylists() = runBlocking {
        val names = ArrayDeque(listOf("One", "Renamed"))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path.orEmpty().contains("getPlaylists")) {
                    val name = names.removeFirst()
                    MockResponse().setBody(
                        """{"subsonic-response":{"status":"ok","version":"1.16.1","playlists":{"playlist":[{"id":"p1","name":"$name","owner":"u","songCount":1,"duration":100}]}}}""",
                    )
                } else {
                    MockResponse().setBody("""{"subsonic-response":{"status":"ok","version":"1.16.1"}}""")
                }
        }
        server.start()
        val source = source()
        assertEquals("One", source.library().getPlaylists().single().name)
        source.writeActions().renamePlaylist(MediaId.subsonic("p1"), "Renamed", null).getOrThrow()
        assertEquals("Renamed", source.library().getPlaylists().single().name)
    }

    @Test
    fun randomAlbumListsAreNeverShared() = runBlocking {
        val requests = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.incrementAndGet()
                return MockResponse().setBody("""{"subsonic-response":{"status":"ok","version":"1.16.1","albumList2":{"album":[]}}}""")
            }
        }
        server.start()
        val source = source()
        source.library().getAlbumList("random", 10, 0)
        source.library().getAlbumList("random", 10, 0)
        assertEquals(2, requests.get())
    }
}
