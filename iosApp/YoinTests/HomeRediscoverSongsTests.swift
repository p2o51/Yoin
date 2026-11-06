import SwiftUI
import XCTest
@testable import Yoin

/// Rediscover brings back rated and noted songs next to albums, and a short release comes back
/// as songs (Android RediscoverSelection.kt: selectRediscoverSongs / selectRediscoverShelf).
@MainActor
final class HomeRediscoverSongsTests: XCTestCase {
    private let day = HomeHistory.dayMs
    private let now: Int64 = 1_800_000_000_000

    private func play(_ song: String, album: String?, at: Int64, title: String? = nil) -> PlayEvent {
        PlayEvent(id: UUID().uuidString, scope: "s", provider: "subsonic", songId: song, albumId: album, artistId: nil,
                  title: title ?? "Song \(song)", album: album.map { "Album \($0)" } ?? "", artist: "Artist",
                  coverKey: album, startedAt: at, durationSec: 200)
    }

    private func note(_ song: String, _ text: String, at: Int64 = 1) -> SongNote {
        SongNote(id: UUID().uuidString, provider: "subsonic", trackId: song, content: text, createdAt: at, updatedAt: at,
                 title: "Song \(song)", artist: "Artist")
    }

    private func inputs(plays: [PlayEvent], notes: [SongNote] = [], ratings: [String: Double] = [:]) -> HomeHistory.Inputs {
        HomeHistory.Inputs(now: now, plays: plays, notes: notes, details: [:], known: [:],
                           localRating: { ratings[$0] }, resolve: { _, _ in nil })
    }

    private func album(_ id: String, tracks: Int, lastPlayed: Int64, rated: Int = 1) -> AlbumMemory {
        AlbumMemory(album: Album(id: MediaId(provider: "subsonic", rawId: id), title: "Album \(id)", artist: "Artist", year: 2020,
                                 palette: CoverPalette(a: .red, b: .green, c: .blue), tracks: []),
                    totalTracks: tracks, ratedTrackCount: rated, coverage: 0.8, averageSongRating: 8, albumRating: 9,
                    noteCount: 0, lastWrittenAt: lastPlayed, playCount: 3, firstPlayedAt: lastPlayed, lastPlayedAt: lastPlayed,
                    isMemoryEligible: true)
    }

    private func carrier(_ s: HomeHistory.SongMemory) -> Album {
        Album(id: MediaId(provider: "subsonic", rawId: s.albumId.isEmpty ? "song:\(s.songId)" : s.albumId), title: s.album,
              artist: s.artist, year: 0, palette: CoverPalette(a: .red, b: .green, c: .blue), tracks: [])
    }

    func test_should_listRatedAndNotedPlayedSongs_when_buildingSongMemories() {
        let x = inputs(plays: [play("s1", album: "al1", at: 10), play("s1", album: "al1", at: 30), play("s2", album: nil, at: 20),
                               play("s3", album: "al1", at: 25)],
                       notes: [note("s2", "  "), note("s2", "late train", at: 5), note("s2", "newest", at: 9),
                               note("unplayed", "never played")],
                       ratings: ["s1": 8.5])
        let songs = HomeHistory.songMemories(x).sorted { $0.songId < $1.songId }
        // s3 has neither; "unplayed" has no play row; the blank note counts for nothing.
        XCTAssertEqual(songs.map(\.songId), ["s1", "s2"])
        XCTAssertEqual(songs[0].playCount, 2)
        XCTAssertEqual(songs[0].firstPlayedAt, 10)
        XCTAssertEqual(songs[0].lastPlayedAt, 30)
        XCTAssertEqual(songs[0].rating, 8.5)
        XCTAssertEqual(songs[1].noteCount, 2)
        XCTAssertEqual(songs[1].latestNote, "newest")
        XCTAssertEqual(songs[1].albumId, "")
    }

    func test_should_orderAndExcludeSongs_when_selecting() {
        func memory(_ id: String, rating: Double?, last: Int64, album: String = "al", note: String? = nil) -> HomeHistory.SongMemory {
            HomeHistory.SongMemory(songId: id, provider: "subsonic", title: id, artist: "A", album: "Al", albumId: album,
                                   coverKey: nil, durationSec: 200, playCount: 1, firstPlayedAt: last, lastPlayedAt: last,
                                   rating: rating, noteCount: note == nil ? 0 : 1, latestNote: note)
        }
        let old = now - 200 * day, older = now - 300 * day, recent = now - 10 * day
        let songs = [memory("noted", rating: nil, last: older, note: "n"), memory("low", rating: 6, last: old, album: "a2"),
                     memory("high", rating: 9, last: old, album: "a3"), memory("fresh", rating: 10, last: recent),
                     memory("nothing", rating: nil, last: old, album: "a4")]
        let picks = HomeHistory.selectRediscoverSongs(songs, now: now)
        // Best score first, then the unscored; a play inside 90 days or no memory never qualifies.
        XCTAssertEqual(picks.map(\.song.songId), ["high", "low", "noted"])
        XCTAssertEqual(HomeHistory.selectRediscoverSongs(songs, now: now, excludeSongIds: ["high"]).map(\.song.songId),
                       ["low", "noted"])
        XCTAssertEqual(HomeHistory.selectRediscoverSongs(songs, now: now, excludeAlbumIds: ["a2"]).map(\.song.songId),
                       ["high", "noted"])
    }

    func test_should_comeBackAsSongs_when_releaseHasUnderFourTracks() {
        let ep = album("ep", tracks: 3, lastPlayed: now - 200 * day, rated: 1)
        // Not memory-eligible: it never passed the Memory gates, and 3 tracks can't be an album card.
        var short = ep
        short.isMemoryEligible = false
        XCTAssertTrue(HomeHistory.selectRediscover([short], now: now, exclude: []).isEmpty)
        let song = HomeHistory.SongMemory(songId: "t1", provider: "subsonic", title: "Late Train", artist: "Quiet", album: "EP",
                                          albumId: "ep", coverKey: nil, durationSec: 180, playCount: 4,
                                          firstPlayedAt: now - 400 * day, lastPlayedAt: now - 200 * day, rating: 8.5,
                                          noteCount: 0, latestNote: nil)
        let picks = HomeHistory.selectRediscoverSongs([song], now: now)
        let shelf = HomeHistory.rediscover([short], now: now, exclude: [], songs: picks, songAlbum: carrier)
        XCTAssertEqual(shelf.count, 1)
        XCTAssertEqual(shelf[0].song?.track.id.rawId, "t1")
        XCTAssertEqual(shelf[0].song?.track.title, "Late Train")
        XCTAssertEqual(shelf[0].score, 8.5)
        XCTAssertEqual(shelf[0].away, loc("\(6) months away"))
        // A score badges the song, so no note line.
        XCTAssertNil(shelf[0].noteSnippet)
    }

    func test_should_showNoteLineOnlyOnUnscoredSong_when_buildingSongCard() {
        let noted = HomeHistory.SongMemory(songId: "t2", provider: "subsonic", title: "Paper Kites", artist: "Ena", album: "",
                                           albumId: "", coverKey: nil, durationSec: 0, playCount: 1, firstPlayedAt: 1,
                                           lastPlayedAt: now - 130 * day, rating: nil, noteCount: 1,
                                           latestNote: " the bridge at 2:10 ")
        let shelf = HomeHistory.rediscover([], now: now, exclude: [],
                                           songs: HomeHistory.selectRediscoverSongs([noted], now: now), songAlbum: carrier)
        XCTAssertEqual(shelf.first?.noteSnippet, "the bridge at 2:10")
        XCTAssertNil(shelf.first?.score)
        // An album-less song stands in for itself; one second of track at least.
        XCTAssertEqual(shelf.first?.song?.track.durationSec, 1)
        XCTAssertEqual(shelf.first?.song?.album.id.rawId, "song:t2")
    }

    func test_should_dropSongOfShelfAlbum_when_albumIsOnTheShelf() {
        let al = album("al", tracks: 10, lastPlayed: now - 200 * day)
        let sameAlbum = HomeHistory.SongMemory(songId: "t3", provider: "subsonic", title: "T", artist: "A", album: "Album al",
                                               albumId: "al", coverKey: nil, durationSec: 100, playCount: 1, firstPlayedAt: 1,
                                               lastPlayedAt: now - 300 * day, rating: 9.9, noteCount: 0, latestNote: nil)
        let other = HomeHistory.SongMemory(songId: "t4", provider: "subsonic", title: "T4", artist: "A", album: "Other",
                                           albumId: "other", coverKey: nil, durationSec: 100, playCount: 1, firstPlayedAt: 1,
                                           lastPlayedAt: now - 300 * day, rating: 1, noteCount: 0, latestNote: nil)
        let shelf = HomeHistory.selectRediscoverShelf(HomeHistory.selectRediscover([al], now: now, exclude: []),
                                                      HomeHistory.selectRediscoverSongs([sameAlbum, other], now: now))
        // The album (9.0) leads, the song of that album (9.9) would have led but stays off, the other song follows.
        XCTAssertEqual(shelf.count, 2)
        if case .album(let p) = shelf[0] { XCTAssertEqual(p.memory.albumId, "al") } else { XCTFail("album first") }
        if case .song(let p) = shelf[1] { XCTAssertEqual(p.song.songId, "t4") } else { XCTFail("song second") }
    }

    func test_should_keepAlbumFirst_when_albumAndSongTie() {
        let al = album("al", tracks: 10, lastPlayed: now - 200 * day)
        let tie = HomeHistory.SongMemory(songId: "t5", provider: "subsonic", title: "T", artist: "A", album: "O", albumId: "o",
                                         coverKey: nil, durationSec: 100, playCount: 1, firstPlayedAt: 1,
                                         lastPlayedAt: now - 200 * day, rating: 9, noteCount: 0, latestNote: nil)
        let shelf = HomeHistory.selectRediscoverShelf(HomeHistory.selectRediscover([al], now: now, exclude: []),
                                                      HomeHistory.selectRediscoverSongs([tie], now: now))
        if case .album = shelf[0] {} else { XCTFail("album first on a tie") }
    }
}
