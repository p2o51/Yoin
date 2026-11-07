import XCTest
@testable import Yoin

/// Cloud sync's "Good to know" (CloudSyncView, Android settings_sync_know_*, parity D-U3).
@MainActor
final class CloudSyncCopyTests: XCTestCase {
    func test_should_sayWhereTheDataLivesAndThatItIsNotEndToEndEncrypted() {
        let zh = Bundle.main.preferredLocalizations.first?.hasPrefix("zh") ?? false
        let lines = CloudSyncView.goodToKnowLines
        XCTAssertEqual(lines.count, 3)
        XCTAssertTrue(lines[0].contains(zh ? "不是端到端加密" : "not end-to-end encrypted"))
        XCTAssertTrue(lines[0].contains("Google Drive"))
        XCTAssertTrue(lines[1].contains(zh ? "密码" : "passwords"))
    }
}
