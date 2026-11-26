import XCTest

/// iOS parity round 4 tapped through on the mock library (Android #310, #326):
/// - the album's release date: "22.6.12" in the header, the full date under the last track, Released / Length on
///   the scrapbook receipt (`-albumReleaseDate`);
/// - the scrapbook track sheet's Notes page writes with the @ editor: "@" opens the popup, Return picks the top row
///   as a chip, Done saves the note with its mention (`-scrapNotesDemo plain-draft -scrapNotesOnMock YES`);
/// - AI copy names the service in use (`-aiProviderDemo`): Now Playing's Ask bar says "Ask DeepSeek" / 问千问.
///
/// The language comes from `YOIN_LANG` (en by default; `TEST_RUNNER_YOIN_LANG=zh-Hans` from xcodebuild).
/// Screenshots go to `PARITY_SHOT_DIR` when set, and are attached either way.
final class IosParity4UITests: XCTestCase {
    private var app: XCUIApplication!
    private var zh: Bool { lang.hasPrefix("zh") }
    private var lang: String { ProcessInfo.processInfo.environment["YOIN_LANG"] ?? "en" }

    override func setUp() {
        continueAfterFailure = false
        app = XCUIApplication()
    }

    private func launch(_ args: [String]) {
        app.launchArguments = ["-silentAudio", "YES", "-AppleLanguages", "(\(lang))",
                               "-AppleLocale", zh ? "zh_CN" : "en_US"] + args
        app.launch()
    }

    private func shot(_ name: String) {
        let image = XCUIScreen.main.screenshot()
        let a = XCTAttachment(screenshot: image)
        a.name = name
        a.lifetime = .keepAlways
        add(a)
        if let dir = ProcessInfo.processInfo.environment["PARITY_SHOT_DIR"] {
            let device = UIDevice.current.userInterfaceIdiom == .pad ? "ipad" : "iphone"
            let style = app.windows.firstMatch.exists && XCUIDevice.shared.appearance == .dark ? "dark" : "light"
            let file = "\(device)-\(style)-\(lang)-\(name).png"
            try? image.pngRepresentation.write(to: URL(fileURLWithPath: dir).appendingPathComponent(file))
        }
    }

    private func text(containing words: String) -> XCUIElement {
        app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", words)).firstMatch
    }

    // MARK: Release date

    func test_should_showTheReleaseDate_inTheHeader_underTheTracks_andOnTheReceipt() {
        // Copper Season: six songs, so the list's end is on screen on a phone too.
        launch(["-screen", "album", "-albumIndex", "2", "-albumReleaseDate", "2022-06-12"])
        XCTAssertTrue(app.staticTexts["22.6.12"].waitForExistence(timeout: 20), "the header's compact date")
        sleep(2)
        shot("album-header")

        app.terminate()
        launch(["-screen", "album-list", "-albumIndex", "2", "-albumReleaseDate", "2022-06-12"])
        let line = app.staticTexts["album.releaseDate"]
        XCTAssertTrue(line.waitForExistence(timeout: 20), "the date under the last track")
        XCTAssertEqual(line.label, zh ? "2022年6月12日" : "June 12, 2022")
        sleep(2)
        shot("album-list-end")

        app.terminate()
        launch(["-screen", "album-scrapbook-bottom", "-albumIndex", "2", "-albumReleaseDate", "2022-06"])
        XCTAssertTrue(app.staticTexts["Released"].waitForExistence(timeout: 20), "the receipt opens with Released")
        XCTAssertTrue(app.staticTexts["Jun 2022"].exists, "at the date's own precision")
        XCTAssertTrue(app.staticTexts["Length"].exists)
        sleep(2)
        shot("album-receipt")
    }

    /// Only a year: the header writes it whole; the line under the tracks is the year.
    func test_should_writeAYearAlone_inFull() {
        launch(["-screen", "album-list", "-albumIndex", "2", "-albumReleaseDate", "2022"])
        let line = app.staticTexts["album.releaseDate"]
        XCTAssertTrue(line.waitForExistence(timeout: 20))
        XCTAssertEqual(line.label, "2022")
        XCTAssertTrue(app.staticTexts["2022"].exists)
    }

    // MARK: Track sheet notes

    func test_should_writeAMention_inTheTrackSheetsNotes() {
        launch(["-screen", "album-scrapbook", "-scrapNotesDemo", "plain-draft", "-scrapNotesOnMock", "YES"])
        let field = app.textViews.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 25), "the sheet opens on Notes with a new note's editor")
        sleep(2)
        // A fresh simulator's keyboard introduction, if it shows.
        let intro = app.buttons["Continue"]
        if intro.exists { intro.tap() }
        field.tap()
        field.typeText("Sounds like @Pap")
        let row = app.buttons[zh ? "歌曲：Paper Moon" : "Song: Paper Moon"]
        XCTAssertTrue(row.waitForExistence(timeout: 8), "the @ popup offers the song")
        sleep(1)
        shot("sheet-note-mention-popup")
        field.typeText("\n")
        XCTAssertFalse(row.waitForExistence(timeout: 1.5), "Return picked it; the popup is gone")
        sleep(1)
        shot("sheet-note-chip")
        app.buttons[zh ? "完成" : "Done"].firstMatch.tap()
        XCTAssertTrue(text(containing: "Sounds like").waitForExistence(timeout: 5), "the saved note is on the page")
        sleep(1)
        shot("sheet-note-saved")
    }

    // MARK: AI service names

    func test_should_nameTheActiveService_onNowPlayingsAskBar() {
        let provider = zh ? "Qwen" : "DeepSeek"
        launch(["-screen", "np", "-npTab", "about-full", "-aboutDemo", "YES", "-aiProviderDemo", provider])
        let askWords = zh ? "问千问" : "Ask DeepSeek"
        let ask = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", askWords)).firstMatch
        XCTAssertTrue(ask.waitForExistence(timeout: 20), "the Ask bar names \(provider)")
        sleep(2)
        shot("np-about-ask-\(provider.lowercased())")

        // An AI row's long-press sheet: Regenerate with the same service.
        let review = text(containing: "last train home")
        XCTAssertTrue(review.waitForExistence(timeout: 5))
        review.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).press(forDuration: 0.9)
        let regenerate = app.buttons[zh ? "用千问重新生成" : "Regenerate with DeepSeek"]
        XCTAssertTrue(regenerate.waitForExistence(timeout: 5), "Regenerate names \(provider)")
        sleep(1)
        shot("np-about-regenerate-\(provider.lowercased())")
    }
}
