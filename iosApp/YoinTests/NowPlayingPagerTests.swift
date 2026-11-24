import XCTest
@testable import Yoin

/// Now Playing's Lyrics / About / Note pager and its tabs (NowPlayingPager): a tab tap and a
/// swipe move one position, and the tabs' highlight is read from that same position.
final class NowPlayingPagerTests: XCTestCase {
    private let width: CGFloat = 372

    func test_should_landOnTappedPage_when_aTabIsTapped() {
        // settlePager(on:) sets the tab and drops any drag: the pager sits on the page, its tab fully selected.
        for page in NowPlayingDetailPage.allCases {
            let position = NowPlayingPager.position(tab: page.rawValue, drag: 0, width: width)
            XCTAssertEqual(position, CGFloat(page.rawValue))
            XCTAssertEqual(NowPlayingPager.shownTab(position: position), page.rawValue)
            for other in NowPlayingDetailPage.allCases {
                XCTAssertEqual(NowPlayingPager.selection(of: other.rawValue, at: position),
                               other == page ? 1 : 0, "\(other) on \(page)")
            }
        }
    }

    func test_should_passTheHighlightWithTheFinger_when_swipingTowardTheNextPage() {
        // A third of the way from Lyrics to About: Lyrics still mostly selected, About coming in.
        var position = NowPlayingPager.position(tab: 0, drag: -width / 3, width: width)
        XCTAssertEqual(NowPlayingPager.selection(of: 0, at: position), 2.0 / 3, accuracy: 0.001)
        XCTAssertEqual(NowPlayingPager.selection(of: 1, at: position), 1.0 / 3, accuracy: 0.001)
        XCTAssertEqual(NowPlayingPager.selection(of: 2, at: position), 0)
        XCTAssertEqual(NowPlayingPager.shownTab(position: position), 0)
        // Past half way the pager is heading for About (Android targetPage): its tab turns bold.
        position = NowPlayingPager.position(tab: 0, drag: -width * 0.6, width: width)
        XCTAssertEqual(NowPlayingPager.shownTab(position: position), 1)
        XCTAssertGreaterThan(NowPlayingPager.selection(of: 1, at: position),
                             NowPlayingPager.selection(of: 0, at: position))
        // Back toward Lyrics from Note.
        position = NowPlayingPager.position(tab: 2, drag: width / 4, width: width)
        XCTAssertEqual(NowPlayingPager.selection(of: 2, at: position), 0.75, accuracy: 0.001)
        XCTAssertEqual(NowPlayingPager.selection(of: 1, at: position), 0.25, accuracy: 0.001)
    }

    func test_should_keepOneTabSelected_when_rubberBandingPastAnEnd() {
        let dx = NowPlayingPager.rubberBand(tab: 0, dx: 120, width: width)
        XCTAssertLessThan(dx, 120, "the drag resists past the first page")
        let position = NowPlayingPager.position(tab: 0, drag: dx, width: width)
        XCTAssertEqual(NowPlayingPager.shownTab(position: position), 0)
        XCTAssertEqual(NowPlayingPager.selection(of: 0, at: position), 1)
        XCTAssertEqual(NowPlayingPager.selection(of: 1, at: position), 0)
        let end = NowPlayingPager.position(tab: 2, drag: NowPlayingPager.rubberBand(tab: 2, dx: -120, width: width),
                                           width: width)
        XCTAssertEqual(NowPlayingPager.shownTab(position: end), 2)
        XCTAssertEqual(NowPlayingPager.selection(of: 2, at: end), 1)
    }

    func test_should_settleTheTabWhereThePageLands_when_released() {
        // Past a third of a page: the next page; the tabs then read that page alone.
        let next = NowPlayingPager.releaseTarget(tab: 0, drag: -width * 0.4, width: width, velocity: 0, cancelled: false)
        XCTAssertEqual(next, 1)
        let settled = NowPlayingPager.position(tab: next, drag: 0, width: width)
        XCTAssertEqual(NowPlayingPager.shownTab(position: settled), 1)
        XCTAssertEqual(NowPlayingPager.selection(of: 1, at: settled), 1)
        // A short drag springs back; a fling turns the page; a cancelled touch never does.
        XCTAssertEqual(NowPlayingPager.releaseTarget(tab: 1, drag: -width * 0.2, width: width, velocity: 0, cancelled: false), 1)
        XCTAssertEqual(NowPlayingPager.releaseTarget(tab: 1, drag: 30, width: width, velocity: 900, cancelled: false), 0)
        XCTAssertEqual(NowPlayingPager.releaseTarget(tab: 1, drag: -width * 0.6, width: width, velocity: 0, cancelled: true), 1)
        // Never past the ends.
        XCTAssertEqual(NowPlayingPager.releaseTarget(tab: 2, drag: -width, width: width, velocity: -900, cancelled: false), 2)
        XCTAssertEqual(NowPlayingPager.releaseTarget(tab: 0, drag: width, width: width, velocity: 900, cancelled: false), 0)
    }
}
