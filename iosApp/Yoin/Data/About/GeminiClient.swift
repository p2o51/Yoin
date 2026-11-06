import Foundation

/// About, Ask and album-memory calls shared with iOS: Android `data/remote/GeminiService`.
/// About and Ask use search grounding; memory titles and narration use only their supplied input.
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

    /// Android's private-journal title prompt keeps the writing's own language.
    func memoryTitle(apiKey: String, albumName: String, artist: String?, year: Int?,
                     writingKind: String, writingText: String) async throws -> String {
        let prompt = Self.memoryTitlePrompt(albumName: albumName, artist: artist, year: year,
                                            writingKind: writingKind, writingText: writingText)
        let raw = try await memoryText(apiKey: apiKey, prompt: prompt, temperature: 0.2, timeout: 45)
        var title = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if title.hasPrefix("\""), title.hasSuffix("\""), title.count >= 2 {
            title = String(title.dropFirst().dropLast())
        }
        title = title.replacingOccurrences(of: "\n", with: " ")
        guard !title.isEmpty else { throw Failure(message: "No content in Gemini response") }
        return title
    }

    struct MemoryNarration: Equatable, Sendable {
        var narration: String
        var question: String
        var encoded: String { "[NARRATION]\(narration)[/NARRATION]\n[QUESTION]\(question)[/QUESTION]" }
    }

    /// No grounding or listener writing in this request; Android's eight-second narration timeout.
    func memoryNarration(apiKey: String, targetLanguage: String, facts: [String],
                         alreadySaid: String? = nil) async throws -> MemoryNarration {
        let prompt = Self.memoryNarrationPrompt(targetLanguage: targetLanguage, facts: facts, alreadySaid: alreadySaid)
        let raw = try await memoryText(apiKey: apiKey, prompt: prompt, temperature: 0.4, timeout: 8)
        guard let result = Self.parseMemoryNarration(raw), Self.followsMemoryVoiceRules(result) else {
            throw Failure(message: "No usable narration in Gemini response")
        }
        return result
    }

    private func memoryText(apiKey: String, prompt: String, temperature: Double, timeout: TimeInterval) async throws -> String {
        var request = try Self.request(baseURL: baseURL, apiKey: apiKey,
                                       body: RequestBody(contents: [.init(parts: [.init(text: prompt)])], tools: nil,
                                                         generationConfig: .init(temperature: temperature)))
        request.timeoutInterval = timeout
        let (status, data) = try await send(request)
        return try Self.text(status: status, body: data, requireNonEmpty: true)
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

    /// Android MemorySentences.split and MemoryNarration.followsVoiceRules (decimals aren't stops).
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

    /// Android buildMemoryTitlePrompt, word for word.
    static func memoryTitlePrompt(albumName: String, artist: String?, year: Int?,
                                  writingKind: String, writingText: String) -> String {
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

    /// Android buildMemoryNarrationPrompt (v2), word for word.
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
