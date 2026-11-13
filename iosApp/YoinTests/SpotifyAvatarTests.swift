import XCTest
@testable import Yoin

/// A Spotify account's profile picture (Android ProfileCard.photoUrl).
final class SpotifyAvatarTests: XCTestCase {
    func test_should_pickTheLargestPicture_when_meListsSeveral() throws {
        let json = #"{"id":"u1","display_name":"Kiri","images":[{"url":"https://i/s","width":64},{"url":"https://i/l","width":300}]}"#
        let me = try JSONDecoder().decode(SpotifyAuth.Me.self, from: Data(json.utf8))
        XCTAssertEqual(me.avatar, "https://i/l")
        let none = try JSONDecoder().decode(SpotifyAuth.Me.self, from: Data(#"{"id":"u2"}"#.utf8))
        XCTAssertNil(none.avatar)
    }

    func test_should_readSavedAccounts_when_theyPredateThePicture() throws {
        let tokens = #"{"accessToken":"a","refreshToken":"r","expiresAt":1,"scope":"","clientId":"c","userId":"u"}"#
        XCTAssertNil(try JSONDecoder().decode(SpotifyTokens.self, from: Data(tokens.utf8)).avatarURL)
        let profile = #"{"id":"spotify-u","service":"spotify","createdAt":1,"spotifyUserId":"u"}"#
        XCTAssertNil(try JSONDecoder().decode(Profile.self, from: Data(profile.utf8)).spotifyAvatar)
    }
}

/// The failure banner's action follows the failure (Android's typed Spotify connect failures).
final class PlaybackFailureActionTests: XCTestCase {
    func test_should_offerTheRightAction_when_aFailureIsShown() {
        XCTAssertEqual(PlaybackFailure.action(for: SpotifyFailureCopy.premium), .none)
        XCTAssertEqual(PlaybackFailure.action(for: SpotifyFailureCopy.openSpotify(on: "iPhone")), .openSpotify)
        XCTAssertEqual(PlaybackFailure.action(for: SpotifyFailureCopy.openSpotify(on: "iPad")), .openSpotify)
        // A Mac has two steps: Open Spotify (nothing plays), then Retry.
        XCTAssertEqual(PlaybackFailure.action(for: SpotifyFailureCopy.notOpenOnMac), .openSpotifyThenRetry)
        // The toast "Couldn't reach the server" isn't for a Spotify app that has to be opened.
        XCTAssertFalse(PlaybackFailure.isServerOutOfReach(SpotifyFailureCopy.notOpenOnMac))
        XCTAssertFalse(PlaybackFailure.isServerOutOfReach(SpotifyFailureCopy.openSpotify(on: "iPhone")))
        XCTAssertTrue(PlaybackFailure.isServerOutOfReach("The server didn't answer"))
        XCTAssertEqual(PlaybackFailure.action(for: String(localized: "Reconnect Spotify in Settings")), .settings)
        XCTAssertEqual(PlaybackFailure.action(for: String(localized: "Reconnect Spotify in Settings to let Yoin control playback")), .settings)
        XCTAssertEqual(PlaybackFailure.action(for: "The server didn't answer"), .retry)
        XCTAssertEqual(SpotifySource.message(for: SpotifyAPIError.http(status: 403, message: "Player command failed: Premium required")),
                       SpotifyFailureCopy.premium)
    }
}
