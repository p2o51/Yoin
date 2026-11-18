import XCTest
@testable import Yoin

/// What offline downloads' Live Activity counts and says (Data/Cache/DownloadLiveActivity.swift: DownloadRun).
final class DownloadRunTests: XCTestCase {
    private typealias Song = OfflineDownloads.Song

    private func song(_ key: String, _ state: Song.State, in collection: String = "Blue Rev") -> Song {
        Song(key: key, scope: "s", songId: key, title: key, artist: "a", album: collection, albumId: nil,
             durationSec: 200, quality: .kbps320,
             collections: [.init(kind: .album, id: collection, title: collection)], state: state)
    }

    func test_should_countTheSongsWaiting_andTheOnesDoneSinceItBegan() throws {
        var run = DownloadRun()
        var songs = [song("old", .done), song("a", .downloading), song("b", .queued), song("c", .queued)]
        run.follow(songs)
        var state = try XCTUnwrap(run.state(songs, partial: ("a", 0.5), waiting: nil))
        // A song downloaded before the run isn't counted.
        XCTAssertEqual(state.total, 3)
        XCTAssertEqual(state.done, 0)
        XCTAssertEqual(state.progress, 0.5 / 3, accuracy: 0.001)
        XCTAssertEqual(state.stage, .downloading)
        XCTAssertEqual(state.title, "Blue Rev")

        songs[1].state = .done
        songs[2].state = .downloading
        run.follow(songs)
        state = try XCTUnwrap(run.state(songs, partial: ("a", 1), waiting: nil))
        XCTAssertEqual(state.done, 1)
        // The finished song's last bytes don't count twice.
        XCTAssertEqual(state.progress, 1.0 / 3, accuracy: 0.001)
    }

    func test_should_takeInSongsQueuedWhileItLasts_andLetRemovedOnesGo() throws {
        var run = DownloadRun()
        var songs = [song("a", .downloading), song("b", .queued)]
        run.follow(songs)
        songs.append(song("x", .queued, in: "Night Drive"))
        songs.append(song("y", .queued, in: "Night Drive"))
        songs.removeAll { $0.key == "b" }
        run.follow(songs)
        let state = try XCTUnwrap(run.state(songs, partial: nil, waiting: nil))
        XCTAssertEqual(run.keys, ["a", "x", "y"])
        XCTAssertEqual(state.total, 3)
        XCTAssertEqual(state.title, "Blue Rev")
        XCTAssertEqual(state.more, 1)
    }

    func test_should_sayWhyItWaits_whileSongsAreLeft() throws {
        var run = DownloadRun()
        let songs = [song("a", .queued)]
        run.follow(songs)
        XCTAssertEqual(run.state(songs, partial: nil, waiting: .waitingForWiFi)?.stage, .waitingForWiFi)
        XCTAssertEqual(run.state(songs, partial: nil, waiting: .waitingForNetwork)?.stage, .waitingForNetwork)
    }

    func test_should_sayWhatCameOfIt_whenNothingWaits() throws {
        var run = DownloadRun()
        var songs = [song("a", .queued), song("b", .queued)]
        run.follow(songs)
        songs[0].state = .done
        songs[1].state = .failed
        run.follow(songs)
        // Waiting no longer matters once nothing is left to download.
        let state = try XCTUnwrap(run.state(songs, partial: nil, waiting: .waitingForWiFi))
        XCTAssertEqual(state.stage, .finished)
        XCTAssertEqual(state.done, 1)
        XCTAssertEqual(state.failed, 1)
        XCTAssertEqual(state.progress, 1)
    }

    func test_should_beEmpty_whenEverySongOfItIsRemoved() {
        var run = DownloadRun()
        run.follow([song("a", .queued)])
        run.follow([])
        XCTAssertNil(run.state([], partial: nil, waiting: nil))
        // Nothing waiting, nothing begun.
        var idle = DownloadRun()
        idle.follow([song("old", .done)])
        XCTAssertNil(idle.state([song("old", .done)], partial: nil, waiting: nil))
    }
}
