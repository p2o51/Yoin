import XCTest
@testable import Yoin

/// The lyric tools' auto-hide (Android #334, `LyricToolsTapTargetTest` / `LyricToolsWakeGuardTest`): a key still
/// fading out takes its tap; only fully faded keys drop their hit area and accessibility, and the first tap on their
/// strip only wakes them; a lyric line outside the strip still seeks; VoiceOver / Switch Control never hide them.
final class LyricToolsIdleTests: XCTestCase {
    func test_should_runTheKey_when_tappedWhileFadingOut() {
        // Fading out: idle has started, the keys aren't hidden yet.
        XCTAssertEqual(LyricToolsIdleRules.route(hidden: false, inStrip: true), .key)
        XCTAssertTrue(LyricToolsIdleRules.keysInteractive(hidden: false))
        XCTAssertFalse(LyricToolsIdleRules.wakeGuardUp(hidden: false), "no guard over keys still on screen")
    }

    func test_should_onlyWake_when_theHiddenStripIsTapped() {
        XCTAssertEqual(LyricToolsIdleRules.route(hidden: true, inStrip: true), .wakeOnly, "never a seek, never search")
        XCTAssertFalse(LyricToolsIdleRules.keysInteractive(hidden: true), "hidden keys: no hit area, not announced")
        XCTAssertTrue(LyricToolsIdleRules.wakeGuardUp(hidden: true))
    }

    func test_should_stillSeek_when_aLyricLineAboveTheStripIsTapped() {
        XCTAssertEqual(LyricToolsIdleRules.route(hidden: true, inStrip: false), .lyrics)
        XCTAssertEqual(LyricToolsIdleRules.route(hidden: false, inStrip: false), .lyrics)
    }

    func test_should_neverAutoHide_when_voiceOverOrSwitchControlIsOn() {
        XCTAssertTrue(LyricToolsIdleRules.autoHides(eligible: true, voiceOver: false, switchControl: false))
        XCTAssertFalse(LyricToolsIdleRules.autoHides(eligible: true, voiceOver: true, switchControl: false))
        XCTAssertFalse(LyricToolsIdleRules.autoHides(eligible: true, voiceOver: false, switchControl: true))
        XCTAssertFalse(LyricToolsIdleRules.autoHides(eligible: false, voiceOver: false, switchControl: false))
        XCTAssertEqual(LyricToolsIdleRules.idleSeconds, 3)
    }
}
