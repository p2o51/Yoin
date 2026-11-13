import XCTest
@testable import Yoin

/// Android NowPlayingBudgetTest's DualPane cases, plus the iPad windows.
final class DualPaneBudgetTests: XCTestCase {
    private func dual(navBottom: CGFloat = 24) -> DualPaneBudget.Metrics {
        .init(transport: 136, titleOneLine: 24 + 2 + 36, titleTwoLines: 24 + 2 + 72, navBottom: navBottom)
    }

    func test_should_measureStackAt312AndKeepCoverLarge_when_owner1130x753() {
        let b = DualPaneBudget.resolve(rowWidth: 1082, contentHeight: 708, barHeight: 48, metrics: dual())
        // Two lines would leave the cover no room to focus: one line it is.
        XCTAssertEqual(b.titleForm, .oneLine)
        XCTAssertGreaterThanOrEqual(b.rest.cover, 290)
        XCTAssertEqual(b.rest.ratingSide, DualPaneBudget.ratingSide)
        XCTAssertEqual(b.rest.slot, b.rest.cover + DualPaneBudget.ratingSide, accuracy: 0.001)
        let focus = try! XCTUnwrap(b.focus)
        XCTAssertGreaterThanOrEqual(focus.cover, b.rest.cover + 40)
        XCTAssertEqual(focus.slot, max(DualPaneBudget.stackWidth, focus.cover))
        // The title stays in focus, exactly as it rests; only the rating retreats.
        XCTAssertEqual(focus.title, b.rest.title)
        XCTAssertEqual(focus.ratingSide, 0)
    }

    func test_should_reach312WithTwoLineTitle_when_tallWindow() {
        let b = DualPaneBudget.resolve(rowWidth: 1392, contentHeight: 876, barHeight: 48, metrics: dual())
        XCTAssertEqual(b.titleForm, .twoLines)
        XCTAssertEqual(b.rest.cover, 312)
        XCTAssertEqual(b.frameTop, 16)
    }

    func test_should_foldTitleIntoTopBar_when_veryShort() {
        let b = DualPaneBudget.resolve(rowWidth: 852, contentHeight: 420, barHeight: 48, metrics: dual())
        XCTAssertEqual(b.titleForm, .topBar)
        // A small cover still keeps the controls at the measuring width.
        XCTAssertEqual(b.rest.slot, DualPaneBudget.stackWidth)
    }

    func test_should_growCoverHalfAgainInFocus_when_iPad13Landscape() {
        // 1376 × 1032: 24 status bar, 20 home indicator; 1376 − 2 × 24 frame.
        let m = DualPaneBudget.metrics(controlSize: 56, rowGap: 4, labelLine: 20, artistLine: 24,
                                       titleLine: 36, navBottom: 20)
        XCTAssertEqual(m.transport, 136)
        let b = DualPaneBudget.resolve(rowWidth: 1328, contentHeight: 1008, metrics: m)
        XCTAssertEqual(b.titleForm, .twoLines)
        XCTAssertEqual(b.rest.cover, 312)
        XCTAssertEqual(b.rest.slot, 380)
        XCTAssertEqual(b.focus?.cover, 468)
        XCTAssertEqual(b.focus?.coverToTransport, 8)
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
        XCTAssertEqual(NowPlayingPadFrame.resolve(width: 1032, height: 1376), .wide)
        XCTAssertEqual(NowPlayingPadFrame.resolve(width: 834, height: 1210), .enlarged)
        XCTAssertEqual(NowPlayingPadFrame.resolve(width: 1210, height: 834), .wide)
        XCTAssertEqual(NowPlayingPadFrame.resolve(width: 900, height: 470), .enlarged)
        XCTAssertEqual(NowPlayingPadFrame.enlargedColumnWidth(windowWidth: 800), 640)
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
