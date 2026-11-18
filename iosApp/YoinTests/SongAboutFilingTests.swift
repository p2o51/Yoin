import XCTest
@testable import Yoin

/// The scrapbook finds About rows Now Playing filed under the track's own metadata
/// (SongAboutStore.filed / sameSong): an Apple single's "- Single" album, Spotify's joined artists.
@MainActor
final class SongAboutFilingTests: XCTestCase {
    private var dir: URL!

    override func setUp() async throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: dir)
    }

    private func store() -> SongAboutStore {
        let s = SongAboutStore(fileURL: dir.appendingPathComponent("a.json"))
        s.language = { "English" }
        return s
    }

    func test_should_findNowPlayingRows_when_theAlbumCarriesASingleSuffix() {
        let s = store()
        s.writeOwn(title: "Glow", artist: "Aurora", album: "Glow - Single", heading: "Where", text: "Train")
        let k = s.filed(title: "Glow", artist: "Aurora", album: "Glow")
        XCTAssertEqual(k.album, "Glow - Single")
        XCTAssertEqual(s.entries(SongAboutStore.key(title: k.title, artist: k.artist, album: k.album)).map(\.answerText),
                       ["Train"])
        XCTAssertTrue(SongAboutStore.sameSong(SongAboutStore.key(title: "a", artist: "b", album: "C - EP"),
                                              SongAboutStore.key(title: "a", artist: "b", album: "c")))
    }

    func test_should_findNowPlayingRows_when_theArtistsComeJoined() {
        let s = store()
        s.writeOwn(title: "Duet", artist: "Ann, Bo & Cy", album: "Pair", heading: "Note", text: "Mine")
        XCTAssertEqual(s.filed(title: "Duet", artist: "Ann", album: "Pair").artist, "Ann, Bo & Cy")
    }

    func test_should_keepTheExactKey_when_rowsExistUnderIt() {
        let s = store()
        s.writeOwn(title: "Glow", artist: "Aurora", album: "Glow - Single", heading: "a", text: "one")
        s.writeOwn(title: "Glow", artist: "Aurora", album: "Glow", heading: "b", text: "two")
        XCTAssertEqual(s.filed(title: "Glow", artist: "Aurora", album: "Glow").album, "Glow")
    }

    func test_should_notMatch_when_titleOrFirstArtistDiffers() {
        let s = store()
        s.writeOwn(title: "Glow", artist: "Aurora", album: "Glow - Single", heading: "a", text: "one")
        XCTAssertEqual(s.filed(title: "Glow", artist: "Bjork", album: "Glow").album, "Glow")
        XCTAssertEqual(s.filed(title: "Glow II", artist: "Aurora", album: "Glow").album, "Glow")
        // "Deluxe" is another release, not a suffix this drops.
        XCTAssertEqual(s.filed(title: "Glow", artist: "Aurora", album: "Glow (Deluxe)").album, "Glow (Deluxe)")
    }
}
