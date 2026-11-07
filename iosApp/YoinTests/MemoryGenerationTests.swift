import XCTest
@testable import Yoin

/// Memory titles and narration through Gemini (Memories/MemoryGeneration, Android GeminiService memory
/// prompts): once per album and language, Android's synced form, the local copy when there is none.
/// Gemini is GeminiStubProtocol: no real request leaves.
@MainActor
final class MemoryGenerationTests: XCTestCase {
    private var dir: URL!
    private var defaults: UserDefaults!
    private var keychain: GeminiKeychain!
    private let now: Int64 = 1_762_000_000_000

    override func setUp() async throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defaults = UserDefaults(suiteName: "memory-gen-\(UUID().uuidString)")
        keychain = GeminiKeychain(service: "com.gpo.yoin.gemini.memory.\(UUID().uuidString)")
        GeminiStubProtocol.requests = []
        GeminiStubProtocol.handler = { r in
            let body = String(decoding: r.httpBody ?? Data(), as: UTF8.self)
            if body.contains("titling a private music-journal entry") { return (200, geminiBody("Night Swim")) }
            return (200, geminiBody("[NARRATION]3 plays since March, the last one today.[/NARRATION]\n"
                                    + "[QUESTION]You gave it a 9.5. What earned it?[/QUESTION]"))
        }
    }

    override func tearDown() async throws {
        keychain.delete()
        try? FileManager.default.removeItem(at: dir)
    }

    private func settings(key: String?) -> GeminiSettings {
        let s = GeminiSettings(keychain: keychain, defaults: defaults)
        if let key { s.saveKey(key) }
        s.saveLanguage("English")
        return s
    }

    private var client: GeminiClient {
        GeminiClient(baseURL: URL(string: "http://127.0.0.1:8790/")!, session: GeminiStubProtocol.session)
    }

    private func store() -> AlbumMemoryStore {
        AlbumMemoryStore(fileURL: dir.appendingPathComponent("m.json"), scopes: AccountScope())
    }

    /// One played, album-scored memory; [review] gives it a written review (the title's input).
    private func inputs(review: String? = nil) -> MemoryDeck.Inputs {
        let tracks = (0..<4).map { Track(id: MediaId(provider: "subsonic", rawId: "t\($0)"), number: $0 + 1,
                                         title: "T\($0)", durationSec: 200) }
        let album = Album(id: MediaId(provider: "subsonic", rawId: "al"), title: "Tide", artist: "Wave",
                          year: 2020, palette: CoverPalette(a: .red, b: .green, c: .blue), tracks: tracks)
        let m = AlbumMemory(album: album, totalTracks: 4, ratedTrackCount: 0, coverage: 0, averageSongRating: nil,
                            albumRating: 9.5, noteCount: 0, lastWrittenAt: now - 1000, playCount: 3,
                            firstPlayedAt: now - 40 * 86_400_000, lastPlayedAt: now - 1000,
                            isMemoryEligible: true)
        return MemoryDeck.Inputs(memories: [m], review: { _ in review.map { ($0, self.now - 2000) } })
    }

    private func input(_ x: MemoryDeck.Inputs) -> MemoryVoice.Input {
        MemoryVoice.Input(card: MemoryDeck.cards(x)[0], notes: [], now: now, targetLanguage: "English")
    }

    func test_should_saveAndroidsTaggedNarration_once_when_aKeyIsSet() async {
        let s = store(), x = inputs(), i = input(x)
        await MemoryGeneration().generate(i, account: "p1", client: client, store: s, settings: settings(key: "k"),
                                          onSaved: {}, isCurrent: { true })
        let row = s.memory(provider: "subsonic", entityId: "al")
        XCTAssertEqual(row?.copy, "[NARRATION]3 plays since March, the last one today.[/NARRATION]\n"
                       + "[QUESTION]You gave it a 9.5. What earned it?[/QUESTION]")
        XCTAssertEqual(row?.promptHash, i.narrationHash)
        XCTAssertTrue(row?.promptHash.hasPrefix("narration-v2:") ?? false)
        XCTAssertEqual(GeminiStubProtocol.requests.count, 1, "no writing: narration only")
        // Reopening Memories (a new generator, the same store): nothing is asked again.
        await MemoryGeneration().generate(i, account: "p1", client: client, store: s, settings: settings(key: "k"),
                                          onSaved: {}, isCurrent: { true })
        XCTAssertEqual(GeminiStubProtocol.requests.count, 1)
        let card = MemoryGeneration.voiced(MemoryDeck.cards(x)[0], x, language: "English", now: now, store: s)
        XCTAssertEqual(card.paragraph, "3 plays since March, the last one today. You gave it a 9.5. What earned it?")
    }

    func test_should_titleTheReview_when_oneIsWritten() async {
        let s = store(), x = inputs(review: "Cold water, warm light.")
        await MemoryGeneration().generate(input(x), account: "p1", client: client, store: s,
                                          settings: settings(key: "k"), onSaved: {}, isCurrent: { true })
        XCTAssertEqual(s.memory(provider: "subsonic", entityId: "al")?.title, "Night Swim")
        XCTAssertEqual(GeminiStubProtocol.requests.count, 1, "a review: no narration")
        let card = MemoryGeneration.voiced(MemoryDeck.cards(x)[0], x, language: "English", now: now, store: s)
        XCTAssertEqual(card.title, "Night Swim")
        XCTAssertEqual(card.titleKind, .ai)
        XCTAssertNil(card.paragraph)
    }

    func test_should_askNothing_when_noKeyIsSet() async {
        let s = store()
        await MemoryGeneration().generate(input(inputs()), account: "p1", client: client, store: s,
                                          settings: settings(key: nil), onSaved: {}, isCurrent: { true })
        XCTAssertTrue(GeminiStubProtocol.requests.isEmpty)
        XCTAssertNil(s.memory(provider: "subsonic", entityId: "al"))
    }

    func test_should_tryOnceAndSayTheLocalCopy_when_geminiFails() async {
        GeminiStubProtocol.handler = { _ in (500, Data()) }
        let s = store(), x = inputs(), i = input(x)
        let gen = MemoryGeneration()
        await gen.generate(i, account: "p1", client: client, store: s, settings: settings(key: "k"),
                           onSaved: {}, isCurrent: { true })
        await gen.generate(i, account: "p1", client: client, store: s, settings: settings(key: "k"),
                           onSaved: {}, isCurrent: { true })
        XCTAssertEqual(GeminiStubProtocol.requests.count, 1, "the attempt is kept: no retry loop")
        // Nothing to sync: an attempt alone is local bookkeeping.
        XCTAssertTrue(s.memories.isEmpty)
        let card = MemoryGeneration.voiced(MemoryDeck.cards(x)[0], x, language: "English", now: now, store: s)
        XCTAssertEqual(card.paragraph, i.fallback?.paragraph)
        XCTAssertNotNil(card.paragraph)
    }

    func test_should_readAndroidsSyncedCopy_when_itsHashMatches() {
        let s = store(), x = inputs(), i = input(x)
        s.putMemory(.init(provider: "subsonic", entityType: "album", entityId: "al",
                          copy: "[NARRATION]You played it three times.[/NARRATION]\n[QUESTION]Why that score?[/QUESTION]",
                          promptHash: i.narrationHash, generatedAt: now))
        XCTAssertTrue(MemoryGeneration.cached(i, row: s.memory(provider: "subsonic", entityId: "al")).narrationAttempted)
        let card = MemoryGeneration.voiced(MemoryDeck.cards(x)[0], x, language: "English", now: now, store: s)
        XCTAssertEqual(card.paragraph, "You played it three times. Why that score?")
    }

    func test_should_hashLikeKotlin_when_keyingPrompts() {
        // Kotlin "abc".hashCode() and "Yoin".hashCode().
        XCTAssertEqual(MemoryVoice.hash("abc"), "96354")
        XCTAssertEqual(MemoryVoice.hash("Yoin"), String("Yoin".utf16.reduce(Int32(0)) { $0 &* 31 &+ Int32($1) }))
    }
}
