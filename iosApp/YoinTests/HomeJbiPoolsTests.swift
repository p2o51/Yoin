import XCTest
@testable import Yoin

/// Jump Back In's covers (Android buildWidgetGrid): three pools, signal exclusion, art last, round-robin, 28 cap.
final class HomeJbiPoolsTests: XCTestCase {
    private let noArt = CoverPalette(a: .red, b: .green, c: .blue)
    private let art = CoverPalette(a: .red, b: .green, c: .blue, cover: CoverRef(url: URL(string: "http://x.test/rest/getCoverArt?id=1")!))

    private func album(_ i: Int, art hasArt: Bool = true) -> Album {
        Album(id: MediaId(provider: "subsonic", rawId: "a\(i)"), title: "A\(i)", artist: "X", year: 2020,
              palette: hasArt ? art : noArt, tracks: [])
    }

    private func song(_ i: Int, art hasArt: Bool = true) -> PlaylistEntry {
        PlaylistEntry(album: album(100 + i, art: hasArt),
                      track: Track(id: MediaId(provider: "subsonic", rawId: "s\(i)"), number: 1, title: "S\(i)", durationSec: 100))
    }

    private func playlist(_ i: Int, art hasArt: Bool = true) -> LibraryItem {
        LibraryItem(id: MediaId(provider: "subsonic", rawId: "p\(i)"), kind: .playlist, title: "P\(i)", subtitle: "",
                    palette: hasArt ? art : noArt, album: nil)
    }

    private func ids(_ items: [JBIItem]) -> [String] {
        items.map {
            switch $0 {
            case let .album(a): a.id.rawId
            case let .track(e): e.track.id.rawId
            case let .playlist(p): p.id.rawId
            }
        }
    }

    func test_should_interleaveThreeAlbumsTwoSongsThreePlaylists_then_theRest() {
        let items = HomeJbiPools.select(albums: (0..<5).map { album($0) }, tracks: (0..<3).map { song($0) },
                                        playlists: (0..<4).map { playlist($0) })
        // primary: a0 s0 p0 | a1 s1 p1 | a2 p2 ; rest: a3 s2 p3 | a4
        XCTAssertEqual(ids(items), ["a0", "s0", "p0", "a1", "s1", "p1", "a2", "p2", "a3", "s2", "p3", "a4"])
    }

    func test_should_putArtlessCoversLast_insideAndAcrossPools() {
        let items = HomeJbiPools.select(albums: [album(0, art: false), album(1)], tracks: [song(0)],
                                        playlists: [playlist(0, art: false), playlist(1)])
        XCTAssertEqual(ids(items), ["a1", "s0", "p1", "a0", "p0"])
    }

    func test_should_leaveOutTheCardsASignalShows() {
        let items = HomeJbiPools.select(albums: (0..<3).map { album($0) }, tracks: (0..<3).map { song($0) },
                                        playlists: [playlist(0)],
                                        excludedAlbums: [album(1).id], excludedTracks: [song(0).track.id])
        XCTAssertFalse(ids(items).contains("a1"))
        XCTAssertFalse(ids(items).contains("s0"))
        XCTAssertEqual(ids(items).count, 5)
    }

    func test_should_capTheShelfAt28() {
        let items = HomeJbiPools.select(albums: (0..<40).map { album($0) }, tracks: (0..<40).map { song($0) },
                                        playlists: (0..<40).map { playlist($0) })
        XCTAssertEqual(items.count, 28)
    }

    func test_should_trimPoolsTo14Albums10Songs10Playlists() {
        XCTAssertEqual(HomeJbiPools.albumPool(random: (0..<20).map { album($0) }, fallback: []).count, 14)
        XCTAssertEqual(HomeJbiPools.trackPool((0..<20).map { song($0) }).count, 10)
        XCTAssertEqual(HomeJbiPools.playlistPool((0..<20).map { playlist($0) }, now: 0).count, 10)
        // 14 + 10 + 10 = 34 candidates, the shelf takes 28.
        let all = HomeJbiPools.select(albums: HomeJbiPools.albumPool(random: (0..<20).map { album($0) }, fallback: []),
                                      tracks: HomeJbiPools.trackPool((0..<20).map { song($0) }),
                                      playlists: HomeJbiPools.playlistPool((0..<20).map { playlist($0) }, now: 0))
        XCTAssertEqual(all.count, 28)
    }

    func test_should_topUpAShortAlbumPool_withoutRepeats() {
        let pool = HomeJbiPools.albumPool(random: [album(0), album(1)], fallback: [album(1), album(2)])
        XCTAssertEqual(pool.map(\.id.rawId), ["a0", "a1", "a2"])
    }

    func test_should_orderPlaylistsAlike_withinAWindow_andKeepArtlessBehind() {
        let lists = (0..<8).map { playlist($0, art: $0 % 2 == 0) }
        let one = HomeJbiPools.playlistPool(lists, now: 1000)
        XCTAssertEqual(one, HomeJbiPools.playlistPool(lists, now: 2000))
        XCTAssertEqual(one.map { $0.palette.cover != nil }, [true, true, true, true, false, false, false, false])
        XCTAssertEqual(Set(one.map(\.id)), Set(lists.map(\.id)))
    }

    func test_should_rollPlaylistsAgain_whenTheSixHourWindowTurns() {
        let lists = (0..<10).map { playlist($0) }
        let windows = (0..<6).map { HomeJbiPools.playlistPool(lists, now: Int64($0) * HomeJbiPools.rotationWindowMs).map(\.id.rawId) }
        XCTAssertGreaterThan(Set(windows).count, 1)
    }

    func test_should_buildTheShelfFromHomeLists_withSignalsLeftOut() {
        let rated = JBISignal(kind: .ratedAlbum, album: album(3), title: "A3", subtitle: "X", score: "9.0", basis: nil, comment: nil)
        let feed = LibraryStore.buildHome(recent: (0..<4).map { album($0) }, frequent: [], newest: [], highest: [],
                                          random: (3..<9).map { album($0) }, recentTracks: [],
                                          history: LibraryStore.HomeOverlay(signals: [rated]),
                                          playlists: [playlist(0)], trackPool: (0..<3).map { song($0) })
        let shelf = ids(feed.jumpBackIn)
        XCTAssertFalse(shelf.contains("a3"))
        XCTAssertTrue(shelf.contains("s0") && shelf.contains("p0"))
        // Activities' albums (a0 ... a3 here) stay in the pool, as on Android.
        XCTAssertTrue(shelf.contains("a0"))
    }
}
