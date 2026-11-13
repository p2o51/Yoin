import UIKit
import XCTest
@testable import Yoin

/// Pointer hover (an iPad with a pointer, the iPad app on a Mac): the M3 hovered state layer on rows,
/// lyric lines, the bar pill and the seek bar, and the split handle's pointer region.
final class PointerHoverTests: XCTestCase {
    func test_should_showM3HoverLayer_when_hovered() {
        XCTAssertEqual(PointerHover.layer, 0.08)
        XCTAssertEqual(PointerHover.opacity(hovered: true), 0.08)
        XCTAssertEqual(PointerHover.opacity(hovered: false), 0)
    }

    func test_should_keepPressedLayer_when_pressedUnderPointer() {
        XCTAssertEqual(PointerHover.opacity(hovered: true, pressed: true), 0.08)
        XCTAssertEqual(PointerHover.opacity(hovered: false, pressed: true, pressedLayer: 0.1), 0.1)
        XCTAssertEqual(PointerHover.opacity(hovered: true, pressed: true, pressedLayer: 0.1), 0.1)
    }

    func test_should_deepenSeekTrack_when_hovered() {
        XCTAssertEqual(WavyProgressBar.trackOpacity(buffered: false, hovered: false), 0.14, accuracy: 1e-9)
        XCTAssertEqual(WavyProgressBar.trackOpacity(buffered: true, hovered: false), 0.20, accuracy: 1e-9)
        XCTAssertEqual(WavyProgressBar.trackOpacity(buffered: false, hovered: true), 0.22, accuracy: 1e-9)
        XCTAssertEqual(WavyProgressBar.trackOpacity(buffered: true, hovered: true), 0.28, accuracy: 1e-9)
    }

    func test_should_takeOnlyHover_when_overSplitHandle() {
        XCTAssertTrue(ColumnResizePointerView.takes(.hover))
        XCTAssertFalse(ColumnResizePointerView.takes(.touches))
        XCTAssertFalse(ColumnResizePointerView.takes(nil))
    }
}
