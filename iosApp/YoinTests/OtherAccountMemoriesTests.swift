import XCTest
@testable import Yoin

/// The Memories deck deals every account's memories (Android #178: MemoriesDeckCoordinator's other
/// profiles, MemoryOtherProfiles, YoinRepository.getOtherProfileAlbumMemoryCandidates): another account's
/// rows as the deck reads them, its cards among the account in use's, and what a switch does to a deck.
@MainActor
final class OtherAccountMemoriesTests: XCTestCase {
    private let now: Int64 = 1_762_000_000_000
    private let day: Int64 = 86_400_000
    private let home = LedgerAccount(provider: "subsonic", scope: "http://home|me")
    private let work = LedgerAccount(provider: "subsonic", scope: "http://work|me")
    private let demo = MemoryCard.Account(profileId: "p-demo", name: "demo", service: .subsonic, shape: 3)
    private let spare = MemoryCard.Account(profileId: "p-spare", name: "spare", service: .subsonic, shape: 5)

    // MARK: Rows

    private func album(_ id: String, tracks: Int = 4, title: String? = nil) -> Album {
        Album(id: MediaId(provider: "subsonic", rawId: id), title: title ?? "Album \(id)", artist: "Artist", year: 2020,
              palette: CoverPalette(a: .red, b: .green, c: .blue),
              tracks: (0..<tracks).map { Track(id: MediaId(provider: "subsonic", rawId: "\(id)-\($0)"), number: $0 + 1,
                                               title: "\(id) track \($0 + 1)", durationSec: 200) })
    }

    private func play(_ account: LedgerAccount, _ album: String, _ track: Int, at: Int64) -> PlayEvent {
        PlayEvent(id: UUID().uuidString, scope: MemoryLedgerSource.historyScope(account), provider: account.provider,
                  songId: "\(album)-\(track)", albumId: album, title: "\(album) track \(track + 1)", album: "Album \(album)",
                  artist: "Artist", startedAt: at, durationSec: 200)
    }

    private func rating(_ account: LedgerAccount, _ song: String, _ value: Double?, at: Int64 = 0) -> LocalRatings.Row {
        .init(scope: account.scope, provider: account.provider, songId: song, rating: value, updatedAt: at)
    }

    private func note(_ account: LedgerAccount, _ song: String, _ words: String, at: Int64, position: Int64? = nil) -> SongNote {
        SongNote(id: "n-\(song)-\(at)", provider: account.provider, trackId: song, content: words, createdAt: at,
                 updatedAt: at, title: song, artist: "Artist", positionMs: position, scope: account.scope)
    }

    private func albumRow(_ account: LedgerAccount, _ id: String, rating: Double = 0, review: String? = nil,
                          at: Int64 = 0) -> AlbumMemoryStore.AlbumRecord {
        .init(provider: account.provider, albumId: id, rating: rating, review: review,
              reviewUpdatedAt: review == nil ? nil : at, updatedAt: at, scope: account.scope)
    }

    /// One account's rows: [id] rated on every track (an eligible memory), played twice.
    private func ratedRows(_ account: LedgerAccount, _ id: String, tracks: Int = 4, base: Double = 8) -> MemoryAccountRows {
        MemoryAccountRows(plays: [play(account, id, 0, at: now - 9 * day), play(account, id, 1, at: now - 2 * day)],
                          ratings: (0..<tracks).map { rating(account, "\(id)-\($0)", base, at: now - day) })
    }

    func test_should_keepOnlyThatAccountsRows_when_readingEveryAccounts() {
        let rows = OtherAccountDeck.rows(
            of: home,
            plays: [play(home, "a", 0, at: 1), play(work, "a", 0, at: 2)],
            notes: [note(home, "a-0", "mine", at: 1), note(work, "a-0", "theirs", at: 2)],
            ratings: [rating(home, "a-0", 8), rating(work, "a-0", 3),
                      .init(scope: nil, provider: "applemusic", songId: "a-0", rating: 5, updatedAt: 0)],
            albums: [albumRow(home, "a", rating: 9), albumRow(work, "a", rating: 2)],
            titles: [.init(provider: "subsonic", albumId: "a", title: "Mine", updatedAt: 1, scope: home.scope),
                     .init(provider: "subsonic", albumId: "a", title: "Theirs", updatedAt: 1, scope: work.scope)],
            copies: [.init(provider: "subsonic", entityType: "album", entityId: "a", copy: "", promptHash: "", generatedAt: 0,
                           title: "AI", scope: home.scope),
                     .init(provider: "subsonic", entityType: "playlist", entityId: "a", copy: "", promptHash: "", generatedAt: 0,
                           scope: home.scope)])
        XCTAssertEqual(rows.plays.map(\.startedAt), [1])
        XCTAssertEqual(rows.notes.map(\.content), ["mine"])
        XCTAssertEqual(rows.ratings.map(\.rating), [8])
        XCTAssertEqual(rows.albums.map(\.rating), [9])
        XCTAssertEqual(rows.titles.map(\.title), ["Mine"])
        XCTAssertEqual(rows.copies.map(\.entityType), ["album"], "album copies only")
    }

    func test_should_listWrittenAlbumsNewestFirst_when_pickingThePagesToRead() {
        var rows = MemoryAccountRows()
        rows.plays = [play(home, "played", 0, at: 5), play(home, "noted", 1, at: 6), play(home, "rated", 2, at: 7)]
        rows.albums = [albumRow(home, "scored", rating: 8, at: 100), albumRow(home, "reviewed", review: "Loud.", at: 300),
                       albumRow(home, "blank", review: "  ", at: 900)]
        rows.notes = [note(home, "noted-1", "the bridge", at: 200), note(home, "noted-1", "   ", at: 950),
                      note(home, "unknown-song", "no play names its album", at: 999)]
        rows.ratings = [rating(home, "rated-2", 7, at: 400), rating(home, "played-0", nil, at: 990)]
        XCTAssertEqual(OtherAccountDeck.writtenAlbumIds(rows), ["rated", "reviewed", "noted", "scored"],
                       "plays alone, a blank review and a cleared rating write nothing")
        XCTAssertEqual(OtherAccountDeck.writtenAlbumIds(rows, limit: 2), ["rated", "reviewed"])
        XCTAssertEqual(OtherAccountDeck.scanLimit, 96)
    }

    func test_should_dealOnlyEligibleMemoriesWithTheirOwnRows_when_buildingAnotherAccountsInputs() {
        var rows = ratedRows(home, "full")
        // Two of six tracks rated: something kept, but not a memory yet.
        rows.plays.append(play(home, "thin", 0, at: now - 3 * day))
        rows.ratings += [rating(home, "thin-0", 6), rating(home, "thin-1", 6)]
        rows.albums = [albumRow(home, "full", rating: 9, review: "Warm.", at: now - 5 * day)]
        rows.titles = [.init(provider: "subsonic", albumId: "full", title: " Harbour nights ", updatedAt: 1, scope: home.scope)]
        rows.copies = [.init(provider: "subsonic", entityType: "album", entityId: "full", copy: "kept", promptHash: "h",
                             generatedAt: 0, title: "AI title", scope: home.scope)]
        rows.notes = [note(home, "full-1", "the bridge", at: now - 4 * day, position: 64_000)]
        let x = OtherAccountDeck.inputs(account: demo, rows: rows, details: ["full": album("full"), "thin": album("thin", tracks: 6)],
                                        now: now)
        XCTAssertEqual(x.memories.map(\.albumId), ["full"])
        XCTAssertTrue(x.isOther)
        XCTAssertEqual(x.account, demo)
        XCTAssertEqual(x.review("full")?.text, "Warm.")
        XCTAssertEqual(x.title("full")?.text, "Harbour nights")
        XCTAssertEqual(x.title("full")?.user, true)
        XCTAssertEqual(x.yoinTitle("full"), "AI title")
        XCTAssertEqual(x.copy("full"), "kept")
        XCTAssertEqual(x.memoryCopy?("full")?.title, "AI title")
        XCTAssertEqual(x.rating("full-0"), 8)
        XCTAssertEqual(x.notes["full"]?.map(\.content), ["the bridge"])

        let card = MemoryDeck.cards(x)[0]
        XCTAssertTrue(card.fromOther)
        XCTAssertEqual(card.owner, demo)
        XCTAssertEqual(card.title, "Harbour nights")
        XCTAssertEqual(card.review?.text, "Warm.")
        XCTAssertEqual(card.score, 9)
        XCTAssertEqual(card.diaryTracks.map(\.index), [0, 1, 2, 3])
        XCTAssertEqual(card.diaryTracks[1].notes.first?.positionMs, 64_000)
        XCTAssertEqual(card.playCount, 2)
    }

    func test_should_qualifyByItsReview_when_noPageIsStored() {
        // Android: an album without a cached detail still qualifies by its review / notes.
        var rows = MemoryAccountRows()
        rows.plays = [play(home, "gone", 0, at: now - day)]
        rows.albums = [albumRow(home, "gone", rating: 7.5, review: "Still think about it.", at: now - day)]
        let x = OtherAccountDeck.inputs(account: demo, rows: rows, details: [:], now: now) { id, snap in
            snap.map { Album(id: MediaId(provider: $0.provider, rawId: id), title: $0.album, artist: $0.artist, year: 0,
                             palette: CoverPalette(a: .red, b: .green, c: .blue), tracks: []) }
        }
        XCTAssertEqual(x.memories.map(\.albumId), ["gone"])
        let card = MemoryDeck.cards(x)[0]
        XCTAssertEqual(card.album.title, "Album gone")
        XCTAssertTrue(card.diaryTracks.isEmpty, "no track list: nothing to play")
        XCTAssertEqual(card.review?.text, "Still think about it.")
    }

    func test_should_keepTwentyFourAtMost_when_anotherAccountHasMore() {
        var rows = MemoryAccountRows()
        var details: [String: Album] = [:]
        for i in 0..<30 {
            let id = "m\(i)"
            rows.plays.append(play(home, id, 0, at: now - Int64(i) * day))
            rows.ratings += (0..<4).map { rating(home, "\(id)-\($0)", 8) }
            details[id] = album(id)
        }
        let x = OtherAccountDeck.inputs(account: demo, rows: rows, details: details, now: now)
        XCTAssertEqual(MemoryDeck.otherAccountLimit, 24)
        XCTAssertEqual(x.memories.count, 24)
        // The builder's order (newest play first among equals) decides who stays.
        XCTAssertEqual(x.memories.first?.albumId, "m0")
        XCTAssertFalse(x.memories.contains { $0.albumId == "m29" })
    }

    // MARK: The deck

    /// The account in use ([own]) keeps album "a"; so does the other one, with its own rows.
    private func twoAccounts(own: MemoryCard.Account, other: MemoryCard.Account, otherAlbums: [String] = ["a", "b"]) -> MemoryDeck.Inputs {
        var x = OtherAccountDeck.inputs(account: own, rows: ratedRows(work, "a", base: 6), details: ["a": album("a")], now: now)
        x.isOther = false
        var rows = MemoryAccountRows()
        var details: [String: Album] = [:]
        for id in otherAlbums {
            let r = ratedRows(home, id, base: 9)
            rows.plays += r.plays
            rows.ratings += r.ratings
            details[id] = album(id)
        }
        x.others = [OtherAccountDeck.inputs(account: other, rows: rows, details: details, now: now)]
        return x
    }

    func test_should_dealEveryAccountsMemoriesApart_when_twoKeepTheSameAlbum() {
        let x = twoAccounts(own: spare, other: demo)
        let cards = MemoryDeck.cards(x)
        XCTAssertEqual(cards.map(\.albumId), ["a", "a", "b"], "the account in use first, then the other's")
        XCTAssertEqual(cards.map(\.fromOther), [false, true, true])
        XCTAssertEqual(Set(cards.map(\.id)).count, 3, "one album id, two accounts: two cards")
        XCTAssertEqual(cards[0].score, 6)
        XCTAssertEqual(cards[1].score, 9, "each reads its own account's ratings")
        XCTAssertNil(cards[0].owner)
        XCTAssertEqual(cards[1].owner?.name, "demo")
        // No accounts (the mock deck, the older tests): the album id is the card's id, as before.
        XCTAssertEqual(MemoryDeck.cardId(account: nil, albumId: "a"), "a")
    }

    func test_should_stopOnTheAccountInUsesCard_when_focusedOnAnAlbumBothKeep() {
        let x = twoAccounts(own: spare, other: demo)
        var rng = SystemRandomNumberGenerator()
        for _ in 0..<12 {
            let deck = MemoryDeck.deck(x, focus: "a", using: &rng)
            XCTAssertEqual(deck.count, 3)
            XCTAssertEqual(deck.first?.albumId, "a")
            XCTAssertEqual(deck.first?.fromOther, false)
            XCTAssertEqual(MemoryDeck.startIndex(deck, focus: "a"), 0)
        }
        // A focus only another account keeps isn't one: the deck opens on its first card.
        let deck = MemoryDeck.deck(x, focus: "b", using: &rng)
        XCTAssertEqual(MemoryDeck.startIndex(deck, focus: "b"), 0)
    }

    func test_should_dealTheUnseenOfEveryAccountFirst_when_pullingPastTheDecksEnd() {
        let x = twoAccounts(own: spare, other: demo, otherAlbums: ["a", "b", "c", "d", "e", "f", "g"])
        let all = MemoryDeck.cards(x)
        XCTAssertEqual(all.count, 8)
        let current = Set(all.prefix(6).map(\.id))
        let next = MemoryDeck.next(x, excluding: current)
        XCTAssertEqual(next.count, MemoryDeck.limit)
        XCTAssertEqual(Set(next.prefix(2).map(\.id)), Set(all.suffix(2).map(\.id)))
        XCTAssertTrue(next.dropFirst(2).allSatisfy { current.contains($0.id) })
    }

    // MARK: A switch under the open deck

    func test_should_followTheAccountInUse_when_aSwitchLands() {
        let before = MemoryDeck.cards(twoAccounts(own: spare, other: demo))
        // At once, before any row is read again: whose card is the other account's.
        let stamped = before.map { $0.stamped(active: demo.profileId) }
        XCTAssertEqual(stamped.map(\.fromOther), [true, false, false])
        XCTAssertEqual(stamped.map(\.id), before.map(\.id), "a card keeps its identity across the switch")
        XCTAssertEqual(stamped[0].owner?.name, "spare")
        // A deck without accounts never has another account's card.
        let mock = MemoryDeck.card(MemoryDeck.mockMemories()[0], MemoryDeck.Inputs(memories: []))
        XCTAssertFalse(mock.stamped(active: "anyone").fromOther)
    }

    func test_should_readEachCardFromItsOwnAccount_when_refreshingAfterASwitch() {
        let before = MemoryDeck.cards(twoAccounts(own: spare, other: demo))
        // demo is in use now: its rows are the deck's own, spare's are the other account's.
        var after = OtherAccountDeck.inputs(account: demo, rows: {
            var r = ratedRows(home, "a", base: 9)
            let b = ratedRows(home, "b", base: 9)
            r.plays += b.plays
            r.ratings += b.ratings
            r.albums = [albumRow(home, "a", review: "Written after the switch.", at: now)]
            return r
        }(), details: ["a": album("a"), "b": album("b")], now: now)
        after.isOther = false
        after.others = [OtherAccountDeck.inputs(account: spare, rows: ratedRows(work, "a", base: 6), details: ["a": album("a")],
                                                now: now)]
        let cards = before.map { MemoryDeck.refreshed($0, after) }
        XCTAssertEqual(cards.map(\.id), before.map(\.id))
        XCTAssertEqual(cards.map(\.fromOther), [true, false, false])
        XCTAssertEqual(cards[0].score, 6)
        XCTAssertEqual(cards[1].review?.text, "Written after the switch.")
    }

    func test_should_keepWhatTheCardShows_when_itsAccountOrPageIsNotThereYet() {
        let before = MemoryDeck.cards(twoAccounts(own: spare, other: demo))
        // Just after the switch: demo's album pages aren't loaded again (snapshots, no track list) and
        // spare's rows aren't read yet.
        var after = OtherAccountDeck.inputs(account: demo, rows: ratedRows(home, "a", base: 9), details: [:], now: now) { id, snap in
            snap.map { Album(id: MediaId(provider: $0.provider, rawId: id), title: $0.album, artist: $0.artist, year: 0,
                             palette: CoverPalette(a: .red, b: .green, c: .blue), tracks: []) }
        }
        after.isOther = false
        let cards = before.map { MemoryDeck.refreshed($0, after) }
        XCTAssertEqual(cards.map(\.fromOther), [true, false, false])
        XCTAssertEqual(cards[1].album.tracks.count, 4, "the card keeps its track list until the page is back")
        XCTAssertEqual(cards[1].diaryTracks.count, 4)
        XCTAssertEqual(cards[0].score, 6, "spare's card stays as it read")
        XCTAssertEqual(cards[2].albumId, "b", "an album no longer in the pool stays where it is")
    }

    // MARK: Read-only

    func test_should_sayTheOtherAccountsTitleAndTheLocalCopy_when_voicingItsCard() {
        var rows = ratedRows(home, "a", base: 9)
        let narration = "[NARRATION]You played it twice.[/NARRATION]\n[QUESTION]Why that score?[/QUESTION]"
        let probe = MemoryDeck.cards(OtherAccountDeck.inputs(account: demo, rows: rows, details: ["a": album("a")], now: now))[0]
        let voice = MemoryVoice.Input(card: probe, notes: [], now: now, targetLanguage: "English")
        rows.copies = [.init(provider: "subsonic", entityType: "album", entityId: "a", copy: narration,
                             promptHash: voice.narrationHash, generatedAt: now, title: "Tide lines",
                             titlePromptHash: voice.titleHash, scope: home.scope)]
        var x = MemoryDeck.Inputs(memories: [])
        x.account = spare
        x.others = [OtherAccountDeck.inputs(account: demo, rows: rows, details: ["a": album("a")], now: now)]
        let store = AlbumMemoryStore(fileURL: FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID().uuidString).json"),
                                     scopes: AccountScope())
        // The account in use keeps another title for the same album id: never this card's.
        store.putMemory(.init(provider: "subsonic", entityType: "album", entityId: "a", copy: "", promptHash: "",
                              generatedAt: now, title: "Not this one", titlePromptHash: voice.titleHash))
        let card = MemoryGeneration.voiced(MemoryDeck.cards(x)[0], x, language: "English", now: now, store: store)
        XCTAssertEqual(card.title, "Tide lines")
        XCTAssertEqual(card.titleKind, .ai)
        // Android: another account's memory keeps the template (Gemini's narration is the account in use's).
        XCTAssertEqual(card.paragraph, voice.fallback?.paragraph)
        XCTAssertNotEqual(card.paragraph, "You played it twice. Why that score?")
        // The same rows as the account in use's own: there the stored narration is what it says.
        var own = OtherAccountDeck.inputs(account: demo, rows: rows, details: ["a": album("a")], now: now)
        own.isOther = false
        XCTAssertEqual(MemoryGeneration.voiced(MemoryDeck.cards(own)[0], own, language: "English", now: now, store: store).paragraph,
                       "You played it twice. Why that score?")
        // A card whose account's rows aren't in the inputs stays as it reads.
        let stray = MemoryDeck.cards(x)[0]
        XCTAssertEqual(MemoryGeneration.voiced(stray, MemoryDeck.Inputs(memories: []), language: "English", now: now, store: store), stray)
    }

    func test_should_takeNoWrite_when_theMemoryIsAnotherAccounts() {
        let card = MemoryDeck.cards(twoAccounts(own: spare, other: demo))[1]
        XCTAssertTrue(card.fromOther)
        XCTAssertFalse(MemoryWrites.saveReview(card, text: "Not mine to write."))
    }

    func test_should_askGeminiNothing_when_theMemoryIsAnotherAccounts() async {
        let defaults = UserDefaults(suiteName: "other-account-\(UUID().uuidString)")!
        let keychain = GeminiKeychain(service: "com.gpo.yoin.gemini.other.\(UUID().uuidString)")
        defer { keychain.delete() }
        let settings = GeminiSettings(keychain: keychain, defaults: defaults)
        settings.saveKey("k")
        settings.saveLanguage("English")
        GeminiStubProtocol.requests = []
        GeminiStubProtocol.handler = { _ in (500, Data()) }
        let store = AlbumMemoryStore(fileURL: FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID().uuidString).json"),
                                     scopes: AccountScope())
        let card = MemoryDeck.cards(twoAccounts(own: spare, other: demo))[1]
        let input = MemoryVoice.Input(card: card, notes: [], now: now, targetLanguage: "English")
        await MemoryGeneration().generate(input, account: spare.profileId,
                                          client: GeminiClient(baseURL: URL(string: "http://127.0.0.1:8790/")!,
                                                               session: GeminiStubProtocol.session),
                                          store: store, settings: settings, onSaved: {}, isCurrent: { true })
        XCTAssertTrue(GeminiStubProtocol.requests.isEmpty)
        XCTAssertNil(store.memory(provider: "subsonic", entityId: "a"), "not even an attempt is kept")
    }

    // MARK: The tip

    func test_should_carryAndroidsWords_when_listingTheAllAccountsTip() throws {
        let tip = try XCTUnwrap(Tips.tip("MemoriesAllAccounts"))
        XCTAssertEqual(tip.title, "Memories across accounts")
        XCTAssertEqual(tip.text, "Memories includes all your accounts, and switches to that account first when you play or go to the album")
        XCTAssertEqual(tip.titleZhHans, "各账号的回忆")
        XCTAssertEqual(tip.textZhHans, "Memories 会显示你所有账号的回忆，播放或去专辑会先切换到该账号")
    }
}
