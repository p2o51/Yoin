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
