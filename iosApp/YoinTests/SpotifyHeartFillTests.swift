import SwiftUI
import XCTest
@testable import Yoin

@MainActor
final class SpotifyHeartFillTests: XCTestCase {
    private func track(_ id: String, starred: Bool = false) -> Track {
        Track(id: MediaId(provider: SpotifyMapping.provider, rawId: id), number: 1, title: id, durationSec: 60,
              starred: starred)
    }

    private func page(_ ids: [String], positions: [Int]?, songCount: Int?, snapshot: String? = "s1") -> PlaylistInfo {
        let album = Album(id: MediaId(provider: SpotifyMapping.provider, rawId: "a"), title: "A", artist: "X", year: 0,
                          palette: CoverPalette(a: .red, b: .green, c: .blue), tracks: [])
        var p = PlaylistInfo(id: MediaId(provider: SpotifyMapping.provider, rawId: "p1"), title: "Mix", owner: "me",
                             palette: album.palette, entries: ids.map { PlaylistEntry(album: album, track: track($0)) })
        p.positions = positions
        p.songCount = songCount
        p.snapshotId = snapshot
        return p
    }

    func test_should_keep_the_old_heart_when_no_read_answered_for_the_song() {
        let before = [track("a", starred: true), track("b", starred: true)]
        let known: [String: Bool] = ["b": false]
        let kept = LibraryStore.keepingUnknownHearts([track("a"), track("b"), track("c")], before: before) { known[$0] }
        XCTAssertEqual(kept.map(\.starred), [true, false, false])
    }

    func test_should_report_a_change_only_when_an_answer_differs() {
        var tracks = [track("a"), track("b", starred: true)]
        XCTAssertFalse(LibraryStore.applyHearts(["b": true], to: &tracks))
        XCTAssertTrue(LibraryStore.applyHearts(["a": true, "b": false], to: &tracks))
        XCTAssertEqual(tracks.map(\.starred), [true, false])
    }

    func test_should_number_the_new_rows_after_the_last_item_when_songs_are_added() throws {
        let before = page(["A", "B"], positions: [0, 2], songCount: 3)
        let now = page(["A", "B", "C", "D"], positions: nil, songCount: 3)
        let patched = try XCTUnwrap(PlaylistActions.appended(before, now: now, added: 2, snapshot: "s2"))
        XCTAssertEqual(patched.positions, [0, 2, 3, 4])
        XCTAssertEqual(patched.songCount, 5)
        XCTAssertEqual(patched.snapshotId, "s2")
    }

    func test_should_read_again_when_the_page_had_no_positions() {
        let before = page(["A"], positions: nil, songCount: 1)
        let now = page(["A", "C"], positions: nil, songCount: 1)
        XCTAssertNil(PlaylistActions.appended(before, now: now, added: 1, snapshot: "s2"))
        let counted = page(["A"], positions: [0], songCount: nil)
        XCTAssertNil(PlaylistActions.appended(counted, now: now, added: 1, snapshot: "s2"))
        XCTAssertNil(PlaylistActions.appended(page(["A"], positions: [0], songCount: 1), now: now, added: 1, snapshot: nil))
    }
}
