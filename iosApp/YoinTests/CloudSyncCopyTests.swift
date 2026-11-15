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

    func test_should_listEverythingTheSyncCarries_androidsLinesAndIOSsOwn() {
        let lines = CloudSyncView.syncedLines
        XCTAssertEqual(lines, [loc("Notes on songs"), loc("Song and album ratings, album reviews"),
                               loc("Memory titles, About and Ask"), loc("Home layout"), loc("AI lyric translations"),
                               loc("Playlist cloud backups"), loc("Translation language and Spotify Client ID"),
                               loc("Account names, server addresses")])
        // No scroll edge style on iOS (Android's settings line names it).
        XCTAssertFalse(lines.contains { $0.localizedCaseInsensitiveContains("scroll") || $0.contains("滚动") })
    }

    func test_should_offerAndroidsFourBenefits_beforeSyncIsOn() {
        let zh = Bundle.main.preferredLocalizations.first?.hasPrefix("zh") ?? false
        XCTAssertEqual(CloudSyncView.benefits.map(\.title),
                       zh ? ["笔记、评分与评论", "歌词翻译", "首页布局与设置", "支持离线记录"]
                          : ["Notes, ratings and reviews", "Lyric translations", "Home layout and settings", "Works offline"])
        XCTAssertEqual(CloudSyncView.benefits.map(\.icon), ["edit_note", "translate", "settings", "cloud"])
    }
}
