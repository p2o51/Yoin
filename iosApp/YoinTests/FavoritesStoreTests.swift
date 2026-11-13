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

    /// Codex C10 + 2026-11-10 review #18: one write per song at a time, the
    /// newest intent last; a failure goes back to what the source confirmed.
    func test_should_sendNewestIntentInOrder_andRollBackToConfirmed() async {
        let hearts = FavoritesStore()
        var release: [CheckedContinuation<Void, Error>] = []
        func send() async throws { try await withCheckedThrowingContinuation { release.append($0) } }
        // off → on (A) goes out; then on → off (B), off → on (C) while it's in flight: C waits, B is superseded.
        hearts.write(provider: "subsonic", songId: "s1", target: true, was: false, send: send)
        await until { release.count == 1 }
        guard release.count == 1 else { return XCTFail("the first write never went out") }
        hearts.write(provider: "subsonic", songId: "s1", target: false, was: true, send: send)
        hearts.write(provider: "subsonic", songId: "s1", target: true, was: false, send: send)
        for _ in 0..<5 { await Task.yield() }
        XCTAssertEqual(release.count, 1, "one write in flight per song")
        XCTAssertTrue(hearts.isFavorite(provider: "subsonic", songId: "s1"))
        // A fails: C is queued, so the heart stays on and C goes out.
        release[0].resume(throwing: URLError(.timedOut))
        await until { release.count == 2 }
        guard release.count == 2 else { return XCTFail("the queued write never went out") }
        XCTAssertTrue(hearts.isFavorite(provider: "subsonic", songId: "s1"))
        // C fails too: back to what the source holds (off).
        release[1].resume(throwing: URLError(.timedOut))
        for _ in 0..<5 { await Task.yield() }
        XCTAssertFalse(hearts.isFavorite(provider: "subsonic", songId: "s1"))
    }

    /// Codex review #18's case: off, on goes out, off while it's in flight, the on fails: ends off, not a fake heart.
    func test_should_endOff_when_quickOnOffAndFirstFails() async {
        let hearts = FavoritesStore()
        var release: [CheckedContinuation<Void, Error>] = []
        func send() async throws { try await withCheckedThrowingContinuation { release.append($0) } }
        hearts.write(provider: "subsonic", songId: "s2", target: true, was: false, send: send)
        await until { release.count == 1 }
        guard release.count == 1 else { return XCTFail("the first write never went out") }
        hearts.write(provider: "subsonic", songId: "s2", target: false, was: true, send: send)
        release[0].resume(throwing: URLError(.timedOut))
        for _ in 0..<5 { await Task.yield() }
        XCTAssertEqual(release.count, 1, "off is what the source already holds: nothing to send")
        XCTAssertFalse(hearts.isFavorite(provider: "subsonic", songId: "s2"))
    }

    /// On then off before the first write even went out: nothing to tell the source.
    func test_should_sendNothing_when_tapsCancelOutBeforeSending() async {
        let hearts = FavoritesStore()
        var sent = 0
        func send() async throws { sent += 1 }
        hearts.write(provider: "subsonic", songId: "s4", target: true, was: false, send: send)
        hearts.write(provider: "subsonic", songId: "s4", target: false, was: true, send: send)
        for _ in 0..<10 { await Task.yield() }
        XCTAssertEqual(sent, 0)
        XCTAssertFalse(hearts.isFavorite(provider: "subsonic", songId: "s4"))
    }

    /// Yields until [done], at most 200 turns (a hang fails the test instead of the run).
    private func until(_ done: () -> Bool) async {
        for _ in 0..<200 where !done() { await Task.yield() }
    }

    func test_should_reportNothing_when_successLandsAfterReset() async {
        let hearts = FavoritesStore()
        var release: [CheckedContinuation<Void, Error>] = []
        func send() async throws { try await withCheckedThrowingContinuation { release.append($0) } }
        hearts.write(provider: "subsonic", songId: "s3", target: true, was: false, send: send)
        await until { release.count == 1 }
        guard release.count == 1 else { return XCTFail("the write never went out") }
        hearts.reset()
        release[0].resume()
        for _ in 0..<5 { await Task.yield() }
        XCTAssertFalse(hearts.hasReport(provider: "subsonic", songId: "s3"))
    }

    func test_should_forgetReports_when_reset() {
        let hearts = FavoritesStore()
        hearts.report(true, provider: "subsonic", songId: "s9")
        hearts.reset()
        XCTAssertFalse(hearts.hasReport(provider: "subsonic", songId: "s9"))
    }
}
