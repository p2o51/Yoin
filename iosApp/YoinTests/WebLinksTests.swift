import XCTest
@testable import Yoin

/// Share text and "Open in …" links per provider (B-F6, Android webUrl / detailShareText / openInLabel).
final class WebLinksTests: XCTestCase {
    private func id(_ provider: String, _ raw: String) -> MediaId { MediaId(provider: provider, rawId: raw) }

    func test_should_build_open_spotify_links_when_the_provider_is_spotify() {
        XCTAssertEqual(WebLinks.url(kind: .track, id: id("spotify", "4uLU6hMCjMI75M1A2tKUQC"))?.absoluteString,
                       "https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC")
        XCTAssertEqual(WebLinks.url(kind: .album, id: id("spotify", "1A2GTWGtFfWp7KSQTwWOyo"))?.absoluteString,
                       "https://open.spotify.com/album/1A2GTWGtFfWp7KSQTwWOyo")
        XCTAssertEqual(WebLinks.url(kind: .playlist, id: id("spotify", "37i9dQZF1DXcBWIGoYBM5M"))?.host, "open.spotify.com")
        XCTAssertNil(WebLinks.url(kind: .album, id: id("spotify", "song:abc")), "a loose song's stand-in album")
        XCTAssertNil(WebLinks.url(kind: .album, id: id("spotify", "")))
    }

    func test_should_build_apple_music_links_when_the_id_is_a_catalog_id() {
        XCTAssertEqual(WebLinks.url(kind: .album, id: id("applemusic", "1440857781"), appleStorefront: "us")?.absoluteString,
                       "https://music.apple.com/us/album/1440857781")
        XCTAssertEqual(WebLinks.url(kind: .playlist, id: id("applemusic", "pl.u-abc"), appleStorefront: "jp")?.absoluteString,
                       "https://music.apple.com/jp/playlist/pl.u-abc")
        XCTAssertNil(WebLinks.url(kind: .album, id: id("applemusic", "pl.u-abc"), appleStorefront: "us"),
                     "only playlists use the pl. prefix")
        XCTAssertNil(WebLinks.url(kind: .album, id: id("applemusic", "library:l.XYZ"), appleStorefront: "us"))
        XCTAssertNil(WebLinks.url(kind: .track, id: id("applemusic", "1440857786"), appleStorefront: nil))
    }

    func test_should_have_no_link_when_the_provider_is_a_subsonic_server_or_the_mock() {
        XCTAssertNil(WebLinks.url(kind: .album, id: id("subsonic", "al-1"), appleStorefront: "us"))
        XCTAssertNil(WebLinks.url(kind: .track, id: id("mock", "t1")))
    }

    func test_should_label_open_in_by_the_links_host_when_there_is_one() {
        XCTAssertEqual(WebLinks.openInLabel(URL(string: "https://open.spotify.com/track/x")), loc("Open in Spotify"))
        XCTAssertEqual(WebLinks.openInLabel(URL(string: "https://music.apple.com/us/album/1")), loc("Open in Apple Music"))
        XCTAssertNil(WebLinks.openInLabel(URL(string: "https://example.com/x")))
        XCTAssertNil(WebLinks.openInLabel(nil))
    }

    func test_should_put_the_link_on_the_next_line_when_sharing() {
        let url = URL(string: "https://open.spotify.com/track/x")
        XCTAssertEqual(WebLinks.shareText(title: "Song — Artist", url: url), "Song — Artist\nhttps://open.spotify.com/track/x")
        XCTAssertEqual(WebLinks.shareText(title: "Song — Artist", url: nil), "Song — Artist")
    }
}
