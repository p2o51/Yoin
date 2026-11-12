package com.gpo.yoin.ui.detail

import androidx.lifecycle.viewModelScope
import com.gpo.yoin.data.integration.neodb.NeoDBApi
import com.gpo.yoin.data.integration.neodb.NeoDbTokenStore
import com.gpo.yoin.data.integration.neodb.NeoDbVoicesService
import com.gpo.yoin.data.local.NeoDBConfig
import com.gpo.yoin.data.local.NeoDBConfigDao
import com.gpo.yoin.data.profile.CredentialsCipher
import com.gpo.yoin.data.profile.EncryptedBlob
import com.gpo.yoin.testutil.MainDispatcherRule
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The "别人怎么说" page's states, over the real service and parsing with NeoDB answered by an OkHttp
 * interceptor (no network). Signed out: no token file, so the service reads neodb.social anonymously.
 */
class NeoDbVoicesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val temp = TemporaryFolder()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** What NeoDB answers, by request. Null: HTTP 500. */
    private var answer: (Request) -> String? = { null }
    private val requests = CopyOnWriteArrayList<String>()

    private fun viewModel(albumName: String = "OK Computer", artist: String? = "Radiohead"): NeoDbVoicesViewModel {
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    requests += request.url.encodedPath + "?" + request.url.encodedQuery.orEmpty()
                    val body = answer(request)
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(if (body == null) 500 else 200)
                        .message(if (body == null) "Server Error" else "OK")
                        .body((body ?: "{}").toResponseBody("application/json".toMediaType()))
                        .build()
                },
            )
            .build()
        val service = NeoDbVoicesService(
            api = NeoDBApi(client = client, json = json),
            configDao = NoConfig,
            tokenStore = NeoDbTokenStore(cipher = NoCipher, storageDir = temp.root),
            client = client,
            json = json,
        )
        return NeoDbVoicesViewModel(service, albumName, artist)
    }

    /** Every coroutine the page started has ended (the reads hop to Dispatchers.IO, so this waits for real). */
    private fun NeoDbVoicesViewModel.awaitIdle() = runBlocking {
        withTimeout(10_000) { viewModelScope.coroutineContext.job.children.toList().joinAll() }
    }

    private fun neoDb(pages: Int = 1, search: String = SEARCH_HIT): (Request) -> String? = { request ->
        val path = request.url.encodedPath
        when {
            path.endsWith("/api/catalog/search") -> search
            path.contains("/posts/") -> posts(page = request.url.queryParameter("page")!!.toInt(), pages = pages)
            else -> null
        }
    }

    @Test
    fun should_showVoices_when_neoDbHasTheAlbum() {
        answer = neoDb()
        val viewModel = viewModel()
        viewModel.awaitIdle()

        val state = viewModel.uiState.value as NeoDbVoicesUiState.Content
        assertEquals("main", state.album.itemUuid)
        assertEquals(510, state.album.ratingCount)
        assertEquals(listOf("p1-a", "p1-b"), state.voices.map { it.id })
        assertEquals(2, state.count)
        assertTrue(state.endReached)
        assertFalse(state.loadingMore)
        // Signed out, the token never leaves and neodb.social is the instance read.
        assertTrue(requests.first().startsWith("/api/catalog/search?"))
    }

    @Test
    fun should_offerASearch_when_neoDbHasNoSuchAlbum() {
        answer = neoDb(search = """{"data":[{"uuid":"x","title":"Sound Museum","artist":["Towa Tei"]}]}""")
        val viewModel = viewModel(albumName = "Museum", artist = "OWIS")
        viewModel.awaitIdle()

        val state = viewModel.uiState.value as NeoDbVoicesUiState.NotFound
        assertEquals("https://neodb.social/search?c=music&q=Museum+OWIS", state.searchUrl)
        assertTrue(requests.none { "/posts/" in it })
    }

    @Test
    fun should_loadAgain_when_retryFollowsAFailure() {
        val viewModel = viewModel()
        viewModel.awaitIdle()
        assertEquals(NeoDbVoicesUiState.Failed, viewModel.uiState.value)

        answer = neoDb()
        viewModel.load()
        viewModel.awaitIdle()
        assertTrue(viewModel.uiState.value is NeoDbVoicesUiState.Content)
    }

    @Test
    fun should_appendTheNextPage_when_listNearsItsEnd() {
        answer = neoDb(pages = 2)
        val viewModel = viewModel()
        viewModel.awaitIdle()
        assertFalse((viewModel.uiState.value as NeoDbVoicesUiState.Content).endReached)

        viewModel.loadMore()
        viewModel.awaitIdle()

        val state = viewModel.uiState.value as NeoDbVoicesUiState.Content
        // p1-b comes again at the top of page 2 (someone posted in between): shown once.
        assertEquals(listOf("p1-a", "p1-b", "p2-a"), state.voices.map { it.id })
        assertTrue(state.endReached)
        assertFalse(state.loadingMore)

        val before = requests.size
        viewModel.loadMore()
        viewModel.awaitIdle()
        assertEquals(before, requests.size)
    }

    @Test
    fun should_endInARetry_when_theNextPageFails() {
        answer = neoDb(pages = 2)
        val viewModel = viewModel()
        viewModel.awaitIdle()

        answer = { null }
        viewModel.loadMore()
        viewModel.awaitIdle()
        val failed = viewModel.uiState.value as NeoDbVoicesUiState.Content
        assertTrue(failed.moreFailed)
        assertFalse(failed.loadingMore)
        assertEquals(2, failed.voices.size)

        answer = neoDb(pages = 2)
        viewModel.loadMore()
        viewModel.awaitIdle()
        val state = viewModel.uiState.value as NeoDbVoicesUiState.Content
        assertFalse(state.moreFailed)
        assertEquals(3, state.voices.size)
    }

    @Test
    fun should_keepTheNewLoad_when_anEarlierLoadIsCancelled() {
        // The first search hangs; Retry starts a second load, which finishes first.
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val searches = AtomicInteger()
        val good = neoDb()
        answer = { request ->
            val search = request.url.encodedPath.endsWith("/api/catalog/search")
            if (search && searches.getAndIncrement() == 0) {
                firstEntered.countDown()
                releaseFirst.await(10, TimeUnit.SECONDS)
                null
            } else {
                good(request)
            }
        }
        val viewModel = viewModel()
        assertTrue(firstEntered.await(10, TimeUnit.SECONDS))

        viewModel.load()
        runBlocking {
            withTimeout(10_000) {
                viewModel.viewModelScope.coroutineContext.job.children.toList().last().join()
            }
        }
        assertTrue(viewModel.uiState.value is NeoDbVoicesUiState.Content)

        // The cancelled first load comes back (with a failure, even): it must not replace what is shown.
        releaseFirst.countDown()
        viewModel.awaitIdle()
        assertTrue(viewModel.uiState.value is NeoDbVoicesUiState.Content)
    }

    private fun posts(page: Int, pages: Int): String {
        val ids = if (page == 1) listOf("p1-a", "p1-b") else listOf("p1-b", "p2-a")
        val data = ids.joinToString(",") { id ->
            """{"id":"$id","account":{"acct":"a@neodb.social","display_name":"A"},""" +
                """"ext_neodb":{"relatedWith":[{"type":"Comment","content":"words $id"}]}}"""
        }
        return """{"pages":$pages,"count":${if (pages == 1) 2 else 3},"data":[$data]}"""
    }

    private object NoConfig : NeoDBConfigDao {
        override fun observe(): Flow<NeoDBConfig?> = flowOf(null)
        override suspend fun get(): NeoDBConfig? = null
        override suspend fun upsert(config: NeoDBConfig) = Unit
        override suspend fun clear() = Unit
    }

    private object NoCipher : CredentialsCipher {
        override fun encrypt(plaintext: ByteArray): EncryptedBlob = error("signed out: nothing to encrypt")
        override fun decrypt(blob: EncryptedBlob): ByteArray = error("signed out: nothing to decrypt")
    }

    private companion object {
        /** Two editions of one album: the one most people rated stands for it. */
        const val SEARCH_HIT =
            """{"data":[{"uuid":"apple","title":"OK Computer","artist":["Radiohead"],"rating_count":4},""" +
                """{"uuid":"main","url":"/album/main","title":"OK Computer","artist":["Radiohead"],""" +
                """"rating":9.4,"rating_count":510,"rating_distribution":[0,1,3,20,76]}]}"""
    }
}
