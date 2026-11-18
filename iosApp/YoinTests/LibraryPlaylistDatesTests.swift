import XCTest
import YoinShared
@testable import Yoin

/// Library dates playlists where the source does (B-F2, Android playlistsHaveLibraryDates).
final class LibraryPlaylistDatesTests: XCTestCase {
    // (`LibraryItem` is ambiguous with YoinShared's as a type name here: built by inference.)
    private let playlist = { (id: String, name: String, added: String?) in
        LibraryItem(id: MediaId(provider: "subsonic", rawId: id), kind: .playlist, title: name, subtitle: "",
                    palette: CoverPalette(a: .red, b: .green, c: .blue), addedAt: added)
    }

    private let sorter = LibrarySorter(recents: [:], ignoresArticles: false)

    func test_should_order_playlists_newest_first_and_undated_last_when_sorted_by_recently_added() {
        let items = [playlist("a", "Alpha", "2024-01-01T00:00:00Z"), playlist("b", "Beta", nil),
                     playlist("c", "Gamma", "2026-03-01T10:00:00.000Z"), playlist("d", "Delta", nil)]
        XCTAssertEqual(sorter.sorted(items, by: .recentlyAdded).map(\.id.rawId), ["c", "a", "b", "d"])
    }

    func test_should_offer_recently_added_for_playlists_only_when_they_are_dated() {
        let dated = LibrarySortOptions.playlistsDated([playlist("a", "A", "2024-01-01T00:00:00Z")])
        XCTAssertTrue(dated)
        XCTAssertFalse(LibrarySortOptions.playlistsDated([playlist("a", "A", nil)]))
        XCTAssertFalse(LibrarySortOptions.playlistsDated([]))
        XCTAssertEqual(LibrarySortOptions.options(for: .playlists, playlistsDated: true),
                       [.recents, .recentlyAdded, .alphabetical, .creator])
        XCTAssertEqual(LibrarySortOptions.options(for: .playlists, playlistsDated: false),
                       [.recents, .alphabetical, .creator])
        XCTAssertEqual(LibrarySortOptions.options(for: nil, playlistsDated: false),
                       [.recents, .recentlyAdded, .alphabetical, .creator])
        XCTAssertEqual(LibrarySortOptions.options(for: .artists, playlistsDated: true), [.recents, .alphabetical])
        XCTAssertTrue(LibrarySortOptions.options(for: .songs, playlistsDated: true).isEmpty)
    }

    func test_should_carry_the_created_date_when_mapping_a_subsonic_playlist() {
        let p = PlaylistSummary(ref: MediaRef(provider: MediaRef.companion.PROVIDER_SUBSONIC, rawId: "p1"), id: "p1",
                                name: "Mix", owner: "kiri", isPublic: nil, comment: nil, coverArtId: "p1",
                                songCount: KotlinInt(int: 3), durationSec: nil, created: "2025-05-05T05:05:05Z",
                                changed: "2026-01-01T00:00:00Z", canWrite: true, ownedByMe: nil)
        XCTAssertEqual(SubsonicMapping { _ in nil }.playlistItem(p).addedAt, "2025-05-05T05:05:05Z")
    }

    func test_should_carry_date_added_when_mapping_an_apple_library_playlist() {
        let raw: [String: Any] = ["id": "p.1", "type": "library-playlists",
                                  "attributes": ["name": "Mine", "canEdit": true, "dateAdded": "2025-02-03T04:05:06Z"]]
        let item = AppleMusicMapping.playlist(raw).map(AppleMusicMapping.playlistItem)
        XCTAssertEqual(item?.addedAt, "2025-02-03T04:05:06Z")
        let catalog: [String: Any] = ["id": "pl.u", "type": "playlists", "attributes": ["name": "Cat", "dateAdded": "x"]]
        XCTAssertNil(AppleMusicMapping.playlist(catalog).map(AppleMusicMapping.playlistItem)?.addedAt)
    }
}
