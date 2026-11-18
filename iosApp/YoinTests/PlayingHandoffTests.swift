import XCTest
@testable import Yoin

/// Handoff of what's playing (App/PlayingHandoff.swift): the payload's round trip, in the native Mac's
/// shape too, the spot a hand-off starts at, and which account here it belongs to (Android ContinueOn's
/// rules). The hand-off itself needs two devices and is not covered here.
@MainActor
final class PlayingHandoffTests: XCTestCase {
    private func subsonic(_ id: String, _ url: String, _ user: String) -> Profile {
        Profile(id: id, service: .subsonic, baseUrl: url, username: user, createdAt: 1)
    }

    private func spotify(_ id: String, user: String?) -> Profile {
        Profile(id: id, service: .spotify, createdAt: 2, spotifyUserId: user)
    }

    private let apple = Profile(id: ProfileStore.appleMusicId, service: .appleMusic, createdAt: 3)

    private func sample() -> HandoffPlaying {
        HandoffPlaying(provider: "subsonic", songId: "so-12", title: "Low Tide", artist: "Ines Varga",
                       album: "Northern Field Recordings", albumId: "al-field", playlistId: "pl-commute",
                       account: "s-0123456789abcdef01234567", positionSec: 83, playing: true, sentAt: 1_000)
    }

    // MARK: Payload

    func test_should_readBackTheSamePayload_when_itGoesThroughAnActivity() {
        let sent = sample()
        let activity = NSUserActivity(activityType: HandoffPlaying.activityType)
        activity.userInfo = sent.userInfo
        XCTAssertEqual(HandoffPlaying(userInfo: activity.userInfo), sent)
        // Through a property list too, as Handoff carries it between devices.
        let data = try? PropertyListSerialization.data(fromPropertyList: sent.userInfo, format: .binary, options: 0)
        let back = data.flatMap { try? PropertyListSerialization.propertyList(from: $0, format: nil) } as? [String: Any]
        XCTAssertEqual(HandoffPlaying(userInfo: back), sent)
    }

    func test_should_readTheMacsPayload_when_itHasNoAccountOrClock() {
        // YoinMac MacHandoff.fill's keys and types, as it sends them.
        let mac: [AnyHashable: Any] = ["provider": "spotify", "songId": "4uLU6hMCjMI75M1A2tKUQC", "title": "Song",
                                       "artist": "Artist", "album": "Album", "positionSec": 42, "playing": false,
                                       "albumId": "6akEvsycLGftJxYudPjmqK"]
        let h = HandoffPlaying(userInfo: mac)
        XCTAssertEqual(h?.provider, "spotify")
        XCTAssertEqual(h?.albumId, "6akEvsycLGftJxYudPjmqK")
        XCTAssertNil(h?.account)
        XCTAssertNil(h?.sentAt)
        XCTAssertNil(h?.playlistId)
        XCTAssertEqual(h?.positionSec, 42)
        XCTAssertEqual(h?.playing, false)
    }

    func test_should_keepTheMacsKeys_when_iOSSends() {
        let macKeys: Set<String> = ["provider", "songId", "title", "artist", "album", "positionSec", "playing", "albumId"]
        XCTAssertTrue(macKeys.isSubset(of: Set(sample().userInfo.keys)))
        XCTAssertEqual(HandoffPlaying.activityType, "com.gpo.yoin.playing")
    }

    func test_should_refusePayload_when_theServiceOrSongIsMissing() {
        XCTAssertNil(HandoffPlaying(userInfo: nil))
        XCTAssertNil(HandoffPlaying(userInfo: ["songId": "1", "positionSec": 3]))
        XCTAssertNil(HandoffPlaying(userInfo: ["provider": "subsonic", "songId": ""]))
    }

    func test_should_carryNoSignIn_when_aSubsonicAccountSends() {
        let p = subsonic("a", "https://music.example.org:4533/navi", "alice")
        var h = sample()
        h.account = HandoffAccounts.key(of: p)
        let text = "\(h.userInfo)"
        XCTAssertFalse(text.contains("example.org"))
        XCTAssertFalse(text.contains("alice"))
        XCTAssertTrue(h.account?.hasPrefix("s-") == true)
        XCTAssertEqual(h.account?.count, 26)
    }

    // MARK: Where it starts

    func test_should_catchUpByTheClock_when_itWasPlaying() {
        var h = sample()
        XCTAssertEqual(h.startSec(now: 1_030, durationSec: 300), 113)
        // Past ten minutes the clock is no help: where it was sent.
        XCTAssertEqual(h.startSec(now: 1_000 + 601, durationSec: 3_000), 83)
        // Never past the song's end.
        XCTAssertEqual(h.startSec(now: 1_300, durationSec: 200), 199)
        h.playing = false
        XCTAssertEqual(h.startSec(now: 1_030, durationSec: 300), 83)
        h.playing = true
        h.sentAt = nil
        XCTAssertEqual(h.startSec(now: 1_030, durationSec: nil), 83)
    }

    // MARK: Accounts

    func test_should_nameTheSameAccount_when_theAddressIsWrittenDifferently() {
        let a = HandoffAccounts.key(of: subsonic("a", "https://Music.Example.org/", "Alice"))
        let b = HandoffAccounts.key(of: subsonic("b", "http://music.example.org", "alice"))
        XCTAssertNotNil(a)
        XCTAssertEqual(a, b)
        XCTAssertNotEqual(a, HandoffAccounts.key(of: subsonic("c", "https://music.example.org", "bob")))
        XCTAssertNil(HandoffAccounts.key(of: apple))
        XCTAssertNil(HandoffAccounts.key(of: spotify("s", user: nil)))
        XCTAssertEqual(HandoffAccounts.key(of: spotify("s", user: "u1")), HandoffAccounts.key(of: spotify("t", user: "u1")))
        XCTAssertEqual(HandoffAccounts.key(of: spotify("s", user: nil), spotifyUserId: "u1"),
                       HandoffAccounts.key(of: spotify("t", user: "u1")))
    }

    private func match(_ provider: String, _ account: String?, _ profiles: [Profile], active: String?) -> String? {
        HandoffAccounts.match(provider: provider, account: account, profiles: profiles, activeId: active) {
            HandoffAccounts.key(of: $0)
        }?.id
    }

    func test_should_pickTheKeyedAccount_when_twoServersAreHere() {
        let home = subsonic("home", "https://home.example.org", "alice")
        let work = subsonic("work", "https://work.example.org", "alice")
        let key = HandoffAccounts.key(of: work)
        XCTAssertEqual(match("subsonic", key, [home, work, apple], active: "home"), "work")
    }

    func test_should_sayItIsMissing_when_noAccountHereHasTheKey() {
        let home = subsonic("home", "https://home.example.org", "alice")
        let other = HandoffAccounts.key(of: subsonic("x", "https://elsewhere.example.org", "alice"))
        XCTAssertNil(match("subsonic", other, [home, apple], active: "home"))
        XCTAssertNil(match("spotify", "s-whatever", [home, apple], active: "home"))
        XCTAssertNil(match("subsonic", nil, [apple], active: apple.id))
    }

    func test_should_takeTheOnlyAccount_when_theMacSentNoKey() {
        let home = subsonic("home", "https://home.example.org", "alice")
        XCTAssertEqual(match("subsonic", nil, [home, apple], active: apple.id), "home")
        XCTAssertEqual(match("applemusic", nil, [home, apple], active: "home"), apple.id)
    }

    func test_should_takeTheAccountInUse_when_noKeyAndTwoAccounts() {
        let home = subsonic("home", "https://home.example.org", "alice")
        let work = subsonic("work", "https://work.example.org", "alice")
        XCTAssertEqual(match("subsonic", nil, [home, work], active: "work"), "work")
        XCTAssertNil(match("subsonic", nil, [home, work], active: apple.id))
    }

    func test_should_fallBackToAnUnkeyedAccount_when_theKeyIsNotKnownHere() {
        // A Spotify account whose user isn't known here yet has no key: the only one that could be it.
        let s = spotify("sp", user: nil)
        XCTAssertEqual(match("spotify", "s-0123456789abcdef01234567", [s, apple], active: apple.id), "sp")
        // A keyed account that doesn't match is not it.
        let t = spotify("sp2", user: "someone-else")
        XCTAssertNil(match("spotify", "s-0123456789abcdef01234567", [t], active: "sp2"))
    }
}
