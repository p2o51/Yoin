import XCTest
@testable import Yoin

/// Go to album from Now Playing (its Playing from line, its title, the song sheet from the page or the
/// mini player): the page folds into the pill where the album page's bar puts it, and the album page
/// already on top isn't pushed again.
@MainActor
final class NowPlayingGoToAlbumTests: XCTestCase {
    private let homePill = CGRect(x: 76, y: 816, width: 188, height: 60)
    private let homeCover = CGRect(x: 85, y: 825, width: 42, height: 42)
    private let albumPill = CGRect(x: 176, y: 816, width: 230, height: 60)
    private let albumCover = CGRect(x: 185, y: 825, width: 42, height: 42)

    func test_should_foldIntoThePillWhereItIsNow_when_theBarMovedWhileThePageWasUp() {
        let np = NowPlayingPresentation()
        np.pillMeasured(.pill, homePill)
        np.pillMeasured(.cover, homeCover)
        XCTAssertEqual(np.pillFrame, homePill)
        np.snapOpen()
        // Up: the album page is pushed under the page and its bar moves the pill; the morph holds still.
        np.pillMeasured(.pill, albumPill)
        np.pillMeasured(.cover, albumCover)
        XCTAssertEqual(np.pillFrame, homePill, "nothing reaches the morph while the page is up")
        // Folding: it lands on the pill beside the album page's Play split, cover on its cover.
        np.show(false)
        XCTAssertTrue(np.closing)
        XCTAssertEqual(np.pillFrame, albumPill)
        XCTAssertEqual(np.pillCoverFrame, albumCover)
    }

    func test_should_followThePill_when_theBarMovesAfterTheFoldStarted() {
        let np = NowPlayingPresentation()
        np.pillMeasured(.pill, homePill)
        np.snapOpen()
        np.show(false)
        np.pillMeasured(.pill, albumPill)
        np.pillMeasured(.title, CGRect(x: 237, y: 828, width: 120, height: 18))
        XCTAssertEqual(np.pillFrame, albumPill)
        XCTAssertEqual(np.pillTitleFrame.minX, 237)
    }

    func test_should_landOnTheFirstMeasuredPill_when_launchedOpen() {
        // Launched open (or warmed before the bar was measured): the fold had no pill and went to the
        // screen's middle.
        let np = NowPlayingPresentation()
        np.snapOpen()
        np.pillMeasured(.pill, homePill)
        XCTAssertEqual(np.pillFrame, .zero)
        np.show(false)
        XCTAssertEqual(np.pillFrame, homePill)
    }

    func test_should_keepTheAlbumPage_when_goToAlbumOpensTheAlbumAlreadyOnTop() {
        let model = AppModel()
        model.isPad = false
        let album = MockLibrary.allAlbums[0]
        model.path = [.album(album)]
        model.open(album)
        XCTAssertEqual(model.path, [.album(album)], "no second copy of the same page")
        let other = MockLibrary.allAlbums[1]
        model.open(other)
        XCTAssertEqual(model.path, [.album(album), .album(other)])
    }
}
