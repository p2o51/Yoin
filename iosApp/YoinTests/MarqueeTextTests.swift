import XCTest
@testable import Yoin

/// The bar's scrolling title (Theme/MarqueeText, Compose basicMarquee as Android's pill sets it, parity C-U9).
final class MarqueeTextTests: XCTestCase {
    private func x(_ t: Double) -> CGFloat {
        // A 300-wide line in a 150 box: travel 300 + 50 at 30/s = 11.67 s, then 2 s still.
        MarqueeText.offset(at: t, natural: 300, box: 150, velocity: 30, initialDelay: 2.5, repeatDelay: 2)
    }

    func test_should_holdStill_through_theFirstDelay() {
        XCTAssertEqual(x(0), 0)
        XCTAssertEqual(x(2.4), 0)
    }

    func test_should_travelLeftAtItsVelocity_when_running() {
        XCTAssertEqual(x(3.5), -30, accuracy: 0.001)
        XCTAssertEqual(x(12.5), -300, accuracy: 0.001)
    }

    func test_should_restThenGoAgain_when_theCopyArrives() {
        // Moving ends at 2.5 + 11.67 = 14.17; still until 16.17; then again.
        XCTAssertEqual(x(15), 0)
        XCTAssertEqual(x(17.1667), -30, accuracy: 0.01)
    }

    func test_should_leaveAGapOfAThirdOfTheBox() {
        XCTAssertEqual(MarqueeText.spacing(150), 50)
    }
}
