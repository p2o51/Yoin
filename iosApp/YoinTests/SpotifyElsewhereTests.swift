import XCTest
@testable import Yoin

/// Home's pill for another Spotify account's playback (HomeSpotifyElsewhere.swift).
final class SpotifyElsewhereTests: XCTestCase {
    private func e(_ id: String, playing: Bool) -> SpotifyElsewhere {
        SpotifyElsewhere(profileId: id, title: "Pink + White", artist: "Frank Ocean", coverURL: nil, isPlaying: playing)
    }

    func testPlayingAccountWinsOverPausedOne() {
        XCTAssertEqual(SpotifyElsewhere.pick([e("paused", playing: false), e("playing", playing: true)])?.profileId, "playing")
        XCTAssertEqual(SpotifyElsewhere.pick([e("paused", playing: false)])?.profileId, "paused")
        XCTAssertNil(SpotifyElsewhere.pick([]))
    }

    func testHiddenWhileYoinPlays() {
        XCTAssertTrue(SpotifyElsewhere.visible(e("a", playing: true), yoinPlaying: false))
        XCTAssertFalse(SpotifyElsewhere.visible(e("a", playing: true), yoinPlaying: true))
        XCTAssertFalse(SpotifyElsewhere.visible(nil, yoinPlaying: false))
    }

    func testShowsFiveSeconds() {
        XCTAssertEqual(SpotifyElsewhere.showSeconds, 5)
    }

    // MARK: The count runs only while the header can be seen (Android 8cd973e6c)

    func testCountsOnlyWithAnAnswerOnScreen() {
        XCTAssertTrue(SpotifyElsewhere.counts(shown: true, onScreen: true))
        XCTAssertFalse(SpotifyElsewhere.counts(shown: true, onScreen: false))
        XCTAssertFalse(SpotifyElsewhere.counts(shown: false, onScreen: true))
    }

    func testHeaderOnScreenOnlyWhenLoadedAndNothingOverIt() {
        func on(feed: Bool = true, active: Bool = true, np: Bool = false, column: Bool = false,
                pushed: Bool = false, search: Bool = false, memories: Bool = false) -> Bool {
            SpotifyElsewhere.headerOnScreen(feedLoaded: feed, sceneActive: active, nowPlaying: np, detailColumn: column,
                                            pushed: pushed, search: search, memories: memories)
        }
        XCTAssertTrue(on())
        XCTAssertFalse(on(feed: false), "Home still loading")
        XCTAssertFalse(on(active: false), "app not in front")
        XCTAssertFalse(on(np: true), "under Now Playing")
        XCTAssertFalse(on(column: true), "a detail column open")
        XCTAssertFalse(on(pushed: true))
        XCTAssertFalse(on(search: true))
        XCTAssertFalse(on(memories: true))
    }

    /// The waits the watch asked for; each one holds until released (or cancelled).
    @MainActor
    private final class Clock {
        var asked: [Duration] = []
        var waiting: [CheckedContinuation<Void, Error>] = []
        func elapse() { waiting.forEach { $0.resume() }; waiting.removeAll() }
    }

    @MainActor
    private func watch(_ clock: Clock) -> SpotifyElsewhereWatch {
        let w = SpotifyElsewhereWatch()
        w.sleep = { d in
            await MainActor.run { clock.asked.append(d) }
            try await withTaskCancellationHandler {
                try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
                    Task { @MainActor in
                        if Task.isCancelled { c.resume(throwing: CancellationError()) } else { clock.waiting.append(c) }
                    }
                }
            } onCancel: {
                Task { @MainActor in
                    clock.waiting.forEach { $0.resume(throwing: CancellationError()) }
                    clock.waiting.removeAll()
                }
            }
        }
        return w
    }

    @MainActor
    private func settle() async { for _ in 0..<20 { await Task.yield() } }

    @MainActor
    func testAnswerArrivingUnseenWaitsForTheHeader() async {
        let clock = Clock()
        let w = watch(clock)
        w.show(e("a", playing: true))
        await settle()
        XCTAssertTrue(clock.asked.isEmpty, "no count while the header can't be seen")
        XCTAssertNotNil(w.shown)

        w.setOnScreen(true)
        await settle()
        XCTAssertEqual(clock.asked, [.seconds(5)])
        clock.elapse()
        await settle()
        XCTAssertNil(w.shown, "gone after 5 s on screen")
    }

    @MainActor
    func testCoveredMidCountStopsAndAReturnGetsTheWholeFiveSeconds() async {
        let clock = Clock()
        let w = watch(clock)
        w.setOnScreen(true)
        w.show(e("a", playing: true))
        await settle()
        XCTAssertEqual(clock.asked, [.seconds(5)])

        w.setOnScreen(false) // Now Playing opens over Home
        await settle()
        clock.elapse() // the old wait would have run out by now
        await settle()
        XCTAssertNotNil(w.shown, "the count stopped while covered")

        w.setOnScreen(true)
        await settle()
        XCTAssertEqual(clock.asked, [.seconds(5), .seconds(5)], "a return starts over at 5 s")
        clock.elapse()
        await settle()
        XCTAssertNil(w.shown)
    }

    @MainActor
    func testDismissStopsTheCount() async {
        let clock = Clock()
        let w = watch(clock)
        w.setOnScreen(true)
        w.show(e("a", playing: true))
        await settle()
        w.dismiss()
        XCTAssertNil(w.shown)
        w.setOnScreen(false)
        w.setOnScreen(true)
        await settle()
        XCTAssertEqual(clock.asked.count, 1, "nothing to count once dismissed")
    }
}
