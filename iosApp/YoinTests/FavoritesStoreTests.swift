import XCTest
@testable import Yoin

@MainActor
final class FavoritesStoreTests: XCTestCase {
    func test_should_agreeEverywhere_when_heartSetInOnePlace() {
        let hearts = FavoritesStore()
        var written: [String] = []
        hearts.onWrite = { _, id in written.append(id) }
        // Album row: the album said off.
        XCTAssertFalse(hearts.isFavorite(provider: "subsonic", songId: "s1", known: false))
        hearts.toggle(provider: "subsonic", songId: "s1", known: false, client: nil, apple: nil)
        // Now Playing's copy still says off: the tap wins.
        XCTAssertTrue(hearts.isFavorite(provider: "subsonic", songId: "s1", known: false))
        XCTAssertTrue(hearts.isFavorite(provider: "subsonic", songId: "s1"))
        // And back from Now Playing.
        hearts.toggle(provider: "subsonic", songId: "s1", known: true, client: nil, apple: nil)
        XCTAssertFalse(hearts.isFavorite(provider: "subsonic", songId: "s1", known: true))
        XCTAssertEqual(written, ["s1", "s1"])
    }

    func test_should_preferCallerCopy_overReport_when_untouched() {
        let hearts = FavoritesStore()
        hearts.report(true, provider: "subsonic", songId: "s2")
        hearts.report(false, provider: "subsonic", songId: "s2")
        XCTAssertTrue(hearts.isFavorite(provider: "subsonic", songId: "s2"), "the first report stands")
        XCTAssertFalse(hearts.isFavorite(provider: "subsonic", songId: "s2", known: false))
    }

    func test_should_onlyAdd_when_appleMusic() {
        let hearts = FavoritesStore()
        hearts.toggle(provider: "applemusic", songId: "a1", known: true, client: nil, apple: nil)
        XCTAssertTrue(hearts.isFavorite(provider: "applemusic", songId: "a1", known: true), "an added song stays added")
        hearts.toggle(provider: "applemusic", songId: "a2", known: false, client: nil, apple: nil)
        XCTAssertFalse(hearts.isFavorite(provider: "applemusic", songId: "a2", known: false), "no Apple Music: nothing to add to")
    }

    func test_should_forgetTaps_when_reset() {
        let hearts = FavoritesStore()
        hearts.toggle(provider: "subsonic", songId: "s3", known: false, client: nil, apple: nil)
        hearts.reset()
        XCTAssertFalse(hearts.isFavorite(provider: "subsonic", songId: "s3", known: false))
    }

    /// Codex C10: an older write's failure never undoes a newer tap, and nothing lands after a reset.
    func test_should_rollBackOnlyTheNewestTap_when_writesFailOutOfOrder() async {
        let hearts = FavoritesStore()
        var release: [CheckedContinuation<Void, Error>] = []
        func send() async throws { try await withCheckedThrowingContinuation { release.append($0) } }
        // off → on (A), on → off (B), off → on (C): three requests in flight.
        hearts.write(provider: "subsonic", songId: "s1", target: true, was: false, send: send)
        hearts.write(provider: "subsonic", songId: "s1", target: false, was: true, send: send)
        hearts.write(provider: "subsonic", songId: "s1", target: true, was: false, send: send)
        while release.count < 3 { await Task.yield() }
        XCTAssertTrue(hearts.isFavorite(provider: "subsonic", songId: "s1"))
        // A (an older true) fails: the newest tap (C, also true) stands.
        release[0].resume(throwing: URLError(.timedOut))
        for _ in 0..<5 { await Task.yield() }
        XCTAssertTrue(hearts.isFavorite(provider: "subsonic", songId: "s1"))
        // C fails: back to what C replaced.
        release[2].resume(throwing: URLError(.timedOut))
        for _ in 0..<5 { await Task.yield() }
        XCTAssertFalse(hearts.isFavorite(provider: "subsonic", songId: "s1"))

        // After a reset (another account) a late success reports nothing.
        hearts.reset()
        release[1].resume()
        for _ in 0..<5 { await Task.yield() }
        XCTAssertFalse(hearts.hasReport(provider: "subsonic", songId: "s1"))
    }

    func test_should_forgetReports_when_reset() {
        let hearts = FavoritesStore()
        hearts.report(true, provider: "subsonic", songId: "s9")
        hearts.reset()
        XCTAssertFalse(hearts.hasReport(provider: "subsonic", songId: "s9"))
    }
}
