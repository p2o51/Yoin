import XCTest
@testable import Yoin

/// Android edgeBandLife and DotDissolve's curve, as the two-column focus uses them.
@MainActor
final class NowPlayingEdgeBandTests: XCTestCase {
    func test_should_burstToWidest_when_bandIsAFifthOfASecondOld() {
        XCTAssertEqual(EdgeBandLife.at(0).burst, 0, accuracy: 1e-9)
        XCTAssertEqual(EdgeBandLife.at(EdgeBandLife.burstRiseS).burst, 1, accuracy: 1e-9)
        XCTAssertLessThan(EdgeBandLife.at(1).burst, 0.1)
    }

    func test_should_holdThenRecede_when_bandAges() {
        XCTAssertEqual(EdgeBandLife.at(1.9).fade, 1)
        XCTAssertEqual(EdgeBandLife.at(5).fade, 0.5, accuracy: 1e-9)
        XCTAssertEqual(EdgeBandLife.at(EdgeBandLife.goneS).fade, 0)
    }

    func test_should_slowTheWaves_when_paused() {
        let playing = EdgeBandClock(), paused = EdgeBandClock()
        let t0 = Date(timeIntervalSinceReferenceDate: 0)
        for clock in [playing, paused] { clock.advance(to: t0, playing: 0) }
        playing.advance(to: t0.addingTimeInterval(0.1), playing: 1)
        paused.advance(to: t0.addingTimeInterval(0.1), playing: 0)
        XCTAssertEqual(playing.phase, 0.1, accuracy: 1e-9)
        XCTAssertEqual(paused.phase, 0.1 * EdgeBandLife.pausedWaveRate, accuracy: 1e-9)
    }

    func test_should_leaveTheViewWhole_when_retreatHasNotStarted() {
        XCTAssertNil(DotDissolve.radius(0))
        XCTAssertEqual(DotDissolve.radius(1), 0)
        XCTAssertEqual(DotDissolve.opacity(1), 0)
    }
}
