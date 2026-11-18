import MediaPlayer
import XCTest
@testable import Yoin

@MainActor
final class PlayModeGlyphParityTests: XCTestCase {
    // MARK: Play mode glyph (C-U8)

    func test_should_tell_all_six_play_modes_apart() {
        var seen = Set<String>()
        for shuffle in [false, true] {
            for mode in RepeatMode.allCases {
                let g = PlayModeGlyph(shuffle: shuffle, repeatMode: mode)
                XCTAssertEqual(g.icon, shuffle ? "shuffle" : "repeat")
                XCTAssertEqual(g.one, mode == .one)
                XCTAssertEqual(g.off, mode == .off)
                seen.insert("\(g.icon)/\(g.one)/\(g.off)")
            }
        }
        XCTAssertEqual(seen.count, 6)
    }

}
