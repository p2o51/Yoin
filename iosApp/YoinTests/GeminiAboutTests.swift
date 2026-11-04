import XCTest
@testable import Yoin

/// Answers a request from a closure (no network); records what was sent.
final class GeminiStubProtocol: URLProtocol {
    nonisolated(unsafe) static var handler: ((URLRequest) -> (Int, Data))?
    nonisolated(unsafe) static var requests: [URLRequest] = []

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        var r = request
        if r.httpBody == nil, let stream = r.httpBodyStream {
            stream.open()
            var data = Data()
            var buf = [UInt8](repeating: 0, count: 4096)
            while stream.hasBytesAvailable {
                let n = stream.read(&buf, maxLength: buf.count)
                if n <= 0 { break }
                data.append(buf, count: n)
            }
            stream.close()
            r.httpBody = data
        }
        Self.requests.append(r)
        let (status, body) = Self.handler?(r) ?? (500, Data())
        let response = HTTPURLResponse(url: r.url!, statusCode: status, httpVersion: nil, headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: body)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}

    static var session: URLSession {
        let c = URLSessionConfiguration.ephemeral
        c.protocolClasses = [GeminiStubProtocol.self]
        return URLSession(configuration: c)
    }
}

/// Android-shaped generateContent body around a tagged answer.
func geminiBody(_ text: String) -> Data {
    let payload: [String: Any] = ["candidates": [["content": ["parts": [["text": text]], "role": "model"]]]]
    return try! JSONSerialization.data(withJSONObject: payload)
}

let taggedAnswer = """
[CREATION_TIME]
2006
[/CREATION_TIME]

[CREATION_LOCATION]
N/A
[/CREATION_LOCATION]

[lyricist] Matt Bellamy [/lyricist]

[ COMPOSER ]
Matt Bellamy
[ / COMPOSER ]

[PRODUCER]
Rich Costey and **Muse**
[/PRODUCER]

[REVIEW]
A soaring anthem.
"""

final class GeminiClientTests: XCTestCase {
    func test_should_buildAndroidShapedRequest_when_askingAbout() throws {
        let r = try GeminiClient.aboutRequest(baseURL: GeminiClient.baseURL, apiKey: "test-key", title: "Starlight",
                                              artist: "Muse", album: "Black Holes and Revelations",
                                              targetLanguage: "Japanese")
        XCTAssertEqual(r.httpMethod, "POST")
        XCTAssertEqual(r.url?.absoluteString,
                       "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:generateContent")
        XCTAssertEqual(r.value(forHTTPHeaderField: "x-goog-api-key"), "test-key")
        XCTAssertEqual(r.value(forHTTPHeaderField: "Content-Type"), "application/json")
        XCTAssertFalse(r.url!.absoluteString.contains("test-key"), "the key never rides in the URL")

        let json = try JSONSerialization.jsonObject(with: r.httpBody!) as! [String: Any]
        XCTAssertEqual(Set(json.keys), ["contents", "tools"], "no generationConfig, as Android's encoder drops it")
        let tools = json["tools"] as! [[String: Any]]
        XCTAssertEqual(tools.count, 1)
        XCTAssertEqual((tools[0]["googleSearch"] as? [String: Any])?.isEmpty, true)
        let contents = json["contents"] as! [[String: Any]]
        let text = ((contents[0]["parts"] as! [[String: Any]])[0]["text"] as! String)
        XCTAssertTrue(text.hasPrefix("Search for and provide detailed information about the following song:\n\nSong: Starlight\nArtist: Muse\nAlbum: Black Holes and Revelations\n"))
        XCTAssertTrue(text.contains("answers in Japanese. Respond strictly"))
        for tag in ["CREATION_TIME", "CREATION_LOCATION", "LYRICIST", "COMPOSER", "PRODUCER", "REVIEW"] {
            XCTAssertTrue(text.contains("[\(tag)]\n") && text.contains("[/\(tag)]"), tag)
        }
        XCTAssertTrue(text.hasSuffix("Every field must include its corresponding tags."))
        XCTAssertFalse(text.contains("\n        "), "indentation stripped like trimIndent")
    }

    func test_should_appendModelPath_when_debugBaseHasNoTrailingSlash() throws {
        let r = try GeminiClient.aboutRequest(baseURL: URL(string: "http://127.0.0.1:8790")!, apiKey: "k", title: "a",
                                              artist: "b", album: "c", targetLanguage: "English")
        XCTAssertEqual(r.url?.absoluteString, "http://127.0.0.1:8790/gemini-3.1-flash-lite:generateContent")
    }

    func test_should_keepFoundFieldsInOrder_when_parsingTaggedAnswer() throws {
        let values = try GeminiClient.parseAbout(status: 200, body: geminiBody(taggedAnswer))
        XCTAssertEqual(values, [
            .init(key: "creation_time", answer: "2006"),
            .init(key: "lyricist", answer: "Matt Bellamy"),
            .init(key: "composer", answer: "Matt Bellamy"),
            .init(key: "producer", answer: "Rich Costey and **Muse**"),
            // Missing close tag: reads to the end.
            .init(key: "review", answer: "A soaring anthem."),
        ])
    }

    func test_should_dropPlaceholders_when_fieldUnknown() {
        let raw = "[LYRICIST]null[/LYRICIST][COMPOSER]无法获取[/COMPOSER][PRODUCER]n/a[/PRODUCER][REVIEW]  [/REVIEW]"
        XCTAssertEqual(GeminiClient.parseTagged(raw), [])
    }

    func test_should_reportAndroidMessage_when_httpError() {
        let body = Data(#"{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}"#.utf8)
        XCTAssertThrowsError(try GeminiClient.parseAbout(status: 400, body: body)) { error in
            XCTAssertEqual((error as? GeminiClient.Failure)?.message,
                           "Gemini API error (400): API key not valid. Please pass a valid API key.")
        }
    }

    func test_should_reportNoContent_when_noCandidates() {
        XCTAssertThrowsError(try GeminiClient.parseAbout(status: 200, body: Data(#"{"candidates":[]}"#.utf8))) { error in
            XCTAssertEqual((error as? GeminiClient.Failure)?.message, "No content in Gemini response")
        }
    }
}

@MainActor
final class SongAboutTests: XCTestCase {
    private var dir: URL!
    private var defaults: UserDefaults!
    private var keychain: GeminiKeychain!

    override func setUp() async throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        let suite = "gemini-tests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)
        keychain = GeminiKeychain(service: "com.gpo.yoin.gemini.tests.\(UUID().uuidString)")
        GeminiStubProtocol.requests = []
        GeminiStubProtocol.handler = nil
    }

    override func tearDown() async throws {
        keychain.delete()
        try? FileManager.default.removeItem(at: dir)
    }

    func test_should_matchAndroidNormalize_when_keying() {
        XCTAssertEqual(SongAboutStore.normalize("  Fake   Love (feat. X)  "), "fake love")
        XCTAssertEqual(SongAboutStore.key(title: "Starlight", artist: "MUSE", album: "BH&R"),
                       SongAboutStore.key(title: "starlight ", artist: "muse", album: "bh&r"))
    }

    func test_should_orderCanonicalThenNewestAsk_when_readingBack() {
        let url = dir.appendingPathComponent("about.json")
        let store = SongAboutStore(fileURL: url)
        store.saveCanonical(title: "Starlight", artist: "Muse", album: "BH&R",
                            values: [.init(key: "review", answer: "R"), .init(key: "creation_time", answer: "2006")],
                            language: "English")
        let k = SongAboutStore.key(title: "Starlight", artist: "Muse", album: "BH&R")
        func ask(_ q: String, _ at: Int64) -> SongAboutStore.Entry {
            .init(titleKey: k.title, artistKey: k.artist, albumKey: k.album, titleDisplay: "Starlight",
                  artistDisplay: "Muse", albumDisplay: "BH&R", kind: "ask", entryKey: q, promptText: q,
                  titleText: "T \(q)", answerText: "A", createdAt: at, updatedAt: at, language: "English")
        }
        store.upsert([ask("old", 1), ask("new", 2)])

        let reloaded = SongAboutStore(fileURL: url)
        reloaded.language = { "English" }
        XCTAssertTrue(reloaded.hasCanonical(k))
        XCTAssertEqual(reloaded.entries(k).map(\.entryKey),
                       ["creation_time", "creation_location", "lyricist", "composer", "producer", "review", "new", "old"])
        XCTAssertEqual(reloaded.entries(k).first?.answerText, "2006")
        XCTAssertEqual(reloaded.entries(k)[1].answerText, "", "missing fields stored blank, as Android")
        XCTAssertEqual(reloaded.entries(k).first?.language, "English")
    }

    /// Codex C17: the cache is per language; rows of unknown language (from before) are not shown.
    func test_should_showOnlyThisLanguagesRows_when_languageChanges() {
        let store = SongAboutStore(fileURL: dir.appendingPathComponent("lang.json"))
        var language = "English"
        store.language = { language }
        store.saveCanonical(title: "Starlight", artist: "Muse", album: "BH&R",
                            values: [.init(key: "review", answer: "In English")], language: "English")
        store.saveAsk(title: "Starlight", artist: "Muse", album: "BH&R", question: "Who?",
                      reply: .init(title: "T", answer: "Asked in English"), language: "English")
        let k = SongAboutStore.key(title: "Starlight", artist: "Muse", album: "BH&R")
        let old = SongAboutStore.Entry(titleKey: k.title, artistKey: k.artist, albumKey: k.album, titleDisplay: "",
                                       artistDisplay: "", albumDisplay: "", kind: "ask", entryKey: "legacy",
                                       promptText: "legacy", titleText: nil, answerText: "unknown language",
                                       createdAt: 1, updatedAt: 1, language: nil)
        store.upsert([old])
        XCTAssertTrue(store.hasCanonical(k))
        XCTAssertFalse(store.entries(k).contains { $0.entryKey == "legacy" })
        XCTAssertEqual(store.entries(k).first { $0.kind == "ask" }?.language, "English")

        language = "Japanese"
        XCTAssertFalse(store.hasCanonical(k), "Japanese asks Gemini again")
        XCTAssertTrue(store.entries(k).isEmpty, "no English answers under Japanese")
    }

    func test_should_signalSync_when_geminiAnswers_butNotForSyncedRows() {
        var signals = 0
        SongAboutStore.onLocalChange = { signals += 1 }
        defer { SongAboutStore.onLocalChange = nil }
        let store = SongAboutStore(fileURL: dir.appendingPathComponent("sig.json"))
        store.saveCanonical(title: "A", artist: "B", album: "C", values: [], language: "English")
        store.saveAsk(title: "A", artist: "B", album: "C", question: "Q", reply: .init(title: "T", answer: "X"),
                      language: "English")
        XCTAssertEqual(signals, 2)
        let k = SongAboutStore.key(title: "A", artist: "B", album: "C")
        XCTAssertTrue(store.syncUpsert(.init(titleKey: k.title, artistKey: k.artist, albumKey: k.album, titleDisplay: "A",
                                             artistDisplay: "B", albumDisplay: "C", kind: "ask", entryKey: "r",
                                             promptText: "r", titleText: nil, answerText: "remote", createdAt: 1,
                                             updatedAt: 1, language: "English")))
        XCTAssertEqual(signals, 2, "a row from sync is not a local change")
    }

    private func loader(store: SongAboutStore, key: String?) -> SongAboutLoader {
        let settings = GeminiSettings(keychain: keychain, defaults: defaults)
        if let key { settings.saveKey(key) }
        let base = URL(string: "http://127.0.0.1:8790/")!
        return SongAboutLoader(store: store, settings: settings,
                               client: { GeminiClient(baseURL: base, session: GeminiStubProtocol.session) })
    }

    func test_should_sayNoKey_when_noKeySaved() async {
        let l = loader(store: SongAboutStore(fileURL: dir.appendingPathComponent("a.json")), key: nil)
        await l.ensure(title: "Starlight", artist: "Muse", album: "BH&R")
        XCTAssertEqual(l.phase(SongAboutStore.key(title: "Starlight", artist: "Muse", album: "BH&R")), .noKey)
        XCTAssertTrue(GeminiStubProtocol.requests.isEmpty)
    }

    func test_should_askOnceAndCache_when_keySaved() async {
        GeminiStubProtocol.handler = { _ in (200, geminiBody(taggedAnswer)) }
        let store = SongAboutStore(fileURL: dir.appendingPathComponent("a.json"))
        let l = loader(store: store, key: "  test-key  ")
        l.settings.saveLanguage("Korean")
        await l.ensure(title: "Starlight", artist: "Muse", album: "BH&R")
        await l.ensure(title: "starlight", artist: "muse", album: "bh&r")

        XCTAssertEqual(GeminiStubProtocol.requests.count, 1, "the cached answer stands")
        let sent = GeminiStubProtocol.requests[0]
        XCTAssertEqual(sent.value(forHTTPHeaderField: "x-goog-api-key"), "test-key")
        XCTAssertTrue(String(decoding: sent.httpBody ?? Data(), as: UTF8.self).contains("answers in Korean"))
        let k = SongAboutStore.key(title: "Starlight", artist: "Muse", album: "BH&R")
        XCTAssertEqual(l.phase(k), .idle)
        XCTAssertEqual(store.entries(k).first { $0.entryKey == "lyricist" }?.answerText, "Matt Bellamy")

        await l.ensure(title: "Starlight", artist: "Muse", album: "BH&R", retry: true)
        XCTAssertEqual(GeminiStubProtocol.requests.count, 2, "retry asks again")
    }

    func test_should_fail_when_geminiRefuses() async {
        GeminiStubProtocol.handler = { _ in (403, Data(#"{"error":{"message":"Permission denied"}}"#.utf8)) }
        let l = loader(store: SongAboutStore(fileURL: dir.appendingPathComponent("a.json")), key: "k")
        await l.ensure(title: "Starlight", artist: "Muse", album: "BH&R")
        XCTAssertEqual(l.phase(SongAboutStore.key(title: "Starlight", artist: "Muse", album: "BH&R")),
                       .failed("Gemini API error (403): Permission denied"))
    }

    func test_should_notAsk_when_artistBlank() async {
        let l = loader(store: SongAboutStore(fileURL: dir.appendingPathComponent("a.json")), key: "k")
        await l.ensure(title: "Starlight", artist: " ", album: "")
        XCTAssertTrue(GeminiStubProtocol.requests.isEmpty)
    }

    func test_should_storeKeyInKeychain_when_saved() throws {
        let probe = keychain.save("probe")
        try XCTSkipIf(probe == errSecMissingEntitlement, "unsigned simulator build: no Keychain")
        keychain.delete()
        let s = GeminiSettings(keychain: keychain, defaults: defaults)
        XCTAssertFalse(s.hasKey)
        s.saveKey("  abc  ")
        XCTAssertEqual(keychain.load(), "abc")
        XCTAssertEqual(GeminiSettings(keychain: keychain, defaults: defaults).apiKey, "abc")
        s.saveLanguage("Klingon")
        XCTAssertEqual(s.targetLanguage, "English")
    }
}
