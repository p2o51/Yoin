import XCTest
@testable import Yoin

/// Which Home sections have something to show (HomeFeed.hasContent).
final class HomeSectionContentTests: XCTestCase {
    private let album = Album(id: MediaId(provider: "subsonic", rawId: "a"), title: "A", artist: "B", year: 2020,
                              palette: CoverPalette(a: .red, b: .green, c: .blue), tracks: [])

    private func feed(jumpBackIn: [JBIItem] = [], signals: [JBISignal] = []) -> HomeFeed {
        HomeFeed(layout: .server, activities: [], jumpBackIn: jumpBackIn, recentTracks: [], recentAlbums: [],
                 rediscover: [], signals: signals)
    }

    func test_should_showJumpBackIn_when_onlySignalsHaveContent() {
        let signal = JBISignal(kind: .ratedAlbum, album: album, title: "A", subtitle: "B", score: "9.0", basis: nil, comment: nil)
        XCTAssertTrue(feed(signals: [signal]).hasContent(.jumpBackIn))
    }

    func test_should_showJumpBackIn_when_onlyAlbumsHaveContent() {
        XCTAssertTrue(feed(jumpBackIn: [.album(album)]).hasContent(.jumpBackIn))
    }

    func test_should_hideJumpBackIn_when_bothAreEmpty() {
        XCTAssertFalse(feed().hasContent(.jumpBackIn))
    }
}
