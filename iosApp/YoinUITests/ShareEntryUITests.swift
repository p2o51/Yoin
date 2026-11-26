import XCTest

/// The share sheet's entry points tapped through on the mock song (Android ShareSheet entries, PR #335): an About
/// row's long-press sheet offers "Share as image" (opening on 关于) except on the listener's own entry, and a
/// note's edit card offers Share in its top-right corner while the edit is unchanged (opening on 笔记).
/// Demo data: `-aboutDemo YES` (Gemini's rows, an answer and an own entry) and `-npEditNote YES` (the composer
/// open on the song's first note). Screenshots go to `SHARE_SHOT_DIR` when set, and are attached either way.
final class ShareEntryUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = false
        app = XCUIApplication()
    }

    private func launch(_ args: [String]) {
        app.launchArguments = ["-silentAudio", "YES", "-screen", "np", "-AppleLanguages", "(zh-Hans)",
                               "-AppleLocale", "zh_CN"] + args
        app.launch()
    }

    private func shot(_ name: String) {
        let image = XCUIScreen.main.screenshot()
        let a = XCTAttachment(screenshot: image)
        a.name = name
        a.lifetime = .keepAlways
        add(a)
        if let dir = ProcessInfo.processInfo.environment["SHARE_SHOT_DIR"] {
            try? image.pngRepresentation.write(to: URL(fileURLWithPath: dir).appendingPathComponent("\(name).png"))
        }
    }

    /// A long press at the element's centre (by coordinate: the page's fades leave it "not hittable" to XCUITest).
    private func hold(_ element: XCUIElement) {
        element.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).press(forDuration: 0.9)
    }

    private func text(containing words: String) -> XCUIElement {
        app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", words)).firstMatch
    }

    func test_should_openTheSheetOnAbout_when_anAboutRowsShareIsTapped() {
        launch(["-npTab", "about-full", "-aboutDemo", "YES"])
        let review = text(containing: "last train home")
        XCTAssertTrue(review.waitForExistence(timeout: 20), "the demo review is on the expanded About page")
        sleep(3)

        // Gemini's review: the long-press sheet offers Share as image.
        hold(review)
        let share = app.buttons["分享图片"]
        XCTAssertTrue(share.waitForExistence(timeout: 5), "an AI row's sheet offers Share as image")
        sleep(1)
        shot("entry-about-menu")
        share.tap()
        let aboutTab = app.buttons["shareTab:About"]
        XCTAssertTrue(aboutTab.waitForExistence(timeout: 8), "the share sheet opens")
        XCTAssertTrue(aboutTab.isSelected, "it opens on 关于")
        XCTAssertTrue(app.buttons["shareThumb:AboutReceipt"].exists)
        sleep(2)
        shot("entry-about-sheet")

        // Down again, then the listener's own entry (the page scrolled up to it): no Share as image.
        app.swipeDown(velocity: .fast)
        XCTAssertTrue(aboutTab.waitForNonExistence(timeout: 8), "the share sheet went down")
        let own = text(containing: "radio in a taxi")
        XCTAssertTrue(own.waitForExistence(timeout: 5))
        for _ in 0..<4 where own.frame.maxY > app.windows.firstMatch.frame.maxY - 120 {
            let from = review.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
            from.press(forDuration: 0.05, thenDragTo: from.withOffset(CGVector(dx: 0, dy: -260)))
            sleep(1)
        }
        hold(own)
        XCTAssertTrue(app.buttons["在剪贴簿里管理"].waitForExistence(timeout: 5), "the own entry's sheet is up")
        sleep(1)
        XCTAssertFalse(app.buttons["分享图片"].exists, "the listener's own entry has no Share as image")
        shot("entry-about-own-menu")
    }

    func test_should_openTheSheetOnNote_when_theEditCardsShareIsTapped() {
        launch(["-noteMentionsDemo", "YES", "-npEditNote", "YES"])
        let share = app.buttons["noteShare"]
        XCTAssertTrue(share.waitForExistence(timeout: 25), "an unchanged edit offers Share in the card's corner")
        sleep(1)
        shot("entry-note-edit")
        share.tap()
        let noteTab = app.buttons["shareTab:Note"]
        XCTAssertTrue(noteTab.waitForExistence(timeout: 8), "the share sheet opens")
        XCTAssertTrue(noteTab.isSelected, "it opens on 笔记")
        XCTAssertTrue(app.buttons["shareThumb:NoteBig"].exists)
        sleep(2)
        shot("entry-note-sheet")
    }

    /// Review shots of the sheet (51, 2026-11-26), only when `SHARE_TONES_SHOT_DIR` is set: About opening on its
    /// tertiary tone with every family offered (five swatches), the tabs fixed while the rest scrolls under the
    /// sheet's top edge, and Note opening on its primary tone. The runner's appearance decides light / dark.
    func test_should_shootTheSheetsTonesAndTop_forReview() throws {
        guard let dir = ProcessInfo.processInfo.environment["SHARE_TONES_SHOT_DIR"] else {
            throw XCTSkip("set SHARE_TONES_SHOT_DIR to shoot the share sheet for review")
        }
        let mode = ProcessInfo.processInfo.environment["SHARE_TONES_SHOT_MODE"] ?? "light"
        func save(_ name: String) {
            let image = XCUIScreen.main.screenshot()
            try? image.pngRepresentation.write(to: URL(fileURLWithPath: dir).appendingPathComponent("\(name)-\(mode).png"))
        }
        for (lang, locale) in [("en", "en_US"), ("zh-Hans", "zh_CN")] {
            for (tab, layout) in [("about", "AboutQuote"), ("note", "NoteBig")] {
                app.terminate()
                app.launchArguments = ["-silentAudio", "YES", "-screen", "np", "-AppleLanguages", "(\(lang))",
                                       "-AppleLocale", locale, "-shareDemo", tab, "-shareLayout", layout]
                app.launch()
                let tabButton = app.buttons["shareTab:\(tab == "about" ? "About" : "Note")"]
                XCTAssertTrue(tabButton.waitForExistence(timeout: 20), "the demo sheet opens on \(tab)")
                sleep(2)
                save("sheet-\(tab)-\(lang)")
                // Scroll the content up under the fixed tabs: the swatch row comes into view.
                let preview = app.otherElements["sharePreview"]
                let from = (preview.exists ? preview : app.windows.firstMatch).coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.6))
                from.press(forDuration: 0.05, thenDragTo: from.withOffset(CGVector(dx: 0, dy: -260)))
                sleep(2)
                XCTAssertTrue(tabButton.isHittable, "the tabs stay at the top")
                save("sheet-\(tab)-\(lang)-scrolled")
            }
        }
    }
}
