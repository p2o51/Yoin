import XCTest
@testable import Yoin

/// The playlist page's empty state copy (AlbumDetailView, Android detail_playlist_empty_*, parity B-U15).
final class PlaylistDetailCopyTests: XCTestCase {
    func test_should_sayThePlaylistIsEmpty_inAndroidsWords() {
        let zh = Bundle.main.preferredLocalizations.first?.hasPrefix("zh") ?? false
        XCTAssertEqual(loc("This playlist is empty."), zh ? "此播放列表为空。" : "This playlist is empty.")
    }

}

/// The detail pages' count line ("10 首 39 分钟"): its units through the catalog.
final class DetailCountUnitTests: XCTestCase {
    func test_should_localizeTheUnits_when_chinese() {
        let zh = Bundle.main.preferredLocalizations.first?.hasPrefix("zh") ?? false
        XCTAssertEqual(String(localized: "tracks", bundle: .main), zh ? "首" : "tracks")
        XCTAssertEqual(String(localized: "h", bundle: .main), zh ? "小时" : "h")
        XCTAssertEqual(String(localized: "min", bundle: .main), zh ? "分钟" : "min")
    }
}
