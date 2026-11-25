import SwiftUI
import XCTest
import YoinShared
@testable import Yoin

/// Album About on iOS (Android PR #326, shared core PR #344): the shared golden vectors
/// (shared/src/commonTest/resources/golden/album-about.json) run through what iOS adds — its strings and date
/// format for the credits line, the Album → grounding mapping, the AI call's reply handling, the store's rows →
/// page content, the `album_about` sync adapter over SyncDomainBridge, and the page states (failure, empty,
/// content, questions only).
@MainActor
final class AlbumAboutTests: XCTestCase {
    private var dir: URL!

    override func setUp() async throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        GeminiStubProtocol.requests = []
        GeminiStubProtocol.handler = nil
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: dir)
        GeminiStubProtocol.handler = nil
    }

    // MARK: Golden file

    private static let golden: [String: Any] = {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("shared/src/commonTest/resources/golden/album-about.json")
        let data = try! Data(contentsOf: url)
        return try! JSONSerialization.jsonObject(with: data) as! [String: Any]
    }()

    private func vectors(_ section: String) -> [[String: Any]] { Self.golden[section] as! [[String: Any]] }

    private static func strings(_ o: Any?) -> [String] { (o as? [String]) ?? [] }
    private static func string(_ o: Any?) -> String? { o as? String }

    private static func credits(_ o: Any?) -> AlbumAiCredits {
        let c = o as! [String: Any]
        return AlbumAiCredits(label: strings(c["label"]), producers: strings(c["producers"]),
                              mastering: strings(c["mastering"]), mixing: strings(c["mixing"]),
                              recordedTime: string(c["recordedTime"]), recordedStudios: strings(c["recordedStudios"]),
                              source: string(c["source"]), firstReleased: string(c["firstReleased"]))
    }

    private static func bundle(_ language: String) -> Bundle {
        let path = Bundle.main.path(forResource: language, ofType: "lproj")!
        return Bundle(path: path)!
    }

    private static func album(id: String = "al-1", provider: String = "spotify", title: String = "Tide Tables",
                              artist: String = "Morikawa") -> Album {
        var a = MockLibrary.allAlbums[0]
        a.id = MediaId(provider: provider, rawId: id)
        a.title = title
        a.artist = artist
        return a
    }

    // MARK: Credits line (iOS strings + DateFormatter)

    /// Localizable's label sets are the golden ones (厂牌 / Label …), word for word.
    func test_should_matchGoldenLabels_when_readingLocalizable() {
        let sets = Self.golden["labels"] as! [String: [String: String]]
        for (language, bundle) in [("zh", "zh-Hans"), ("en", "en")] {
            let l = AlbumCredits.labels(bundle: Self.bundle(bundle))
            let g = sets[language]!
            XCTAssertEqual(l.label, g["label"], language)
            XCTAssertEqual(l.producer, g["producer"], language)
            XCTAssertEqual(l.mastering, g["mastering"], language)
            XCTAssertEqual(l.mixing, g["mixing"], language)
            XCTAssertEqual(l.recorded, g["recorded"], language)
            XCTAssertEqual(l.source, g["source"], language)
            XCTAssertEqual(l.firstReleased, g["firstReleased"], language)
            XCTAssertEqual(l.nameSeparator, g["nameSeparator"], language)
            XCTAssertEqual(l.timeAndPlace, g["timeAndPlace"], language)
        }
    }

    /// Every creditsLine vector, end to end on iOS: the service's facts on an iOS Album, iOS's labels and iOS's
    /// long date, then the line, its segments and the sheet's rows as Android writes them.
    func test_should_writeTheGoldenCreditsLine_through_iosLabelsAndDates() {
        let all = vectors("creditsLine")
        XCTAssertEqual(all.count, 33)
        for v in all {
            let name = v["name"] as! String
            let zh = (v["labels"] as! String) == "zh"
            var album = Self.album()
            album.recordLabels = Self.strings(v["sourceLabels"])
            album.releaseDate = Self.string(v["releaseDate"])
            album.originalReleaseDate = Self.string(v["originalReleaseDate"])
            let items = AlbumCredits.items(album, ai: Self.credits(v["ai"]))
            let locale = Locale(identifier: zh ? "zh-Hans" : "en")
            let labels = AlbumCredits.labels(bundle: Self.bundle(zh ? "zh-Hans" : "en"))
            let line: AlbumCreditsLine?
            if let dateText = Self.string(v["dateText"]) {
                // iOS's long date is Android's ICU one, and the line iOS writes with it is the golden line.
                XCTAssertEqual(AlbumCredits.dateText(items, locale: locale), dateText, name)
                line = AlbumCredits.line(items, labels: labels, locale: locale)
            } else if items.contains(where: { $0.date != nil }) {
                // The vector that pins :shared's fallback (a date with no text): the ISO date as it is.
                line = YoinAlbumAbout.shared.creditsLine(items: items, labels: labels, dateText: nil)
            } else {
                XCTAssertNil(AlbumCredits.dateText(items, locale: locale), name)
                line = AlbumCredits.line(items, labels: labels, locale: locale)
            }
            guard let expected = v["line"] as? [String: Any] else {
                XCTAssertNil(line, name)
                continue
            }
            XCTAssertEqual(line?.text, expected["text"] as? String, name)
            XCTAssertEqual(line?.plain, expected["plain"] as? String, name)
            XCTAssertEqual(line?.segments, expected["segments"] as? [String], name)
            let rows = (expected["rows"] as! [[String: String]]).map { "\($0["label"]!)=\($0["value"]!)" }
            XCTAssertEqual(line?.rows.map { "\($0.label)=\($0.value)" }, rows, name)
        }
    }

    func test_should_readIsoDates_atTheServicesPrecision() {
        XCTAssertEqual(ReleaseDateISO.parse("2023-10-27"), "2023-10-27")
        XCTAssertEqual(ReleaseDateISO.parse("2023-10-27", precision: "month"), "2023-10")
        XCTAssertEqual(ReleaseDateISO.parse("2023-10-27", precision: "year"), "2023")
        XCTAssertEqual(ReleaseDateISO.parse("2023-02-30"), "2023-02")
        XCTAssertEqual(ReleaseDateISO.parse("2024-02-29T00:00:00Z"), "2024-02-29")
        XCTAssertEqual(ReleaseDateISO.parse("2023-13"), "2023")
        XCTAssertNil(ReleaseDateISO.parse("0000"))
        XCTAssertNil(ReleaseDateISO.parse(" "))
    }

    // MARK: Grounding and the AI call

    /// The prompt vectors, from an iOS Album: the grounding iOS builds sends Android's exact prompts.
    func test_should_sendTheGoldenPrompts_from_anIosAlbum() {
        var checked = 0
        for v in vectors("prompts") {
            let g = v["grounding"] as! [String: Any]
            // iOS always knows a release kind (Album by default); a vector without one isn't an iOS album.
            guard let type = g["releaseType"] as? String, let kind = ReleaseKind(rawValue: type) else { continue }
            var album = Self.album(title: g["album"] as! String, artist: g["artist"] as! String)
            album.version = Self.string(g["version"])
            album.releaseDate = Self.string(g["releaseDate"])
            album.originalReleaseDate = Self.string(g["originalReleaseDate"])
            album.recordLabels = Self.strings(g["labels"])
            album.kind = kind
            album.genre = Self.string(g["genre"])
            album.tracks = Self.strings(g["tracks"]).enumerated().map { i, t in
                Track(id: MediaId(provider: "spotify", rawId: "t\(i)"), number: i + 1, title: t, durationSec: 0, rating: nil)
            }
            album.durationSec = (g["totalDurationSec"] as? NSNumber)?.intValue
            album.editorialNotes = Self.string(g["serviceNotes"])
            let grounding = album.aboutGrounding
            let about = YoinAlbumAbout.shared
            let language = v["targetLanguage"] as! String
            let lead = v["searchLead"] as! String
            XCTAssertEqual(about.groundingLines(grounding: grounding), v["groundingLines"] as? String)
            XCTAssertEqual(about.aboutPrompt(grounding: grounding, targetLanguage: language, searchLead: lead),
                           v["about"] as? String)
            if let question = Self.string(v["question"]) {
                XCTAssertEqual(about.askPrompt(grounding: grounding, question: question, targetLanguage: language,
                                               searchLead: lead), v["ask"] as? String)
            }
            checked += 1
        }
        XCTAssertEqual(checked, 9)
    }

    /// The reply vectors through iOS's AI call (Gemini over the stub): an answer comes back as Android stores it,
    /// with Apple's notes the AI's paragraph is dropped, and a reply that is no answer throws (nothing stored).
    func test_should_storeOrFailLikeAndroid_for_everyGoldenReply() async throws {
        let client = GeminiClient(baseURL: URL(string: "http://127.0.0.1:8790/")!, session: GeminiStubProtocol.session)
        let ai = AiSettings.geminiOnly(apiKey: "k")
        let all = vectors("replies")
        XCTAssertEqual(all.count, 52)
        for v in all {
            let name = "\(v["name"] as! String) notes=\(v["hasServiceNotes"] as! Bool)"
            let raw = v["raw"] as! String
            GeminiStubProtocol.handler = { _ in (200, geminiBody(raw)) }
            var album = Self.album()
            if v["hasServiceNotes"] as! Bool { album.editorialNotes = "Apple's notes." }
            let stored = v["stored"] as! [String: Any]
            do {
                let run = try await client.albumAbout(ai: ai, grounding: album.aboutGrounding, targetLanguage: "English")
                XCTAssertTrue(stored["answered"] as! Bool, name)
                XCTAssertEqual(run.blurb, Self.string(stored["blurb"]), name)
                XCTAssertEqual(run.credits, Self.credits(stored["credits"]), name)
                XCTAssertEqual(run.isEmpty, stored["isEmpty"] as? Bool, name)
            } catch {
                XCTAssertFalse(stored["answered"] as! Bool, "\(name): \(error)")
            }
        }
    }

    // MARK: Store → page content

    /// The content vectors: rows filed in AlbumAboutStore (and read back from disk) give the golden content.
    func test_should_readTheGoldenContent_from_storedRows() {
        for (i, v) in vectors("content").enumerated() {
            let url = dir.appendingPathComponent("c\(i).json")
            let store = AlbumAboutStore(fileURL: url)
            store.language = { "English" }
            let rows = (v["rows"] as! [[String: Any]]).map { r in
                AlbumAboutStore.Entry(provider: "spotify", albumId: "al-1", albumDisplay: "A", artistDisplay: "B",
                                      kind: r["kind"] as! String, entryKey: r["entryKey"] as! String,
                                      promptText: Self.string(r["promptText"]), titleText: Self.string(r["titleText"]),
                                      answerText: r["answerText"] as! String,
                                      createdAt: (r["createdAt"] as! NSNumber).int64Value, updatedAt: 1,
                                      language: "English")
            }
            XCTAssertTrue(store.upsert(rows))
            let reread = AlbumAboutStore(fileURL: url)
            reread.language = { "English" }
            let content = reread.content(.init(provider: "spotify", albumId: "al-1"))
            let g = v["content"] as! [String: Any]
            XCTAssertEqual(content.credits, Self.credits(g["credits"]), "content \(i)")
            XCTAssertEqual(content.blurb, Self.string(g["blurb"]), "content \(i)")
            XCTAssertEqual(content.generated, g["generated"] as? Bool, "content \(i)")
            let asks = (g["asks"] as! [[String: Any]]).map { "\($0["question"]!)|\($0["title"] as? String ?? "-")|\($0["answer"]!)" }
            XCTAssertEqual(content.asks.map { "\($0.question)|\($0.title ?? "-")|\($0.answer)" }, asks, "content \(i)")
        }
    }

    /// Rows are kept per language; another language's rows don't show, and a question asked again keeps its place.
    func test_should_keepRowsPerLanguage_and_askAgainInPlace() {
        let store = AlbumAboutStore(fileURL: dir.appendingPathComponent("lang.json"))
        var language = "English"
        store.language = { language }
        let album = Self.album()
        let key = AlbumAboutStore.key(album)
        store.saveCanonical(album: album, credits: AlbumAiCredits.companion.None, blurb: "Cut by the sea.",
                            language: "English")
        store.saveAsk(album: album, question: " Who played the Organ? ", reply: .init(title: "Organ", answer: "Ren."),
                      language: "English", now: Date(timeIntervalSince1970: 10))
        store.saveAsk(album: album, question: "Why Kamakura?", reply: .init(title: "Sea", answer: "Quiet."),
                      language: "English", now: Date(timeIntervalSince1970: 20))
        store.saveAsk(album: album, question: "who played the organ?", reply: .init(title: "Organ", answer: "Ren Kudo."),
                      language: "English", now: Date(timeIntervalSince1970: 30))
        XCTAssertEqual(store.content(key).blurb, "Cut by the sea.")
        XCTAssertEqual(store.content(key).asks.map(\.answer), ["Quiet.", "Ren Kudo."], "newest first by first asked")
        XCTAssertEqual(store.askCount(key), 2)
        language = "Japanese"
        XCTAssertFalse(store.content(key).generated)
        XCTAssertEqual(store.askCount(key), 0)
    }

    // MARK: Page states (the loader and the piece)

    private func loader(_ store: AlbumAboutStore, key: String?, calls: AlbumAboutLoader.Calls) -> AlbumAboutLoader {
        let defaults = UserDefaults(suiteName: "album-about-\(UUID().uuidString)")!
        let keychain = GeminiKeychain(service: "com.gpo.yoin.gemini.albumabout.\(UUID().uuidString)")
        let settings = GeminiSettings(keychain: keychain, defaults: defaults)
        if let key { settings.saveKey(key) }
        addTeardownBlock { keychain.delete() }
        return AlbumAboutLoader(store: store, settings: settings, calls: calls)
    }

    nonisolated private static func run(_ credits: AlbumAiCredits = AlbumAiCredits.companion.None,
                            blurb: String?) -> GeminiClient.AlbumAboutRun {
        GeminiClient.AlbumAboutRun(credits: credits, blurb: blurb, rawText: "", searchQueries: [])
    }

    nonisolated private static func calls(about: @escaping @Sendable () throws -> GeminiClient.AlbumAboutRun) -> AlbumAboutLoader.Calls {
        AlbumAboutLoader.Calls(about: { _, _, _ in try about() },
                               ask: { _, _, q, _ in GeminiClient.AskAnswer(title: "T", answer: "A to \(q)") })
    }

    private func piece(_ l: AlbumAboutLoader, _ album: Album) -> AlbumAboutPiece? {
        AlbumAboutPiece.of(l.store.content(AlbumAboutStore.key(album)), serviceNotes: album.editorialNotes,
                           writing: l.isWriting(album))
    }

    /// A failure stores nothing: no piece, and the next visit asks again.
    func test_should_showNothing_and_askAgain_when_theCallFails() async {
        let attempts = Box(0)
        let l = loader(AlbumAboutStore(fileURL: dir.appendingPathComponent("f.json")), key: "k",
                       calls: Self.calls { throw GeminiClient.Failure(message: "No album About in AI response") })
        let album = Self.album()
        await l.ensure(album)
        XCTAssertNil(piece(l, album))
        XCTAssertTrue(l.store.all.isEmpty)
        // Next visit: asked again.
        let again = loader(l.store, key: "k", calls: Self.calls { attempts.value += 1; return Self.run(blurb: "Second try.") })
        await again.ensure(album)
        XCTAssertEqual(attempts.value, 1)
        XCTAssertEqual(piece(again, album), .paragraph(text: "Second try.", fromService: false, asks: []))
    }

    /// Answered with nothing: stored, so the page doesn't ask on every visit; the quiet ✦ circle.
    func test_should_showTheEmptyMark_and_notAskAgain_when_theAnswerIsEmpty() async {
        let attempts = Box(0)
        let l = loader(AlbumAboutStore(fileURL: dir.appendingPathComponent("e.json")), key: "k",
                       calls: Self.calls { attempts.value += 1; return Self.run(blurb: nil) })
        let album = Self.album()
        await l.ensure(album)
        await l.ensure(album)
        XCTAssertEqual(attempts.value, 1)
        XCTAssertEqual(l.store.canonical(AlbumAboutStore.key(album)).count, 2)
        XCTAssertEqual(piece(l, album), .empty)
    }

    func test_should_showTheParagraph_and_creditsLine_when_answered() async throws {
        let credits = AlbumAiCredits(label: [], producers: ["Ren Kudo"], mastering: [], mixing: [], recordedTime: nil,
                                     recordedStudios: [], source: nil, firstReleased: nil)
        let l = loader(AlbumAboutStore(fileURL: dir.appendingPathComponent("p.json")), key: "k",
                       calls: Self.calls { Self.run(credits, blurb: "Cut by the sea.") })
        var album = Self.album()
        album.recordLabels = ["ECM"]
        await l.ensure(album)
        XCTAssertEqual(piece(l, album), .paragraph(text: "Cut by the sea.", fromService: false, asks: []))
        let items = AlbumCredits.items(album, ai: l.store.content(AlbumAboutStore.key(album)).credits)
        let line = AlbumCredits.line(items, labels: AlbumCredits.labels(bundle: Self.bundle("en")),
                                     locale: Locale(identifier: "en"))
        XCTAssertEqual(line?.plain, "Label ECM · Producer Ren Kudo")
    }

    /// No paragraph but questions: the newest question's card. Apple's notes are the paragraph when there are any.
    func test_should_showTheQuestions_when_thereIsNoParagraph_and_applesNotesFirst() async throws {
        let l = loader(AlbumAboutStore(fileURL: dir.appendingPathComponent("q.json")), key: "k",
                       calls: Self.calls { Self.run(blurb: nil) })
        var album = Self.album()
        XCTAssertTrue(l.ask(album, question: "Who played the piano?"))
        for _ in 0..<200 where !l.asking.isEmpty { try await Task.sleep(for: .milliseconds(10)) }
        guard case .questions(let asks) = piece(l, album) else { return XCTFail("\(String(describing: piece(l, album)))") }
        XCTAssertEqual(asks.map(\.answer), ["A to Who played the piano?"])
        album.editorialNotes = "Apple's own words."
        XCTAssertEqual(piece(l, album)?.fromService, true)
    }

    func test_should_notCallOrShowDots_when_thereIsNoKey() async {
        let attempts = Box(0)
        let l = loader(AlbumAboutStore(fileURL: dir.appendingPathComponent("n.json")), key: nil,
                       calls: Self.calls { attempts.value += 1; return Self.run(blurb: "x") })
        await l.ensure(Self.album())
        XCTAssertEqual(attempts.value, 0)
        XCTAssertFalse(l.isAiReady)
        XCTAssertNil(piece(l, Self.album()))
        XCTAssertEqual(AlbumAboutPiece.of(YoinAlbumAbout.shared.content(rows: []), serviceNotes: nil, writing: true), .pending)
    }

    /// Regenerate: an empty answer keeps what was there ("nothing new"); a new one hands back the old rows, and
    /// Undo writes them back.
    func test_should_keepTheOldRows_when_regenerateHasNothingNew_and_undo() async {
        let next = Box(Self.run(blurb: "First."))
        let l = loader(AlbumAboutStore(fileURL: dir.appendingPathComponent("r.json")), key: "k",
                       calls: Self.calls { next.value })
        let album = Self.album()
        let key = AlbumAboutStore.key(album)
        await l.ensure(album)
        next.value = Self.run(blurb: nil)
        let empty = await l.regenerate(album)
        XCTAssertEqual(empty, .nothingNew)
        XCTAssertEqual(l.store.content(key).blurb, "First.")
        next.value = Self.run(blurb: "Second.")
        guard case .success(let before) = await l.regenerate(album) else { return XCTFail() }
        XCTAssertEqual(l.store.content(key).blurb, "Second.")
        l.restore(before)
        XCTAssertEqual(l.store.content(key).blurb, "First.")
    }

    // MARK: Sync (SyncDomainBridge + the shared adapter)

    nonisolated private static func clock(_ now: Int64) -> () -> KotlinLong { { KotlinLong(value: now) } }
    nonisolated private static func constant(_ text: String) -> () -> String { { text } }

    /// iOS rows go out as Android's `album_about` records (artifacts files, the same key, the same twelve fields)
    /// and come in on another device reading the same language through the engine, never twice; deletes never sync.
    func test_should_roundTripAlbumAboutRecords_through_theBridgeAndEngine() async throws {
        let language = GeminiSettings.normalizeLanguage(GeminiSettings.shared.targetLanguage)
        let accounts: @MainActor @Sendable () -> SyncDomainBridge.Accounts = {
            SyncDomainBridge.Accounts(subsonic: [], appleMusic: false, storefront: nil)
        }
        let mine = AlbumAboutStore(fileURL: dir.appendingPathComponent("mine.json"))
        mine.language = { language }
        let album = Self.album()
        mine.saveCanonical(album: album, credits: AlbumAiCredits.companion.None, blurb: "Cut by the sea.",
                           language: language)
        mine.saveAsk(album: album, question: "Who?", reply: .init(title: "T", answer: "Ren."), language: language)
        let now: Int64 = 1_793_000_060_000
        let outBridge = SyncDomainBridge(accounts: accounts, albumAbouts: { mine })
        let outStore = InMemorySyncStore()
        let sender = SyncEngine(db: outStore, adapters: SharedSyncAdapters.shared.create(domain: outBridge, syncDb: outStore),
                                deviceId: "ios000000000000a", deviceName: Self.constant("iPhone"), clock: Self.clock(now))
        XCTAssertTrue(SyncEngine.companion.KNOWN_ARTIFACT_KINDS.contains("album_about"))
        _ = try await sender.capture(bindings: [])
        let files = try await sender.outgoingFiles(epoch: 0, writtenAt: now, appVersionCode: 1)
        let records = files.flatMap { SnapshotCodec.shared.decode(bytes: $0.bytes).records }
            .filter { $0.kind == "album_about" }
        XCTAssertEqual(records.count, 3)
        XCTAssertTrue(files.filter { $0.fileKind != "state" }.flatMap { SnapshotCodec.shared.decode(bytes: $0.bytes).records }
            .contains { $0.kind == "album_about" }, "an artifacts kind")
        let blurb = try XCTUnwrap(records.first { $0.key.hasSuffix("blurb") })
        XCTAssertEqual(blurb.key, [language, "spotify", "al-1", "canonical", "blurb"].joined(separator: "\u{1F}"))
        XCTAssertEqual(blurb.scope, "")
        // The payload as text (Kotlin's toString of the record; a JsonObject doesn't cross into Swift as one).
        let text = blurb.description
        let start = try XCTUnwrap(text.range(of: "payload="))
        let json = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(String(text[start.upperBound...].dropLast()).utf8))
            as? [String: Any])
        XCTAssertEqual(Set(json.keys), ["targetLanguage", "provider", "albumId", "albumDisplay", "artistDisplay", "kind",
                                        "entryKey", "promptText", "titleText", "answerText", "createdAt", "updatedAt"])
        XCTAssertEqual(json["answerText"] as? String, "Cut by the sea.")
        XCTAssertEqual(json["targetLanguage"] as? String, language)

        let theirs = AlbumAboutStore(fileURL: dir.appendingPathComponent("theirs.json"))
        theirs.language = { language }
        let inStore = InMemorySyncStore()
        let receiver = SyncEngine(db: inStore,
                                  adapters: SharedSyncAdapters.shared.create(
                                      domain: SyncDomainBridge(accounts: accounts, albumAbouts: { theirs }), syncDb: inStore),
                                  deviceId: "ios000000000000b", deviceName: Self.constant("iPad"),
                                  clock: Self.clock(now + 60_000))
        for (shard, file) in files.enumerated() where file.fileKind != "state" {
            _ = try await receiver.mergeRemote(
                file: RemoteFile(id: "a-\(shard)", name: "yoin-artifacts-ios000000000000a-\(file.shard).json.gz",
                                 version: 1, modifiedTime: nil, size: nil, appProperties: [:]),
                envelope: SnapshotCodec.shared.decode(bytes: file.bytes))
        }
        let applied = try await receiver.apply(bindings: [])
        XCTAssertEqual(applied.appliedLive[""]?["album_about"]?.intValue, 3)
        XCTAssertEqual(theirs.content(AlbumAboutStore.key(album)).blurb, "Cut by the sea.")
        XCTAssertEqual(theirs.askCount(AlbumAboutStore.key(album)), 1)
        XCTAssertEqual(theirs.all.map(\.language), Array(repeating: language, count: 3))
        let again = try await receiver.apply(bindings: [])
        XCTAssertNil(again.appliedLive[""]?["album_about"], "nothing applied twice")
        let capture = try await receiver.capture(bindings: [])
        XCTAssertNil(capture.newVersions[""]?["album_about"], "nothing new to say")
    }

    // MARK: AI service names in copy

    /// The aiCopy vectors with iOS's own templates (Localizable) and provider names.
    func test_should_nameTheService_likeAndroid() {
        let templates: [String: (key: String, bundle: String)] = [
            "Ask %1$s": ("Ask %@", "en"), "问 %1$s": ("Ask %@", "zh-Hans"),
            "%1$s 没能回答，再试一次": ("%@ couldn't answer. Try again.", "zh-Hans"),
            "缺少 %1$s API 密钥": ("%@ API key missing", "zh-Hans"),
        ]
        var checked = 0
        for v in vectors("aiCopy") {
            guard let t = templates[v["template"] as! String] else { continue }
            let template = Self.bundle(t.bundle).localizedString(forKey: t.key, value: nil, table: nil)
            XCTAssertEqual(template.replacingOccurrences(of: "%@", with: "%1$s"), v["template"] as? String)
            XCTAssertEqual(AiProviderName.copy(template, v["name"] as! String), v["text"] as? String)
            checked += 1
        }
        XCTAssertEqual(checked, 20)
        XCTAssertEqual(Self.bundle("zh-Hans").localizedString(forKey: "ai.provider.qwen", value: nil, table: nil), "千问")
        XCTAssertEqual(AiProviderName.name(.custom), "AI")
    }

    // MARK: Colours

    /// The scrapbook's papers keep their words readable on every cover, light and dark (Android: ≥ 4.5:1): the
    /// listener's (review, stickies, notes: onPrimaryContainer on primaryContainer) and the AI's (About, Ask, facts:
    /// onTertiaryContainer on tertiaryContainer).
    func test_should_keepPaperInkReadable_onEveryCover() {
        for album in MockLibrary.allAlbums {
            for dark in [false, true] {
                let s = CoverScheme(album: album, dark: dark)
                XCTAssertGreaterThanOrEqual(Self.contrast(s.onPrimaryContainer, s.primaryContainer), 4.5, "\(album.title) \(dark)")
                XCTAssertGreaterThanOrEqual(Self.contrast(s.onTertiaryContainer, s.tertiaryContainer), 4.5, "\(album.title) \(dark)")
                // The ✦ circle and the sheet's marks: tertiary on the page.
                XCTAssertGreaterThanOrEqual(Self.contrast(s.tertiary, s.surface), 3, "\(album.title) \(dark)")
                XCTAssertGreaterThanOrEqual(Self.contrast(s.onSurfaceVariant, s.surface), 4.5, "\(album.title) \(dark)")
            }
        }
    }

    private static func contrast(_ a: Color, _ b: Color) -> Double {
        func luminance(_ c: Color) -> Double {
            var r: CGFloat = 0, g: CGFloat = 0, bl: CGFloat = 0, al: CGFloat = 0
            UIColor(c).getRed(&r, green: &g, blue: &bl, alpha: &al)
            func lin(_ v: CGFloat) -> Double {
                let x = Double(v)
                return x <= 0.03928 ? x / 12.92 : pow((x + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(bl)
        }
        let (l1, l2) = (luminance(a), luminance(b))
        return (max(l1, l2) + 0.05) / (min(l1, l2) + 0.05)
    }

    // MARK: Renders

    /// The card, the ✦ circle and the sheet render on a cover's scheme in light and dark (PNG attachments, and
    /// files under $ALBUM_ABOUT_RENDERS when set).
    func test_should_render_theCardAndSheet() throws {
        let album = MockLibrary.allAlbums[0]
        let credits = AlbumAiCredits(label: [], producers: ["Ren Kudo", "Mei Lin"], mastering: ["Aki Sato"], mixing: [],
                                     recordedTime: "Sep–Dec 2013", recordedStudios: ["Studio Mare, Kamakura"],
                                     source: nil, firstReleased: nil)
        var decorated = album
        decorated.recordLabels = ["Harbor Light Records"]
        decorated.releaseDate = "2024-03-08"
        decorated.originalReleaseDate = "2014-05-20"
        let items = AlbumCredits.items(decorated, ai: credits)
        let text = "Afterglow Letters was Mina Asahi's second studio album and the first she made away from Tokyo. It was cut in a small studio by the sea, the piano and vocals mostly in one take, with the room's wind left in. It pushed her early folk writing toward quieter arrangements."
        let ask = AlbumAboutAsk(question: "Who played the piano?", title: "The piano", answer: "The producer, **Ren Kudo**.")
        let content = AlbumAboutContent(credits: credits, blurb: text, asks: [ask], generated: true)
        for dark in [false, true] {
            let s = CoverScheme(album: album, dark: dark)
            let card = VStack(alignment: .leading, spacing: 20) {
                AlbumCreditsLineView(items: items, scheme: s)
                AlbumAboutParagraphCard(text: text, fromService: false, hasQuestions: true, albumName: album.title,
                                        scheme: s, onOpen: {})
                AlbumAboutParagraphCard(text: text, fromService: true, hasQuestions: false, albumName: album.title,
                                        scheme: s, onOpen: {})
                AlbumAboutEmptyMark(scheme: s, onOpen: {})
            }
            .padding(16).frame(width: 390).background(s.surface)
            .environment(\.colorScheme, dark ? .dark : .light)
            try save(card, "album-about-card-\(dark ? "dark" : "light")")
            let sheet = AlbumAboutSheetBody(album: album, content: content,
                                            piece: .paragraph(text: text, fromService: false, asks: [ask]),
                                            credits: items, scheme: s, regenerating: false, asking: ["Why Kamakura?"],
                                            notice: nil, providerName: "Gemini", onRegenerate: {}, onAsk: { _ in },
                                            scrolls: false)
                .frame(width: 390).background(s.surfaceContainerLow)
                .environment(\.colorScheme, dark ? .dark : .light)
            try save(sheet, "album-about-sheet-\(dark ? "dark" : "light")")
        }
    }

    private func save(_ view: some View, _ name: String) throws {
        let renderer = ImageRenderer(content: view)
        renderer.scale = 2
        let image = try XCTUnwrap(renderer.uiImage, name)
        XCTAssertGreaterThan(image.size.height, 100, name)
        let png = try XCTUnwrap(image.pngData())
        let attachment = XCTAttachment(data: png, uniformTypeIdentifier: "public.png")
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        if let out = ProcessInfo.processInfo.environment["ALBUM_ABOUT_RENDERS"] {
            try png.write(to: URL(fileURLWithPath: out).appendingPathComponent("\(name).png"))
        }
    }
}

/// A value a @Sendable stub can change (tests run one call at a time).
private final class Box<T>: @unchecked Sendable {
    var value: T
    init(_ value: T) { self.value = value }
}
