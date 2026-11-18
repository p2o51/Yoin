import XCTest
@testable import Yoin

@MainActor
final class SpotifyQueueReadTests: XCTestCase {
    private let entry = SpotifySource.QueueEntry(id: 0, uri: "spotify:track:a", title: "A", artist: "", coverURL: nil)

    func test_should_sayItFailed_notEmpty_when_theFirstReadFails() {
        XCTAssertEqual(SpotifyQueueRead.loading.afterFailedRead, .failed)
        XCTAssertEqual(SpotifyQueueRead.failed.afterFailedRead, .failed)
    }

    func test_should_keepTheListShown_when_aLaterReadFails() {
        XCTAssertEqual(SpotifyQueueRead.loaded([entry]).afterFailedRead, .loaded([entry]))
        XCTAssertEqual(SpotifyQueueRead.loaded([]).afterFailedRead, .loaded([]))
    }

    func test_should_keyRows_by_placeAndSong() {
        var again = entry
        again.id = 1
        XCTAssertNotEqual(entry.rowKey, again.rowKey, "the same song queued twice is two rows")
        var other = entry
        other.uri = "spotify:track:b"
        XCTAssertNotEqual(entry.rowKey, other.rowKey)
    }
}
