import MediaPlayer
import XCTest
@testable import Yoin

@MainActor
final class RemoteCommandParityTests: XCTestCase {
    // MARK: Remote commands (D-F8)

    func test_should_map_play_modes_to_remote_command_types() {
        XCTAssertEqual(RemoteCommandState(shuffle: true, repeatMode: .one, provider: nil, liked: false).shuffleType, .items)
        XCTAssertEqual(RemoteCommandState(shuffle: false, repeatMode: .off, provider: nil, liked: false).shuffleType, .off)
        for mode in RepeatMode.allCases {
            let type = RemoteCommandState(shuffle: false, repeatMode: mode, provider: nil, liked: false).repeatType
            XCTAssertEqual(RemoteCommandState.repeatMode(type), mode)
        }
        XCTAssertTrue(RemoteCommandState.shuffle(.items))
        XCTAssertTrue(RemoteCommandState.shuffle(.collections))
        XCTAssertFalse(RemoteCommandState.shuffle(.off))
    }

    func test_should_enable_like_only_for_subsonic_songs() {
        func enabled(_ p: String?) -> Bool {
            RemoteCommandState(shuffle: false, repeatMode: .all, provider: p, liked: false).likeEnabled
        }
        XCTAssertTrue(enabled("subsonic"))
        XCTAssertFalse(enabled("spotify"))
        XCTAssertFalse(enabled("apple"))
        XCTAssertFalse(enabled(nil))
    }
}
