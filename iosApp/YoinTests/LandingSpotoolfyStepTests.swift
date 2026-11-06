import XCTest
@testable import Yoin

/// The landing's Spotoolfy step (Android LandingModels, LandingSpotoolfyStep): where it sits and what the pill does.
@MainActor
final class LandingSpotoolfyStepTests: XCTestCase {
    func test_should_skipSpotoolfy_when_spotifyIsNotPicked() {
        XCTAssertFalse(landingSteps(picked: [.subsonic, .appleMusic], hasAccount: true).contains(.spotoolfy))
        XCTAssertTrue(landingSteps(picked: [.spotify]).contains(.spotoolfy))
    }

    func test_should_walkSpotoolfyThenSpotify_when_spotifyIsPicked() {
        var flow = LandingFlow(mode: .firstRun)
        flow.toggle(.spotify)
        flow.goTo(.about)
        flow.next()
        XCTAssertEqual(flow.step, .spotoolfy)
        flow.next()
        XCTAssertEqual(flow.step, .spotify)
    }

    func test_should_refusePrimary_when_spotoolfyHasNoAnswerOrIsReading() {
        var flow = LandingFlow(mode: .firstRun)
        flow.toggle(.spotify)
        flow.goTo(.spotoolfy)
        XCTAssertTrue(flow.refusesPrimary(hasAccount: false, spotoolfy: SpotoolfyStepState()))
        XCTAssertTrue(flow.refusesPrimary(hasAccount: false, spotoolfy: SpotoolfyStepState(choice: .using, file: .reading)))
        XCTAssertFalse(flow.refusesPrimary(hasAccount: false, spotoolfy: SpotoolfyStepState(choice: .notUsing)))
        XCTAssertFalse(flow.refusesPrimary(hasAccount: false, spotoolfy: SpotoolfyStepState(choice: .using)))
    }

    func test_should_sayWhatThePillDoes_when_spotoolfyStateChanges() {
        let next = loc("Next")
        XCTAssertEqual(spotoolfyPrimary(SpotoolfyStepState()), LandingPrimary(label: next, style: .unavailable))
        XCTAssertEqual(spotoolfyPrimary(SpotoolfyStepState(choice: .notUsing)), LandingPrimary(label: next, style: .filled))
        XCTAssertEqual(spotoolfyPrimary(SpotoolfyStepState(choice: .using)),
                       LandingPrimary(label: loc("Choose the export file"), style: .filled))
        XCTAssertEqual(spotoolfyPrimary(SpotoolfyStepState(choice: .using, file: .failed(.corrupt))).label, loc("Choose the export file"))
        XCTAssertEqual(spotoolfyPrimary(SpotoolfyStepState(choice: .using, file: .reading)).style, .unavailable)
        XCTAssertEqual(spotoolfyPrimary(SpotoolfyStepState(choice: .using, file: .received(noteCount: 3, ratingCount: 1))),
                       LandingPrimary(label: next, style: .filled))
        XCTAssertEqual(spotoolfyPrimary(SpotoolfyStepState(choice: .using, file: .notWritten)).style, .filled)
    }

    func test_should_holdTheFileUntilSpotifyConnects_when_received() {
        XCTAssertTrue(SpotoolfyStepState(choice: .using, file: .received(noteCount: 1, ratingCount: 0)).unwritten)
        // Saying "No" afterwards, or a file already written, is nothing left to drop.
        XCTAssertFalse(SpotoolfyStepState(choice: .notUsing, file: .received(noteCount: 1, ratingCount: 0)).unwritten)
        XCTAssertFalse(SpotoolfyStepState(choice: .using, file: .written(noteCount: 1, ratingCount: 0, accountName: "x")).unwritten)
    }

    func test_should_askAboutSpotoolfy_when_mascotSpeaks() {
        XCTAssertEqual(landingBubble(.spotoolfy, noAccounts: false).title, loc("Using Spotoolfy?"))
    }

    func test_should_putSpotoolfyRightBeforeSpotify_when_everythingIsPicked() {
        XCTAssertEqual(landingSteps(picked: [.appleMusic, .spotify, .subsonic], hasAccount: true),
                       [.hello, .pick, .about, .subsonic, .spotoolfy, .spotify, .appleMusic, .home, .ready])
    }
}
