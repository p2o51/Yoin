import XCTest
@testable import Yoin

/// Library's chips by source (B-U12) and By You by ownership (B-F1), as Android LibraryViewModel and
/// PlaylistsByYou decide them.
@MainActor
final class LibraryFilterParityTests: XCTestCase {
    // (`LibraryItem` is ambiguous with YoinShared's as a type name here: built by inference.)
    private let playlist = { (id: String, ownedByMe: Bool?) in
        LibraryItem(id: MediaId(provider: "spotify", rawId: id), kind: .playlist, title: id, subtitle: "",
                    palette: CoverPalette(a: .red, b: .green, c: .blue), ownedByMe: ownedByMe)
    }

    func test_should_offer_favorites_only_when_source_has_its_own() {
        XCTAssertEqual(LibraryFilterRow.kinds(favorites: true), [.playlists, .artists, .albums, .songs, .favorites])
        XCTAssertEqual(LibraryFilterRow.kinds(favorites: false), [.playlists, .artists, .albums, .songs])
    }

    func test_should_read_as_all_when_kept_view_is_not_offered() {
        let kinds = LibraryFilterRow.kinds(favorites: false)
        XCTAssertNil(LibraryFilterRow.offered(.favorites, kinds: kinds))
        XCTAssertEqual(LibraryFilterRow.offered(.albums, kinds: kinds), .albums)
        XCTAssertNil(LibraryFilterRow.offered(nil, kinds: kinds))
    }

    func test_should_offer_by_you_when_playlists_are_mixed() {
        XCTAssertTrue(LibraryFilterRow.byYouAvailable([playlist("a", true), playlist("b", false)]))
    }

    func test_should_hide_by_you_when_ownership_is_one_sided_or_unknown() {
        XCTAssertFalse(LibraryFilterRow.byYouAvailable([playlist("a", true), playlist("b", nil)]))
        XCTAssertFalse(LibraryFilterRow.byYouAvailable([playlist("a", false)]))
        XCTAssertFalse(LibraryFilterRow.byYouAvailable([]))
    }

    func test_should_read_ownership_when_mapping_spotify_and_apple_playlists() {
        let raw: [String: Any] = ["id": "p.1", "type": "library-playlists",
                                  "attributes": ["name": "Mine", "canEdit": true]]
        let apple = AppleMusicMapping.playlist(raw).map(AppleMusicMapping.playlistItem)
        XCTAssertEqual(apple?.ownedByMe, true)
        let catalog: [String: Any] = ["id": "pl.1", "type": "playlists", "attributes": ["name": "Theirs"]]
        XCTAssertNil(AppleMusicMapping.playlist(catalog).map(AppleMusicMapping.playlistItem)?.ownedByMe)
    }
}
