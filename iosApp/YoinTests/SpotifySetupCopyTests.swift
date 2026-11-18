import XCTest
@testable import Yoin

/// Settings' Spotify developer setup (ServiceSetupView.clientIdRow, Android SpotifyDeveloperGroup,
/// parity A-U2/D-U2): its copy in both languages; the URIs it lists are the ones sign-in uses.
final class SpotifySetupCopyTests: XCTestCase {
    func test_should_listTheTwoRedirectURIs_when_setUp() {
        XCTAssertEqual(SpotifyConfig.redirectURI, "yoin://auth/spotify/callback")
        XCTAssertEqual(SpotifyConfig.appRemoteRedirectURI, "yoin://auth/spotify/app-remote")
    }

    func test_should_sayAndroidsDeveloperCopy_when_localized() {
        let zh = Bundle.main.preferredLocalizations.first?.hasPrefix("zh") ?? false
        XCTAssertEqual(loc("Developer setup"), zh ? "开发者设置" : "Developer setup")
        XCTAssertEqual(loc("Not set"), zh ? "未设置" : "Not set")
        XCTAssertTrue(loc("Create an app at developer.spotify.com and register these redirect URIs:")
            .contains("developer.spotify.com"))
    }
}
