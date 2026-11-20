import XCTest
@testable import Yoin

final class SpotifyDeveloperGuideTests: XCTestCase {
    func test_should_extract32HexClientId_when_pastedWithSurroundingText() {
        let raw = "  Client ID: a1b2c3d4e5f60718293a4b5c6d7e8f90 \n"
        XCTAssertEqual(SpotifyClientIdExtractor.extract(from: raw), "a1b2c3d4e5f60718293a4b5c6d7e8f90")
    }

    func test_should_preserveTrimmedValue_when_pastedTextIsNot32Hex() {
        XCTAssertEqual(SpotifyClientIdExtractor.extract(from: "  custom-id-123  "), "custom-id-123")
        XCTAssertEqual(SpotifyClientIdExtractor.extract(from: "   \n "), "")
    }

    func test_should_provideSixIosStepsWithRedirectUrisAndBundleId() {
        let steps = SpotifyGuideStep.iosSteps(
            redirectURI: "yoin://spotify/callback",
            appRemoteRedirectURI: "yoin://spotify-app-remote/callback",
            bundleId: "com.gpo.yoin"
        )
        XCTAssertEqual(steps.count, 6)
        XCTAssertEqual(steps[1].copyValue, "Yoin")
        XCTAssertEqual(steps[2].copyValue, "yoin://spotify/callback")
        XCTAssertEqual(steps[3].copyValue, "yoin://spotify-app-remote/callback")
        XCTAssertEqual(steps[4].copyValue, "com.gpo.yoin")
    }
}
