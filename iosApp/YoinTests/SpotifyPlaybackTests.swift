import XCTest
@testable import Yoin

/// The engine's stand-in URLs for Spotify songs, and the PKCE challenge.
@MainActor
final class SpotifyPlaybackTests: XCTestCase {
    func test_should_carryTheAlbumAsContext_when_aSongHasOne() {
        let url = SpotifyAudioOutput.streamURL(trackId: "4uLU6hMCjMI75M1A2tKUQC", albumId: "6akEvsycLGftJxYudPjmqK")
        XCTAssertTrue(SpotifyAudioOutput.isSpotify(url))
        let item = SpotifyAudioOutput.Item(url)
        XCTAssertEqual(item?.trackURI, "spotify:track:4uLU6hMCjMI75M1A2tKUQC")
        XCTAssertEqual(item?.contextURI, "spotify:album:6akEvsycLGftJxYudPjmqK")
    }

    func test_should_playABareSong_when_thereIsNoAlbum() {
        let item = SpotifyAudioOutput.Item(SpotifyAudioOutput.streamURL(trackId: "abc", albumId: nil))
        XCTAssertEqual(item?.trackURI, "spotify:track:abc")
        XCTAssertNil(item?.contextURI)
    }

    func test_should_notReadAnotherSourcesURL_when_parsing() {
        XCTAssertNil(SpotifyAudioOutput.Item(URL(string: "https://example.com/rest/stream?id=1")))
        XCTAssertFalse(SpotifyAudioOutput.isSpotify(AppleMusicMapping.streamURL(rawId: "1")))
    }

    /// RFC 7636 appendix B.
    func test_should_matchTheRFCExample_when_hashingTheVerifier() {
        XCTAssertEqual(SpotifyAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
                       "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
    }

    func test_should_askToSignInAgain_when_aRequiredScopeIsMissing() {
        var t = SpotifyTokens(accessToken: "a", refreshToken: "r", expiresAt: 0,
                              scope: SpotifyConfig.scopes.joined(separator: " "), clientId: "c", userId: "u")
        XCTAssertFalse(t.missingRequiredScopes)
        t.scope = "user-read-private"
        XCTAssertTrue(t.missingRequiredScopes)
    }
}
