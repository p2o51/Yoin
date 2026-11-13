import XCTest
@testable import Yoin

/// All Memories on large screens and songs' albums (Android #202): the song lookup in the ledger, the month's
/// unnamed-album scores on one card, the resolver's passes, the page's layout by width and the masonry.
@MainActor
final class MemoryLedgerWideTests: XCTestCase {
    private let sub = LedgerAccount(provider: "subsonic", scope: "https://a|me")
    private let spotify = LedgerAccount(provider: "spotify", scope: "spotify|u")
    private let day: Int64 = 86_400_000
    private var cal: Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "UTC")!
        return c
    }
    private let today = Date(timeIntervalSince1970: 1_794_000_000) // 2026-11-07
    private var now: Int64 { Int64(today.timeIntervalSince1970 * 1000) }

    // MARK: The song lookup in the ledger

    func test_should_nameASongsAlbumFromItsLookup_when_nothingLocalNamesIt() {
        var rows = LedgerRows()
        rows.trackRatings = [.init(account: sub, songId: "s1", rating: 8, updatedAt: now)]
        rows.notes = [.init(id: "n", account: sub, trackId: "s2", content: "words", createdAt: now - day, title: "", positionMs: nil)]
        let lookup = LedgerLookup(track: { _, id in
            LedgerTrack(title: "Song \(id)", albumId: "al-f", albumName: "Field", artist: "Band", coverKey: "cv")
        })
        let entries = MemoryLedger.build(rows, accounts: nil, lookup: lookup)
        XCTAssertEqual(entries.map(\.album?.name), ["Field", "Field"])
        XCTAssertEqual(entries.map(\.trackTitle), ["Song s1", "Song s2"])
        XCTAssertEqual(entries[0].album?.coverKey, "cv")
        XCTAssertEqual(entries[0].album?.account, sub)
    }

    func test_should_letAnAlbumsOwnScoreFindIt_when_onlyASongsLookupNamesTheAlbum() {
        var rows = LedgerRows()
        rows.trackRatings = [.init(account: sub, songId: "s1", rating: 8, updatedAt: now)]
        rows.albums = [.init(account: sub, albumId: "al-f", rating: 9, review: "warm", reviewUpdatedAt: nil, updatedAt: now - day)]
        rows.titles = [.init(account: sub, albumId: "al-f", title: "Frost", updatedAt: now - day)]
        let lookup = LedgerLookup(track: { _, _ in
            LedgerTrack(title: "I", albumId: "al-f", albumName: "Field", artist: "", coverKey: nil)
        })
        let entries = MemoryLedger.build(rows, accounts: nil, lookup: lookup)
        XCTAssertEqual(Set(entries.map(\.kind)), [.trackRating, .albumRating, .review, .title])
        XCTAssertTrue(entries.allSatisfy { $0.album?.albumId == "al-f" })
    }

    func test_should_preferWhatTheDeviceKnows_when_aLookupAlsoAnswers() {
        var rows = LedgerRows()
        rows.trackRatings = [.init(account: sub, songId: "s1", rating: 8, updatedAt: now)]
        let lookup = LedgerLookup(
            album: { a, id in id == "al-local" ? LedgerAlbum(account: a, albumId: id, name: "Local", artist: "", coverKey: nil) : nil },
            song: { _, _ in ("al-local", "Played") },
            track: { _, _ in LedgerTrack(title: "Fetched", albumId: "al-f", albumName: "Field", artist: nil, coverKey: nil) })
        let e = MemoryLedger.build(rows, accounts: nil, lookup: lookup)[0]
        XCTAssertEqual(e.album?.name, "Local")
        XCTAssertEqual(e.trackTitle, "Played")
    }

    // MARK: Unnamed-album scores on one card

    func test_should_gatherAMonthsUnnamedAlbumScores_when_timelineIsBuilt() {
        let a = LedgerAlbum(account: sub, albumId: "a", name: "A", artist: "", coverKey: nil)
        let entries = [
            LedgerEntry(id: "1", kind: .trackRating, at: now, account: sub, album: nil, trackTitle: "x", songId: "x", score: 7),
            LedgerEntry(id: "2", kind: .albumRating, at: now - day, account: sub, album: a, score: 8),
            LedgerEntry(id: "3", kind: .trackRating, at: now - 2 * day, account: sub, album: nil, songId: "y", score: 9),
            LedgerEntry(id: "4", kind: .songNote, at: now - 3 * day, account: sub, album: nil, text: "n"),
            LedgerEntry(id: "5", kind: .trackRating, at: now - 40 * day, account: sub, album: nil, songId: "z", score: 6),
        ]
        let rows = MemoryLedger.timeline(entries, today: today, calendar: cal)
        var scores: [[String]] = []
        for row in rows { if case .scores(_, let es) = row { scores.append(es.map(\.id)) } }
        // One card a month, where the month's newest falls, newest first.
        XCTAssertEqual(scores, [["1", "3"], ["5"]])
        guard case .scores = rows[1], case .album = rows[2], case .note = rows[3] else {
            return XCTFail("the scores card where its newest falls, then the album, then the note: \(rows)")
        }
        XCTAssertFalse(rows.contains { if case .note(let e) = $0 { e.kind == .trackRating } else { false } })
        XCTAssertEqual(rows.filter(\.isFullLine).count, rows.filter { if case .month = $0 { true } else if case .quiet = $0 { true } else { false } }.count)
    }

    // MARK: The resolver

    func test_should_askOnlyUnnamedSubsonicSongsOnce_when_pendingIsRead() {
        let a = LedgerAlbum(account: sub, albumId: "a", name: "A", artist: "", coverKey: nil)
        let entries = [
            LedgerEntry(id: "1", kind: .trackRating, at: now, account: sub, album: nil, songId: "s1", score: 7),
            LedgerEntry(id: "2", kind: .songNote, at: now, account: sub, album: nil, text: "n", songId: "s1"),
            LedgerEntry(id: "3", kind: .songNote, at: now, account: sub, album: nil, text: "n", songId: "s2"),
            LedgerEntry(id: "4", kind: .trackRating, at: now, account: sub, album: a, songId: "s3", score: 7),
            LedgerEntry(id: "5", kind: .trackRating, at: now, account: spotify, album: nil, songId: "s4", score: 7),
            LedgerEntry(id: "6", kind: .trackRating, at: now, account: sub, album: nil, songId: "s5", score: 7),
        ]
        let asked: Set<String> = [LedgerTrackResolver.Wanted.key(sub, "s5")]
        XCTAssertEqual(LedgerTrackResolver.pending(entries, asked: asked).map(\.songId), ["s1", "s2"])
    }

    func test_should_keepAnswersAndSkipAFailedAccount_when_aPassRuns() async {
        let resolver = LedgerTrackResolver()
        let other = LedgerAccount(provider: "subsonic", scope: "https://b|me")
        let wanted = [LedgerTrackResolver.Wanted(account: sub, songId: "known"),
                      LedgerTrackResolver.Wanted(account: sub, songId: "gone"),
                      LedgerTrackResolver.Wanted(account: other, songId: "x1")]
        let pass = await resolver.resolve(wanted) { w in
            switch w.songId {
            case "known": return LedgerTrack(title: "K", albumId: "al", albumName: "Al", artist: nil, coverKey: nil)
            case "gone": return nil
            default: throw URLError(.notConnectedToInternet)
            }
        }
        XCTAssertEqual(pass, .init(answered: 2, found: true))
        XCTAssertEqual(resolver.known.keys.sorted(), [LedgerTrackResolver.Wanted.key(sub, "known")])
        // A song the server doesn't know is answered (not asked again); a failed account's isn't.
        XCTAssertEqual(Set(resolver.answers.keys), [LedgerTrackResolver.Wanted.key(sub, "known"),
                                                    LedgerTrackResolver.Wanted.key(sub, "gone")])
        let again = await resolver.resolve([]) { _ in nil }
        XCTAssertEqual(again, .init(answered: 0, found: false))
    }

    func test_should_notCountAnUnknownSongAsNews_when_aPassOnlyMeetsGoneSongs() async {
        let resolver = LedgerTrackResolver()
        let pass = await resolver.resolve([.init(account: sub, songId: "gone")]) { _ in nil }
        XCTAssertEqual(pass, .init(answered: 1, found: false))
    }

    // MARK: Layout

    func test_should_pickColumnsByWidth_when_thePageIsLaidOut() {
        XCTAssertEqual(LedgerPageLayout.at(width: 402, compactHeight: false), .phone)
        XCTAssertEqual(LedgerPageLayout.at(width: 744, compactHeight: false).columns, 2)
        XCTAssertFalse(LedgerPageLayout.at(width: 834, compactHeight: false).wide)
        let portrait = LedgerPageLayout.at(width: 1032, compactHeight: false)
        XCTAssertEqual([portrait.columns, portrait.lanes], [2, 2])
        XCTAssertTrue(portrait.wide)
        XCTAssertEqual(portrait.gridMaxWidth, 760)
        let landscape = LedgerPageLayout.at(width: 1376, compactHeight: false)
        XCTAssertEqual([landscape.columns, landscape.lanes], [3, 3])
        XCTAssertEqual(landscape.maxWidth, 1240)
        // A short window (a landscape phone) is the phone layout whatever its width.
        XCTAssertEqual(LedgerPageLayout.at(width: 874, compactHeight: true), .phone)
    }

    func test_should_fillTheShortestColumnAndStartFullLinesBelowTheTallest_when_masonryArranges() {
        let items: [(fullLine: Bool, height: (CGFloat) -> CGFloat)] = [
            (true, { _ in 10 }), (false, { _ in 100 }), (false, { _ in 40 }), (false, { _ in 30 }), (true, { _ in 20 }),
        ]
        let r = LedgerMasonry.arrange(width: 210, columns: 2, spacing: 10, rowSpacing: 0, items: items)
        XCTAssertEqual(r.rects[0], CGRect(x: 0, y: 0, width: 210, height: 10))
        XCTAssertEqual(r.rects[1], CGRect(x: 0, y: 10, width: 100, height: 100))
        XCTAssertEqual(r.rects[2], CGRect(x: 110, y: 10, width: 100, height: 40))
        // The second column is shorter (50 < 110): the third card goes under the second.
        XCTAssertEqual(r.rects[3], CGRect(x: 110, y: 50, width: 100, height: 30))
        XCTAssertEqual(r.rects[4], CGRect(x: 0, y: 110, width: 210, height: 20))
        XCTAssertEqual(r.height, 130)
        let one = LedgerMasonry.arrange(width: 100, columns: 1, spacing: 10, rowSpacing: 5, items: [(false, { _ in 10 }), (false, { _ in 10 })])
        XCTAssertEqual(one.rects.map(\.minY), [0, 15])
        XCTAssertEqual(one.height, 25)
    }
}
