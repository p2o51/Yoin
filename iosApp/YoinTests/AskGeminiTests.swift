import XCTest
@testable import Yoin

final class GeminiAskClientTests: XCTestCase {
    func test_should_sendAndroidAskPrompt_when_asking() throws {
        let prompt = GeminiClient.askPrompt(title: "Starlight", artist: "Muse", album: "BH&R",
                                            question: "Who plays the piano?", targetLanguage: "Japanese")
        XCTAssertTrue(prompt.hasPrefix("Answer the following question about the song \"Starlight\" from the album \"BH&R\" by Muse.\nUse the search tool"))
        XCTAssertTrue(prompt.contains("Write the title and answer in Japanese."))
        XCTAssertTrue(prompt.contains("[TITLE]\nA concise headline") && prompt.contains("[/ANSWER]"))
        XCTAssertTrue(prompt.hasSuffix("\n\nQuestion: Who plays the piano?"))
        XCTAssertFalse(prompt.contains("\n        "), "indentation stripped like trimIndent")

        let title = GeminiClient.askTitlePrompt(title: "Starlight", artist: "Muse", album: "BH&R",
                                                question: "Q", targetLanguage: "English")
        XCTAssertTrue(title.hasPrefix("Write a concise visual heading for an in-progress Gemini answer about this song.\nWrite the heading in English.\n\nSong: Starlight\nArtist: Muse\nAlbum: BH&R\nQuestion: Q\n"))
        XCTAssertTrue(title.hasSuffix("Do not\nanswer the question."))
    }

    func test_should_carryTemperatureOnlyOnTitleCall_when_encoding() throws {
        let titleBody = GeminiClient.RequestBody(contents: [.init(parts: [.init(text: "x")])], tools: nil,
                                                 generationConfig: .init(temperature: 0.2))
        let json = try JSONSerialization.jsonObject(with: JSONEncoder().encode(titleBody)) as! [String: Any]
        XCTAssertEqual(Set(json.keys), ["contents", "generationConfig"])
        XCTAssertEqual((json["generationConfig"] as? [String: Any])?["temperature"] as? Double, 0.2)
    }

    func test_should_parseTitleAndAnswer_when_tagged() {
        XCTAssertEqual(GeminiClient.parseAsk("[TITLE] Piano on Starlight [/TITLE]\n[ANSWER]Matt Bellamy.[/ANSWER]",
                                             fallbackQuestion: " Who? "),
                       .init(title: "Piano on Starlight", answer: "Matt Bellamy."))
        XCTAssertEqual(GeminiClient.parseAsk("[answer]Only an answer", fallbackQuestion: " Who? "),
                       .init(title: "Who?", answer: "Only an answer"))
        XCTAssertEqual(GeminiClient.parseAsk("Plain prose [REF]", fallbackQuestion: "Who?"),
                       .init(title: "Who?", answer: "Plain prose"))
    }

    func test_should_cleanHeading_when_titleCallAnswers() {
        XCTAssertEqual(GeminiClient.cleanAskTitle("\n  \"The piano part?\"  \nmore", fallbackQuestion: "Q"), "The piano part")
        XCTAssertEqual(GeminiClient.cleanAskTitle("钢琴是谁弹的？", fallbackQuestion: "Q"), "钢琴是谁弹的")
        XCTAssertEqual(GeminiClient.cleanAskTitle("  ", fallbackQuestion: " Q "), "Q")
    }
}

@MainActor
final class AskGeminiLoaderTests: XCTestCase {
    private var dir: URL!
    private var defaults: UserDefaults!
    private var keychain: GeminiKeychain!

    override func setUp() async throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defaults = UserDefaults(suiteName: "ask-tests-\(UUID().uuidString)")
        keychain = GeminiKeychain(service: "com.gpo.yoin.gemini.asktests.\(UUID().uuidString)")
        GeminiStubProtocol.requests = []
        GeminiStubProtocol.handler = nil
    }

    override func tearDown() async throws {
        keychain.delete()
        try? FileManager.default.removeItem(at: dir)
    }

    private func loader(_ store: SongAboutStore, key: String?) -> SongAboutLoader {
        let settings = GeminiSettings(keychain: keychain, defaults: defaults)
        if let key { settings.saveKey(key) }
        let base = URL(string: "http://127.0.0.1:8790/")!
        return SongAboutLoader(store: store, settings: settings,
                               client: { GeminiClient(baseURL: base, session: GeminiStubProtocol.session) })
    }

    private func settle(_ l: SongAboutLoader) async {
        for _ in 0..<200 {
            if case .loading = l.askState { try? await Task.sleep(for: .milliseconds(10)) } else { return }
        }
    }

    func test_should_saveAskRow_when_geminiAnswers() async {
        GeminiStubProtocol.handler = { r in
            let body = String(decoding: r.httpBody ?? Data(), as: UTF8.self)
            return (200, geminiBody(body.contains("[ANSWER]") ? "[TITLE]Piano player[/TITLE][ANSWER]Matt.[/ANSWER]" : "Who plays piano"))
        }
        let store = SongAboutStore(fileURL: dir.appendingPathComponent("a.json"))
        let l = loader(store, key: "k")
        l.askFocused()
        XCTAssertEqual(l.askState, .focused)
        l.ask(title: "Starlight", artist: "Muse", album: "BH&R", question: "  Who plays the piano?  ")
        if case .loading(let t, _) = l.askState { XCTAssertEqual(t, "Who plays the piano?") } else { XCTFail() }
        await settle(l)
        XCTAssertEqual(l.askState, .idle)
        XCTAssertEqual(GeminiStubProtocol.requests.count, 2, "a heading call and the grounded answer")

        let k = SongAboutStore.key(title: "Starlight", artist: "Muse", album: "BH&R")
        let row = store.entries(k).first { $0.kind == "ask" }
        XCTAssertEqual(row?.entryKey, "who plays the piano?")
        XCTAssertEqual(row?.promptText, "Who plays the piano?")
        XCTAssertEqual(row?.titleText, "Piano player")
        XCTAssertEqual(row?.answerText, "Matt.")

        // Asked again: the answer is replaced, the first createdAt kept.
        let created = row?.createdAt
        store.saveAsk(title: "Starlight", artist: "Muse", album: "BH&R", question: "who plays  the piano?",
                      reply: .init(title: "T", answer: "B"), now: Date(timeIntervalSinceNow: 60))
        let asks = store.entries(k).filter { $0.kind == "ask" }
        XCTAssertEqual(asks.count, 1)
        XCTAssertEqual(asks.first?.answerText, "B")
        XCTAssertEqual(asks.first?.createdAt, created)
    }

    func test_should_showAndroidError_when_noKey() {
        let l = loader(SongAboutStore(fileURL: dir.appendingPathComponent("a.json")), key: nil)
        l.ask(title: "Starlight", artist: "Muse", album: "BH&R", question: "Q")
        XCTAssertEqual(l.askState, .error("Gemini API key missing"))
        XCTAssertTrue(GeminiStubProtocol.requests.isEmpty)
        l.dismissAskError()
        XCTAssertEqual(l.askState, .idle)
    }

    func test_should_showMessage_when_geminiRefuses() async {
        GeminiStubProtocol.handler = { _ in (403, Data(#"{"error":{"message":"Permission denied"}}"#.utf8)) }
        let l = loader(SongAboutStore(fileURL: dir.appendingPathComponent("a.json")), key: "k")
        l.ask(title: "Starlight", artist: "Muse", album: "BH&R", question: "Q")
        await settle(l)
        XCTAssertEqual(l.askState, .error("Gemini API error (403): Permission denied"))
    }

    func test_should_dropStaleAnswer_when_songChanges() async {
        GeminiStubProtocol.handler = { _ in (500, Data()) }
        let l = loader(SongAboutStore(fileURL: dir.appendingPathComponent("a.json")), key: "k")
        l.ask(title: "Starlight", artist: "Muse", album: "BH&R", question: "Q")
        l.resetAsk()
        try? await Task.sleep(for: .milliseconds(300))
        XCTAssertEqual(l.askState, .idle, "another song's bar isn't told about the old answer")
    }
}
