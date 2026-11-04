import XCTest
@testable import Yoin

@MainActor
final class FavoritesStoreTests: XCTestCase {
    func test_should_agreeEverywhere_when_heartSetInOnePlace() {
        let hearts = FavoritesStore()
        var written: [String] = []
        hearts.onWrite = { _, id in written.append(id) }
        // Album row: the album said off.
        XCTAssertFalse(hearts.isFavorite(provider: "subsonic", songId: "s1", known: false))
        hearts.toggle(provider: "subsonic", songId: "s1", known: false, client: nil, apple: nil)
        // Now Playing's copy still says off: the tap wins.
        XCTAssertTrue(hearts.isFavorite(provider: "subsonic", songId: "s1", known: false))
        XCTAssertTrue(hearts.isFavorite(provider: "subsonic", songId: "s1"))
        // And back from Now Playing.
        hearts.toggle(provider: "subsonic", songId: "s1", known: true, client: nil, apple: nil)
        XCTAssertFalse(hearts.isFavorite(provider: "subsonic", songId: "s1", known: true))
        XCTAssertEqual(written, ["s1", "s1"])
    }

    func test_should_preferCallerCopy_overReport_when_untouched() {
        let hearts = FavoritesStore()
        hearts.report(true, provider: "subsonic", songId: "s2")
        hearts.report(false, provider: "subsonic", songId: "s2")
        XCTAssertTrue(hearts.isFavorite(provider: "subsonic", songId: "s2"), "the first report stands")
        XCTAssertFalse(hearts.isFavorite(provider: "subsonic", songId: "s2", known: false))
    }

    func test_should_onlyAdd_when_appleMusic() {
        let hearts = FavoritesStore()
        hearts.toggle(provider: "applemusic", songId: "a1", known: true, client: nil, apple: nil)
        XCTAssertTrue(hearts.isFavorite(provider: "applemusic", songId: "a1", known: true), "an added song stays added")
        hearts.toggle(provider: "applemusic", songId: "a2", known: false, client: nil, apple: nil)
        XCTAssertFalse(hearts.isFavorite(provider: "applemusic", songId: "a2", known: false), "no Apple Music: nothing to add to")
    }

    func test_should_forgetTaps_when_reset() {
        let hearts = FavoritesStore()
        hearts.toggle(provider: "subsonic", songId: "s3", known: false, client: nil, apple: nil)
        hearts.reset()
        XCTAssertFalse(hearts.isFavorite(provider: "subsonic", songId: "s3", known: false))
    }
}
