import UIKit
import XCTest
@testable import Yoin

/// A right-click on a song opens its sheet (the iPad app on a Mac, an iPad with a pointer); which
/// pointer events count as that click.
final class SecondaryClickTests: XCTestCase {
    func test_should_countSecondaryButton_when_rightClicked() {
        XCTAssertTrue(SecondaryClickRecognizer.isSecondary(buttons: .secondary, modifiers: []))
    }

    func test_should_countControlClick_when_primaryWithControl() {
        XCTAssertTrue(SecondaryClickRecognizer.isSecondary(buttons: .primary, modifiers: .control))
    }

    func test_should_notCount_when_plainClickOrOtherModifier() {
        XCTAssertFalse(SecondaryClickRecognizer.isSecondary(buttons: .primary, modifiers: []))
        XCTAssertFalse(SecondaryClickRecognizer.isSecondary(buttons: .primary, modifiers: .command))
        XCTAssertFalse(SecondaryClickRecognizer.isSecondary(buttons: [], modifiers: .control))
    }

    func test_should_stayAClick_when_pointerMovesWithinSlop() {
        XCTAssertTrue(SecondaryClickRecognizer.isClick(from: .zero, to: CGPoint(x: 6, y: 8)))
        XCTAssertFalse(SecondaryClickRecognizer.isClick(from: .zero, to: CGPoint(x: 8, y: 8)))
    }

    /// An iPad song row's hold: held the long-press time and let go where it went down opens the sheet;
    /// a quicker press is the row's tap, and one that wandered off is a scroll or a drag.
    func test_should_openTheSheet_when_aRowIsHeldAndLetGoInPlace() {
        XCTAssertTrue(HoldReleaseRecognizer.isHold(elapsed: 0.4, travel: 0))
        XCTAssertTrue(HoldReleaseRecognizer.isHold(elapsed: 1.2, travel: 10))
        XCTAssertFalse(HoldReleaseRecognizer.isHold(elapsed: 0.39, travel: 0))
        XCTAssertFalse(HoldReleaseRecognizer.isHold(elapsed: 1.2, travel: 10.5))
    }

    /// Without the opt-in a pointer click is a direct touch with no button mask, and the recognizer
    /// (which needs `.indirectPointer` and `.secondary`) never fires.
    func test_should_optIntoIndirectInputEvents_when_appBundleBuilt() {
        let value = Bundle.main.object(forInfoDictionaryKey: "UIApplicationSupportsIndirectInputEvents")
        XCTAssertEqual(value as? Bool, true)
    }

    /// One window on iPad and on a Mac (docs/ipad.md): every scene shares AppModel.shared.
    func test_should_haveOneWindow_when_appBundleBuilt() {
        let manifest = Bundle.main.object(forInfoDictionaryKey: "UIApplicationSceneManifest") as? [String: Any]
        XCTAssertEqual(manifest?["UIApplicationSupportsMultipleScenes"] as? Bool, false)
    }
}
