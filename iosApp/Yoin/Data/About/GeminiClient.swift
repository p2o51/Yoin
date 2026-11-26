import Foundation

/// Online AI client for About, Ask, Lyric Translation, Release-Name Lookup, and Album Memories
/// (Android `GeminiService` + `AiClient`). Routes calls to Gemini, DeepSeek, Qwen, or Custom
/// (OpenAI-compatible / Anthropic Messages) plus external search providers (DeepSeek, Exa,
/// Tavily, Brave, Doubao). API keys travel only in request headers and are never logged.
struct GeminiClient: Sendable {
    static let model = AiSettings.geminiGood
    static let baseURL = URL(string: "https://generativelanguage.googleapis.com/v1beta/models/")!
    static let anthropicVersion = "2023-06-01"
    static let anthropicMaxTokens = 16_000
    static let searchResultsLimit = 8
    static let maxPagesInPrompt = 10
    static let maxPageChars = 2_000
    private static let deepSeekQueryLimit = 128
    private static let doubaoQueryLimit = 100
    private static let doubaoSearchURL = "https://open.feedcoopapi.com/search_api/web_search"
    private static let braveBaseURL = "https://api.search.brave.com"
    private static let braveFallbackCodes: Set<Int> = [403, 404, 422]
    private static let adaptiveThinkingWords = ["thinking", "adaptive", "effort", "output_config"]

    var baseURL: URL = GeminiClient.defaultBaseURL
    var session: URLSession = .shared
    var ai: AiSettings?

    /// One About field: `SongAboutStore.Canon` key and AI answer.
    struct Value: Equatable, Sendable {
        var key: String
        var answer: String
    }

    struct Failure: LocalizedError, Equatable {
        var message: String
        var httpCode: Int?
        var errorDescription: String? { message }

        init(message: String, httpCode: Int? = nil) {
            self.message = message
            self.httpCode = httpCode
        }
    }

    struct SearchHit: Equatable, Sendable {
        var title: String
        var url: String
        var text: String
    }

    struct AboutRun: Equatable, Sendable {
        var values: [Value]
        var rawText: String
        var searchKind: AiSettings.SearchKind
        var searchQueries: [String]
        var pagesRead: Int
        var model: String
    }

    /// Ask reply: a short heading and the answer (Android `AskAnswer`).
    struct AskAnswer: Equatable, Sendable {
        var title: String
        var answer: String
    }

    struct MemoryNarration: Equatable, Sendable {
        var narration: String
        var question: String
        var encoded: String { "[NARRATION]\(narration)[/NARRATION]\n[QUESTION]\(question)[/QUESTION]" }
    }

    // MARK: - Canonical About & Try a Song

    func canonicalAbout(
        apiKey: String,
        title: String,
        artist: String,
        album: String,
        targetLanguage: String
    ) async throws -> [Value] {
        let settings = resolvedAi(apiKey: apiKey)
        return try await runCanonicalAbout(
            ai: settings,
            title: title,
            artist: artist,
            album: album,
            targetLanguage: targetLanguage
        ).values
    }

    func runCanonicalAbout(
        ai settings: AiSettings,
        title: String,
        artist: String,
        album: String,
        targetLanguage: String
    ) async throws -> AboutRun {
        var queries = ["\(title) \(artist) \(Self.creditsKeywords(title: title, artist: artist))"]
        if settings.searchQueries > 1 {
            queries.append("\(title) \(artist) \(album)")
        }
        queries = Array(queries.prefix(settings.searchQueries))

        let searched = try await searchFor(ai: settings, queries: queries)
        let prompt = Self.aboutPrompt(
            title: title,
            artist: artist,
            album: album,
            targetLanguage: targetLanguage,
            searchLead: Self.searchLead(for: settings, pages: searched.pages)
        ) + searched.appendix

        let rawText: String
        if settings.provider == .gemini {
            rawText = try await generateGemini(
                ai: settings,
                prompt: prompt,
                temperature: nil,
                useSearch: true,
                timeout: 45,
                requireNonEmpty: false
            )
        } else {
            rawText = try await generateText(
                ai: settings,
                prompt: prompt,
                temperature: 0.8,
                useSearch: true,
                timeout: 45,
                requireNonEmpty: true
            )
        }
        return AboutRun(
            values: Self.parseTagged(rawText),
            rawText: rawText,
            searchKind: settings.searchKind,
            searchQueries: settings.searchKind == .external ? queries : [],
            pagesRead: searched.pages,
            model: settings.modelId
        )
    }

    // MARK: - Ask

    func ask(
        apiKey: String,
        title: String,
        artist: String,
        album: String,
        question: String,
        targetLanguage: String
    ) async throws -> AskAnswer {
        let settings = resolvedAi(apiKey: apiKey)
        let searched = try await searchFor(ai: settings, queries: ["\(title) \(artist) \(question)"])
        let prompt = Self.askPrompt(
            title: title,
            artist: artist,
            album: album,
            question: question,
            targetLanguage: targetLanguage,
            searchLead: Self.searchLead(for: settings, pages: searched.pages)
        ) + searched.appendix
        let raw = try await generateText(
            ai: settings,
            prompt: prompt,
            temperature: 0.8,
            useSearch: true,
            timeout: 45,
            requireNonEmpty: true
        )
        return Self.parseAsk(raw, fallbackQuestion: question)
    }

    func askTitle(
        apiKey: String,
        title: String,
        artist: String,
        album: String,
        question: String,
        targetLanguage: String
    ) async throws -> String {
        let settings = resolvedAi(apiKey: apiKey)
        let prompt = Self.askTitlePrompt(
            title: title,
            artist: artist,
            album: album,
            question: question,
            targetLanguage: targetLanguage
        )
        let raw = try await generateText(
            ai: settings,
            prompt: prompt,
            temperature: 0.2,
            useSearch: false,
            timeout: 45,
            requireNonEmpty: true
        )
        return Self.cleanAskTitle(raw, fallbackQuestion: question)
    }

    // MARK: - Memories

    func memoryTitle(
        apiKey: String,
        albumName: String,
        artist: String?,
        year: Int?,
        writingKind: String,
        writingText: String
    ) async throws -> String {
        let settings = resolvedAi(apiKey: apiKey)
        let prompt = Self.memoryTitlePrompt(
            albumName: albumName,
            artist: artist,
            year: year,
            writingKind: writingKind,
            writingText: writingText
        )
        let raw = try await generateText(
            ai: settings,
            prompt: prompt,
            temperature: 0.2,
            useSearch: false,
            timeout: 45,
            requireNonEmpty: true
        )
        var title = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if title.hasPrefix("\""), title.hasSuffix("\""), title.count >= 2 {
            title = String(title.dropFirst().dropLast())
        }
        title = title.replacingOccurrences(of: "\n", with: " ")
        guard !title.isEmpty else {
            throw Failure(message: "No content in \(Self.providerLabel(settings)) response")
        }
        return title
    }

    /// No search grounding or listener writing in this request; Android's 8-second narration timeout.
    func memoryNarration(
        apiKey: String,
        targetLanguage: String,
        facts: [String],
        alreadySaid: String? = nil
    ) async throws -> MemoryNarration {
        let settings = resolvedAi(apiKey: apiKey)
        let prompt = Self.memoryNarrationPrompt(
            targetLanguage: targetLanguage,
            facts: facts,
            alreadySaid: alreadySaid
        )
        let raw = try await generateText(
            ai: settings,
            prompt: prompt,
            temperature: 0.4,
            useSearch: false,
            timeout: 8,
            requireNonEmpty: true
        )
        guard let result = Self.parseMemoryNarration(raw), Self.followsMemoryVoiceRules(result) else {
            throw Failure(message: "No usable narration in AI response")
        }
        return result
    }

    // MARK: - Release Names Lookup (Lyrics fallback)

    /// Identifies the original release title + main artist when a streaming service displays a
    /// localized/transliterated track name. Returns `nil` if unsure or if names match what is shown.
    func resolveReleaseNames(
        title: String,
        artist: String,
        album: String? = nil,
        isrc: String? = nil,
        durationMs: Int64? = nil,
        otherNames: [(title: String, artist: String)] = [],
        ai overrideAi: AiSettings? = nil
    ) async throws -> (title: String, artist: String)? {
        let settings = overrideAi ?? resolvedAi(apiKey: "")
        let queryParts = [title, artist, album]
            .compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        let query = queryParts.joined(separator: " ")
        let searched = query.isEmpty ? Searched(appendix: "", pages: 0) : try await searchFor(ai: settings, queries: [query])
        let prompt = Self.releaseNamesPrompt(
            title: title,
            artist: artist,
            album: album,
            isrc: isrc,
            durationMs: durationMs,
            otherNames: otherNames,
            searchLead: Self.searchLead(for: settings, pages: searched.pages)
        ) + searched.appendix
        let raw = try await generateText(
            ai: settings,
            prompt: prompt,
            temperature: 0.1,
            useSearch: true,
            timeout: 20,
            requireNonEmpty: true
        )
        return Self.parseReleaseNames(raw, shownTitle: title, shownArtist: artist)
    }

    // MARK: - Custom Model Discovery

    func listCustomModels(format: AiApiFormat, baseUrl: String, apiKey: String) async throws -> [String] {
        let trimmedBase = baseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let urlString: String = switch format {
        case .openAi: "\(trimmedBase)/models"
        case .anthropic: "\(Self.anthropicBase(trimmedBase))/models"
        }
        guard !trimmedBase.isEmpty, let url = URL(string: urlString) else {
            throw Failure(message: "Invalid endpoint URL")
        }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = 20
        let trimmedKey = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        switch format {
        case .openAi:
            request.setValue("Bearer \(trimmedKey)", forHTTPHeaderField: "Authorization")
        case .anthropic:
            request.setValue(trimmedKey, forHTTPHeaderField: "x-api-key")
            request.setValue(Self.anthropicVersion, forHTTPHeaderField: "anthropic-version")
        }
        let json = try await executeJSON(request, label: "Models")
        let rows = (json["data"] as? [[String: Any]]) ?? []
        return rows.compactMap { ($0["id"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .sorted()
    }

    func listCustomModels(ai settings: AiSettings) async throws -> [String] {
        try await listCustomModels(
            format: settings.prefs.customFormat,
            baseUrl: settings.prefs.customBaseUrl,
            apiKey: settings.secrets.customKey
        )
    }

    // MARK: - Provider Routing & Transport

    func resolvedAi(apiKey: String) -> AiSettings {
        if let ai {
            if ai.provider == .gemini, ai.geminiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, !apiKey.isEmpty {
                var copy = ai
                copy.geminiKey = apiKey
                return copy
            }
            return ai
        }
        if baseURL == Self.baseURL {
            let shared = GeminiSettings.currentSharedAiSettings()
            if shared.provider == .gemini, shared.geminiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, !apiKey.isEmpty {
                var copy = shared
                copy.geminiKey = apiKey
                return copy
            }
            return shared
        }
        return .geminiOnly(apiKey: apiKey)
    }

    func generateText(
        ai settings: AiSettings,
        prompt: String,
        temperature: Double?,
        useSearch: Bool,
        timeout: TimeInterval,
        requireNonEmpty: Bool = true
    ) async throws -> String {
        switch settings.provider {
        case .gemini:
            return try await generateGemini(
                ai: settings,
                prompt: prompt,
                temperature: temperature,
                useSearch: useSearch,
                timeout: timeout,
                requireNonEmpty: requireNonEmpty
            )
        case .deepSeek:
            return try await generateOpenAI(
                ai: settings,
                urlString: "\(AiSettings.deepSeekBaseURL)/chat/completions",
                prompt: prompt,
                temperature: temperature,
                extra: ["thinking": ["type": "disabled"]],
                timeout: timeout
            )
        case .qwen:
            var extra: [String: Any] = ["enable_thinking": false]
            if useSearch {
                extra["enable_search"] = true
                extra["search_options"] = ["forced_search": true]
            }
            return try await generateOpenAI(
                ai: settings,
                urlString: "\(AiSettings.qwenBaseURL)/chat/completions",
                prompt: prompt,
                temperature: temperature,
                extra: extra,
                timeout: timeout
            )
        case .custom:
            let base = settings.prefs.customBaseUrl
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            switch settings.prefs.customFormat {
            case .openAi:
                var extra: [String: Any] = [:]
                if let effort = Self.reasoningEffort(settings.prefs.customThinking) {
                    extra["reasoning_effort"] = effort
                }
                return try await generateOpenAI(
                    ai: settings,
                    urlString: "\(base)/chat/completions",
                    prompt: prompt,
                    temperature: nil,
                    extra: extra,
                    timeout: timeout
                )
            case .anthropic:
                return try await generateAnthropic(
                    ai: settings,
                    base: base,
                    prompt: prompt,
                    timeout: timeout
                )
            }
        }
    }

    private func generateGemini(
        ai settings: AiSettings,
        prompt: String,
        temperature: Double?,
        useSearch: Bool,
        timeout: TimeInterval,
        requireNonEmpty: Bool
    ) async throws -> String {
        let body = RequestBody(
            contents: [.init(parts: [.init(text: prompt)])],
            tools: useSearch ? [.init(googleSearch: .init())] : nil,
            generationConfig: temperature.map { .init(temperature: $0) }
        )
        var request = try Self.request(
            baseURL: baseURL,
            model: settings.modelId,
            apiKey: settings.apiKey.trimmingCharacters(in: .whitespacesAndNewlines),
            body: body
        )
        request.timeoutInterval = timeout
        let (status, data) = try await send(request, label: "Gemini")
        return try Self.text(status: status, body: data, requireNonEmpty: requireNonEmpty)
    }

    private func generateOpenAI(
        ai settings: AiSettings,
        urlString: String,
        prompt: String,
        temperature: Double?,
        extra: [String: Any],
        timeout: TimeInterval
    ) async throws -> String {
        guard let url = URL(string: urlString), url.scheme != nil else {
            throw Failure(message: "Invalid endpoint URL")
        }
        var payload: [String: Any] = [
            "model": settings.modelId,
            "messages": [["role": "user", "content": prompt]],
        ]
        if let temperature { payload["temperature"] = temperature }
        for (k, v) in extra { payload[k] = v }

        let label = Self.providerLabel(settings)
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = timeout
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(settings.apiKey.trimmingCharacters(in: .whitespacesAndNewlines))", forHTTPHeaderField: "Authorization")
        request.httpBody = try JSONSerialization.data(withJSONObject: payload)

        let root = try await executeJSON(request, label: label)
        let choices = (root["choices"] as? [[String: Any]]) ?? []
        let content = ((choices.first?["message"] as? [String: Any])?["content"] as? String) ?? ""
        guard !content.isEmpty else {
            throw Failure(message: "No content in \(label) response")
        }
        return content
    }

    private func generateAnthropic(
        ai settings: AiSettings,
        base: String,
        prompt: String,
        timeout: TimeInterval
    ) async throws -> String {
        let label = Self.providerLabel(settings)
        guard !base.isEmpty, let url = URL(string: "\(Self.anthropicBase(base))/messages"), url.scheme != nil else {
            throw Failure(message: "Invalid Anthropic URL")
        }
        let thinking = settings.prefs.customThinking
        let root: [String: Any]
        do {
            root = try await postAnthropic(
                url: url,
                apiKey: settings.apiKey.trimmingCharacters(in: .whitespacesAndNewlines),
                body: Self.anthropicBody(model: settings.modelId, prompt: prompt, thinking: thinking, budgetThinking: false),
                label: label,
                timeout: timeout
            )
        } catch let failure as Failure where thinking != .off && Self.rejectsAdaptiveThinking(failure) {
            root = try await postAnthropic(
                url: url,
                apiKey: settings.apiKey.trimmingCharacters(in: .whitespacesAndNewlines),
                body: Self.anthropicBody(model: settings.modelId, prompt: prompt, thinking: thinking, budgetThinking: true),
                label: label,
                timeout: timeout
            )
        }
        if (root["stop_reason"] as? String) == "max_tokens" {
            throw Failure(message: "\(label) reply was cut off at the output limit")
        }
        let blocks = (root["content"] as? [[String: Any]]) ?? []
        let text = blocks
            .filter { ($0["type"] as? String) == "text" }
            .compactMap { $0["text"] as? String }
            .joined()
        guard !text.isEmpty else {
            throw Failure(message: "No content in \(label) response")
        }
        return text
    }

    private func postAnthropic(
        url: URL,
        apiKey: String,
        body: [String: Any],
        label: String,
        timeout: TimeInterval
    ) async throws -> [String: Any] {
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = timeout
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(apiKey, forHTTPHeaderField: "x-api-key")
        request.setValue(Self.anthropicVersion, forHTTPHeaderField: "anthropic-version")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        return try await executeJSON(request, label: label)
    }

    // MARK: - External Web Search

    struct Searched {
        let appendix: String
        let pages: Int
    }

    func searchFor(ai settings: AiSettings, queries: [String]) async throws -> Searched {
        guard settings.searchKind == .external, !queries.isEmpty else {
            return Searched(appendix: "", pages: 0)
        }
        let hits = try await withThrowingTaskGroup(of: (Int, [SearchHit]).self) { group in
            for (index, query) in queries.enumerated() {
                group.addTask {
                    (index, try await self.search(ai: settings, query: query))
                }
            }
            var byIndex: [(Int, [SearchHit])] = []
            for try await pair in group {
                byIndex.append(pair)
            }
            return byIndex.sorted { $0.0 < $1.0 }.flatMap(\.1)
        }
        let block = Self.contextBlock(hits)
        guard !block.isEmpty else { return Searched(appendix: "", pages: 0) }
        var uniqueCount = 0
        var seenUrls = Set<String>()
        for hit in hits where seenUrls.insert(hit.url).inserted {
            uniqueCount += 1
            if uniqueCount == Self.maxPagesInPrompt { break }
        }
        return Searched(appendix: "\n\nSearch results:\n\(block)", pages: uniqueCount)
    }

    func search(ai settings: AiSettings, query: String) async throws -> [SearchHit] {
        let key = settings.searchApiKey
        let rawHits: [SearchHit]
        switch settings.searchService {
        case .deepSeek:
            let payload: [String: Any] = [
                "query": String(query.prefix(Self.deepSeekQueryLimit)),
                "count": Self.searchResultsLimit,
                "need_content": true,
            ]
            let root = try await postJSON(
                urlString: "\(AiSettings.deepSeekBaseURL)/v1/tools/search",
                body: payload,
                label: "DeepSeek Search",
                headers: ["Authorization": "Bearer \(key)"]
            )
            let results = (root["results"] as? [[String: Any]]) ?? []
            rawHits = results.map { item in
                let content = (item["content"] as? String) ?? ""
                let summary = (item["summary"] as? String) ?? ""
                return SearchHit(
                    title: (item["title"] as? String) ?? "",
                    url: (item["url"] as? String) ?? "",
                    text: content.isEmpty ? summary : content
                )
            }

        case .exa:
            let payload: [String: Any] = [
                "query": query,
                "numResults": Self.searchResultsLimit,
                "contents": ["text": ["maxCharacters": Self.maxPageChars]],
            ]
            let root = try await postJSON(
                urlString: "https://api.exa.ai/search",
                body: payload,
                label: "Exa",
                headers: ["x-api-key": key]
            )
            let results = (root["results"] as? [[String: Any]]) ?? []
            rawHits = results.map { item in
                SearchHit(
                    title: (item["title"] as? String) ?? "",
                    url: (item["url"] as? String) ?? "",
                    text: (item["text"] as? String) ?? ""
                )
            }

        case .tavily:
            let payload: [String: Any] = [
                "query": query,
                "max_results": Self.searchResultsLimit,
                "search_depth": "advanced",
                "chunks_per_source": 3,
            ]
            let root = try await postJSON(
                urlString: "https://api.tavily.com/search",
                body: payload,
                label: "Tavily",
                headers: ["Authorization": "Bearer \(key)"]
            )
            let results = (root["results"] as? [[String: Any]]) ?? []
            rawHits = results.map { item in
                SearchHit(
                    title: (item["title"] as? String) ?? "",
                    url: (item["url"] as? String) ?? "",
                    text: (item["content"] as? String) ?? ""
                )
            }

        case .brave:
            rawHits = try await braveSearch(key: key, query: query)

        case .doubao:
            let payload: [String: Any] = [
                "Query": String(query.prefix(Self.doubaoQueryLimit)),
                "SearchType": "web",
                "Count": Self.searchResultsLimit,
                "Filter": ["NeedContent": true],
            ]
            let root = try await postJSON(
                urlString: Self.doubaoSearchURL,
                body: payload,
                label: "Doubao",
                headers: ["Authorization": "Bearer \(key)"]
            )
            if let errorText = Self.doubaoError(root) {
                throw Failure(message: "Doubao error: \(errorText)")
            }
            let webResults = ((root["Result"] as? [String: Any])?["WebResults"] as? [[String: Any]]) ?? []
            rawHits = webResults.map { item in
                let content = (item["Content"] as? String) ?? ""
                let summary = (item["Summary"] as? String) ?? ""
                return SearchHit(
                    title: (item["Title"] as? String) ?? "",
                    url: (item["Url"] as? String) ?? "",
                    text: content.isEmpty ? summary : content
                )
            }

        case .none:
            rawHits = []
        }

        return rawHits.compactMap { hit in
            guard !hit.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
            return SearchHit(title: hit.title, url: hit.url, text: String(hit.text.prefix(Self.maxPageChars)))
        }
    }

    private func braveSearch(key: String, query: String) async throws -> [SearchHit] {
        func fetchBrave(path: String, extraQuery: [URLQueryItem]) async throws -> [String: Any] {
            var comps = URLComponents(string: "\(Self.braveBaseURL)/\(path)")!
            comps.queryItems = [
                URLQueryItem(name: "q", value: query),
                URLQueryItem(name: "count", value: String(Self.searchResultsLimit)),
            ] + extraQuery
            guard let url = comps.url else { throw Failure(message: "Invalid Brave URL") }
            var request = URLRequest(url: url)
            request.httpMethod = "GET"
            request.timeoutInterval = 30
            request.setValue(key, forHTTPHeaderField: "X-Subscription-Token")
            request.setValue("application/json", forHTTPHeaderField: "Accept")
            return try await executeJSON(request, label: "Brave")
        }

        do {
            let context = try await fetchBrave(path: "res/v1/llm/context", extraQuery: [])
            let generic = ((context["grounding"] as? [String: Any])?["generic"] as? [[String: Any]]) ?? []
            return generic.map { item in
                let snippets = (item["snippets"] as? [String]) ?? []
                return SearchHit(
                    title: (item["title"] as? String) ?? "",
                    url: (item["url"] as? String) ?? "",
                    text: snippets.joined(separator: "\n")
                )
            }
        } catch let failure as Failure where failure.httpCode.map({ Self.braveFallbackCodes.contains($0) }) == true {
            let web = try await fetchBrave(
                path: "res/v1/web/search",
                extraQuery: [URLQueryItem(name: "extra_snippets", value: "true")]
            )
            let results = ((web["web"] as? [String: Any])?["results"] as? [[String: Any]]) ?? []
            return results.map { item in
                let description = (item["description"] as? String) ?? ""
                let extras = (item["extra_snippets"] as? [String]) ?? []
                let text = ([description] + extras)
                    .filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
                    .joined(separator: "\n")
                return SearchHit(
                    title: (item["title"] as? String) ?? "",
                    url: (item["url"] as? String) ?? "",
                    text: text
                )
            }
        }
    }

    private func postJSON(
        urlString: String,
        body: [String: Any],
        label: String,
        headers: [String: String],
        timeout: TimeInterval = 30
    ) async throws -> [String: Any] {
        guard let url = URL(string: urlString), url.scheme != nil else {
            throw Failure(message: "Invalid \(label) URL")
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = timeout
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        for (name, value) in headers {
            request.setValue(value, forHTTPHeaderField: name)
        }
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        return try await executeJSON(request, label: label)
    }

    private func executeJSON(_ request: URLRequest, label: String) async throws -> [String: Any] {
        let (status, data) = try await send(request, label: label)
        guard (200..<300).contains(status) else {
            throw Failure(message: "\(label) error (\(status)): \(Self.errorMessage(data))", httpCode: status)
        }
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw Failure(message: "Unreadable response from \(label)")
        }
        return json
    }

    private func send(_ request: URLRequest, label: String = "Gemini") async throws -> (Int, Data) {
        do {
            let (data, response) = try await session.data(for: request)
            return ((response as? HTTPURLResponse)?.statusCode ?? 0, data)
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as URLError where error.code == .cancelled {
            throw CancellationError()
        } catch let error as URLError {
            throw Failure(message: Self.networkMessage(error, label: label))
        }
    }

    // MARK: - Prompts

    static func memoryTitlePrompt(
        albumName: String,
        artist: String?,
        year: Int?,
        writingKind: String,
        writingText: String
    ) -> String {
        let artistLine = artist.flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 } ?? "Unknown artist"
        let yearLine = year.map(String.init) ?? "Unknown year"
        return """
        You are titling a private music-journal entry. The user wrote the following
        \(writingKind) about an album; give it a short title.

        Album: \(albumName)
        Artist: \(artistLine)
        Year: \(yearLine)

        Their \(writingKind):
        \(writingText)

        Write ONE title that captures the heart of what they wrote, drawing on what
        you know about this album's sound and background where it sharpens the title.
        Same language as their text. At most 14 CJK characters or 34 latin characters.
        No quotes, no emoji, no trailing period, no line break.
        Output only the title itself.
        """
    }

    static func memoryNarrationPrompt(targetLanguage: String, facts: [String], alreadySaid: String?) -> String {
        let factLines = facts.map { "- \($0)" }.joined(separator: "\n")
        let saidLine = alreadySaid.flatMap { said in
            said.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil
                : "\nThe title already on the page reads: \"\(said)\". Do not repeat any fact it states.\n"
        } ?? ""
        return """
        You are Yoin, a music app. You write a few words at the top of a listener's
        private diary page for one album, speaking to the listener directly.

        Listening facts recorded in the app (the only things you may mention):
        \(factLines)
        \(saidLine)
        Rules:
        - Write in \(targetLanguage).
        - NARRATION: one or two short sentences, second person, past tense, built only
          from the facts above. Pick the one or two facts that say most about how they
          listened; do not list them all.
        - QUESTION: one short question that follows directly from the last fact you
          stated, so the listener wants to write their own words. It ends with "?" in
          English or "？" in Chinese.
        - Never judge, praise or describe the music, the artist, the album or its
          sound. Never guess at feelings, moods or reasons.
        - Never quote, paraphrase or allude to anything the listener wrote. You have
          not read it.
        - Never name the mechanism: no words such as "memory", "memories",
          "remember", "记忆", "回忆".
        - Keep track names, months and relative dates as given.
        - No quotation marks around your text, no emoji, no hashtags, no line breaks.

        The voice, from other albums (never reuse their facts):
        [NARRATION]37 plays since March, the last one two days ago.[/NARRATION]
        [QUESTION]You gave it a 9.5. What earned it?[/QUESTION]
        [NARRATION]你跨了三个季节回来听，最高分是《Undertow》的 9.0。[/NARRATION]
        [QUESTION]那整张专辑呢？[/QUESTION]

        Output exactly this and nothing else:
        [NARRATION]...[/NARRATION]
        [QUESTION]...[/QUESTION]
        """
    }

    static func aboutPrompt(title: String, artist: String, album: String, targetLanguage: String) -> String {
        aboutPrompt(
            title: title,
            artist: artist,
            album: album,
            targetLanguage: targetLanguage,
            searchLead: "Use the search tool to find accurate information."
        )
    }

    static func aboutPrompt(
        title: String,
        artist: String,
        album: String,
        targetLanguage: String,
        searchLead: String
    ) -> String {
        """
        Search for and provide detailed information about the following song:

        Song: \(title)
        Artist: \(artist)
        Album: \(album)

        \(searchLead) Write all human-readable
        answers in \(targetLanguage). Respond strictly in the following tagged format.
        If any information cannot be found, write "N/A" inside the tag.

        [CREATION_TIME]
        When the song was created/recorded
        [/CREATION_TIME]

        [CREATION_LOCATION]
        Where the song was created/recorded
        [/CREATION_LOCATION]

        [LYRICIST]
        Lyricist(s)
        [/LYRICIST]

        [COMPOSER]
        Composer(s)
        [/COMPOSER]

        [PRODUCER]
        Producer(s)
        [/PRODUCER]

        [REVIEW]
        A 2-4 sentence analysis of the song including its background, musical characteristics, and cultural significance. Be insightful and professional but not overly academic.
        [/REVIEW]

        Respond strictly in the tagged format above. Every field must include its corresponding tags.
        """
    }

    static func askPrompt(
        title: String,
        artist: String,
        album: String,
        question: String,
        targetLanguage: String
    ) -> String {
        askPrompt(
            title: title,
            artist: artist,
            album: album,
            question: question,
            targetLanguage: targetLanguage,
            searchLead: "Use the search tool to find accurate, up-to-date information."
        )
    }

    static func askPrompt(
        title: String,
        artist: String,
        album: String,
        question: String,
        targetLanguage: String,
        searchLead: String
    ) -> String {
        """
        Answer the following question about the song "\(title)" from the album "\(album)" by \(artist).
        \(searchLead)
        Write the title and answer in \(targetLanguage).

        Respond strictly in the following tagged format:

        [TITLE]
        A concise headline that summarises the question — no more than 8 words, no trailing punctuation. This is shown as the visual heading above your answer, so it should feel like a title, not a full sentence.
        [/TITLE]

        [ANSWER]
        The detailed answer. No more than 120 words. Plain prose only — no citation markers, headers, or bullet points.
        [/ANSWER]

        Question: \(question)
        """
    }

    static func askTitlePrompt(
        title: String,
        artist: String,
        album: String,
        question: String,
        targetLanguage: String
    ) -> String {
        """
        Write a concise visual heading for an in-progress Gemini answer about this song.
        Write the heading in \(targetLanguage).

        Song: \(title)
        Artist: \(artist)
        Album: \(album)
        Question: \(question)

        Return only the heading. No more than 8 words. No trailing punctuation. Do not
        answer the question.
        """
    }

    static func releaseNamesPrompt(
        title: String,
        artist: String,
        album: String?,
        isrc: String?,
        durationMs: Int64?,
        otherNames: [(title: String, artist: String)],
        searchLead: String
    ) -> String {
        let albumLine = album.flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : "Album: \($0)\n" } ?? ""
        let isrcLine = isrc.flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : "ISRC: \($0.trimmingCharacters(in: .whitespacesAndNewlines).uppercased())\n" } ?? ""
        let lengthLine: String = if let ms = durationMs, ms > 0 {
            String(format: "Length: %d:%02d\n", (ms / 1000) / 60, (ms / 1000) % 60)
        } else {
            ""
        }
        let others = otherNames.map { "Also listed as: \($0.title) — \($0.artist)\n" }.joined()
        return """
        A music streaming service lists a song under localized, translated or
        transliterated names, and lyrics sites can't find it under them. Identify the
        song and give its title and main artist exactly as it was originally released,
        in the original language and script, the way lyrics sites list it.

        Title: \(title)
        Artist: \(artist)
        \(albumLine)\(isrcLine)\(lengthLine)\(others)
        \(searchLead) Respond strictly in this format:

        [TITLE]original title[/TITLE]
        [ARTIST]original main artist[/ARTIST]

        If you are not sure which song this is, write N/A in both tags.
        """
    }

    // MARK: - Request & Body Helpers (Preserved for existing tests)

    static func aboutRequest(
        baseURL: URL,
        apiKey: String,
        title: String,
        artist: String,
        album: String,
        targetLanguage: String
    ) throws -> URLRequest {
        try request(
            baseURL: baseURL,
            model: model,
            apiKey: apiKey,
            body: RequestBody(
                contents: [.init(parts: [.init(text: aboutPrompt(
                    title: title,
                    artist: artist,
                    album: album,
                    targetLanguage: targetLanguage
                ))])],
                tools: [.init(googleSearch: .init())]
            )
        )
    }

    static func request(baseURL: URL, apiKey: String, body: RequestBody) throws -> URLRequest {
        try request(baseURL: baseURL, model: model, apiKey: apiKey, body: body)
    }

    static func request(baseURL: URL, model: String, apiKey: String, body: RequestBody) throws -> URLRequest {
        var base = baseURL.absoluteString
        if !base.hasSuffix("/") { base += "/" }
        guard let url = URL(string: base + model + ":generateContent") else {
            throw Failure(message: "Invalid Gemini URL")
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 45
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(apiKey, forHTTPHeaderField: "x-goog-api-key")
        request.httpBody = try JSONEncoder().encode(body)
        return request
    }

    struct RequestBody: Codable, Equatable {
        struct Content: Codable, Equatable { var parts: [Part] }
        struct Part: Codable, Equatable { var text: String? }
        struct Tool: Codable, Equatable {
            struct GoogleSearch: Codable, Equatable {}
            var googleSearch: GoogleSearch
        }
        struct GenerationConfig: Codable, Equatable { var temperature: Double }
        var contents: [Content]
        var tools: [Tool]?
        var generationConfig: GenerationConfig?
    }

    // MARK: - AI v2 Helpers (Static, Testable)

    static func searchLead(for ai: AiSettings, pages: Int) -> String {
        if ai.searchKind == .builtIn {
            return "Use the search tool to find accurate information."
        }
        if pages > 0 {
            return "Use the search results provided below to find accurate information."
        }
        return "Answer from what you reliably know; write \"N/A\" for anything you are not sure of."
    }

    static func providerLabel(_ ai: AiSettings) -> String {
        switch ai.provider {
        case .gemini: "Gemini"
        case .deepSeek: "DeepSeek"
        case .qwen: "Qwen"
        case .custom: ai.modelId.isEmpty ? "Custom" : ai.modelId
        }
    }

    static func reasoningEffort(_ thinking: AiThinking) -> String? {
        switch thinking {
        case .off: nil
        case .low: "low"
        case .medium: "medium"
        case .high: "high"
        }
    }

    static func thinkingBudget(_ thinking: AiThinking) -> Int {
        switch thinking {
        case .off: 0
        case .low: 1_024
        case .medium: 4_096
        case .high: 8_192
        }
    }

    static func anthropicBase(_ base: String) -> String {
        base.hasSuffix("/v1") ? base : "\(base)/v1"
    }

    static func anthropicBody(
        model: String,
        prompt: String,
        thinking: AiThinking,
        budgetThinking: Bool
    ) -> [String: Any] {
        var body: [String: Any] = [
            "model": model,
            "max_tokens": anthropicMaxTokens,
            "messages": [["role": "user", "content": prompt]],
        ]
        guard let effort = reasoningEffort(thinking) else { return body }
        if budgetThinking {
            body["thinking"] = [
                "type": "enabled",
                "budget_tokens": thinkingBudget(thinking),
            ]
        } else {
            body["thinking"] = ["type": "adaptive"]
            body["output_config"] = ["effort": effort]
        }
        return body
    }

    static func rejectsAdaptiveThinking(_ error: Failure) -> Bool {
        guard error.httpCode == 400 else { return false }
        return adaptiveThinkingWords.contains { error.message.localizedCaseInsensitiveContains($0) }
    }

    static func creditsKeywords(title: String, artist: String) -> String {
        let combined = title + artist
        for scalar in combined.unicodeScalars {
            let v = scalar.value
            if (0xAC00...0xD7AF).contains(v) { return "작사 작곡 프로듀서" }
            if (0x3040...0x30FF).contains(v) { return "作詞 作曲 編曲" }
            if (0x4E00...0x9FFF).contains(v) { return "作詞 作曲 製作人" }
        }
        return "songwriting credits producer"
    }

    static func contextBlock(_ hits: [SearchHit]) -> String {
        var seen = Set<String>()
        var kept: [SearchHit] = []
        kept.reserveCapacity(min(hits.count, maxPagesInPrompt))
        for hit in hits where seen.insert(hit.url).inserted {
            kept.append(hit)
            if kept.count == maxPagesInPrompt { break }
        }
        return kept.enumerated().map { index, hit in
            "[\(index + 1)] \(hit.title) (\(hit.url))\n\(String(hit.text.prefix(maxPageChars)))"
        }.joined(separator: "\n\n")
    }

    // MARK: - Response Parsers

    private struct Response: Decodable {
        struct Candidate: Decodable {
            struct Content: Decodable { var parts: [RequestBody.Part]? }
            var content: Content?
        }
        var candidates: [Candidate]?
    }

    static func parseAbout(status: Int, body: Data) throws -> [Value] {
        let raw = try text(status: status, body: body, requireNonEmpty: false)
        return parseTagged(raw)
    }

    static func text(status: Int, body: Data, requireNonEmpty: Bool) throws -> String {
        guard (200..<300).contains(status) else {
            throw Failure(message: "Gemini API error (\(status)): \(errorMessage(body))", httpCode: status)
        }
        guard let response = try? JSONDecoder().decode(Response.self, from: body),
              let parts = response.candidates?.first?.content?.parts,
              !parts.isEmpty
        else {
            throw Failure(message: "No content in Gemini response")
        }
        let textParts = parts.compactMap(\.text)
        guard !textParts.isEmpty else {
            throw Failure(message: "No content in Gemini response")
        }
        let joined = textParts.joined()
        if requireNonEmpty && joined.isEmpty {
            throw Failure(message: "No content in Gemini response")
        }
        return joined
    }

    static func parseAsk(_ raw: String, fallbackQuestion: String) -> AskAnswer {
        let title = tagContent(raw, "TITLE")
        let answer = tagContent(raw, "ANSWER")
        let question = fallbackQuestion.trimmingCharacters(in: .whitespacesAndNewlines)
        switch (title, answer) {
        case let (t?, a?): return AskAnswer(title: t, answer: a)
        case let (nil, a?): return AskAnswer(title: question, answer: a)
        case let (t?, nil): return AskAnswer(title: t, answer: stripTags(raw))
        case (nil, nil): return AskAnswer(title: question, answer: stripTags(raw))
        }
    }

    static func cleanAskTitle(_ raw: String, fallbackQuestion: String) -> String {
        let ws = CharacterSet.whitespacesAndNewlines
        guard var line = stripTags(raw).components(separatedBy: .newlines)
            .first(where: { !$0.trimmingCharacters(in: ws).isEmpty }) else {
            return fallbackQuestion.trimmingCharacters(in: ws)
        }
        line = line.trimmingCharacters(in: ws).trimmingCharacters(in: CharacterSet(charactersIn: "\"'“”‘’"))
        for suffix in [".", "?", "!", "。", "？", "！"] where line.hasSuffix(suffix) {
            line.removeLast(suffix.count)
        }
        line = line.trimmingCharacters(in: ws)
        return line.isEmpty ? fallbackQuestion.trimmingCharacters(in: ws) : line
    }

    static func parseReleaseNames(
        _ raw: String,
        shownTitle: String,
        shownArtist: String
    ) -> (title: String, artist: String)? {
        func extract(_ tag: String) -> String? {
            guard let content = tagContent(raw, tag),
                  let firstLine = content.components(separatedBy: .newlines).first
            else { return nil }
            let trimmed = firstLine
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .trimmingCharacters(in: CharacterSet(charactersIn: "\"“”《》「」"))
                .trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty,
                  trimmed.caseInsensitiveCompare("N/A") != .orderedSame,
                  trimmed.caseInsensitiveCompare("null") != .orderedSame
            else { return nil }
            return trimmed
        }
        guard let title = extract("TITLE"), let artist = extract("ARTIST") else { return nil }
        let sameTitle = title.caseInsensitiveCompare(shownTitle.trimmingCharacters(in: .whitespacesAndNewlines)) == .orderedSame
        let sameArtist = artist.caseInsensitiveCompare(shownArtist.trimmingCharacters(in: .whitespacesAndNewlines)) == .orderedSame
        if sameTitle && sameArtist { return nil }
        return (title, artist)
    }

    static func parseMemoryNarration(_ raw: String) -> MemoryNarration? {
        func clean(_ tag: String) -> String? {
            guard let text = tagContent(raw, tag) else { return nil }
            let value = text.replacingOccurrences(of: #"\s*\n\s*"#, with: " ", options: .regularExpression)
                .trimmingCharacters(in: CharacterSet(charactersIn: "\"“”").union(.whitespacesAndNewlines))
            return value.isEmpty ? nil : value
        }
        guard let narration = clean("NARRATION"), let question = clean("QUESTION"),
              question.hasSuffix("?") || question.hasSuffix("？") else { return nil }
        return MemoryNarration(narration: narration, question: question)
    }

    static func followsMemoryVoiceRules(_ text: MemoryNarration) -> Bool {
        func sentences(_ text: String) -> Int {
            let chars = Array(text)
            var count = 0, index = 0
            var pending = false
            while index < chars.count {
                let c = chars[index]
                if !c.isWhitespace { pending = true }
                let next = index + 1 < chars.count ? chars[index + 1] : nil
                if "。！？!?".contains(c) || (c == "." && !(next.map { "0123456789".contains($0) } ?? false)) {
                    while index + 1 < chars.count, "。！？!?.”’」）)".contains(chars[index + 1]) { index += 1 }
                    if pending { count += 1; pending = false }
                }
                index += 1
            }
            return count + (pending ? 1 : 0)
        }
        let narrationCount = sentences(text.narration), questionCount = sentences(text.question)
        let combined = "\(text.narration) \(text.question)".lowercased()
        return (1...2).contains(narrationCount) && (1...2).contains(questionCount)
            && !["memor", "remember", "记忆", "回忆"].contains(where: combined.contains)
    }

    static func errorMessage(_ body: Data) -> String {
        if let root = try? JSONSerialization.jsonObject(with: body) as? [String: Any] {
            if let errorObj = root["error"] as? [String: Any] {
                if let msg = (errorObj["message"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines), !msg.isEmpty {
                    return msg
                }
                if let detail = (errorObj["detail"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines), !detail.isEmpty {
                    return detail
                }
            }
            if let errorStr = (root["error"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines), !errorStr.isEmpty {
                return errorStr
            }
            if let msg = (root["message"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines), !msg.isEmpty {
                return msg
            }
            if let doubao = doubaoError(root) {
                return doubao
            }
        }
        return String(String(decoding: body, as: UTF8.self).prefix(200))
    }

    private static func doubaoError(_ root: [String: Any]) -> String? {
        guard let meta = root["ResponseMetadata"] as? [String: Any],
              let error = meta["Error"] as? [String: Any]
        else { return nil }
        let message = (error["Message"] as? String) ?? ""
        let code: String = if let c = error["Code"] as? String, !c.isEmpty {
            c
        } else if let c = error["CodeN"] as? String, !c.isEmpty {
            c
        } else if let n = error["CodeN"] as? Int {
            String(n)
        } else {
            ""
        }
        if message.isEmpty && code.isEmpty { return nil }
        if code.isEmpty { return message }
        return "\(message.isEmpty ? code : message) (\(code))"
    }

    static func networkMessage(_ error: URLError, label: String = "Gemini") -> String {
        switch error.code {
        case .notConnectedToInternet, .networkConnectionLost: "No internet connection"
        case .timedOut: "\(label) timed out"
        default: "Couldn't reach \(label)"
        }
    }

    static func parseTagged(_ raw: String) -> [Value] {
        let tags: [(String, String)] = [
            (SongAboutStore.Canon.creationTime, "CREATION_TIME"),
            (SongAboutStore.Canon.creationLocation, "CREATION_LOCATION"),
            (SongAboutStore.Canon.lyricist, "LYRICIST"),
            (SongAboutStore.Canon.composer, "COMPOSER"),
            (SongAboutStore.Canon.producer, "PRODUCER"),
            (SongAboutStore.Canon.review, "REVIEW"),
        ]
        return tags.compactMap { key, tag in
            guard let v = tagContent(raw, tag),
                  v.caseInsensitiveCompare("N/A") != .orderedSame,
                  v.caseInsensitiveCompare("null") != .orderedSame,
                  v != "无法获取"
            else { return nil }
            return Value(key: key, answer: v)
        }
    }

    static func tagContent(_ text: String, _ tag: String) -> String? {
        let t = NSRegularExpression.escapedPattern(for: tag)
        guard let open = text.range(of: #"\[\s*\#(t)\s*\]"#, options: [.regularExpression, .caseInsensitive])
        else { return nil }
        let rest = text[open.upperBound...]
        let close = rest.range(of: #"\[\s*/\s*\#(t)\s*\]"#, options: [.regularExpression, .caseInsensitive])
        let content = String(rest[..<(close?.lowerBound ?? rest.endIndex)])
        let stripped = stripTags(content)
        return stripped.isEmpty ? nil : stripped
    }

    static func stripTags(_ text: String) -> String {
        text.replacingOccurrences(of: #"\[\s*/?\s*[A-Z][A-Z0-9_]*\s*\]"#, with: "",
                                  options: [.regularExpression, .caseInsensitive])
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static var defaultBaseURL: URL {
        #if DEBUG
        if let raw = UserDefaults.standard.string(forKey: "geminiDebugBaseURL"), let url = URL(string: raw) {
            return url
        }
        #endif
        return baseURL
    }
}
