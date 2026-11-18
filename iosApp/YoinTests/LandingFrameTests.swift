import SwiftUI
import XCTest
@testable import Yoin

/// The landing stage's columns (LandingFrame, Android LandingStage, parity A-U5).
final class LandingFrameTests: XCTestCase {
    private let insets = EdgeInsets(top: 20, leading: 0, bottom: 20, trailing: 0)

    func test_should_useOneColumn_when_aPhoneIsUpright() {
        let f = LandingFrame(size: CGSize(width: 402, height: 874), insets: insets, keyboard: 0)
        XCTAssertFalse(f.wide)
        XCTAssertEqual(f.colX, (402 - f.colW) / 2)
        XCTAssertEqual(f.helloPillRect, f.pillRect)
    }

    func test_should_putTheWindowInTheRightColumn_when_wide() {
        let f = LandingFrame(size: CGSize(width: 1180, height: 820), insets: insets, keyboard: 0)
        XCTAssertTrue(f.wide)
        XCTAssertEqual(f.colW, 519.2, accuracy: 0.01)
        XCTAssertEqual(f.colX + f.colW, 1180 - 70.8, accuracy: 0.01)
        // Hello's pill stays centred under Yoin.
        XCTAssertEqual(f.helloPillRect.midX, 590, accuracy: 0.01)
        XCTAssertEqual(f.helloPillRect.width, 380)
    }

    func test_should_useTwoColumns_when_aPhoneLiesOnItsSide() {
        XCTAssertTrue(LandingFrame.isWide(CGSize(width: 874, height: 402)))
        XCTAssertFalse(LandingFrame.isWide(CGSize(width: 820, height: 1180)))
    }
}
