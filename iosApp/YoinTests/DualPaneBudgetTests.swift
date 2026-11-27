import XCTest
@testable import Yoin

/// Android NowPlayingBudgetTest's DualPane cases, plus the iPad windows.
final class DualPaneBudgetTests: XCTestCase {
    private func dual(navBottom: CGFloat = 24) -> DualPaneBudget.Metrics {
        .init(transport: 136, titleOneLine: 24 + 2 + 36, titleTwoLines: 24 + 2 + 72, navBottom: navBottom)
    }

    /// 13" landscape: 1376 × 1032, 24 status bar, 20 home indicator; 1376 − 2 × 24 frame.
    private func pad13() -> DualPaneBudget.Result {
        let m = DualPaneBudget.metrics(controlSize: 56, rowGap: 4, labelLine: 20, artistLine: 24,
                                       titleLine: 36, navBottom: 20)
        return DualPaneBudget.resolve(rowWidth: 1328, contentHeight: 1008, metrics: m)
    }

    func test_should_centreA1220RowSplit44To56_when_iPad13Landscape() {
        let b = pad13()
        XCTAssertEqual(b.contentWidth, DualPaneBudget.contentMaxWidth)
        XCTAssertEqual(b.contentInset, 54)
        XCTAssertEqual(b.rest.leftColumn, 526)
        XCTAssertEqual(b.rest.rightColumn, 1220 - 24 - 526)
        XCTAssertEqual(b.rest.leftColumn / (b.rest.leftColumn + b.rest.rightColumn), 0.44, accuracy: 0.01)
    }

    func test_should_growCoverTo400To460WithTitleAndTransportUnderIt_when_iPad13Landscape() {
        let b = pad13()
        XCTAssertEqual(b.titleForm, .twoLines)
        XCTAssertEqual(b.rest.cover, 458)
        XCTAssertEqual(b.rest.slot, b.rest.cover + DualPaneBudget.ratingSide)
        XCTAssertLessThanOrEqual(b.rest.slot, b.rest.leftColumn)
        // Cover, title and transport are one block, no hole: it fits the row with air above and below.
        XCTAssertEqual(b.rest.block, 458 + 20 + 98 + 12 + 116)
        XCTAssertLessThan(b.rest.block, b.rowHeight)
        let focus = try! XCTUnwrap(b.focus)
        XCTAssertEqual(focus.ratingSide, 0)
        XCTAssertEqual(focus.title, b.rest.title)
        XCTAssertGreaterThanOrEqual(focus.cover, b.rest.cover + 40)
        XCTAssertLessThanOrEqual(focus.block, b.rowHeight)
        XCTAssertGreaterThanOrEqual(focus.rightColumn, DualPaneBudget.rightColumnMin)
    }

    func test_should_keepCoverIn400To460_when_iPad11Landscape() {
        // 1210 × 834: the content is the window less its frame (narrower than 1220).
        let m = DualPaneBudget.metrics(controlSize: 56, rowGap: 4, labelLine: 20, artistLine: 24,
                                       titleLine: 36, navBottom: 20)
        let b = DualPaneBudget.resolve(rowWidth: 1162, contentHeight: 810, metrics: m)
        XCTAssertEqual(b.contentInset, 0)
        XCTAssertEqual(b.titleForm, .twoLines)
        XCTAssertGreaterThanOrEqual(b.rest.cover, 400)
        XCTAssertLessThanOrEqual(b.rest.cover, 460)
        XCTAssertLessThanOrEqual(b.rest.block, b.rowHeight)
    }

    func test_should_takeOneLineTitle_when_shortStageManagerWindow() {
        let b = DualPaneBudget.resolve(rowWidth: 852, contentHeight: 496, barHeight: 48, metrics: dual(navBottom: 20))
        XCTAssertEqual(b.titleForm, .oneLine)
        XCTAssertGreaterThanOrEqual(b.rest.cover, DualPaneBudget.coverMin)
        XCTAssertLessThanOrEqual(b.rest.block, b.rowHeight)
    }

    func test_should_foldTitleIntoTopBar_when_veryShort() {
        let b = DualPaneBudget.resolve(rowWidth: 852, contentHeight: 420, barHeight: 48, metrics: dual())
        XCTAssertEqual(b.titleForm, .topBar)
        XCTAssertEqual(b.rest.title, 0)
        XCTAssertLessThanOrEqual(b.rest.block, b.rowHeight)
    }

    func test_should_notOfferFocus_when_coverCannotGrowEnough() {
        XCTAssertFalse(DualPaneBudget.focusOffered(rest: 312, focus: 340))
        XCTAssertTrue(DualPaneBudget.focusOffered(rest: 312, focus: 352))
        XCTAssertFalse(DualPaneBudget.focusOffered(rest: 400, focus: 445))
    }

    func test_should_squeezeLowerGapsFirst_when_deficitIsSmall() {
        let lower = [DualPaneBudget.Gap(16, 8), DualPaneBudget.Gap(16, 8)]
        let upper = [DualPaneBudget.Gap(16, 8)]
        XCTAssertEqual(DualPaneBudget.squeeze(8, lower: lower, upper: upper),
                       .init(lower: 0.5, upper: 0, unmet: 0))
        XCTAssertEqual(DualPaneBudget.squeeze(20, lower: lower, upper: upper),
                       .init(lower: 1, upper: 0.5, unmet: 0))
        XCTAssertEqual(DualPaneBudget.squeeze(30, lower: lower, upper: upper),
                       .init(lower: 1, upper: 1, unmet: 6))
    }

    func test_should_pickWideOrEnlarged_when_windowSizeChanges() {
        XCTAssertEqual(NowPlayingPadFrame.resolve(width: 1376, height: 1032), .wide)
        // Option B2 (owner 2026-11-27): a portrait window is the enlarged poster, 13" included.
        XCTAssertEqual(NowPlayingPadFrame.resolve(width: 1032, height: 1376), .enlarged)
        XCTAssertEqual(NowPlayingPadFrame.resolve(width: 946, height: 1032), .enlarged)
        XCTAssertEqual(NowPlayingPadFrame.resolve(width: 834, height: 1210), .enlarged)
        XCTAssertEqual(NowPlayingPadFrame.resolve(width: 1210, height: 834), .wide)
        XCTAssertEqual(NowPlayingPadFrame.resolve(width: 900, height: 470), .enlarged)
        XCTAssertEqual(NowPlayingPadFrame.enlargedColumnWidth(windowWidth: 800), 584)
        XCTAssertEqual(NowPlayingPadFrame.enlargedColumnWidth(windowWidth: 1032), 584)
        XCTAssertEqual(NowPlayingPadFrame.enlargedColumnWidth(windowWidth: 690), 530)
        XCTAssertEqual(NowPlayingPadFrame.enlargedColumnWidth(windowWidth: 400), 360)
        XCTAssertEqual(NowPlayingPadFrame.enlargedRatingColumn(column: 640), 104)
        XCTAssertEqual(NowPlayingPadFrame.enlargedRatingColumn(column: 360), 56)
        XCTAssertEqual(NowPlayingPadFrame.enlargedRatingColumn(column: 530), 82, accuracy: 0.001)
        XCTAssertEqual(NowPlayingPadFrame.enlargedControlSize(column: 640), 60)
        XCTAssertEqual(NowPlayingPadFrame.enlargedControlSize(column: 560), 60)
        XCTAssertEqual(NowPlayingPadFrame.enlargedControlSize(column: 530), 56)
    }
}
