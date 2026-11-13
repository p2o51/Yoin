import XCTest
@testable import Yoin

/// Subsonic ratings the server hasn't taken (I10, Android local_ratings.needsSync + syncPendingRatings):
/// kept across launches per account, sent with the newest value, and only removed once sent.
@MainActor
final class RatingOutboxTests: XCTestCase {
    private var dir: URL!
    private var url: URL { dir.appendingPathComponent("pending-ratings.json") }

    override func setUp() {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("rating-outbox-\(UUID().uuidString)")
    }

    override func tearDown() { try? FileManager.default.removeItem(at: dir) }

    private struct Offline: Error {}

    func test_should_keepSong_when_sendFails_and_relaunched() async {
        let outbox = RatingOutbox(fileURL: url)
        let e = RatingOutbox.Entry(scope: "a", songId: "s1")
        outbox.mark(e)
        let outcome = await outbox.push(e, next: { .send(4) }, send: { _ in throw Offline() })
        XCTAssertEqual(outcome, .kept)
        XCTAssertEqual(RatingOutbox(fileURL: url).pending, [e], "a failed rating survives a relaunch")
    }

    func test_should_removeSong_when_serverTookIt() async {
        let outbox = RatingOutbox(fileURL: url)
        let e = RatingOutbox.Entry(scope: "a", songId: "s1")
        outbox.mark(e)
        var sent: [Int32] = []
        let outcome = await outbox.push(e, next: { .send(5) }, send: { sent.append($0) })
        XCTAssertEqual(outcome, .sent)
        XCTAssertEqual(sent, [5])
        XCTAssertTrue(RatingOutbox(fileURL: url).pending.isEmpty)
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path), "an empty list leaves no file")
    }

    func test_should_markOnce_when_songRatedTwice() {
        let outbox = RatingOutbox(fileURL: nil)
        let e = RatingOutbox.Entry(scope: "a", songId: "s1")
        outbox.mark(e)
        outbox.mark(e)
        XCTAssertEqual(outbox.pending, [e])
    }

    func test_should_listOnlyThatAccount_when_askingWhatWaits() {
        let outbox = RatingOutbox(fileURL: nil)
        outbox.mark(.init(scope: "a", songId: "s1"))
        outbox.mark(.init(scope: "b", songId: "s1"))
        outbox.mark(.init(scope: "a", songId: "s2"))
        XCTAssertEqual(outbox.waiting(for: "a").map(\.songId), ["s1", "s2"])
        XCTAssertEqual(outbox.waiting(for: "b").map(\.songId), ["s1"])
    }

    func test_should_keepSong_when_anotherAccountIsInUse() async {
        let outbox = RatingOutbox(fileURL: nil)
        let e = RatingOutbox.Entry(scope: "a", songId: "s1")
        outbox.mark(e)
        var sent = 0
        let outcome = await outbox.push(e, next: { .hold }, send: { _ in sent += 1 })
        XCTAssertEqual(outcome, .kept)
        XCTAssertEqual(sent, 0)
        XCTAssertEqual(outbox.pending, [e])
    }

    func test_should_sendNewestValueLast_when_ratedAgainWhileSending() async {
        let outbox = RatingOutbox(fileURL: nil)
        let e = RatingOutbox.Entry(scope: "a", songId: "s1")
        var stored: Int32 = 2
        var sent: [Int32] = []
        outbox.mark(e)
        let outcome = await outbox.push(e, next: { .send(stored) }, send: { stars in
            sent.append(stars)
            guard sent.count == 1 else { return }
            // Rated 5 while the 2 was out: a second push hands its value to this one.
            stored = 5
            outbox.mark(e)
            let second = await outbox.push(e, next: { .send(stored) }, send: { sent.append($0) })
            XCTAssertEqual(second, .handedOff)
        })
        XCTAssertEqual(outcome, .sent)
        XCTAssertEqual(sent, [2, 5], "the newest rating goes last")
        XCTAssertTrue(outbox.pending.isEmpty)
    }

    func test_should_hideSongWhileSending_when_listingWhatWaits() async {
        let outbox = RatingOutbox(fileURL: nil)
        let e = RatingOutbox.Entry(scope: "a", songId: "s1")
        outbox.mark(e)
        _ = await outbox.push(e, next: { .send(3) }, send: { _ in
            XCTAssertTrue(outbox.waiting(for: "a").isEmpty, "a flush skips a song whose push is out")
            throw Offline()
        })
        XCTAssertEqual(outbox.waiting(for: "a"), [e])
    }

    func test_should_readNewestRatingOfThatAccount_when_choosingWhatToSend() {
        let ratingsURL = dir.appendingPathComponent("ratings.json")
        let scopes = AccountScope()
        scopes.adoptsLegacyRows = false
        let ratings = LocalRatings(fileURL: ratingsURL, scopes: scopes)
        scopes.subsonic = "a"
        ratings.set(7, provider: "subsonic", songId: "s1")
        ratings.set(9, provider: "subsonic", songId: "s2")
        ratings.clear(provider: "subsonic", songId: "s2")
        scopes.subsonic = "b"
        let s1 = RatingOutbox.Entry(scope: "a", songId: "s1")
        guard case .send(let stars) = RatingOutbox.next(s1, ratings: ratings, activeScope: "a") else {
            return XCTFail("a's rating goes to a while a is in use")
        }
        XCTAssertEqual(stars, 4, "7 / 2 rounds to 4 stars")
        guard case .send(let cleared) = RatingOutbox.next(.init(scope: "a", songId: "s2"), ratings: ratings,
                                                           activeScope: "a") else {
            return XCTFail("a cleared rating still goes out")
        }
        XCTAssertEqual(cleared, 0, "a clear removes the server's rating")
        guard case .hold = RatingOutbox.next(s1, ratings: ratings, activeScope: "b") else {
            return XCTFail("never sent to another account")
        }
        guard case .drop = RatingOutbox.next(.init(scope: "a", songId: "gone"), ratings: ratings, activeScope: "a") else {
            return XCTFail("no row: nothing to send")
        }
    }
}
