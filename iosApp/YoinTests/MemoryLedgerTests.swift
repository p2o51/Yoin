import XCTest
@testable import Yoin

/// All Memories: the ledger, its grid and its views (Data/Memories/MemoryLedger.swift, Android #178 #186).
final class MemoryLedgerTests: XCTestCase {
    private let sub = LedgerAccount(provider: "subsonic", scope: "https://a|me")
    private let other = LedgerAccount(provider: "subsonic", scope: "https://b|me")
    private let day: Int64 = 86_400_000
    private var cal: Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "UTC")!
        c.firstWeekday = 2
        return c
    }
    private let today = Date(timeIntervalSince1970: 1_794_000_000) // 2026-11-07

    private func lookup() -> LedgerLookup {
        LedgerLookup(
            album: { account, id in
                LedgerAlbum(account: account, albumId: id, name: "Album \(id)", artist: "Artist", coverKey: nil)
            },
            song: { _, id in id.hasPrefix("lost") ? nil : ("al-\(id.prefix(1))", "Song \(id)") })
    }

    private var now: Int64 { Int64(today.timeIntervalSince1970 * 1000) }

    func test_should_buildEntriesNewestFirst_when_rowsComeFromEveryStore() {
        var rows = LedgerRows()
        rows.notes = [.init(id: "n1", account: sub, trackId: "x1", content: "  rain on the window ", createdAt: now - day,
                            title: "", positionMs: 4_000)]
        rows.trackRatings = [.init(account: sub, songId: "x2", rating: 8, updatedAt: now - 2 * day),
                             .init(account: sub, songId: "x3", rating: nil, updatedAt: now)]
        rows.albums = [.init(account: sub, albumId: "z", rating: 9, review: "great", reviewUpdatedAt: now - 3 * day,
                             updatedAt: now - 4 * day)]
        rows.titles = [.init(account: sub, albumId: "z", title: "Summer", updatedAt: now - 5 * day)]
        let entries = MemoryLedger.build(rows, accounts: nil, lookup: lookup())
        XCTAssertEqual(entries.map(\.kind), [.songNote, .trackRating, .review, .albumRating, .title])
        XCTAssertEqual(entries[0].text, "rain on the window")
        XCTAssertEqual(entries[0].trackTitle, "Song x1")
        XCTAssertEqual(entries[0].album?.albumId, "al-x")
        // A cleared rating (nil) is not a mark.
        XCTAssertFalse(entries.contains { $0.songId == "x3" })
    }

    func test_should_leaveOutRemovedAccounts_when_accountsAreGiven() {
        var rows = LedgerRows()
        rows.trackRatings = [.init(account: sub, songId: "x1", rating: 6, updatedAt: now),
                             .init(account: other, songId: "x1", rating: 7, updatedAt: now)]
        let entries = MemoryLedger.build(rows, accounts: [sub], lookup: lookup())
        XCTAssertEqual(entries.map(\.account), [sub])
    }

    func test_should_keepTwoAccountsApart_when_theySameAlbumId() {
        var rows = LedgerRows()
        rows.albums = [.init(account: sub, albumId: "z", rating: 9, review: nil, reviewUpdatedAt: nil, updatedAt: now),
                       .init(account: other, albumId: "z", rating: 5, review: nil, reviewUpdatedAt: nil, updatedAt: now)]
        let items = MemoryLedger.items(MemoryLedger.build(rows, accounts: nil, lookup: lookup()))
        XCTAssertEqual(items.count, 2)
        XCTAssertEqual(Set(items.map(\.account)), [sub, other])
    }

    func test_should_rankWrittenAlbumsFirst_when_gridCellsFill() {
        let a = LedgerAlbum(account: sub, albumId: "a", name: "A", artist: "", coverKey: nil)
        let b = LedgerAlbum(account: sub, albumId: "b", name: "B", artist: "", coverKey: nil)
        let entries = [
            LedgerEntry(id: "1", kind: .albumRating, at: now, account: sub, album: a, score: 8),
            LedgerEntry(id: "2", kind: .trackRating, at: now, account: sub, album: a, score: 8),
            LedgerEntry(id: "3", kind: .songNote, at: now - 1, account: sub, album: b, text: "x"),
        ]
        let marks = MemoryGrid.rankMarks(entries, capacity: 2)
        XCTAssertEqual(marks.map(\.album.albumId), ["b", "a"])
        XCTAssertEqual(marks.map(\.wrote), [true, false])
        XCTAssertEqual(marks[1].count, 2)
    }

    func test_should_pickTheScale_when_activeDaysCross10And30() {
        XCTAssertEqual(MemoryGrid.scale(activeDays: 0), .month)
        XCTAssertEqual(MemoryGrid.scale(activeDays: 9), .month)
        XCTAssertEqual(MemoryGrid.scale(activeDays: 10), .week)
        XCTAssertEqual(MemoryGrid.scale(activeDays: 29), .week)
        XCTAssertEqual(MemoryGrid.scale(activeDays: 30), .day)
    }

    func test_should_layOutMonthsWeeksAndDays_when_built() {
        let a = LedgerAlbum(account: sub, albumId: "a", name: "A", artist: "", coverKey: nil)
        let one = [LedgerEntry(id: "1", kind: .songNote, at: now, account: sub, album: a, text: "x")]
        let months = MemoryGrid.build(one, today: today, calendar: cal)
        XCTAssertEqual(months.scale, .month)
        XCTAssertEqual(months.cells.count, MemoryGrid.minMonths)
        XCTAssertEqual(months.cells.last?.marks.first?.album.albumId, "a")

        let weeks = MemoryGrid.build(one, today: today, calendar: cal, scale: .week)
        XCTAssertEqual(weeks.cells.count, MemoryGrid.weeks)
        // Weeks start on the calendar's first weekday (Monday here); today's week is last.
        XCTAssertEqual(cal.component(.weekday, from: weeks.cells[0].start), 2)
        XCTAssertTrue(weeks.cells.last!.start <= today && today <= weeks.cells.last!.end.addingTimeInterval(86_399))

        let days = MemoryGrid.build(one, today: today, calendar: cal, scale: .day)
        XCTAssertEqual(days.cells.count, MemoryGrid.dayWeeks * 7)
        // The tail of this week hasn't come yet.
        XCTAssertTrue(days.cells.last!.future)
        XCTAssertEqual(days.cells.filter { !$0.isEmpty }.count, 1)
    }

    func test_should_foldEmptyMonthsAndAlternateCards_when_timelineIsBuilt() {
        let a = LedgerAlbum(account: sub, albumId: "a", name: "A", artist: "", coverKey: nil)
        let b = LedgerAlbum(account: sub, albumId: "b", name: "B", artist: "", coverKey: nil)
        let entries = [
            LedgerEntry(id: "1", kind: .albumRating, at: now, account: sub, album: a, score: 8),
            LedgerEntry(id: "2", kind: .review, at: now - day, account: sub, album: b, text: "x"),
            LedgerEntry(id: "3", kind: .songNote, at: now - 2 * day, account: sub, album: nil, text: "y"),
            LedgerEntry(id: "4", kind: .albumRating, at: now - 120 * day, account: sub, album: a, score: 6),
        ]
        let rows = MemoryLedger.timeline(entries, today: today, calendar: cal)
        guard case .month(_, let count) = rows[0] else { return XCTFail("starts with this month") }
        XCTAssertEqual(count, 3)
        guard case .album(_, _, let firstEnd) = rows[1], case .album(_, _, let secondEnd) = rows[2] else {
            return XCTFail("two album cards")
        }
        XCTAssertEqual([firstEnd, secondEnd], [false, true])
        guard case .note = rows[3] else { return XCTFail("the song note between") }
        XCTAssertTrue(rows.contains { if case .quiet = $0 { true } else { false } })
    }

    func test_should_sortByScoreThenRecent_when_listIsSortedByScore() {
        let a = LedgerAlbum(account: sub, albumId: "a", name: "Zed", artist: "", coverKey: nil)
        let b = LedgerAlbum(account: sub, albumId: "b", name: "Alpha", artist: "", coverKey: nil)
        let entries = [
            LedgerEntry(id: "1", kind: .albumRating, at: now, account: sub, album: a, score: 6),
            LedgerEntry(id: "2", kind: .albumRating, at: now - 1, account: sub, album: b, score: 9),
            LedgerEntry(id: "3", kind: .songNote, at: now - 2, account: sub, album: nil, text: "x", trackTitle: "Mid", songId: "s"),
        ]
        let items = MemoryLedger.items(entries)
        XCTAssertEqual(MemoryLedger.sorted(items, by: .score).map(\.name), ["Alpha", "Zed", "Mid"])
        XCTAssertEqual(MemoryLedger.sorted(items, by: .name).map(\.name), ["Alpha", "Mid", "Zed"])
        XCTAssertEqual(MemoryLedger.sorted(items, by: .recent).map(\.name), ["Zed", "Alpha", "Mid"])
    }

    func test_should_countWritingAndCharacters_when_statsAreRead() {
        let a = LedgerAlbum(account: sub, albumId: "a", name: "A", artist: "", coverKey: nil)
        let entries = [
            LedgerEntry(id: "1", kind: .songNote, at: 5, account: sub, album: a, text: "雨天"),
            LedgerEntry(id: "2", kind: .review, at: 3, account: sub, album: a, text: "ok"),
            LedgerEntry(id: "3", kind: .title, at: 4, account: sub, album: a, text: "Title"),
            LedgerEntry(id: "4", kind: .trackRating, at: 9, account: sub, album: nil, score: 7),
        ]
        let s = MemoryLedger.stats(entries)
        XCTAssertEqual(s.writings, 2)
        XCTAssertEqual(s.characters, 4)
        XCTAssertEqual(s.albums, 1)
        XCTAssertEqual(s.ratedTracks, 1)
        XCTAssertEqual(s.firstAt, 3)
    }

    func test_should_nameAlbumsFromPlaysAndVisits_when_lookupIsBuilt() {
        let play = PlayEvent(id: "p", scope: "https://b|me", provider: "subsonic", songId: "s1", albumId: "al1",
                             artistId: nil, title: "Song", album: "Played Album", artist: "Art", coverKey: "c1",
                             startedAt: 10, durationSec: 100)
        let visit = ActivityRecord(id: "v", scope: "https://b|me", provider: "subsonic", entity: .album, action: .visited,
                                   entityId: "al2", title: "Visited Album", subtitle: "Art2", coverKey: "c2",
                                   songId: nil, albumId: "al2", artistId: nil, timestamp: 20)
        let l = MemoryLedgerSource.lookup(plays: [play], activities: [visit], active: sub, details: [:])
        XCTAssertEqual(l.album(other, "al1")?.name, "Played Album")
        XCTAssertEqual(l.album(other, "al1")?.account, other)
        XCTAssertEqual(l.album(other, "al2")?.coverKey, "c2")
        XCTAssertNil(l.album(sub, "al1"), "another account's plays don't name this account's albums")
        XCTAssertEqual(l.song(other, "s1")?.albumId, "al1")
    }
}
