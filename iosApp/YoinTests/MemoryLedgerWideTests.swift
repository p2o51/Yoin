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
    // MARK: Review fixes: lazy views, the resolver's passes, the grid under Reduce Motion

    func test_should_cutBeforeEachFullLine_when_theTimelineIsSegmented() {
        let rows: [(String, Bool)] = [("a", false), ("m1", true), ("x", false), ("y", false), ("q", true), ("m2", true), ("z", false)]
        let segs = LedgerLazy.segments(rows) { $0.1 }.map { $0.map(\.0) }
        XCTAssertEqual(segs, [["a"], ["m1", "x", "y"], ["q"], ["m2", "z"]])
        XCTAssertEqual(LedgerLazy.segments([(String, Bool)]()) { $0.1 }.count, 0)
    }

    func test_should_layOutLikeOneMasonry_when_eachSegmentIsItsOwn() {
        // A full line starts every column level, so stacking the segments' masonries gives the whole one's heights.
        let items: [(fullLine: Bool, height: (CGFloat) -> CGFloat)] = [
            (true, { _ in 10 }), (false, { _ in 100 }), (false, { _ in 40 }), (false, { _ in 30 }),
            (true, { _ in 20 }), (false, { _ in 5 }), (false, { _ in 60 }), (false, { _ in 7 }),
        ]
        let whole = LedgerMasonry.arrange(width: 210, columns: 2, spacing: 10, rowSpacing: 0, items: items)
        let stacked = LedgerLazy.segments(items) { $0.fullLine }
            .map { LedgerMasonry.arrange(width: 210, columns: 2, spacing: 10, rowSpacing: 0, items: $0).height }
            .reduce(0, +)
        XCTAssertEqual(stacked, whole.height)
    }

    func test_should_dealEachCardToTheShortestLane_when_lanesAreAssigned() {
        XCTAssertEqual(LedgerLazy.lanes([100, 40, 30, 20, 50], count: 2, spacing: 10), [[0, 4], [1, 2, 3]])
        XCTAssertEqual(LedgerLazy.lanes([1, 1, 1], count: 1, spacing: 0), [[0, 1, 2]])
        XCTAssertEqual(LedgerLazy.lanes([1, 1, 1, 1], count: 3, spacing: 0), [[0, 3], [1], [2]])
    }

    func test_should_estimateTallerCards_when_theyHoldMore() {
        let a = LedgerAlbum(account: sub, albumId: "a", name: "A", artist: "", coverKey: nil)
        let bare = LedgerItem.album(album: a, title: nil, score: nil, review: nil, songs: [], at: 0)
        let full = LedgerItem.album(album: a, title: nil, score: 8, review: String(repeating: "words ", count: 60),
                                    songs: [.init(title: "s", score: 7, at: 0), .init(title: "t", score: 6, at: 0), .init(title: "u", score: 5, at: 0),
                                            .init(title: "v", score: 4, at: 0)], at: 0)
        XCTAssertGreaterThan(full.estimatedCardHeight(width: 180), bare.estimatedCardHeight(width: 180))
        // The review is capped at four lines and the songs at three, as the card draws them.
        let expected: CGFloat = 28 + 60 + 8 + 80 + 78  // padding, top row, review gap, 4 lines, 3 songs
        XCTAssertEqual(full.estimatedCardHeight(width: 180), expected)
        XCTAssertEqual(LedgerLazy.lines("abcd", width: 14, size: 14, max: 9), 2)
        XCTAssertEqual(LedgerLazy.lines("一二", width: 14, size: 14, max: 9), 2)
        XCTAssertEqual(LedgerLazy.lines("a\nb", width: 100, size: 14, max: 9), 2)
        XCTAssertEqual(LedgerLazy.lines(nil, width: 100, size: 14, max: 9), 0)
    }

    func test_should_fadeTheRealGridAgainstTheBar_when_reduceMotionIsOn() {
        XCTAssertEqual(MemoryGridLiftMath.realGridOpacity(progress: 0.05, lifting: true, reduced: true), 0.95, accuracy: 1e-9)
        XCTAssertEqual(MemoryGridLiftMath.realGridOpacity(progress: 1, lifting: true, reduced: true), 0)
        XCTAssertEqual(MemoryGridLiftMath.realGridOpacity(progress: 0, lifting: false, reduced: true), 1)
        // Squares in flight stand in for it: hidden at once.
        XCTAssertEqual(MemoryGridLiftMath.realGridOpacity(progress: 0.05, lifting: true, reduced: false), 0)
        XCTAssertEqual(MemoryGridLiftMath.realGridOpacity(progress: 0, lifting: false, reduced: false), 1)
    }

    func test_should_notAskASongTwice_when_aSecondPassStartsWhileTheFirstIsAsking() async {
        let resolver = LedgerTrackResolver()
        let w = LedgerTrackResolver.Wanted(account: sub, songId: "s1")
        let box = Box()
        let first = Task { @MainActor in
            await resolver.resolve([w]) { _ in
                box.calls += 1
                await withCheckedContinuation { box.release = $0 }
                return LedgerTrack(title: "T", albumId: "al", albumName: "Al", artist: nil, coverKey: nil)
            }
        }
        while box.release == nil { await Task.yield() }
        XCTAssertEqual(resolver.asked, [w.key])
        XCTAssertTrue(LedgerTrackResolver.pending(
            [LedgerEntry(id: "1", kind: .trackRating, at: now, account: sub, album: nil, songId: "s1", score: 7)],
            asked: resolver.asked).isEmpty)
        let second = await resolver.resolve([w]) { _ in box.calls += 1; return nil }
        XCTAssertEqual(second.answered, 0)
        box.release?.resume()
        let pass = await first.value
        XCTAssertEqual(pass, .init(answered: 1, found: true))
        XCTAssertEqual(box.calls, 1)
        XCTAssertTrue(resolver.inFlight.isEmpty)
    }

    func test_should_stopAndLeaveSongsUnanswered_when_thePassIsCancelled() async {
        let resolver = LedgerTrackResolver()
        let wanted = (1...8).map { LedgerTrackResolver.Wanted(account: sub, songId: "s\($0)") }
        let box = Box()
        let task = Task { @MainActor in
            await resolver.resolve(wanted) { _ in
                box.calls += 1
                try await Task.sleep(for: .seconds(30))
                return nil
            }
        }
        while box.calls < LedgerTrackResolver.parallel { await Task.yield() }
        task.cancel()
        let pass = await task.value
        // The first batch's lookups were cancelled with it; no later batch was asked, nothing counts as answered.
        XCTAssertEqual(box.calls, LedgerTrackResolver.parallel)
        XCTAssertEqual(pass.answered, 0)
        XCTAssertTrue(resolver.answers.isEmpty)
        XCTAssertTrue(resolver.inFlight.isEmpty)
    }
}

/// Counts and a held lookup, shared with the resolver's main-actor closures.
@MainActor
private final class Box {
    var calls = 0
    var release: CheckedContinuation<Void, Never>?
}
