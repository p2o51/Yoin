import XCTest
@testable import Yoin

/// Audit 11-15 #35: "Playing from" for Spotify's own song comes from its player context.
@MainActor
final class SpotifyPlayingFromTests: XCTestCase {
    typealias Context = SpotifyPlayingFrom.Context

    func test_should_readAlbumPlaylistAndArtist_when_contextNamesThem() {
        XCTAssertEqual(Context.parse("spotify:album:abc"), .album("abc"))
        XCTAssertEqual(Context.parse("spotify:playlist:37i9dQZF1DX"), .playlist("37i9dQZF1DX"))
        XCTAssertEqual(Context.parse("spotify:artist:xyz"), .artist("xyz"))
    }

    func test_should_readThePlaylist_when_itIsAnOlderUserPlaylistURI() {
        XCTAssertEqual(Context.parse("spotify:user:someone:playlist:p1"), .playlist("p1"))
    }

    func test_should_beLikedSongs_when_contextIsTheCollection() {
        XCTAssertEqual(Context.parse("spotify:user:someone:collection"), .likedSongs)
        XCTAssertEqual(Context.parse("spotify:collection:tracks"), .likedSongs)
    }

    func test_should_beNil_when_thereIsNoContextOrItIsUnknown() {
        XCTAssertNil(Context.parse(nil))
        XCTAssertNil(Context.parse(""))
        XCTAssertNil(Context.parse("spotify:show:pod"))
        XCTAssertNil(Context.parse("spotify:album:"))
        XCTAssertNil(Context.parse("https://open.spotify.com/album/abc"))
    }

    func test_should_notLinkSpotifysOwnLists_when_theyOpenEmpty() {
        XCTAssertTrue(SpotifyPlayingFrom.isSpotifyMade("37i9dQZF1DXcBWIGoYBM5M"))
        XCTAssertFalse(SpotifyPlayingFrom.isSpotifyMade("3cEYpjA9oz9GiPac4AsH4n"))
    }
}
