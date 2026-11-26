import XCTest

/// Two-column Home on iPad by hand, on the mock Home (`-screen home`; design.md §主页「两栏」, parity p4 B2):
/// - landscape: a section dragged to 85 % across a full row pairs with it ("Left half" / "Right half" on the blocks);
///   rotated, the order is kept and the pairs come back;
/// - a full row's ⌟ dragged left past the midpoint makes it the left half of a pair with the row after it;
/// - the tier is the container's width: an 11" iPad in portrait (834 pt) is one column, in landscape two.
///
/// Screenshots go to `HOME_SHOT_DIR` when set (`TEST_RUNNER_HOME_SHOT_DIR`), and are attached either way.
final class HomeTwoColumnUITests: XCTestCase {
    private var app: XCUIApplication!
    private var lang: String { ProcessInfo.processInfo.environment["YOIN_LANG"] ?? "en" }

    override func setUpWithError() throws {
        continueAfterFailure = false
        try XCTSkipUnless(UIDevice.current.userInterfaceIdiom == .pad, "two columns are an iPad (and Mac) layout")
        app = XCUIApplication()
        app.launchArguments = ["-screen", "home", "-homeLayoutReset", "YES", "-silentAudio", "YES", "-AppleLanguages", "(\(lang))",
                               "-AppleLocale", lang.hasPrefix("zh") ? "zh_CN" : "en_US"]
    }

    override func tearDown() {
        XCUIDevice.shared.orientation = .portrait
        super.tearDown()
    }

    private func launch(_ orientation: UIDeviceOrientation) {
        XCUIDevice.shared.orientation = orientation
        app.launch()
        XCTAssertTrue(app.staticTexts["Activities"].firstMatch.waitForExistence(timeout: 20))
        sleep(2)
    }

    private func shot(_ name: String) {
        let image = XCUIScreen.main.screenshot()
        let a = XCTAttachment(screenshot: image)
        a.name = name
        a.lifetime = .keepAlways
        add(a)
        if let dir = ProcessInfo.processInfo.environment["HOME_SHOT_DIR"] {
            let w = Int(app.windows.firstMatch.frame.width), h = Int(app.windows.firstMatch.frame.height)
            let style = ProcessInfo.processInfo.environment["HOME_SHOT_STYLE"] ?? "light"
            try? image.pngRepresentation.write(to: URL(fileURLWithPath: dir).appendingPathComponent("ipad-\(w)x\(h)-\(style)-\(name).png"))
        }
    }

    private func block(_ id: String) -> XCUIElement { app.descendants(matching: .any)["home.section.\(id)"].firstMatch }
    private func resize(_ id: String) -> XCUIElement { app.descendants(matching: .any)["home.resize.\(id)"].firstMatch }
    private var feed: XCUIElement { app.scrollViews["home.feed"].firstMatch }
    private func value(_ e: XCUIElement) -> String { (e.value as? String) ?? "" }

    /// "Section j of N…" → j.
    private func place(_ e: XCUIElement) -> Int? {
        value(e).split(whereSeparator: { !$0.isNumber }).first.flatMap { Int($0) }
    }

    private func enterEditFromFooter() {
        let editHome = app.buttons["Edit Home"].firstMatch
        for _ in 0..<12 where !editHome.isHittable { feed.swipeUp(velocity: .fast) }
        XCTAssertTrue(editHome.waitForExistence(timeout: 5))
        editHome.tap()
        XCTAssertTrue(app.buttons["home.edit.done"].waitForExistence(timeout: 5))
        for _ in 0..<12 { feed.swipeDown(velocity: .fast) }
        sleep(1)
    }

    /// Scrolls the feed until [e] is fully on screen above the dock.
    private func bringIntoView(_ e: XCUIElement) {
        let window = app.windows.firstMatch.frame
        for _ in 0..<8 {
            let f = e.frame
            if f.minY > 80 && f.maxY < window.maxY - 120 { return }
            let from = feed.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.6))
            from.press(forDuration: 0.01, thenDragTo: from.withOffset(CGVector(dx: 0, dy: f.minY > 80 ? -240 : 240)))
        }
    }

    func test_should_pairBesideAFullRow_when_draggedTo85PercentAcrossIt_andKeepItThroughARotation() {
        launch(.landscapeLeft)
        shot("landscape-feed")
        enterEditFromFooter()
        shot("landscape-edit")
        // The large-screen default: Activities | Jump Back In, then All Memories and Made For You full rows.
        XCTAssertTrue(value(block("activities")).contains("Left half"), value(block("activities")))
        XCTAssertTrue(value(block("jump_back_in")).contains("Right half"), value(block("jump_back_in")))
        let dragged = block("made_for_you"), target = block("memory_grid")
        XCTAssertFalse(value(dragged).contains("half"), value(dragged))
        XCTAssertFalse(value(target).contains("half"), value(target))
        bringIntoView(dragged)
        // Held near its top at 85 % across and carried up into the full row above: the finger reaches that row's
        // middle (where its outer 30 % pairs) before the block has travelled the half row that would swap them, and
        // the pair's row is where the finger is.
        let from = dragged.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: 0.08))
        let to = target.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: 0.5))
        from.press(forDuration: 0.6, thenDragTo: to, withVelocity: 250, thenHoldForDuration: 0.8)
        sleep(1)
        shot("landscape-paired")
        XCTAssertTrue(value(block("memory_grid")).contains("Left half"), value(block("memory_grid")))
        XCTAssertTrue(value(block("made_for_you")).contains("Right half"), value(block("made_for_you")))
        let ids = ["activities", "jump_back_in", "memory_grid", "made_for_you", "lyric_highlight"]
        let order = ids.map { place(block($0)) }
        // Rotated: the order stays. A 13" iPad in portrait is still two columns (1032 wide) and keeps the pair; an
        // 11" one (834) is one column, every section a row of its own.
        XCUIDevice.shared.orientation = .portrait
        sleep(2)
        shot("portrait-after-rotate")
        XCTAssertEqual(ids.map { place(block($0)) }, order)
        if app.windows.firstMatch.frame.width >= 840 {
            XCTAssertTrue(value(block("memory_grid")).contains("Left half"), value(block("memory_grid")))
        } else {
            XCTAssertFalse(value(block("memory_grid")).contains("half"), value(block("memory_grid")))
            XCTAssertFalse(resize("memory_grid").exists)
        }
        // Back in landscape the pair is back (two columns remember it).
        XCUIDevice.shared.orientation = .landscapeLeft
        sleep(2)
        XCTAssertTrue(value(block("memory_grid")).contains("Left half"), value(block("memory_grid")))
        XCTAssertTrue(value(block("made_for_you")).contains("Right half"), value(block("made_for_you")))
        XCTAssertEqual(ids.map { place(block($0)) }, order)
        // Undo puts the pair back as it was; Done leaves.
        app.buttons["home.edit.undo"].tap()
        sleep(1)
        XCTAssertFalse(value(block("memory_grid")).contains("half"), value(block("memory_grid")))
        app.buttons["home.edit.done"].tap()
    }

    func test_should_turnHalf_when_aFullRowsHandleIsDraggedLeftPastTheMidpoint() {
        launch(.landscapeLeft)
        enterEditFromFooter()
        let handle = resize("memory_grid")
        XCTAssertTrue(handle.waitForExistence(timeout: 5))
        bringIntoView(block("made_for_you"))
        XCTAssertEqual(value(handle), "Full width")
        let start = handle.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        let width = block("memory_grid").frame.width
        start.press(forDuration: 0.05, thenDragTo: start.withOffset(CGVector(dx: -width * 0.4, dy: 0)), withVelocity: 400,
                    thenHoldForDuration: 0.5)
        sleep(2)
        shot("span-half")
        XCTAssertTrue(value(block("memory_grid")).contains("Left half"), value(block("memory_grid")))
        XCTAssertTrue(value(block("made_for_you")).contains("Right half"), value(block("made_for_you")))
        XCTAssertEqual(value(resize("memory_grid")), "Half width")
        // Its handle back right past the midpoint: full again.
        let back = resize("memory_grid").coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        back.press(forDuration: 0.05, thenDragTo: back.withOffset(CGVector(dx: width * 0.4, dy: 0)), withVelocity: 400,
                   thenHoldForDuration: 0.5)
        sleep(2)
        XCTAssertFalse(value(block("memory_grid")).contains("half"), value(block("memory_grid")))
        app.buttons["home.edit.done"].tap()
    }

    func test_should_beOneColumn_when_theContainerIsNarrowerThan840() throws {
        launch(.portrait)
        let width = app.windows.firstMatch.frame.width
        try XCTSkipIf(width >= 840, "a \(Int(width)) pt portrait window is two columns (13\"); the 11\" is the narrow one")
        shot("portrait-one-column")
        enterEditFromFooter()
        shot("portrait-one-column-edit")
        XCTAssertFalse(resize("activities").exists, "no span handle in one column")
        XCTAssertFalse(value(block("activities")).contains("half"), value(block("activities")))
        XCTAssertFalse(value(block("jump_back_in")).contains("half"), value(block("jump_back_in")))
        // Turned to landscape (1194 wide) the same draft is two columns, the large-screen default's pairs shown.
        XCUIDevice.shared.orientation = .landscapeLeft
        sleep(2)
        shot("landscape-two-column-edit")
        XCTAssertTrue(value(block("activities")).contains("Left half"), value(block("activities")))
        XCTAssertTrue(resize("activities").exists)
        app.buttons["home.edit.done"].tap()
    }
}
