import XCTest
@testable import Yoin

/// Jump Back In's "not now" (Android JbiHiddenStore, buildWidgetGrid's hidden filter).
final class HomeJbiHiddenTests: XCTestCase {
    private func store() -> JbiHiddenStore {
        let name = "jbi-hidden-tests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        addTeardownBlock { UserDefaults.standard.removePersistentDomain(forName: name) }
        return JbiHiddenStore(defaults: defaults)
    }

    private let art = CoverPalette(a: .red, b: .green, c: .blue,
                                   cover: CoverRef(url: URL(string: "http://x.test/rest/getCoverArt?id=1")!))

    private func album(_ i: Int) -> Album {
        Album(id: MediaId(provider: "subsonic", rawId: "a\(i)"), title: "A\(i)", artist: "X", year: 2020, palette: art, tracks: [])
    }

    private func song(_ i: Int) -> PlaylistEntry {
        PlaylistEntry(album: album(100 + i),
                      track: Track(id: MediaId(provider: "subsonic", rawId: "s\(i)"), number: 1, title: "S\(i)", durationSec: 100))
    }

    private func playlist(_ i: Int) -> JBILibraryItem {
        JBILibraryItem(id: MediaId(provider: "subsonic", rawId: "p\(i)"), kind: .playlist, title: "P\(i)", subtitle: "",
                       palette: art, album: nil)
    }

    func test_should_hideUntilTheSpanEnds_when_hidden() {
        let s = store()
        s.hide(scope: "subsonic|1", key: "album:x", until: 1_000)
        XCTAssertEqual(s.hidden(scope: "subsonic|1", now: 999), ["album:x"])
        XCTAssertEqual(s.hidden(scope: "subsonic|1", now: 1_000), [])
        XCTAssertEqual(s.hidden(scope: "subsonic|1", now: 500), [], "an expired card is dropped for good")
    }

    func test_should_keepScopesApart_when_twoAccountsHide() {
        let s = store()
        s.hide(scope: "subsonic|1", key: "album:x", until: 1_000)
        s.hide(scope: "subsonic|2", key: "song:y", until: 1_000)
        XCTAssertEqual(s.hidden(scope: "subsonic|1", now: 0), ["album:x"])
        XCTAssertEqual(s.hidden(scope: "subsonic|2", now: 0), ["song:y"])
        XCTAssertEqual(s.hidden(scope: "spotify|1", now: 0), [])
    }

    func test_should_survive_when_theStoreIsReopened() {
        let name = "jbi-hidden-tests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        defer { defaults.removePersistentDomain(forName: name) }
        JbiHiddenStore(defaults: defaults).hide(scope: "s", key: "album:x", until: 9_000)
        XCTAssertEqual(JbiHiddenStore(defaults: defaults).hidden(scope: "s", now: 1), ["album:x"])
    }

    func test_should_offerThreeDaysAndAWeek() {
        XCTAssertEqual(JbiHideSpan.allCases.map(\.days), [3, 7])
        XCTAssertEqual(JbiHideSpan.oneWeek.millis, 7 * 86_400_000)
    }

    func test_should_leaveHiddenCardsOutOfEveryPool_when_selecting() {
        let all = HomeJbiPools.select(albums: (0..<3).map(album), tracks: (0..<2).map(song), playlists: (0..<2).map(playlist))
        XCTAssertEqual(all.count, 7)
        let hidden: Set<String> = [jbiAlbumHideKey(album(1).id), jbiSongHideKey(song(0).track.id), jbiPlaylistHideKey(playlist(1).id)]
        let items = HomeJbiPools.select(albums: (0..<3).map(album), tracks: (0..<2).map(song), playlists: (0..<2).map(playlist),
                                        hidden: hidden)
        XCTAssertEqual(items.count, 4)
        XCTAssertTrue(items.allSatisfy { !hidden.contains($0.hideKey) })
    }

    func test_should_stepTheNextCardUp_when_oneIsHidden() {
        let items = HomeJbiPools.select(albums: (0..<5).map(album), tracks: [], playlists: [],
                                        hidden: [jbiAlbumHideKey(album(0).id)])
        XCTAssertEqual(items.map(\.stableId).first, "cover:album:subsonic:a1")
        XCTAssertEqual(items.count, 4)
    }

    func test_should_hideAMemoryCard_when_itsAlbumIsHidden() {
        let signal = JBISignal(kind: .ratedAlbum, album: album(7), title: "A7", subtitle: "X", score: "9.0", basis: nil)
        XCTAssertEqual(signal.hideKey, JBIItem.album(album(7)).hideKey)
        let noted = JBISignal(kind: .notedTrack, album: album(107), title: "S7", subtitle: "X", score: "8.0", basis: nil,
                              comment: "c", song: song(7))
        XCTAssertEqual(noted.hideKey, JBIItem.track(song(7)).hideKey)
    }

    func test_should_dropHiddenCardsFromTheShelf_when_buildingHome() {
        let feed = LibraryStore.buildHome(recent: [], frequent: [], newest: [], highest: [], random: (0..<4).map(album),
                                          recentTracks: [], hiddenJbi: [jbiAlbumHideKey(album(2).id)])
        XCTAssertEqual(feed.jumpBackIn.count, 3)
        XCTAssertFalse(feed.jumpBackIn.contains { $0.hideKey == jbiAlbumHideKey(album(2).id) })
        XCTAssertEqual(feed.removingHiddenJbi([jbiAlbumHideKey(album(0).id)]).jumpBackIn.count, 2)
    }
}
