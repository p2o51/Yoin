package com.gpo.yoin.data.remote.ai

import com.gpo.yoin.data.remote.GeminiException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** What Custom's two formats put on the wire, and what they make of the answer. */
class AiClientTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val server = MockWebServer()
    private val client = AiClient(OkHttpClient(), json)

    /**
     * The search services' addresses are fixed: this client sends every request to [server]
     * instead, keeping its path and query, so their calls and answers can be checked.
     */
    private val redirected = AiClient(
        OkHttpClient.Builder().addInterceptor { chain ->
            val original = chain.request()
            val local = server.url("/")
            val url = original.url.newBuilder().scheme(local.scheme).host(local.host).port(local.port).build()
            chain.proceed(original.newBuilder().url(url).header("X-Original-Host", original.url.host).build())
        }.build(),
        json,
    )

    private fun searchWith(service: AiSearchService, key: String = "s-key", query: String = "Lemon credits") =
        runBlocking {
            redirected.search(
                AiSettings(
                    AiProviderPrefs(
                        provider = AiProvider.Custom,
                        customKey = "sk",
                        customBaseUrl = "https://x/v1",
                        customModel = "m",
                        searchService = service,
                    ).withSearchKey(service, key),
                    "",
                ),
                query,
            )
        }

    @Before
    fun setup() = server.start()

    @After
    fun cleanup() = server.shutdown()

    private fun custom(format: AiApiFormat, thinking: AiThinking = AiThinking.Off) = AiSettings(
        AiProviderPrefs(
            provider = AiProvider.Custom,
            customFormat = format,
            customBaseUrl = server.url("/").toString(),
            customKey = "sk",
            customModel = "model-1",
            customThinking = thinking,
        ),
        geminiKey = "",
    )

    private fun generate(ai: AiSettings): String = runBlocking {
        client.generate(ai, prompt = "hello", temperature = 0.8f, useSearch = false)
    }

    private fun sentBody(): JsonObject = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

    private fun anthropicReply(text: String, stopReason: String = "end_turn") = MockResponse().setBody(
        """{"content":[{"type":"thinking","thinking":""},{"type":"text","text":"$text"}],""" +
            """"stop_reason":"$stopReason"}""",
    )

    @Test
    fun should_sendNoTemperatureAndNoThinking_when_anthropicThinkingIsOff() {
        server.enqueue(anthropicReply("hi"))

        assertEquals("hi", generate(custom(AiApiFormat.Anthropic)))

        val request = server.takeRequest()
        assertEquals("/v1/messages", request.path)
        assertEquals("sk", request.getHeader("x-api-key"))
        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("model-1", body.getValue("model").jsonPrimitive.content)
        assertEquals(16000, body.getValue("max_tokens").jsonPrimitive.int)
        assertFalse(body.containsKey("temperature"))
        assertFalse(body.containsKey("thinking"))
        assertFalse(body.containsKey("output_config"))
    }

    @Test
    fun should_askForAdaptiveThinkingAtTheLevel_when_anthropicThinkingIsOn() {
        server.enqueue(anthropicReply("hi"))

        generate(custom(AiApiFormat.Anthropic, AiThinking.Medium))

        val body = sentBody()
        assertEquals("adaptive", body.getValue("thinking").jsonObject.getValue("type").jsonPrimitive.content)
        assertFalse(body.getValue("thinking").jsonObject.containsKey("budget_tokens"))
        assertEquals("medium", body.getValue("output_config").jsonObject.getValue("effort").jsonPrimitive.content)
        assertFalse(body.containsKey("temperature"))
    }

    @Test
    fun should_retryWithAThinkingBudget_when_theModelRejectsAdaptiveThinking() {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"error":{"message":"thinking.type: adaptive is not supported on this model"}}"""),
        )
        server.enqueue(anthropicReply("hi"))

        assertEquals("hi", generate(custom(AiApiFormat.Anthropic, AiThinking.Low)))

        sentBody()
        val retry = sentBody()
        assertEquals("enabled", retry.getValue("thinking").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(1024, retry.getValue("thinking").jsonObject.getValue("budget_tokens").jsonPrimitive.int)
        assertFalse(retry.containsKey("output_config"))
        assertFalse(retry.containsKey("temperature"))
    }

    @Test
    fun should_reportTheErrorAsItIs_when_a400IsNotAboutThinking() {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody("""{"error":{"message":"Your credit balance is too low"}}"""),
        )

        try {
            generate(custom(AiApiFormat.Anthropic, AiThinking.High))
            fail("expected the 400 to surface")
        } catch (error: GeminiException) {
            assertEquals(400, error.httpCode)
            assertTrue(error.message.orEmpty().contains("credit balance"))
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun should_fail_when_theAnthropicReplyIsCutAtTheOutputLimit() {
        server.enqueue(anthropicReply("[L0]half", stopReason = "max_tokens"))

        try {
            generate(custom(AiApiFormat.Anthropic))
            fail("a cut-off reply must not come back as an answer")
        } catch (error: GeminiException) {
            assertTrue(error.message.orEmpty().contains("cut off"))
        }
    }

    @Test
    fun should_sendNoTemperature_when_customSpeaksOpenAi() {
        val reply = """{"choices":[{"message":{"content":"hi"}}]}"""
        server.enqueue(MockResponse().setBody(reply))
        server.enqueue(MockResponse().setBody(reply))

        assertEquals("hi", generate(custom(AiApiFormat.OpenAi)))
        generate(custom(AiApiFormat.OpenAi, AiThinking.High))

        val off = sentBody()
        assertFalse(off.containsKey("temperature"))
        assertFalse(off.containsKey("reasoning_effort"))
        val high = sentBody()
        assertFalse(high.containsKey("temperature"))
        assertEquals("high", high.getValue("reasoning_effort").jsonPrimitive.content)
    }

    @Test
    fun should_sendEachSearchServiceItsOwnKey() {
        val base = AiProviderPrefs(
            provider = AiProvider.Custom,
            customKey = "sk",
            customBaseUrl = "https://x/v1",
            customModel = "m",
            searchService = AiSearchService.DeepSeek,
            searchKeyDeepSeek = "ds-key",
        )
        // Switching the service leaves the DeepSeek key where it was: Exa has none of its own yet.
        val exa = AiSettings(base.copy(searchService = AiSearchService.Exa), "")
        assertEquals("", exa.searchApiKey)
        assertEquals(AiSettings.SearchKind.None, exa.searchKind)

        val both = base.copy(searchService = AiSearchService.Exa).withSearchKey(AiSearchService.Exa, "exa-key")
        assertEquals("exa-key", AiSettings(both, "").searchApiKey)
        assertEquals("ds-key", AiSettings(both.copy(searchService = AiSearchService.DeepSeek), "").searchApiKey)
        assertEquals("", AiSettings(both.copy(searchService = AiSearchService.Tavily), "").searchApiKey)
    }

    @Test
    fun should_readDoubaosWebResults_when_itAnswers() {
        val longPage = "x".repeat(AiClient.MAX_PAGE_CHARS + 500)
        server.enqueue(
            MockResponse().setBody(
                """{"ResponseMetadata":{"RequestId":"r1"},"Result":{"ResultCount":3,"WebResults":[""" +
                    """{"Title":"Lemon","Url":"https://a","Content":"$longPage","Summary":"s","SiteName":"A"},""" +
                    """{"Title":"Lemon 2","Url":"https://b","Content":"","Summary":"summary only"},""" +
                    """{"Title":"Empty","Url":"https://c","Content":"","Summary":""}]}}""",
            ),
        )

        val hits = searchWith(AiSearchService.Doubao, key = "db-key", query = "q".repeat(150))

        assertEquals(listOf("https://a", "https://b"), hits.map { it.url })
        assertEquals(AiClient.MAX_PAGE_CHARS, hits[0].text.length)
        assertEquals("summary only", hits[1].text)
        val request = server.takeRequest()
        assertEquals("open.feedcoopapi.com", request.getHeader("X-Original-Host"))
        assertEquals("/search_api/web_search", request.path)
        assertEquals("Bearer db-key", request.getHeader("Authorization"))
        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(100, body.getValue("Query").jsonPrimitive.content.length)
        assertEquals("web", body.getValue("SearchType").jsonPrimitive.content)
        assertEquals(AiClient.SEARCH_RESULTS, body.getValue("Count").jsonPrimitive.int)
        assertTrue(body.getValue("Filter").jsonObject.getValue("NeedContent").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun should_surfaceDoubaosMessageAndCode_when_itRefuses() {
        server.enqueue(
            MockResponse().setBody(
                """{"ResponseMetadata":{"RequestId":"r2","Error":{"CodeN":10400,""" +
                    """"Code":"InvalidApiKey","Message":"api key is invalid"}},"Result":null}""",
            ),
        )

        try {
            searchWith(AiSearchService.Doubao)
            fail("a refused search must not come back as no pages")
        } catch (error: GeminiException) {
            assertTrue(error.message.orEmpty().contains("api key is invalid"))
            assertTrue(error.message.orEmpty().contains("InvalidApiKey"))
        }
    }

    @Test
    fun should_surfaceDoubaosMessage_when_itRefusesWithAnHttpError() {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody(
                """{"ResponseMetadata":{"Error":{"CodeN":10401,"Code":"Unauthorized","Message":"no access"}}}""",
            ),
        )

        try {
            searchWith(AiSearchService.Doubao)
            fail("expected the 401 to surface")
        } catch (error: GeminiException) {
            assertEquals(401, error.httpCode)
            assertTrue(error.message.orEmpty().contains("no access (Unauthorized)"))
        }
    }

    @Test
    fun should_readBravesLlmContext_when_thePlanHasIt() {
        server.enqueue(
            MockResponse().setBody(
                """{"grounding":{"generic":[""" +
                    """{"url":"https://a","title":"Lemon","snippets":["Lyrics by Kenshi Yonezu","Produced by him"]},""" +
                    """{"url":"https://b","title":"No text","snippets":[]}]},"sources":{}}""",
            ),
        )

        val hits = searchWith(AiSearchService.Brave, key = "br-key", query = "Lemon credits")

        assertEquals(listOf(AiClient.SearchHit("Lemon", "https://a", "Lyrics by Kenshi Yonezu\nProduced by him")), hits)
        val request = server.takeRequest()
        assertEquals("api.search.brave.com", request.getHeader("X-Original-Host"))
        assertEquals("GET", request.method)
        assertEquals("br-key", request.getHeader("X-Subscription-Token"))
        val url = request.requestUrl!!
        assertEquals("/res/v1/llm/context", url.encodedPath)
        assertEquals("Lemon credits", url.queryParameter("q"))
        assertEquals(AiClient.SEARCH_RESULTS.toString(), url.queryParameter("count"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun should_fallBackToBravesWebSearch_when_theLlmContextIsNotOnThePlan() {
        server.enqueue(
            MockResponse().setResponseCode(422)
                .setBody("""{"type":"ErrorResponse","error":{"code":"OPTION_NOT_IN_PLAN","detail":"not in plan","status":422}}"""),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"type":"search","web":{"results":[""" +
                    """{"title":"Lemon","url":"https://a","description":"A 2018 single.","extra_snippets":["Written by Yonezu."]},""" +
                    """{"title":"Bare","url":"https://b","description":""}]}}""",
            ),
        )

        val hits = searchWith(AiSearchService.Brave)

        assertEquals(listOf(AiClient.SearchHit("Lemon", "https://a", "A 2018 single.\nWritten by Yonezu.")), hits)
        assertEquals("/res/v1/llm/context", server.takeRequest().requestUrl!!.encodedPath)
        val web = server.takeRequest().requestUrl!!
        assertEquals("/res/v1/web/search", web.encodedPath)
        assertEquals("true", web.queryParameter("extra_snippets"))
        assertEquals(AiClient.SEARCH_RESULTS.toString(), web.queryParameter("count"))
    }

    @Test
    fun should_notFallBack_when_braveRejectsTheKey() {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"type":"ErrorResponse","error":{"code":"SUBSCRIPTION_TOKEN_INVALID","detail":"bad token"}}"""),
        )

        try {
            searchWith(AiSearchService.Brave)
            fail("expected the 401 to surface")
        } catch (error: GeminiException) {
            assertEquals(401, error.httpCode)
            assertTrue(error.message.orEmpty().contains("bad token"))
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun should_keepEachNewSearchServicesKeyToItself() {
        val prefs = AiProviderPrefs(provider = AiProvider.Custom, searchService = AiSearchService.Brave)
            .withSearchKey(AiSearchService.Brave, "br")
            .withSearchKey(AiSearchService.Doubao, "db")

        assertEquals("br", AiSettings(prefs, "").searchApiKey)
        assertEquals("db", AiSettings(prefs.copy(searchService = AiSearchService.Doubao), "").searchApiKey)
        assertEquals("", AiSettings(prefs.copy(searchService = AiSearchService.Tavily), "").searchApiKey)
    }
}
