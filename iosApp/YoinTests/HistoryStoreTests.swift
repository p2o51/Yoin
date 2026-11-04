import SwiftUI
import XCTest
@testable import Yoin

@MainActor
final class HistoryStoreTests: XCTestCase {
    private let day = HomeHistory.dayMs
    private let now: Int64 = 1_800_000_000_000

    private func store(_ url: URL? = nil) -> HistoryStore {
        HistoryStore(fileURL: url, now: { Date(timeIntervalSince1970: 1_800_000_000) })
    }

    private func album(_ id: String, tracks: Int = 0, rating: Double? = nil) -> Album {
        let ts = (0..<tracks).map { Track(id: MediaId(provider: "subsonic", rawId: "\(id)-\($0)"), number: $0 + 1,
                                          title: "T\($0)", durationSec: 200) }
        return Album(id: MediaId(provider: "subsonic", rawId: id), title: "Album \(id)", artist: "Artist",
                     year: 2020, palette: CoverPalette(a: .red, b: .green, c: .blue), tracks: ts, rating: rating)
    }

    @discardableResult
    private func play(_ h: HistoryStore, _ song: String, album: String?, at: Int64, scope: String = "s",
                      provider: String = "subsonic") -> String {
        h.recordPlay(scope: scope, provider: provider, songId: song, albumId: album, artistId: nil, title: song,
                     album: album.map { "Album \($0)" } ?? "", artist: "Artist", coverKey: album, durationSec: 200,
                     at: at)
    }

    // MARK: Store

    func test_should_persistPlaysAndActivities_when_reloaded() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let url = dir.appendingPathComponent("history.json")
        let h = store(url)
        let id = play(h, "a-0", album: "a", at: now - 1000)
        h.markListened(playId: id, listenedSec: 120, completed: true)
        h.recordAlbumVisit(scope: "s", provider: "subsonic", albumId: "b", title: "B", artist: "X", artistId: nil,
                           coverKey: nil, at: now)

        let reloaded = store(url)
        XCTAssertEqual(reloaded.plays.count, 1)
        XCTAssertEqual(reloaded.plays[0].listenedSec, 120)
        XCTAssertTrue(reloaded.plays[0].completed)
        XCTAssertEqual(reloaded.activities.map(\.action), [.played, .visited])
    }

    func test_should_keepAccountsApart_when_scopeOrProviderDiffers() {
        let h = store()
        play(h, "x", album: "a", at: now - 10)
        play(h, "x", album: "a", at: now - 5, scope: "other")
        play(h, "x", album: "a", at: now - 1, provider: "applemusic")
        XCTAssertEqual(h.plays(scope: "s", provider: "subsonic").count, 1)
        XCTAssertEqual(h.albumStats(scope: "s", provider: "subsonic", albumId: "a")?.playCount, 1)
        XCTAssertEqual(h.mostRecentPlay(scope: "s", provider: "subsonic", songId: "x")?.startedAt, now - 10)
    }

    func test_should_aggregateAlbums_when_queried() {
        let h = store()
        play(h, "a-0", album: "a", at: now - 300 * day)
        play(h, "a-1", album: "a", at: now - 100 * day)
        play(h, "a-1", album: "a", at: now - 2 * day)
        play(h, "b-0", album: "b", at: now - 200 * day)
        play(h, "single", album: nil, at: now - day)

        let a = h.albumStats(scope: "s", provider: "subsonic", albumId: "a")
        XCTAssertEqual(a?.playCount, 3)
        XCTAssertEqual(a?.firstPlayedAt, now - 300 * day)
        XCTAssertEqual(a?.lastPlayedAt, now - 2 * day)
        XCTAssertEqual(a?.latest.songId, "a-1")
        XCTAssertEqual(h.songPlayCounts(scope: "s", provider: "subsonic", albumId: "a"), ["a-0": 1, "a-1": 2])
        XCTAssertEqual(h.lastPlayedAlbums(scope: "s", provider: "subsonic").map(\.albumId), ["a", "b"])
        XCTAssertEqual(h.albumsNotPlayedSince(now - 90 * day, scope: "s", provider: "subsonic").map(\.albumId), ["b"])
        XCTAssertEqual(h.recentPlays(scope: "s", provider: "subsonic", limit: 2).map(\.songId), ["single", "a-1"])
    }

    func test_should_collapseActivities_when_sameEntityRepeats() {
        let h = store()
        play(h, "a-0", album: "a", at: now - 30)
        play(h, "b-0", album: "b", at: now - 20)
        play(h, "a-1", album: "a", at: now - 10)
        h.recordAlbumVisit(scope: "s", provider: "subsonic", albumId: "b", title: "B", artist: "", artistId: nil,
                           coverKey: nil, at: now - 5)
        let rows = h.recentActivities(scope: "s", provider: "subsonic")
        XCTAssertEqual(rows.map { "\($0.action):\($0.entityId)" }, ["visited:b", "played:a", "played:b"])
        XCTAssertEqual(rows[1].timestamp, now - 10)
    }

    // MARK: HomeHistory

    private func inputs(_ h: HistoryStore, details: [Album], notes: [SongNote] = [],
                        ratings: [String: Double] = [:]) -> HomeHistory.Inputs {
        let byId = Dictionary(uniqueKeysWithValues: details.map { ($0.id.rawId, $0) })
        return HomeHistory.Inputs(now: now, plays: h.plays(scope: "s", provider: "subsonic"), notes: notes,
                                  details: byId, known: [:], localRating: { ratings[$0] },
                                  resolve: { id, _ in byId[id] })
    }

    private func note(_ track: String, at: Int64) -> SongNote {
        SongNote(id: UUID().uuidString, provider: "subsonic", trackId: track, content: "words on \(track)",
                 createdAt: at, updatedAt: at, title: "Song \(track)", artist: "Artist", positionMs: nil)
    }

    func test_should_markAlbums_when_ratedOrNoted() {
        let h = store()
        play(h, "r-0", album: "r", at: now - day)
        play(h, "n-0", album: "n", at: now - day)
        play(h, "p-0", album: "p", at: now - day)
        let albums = [album("r", tracks: 5), album("n", tracks: 5), album("p", tracks: 5), album("s", tracks: 5, rating: 8)]
        play(h, "s-0", album: "s", at: now - day)
        let m = HomeHistory.memories(inputs(h, details: albums, notes: [note("n-1", at: now)], ratings: ["r-0": 7]))
        let marks = HomeHistory.marks(m)
        XCTAssertEqual(marks["r"], .outline)
        XCTAssertEqual(marks["n"], .dashed)
        XCTAssertEqual(marks["s"], .solid)
        XCTAssertNil(marks["p"])
    }

    func test_should_pickRatedAlbumSignal_when_coverageMeetsGate() {
        let h = store()
        play(h, "low-0", album: "low", at: now - day)
        play(h, "full-0", album: "full", at: now - day)
        let ratings = ["low-0": 9.0, "full-0": 8, "full-1": 7, "full-2": 9, "full-3": 8]
        let m = HomeHistory.memories(inputs(h, details: [album("low", tracks: 5), album("full", tracks: 5)],
                                            ratings: ratings))
        let s = HomeHistory.ratedAlbumSignal(m)
        XCTAssertEqual(s?.album.id.rawId, "full")
        XCTAssertEqual(s?.score, "8.0")
        XCTAssertEqual(s?.basis, "Based on 4/5 tracks")
    }

    func test_should_pickNewestNote_when_buildingNotedTrack() {
        let h = store()
        play(h, "a-1", album: "a", at: now - day)
        let notes = [note("a-0", at: now - 2 * day), note("a-1", at: now - day)]
        let x = inputs(h, details: [album("a", tracks: 3)], notes: notes, ratings: ["a-1": 6])
        let s = HomeHistory.notedTrackSignal(x) { _ in self.album("placeholder") }
        XCTAssertEqual(s?.title, "Song a-1")
        XCTAssertEqual(s?.score, "6.0")
        XCTAssertEqual(s?.comment, "words on a-1")
        XCTAssertEqual(s?.album.id.rawId, "a")
        XCTAssertEqual(s?.song?.track.id.rawId, "a-1")
        XCTAssertEqual(s?.basis, HomeHistory.memoryDate(now - day))
    }

    func test_should_bringBackAlbums_when_awayNinetyDaysWithAMemory() {
        let h = store()
        play(h, "scored-0", album: "scored", at: now - 200 * day)
        play(h, "noted-0", album: "noted", at: now - 400 * day)
        play(h, "fresh-0", album: "fresh", at: now - 10 * day)
        play(h, "bare-0", album: "bare", at: now - 300 * day)
        let ratings = ["scored-0": 9.0, "scored-1": 9, "scored-2": 8, "fresh-0": 9]
        let albums = ["scored", "noted", "fresh", "bare"].map { album($0, tracks: 4) }
        let m = HomeHistory.memories(inputs(h, details: albums, notes: [note("noted-1", at: now - 120 * day)],
                                            ratings: ratings))
        let shelf = HomeHistory.rediscover(m, now: now, exclude: [])
        XCTAssertEqual(shelf.map(\.album.id.rawId), ["scored", "noted"])
        XCTAssertEqual(shelf[0].reason, "")
        XCTAssertEqual(shelf[0].away, "6 months away")
        XCTAssertEqual(shelf[0].score.map { String(format: "%.2f", $0) }, "8.67")
        XCTAssertEqual(shelf[1].reason, "1 note")
        XCTAssertEqual(shelf[1].away, "1 year away")
        XCTAssertEqual(shelf[1].footnotePlays, "1")
        XCTAssertEqual(shelf[1].footnoteDate, HomeHistory.yearMonth(now - 400 * day))
        XCTAssertTrue(HomeHistory.rediscover(m, now: now, exclude: ["scored", "noted"]).isEmpty)
    }

    func test_should_putEveryThirdCardANoteCard_when_mixing() {
        let mem = AlbumMemory(album: album("x"), totalTracks: 0, ratedTrackCount: 0, coverage: 0,
                              averageSongRating: nil, albumRating: nil, noteCount: 1, lastWrittenAt: nil,
                              playCount: 0, firstPlayedAt: nil, lastPlayedAt: nil, isMemoryEligible: false)
        func pick(_ id: String, notes: Bool) -> HomeHistory.RediscoverPick {
            var m = mem
            m.album = album(id)
            return .init(memory: m, score: nil, lastPlayedAt: 0, fromNotes: notes)
        }
        let listening = (0..<5).map { pick("l\($0)", notes: false) }
        let notes = (0..<3).map { pick("n\($0)", notes: true) }
        XCTAssertEqual(HomeHistory.mix(listening, notes).map(\.memory.albumId), ["l0", "l1", "n0", "l2", "l3", "n1"])
        XCTAssertEqual(HomeHistory.mix([], notes).map(\.memory.albumId), ["n0", "n1", "n2"])
    }

    func test_should_formatTimesLikeAndroid() {
        XCTAssertEqual(HomeHistory.timeAgo(now - 20_000, now: now), "just now")
        XCTAssertEqual(HomeHistory.timeAgo(now - 12 * 60_000, now: now), "12m ago")
        XCTAssertEqual(HomeHistory.timeAgo(now - 2 * 3_600_000, now: now), "2h ago")
        XCTAssertEqual(HomeHistory.timeAgo(now - 3 * day, now: now), "3d ago")
        XCTAssertEqual(HomeHistory.timeAgo(now - 15 * day, now: now), "2w ago")
        XCTAssertEqual(HomeHistory.awayEyebrow(lastPlayedAt: now - 95 * day, now: now), "3 months away")
        XCTAssertEqual(HomeHistory.awayEyebrow(lastPlayedAt: now - 800 * day, now: now), "2 years away")
        XCTAssertEqual(HomeHistory.scoreText(nil), "N/A")
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "UTC")!
        XCTAssertEqual(HomeHistory.relativeDay(now - 3_600_000, now: now, calendar: cal), "Today")
        XCTAssertEqual(HomeHistory.relativeDay(now - day, now: now, calendar: cal), "Yesterday")
        XCTAssertEqual(HomeHistory.relativeDay(now - 3 * day, now: now, calendar: cal), "3 days ago")
    }

    func test_should_leadActivitiesWithHistory_when_buildingHome() {
        let recent = [album("srv1"), album("srv2"), album("srv3"), album("srv4")]
        var overlay = LibraryStore.HomeOverlay()
        overlay.now = now
        overlay.hasHistory = true
        overlay.activities = [(album("mine"), now - 2 * 3_600_000)]
        let feed = LibraryStore.buildHome(recent: recent, frequent: [], newest: [], highest: [album("hi", rating: 8)],
                                          random: [], recentTracks: [], history: overlay)
        XCTAssertEqual(feed.activities.map(\.album.id.rawId), ["mine", "srv1", "srv2", "srv3"])
        XCTAssertEqual(feed.activities[0].eyebrow, "2h ago")
        XCTAssertEqual(feed.activities[1].eyebrow, "")
        XCTAssertTrue(feed.rediscover.isEmpty, "history's rule: nothing kept and away = no shelf")

        let plain = LibraryStore.buildHome(recent: recent, frequent: [], newest: [], highest: [album("hi", rating: 8)],
                                           random: [], recentTracks: [])
        XCTAssertEqual(plain.rediscover.map(\.reason), ["Rated"])
    }
}
