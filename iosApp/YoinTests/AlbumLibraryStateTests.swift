import XCTest
@testable import Yoin

/// B-F3: Save to library / Remove from library on Spotify albums (Android DetailMenu).
@MainActor
final class AlbumLibraryStateTests: XCTestCase {
    private func album(_ provider: String) -> Album {
        Album(id: MediaId(provider: provider, rawId: "al1"), title: "A", artist: "B", year: 2020,
              palette: CoverPalette(a: .red, b: .green, c: .blue), tracks: [])
    }

    func test_should_offer_the_row_for_spotify_albums_only() {
        XCTAssertTrue(AlbumLibraryState.applies(album(SpotifyMapping.provider)))
        XCTAssertFalse(AlbumLibraryState.applies(album("subsonic")))
        XCTAssertFalse(AlbumLibraryState.applies(album("applemusic")))
    }

    func test_should_name_the_action_by_the_albums_state() {
        XCTAssertEqual(AlbumLibraryState.label(saved: false), "Save to library")
        XCTAssertEqual(AlbumLibraryState.label(saved: true), "Remove from library")
    }

    func test_should_wait_for_the_state_before_showing_the_row() {
        let state = AlbumLibraryState()
        XCTAssertNil(state.saved["al1"])
        state.set(true, for: "al1")
        XCTAssertEqual(state.saved["al1"], true)
    }
}
