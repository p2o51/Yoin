import Foundation

/// The song About call to Gemini: Android `data/remote/GeminiService`
/// `generateCanonicalAbout` — same model, same prompt, same tagged answer
/// format, one grounded (`googleSearch`) `generateContent` request.
///
/// The key travels in the `x-goog-api-key` header rather than Android's
/// `?key=` query (Gemini accepts both), so it never sits in a URL that a
/// log, an error or `URLError.failingURL` could carry. Nothing here logs.
struct GeminiClient: Sendable {
    static let model = "gemini-3.1-flash-lite"
    static let baseURL = URL(string: "https://generativelanguage.googleapis.com/v1beta/models/")!

    var baseURL: URL = GeminiClient.defaultBaseURL
    var session: URLSession = .shared

    /// One About field: `SongAboutStore.Canon` key and Gemini's answer.
    struct Value: Equatable, Sendable {
        var key: String
        var answer: String
    }

    struct Failure: LocalizedError, Equatable {
        var message: String
        var errorDescription: String? { message }
    }

    /// The six canonical fields Gemini found (N/A ones dropped), in
    /// `SongAboutStore.Canon.order`.
    func canonicalAbout(apiKey: String, title: String, artist: String, album: String,
                        targetLanguage: String) async throws -> [Value] {
        let request = try Self.aboutRequest(baseURL: baseURL, apiKey: apiKey, title: title, artist: artist,
                                            album: album, targetLanguage: targetLanguage)
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as URLError where error.code == .cancelled {
            throw CancellationError()
        } catch let error as URLError {
            // URLError's own text is safe, its userInfo (failing URL) isn't shown.
            throw Failure(message: Self.networkMessage(error))
        }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        return try Self.parseAbout(status: status, body: data)
    }

    /// Ask Gemini's reply: a short heading and the answer (Android `AskAnswer`).
    struct AskAnswer: Equatable, Sendable {
        var title: String
        var answer: String
    }

    /// Android `askAboutSong`: one grounded call, a `[TITLE]` and an `[ANSWER]`.
    func ask(apiKey: String, title: String, artist: String, album: String, question: String,
             targetLanguage: String) async throws -> AskAnswer {
        let prompt = Self.askPrompt(title: title, artist: artist, album: album, question: question,
                                    targetLanguage: targetLanguage)
        let request = try Self.request(baseURL: baseURL, apiKey: apiKey,
                                       body: RequestBody(contents: [.init(parts: [.init(text: prompt)])],
                                                         tools: [.init(googleSearch: .init())]))
        let (status, data) = try await send(request)
        return Self.parseAsk(try Self.text(status: status, body: data, requireNonEmpty: true), fallbackQuestion: question)
    }

    /// Android `generateAskTitle`: the heading shown while the answer is on
    /// the way. No search, temperature 0.2.
    func askTitle(apiKey: String, title: String, artist: String, album: String, question: String,
                  targetLanguage: String) async throws -> String {
        let prompt = Self.askTitlePrompt(title: title, artist: artist, album: album, question: question,
                                         targetLanguage: targetLanguage)
        let request = try Self.request(baseURL: baseURL, apiKey: apiKey,
                                       body: RequestBody(contents: [.init(parts: [.init(text: prompt)])], tools: nil,
                                                         generationConfig: .init(temperature: 0.2)))
        let (status, data) = try await send(request)
        return Self.cleanAskTitle(try Self.text(status: status, body: data, requireNonEmpty: true),
                                  fallbackQuestion: question)
    }

    private func send(_ request: URLRequest) async throws -> (Int, Data) {
        do {
            let (data, response) = try await session.data(for: request)
            return ((response as? HTTPURLResponse)?.statusCode ?? 0, data)
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as URLError where error.code == .cancelled {
            throw CancellationError()
        } catch let error as URLError {
            throw Failure(message: Self.networkMessage(error))
        }
    }

    // MARK: Request

    /// POST `<base><model>:generateContent`, JSON body as Android's encoder
    /// writes it (`encodeDefaults = false`): the prompt and the search tool,
    /// no `generationConfig` (its 0.8 default is dropped).
    static func aboutRequest(baseURL: URL, apiKey: String, title: String, artist: String, album: String,
                             targetLanguage: String) throws -> URLRequest {
        // Concatenated, not resolved: "gemini-3.1-flash-lite:…" alone parses as a scheme.
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
        let body = RequestBody(contents: [.init(parts: [.init(text: aboutPrompt(title: title, artist: artist,
                                                                                album: album,
                                                                                targetLanguage: targetLanguage))])],
                               tools: [.init(googleSearch: .init())])
        request.httpBody = try JSONEncoder().encode(body)
        return request
    }

    /// POST `<base><model>:generateContent` with [body].
    static func request(baseURL: URL, apiKey: String, body: RequestBody) throws -> URLRequest {
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
        struct Part: Codable, Equatable { var text: String }
        struct Tool: Codable, Equatable {
            struct GoogleSearch: Codable, Equatable {}
            var googleSearch: GoogleSearch
        }
        struct GenerationConfig: Codable, Equatable { var temperature: Double }
        var contents: [Content]
        var tools: [Tool]?
        var generationConfig: GenerationConfig?
    }

    /// Android `buildPrompt`, word for word.
    static func aboutPrompt(title: String, artist: String, album: String, targetLanguage: String) -> String {
        """
        Search for and provide detailed information about the following song:

        Song: \(title)
        Artist: \(artist)
        Album: \(album)

        Use the search tool to find accurate information. Write all human-readable
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

    /// Android `buildAskPrompt`, word for word.
    static func askPrompt(title: String, artist: String, album: String, question: String,
                          targetLanguage: String) -> String {
        """
        Answer the following question about the song "\(title)" from the album "\(album)" by \(artist).
        Use the search tool to find accurate, up-to-date information.
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

    /// Android `buildAskTitlePrompt`, word for word.
    static func askTitlePrompt(title: String, artist: String, album: String, question: String,
                               targetLanguage: String) -> String {
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

    // MARK: Response

    private struct Response: Decodable {
        struct Candidate: Decodable {
            struct Content: Decodable { var parts: [RequestBody.Part]? }
            var content: Content?
        }
        var candidates: [Candidate]?
    }

    private struct ErrorResponse: Decodable {
        struct Detail: Decodable { var message: String? }
        var error: Detail?
    }

    /// Android's error texts: `Gemini API error (<code>): <message>`, then
    /// "No content in Gemini response".
    static func parseAbout(status: Int, body: Data) throws -> [Value] {
        guard (200..<300).contains(status) else {
            throw Failure(message: "Gemini API error (\(status)): \(errorMessage(body))")
        }
        guard let response = try? JSONDecoder().decode(Response.self, from: body),
              let text = response.candidates?.first?.content?.parts?.first?.text
        else { throw Failure(message: "No content in Gemini response") }
        return parseTagged(text)
    }

    /// The reply's first text part (Android's ask calls also refuse an empty one).
    static func text(status: Int, body: Data, requireNonEmpty: Bool) throws -> String {
        guard (200..<300).contains(status) else {
            throw Failure(message: "Gemini API error (\(status)): \(errorMessage(body))")
        }
        guard let response = try? JSONDecoder().decode(Response.self, from: body),
              let text = response.candidates?.first?.content?.parts?.first?.text,
              !(requireNonEmpty && text.isEmpty)
        else { throw Failure(message: "No content in Gemini response") }
        return text
    }

    /// Android `parseAskResponse`: both tags, else the question as the
    /// heading, else the whole reply with its tag markers stripped.
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

    /// Android `cleanAskTitle`: the first non-blank line, quotes trimmed, one
    /// of each trailing mark dropped in Android's order; else the question.
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

    static func errorMessage(_ body: Data) -> String {
        if let e = try? JSONDecoder().decode(ErrorResponse.self, from: body), let m = e.error?.message { return m }
        return String(String(decoding: body, as: UTF8.self).prefix(200))
    }

    static func networkMessage(_ error: URLError) -> String {
        switch error.code {
        case .notConnectedToInternet, .networkConnectionLost: "No internet connection"
        case .timedOut: "Gemini timed out"
        default: "Couldn't reach Gemini"
        }
    }

    /// Android `parseTaggedResponse`: only fields with real content, in order.
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

    /// Android `extractTagContent`: case- and space-tolerant tags; a missing
    /// close tag reads to the end; leftover markers stripped; nil when blank.
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

    /// `-geminiDebugBaseURL http://127.0.0.1:8790/` (DEBUG only): a local
    /// fake endpoint for QA without a real key.
    static var defaultBaseURL: URL {
        #if DEBUG
        if let raw = UserDefaults.standard.string(forKey: "geminiDebugBaseURL"), let url = URL(string: raw) {
            return url
        }
        #endif
        return baseURL
    }
}
