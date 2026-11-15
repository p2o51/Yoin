import XCTest
@testable import Yoin

/// Audit 11-15 #18: songs Spotify played while Yoin was away go into history once.
final class SpotifyListeningBackfillTests: XCTestCase {
    typealias Backfill = SpotifyListeningBackfill

    private func items(_ json: String) throws -> [SpotifyDTO.PlayHistory] {
        try SpotifyWebAPI.decoder.decode(SpotifyDTO.RecentlyPlayed.self, from: Data(json.utf8)).items
    }

    private func heard(_ id: String, at: Int64, sec: Double = 200) -> Backfill.Heard {
        Backfill.Heard(songId: id, playedAt: at, durationSec: sec, title: id, album: "", albumId: nil, artist: "",
                       artistId: nil, coverURL: nil)
    }

    func test_should_readSongsOldestFirst_and_leaveOutEpisodesAndLocalFiles_when_parsing() throws {
        let list = try items("""
        {"items":[
          {"played_at":"2026-11-15T10:05:00.500Z","track":{"id":"b","name":"B","duration_ms":180000,"type":"track",
            "artists":[{"id":"ar1","name":"One"},{"id":"ar2","name":"Two"}],
            "album":{"id":"al","name":"Al","images":[{"url":"https://i/s","width":64},{"url":"https://i/l","width":640}]}}},
          {"played_at":"2026-11-15T10:00:00Z","track":{"id":"a","name":"A","duration_ms":120000}},
          {"played_at":"2026-11-15T09:00:00Z","track":{"id":"e","name":"Ep","type":"episode"}},
          {"played_at":"2026-11-15T09:00:00Z","track":{"name":"Local","is_local":true}},
          {"track":{"id":"x","name":"No time"}}
        ]}
        """)
        let heard = Backfill.heard(list)
        XCTAssertEqual(heard.map(\.songId), ["a", "b"])
        let b = heard[1]
        XCTAssertEqual(b.artist, "One", "the first artist, as App Remote names it")
        XCTAssertEqual(b.artistId, "ar1")
        XCTAssertEqual(b.albumId, "al")
        XCTAssertEqual(b.durationSec, 180)
        XCTAssertEqual(b.coverURL, URL(string: "https://i/l"))
        XCTAssertEqual(b.playedAt - heard[0].playedAt, 300_500)
    }

    func test_should_skipAPlayRecordedLive_when_theSameSongIsInHistoryNearItsTime() {
        let t: Int64 = 1_000_000_000
        let out = Backfill.missing([heard("a", at: t), heard("b", at: t + 400_000)], after: 0,
                                   existing: [(songId: "a", startedAt: t - 150_000)])
        XCTAssertEqual(out.map(\.songId), ["b"])
    }

    func test_should_recordAgain_when_theSameSongWasPlayedLongBefore() {
        let t: Int64 = 1_000_000_000
        let out = Backfill.missing([heard("a", at: t)], after: 0, existing: [(songId: "a", startedAt: t - 3_600_000)])
        XCTAssertEqual(out.map(\.songId), ["a"])
    }

    func test_should_leaveOutPlays_when_theyCameBeforeYoinLeftTheFront() {
        let out = Backfill.missing([heard("a", at: 100), heard("b", at: 300)], after: 200, existing: [])
        XCTAssertEqual(out.map(\.songId), ["b"])
    }

    func test_should_readPlayedAt_when_withOrWithoutFractionalSeconds() {
        XCTAssertEqual(Backfill.playedAtMs("1970-01-01T00:00:01.250Z"), 1250)
        XCTAssertEqual(Backfill.playedAtMs("1970-01-01T00:00:02Z"), 2000)
        XCTAssertNil(Backfill.playedAtMs("yesterday"))
    }
}
