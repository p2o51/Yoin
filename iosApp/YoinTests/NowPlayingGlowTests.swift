import XCTest
@testable import Yoin

/// Android ActionGlowTest + TransportPulseTest, for what the iOS lights decide.
@MainActor
final class NowPlayingGlowTests: XCTestCase {
    private func key(_ shuffle: Bool, _ mode: RepeatMode) -> PlayModeKey { PlayModeKey(shuffle: shuffle, repeatMode: mode) }

    func test_should_pickShuffleLight_when_shuffleTurns() {
        XCTAssertEqual(PlayModeKey.glowKind(from: key(false, .off), to: key(true, .off)), .shuffleOn)
        XCTAssertEqual(PlayModeKey.glowKind(from: key(true, .all), to: key(false, .all)), .shuffleOff)
    }

    func test_should_pickRepeatLight_when_repeatTurns() {
        XCTAssertEqual(PlayModeKey.glowKind(from: key(false, .off), to: key(false, .all)), .repeatAll)
        XCTAssertEqual(PlayModeKey.glowKind(from: key(false, .all), to: key(false, .one)), .repeatOne)
        XCTAssertEqual(PlayModeKey.glowKind(from: key(false, .one), to: key(false, .off)), .repeatOff)
    }

    func test_should_lightNothing_when_modeUnchanged() {
        XCTAssertNil(PlayModeKey.glowKind(from: key(true, .one), to: key(true, .one)))
    }

    func test_should_preferShuffle_when_bothAxesChange() {
        XCTAssertEqual(PlayModeKey.glowKind(from: key(false, .off), to: key(true, .one)), .shuffleOn)
    }

    func test_should_handOver_when_playModeTappedAgain() {
        let glow = NowPlayingGlow()
        glow.glow(.shuffleOn, at: CGRect(x: 0, y: 0, width: 10, height: 10))
        glow.glow(.repeatAll, at: CGRect(x: 0, y: 0, width: 10, height: 10))
        XCTAssertNotNil(glow.lights[0].retired)
        XCTAssertNil(glow.lights[1].retired)
    }

    func test_should_burstFromButton_when_playTappedHere() {
        let glow = NowPlayingGlow()
        glow.report(.play, CGRect(x: 10, y: 20, width: 100, height: 60))
        glow.tapped(.playPause)
        glow.playingChanged(to: true)
        let burst = glow.lights.last
        XCTAssertEqual(burst?.kind, .playBurst)
        XCTAssertEqual(burst?.anchor, CGRect(x: 10, y: 20, width: 100, height: 60))
    }

    func test_should_burstFromNeutralPoint_when_playCameFromElsewhere() {
        let glow = NowPlayingGlow()
        glow.playingChanged(to: false)
        XCTAssertEqual(glow.lights.last?.kind, .pauseBurst)
        XCTAssertNil(glow.lights.last?.anchor)
    }

    func test_should_notBreathe_when_skipDipsPlayback() {
        let glow = NowPlayingGlow()
        glow.report(.next, CGRect(x: 0, y: 0, width: 64, height: 64))
        glow.tapped(.next)
        glow.playingChanged(to: false)
        XCTAssertFalse(glow.lights.contains { $0.kind == .pauseBurst || $0.kind == .playBurst })
    }

    func test_should_sweepFromTappedSide_when_coverLeads() {
        let glow = NowPlayingGlow()
        glow.report(.previous, CGRect(x: 0, y: 0, width: 64, height: 64))
        glow.tapped(.previous)
        glow.songChanged(forward: false, lyricsLead: false, inPlace: false)
        XCTAssertEqual(glow.lights.last?.kind, .sideFlow)
        XCTAssertEqual(glow.lights.last?.value, -1)
    }

    func test_should_notSweep_when_songChangedUntapped() {
        let glow = NowPlayingGlow()
        glow.songChanged(forward: true, lyricsLead: false, inPlace: false)
        XCTAssertTrue(glow.lights.isEmpty)
    }

    func test_should_flowWithLyrics_when_lyricsLead() {
        let glow = NowPlayingGlow()
        glow.songChanged(forward: true, lyricsLead: true, inPlace: false)
        XCTAssertEqual(glow.lights.last?.kind, .flowLight)
        XCTAssertEqual(glow.lights.last?.value, 1)
    }

    func test_should_notFlow_when_lyricsHandedOverInPlace() {
        let glow = NowPlayingGlow()
        glow.songChanged(forward: true, lyricsLead: true, inPlace: true)
        XCTAssertTrue(glow.lights.isEmpty)
    }

    func test_should_leaveOutTravellingLights_when_reduceMotion() {
        let glow = NowPlayingGlow()
        glow.reduceMotion = true
        glow.report(.next, CGRect(x: 0, y: 0, width: 64, height: 64))
        glow.tapped(.next)
        glow.glow(.favoriteOn, at: CGRect(x: 0, y: 0, width: 10, height: 10))
        XCTAssertEqual(glow.lights.map(\.kind), [.favoriteOn])
    }

    func test_should_placeRatingFromBottom() {
        let bar = CGRect(x: 0, y: 100, width: 20, height: 200)
        XCTAssertEqual(ratingGlowPoint(bar, 0).y, 300)
        XCTAssertEqual(ratingGlowPoint(bar, 10).y, 100)
        XCTAssertEqual(ratingGlowPoint(bar, 5).y, 200)
    }
}
