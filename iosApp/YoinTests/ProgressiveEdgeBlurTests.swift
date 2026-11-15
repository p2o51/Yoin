import SwiftUI
import XCTest
@testable import Yoin

/// Home's band is solid through the status bar, then eases out to clear at its foot (51, 2026-11-15: its short
/// falloff showed as an edge in dark).
final class ProgressiveEdgeBlurTests: XCTestCase {
    func test_should_beSolidToTheStart_and_easeToClear_when_fallingOffFromAPoint() {
        let stops = ProgressiveEdgeBlur.easedFalloff(from: 0.6)
        let env = EnvironmentValues()
        let alpha = stops.map { Double($0.color.resolve(in: env).opacity) }
        XCTAssertEqual(stops.first?.location, 0)
        XCTAssertEqual(stops[1].location, 0.6, accuracy: 1e-6)
        XCTAssertEqual(stops.last?.location ?? 0, 1, accuracy: 1e-6)
        XCTAssertEqual(stops.map(\.location), stops.map(\.location).sorted(), "in order")
        XCTAssertEqual(alpha[0], 1, accuracy: 1e-3)
        XCTAssertEqual(alpha[1], 1, accuracy: 1e-3, "solid up to the start")
        XCTAssertEqual(alpha.last ?? 1, 0, accuracy: 1e-3, "clear at the foot")
        // Stop 0 is the solid top, then the falloff's nine (eight steps): its middle is stop 1 + 4.
        XCTAssertEqual(alpha[5], 0.5, accuracy: 1e-3, "half way down, half")
        XCTAssertEqual(alpha, alpha.sorted(by: >), "never brighter further down")
    }
}
