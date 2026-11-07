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

    func test_should_listWhatSyncsInChinese_when_chinese() {
        let zh = Bundle.main.preferredLocalizations.first?.hasPrefix("zh") ?? false
        let stays = loc("Listening history\nPasswords, sign-ins and API keys\nCaches and downloads")
        XCTAssertEqual(stays.split(separator: "\n").count, 3)
        XCTAssertTrue(stays.hasPrefix(zh ? "播放记录" : "Listening history"))
        XCTAssertEqual(String(localized: "\(3) playlists", bundle: .main), zh ? "3 个歌单" : "3 playlists")
    }
}
