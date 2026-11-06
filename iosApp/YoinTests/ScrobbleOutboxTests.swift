import XCTest
@testable import Yoin

/// Subsonic plays waiting for the server (C-F3, Android PendingScrobbles): kept across launches with
/// their own time, per account, and only removed once sent.
@MainActor
final class ScrobbleOutboxTests: XCTestCase {
    private var url: URL!

    override func setUp() {
        url = FileManager.default.temporaryDirectory.appendingPathComponent("outbox-\(UUID().uuidString).json")
    }

    override func tearDown() { try? FileManager.default.removeItem(at: url) }

    func test_should_keep_plays_with_their_time_when_relaunched() {
        let outbox = ScrobbleOutbox(fileURL: url)
        let play = PendingScrobble(scope: "srv|me", songId: "s1", timeMs: 1_700_000_000_000)
        outbox.add(play)
        let reopened = ScrobbleOutbox(fileURL: url)
        XCTAssertEqual(reopened.queue, [play])
    }

    func test_should_send_only_to_its_own_account_oldest_first() {
        let outbox = ScrobbleOutbox(fileURL: url)
        outbox.add(.init(scope: "a", songId: "s1", timeMs: 1))
        outbox.add(.init(scope: "b", songId: "s2", timeMs: 2))
        outbox.add(.init(scope: "a", songId: "s3", timeMs: 3))
        XCTAssertEqual(outbox.next(for: "a")?.songId, "s1")
        XCTAssertEqual(outbox.next(for: "b")?.songId, "s2")
        XCTAssertNil(outbox.next(for: "c"))
    }

    func test_should_leave_only_once_removed_and_drop_duplicates() {
        let outbox = ScrobbleOutbox(fileURL: url)
        let play = PendingScrobble(scope: "a", songId: "s1", timeMs: 1)
        outbox.add(play)
        outbox.add(play)
        XCTAssertEqual(outbox.queue.count, 1)
        outbox.remove(play)
        XCTAssertNil(outbox.next(for: "a"))
        XCTAssertTrue(ScrobbleOutbox(fileURL: url).queue.isEmpty)
    }

    func test_should_drop_oldest_when_past_the_cap() {
        let outbox = ScrobbleOutbox(fileURL: nil)
        for i in 0...ScrobbleOutbox.maxPending { outbox.add(.init(scope: "a", songId: "s\(i)", timeMs: Int64(i))) }
        XCTAssertEqual(outbox.queue.count, ScrobbleOutbox.maxPending)
        XCTAssertEqual(outbox.next(for: "a")?.songId, "s1")
    }

    func test_should_keep_play_when_failure_is_not_the_servers_answer() {
        XCTAssertFalse(LibraryStore.scrobbleRefused(URLError(.notConnectedToInternet)))
    }
}
