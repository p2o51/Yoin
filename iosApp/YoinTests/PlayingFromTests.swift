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

    // MARK: A start in Memories (I02, Android MemoryEntry.toPlaybackActivityContext)

    private func album(_ id: String, _ title: String) -> Album {
        let tracks = (0..<3).map { Track(id: MediaId(provider: "subsonic", rawId: "\(id)-t\($0)"), number: $0 + 1,
                                         title: "T\($0)", durationSec: 200) }
        return Album(id: MediaId(provider: "subsonic", rawId: id), title: title, artist: "Wave", year: 2020,
                     palette: CoverPalette(a: .red, b: .green, c: .blue), tracks: tracks)
    }

    private func memoryCard(_ album: Album, title: String?) -> MemoryCard {
        let m = AlbumMemory(album: album, totalTracks: album.tracks.count, ratedTrackCount: 1, coverage: 0.34,
                            averageSongRating: 8, albumRating: nil, noteCount: 0, lastWrittenAt: 1, playCount: 2,
                            firstPlayedAt: 1, lastPlayedAt: 2, isMemoryEligible: true)
        let first = album.tracks[0].id.rawId
        return MemoryDeck.card(m, MemoryDeck.Inputs(memories: [m], title: { _ in title.map { ($0, true) } },
                                                    rating: { $0 == first ? 8 : nil }))
    }

    func test_should_play_from_the_album_under_its_own_name_when_started_on_a_memory() {
        let tide = album("al", "Tide")
        let card = memoryCard(tide, title: "Night buses")
        XCTAssertEqual(card.title, "Night buses")
        XCTAssertEqual(card.diaryTracks.map(\.index), [0])

        let from = card.playingFrom
        XCTAssertEqual(from, .album(tide))
        XCTAssertEqual(from.kindLabel, loc("PLAYING FROM ALBUM"))
        // The album's name, as Android's albumName: the memory's title is the diary's.
        XCTAssertEqual(from.title, "Tide")
        XCTAssertEqual(from.route, .album(tide))
    }

    func test_should_keep_the_named_source_when_a_page_is_on_screen() {
        let memory = AppModel.PlayingFrom.album(album("al", "Tide"))
        // Memories lies over Home (no page), over a pushed page, or beside the iPad's detail column.
        let pages: [Route?] = [nil, .album(album("other", "Blue")), .playlist("p1"), .artist(ArtistRef("Kiri Ono", id: "ar1")),
                               .allMemories, .settings]
        for page in pages {
            XCTAssertEqual(AppModel.PlayingFrom.of(named: memory, page: page, playlistTitle: { _ in "Night" }), memory)
        }
    }

    func test_should_read_the_page_when_the_start_names_no_source() {
        let blue = album("al1", "Blue")
        let kiri = ArtistRef("Kiri Ono", id: "ar1")
        func of(_ page: Route?) -> AppModel.PlayingFrom? {
            AppModel.PlayingFrom.of(named: nil, page: page, playlistTitle: { $0 == "p1" ? "Night" : "" })
        }
        XCTAssertEqual(of(.album(blue)), .album(blue))
        XCTAssertEqual(of(.playlist("p1")), .playlist(id: "p1", title: "Night"))
        XCTAssertEqual(of(.artist(kiri)), .artist(kiri))
        // Lists (Home, Library, Search) and pages that aren't a source.
        for page in [nil, Route.settings, .allMemories, .labs, .tips(focus: nil)] { XCTAssertNil(of(page)) }
    }
}
