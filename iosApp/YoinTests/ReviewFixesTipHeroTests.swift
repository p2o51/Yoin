import XCTest
import SwiftUI
@testable import Yoin

/// The tip page on iPad (its buttons above the dock, its back through the path when it is a route) and
/// Now Playing's one-line hero (the artist at its own width, capped).
@MainActor
final class ReviewFixesTipHeroTests: XCTestCase {
    func test_should_clearTheDock_when_pushedFromTheTipsListWithOnlyTheHomeIndicator() {
        // 22 + 60 = 82 from the window's edge; the home indicator's 20 is already the page's.
        XCTAssertEqual(TipMetrics.padDockPadding(bottomInset: 20), 62)
        XCTAssertEqual(TipMetrics.padDockPadding(bottomInset: 0), 82)
    }

    func test_should_addNothing_when_theColumnAlreadyPadsPastTheDock() {
        XCTAssertEqual(TipMetrics.padDockPadding(bottomInset: 92), 0)
        XCTAssertEqual(TipMetrics.padDockPadding(bottomInset: 82), 0)
    }

    func test_should_popThePath_when_thePageIsTheRouteOnTop() {
        XCTAssertTrue(TipMetrics.closesThroughPath([.tip("a")], start: "a"))
        XCTAssertTrue(TipMetrics.closesThroughPath([.settings, .tip("a")], start: "a"))
    }

    func test_should_dismiss_when_pushedFromTheTipsList() {
        XCTAssertFalse(TipMetrics.closesThroughPath([.settings, .tips(focus: nil)], start: "a"))
        XCTAssertFalse(TipMetrics.closesThroughPath([], start: "a"))
        XCTAssertFalse(TipMetrics.closesThroughPath([.tip("b")], start: "a"))
    }

    func test_should_offerTheCap_when_moreIsOffered() {
        XCTAssertEqual(CappedWidth.offer(400, cap: 160), 160)
        XCTAssertEqual(CappedWidth.offer(nil, cap: 160), 160)
    }

    func test_should_offerTheParentsWidth_when_lessThanTheCap() {
        XCTAssertEqual(CappedWidth.offer(90, cap: 160), 90)
    }
}
