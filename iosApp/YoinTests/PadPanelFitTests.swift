import XCTest
@testable import Yoin

/// iPad Now Playing side panel: where it fits beside what is open (AppModel.padPanelFits), on the iPad
/// windows the 2026-11-27 simulator audit walked (docs/ipad.md, "Now Playing").
@MainActor
final class PadPanelFitTests: XCTestCase {
    func test_should_fitBesideTwoColumns_when_a13InchIsLandscape() {
        // 1376 − 420 = 956 ≥ 744: Home | album | panel.
        XCTAssertTrue(AppModel.padPanelFits(window: 1376, columnOpen: true))
    }

    func test_should_notFitBesideTwoColumns_when_a13InchTurnsPortrait() {
        // 1032 − 420 = 612 < 744: the panel goes Full instead of squeezing Home to ~140 pt.
        XCTAssertFalse(AppModel.padPanelFits(window: 1032, columnOpen: true))
        XCTAssertTrue(AppModel.padPanelFits(window: 1032, columnOpen: false))
    }

    func test_should_fitBesideOneColumn_when_anIPadMiniOrAn11InchIsPortrait() {
        // 834 − 417 = 417.
        XCTAssertTrue(AppModel.padPanelFits(window: 834, columnOpen: false))
        // 744 − 372 = 372 ≥ 360.
        XCTAssertTrue(AppModel.padPanelFits(window: 744, columnOpen: false))
    }

    func test_should_notFit_when_halfA13InchSplitView() {
        // 683 − 360 = 323 < 360: Home beside it broke its cards' text a letter a line.
        XCTAssertFalse(AppModel.padPanelFits(window: 683, columnOpen: false))
    }

    func test_should_leaveAWholeColumn_when_justAtTheLine() {
        // 720 − 360 = 360; 719 leaves 359.
        XCTAssertTrue(AppModel.padPanelFits(window: 720, columnOpen: false))
        XCTAssertFalse(AppModel.padPanelFits(window: 719, columnOpen: false))
    }
}
