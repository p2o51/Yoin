import XCTest
@testable import Yoin

/// About record management (Android YoinRepository editAboutEntry /
/// writeOwnAboutEntry / regenerateAboutEntry, NowPlayingViewModel
/// undoAboutRegenerate): own entries, edits, blank-as-removed, regenerate and Undo.
@MainActor
final class SongAboutRecordsTests: XCTestCase {
    private var dir: URL!
    private var defaults: UserDefaults!
    private var keychain: GeminiKeychain!
    private let k = SongAboutStore.key(title: "Starlight", artist: "Muse", album: "BH&R")

    override func setUp() async throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defaults = UserDefaults(suiteName: "about-records-\(UUID().uuidString)")
        keychain = GeminiKeychain(service: "com.gpo.yoin.gemini.records.\(UUID().uuidString)")
        GeminiStubProtocol.requests = []
        GeminiStubProtocol.handler = nil
    }

    override func tearDown() async throws {
        keychain.delete()
        try? FileManager.default.removeItem(at: dir)
    }

    private func store(_ name: String = "a.json", language: String = "English") -> SongAboutStore {
        let s = SongAboutStore(fileURL: dir.appendingPathComponent(name))
        s.language = { language }
        return s
    }

    private func loader(_ store: SongAboutStore, key: String?) -> SongAboutLoader {
        let settings = GeminiSettings(keychain: keychain, defaults: defaults)
        if let key { settings.saveKey(key) }
        let base = URL(string: "http://127.0.0.1:8790/")!
        let l = SongAboutLoader(store: store, settings: settings,
                                client: { GeminiClient(baseURL: base, session: GeminiStubProtocol.session) })
        store.language = { "English" }
        return l
    }

    private func ask(_ s: SongAboutStore, _ q: String, _ answer: String = "A", at: Int64 = 1) {
        s.saveAsk(title: "Starlight", artist: "Muse", album: "BH&R", question: q,
                  reply: .init(title: "T \(q)", answer: answer), language: "English",
                  now: Date(timeIntervalSince1970: Double(at) / 1000))
    }

    func test_should_fileOwnEntryInEveryLanguage_when_written() {
        let s = store()
        s.writeOwn(title: "Starlight", artist: "Muse", album: "BH&R", heading: "  Where I heard it ", text: " Taxi. ")
        let own = s.entries(k).first { $0.kind == SongAboutStore.Kind.own }
        XCTAssertEqual(own?.entryKey, "where i heard it")
        XCTAssertEqual(own?.titleText, "Where I heard it")
        XCTAssertEqual(own?.answerText, "Taxi.")
        XCTAssertNil(own?.promptText)
        XCTAssertEqual(own?.language, SongAboutStore.anyLanguage)

        // A Gemini language switch hides Gemini's rows, never the listener's.
        s.language = { "Japanese" }
        XCTAssertEqual(s.entries(k).map(\.kind), [SongAboutStore.Kind.own])

        // Blank input writes nothing.
        s.writeOwn(title: "Starlight", artist: "Muse", album: "BH&R", heading: "x", text: "  ")
        XCTAssertEqual(s.all.count, 1)
    }

    func test_should_roundTripOwnRows_when_reloadedFromDisk() throws {
        let url = dir.appendingPathComponent("rt.json")
        let s = SongAboutStore(fileURL: url)
        s.language = { "English" }
        s.writeOwn(title: "Starlight", artist: "Muse", album: "BH&R", heading: "Note", text: "Mine",
                   now: Date(timeIntervalSince1970: 5))
        let reloaded = SongAboutStore(fileURL: url)
        reloaded.language = { "Korean" }
        XCTAssertEqual(reloaded.all, s.all)
        XCTAssertEqual(reloaded.entries(k).first?.kind, "own")
        XCTAssertEqual(reloaded.entries(k).first?.createdAt, 5000)
        // Android's field names and kind token on disk (the shape sync carries).
        let json = try XCTUnwrap(String(data: Data(contentsOf: url), encoding: .utf8))
        XCTAssertTrue(json.contains(#""kind":"own""#))
        XCTAssertTrue(json.contains(#""titleText":"Note""#))
    }

    func test_should_keepKeyOnRename_andNotOverwriteRenamedEntry_when_writingOldHeadingAgain() {
        let s = store()
        s.writeOwn(title: "Starlight", artist: "Muse", album: "BH&R", heading: "Memory", text: "one")
        let first = s.entries(k)[0].ref
        s.edit(first, title: "Renamed", answer: "one")
        XCTAssertEqual(s.entry(first)?.titleText, "Renamed")
        // The old heading again is a new entry, not the renamed one.
        s.writeOwn(title: "Starlight", artist: "Muse", album: "BH&R", heading: "memory", text: "two")
        XCTAssertEqual(s.entries(k).count, 2)
        XCTAssertEqual(s.entry(first)?.answerText, "one")
        XCTAssertTrue(s.entries(k).contains { $0.entryKey == "memory #2" && $0.answerText == "two" })
        // The renamed heading again replaces its text and keeps its key.
        s.writeOwn(title: "Starlight", artist: "Muse", album: "BH&R", heading: "renamed", text: "three")
        XCTAssertEqual(s.entry(first)?.answerText, "three")
        XCTAssertEqual(s.entry(first)?.titleText, "renamed", "the heading as written last")
        // An own entry always keeps a heading.
        s.edit(first, title: "  ", answer: "four")
        XCTAssertEqual(s.entry(first)?.titleText, "renamed")
    }

    func test_should_hideBlankRow_andKeepItForSync_when_removed() {
        let s = store()
        ask(s, "Who?")
        let ref = s.entries(k)[0].ref
        s.edit(ref, title: nil, answer: "   ")
        XCTAssertTrue(s.entries(k).isEmpty, "a blank answer is removed from every view")
        XCTAssertEqual(s.all.count, 1, "kept as a blank row: About deletes never sync")
        XCTAssertEqual(s.entry(ref)?.titleText, "T Who?", "a removal leaves the heading for Undo")
        // Undo writes it back.
        s.edit(ref, title: "T Who?", answer: "A")
        XCTAssertEqual(s.entries(k).first?.answerText, "A")
    }

    func test_should_orderByFirstWritten_when_anAskIsEdited() {
        let s = store()
        ask(s, "old", at: 1000)
        ask(s, "new", at: 2000)
        s.writeOwn(title: "Starlight", artist: "Muse", album: "BH&R", heading: "mine", text: "x",
                   now: Date(timeIntervalSince1970: 3))
        XCTAssertEqual(s.entries(k).map(\.entryKey), ["mine", "new", "old"])
        s.edit(s.entries(k)[2].ref, title: "Edited", answer: "changed", now: Date(timeIntervalSince1970: 9))
        XCTAssertEqual(s.entries(k).map(\.entryKey), ["mine", "new", "old"], "an edit leaves the row in place")
        XCTAssertEqual(s.entries(k)[2].titleText, "Edited")
    }

    func test_should_askAgainWithTheQuestion_andUndo_when_regeneratingAnAsk() async {
        GeminiStubProtocol.handler = { r in
            let body = String(decoding: r.httpBody ?? Data(), as: UTF8.self)
            XCTAssertTrue(body.contains("Who plays the piano?"), "asked again in its own words")
            return (200, geminiBody("[TITLE]Pianist[/TITLE][ANSWER]Matt Bellamy.[/ANSWER]"))
        }
        let s = store()
        let l = loader(s, key: "k")
        s.saveAsk(title: "Starlight", artist: "Muse", album: "BH&R", question: "Who plays the piano?",
                  reply: .init(title: "Piano", answer: "Old answer"), language: "English",
                  now: Date(timeIntervalSince1970: 1))
        let ref = s.entries(k)[0].ref

        let result = await l.regenerate(ref)
        XCTAssertEqual(result, .success(beforeTitle: "Piano", beforeAnswer: "Old answer"))
        XCTAssertEqual(GeminiStubProtocol.requests.count, 1)
        XCTAssertEqual(s.entry(ref)?.answerText, "Matt Bellamy.")
        XCTAssertEqual(s.entry(ref)?.titleText, "Pianist")
        XCTAssertEqual(s.entry(ref)?.createdAt, 1000, "the row keeps its place")
        XCTAssertTrue(l.regenerating.isEmpty)

        l.undoRegenerate(ref, title: "Piano", answer: "Old answer")
        XCTAssertEqual(s.entry(ref)?.answerText, "Old answer")
        XCTAssertEqual(s.entry(ref)?.titleText, "Piano")
    }

    func test_should_writeOnlyThatField_when_regeneratingACanonicalRow() async {
        GeminiStubProtocol.handler = { _ in (200, geminiBody(taggedAnswer)) }
        let s = store()
        let l = loader(s, key: "k")
        s.saveCanonical(title: "Starlight", artist: "Muse", album: "BH&R",
                        values: [.init(key: "lyricist", answer: "Someone"), .init(key: "creation_time", answer: "Old")],
                        language: "English")
        let ref = SongAboutStore.Entry.Ref(song: k, kind: "canonical", entryKey: "lyricist")
        let result = await l.regenerate(ref)
        XCTAssertEqual(result, .success(beforeTitle: nil, beforeAnswer: "Someone"))
        XCTAssertEqual(s.entry(ref)?.answerText, "Matt Bellamy")
        XCTAssertEqual(s.entry(.init(song: k, kind: "canonical", entryKey: "creation_time"))?.answerText, "Old",
                       "only the regenerated field is written")
    }

    func test_should_leaveRowAsItWas_when_geminiHasNothingNew() async {
        GeminiStubProtocol.handler = { _ in (200, geminiBody("[REVIEW]Something[/REVIEW]")) }
        let s = store()
        let l = loader(s, key: "k")
        s.saveCanonical(title: "Starlight", artist: "Muse", album: "BH&R",
                        values: [.init(key: "producer", answer: "Rich Costey")], language: "English")
        let ref = SongAboutStore.Entry.Ref(song: k, kind: "canonical", entryKey: "producer")
        let result = await l.regenerate(ref)
        XCTAssertEqual(result, .nothingNew)
        XCTAssertEqual(s.entry(ref)?.answerText, "Rich Costey")
    }

    func test_should_neverRegenerateOwnEntry_andSayKeyMissing_when_noKey() async {
        let s = store()
        let l = loader(s, key: nil)
        s.writeOwn(title: "Starlight", artist: "Muse", album: "BH&R", heading: "Mine", text: "x")
        ask(s, "Who?")
        let own = s.entries(k).first { $0.kind == "own" }!.ref
        let asked = s.entries(k).first { $0.kind == "ask" }!.ref
        let ownResult = await l.regenerate(own)
        XCTAssertEqual(ownResult, .nothingNew)
        let askResult = await l.regenerate(asked)
        XCTAssertEqual(askResult, .apiKeyMissing)
        XCTAssertTrue(GeminiStubProtocol.requests.isEmpty)
    }

    func test_should_reportError_andKeepText_when_geminiFails() async {
        GeminiStubProtocol.handler = { _ in (403, Data(#"{"error":{"message":"Permission denied"}}"#.utf8)) }
        let s = store()
        let l = loader(s, key: "k")
        ask(s, "Who?", "Kept")
        let ref = s.entries(k)[0].ref
        let result = await l.regenerate(ref)
        XCTAssertEqual(result, .error("Gemini API error (403): Permission denied"))
        XCTAssertEqual(s.entry(ref)?.answerText, "Kept")
    }

    func test_should_offerUndoInToast_when_regenerateSucceeds() async {
        GeminiStubProtocol.handler = { _ in (200, geminiBody("[TITLE]New[/TITLE][ANSWER]Fresh.[/ANSWER]")) }
        let s = store()
        let l = loader(s, key: "k")
        ask(s, "Who?", "Stale")
        let ref = s.entries(k)[0].ref
        let toast = PlaylistActions()
        l.regenerateAndReport(ref, toast: toast)
        for _ in 0..<200 where toast.toast == nil { try? await Task.sleep(for: .milliseconds(10)) }
        XCTAssertEqual(toast.toast?.text, String(localized: "Regenerated"))
        XCTAssertEqual(toast.toast?.action?.label, String(localized: "Undo"))
        XCTAssertEqual(s.entry(ref)?.answerText, "Fresh.")
        toast.runToastAction()
        XCTAssertNil(toast.toast)
        XCTAssertEqual(s.entry(ref)?.answerText, "Stale")
        XCTAssertEqual(s.entry(ref)?.titleText, "T Who?")
    }

    func test_should_takeFreshRequestOnce_forItsSong() {
        let r = ScrapbookFocusRequest()
        let song = MediaId(provider: "subsonic", rawId: "s1")
        let ref = SongAboutStore.Entry.Ref(song: k, kind: "ask", entryKey: "who?")
        let now = Date()
        r.request(songId: song, edit: ref, now: now)
        XCTAssertNil(r.take(for: [MediaId(provider: "subsonic", rawId: "other")], now: now))
        XCTAssertNotNil(r.pending, "another album leaves it for the right one")
        XCTAssertEqual(r.take(for: [song], now: now)?.edit, ref)
        XCTAssertNil(r.take(for: [song], now: now), "taken once")
        r.request(songId: song, now: now)
        XCTAssertNil(r.take(for: [song], now: now.addingTimeInterval(11)), "a stale request is dropped")
        XCTAssertNil(r.pending)
    }
}
