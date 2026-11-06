import XCTest
@testable import Yoin

/// Now Playing's Playing-from line (C-U7, Android PlayingFromLabel): the real source and its page.
@MainActor
final class PlayingFromTests: XCTestCase {
    func test_should_name_kind_and_open_page_when_started_from_each_source() {
        let album = Album(id: MediaId(provider: "subsonic", rawId: "al1"), title: "Blue", artist: "A", year: 2020,
                          palette: CoverPalette(a: .red, b: .green, c: .blue), tracks: [])
        let cases: [(AppModel.PlayingFrom, String, String, Route)] = [
            (.album(album), loc("PLAYING FROM ALBUM"), "Blue", .album(album)),
            (.playlist(id: "p1", title: "Night"), loc("PLAYING FROM PLAYLIST"), "Night", .playlist("p1")),
            (.artist(ArtistRef("Kiri Ono", id: "ar1")), loc("PLAYING FROM ARTIST"), "Kiri Ono", .artist(ArtistRef("Kiri Ono", id: "ar1"))),
        ]
        for (from, kind, title, route) in cases {
            XCTAssertEqual(from.kindLabel, kind)
            XCTAssertEqual(from.title, title)
            XCTAssertEqual(from.route, route)
        }
    }
}
