import XCTest
@testable import Yoin

/// The playlist page's empty state copy (AlbumDetailView, Android detail_playlist_empty_*, parity B-U15).
final class PlaylistDetailCopyTests: XCTestCase {
    func test_should_sayThePlaylistIsEmpty_inAndroidsWords() {
        let zh = Bundle.main.preferredLocalizations.first?.hasPrefix("zh") ?? false
        XCTAssertEqual(loc("This playlist is empty."), zh ? "此播放列表为空。" : "This playlist is empty.")
    }

}
