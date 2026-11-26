import XCTest

/// Home edit v2 by hand, on the mock Home (`-screen home`; design.md §主页 › 就地编辑, parity p4 B1):
/// - a press held on a section enters edit mode and lifts it; dragged, it moves in place and the drop writes the
///   order ("Section j of N" on each block in edit mode);
/// - held in the bottom 72 pt band, the feed scrolls under the finger and the block lands last;
/// - a pinch folds the feed into the compact list of strips and a pinch out unfolds it; the header's switch does
///   both with one hand.
///
/// The language comes from `YOIN_LANG` (en by default). Screenshots go to `HOME_SHOT_DIR` when set
/// (`TEST_RUNNER_HOME_SHOT_DIR` from xcodebuild), and are attached either way.
final class HomeEditV2UITests: XCTestCase {
    private var app: XCUIApplication!
    private var lang: String { ProcessInfo.processInfo.environment["YOIN_LANG"] ?? "en" }

    override func setUp() {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["-screen", "home", "-homeLayoutReset", "YES", "-silentAudio", "YES", "-AppleLanguages", "(\(lang))",
                               "-AppleLocale", lang.hasPrefix("zh") ? "zh_CN" : "en_US"]
        app.launch()
    }

    private func shot(_ name: String) {
        let image = XCUIScreen.main.screenshot()
        let a = XCTAttachment(screenshot: image)
        a.name = name
        a.lifetime = .keepAlways
        add(a)
        if let dir = ProcessInfo.processInfo.environment["HOME_SHOT_DIR"] {
            let device = UIDevice.current.userInterfaceIdiom == .pad ? "ipad" : "iphone"
            let style = ProcessInfo.processInfo.environment["HOME_SHOT_STYLE"] ?? "light"
            try? image.pngRepresentation.write(to: URL(fileURLWithPath: dir).appendingPathComponent("\(device)-\(style)-\(name).png"))
        }
    }

    private func block(_ id: String) -> XCUIElement { app.descendants(matching: .any)["home.section.\(id)"] }
    private func strip(_ id: String) -> XCUIElement { app.descendants(matching: .any)["home.edit.strip.\(id)"] }
    private var feed: XCUIElement { app.scrollViews["home.feed"].firstMatch }

    /// "Section j of N" → j.
    private func place(_ e: XCUIElement) -> Int? {
        guard let v = e.value as? String else { return nil }
        let digits = v.split(whereSeparator: { !$0.isNumber })
        return digits.first.flatMap { Int($0) }
    }

    private func count(_ e: XCUIElement) -> Int? {
        guard let v = e.value as? String else { return nil }
        return v.split(whereSeparator: { !$0.isNumber }).dropFirst().first.flatMap { Int($0) }
    }

    private func enterEditFromFooter() {
        // The footer's Edit Home, at the end of the feed.
        let editHome = app.buttons["Edit Home"].firstMatch
        for _ in 0..<12 where !editHome.isHittable { feed.swipeUp(velocity: .fast) }
        XCTAssertTrue(editHome.waitForExistence(timeout: 5))
        editHome.tap()
        XCTAssertTrue(app.buttons["home.edit.done"].waitForExistence(timeout: 5))
        for _ in 0..<12 { feed.swipeDown(velocity: .fast) }
    }

    func test_should_reorderInPlace_when_aSectionIsHeldAndDragged() {
        let title = app.staticTexts["Activities"].firstMatch
        XCTAssertTrue(title.waitForExistence(timeout: 15))
        sleep(2)
        let start = title.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        // Held past the long press it enters edit mode and lifts; then it follows the finger down past Jump Back In.
        let target = start.withOffset(CGVector(dx: 0, dy: 520))
        start.press(forDuration: 0.9, thenDragTo: target, withVelocity: 300, thenHoldForDuration: 0.6)
        XCTAssertTrue(app.buttons["home.edit.done"].waitForExistence(timeout: 5), "the hold entered edit mode")
        let activities = block("activities")
        XCTAssertTrue(activities.waitForExistence(timeout: 5))
        XCTAssertGreaterThan(place(activities) ?? 0, 1, "Activities moved down: \(String(describing: activities.value))")
        XCTAssertEqual(place(block("jump_back_in")), 1)
        shot("drag-dropped")
        // Undo puts it back, Done leaves.
        app.buttons["home.edit.undo"].tap()
        XCTAssertEqual(place(block("activities")), 1)
        app.buttons["home.edit.done"].tap()
        XCTAssertFalse(block("activities").waitForExistence(timeout: 1))
    }

    func test_should_scrollAndLandLast_when_theFingerRestsAtTheBottomEdge() {
        enterEditFromFooter()
        let first = block("activities")
        XCTAssertTrue(first.waitForExistence(timeout: 5))
        let n = count(first) ?? 0
        XCTAssertGreaterThan(n, 3)
        let window = app.windows.firstMatch
        let start = first.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.3))
        // The bottom band, just above the bar (Home's frame stops at the bar).
        let feedFrame = feed.frame
        let edge = window.coordinate(withNormalizedOffset: .zero)
            .withOffset(CGVector(dx: feedFrame.midX * 0.6, dy: feedFrame.maxY - 30))
        shot("edge-start")
        start.press(forDuration: 0.5, thenDragTo: edge, withVelocity: 600, thenHoldForDuration: 9)
        sleep(1)
        shot("edge-dropped")
        XCTAssertEqual(place(block("activities")), n, "carried to the end by the edge scroll: \(String(describing: block("activities").value))")
    }

    func test_should_foldIntoTheListAndBack_when_pinchedAndToggled() {
        enterEditFromFooter()
        XCTAssertTrue(block("activities").waitForExistence(timeout: 5))
        // Two fingers on the page (within the feed: a finger landing on the bar is the bar's).
        block("activities").pinch(withScale: 0.4, velocity: -1)
        XCTAssertTrue(strip("activities").waitForExistence(timeout: 5), "a pinch in folds the feed into strips")
        sleep(1)
        shot("list")
        // Dragged by its handle, a strip moves like a block.
        let handleFrom = strip("activities").coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.5))
        handleFrom.press(forDuration: 0.3, thenDragTo: handleFrom.withOffset(CGVector(dx: 0, dy: 72 * 2 + 10)), withVelocity: 300,
                         thenHoldForDuration: 0.5)
        sleep(1)
        XCTAssertEqual(place(strip("activities")), 3, "\(String(describing: strip("activities").value))")
        shot("list-dragged")
        app.scrollViews["home.edit.list"].firstMatch.pinch(withScale: 2.2, velocity: 1)
        XCTAssertTrue(strip("activities").waitForNonExistence(timeout: 5), "a pinch out unfolds it")
        XCTAssertEqual(place(block("activities")), 3, "the new order is the feed's too")
        // The header's switch, one hand.
        app.buttons["home.edit.toggle"].firstMatch.tap()
        XCTAssertTrue(strip("jump_back_in").waitForExistence(timeout: 5))
        app.buttons["home.edit.toggle"].firstMatch.tap()
        XCTAssertTrue(strip("jump_back_in").waitForNonExistence(timeout: 5))
        app.buttons["home.edit.done"].tap()
    }

    func test_should_leave_when_blankSpaceIsTapped() {
        enterEditFromFooter()
        XCTAssertTrue(block("activities").waitForExistence(timeout: 5))
        // The header's greeting is blank page space in edit mode.
        app.staticTexts["Edit Home"].firstMatch.tap()
        XCTAssertTrue(app.buttons["home.edit.done"].waitForNonExistence(timeout: 5))
    }
}
