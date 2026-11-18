import Foundation
import os
import XCTest
@testable import Yoin

private final class AiV2StubProtocol: URLProtocol {
    struct State: Sendable {
        var requests: [(request: URLRequest, body: Data)] = []
        var handler: (@Sendable (URLRequest, Data) -> (Int, Data))?
    }

    static let state = OSAllocatedUnfairLock(initialState: State())

    static func reset(handler: @escaping @Sendable (URLRequest, Data) -> (Int, Data)) {
        state.withLock {
            $0.requests = []
            $0.handler = handler
        }
    }

    static var requests: [(request: URLRequest, body: Data)] {
        state.withLock { $0.requests }
    }

    static var session: URLSession {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [AiV2StubProtocol.self]
        return URLSession(configuration: config)
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        var body = request.httpBody ?? Data()
        if body.isEmpty, let stream = request.httpBodyStream {
            stream.open()
            var buf = [UInt8](repeating: 0, count: 4096)
            while stream.hasBytesAvailable {
                let n = stream.read(&buf, maxLength: buf.count)
                if n <= 0 { break }
                body.append(buf, count: n)
            }
            stream.close()
        }
        let req = request
        let requestBody = body
        let handler = Self.state.withLock { st -> (@Sendable (URLRequest, Data) -> (Int, Data))? in
            st.requests.append((req, requestBody))
            return st.handler
        }
        let (status, responseData) = handler?(req, requestBody) ?? (500, Data())
        let response = HTTPURLResponse(
            url: req.url ?? URL(string: "https://example.com")!,
            statusCode: status,
            httpVersion: nil,
            headerFields: nil
        )!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: responseData)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

@MainActor
final class AiSettingsV2Tests: XCTestCase {
    private var defaults: UserDefaults!
    private var suiteName: String!
    private var geminiKeychain: GeminiKeychain!
    private var aiKeychain: AiKeychain!

    override func setUp() async throws {
        suiteName = "ai-v2-tests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)!
        let servicePrefix = "com.gpo.yoin.ai.tests.\(UUID().uuidString)"
        geminiKeychain = GeminiKeychain(service: "\(servicePrefix).gemini")
        aiKeychain = AiKeychain(service: servicePrefix)
    }

    override func tearDown() async throws {
        geminiKeychain.delete()
        aiKeychain.deleteAll()
        defaults.removePersistentDomain(forName: suiteName)
    }

    // MARK: - Models & Readiness

    func test_should_resolveTiersModelsSearchAndReadinessAcrossProviders() {
        XCTAssertEqual(AiProvider.gemini.tiers, [.good, .best])
        XCTAssertEqual(AiProvider.deepSeek.tiers, [.good, .best])
        XCTAssertEqual(AiProvider.qwen.tiers, [.good])
        XCTAssertTrue(AiProvider.custom.tiers.isEmpty)
        XCTAssertTrue(AiProvider.deepSeek.isDirectInChina)
        XCTAssertTrue(AiProvider.qwen.isDirectInChina)
        XCTAssertFalse(AiProvider.gemini.isDirectInChina)
        XCTAssertFalse(AiProvider.custom.isDirectInChina)

        var prefs = AiProviderPrefs(provider: .gemini, tier: .best)
        XCTAssertEqual(prefs.activeTier, .best)
        prefs.provider = .qwen
        XCTAssertEqual(prefs.activeTier, .good)
        prefs.provider = .gemini
        XCTAssertEqual(prefs.activeTier, .best, "Switching through Qwen must not overwrite saved Best tier")

        var ai = AiSettings(
            prefs: AiProviderPrefs(provider: .gemini, tier: .best),
            secrets: AiSecrets(deepSeekKey: "ds-secret", qwenKey: "qw-secret", customKey: "cu-secret", searchKeyExa: "exa-secret"),
            geminiKey: "gem-secret"
        )
        XCTAssertEqual(ai.modelId, "gemini-3.5-flash-lite")
        XCTAssertEqual(ai.searchKind, .builtIn)
        XCTAssertEqual(ai.searchQueries, 1)
        XCTAssertTrue(ai.isReady)

        ai.prefs.provider = .deepSeek
        ai.prefs.tier = .best
        XCTAssertEqual(ai.modelId, "deepseek-flash")
        XCTAssertEqual(ai.searchKind, .external)
        XCTAssertEqual(ai.searchService, .deepSeek)
        XCTAssertEqual(ai.searchApiKey, "ds-secret")
        XCTAssertEqual(ai.searchQueries, 2)

        ai.prefs.provider = .qwen
        XCTAssertEqual(ai.modelId, "qwen-flash")
        XCTAssertEqual(ai.searchKind, .builtIn)
        XCTAssertEqual(ai.searchQueries, 1)

        ai.prefs.provider = .custom
        ai.prefs.customBaseUrl = "https://example.com/v1"
        ai.prefs.customModel = ""
        ai.prefs.searchService = .exa
        XCTAssertFalse(ai.isReady, "Custom needs non-blank model")
        ai.prefs.customModel = "gpt-5"
        XCTAssertTrue(ai.isReady)
        XCTAssertEqual(ai.searchKind, .external)
        XCTAssertEqual(ai.searchApiKey, "exa-secret")

        ai.prefs.searchService = .tavily
        XCTAssertEqual(ai.searchKind, .none, "Tavily key is separate from Exa key")

        XCTAssertFalse(ai.secrets.description.contains("secret"))
        XCTAssertFalse(ai.secrets.debugDescription.contains("secret"))
    }

    func test_should_storePrefsInUserDefaultsAndNeverStoreKeysInUserDefaults() throws {
        let settings = GeminiSettings(keychain: geminiKeychain, defaults: defaults, aiKeychain: aiKeychain)
        settings.setProvider(.deepSeek)
        settings.setTier(.best)
        settings.setCustomFormat(.anthropic)
        settings.setCustomBaseUrl("https://proxy.example.com")
        settings.setCustomModel("claude-sonnet")
        settings.setCustomThinking(.medium)
        settings.setSearchService(.doubao)

        XCTAssertTrue(settings.saveKey("gm-secret-1", for: .gemini))
        XCTAssertTrue(settings.saveKey("ds-secret-2", for: .deepSeek))
        XCTAssertTrue(settings.saveKey("qw-secret-3", for: .qwen))
        XCTAssertTrue(settings.saveKey("cu-secret-4", for: .custom))
        XCTAssertTrue(settings.saveSearchKey("exa-secret-5", for: .exa))
        XCTAssertTrue(settings.saveSearchKey("db-secret-6", for: .doubao))

        // Verify zero secret strings appear anywhere in UserDefaults.
        let dump = String(describing: defaults.dictionaryRepresentation())
        for secret in ["gm-secret-1", "ds-secret-2", "qw-secret-3", "cu-secret-4", "exa-secret-5", "db-secret-6"] {
            XCTAssertFalse(dump.contains(secret), "UserDefaults must never hold key \(secret)")
        }

        let probe = aiKeychain.save("probe", for: .deepSeek)
        if probe == errSecSuccess {
            XCTAssertTrue(aiKeychain.save("ds-secret-2", for: .deepSeek) == errSecSuccess)
            let reloaded = GeminiSettings(keychain: geminiKeychain, defaults: defaults, aiKeychain: aiKeychain)
            XCTAssertEqual(reloaded.prefs.provider, .deepSeek)
            XCTAssertEqual(reloaded.prefs.tier, .best)
            XCTAssertEqual(reloaded.key(for: .gemini), "gm-secret-1")
            XCTAssertEqual(reloaded.key(for: .deepSeek), "ds-secret-2")
            XCTAssertEqual(reloaded.key(for: .qwen), "qw-secret-3")
            XCTAssertEqual(reloaded.key(for: .custom), "cu-secret-4")
            XCTAssertEqual(reloaded.searchKey(for: .exa), "exa-secret-5")
            XCTAssertEqual(reloaded.searchKey(for: .doubao), "db-secret-6")
        }
    }

    // MARK: - Provider Routing & Search

    func test_should_routeDeepSeekBestWithTwoSearchQueriesAndRunCanonicalAbout() async throws {
        AiV2StubProtocol.reset { req, _ in
            if req.url?.path == "/v1/tools/search" {
                let json = """
                {
                  "results": [
                    {"title": "Credits A", "url": "https://a.example", "content": "Written by Yoin"},
                    {"title": "Duplicate", "url": "https://a.example", "summary": "Ignored dup"}
                  ]
                }
                """
                return (200, Data(json.utf8))
            }
            let reply = """
            {"choices":[{"message":{"content":"[CREATION_TIME]2020[/CREATION_TIME][LYRICIST]Yoin[/LYRICIST]"}}]}
            """
            return (200, Data(reply.utf8))
        }

        let ai = AiSettings(
            prefs: AiProviderPrefs(provider: .deepSeek, tier: .best),
            secrets: AiSecrets(deepSeekKey: "ds-key")
        )
        let client = GeminiClient(session: AiV2StubProtocol.session, ai: ai)
        let run = try await client.runCanonicalAbout(
            ai: ai,
            title: "晴天",
            artist: "周杰伦",
            album: "叶惠美",
            targetLanguage: "Simplified Chinese"
        )

        let searchReqs = AiV2StubProtocol.requests.filter { $0.request.url?.path == "/v1/tools/search" }
        let chatReqs = AiV2StubProtocol.requests.filter { $0.request.url?.path == "/chat/completions" }
        XCTAssertEqual(searchReqs.count, 2)
        XCTAssertEqual(chatReqs.count, 1)
        XCTAssertEqual(chatReqs[0].request.value(forHTTPHeaderField: "Authorization"), "Bearer ds-key")

        let chatJson = try XCTUnwrap(JSONSerialization.jsonObject(with: chatReqs[0].body) as? [String: Any])
        XCTAssertEqual(chatJson["model"] as? String, "deepseek-flash")
        XCTAssertEqual((chatJson["thinking"] as? [String: String])?["type"], "disabled")
        let messages = try XCTUnwrap(chatJson["messages"] as? [[String: Any]])
        let prompt = try XCTUnwrap(messages.first?["content"] as? String)
        XCTAssertTrue(prompt.contains("Search results:\n[1] Credits A (https://a.example)\nWritten by Yoin"))

        XCTAssertEqual(run.searchKind, .external)
        XCTAssertEqual(run.searchQueries.count, 2)
        XCTAssertEqual(run.pagesRead, 1)
        XCTAssertEqual(run.values, [
            .init(key: "creation_time", answer: "2020"),
            .init(key: "lyricist", answer: "Yoin"),
        ])
    }

    func test_should_routeGeminiBestAndQwenAndCustomOpenAI() async throws {
        // 1. Gemini Best -> gemini-3.5-flash-lite
        AiV2StubProtocol.reset { _, _ in
            let data = try! JSONSerialization.data(withJSONObject: [
                "candidates": [["content": ["parts": [["text": "[REVIEW]Great.[/REVIEW]"]]]]]
            ])
            return (200, data)
        }
        let geminiBest = AiSettings(prefs: AiProviderPrefs(provider: .gemini, tier: .best), geminiKey: "gm-key")
        let geminiClient = GeminiClient(session: AiV2StubProtocol.session, ai: geminiBest)
        let values = try await geminiClient.canonicalAbout(apiKey: "", title: "Song", artist: "Artist", album: "Alb", targetLanguage: "English")
        XCTAssertEqual(values, [.init(key: "review", answer: "Great.")])
        XCTAssertTrue(AiV2StubProtocol.requests[0].request.url?.absoluteString.hasSuffix("gemini-3.5-flash-lite:generateContent") == true)

        // 2. Qwen -> forced search when useSearch = true, disabled for askTitle
        AiV2StubProtocol.reset { _, _ in
            (200, Data(#"{"choices":[{"message":{"content":"Heading"}}]}"#.utf8))
        }
        let qwen = AiSettings(prefs: AiProviderPrefs(provider: .qwen), secrets: AiSecrets(qwenKey: "qw-key"))
        let qwenClient = GeminiClient(session: AiV2StubProtocol.session, ai: qwen)
        _ = try await qwenClient.ask(apiKey: "", title: "S", artist: "A", album: "Al", question: "Why?", targetLanguage: "English")
        let qwenBody = try XCTUnwrap(JSONSerialization.jsonObject(with: AiV2StubProtocol.requests[0].body) as? [String: Any])
        XCTAssertEqual(AiV2StubProtocol.requests[0].request.url?.absoluteString, "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions")
        XCTAssertEqual(qwenBody["model"] as? String, "qwen-flash")
        XCTAssertEqual(qwenBody["enable_thinking"] as? Bool, false)
        XCTAssertEqual(qwenBody["enable_search"] as? Bool, true)
        XCTAssertEqual((qwenBody["search_options"] as? [String: Bool])?["forced_search"], true)

        // 3. Custom OpenAI -> omits temperature, sends reasoning_effort
        AiV2StubProtocol.reset { _, _ in
            (200, Data(#"{"choices":[{"message":{"content":"[L0]Hola[/L0]"}}]}"#.utf8))
        }
        let customOpenAI = AiSettings(
            prefs: AiProviderPrefs(
                provider: .custom,
                customFormat: .openAi,
                customBaseUrl: "https://custom.example/v1/",
                customModel: "o3-mini",
                customThinking: .high,
                searchService: .none
            ),
            secrets: AiSecrets(customKey: "cu-key")
        )
        let customClient = GeminiClient(session: AiV2StubProtocol.session, ai: customOpenAI)
        let translated = try await customClient.translateLyricLines(
            apiKey: "",
            title: "Song",
            artist: "Artist",
            lines: ["Hello"],
            targetLanguage: "Spanish"
        )
        XCTAssertEqual(translated, [0: "Hola"])
        let openAIBody = try XCTUnwrap(JSONSerialization.jsonObject(with: AiV2StubProtocol.requests[0].body) as? [String: Any])
        XCTAssertNil(openAIBody["temperature"], "Custom OpenAI must omit temperature")
        XCTAssertEqual(openAIBody["reasoning_effort"] as? String, "high")
        XCTAssertEqual(AiV2StubProtocol.requests[0].request.url?.absoluteString, "https://custom.example/v1/chat/completions")
    }

    func test_should_fallbackAnthropicThinkingOn400AndRejectMaxTokens() async throws {
        let count = OSAllocatedUnfairLock(initialState: 0)
        AiV2StubProtocol.reset { _, _ in
            let step = count.withLock { c -> Int in
                c += 1
                return c
            }
            if step == 1 {
                return (400, Data(#"{"error":{"message":"adaptive thinking is not supported for this model"}}"#.utf8))
            }
            if step == 2 {
                return (200, Data(#"{"stop_reason":"end_turn","content":[{"type":"text","text":"Short Heading"}]}"#.utf8))
            }
            return (200, Data(#"{"stop_reason":"max_tokens","content":[{"type":"text","text":"Cut off"}]}"#.utf8))
        }

        let anthropic = AiSettings(
            prefs: AiProviderPrefs(
                provider: .custom,
                customFormat: .anthropic,
                customBaseUrl: "https://anthropic.example",
                customModel: "claude-sonnet-4",
                customThinking: .medium,
                searchService: .none
            ),
            secrets: AiSecrets(customKey: "ant-key")
        )
        let client = GeminiClient(session: AiV2StubProtocol.session, ai: anthropic)

        let heading = try await client.askTitle(apiKey: "", title: "S", artist: "A", album: "Al", question: "Q?", targetLanguage: "English")
        XCTAssertEqual(heading, "Short Heading")
        XCTAssertEqual(AiV2StubProtocol.requests.count, 2)
        XCTAssertEqual(AiV2StubProtocol.requests[0].request.url?.absoluteString, "https://anthropic.example/v1/messages")
        XCTAssertEqual(AiV2StubProtocol.requests[0].request.value(forHTTPHeaderField: "x-api-key"), "ant-key")
        XCTAssertEqual(AiV2StubProtocol.requests[0].request.value(forHTTPHeaderField: "anthropic-version"), "2023-06-01")

        let firstBody = try XCTUnwrap(JSONSerialization.jsonObject(with: AiV2StubProtocol.requests[0].body) as? [String: Any])
        XCTAssertEqual((firstBody["thinking"] as? [String: Any])?["type"] as? String, "adaptive")
        XCTAssertEqual((firstBody["output_config"] as? [String: Any])?["effort"] as? String, "medium")
        XCTAssertNil(firstBody["temperature"])

        let secondBody = try XCTUnwrap(JSONSerialization.jsonObject(with: AiV2StubProtocol.requests[1].body) as? [String: Any])
        XCTAssertEqual((secondBody["thinking"] as? [String: Any])?["type"] as? String, "enabled")
        XCTAssertEqual((secondBody["thinking"] as? [String: Any])?["budget_tokens"] as? Int, 4_096)

        do {
            _ = try await client.askTitle(apiKey: "", title: "S", artist: "A", album: "Al", question: "Q?", targetLanguage: "English")
            XCTFail("Expected max_tokens cut-off failure")
        } catch let failure as GeminiClient.Failure {
            XCTAssertTrue(failure.message.contains("output limit"))
        }
    }

    func test_should_listCustomModelsForOpenAIAndAnthropic() async throws {
        AiV2StubProtocol.reset { req, _ in
            XCTAssertEqual(req.httpMethod, "GET")
            return (200, Data(#"{"data":[{"id":"z-model"},{"id":"a-model"},{"id":""}]}"#.utf8))
        }
        let client = GeminiClient(session: AiV2StubProtocol.session)

        let openAiModels = try await client.listCustomModels(format: .openAi, baseUrl: "https://api.example/v1/", apiKey: "k1")
        XCTAssertEqual(openAiModels, ["a-model", "z-model"])
        XCTAssertEqual(AiV2StubProtocol.requests[0].request.url?.absoluteString, "https://api.example/v1/models")
        XCTAssertEqual(AiV2StubProtocol.requests[0].request.value(forHTTPHeaderField: "Authorization"), "Bearer k1")

        let anthropicModels = try await client.listCustomModels(format: .anthropic, baseUrl: "https://ant.example", apiKey: "k2")
        XCTAssertEqual(anthropicModels, ["a-model", "z-model"])
        XCTAssertEqual(AiV2StubProtocol.requests[1].request.url?.absoluteString, "https://ant.example/v1/models")
        XCTAssertEqual(AiV2StubProtocol.requests[1].request.value(forHTTPHeaderField: "x-api-key"), "k2")
        XCTAssertEqual(AiV2StubProtocol.requests[1].request.value(forHTTPHeaderField: "anthropic-version"), "2023-06-01")
    }

    func test_should_supportExaTavilyBraveAndDoubaoSearchBackends() async throws {
        let client = GeminiClient(session: AiV2StubProtocol.session)

        // Exa
        AiV2StubProtocol.reset { req, _ in
            XCTAssertEqual(req.value(forHTTPHeaderField: "x-api-key"), "exa-k")
            return (200, Data(#"{"results":[{"title":"E","url":"https://e.com","text":"exa text"}]}"#.utf8))
        }
        let exaAi = AiSettings(prefs: AiProviderPrefs(provider: .custom, searchService: .exa), secrets: AiSecrets(searchKeyExa: "exa-k"))
        let exaHits = try await client.search(ai: exaAi, query: "credits")
        XCTAssertEqual(exaHits, [.init(title: "E", url: "https://e.com", text: "exa text")])

        // Tavily
        AiV2StubProtocol.reset { req, _ in
            XCTAssertEqual(req.value(forHTTPHeaderField: "Authorization"), "Bearer tav-k")
            return (200, Data(#"{"results":[{"title":"T","url":"https://t.com","content":"tavily text"}]}"#.utf8))
        }
        let tavilyAi = AiSettings(prefs: AiProviderPrefs(provider: .custom, searchService: .tavily), secrets: AiSecrets(searchKeyTavily: "tav-k"))
        let tavilyHits = try await client.search(ai: tavilyAi, query: "credits")
        XCTAssertEqual(tavilyHits, [.init(title: "T", url: "https://t.com", text: "tavily text")])

        // Brave: 403 on /llm/context falls back to /web/search
        AiV2StubProtocol.reset { req, _ in
            XCTAssertEqual(req.value(forHTTPHeaderField: "X-Subscription-Token"), "brv-k")
            if req.url?.path.contains("llm/context") == true {
                return (403, Data(#"{"error":{"detail":"LLM context requires pro plan"}}"#.utf8))
            }
            return (200, Data(#"{"web":{"results":[{"title":"B","url":"https://b.com","description":"desc","extra_snippets":["snip"]}]}}"#.utf8))
        }
        let braveAi = AiSettings(prefs: AiProviderPrefs(provider: .custom, searchService: .brave), secrets: AiSecrets(searchKeyBrave: "brv-k"))
        let braveHits = try await client.search(ai: braveAi, query: "credits")
        XCTAssertEqual(braveHits, [.init(title: "B", url: "https://b.com", text: "desc\nsnip")])

        // Doubao: 200 ok + ResponseMetadata error check
        AiV2StubProtocol.reset { _, _ in
            (200, Data(#"{"Result":{"WebResults":[{"Title":"D","Url":"https://d.com","Content":"doubao content"}]}}"#.utf8))
        }
        let doubaoAi = AiSettings(prefs: AiProviderPrefs(provider: .custom, searchService: .doubao), secrets: AiSecrets(searchKeyDoubao: "db-k"))
        let doubaoHits = try await client.search(ai: doubaoAi, query: "credits")
        XCTAssertEqual(doubaoHits, [.init(title: "D", url: "https://d.com", text: "doubao content")])

        AiV2StubProtocol.reset { _, _ in
            (200, Data(#"{"ResponseMetadata":{"Error":{"Code":"QuotaExceeded","Message":"Daily limit"}}}"#.utf8))
        }
        do {
            _ = try await client.search(ai: doubaoAi, query: "credits")
            XCTFail("Expected Doubao error")
        } catch let failure as GeminiClient.Failure {
            XCTAssertEqual(failure.message, "Doubao error: Daily limit (QuotaExceeded)")
        }
    }

    func test_should_resolveReleaseNamesAndReturnNilWhenSameAsShown() async throws {
        AiV2StubProtocol.reset { _, _ in
            (200, Data(#"{"choices":[{"message":{"content":"[TITLE]《摩天輪》[/TITLE]\n[ARTIST]田馥甄[/ARTIST]"}}]}"#.utf8))
        }
        let deepSeek = AiSettings(prefs: AiProviderPrefs(provider: .deepSeek), secrets: AiSecrets(deepSeekKey: "ds"))
        let client = GeminiClient(session: AiV2StubProtocol.session, ai: deepSeek)

        let resolved = try await client.resolveReleaseNames(
            title: "Ferries wheel",
            artist: "ヒビ・ティエン",
            album: "To Hebe",
            isrc: "TWD950519106",
            durationMs: 244_800,
            otherNames: [("摩天輪", "田馥甄")],
            ai: deepSeek
        )
        XCTAssertEqual(resolved?.title, "摩天輪")
        XCTAssertEqual(resolved?.artist, "田馥甄")

        let same = GeminiClient.parseReleaseNames("[TITLE]Same[/TITLE][ARTIST]Artist[/ARTIST]", shownTitle: "same", shownArtist: "Artist")
        XCTAssertNil(same)
        XCTAssertNil(GeminiClient.parseReleaseNames("[TITLE]N/A[/TITLE][ARTIST]N/A[/ARTIST]", shownTitle: "A", shownArtist: "B"))
    }

    func test_should_extractSixDistinctRecentTracksAndFilterModelChipsAndChartLabels() {
        var rows: [PlayEvent] = []
        for idx in 1...10 {
            rows.append(
                PlayEvent(
                    id: "p-\(idx)-a",
                    scope: "s",
                    provider: "subsonic",
                    songId: "song-\(idx)",
                    albumId: "alb-\(idx)",
                    artistId: "art-\(idx)",
                    title: idx == 2 ? "   " : "Song \(idx)",
                    album: "Album \(idx)",
                    artist: "Artist \(idx)",
                    coverKey: nil,
                    startedAt: Int64(100 - idx),
                    durationSec: 200
                )
            )
            rows.append(
                PlayEvent(
                    id: "p-\(idx)-dup",
                    scope: "s",
                    provider: "subsonic",
                    songId: "song-\(idx)",
                    albumId: "alb-\(idx)",
                    artistId: "art-\(idx)",
                    title: idx == 2 ? "   " : "Song \(idx)",
                    album: "Album \(idx)",
                    artist: "Artist \(idx)",
                    coverKey: nil,
                    startedAt: Int64(90 - idx),
                    durationSec: 200
                )
            )
        }
        let picked = AiTrySongs.recentDistinct(from: rows, limit: 6)
        XCTAssertEqual(picked.map(\.songId), ["song-1", "song-3", "song-4", "song-5", "song-6", "song-7"])

        let all = (1...30).map { "model-\($0)" }
        XCTAssertEqual(AiSettings.filteredModelChips(ids: all, typed: "model-2").count, 11)
        XCTAssertEqual(AiSettings.filteredModelChips(ids: Array(all.prefix(10)), typed: "999").count, 10)

        let placed = AiChartLabels.place(
            plot: CGRect(x: 0, y: 0, width: 200, height: 150),
            dots: [CGPoint(x: 100, y: 75), CGPoint(x: 105, y: 75)],
            dotRadius: 8,
            gap: 6,
            labels: [(0, CGSize(width: 40, height: 14)), (1, CGSize(width: 40, height: 14))]
        )
        XCTAssertEqual(placed.count, 2)
    }
}
