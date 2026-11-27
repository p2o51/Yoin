import XCTest

/// iPad round 2 (owner 2026-11-27, iosApp/docs/ipad.md), on the mock data:
/// - Full Now Playing: landscape is option A (the title and the transport right under a large cover, the block
///   centred, not the title at the window's foot); portrait is the enlarged poster (B2), not two columns;
/// - a regular-width window pops the Queue and the song sheet over from what opened them (popovers, not sheets);
/// - ⌘F opens Search;
/// - two-column Home: Lyric Cards' beta intro takes one grid cell, so its row is one unit tall.
///
/// Screenshots are attached; with `PAD_SHOT_DIR` (`TEST_RUNNER_PAD_SHOT_DIR`) they are written there too.
final class PadRound2UITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        try XCTSkipUnless(UIDevice.current.userInterfaceIdiom == .pad, "an iPad layout")
        app = XCUIApplication()
    }

    override func tearDown() {
        XCUIDevice.shared.orientation = .portrait
        super.tearDown()
    }

    private func launch(_ screen: String, _ orientation: UIDeviceOrientation, extra: [String] = []) {
        XCUIDevice.shared.orientation = orientation
        app.launchArguments = ["-screen", screen, "-silentAudio", "YES", "-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
            + extra
        app.launch()
    }

    private func shot(_ name: String) {
        let image = XCUIScreen.main.screenshot()
        let a = XCTAttachment(screenshot: image)
        a.name = name
        a.lifetime = .keepAlways
        add(a)
        if let dir = ProcessInfo.processInfo.environment["PAD_SHOT_DIR"] {
            try? image.pngRepresentation.write(to: URL(fileURLWithPath: dir).appendingPathComponent("\(name).png"))
        }
    }

    private var window: CGRect { app.windows.firstMatch.frame }

    // MARK: Now Playing

    func test_should_setTitleAndTransportUnderTheCover_when_fullNowPlayingLandscape() throws {
        launch("np", .landscapeLeft)
        let pause = app.buttons["Pause"].firstMatch
        XCTAssertTrue(pause.waitForExistence(timeout: 20))
        sleep(2)
        shot("np-landscape-A")
        let artist = app.staticTexts["Mina Asahi"].firstMatch
        XCTAssertTrue(artist.exists)
        let w = window
        try XCTSkipIf(w.width < w.height, "the simulator didn't turn")
        // Left column (44 % of the centred content), and no ~500 pt hole: the title sits just above the transport,
        // both in the window's middle band rather than its foot.
        XCTAssertLessThan(artist.frame.midX, w.midX)
        XCTAssertLessThan(pause.frame.minY - artist.frame.maxY, 160)
        XCTAssertLessThan(pause.frame.maxY, w.height - 60)
        // The tabs sit in the top bar.
        let lyricsTab = app.buttons["Lyrics"].firstMatch
        if lyricsTab.exists { XCTAssertLessThan(lyricsTab.frame.midY, w.minY + 140) }
    }

    func test_should_showThePosterColumn_when_fullNowPlayingPortrait() throws {
        launch("np", .portrait)
        let pause = app.buttons["Pause"].firstMatch
        XCTAssertTrue(pause.waitForExistence(timeout: 20))
        sleep(2)
        shot("np-portrait-B2")
        let w = window
        try XCTSkipIf(w.width > w.height, "the simulator didn't turn")
        // One centred column: the transport is in the column, not a left column of a two-column player.
        let column = min(max(w.width - 160, 360), 640)
        XCTAssertGreaterThanOrEqual(pause.frame.minX, (w.width - column) / 2 - 1)
        // The bottom pills (Queue, Devices, Write) are the enlarged player's.
        XCTAssertTrue(app.buttons["Queue"].firstMatch.exists)
    }

    // MARK: Popovers

    func test_should_popTheQueueOverFromItsButton_when_regularWidth() {
        launch("np", .landscapeLeft)
        let queue = app.buttons["Queue"].firstMatch
        XCTAssertTrue(queue.waitForExistence(timeout: 20))
        sleep(2)
        queue.tap()
        XCTAssertTrue(app.popovers.firstMatch.waitForExistence(timeout: 5), "the Queue is a popover on iPad")
        sleep(1)
        shot("queue-popover")
        // It hangs off the button, not the middle of the screen.
        let popover = app.popovers.firstMatch.frame
        XCTAssertLessThan(abs(popover.minY - queue.frame.maxY), 60)
    }

    func test_should_popTheSongSheetOverFromTheRow_when_heldOnRegularWidth() throws {
        launch("album", .landscapeLeft)
        // The row is one element whose label holds the title (its texts are combined).
        let rows = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "Window Seat"))
        XCTAssertTrue(rows.firstMatch.waitForExistence(timeout: 20))
        sleep(2)
        // The row on screen (the album's Scrapbook page beside it lists the songs too, off screen).
        let w = window
        let row = try XCTUnwrap(rows.allElementsBoundByIndex.first { w.contains(CGPoint(x: $0.frame.midX, y: $0.frame.midY)) })
        app.windows.firstMatch.coordinate(withNormalizedOffset: .zero)
            .withOffset(CGVector(dx: row.frame.midX - w.minX, dy: row.frame.midY - w.minY))
            .press(forDuration: 1.0)
        XCTAssertTrue(app.popovers.firstMatch.waitForExistence(timeout: 5), "the song sheet is a popover on iPad")
        sleep(1)
        shot("song-menu-popover")
        let popover = app.popovers.firstMatch.frame
        XCTAssertLessThan(popover.width, 520)
    }

    // MARK: Keyboard

    func test_should_openSearch_when_commandF() throws {
        launch("home", .landscapeLeft)
        XCTAssertTrue(app.staticTexts["Activities"].firstMatch.waitForExistence(timeout: 20))
        sleep(2)
        app.typeKey("f", modifierFlags: .command)
        let field = app.textFields["Search library"].firstMatch
        // The simulator only delivers a shortcut when something holds the keyboard focus; without it the key
        // never reaches the app (round-1 audit, keyboard/), so this is skipped rather than failed there.
        try XCTSkipUnless(field.waitForExistence(timeout: 5), "⌘F wasn't delivered by the simulator")
        shot("cmd-f-search")
    }

    // MARK: Home

    func test_should_giveLyricCardsIntroOneGridUnit_when_twoColumnHome() throws {
        launch("home-bottom", .landscapeLeft, extra: ["-homeLayoutReset", "YES", "-lyricHighlightIntroSeen", "NO",
                                                       "-lyricHighlightOptedIn", "NO"])
        let intro = app.staticTexts["You found a beta feature"].firstMatch
        XCTAssertTrue(intro.waitForExistence(timeout: 20))
        sleep(2)
        shot("home-lyric-intro-row")
        try XCTSkipIf(window.width < 840, "one column")
        let title = app.staticTexts["Lyric Cards"].firstMatch
        XCTAssertTrue(title.exists)
        // The next row's title starts one unit (40 + 150) and the feed's spacing below: not the 2–3 units that left
        // a hole beside the intro.
        let titles = ["Recently Played", "Your Playlists", "Rediscover", "Made for You", "All Memories", "Jump Back In",
                      "Activities", "Recently Added", "Other Accounts"]
        let below = titles.map { app.staticTexts[$0].firstMatch }
            .filter { $0.exists && $0.frame.minY > title.frame.maxY + 10 }
            .map { $0.frame.minY }
        let next = try XCTUnwrap(below.min(), "a row under Lyric Cards")
        XCTAssertLessThan(next - title.frame.minY, 260)
    }
}
