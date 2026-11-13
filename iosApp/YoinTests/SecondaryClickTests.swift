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

    /// Without the opt-in a pointer click is a direct touch with no button mask, and the recognizer
    /// (which needs `.indirectPointer` and `.secondary`) never fires.
    func test_should_optIntoIndirectInputEvents_when_appBundleBuilt() {
        let value = Bundle.main.object(forInfoDictionaryKey: "UIApplicationSupportsIndirectInputEvents")
        XCTAssertEqual(value as? Bool, true)
    }
}
