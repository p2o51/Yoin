import XCTest
@testable import Yoin

/// Nothing is drawn over Spotify artwork (51's rule, 2026-11-06).
final class CoverMarksTests: XCTestCase {
    func test_should_keepMarksOffTheCover_when_theAlbumIsSpotifys() {
        XCTAssertFalse(CoverMarks.onCover(provider: SpotifyMapping.provider))
        XCTAssertTrue(CoverMarks.onCover(provider: "subsonic"))
        XCTAssertTrue(CoverMarks.onCover(provider: "mock"))
    }

    func test_should_knowASpotifyImage_when_itsHostIsSpotifys() {
        XCTAssertTrue(CoverRef(url: URL(string: "https://i.scdn.co/image/ab67616d0000b273")!).isSpotify)
        XCTAssertTrue(CoverRef(url: URL(string: "https://mosaic.scdn.co/640/abc")!).isSpotify)
        XCTAssertTrue(CoverRef(url: URL(string: "https://image-cdn-ak.spotifycdn.com/image/abc")!).isSpotify)
        XCTAssertFalse(CoverRef(url: URL(string: "http://navidrome.local/rest/getCoverArt?id=1")!).isSpotify)
    }
}
