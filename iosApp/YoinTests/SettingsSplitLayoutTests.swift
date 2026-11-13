import XCTest
@testable import Yoin

/// The two-pane Settings on a wide iPad window (I27; Android SplitLayout.settingsSplitRatio).
final class SettingsSplitLayoutTests: XCTestCase {
    func test_should_splitFrom840_when_windowWidens() {
        XCTAssertFalse(SettingsSplitLayout.splits(window: 839))
        XCTAssertTrue(SettingsSplitLayout.splits(window: 840))
    }

    func test_should_keepListNear420WithinAThirdTo40Percent_when_windowsVary() {
        // 13-inch landscape: 420 is under 40 % and over 30 %.
        XCTAssertEqual(SettingsSplitLayout.listWidth(window: 1376), 420, accuracy: 0.001)
        // Narrow wide window: capped at 40 %.
        XCTAssertEqual(SettingsSplitLayout.listWidth(window: 840), 336, accuracy: 0.001)
        // Very wide: at least 30 %.
        XCTAssertEqual(SettingsSplitLayout.listWidth(window: 2000), 600, accuracy: 0.001)
    }

    func test_should_pickFirstAccountElseAddAccount_when_panesOpen() {
        XCTAssertEqual(SettingsSplitLayout.firstRow(accounts: ["a", "b"]), .account("a"))
        XCTAssertEqual(SettingsSplitLayout.firstRow(accounts: []), .addAccount)
    }

    func test_should_pickNewAccount_when_addedFromAddAccount() {
        XCTAssertEqual(SettingsSplitLayout.reconciled(.addAccount, old: ["a"], new: ["a", "b"]), .account("b"))
        XCTAssertEqual(SettingsSplitLayout.reconciled(.addAccount, old: ["a"], new: ["a"]), .addAccount)
    }

    func test_should_handToFirstRow_when_pickedAccountRemoved() {
        XCTAssertEqual(SettingsSplitLayout.reconciled(.account("b"), old: ["a", "b"], new: ["a"]), .account("a"))
        XCTAssertEqual(SettingsSplitLayout.reconciled(.account("a"), old: ["a"], new: []), .addAccount)
        XCTAssertEqual(SettingsSplitLayout.reconciled(.labs, old: ["a"], new: []), .labs)
    }
}
