import XCTest
@testable import Yoin

/// The engine's stand-in URLs for Spotify songs, and the PKCE challenge.
@MainActor
final class SpotifyPlaybackTests: XCTestCase {
    func test_should_nameTheSpotifyTrack_when_readingTheEnginesURL() {
        let url = SpotifyAudioOutput.streamURL(trackId: "4uLU6hMCjMI75M1A2tKUQC")
        XCTAssertTrue(SpotifyAudioOutput.isSpotify(url))
        XCTAssertEqual(SpotifyAudioOutput.Item(url)?.trackURI, "spotify:track:4uLU6hMCjMI75M1A2tKUQC")
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

    // MARK: Play modes (Codex N03)

    private func item(_ id: String) -> SpotifyAudioOutput.Item {
        SpotifyAudioOutput.Item(SpotifyAudioOutput.streamURL(trackId: id))!
    }

    func test_should_rehand_when_spotifyWouldCarryOnToAStaleSong() {
        XCTAssertTrue(SpotifyAudioOutput.needsRehand(handed: item("a"), next: item("b"), remainingSec: 120))
        XCTAssertTrue(SpotifyAudioOutput.needsRehand(handed: item("a"), next: nil, remainingSec: nil))
    }

    func test_should_notRehand_when_theHandedSongIsStillNext() {
        XCTAssertFalse(SpotifyAudioOutput.needsRehand(handed: item("a"), next: item("a"), remainingSec: 120))
    }

    func test_should_notRehand_when_nothingWasHanded() {
        // The list runs out after the current song: its end starts the engine's next.
        XCTAssertFalse(SpotifyAudioOutput.needsRehand(handed: nil, next: item("b"), remainingSec: 120))
    }

    func test_should_notRehand_when_theSongIsAboutToEnd() {
        XCTAssertFalse(SpotifyAudioOutput.needsRehand(handed: item("a"), next: item("b"), remainingSec: 2))
    }

    func test_should_takeOverRepeatAndShuffle_when_turnedOnInSpotify() {
        XCTAssertNil(SpotifyAudioOutput.externalModes(shuffling: false, repeatRaw: 0))
        let one = SpotifyAudioOutput.externalModes(shuffling: false, repeatRaw: 1)
        XCTAssertNil(one?.shuffle)
        XCTAssertEqual(one?.repeatMode, .one)
        let both = SpotifyAudioOutput.externalModes(shuffling: true, repeatRaw: 2)
        XCTAssertEqual(both?.shuffle, true)
        XCTAssertEqual(both?.repeatMode, .all)
    }

    func test_should_readNoActiveDevice_when_spotifyAnswers404ForAPlayerCommand() {
        XCTAssertTrue(SpotifyAPIError.http(status: 404, message: "Player command failed: No active device found").isNoActiveDevice)
        XCTAssertFalse(SpotifyAPIError.http(status: 404, message: "Non existing id").isNoActiveDevice)
        XCTAssertFalse(SpotifyAPIError.http(status: 403, message: "No active device found").isNoActiveDevice)
    }

    func test_should_remoteForADevice_only_when_itIsPlaying() {
        // Run 1 of 51's log: the Pixel was Spotify's last device but asleep; the start went nowhere.
        let asleep = SpotifySource.PlayerNow(deviceId: "7a3d", deviceName: "Pixel 11 Pro", deviceType: "Smartphone", isPlaying: false)
        let playing = SpotifySource.PlayerNow(deviceId: "7a3d", deviceName: "Pixel 11 Pro", deviceType: "Smartphone", isPlaying: true)
        let phone = SpotifySource.PlayerNow(deviceId: "ecfc", deviceName: "iPhone", deviceType: "Smartphone", isPlaying: true)
        XCTAssertNil(SpotifyAudioOutput.remoteTarget(asleep, this: .phone))
        XCTAssertEqual(SpotifyAudioOutput.remoteTarget(playing, this: .phone), "7a3d")
        XCTAssertNil(SpotifyAudioOutput.remoteTarget(phone, this: .phone))
        XCTAssertNil(SpotifyAudioOutput.remoteTarget(nil, this: .phone))
    }

    func test_should_driveTheOtherDevice_when_spotifyIsActiveOnAMacOrPixel() {
        let mac = SpotifySource.PlayerNow(deviceId: "m", deviceName: "51's MacBook", deviceType: "Computer", isPlaying: true)
        let pixel = SpotifySource.PlayerNow(deviceId: "p", deviceName: "Pixel Tablet", deviceType: "Tablet", isPlaying: false)
        let phone = SpotifySource.PlayerNow(deviceId: "i", deviceName: "51's iPhone Air", deviceType: "Smartphone", isPlaying: true)
        XCTAssertFalse(SpotifyThisDevice.phone.matches(mac))
        XCTAssertFalse(SpotifyThisDevice.phone.matches(pixel))
        XCTAssertTrue(SpotifyThisDevice.phone.matches(phone))
    }

    func test_should_readSpotifysOwnSong_when_mirroringIt() {
        let s = SpotifyAudioOutput.State(uri: "spotify:track:1IEKU3WzzShCxANW5Jfv6g", isPaused: false, positionMs: 14657,
                                         durationMs: 171172, shuffling: false, repeatRaw: 0, title: "Song",
                                         artist: "Artist", album: "Album", coverURL: URL(string: "https://i.scdn.co/image/ab"))
        let t = SpotifyAudioOutput.externalTrack(s)
        XCTAssertEqual(t?.id, "1IEKU3WzzShCxANW5Jfv6g")
        XCTAssertEqual(t?.title, "Song")
        XCTAssertEqual(t?.durationSec ?? 0, 171.172, accuracy: 0.001)
        XCTAssertEqual(t?.coverURL?.absoluteString, "https://i.scdn.co/image/ab")
        XCTAssertNil(SpotifyAudioOutput.externalTrack(.init(uri: "", isPaused: true, positionMs: 0, durationMs: 0,
                                                            shuffling: false, repeatRaw: 0)))
        XCTAssertEqual(SpotifyAudioOutput.coverURL(imageIdentifier: "spotify:image:ab67616d0000b273")?.absoluteString,
                       "https://i.scdn.co/image/ab67616d0000b273")
    }

    /// A song Spotify plays on its own is handed off with its album, so the other device opens the album
    /// rather than a one-song queue.
    func test_should_carryTheAlbum_when_mirroringSpotifysOwnSong() {
        XCTAssertEqual(SpotifyAudioOutput.albumId(fromURI: "spotify:album:6akEvsycLGftJxYudPjmqK"), "6akEvsycLGftJxYudPjmqK")
        XCTAssertNil(SpotifyAudioOutput.albumId(fromURI: nil))
        XCTAssertNil(SpotifyAudioOutput.albumId(fromURI: ""))
        XCTAssertNil(SpotifyAudioOutput.albumId(fromURI: "spotify:album:"))
        XCTAssertNil(SpotifyAudioOutput.albumId(fromURI: "spotify:show:5CfCWKI5pZ28U0uOzXkDHe"))

        let song = SpotifyAudioOutput.State(uri: "spotify:track:1IEKU3WzzShCxANW5Jfv6g", isPaused: false, positionMs: 0,
                                            durationMs: 171172, shuffling: false, repeatRaw: 0, title: "Song",
                                            artist: "Artist", album: "Album", albumId: "6akEvsycLGftJxYudPjmqK")
        XCTAssertEqual(SpotifyAudioOutput.externalTrack(song)?.albumId, "6akEvsycLGftJxYudPjmqK")

        let episode = SpotifyAudioOutput.State(uri: "spotify:episode:512ojhOuo1ktJprKbVcKyQ", isPaused: false, positionMs: 0,
                                               durationMs: 1_800_000, shuffling: false, repeatRaw: 0, title: "Episode",
                                               artist: "Show", album: "Show", albumId: "5CfCWKI5pZ28U0uOzXkDHe")
        XCTAssertNil(SpotifyAudioOutput.externalTrack(episode)?.albumId)
    }
}
