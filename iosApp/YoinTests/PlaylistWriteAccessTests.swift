import XCTest
@testable import Yoin

/// Subsonic playlist write access (Android canWrite, B-F4): the server's readonly flag survives the
/// mapping and the cache and wins over ownership.
@MainActor
final class PlaylistWriteAccessTests: XCTestCase {
    private func playlist(owner: String, canWrite: Bool?) -> PlaylistInfo {
        PlaylistInfo(id: MediaId(provider: "subsonic", rawId: "p1"), title: "P", owner: owner, isPublic: nil,
                     description: nil, palette: CoverPalette(a: .red, b: .green, c: .blue), entries: [],
                     canWrite: canWrite)
    }

    func test_should_hide_writes_when_own_playlist_is_readonly() {
        XCTAssertFalse(PlaylistActions.subsonicCanEdit(playlist(owner: "me", canWrite: false), username: "me"))
    }

    func test_should_allow_writes_when_server_says_writable() {
        XCTAssertTrue(PlaylistActions.subsonicCanEdit(playlist(owner: "me", canWrite: true), username: "me"))
    }

    func test_should_fall_back_to_owner_when_access_not_read() {
        XCTAssertTrue(PlaylistActions.subsonicCanEdit(playlist(owner: "me", canWrite: nil), username: "me"))
        XCTAssertTrue(PlaylistActions.subsonicCanEdit(playlist(owner: "", canWrite: nil), username: "me"))
        XCTAssertFalse(PlaylistActions.subsonicCanEdit(playlist(owner: "them", canWrite: nil), username: "me"))
    }

    func test_should_keep_write_access_and_ownership_when_cached() {
        let codec = DetailCacheCodec.appleMusic
        var p = playlist(owner: "me", canWrite: false)
        p.ownedByMe = true
        let back = codec.playlist(codec.dto(p))
        XCTAssertEqual(back.canWrite, false)
        XCTAssertEqual(back.ownedByMe, true)
    }
}
