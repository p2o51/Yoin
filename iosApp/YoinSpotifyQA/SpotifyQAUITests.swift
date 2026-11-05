import XCTest

/// Spotify screens against `scripts/fake_spotify.py` (start it first; see docs/qa-script.md).
/// Each step saves a screenshot and the element tree under /tmp/yoin-spotify-qa. These are
/// walk-throughs that stop to be looked at, not assertions of every pixel: a missing control
/// fails the step it belongs to.
final class SpotifyQAUITests: XCTestCase {
    static let out = URL(fileURLWithPath: ProcessInfo.processInfo.environment["YOIN_QA_OUT"] ?? "/tmp/yoin-spotify-qa")
    var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = true
        try? FileManager.default.createDirectory(at: Self.out, withIntermediateDirectories: true)
        app = XCUIApplication()
        app.launchArguments = ["-silentAudio", "YES", "-spotifyFake", "http://127.0.0.1:4997",
                               "-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
        sleep(3)
    }

    // MARK: Helpers

    func shot(_ name: String) {
        let png = XCUIScreen.main.screenshot().pngRepresentation
        try? png.write(to: Self.out.appendingPathComponent("\(name).png"))
        try? app.debugDescription.write(to: Self.out.appendingPathComponent("\(name).txt"), atomically: true, encoding: .utf8)
    }

    /// The first hittable element (any type) whose label is [label].
    func element(_ label: String, timeout: TimeInterval = 6) -> XCUIElement? {
        let q = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", label))
        let deadline = Date().addingTimeInterval(timeout)
        repeat {
            for i in 0..<min(q.count, 12) {
                let e = q.element(boundBy: i)
                if e.exists, e.isHittable { return e }
            }
            usleep(300_000)
        } while Date() < deadline
        return nil
    }

    func element(beginning prefix: String, timeout: TimeInterval = 6) -> XCUIElement? {
        let q = app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", prefix))
        let deadline = Date().addingTimeInterval(timeout)
        repeat {
            for i in 0..<min(q.count, 12) {
                let e = q.element(boundBy: i)
                if e.exists, e.isHittable { return e }
            }
            usleep(300_000)
        } while Date() < deadline
        return nil
    }

    @discardableResult
    func tap(_ label: String, file: StaticString = #filePath, line: UInt = #line) -> Bool {
        guard let e = element(label) else {
            XCTFail("no \(label)", file: file, line: line)
            return false
        }
        e.tap()
        sleep(1)
        return true
    }

    func openLibrary(_ tab: String) {
        tap("Library")
        tap(tab)
        sleep(2)
    }

    func openPlaylist(_ name: String) {
        openLibrary("Playlists")
        guard let e = element(beginning: "\(name),") ?? element(name) else { return XCTFail("no playlist \(name)") }
        e.tap()
        sleep(3)
    }

    /// A song row's menu (long press).
    func songMenu(_ title: String) -> Bool {
        guard let e = element(beginning: title) ?? element(title) else { XCTFail("no song \(title)"); return false }
        e.press(forDuration: 0.8)
        sleep(2)
        return true
    }

    // MARK: Walk-throughs

    func testLibrarySongsAndPlaylists() {
        openLibrary("Songs")
        shot("library-songs-top")
        for _ in 0..<40 { app.swipeUp(velocity: .fast) }
        sleep(3)
        for _ in 0..<40 { app.swipeUp(velocity: .fast) }
        sleep(3)
        shot("library-songs-foot")
        tap("Playlists")
        sleep(2)
        shot("library-playlists")
    }

    /// A chip of Search's scope/filter row (under the field), not the bar's button of the same name.
    func chip(_ label: String) -> XCUIElement? {
        let q = app.buttons.matching(NSPredicate(format: "label == %@", label))
        for i in 0..<q.count {
            let e = q.element(boundBy: i)
            if e.exists, e.frame.minY > 110, e.frame.minY < 200 { return e }
        }
        return nil
    }

    func tapChip(_ label: String) {
        guard let c = chip(label) else { return XCTFail("no chip \(label)") }
        if !c.isHittable || c.frame.maxX > app.frame.maxX { chip("All")?.swipeLeft(); sleep(1) }
        c.tap()
        sleep(3)
    }

    func testSearchSpotifyPlaylists() {
        tap("Search")
        sleep(1)
        app.typeText("la")
        sleep(3)
        // The keyboard's first-run slide-typing tip covers the results.
        if let tip = element("Continue", timeout: 2) { tip.tap(); sleep(1) }
        shot("search-scope-library")
        tapChip("Spotify")
        shot("search-scope-spotify")
        tapChip("Playlists")
        shot("search-spotify-playlists")
        // The keyboard down (its Search key), so the swipes land on the results.
        app.typeText("\n")
        sleep(1)
        for _ in 0..<8 { app.swipeUp() }
        sleep(3)
        shot("search-spotify-playlists-more")
        for _ in 0..<8 { app.swipeUp() }
        sleep(3)
        shot("search-spotify-playlists-end")
    }

    func testOwnedPlaylistMenu() {
        openPlaylist("QA Mix")
        shot("playlist-owned")
        tap("More actions")
        sleep(1)
        shot("playlist-owned-menu")
    }

    func testFollowedPlaylist() {
        openPlaylist("Editorial Picks")
        shot("playlist-followed")
        XCTAssertNil(element("More actions", timeout: 2), "a followed playlist whose songs are refused has no menu")
    }

    func testCollaborativePlaylist() {
        openPlaylist("Shared Jam")
        shot("playlist-collaborative")
    }

    func testAddToPlaylistPicker() {
        openLibrary("Songs")
        guard let row = app.cells.firstMatch.exists ? app.cells.firstMatch : element(beginning: "Silver") else {
            return XCTFail("no song row")
        }
        row.press(forDuration: 0.8)
        sleep(2)
        shot("song-menu")
        tap("Add to playlist")
        sleep(3)
        shot("add-to-playlist-picker")
        // Writable only: the owned and the collaborative ones, never the followed one.
        XCTAssertNil(element(beginning: "Editorial Picks", timeout: 1), "a followed playlist is not a destination")
        if let road = element(beginning: "Road Trip", timeout: 3) { road.tap(); sleep(3) }
        shot("add-to-playlist-added")
    }

    func testRemoveDuplicateOccurrence() {
        openPlaylist("QA Mix")
        app.swipeUp()
        sleep(2)
        shot("playlist-duplicate-before")
        // "Satellite Quiet" (tr0003) is QA Mix's first row and its third: remove the first.
        let rows = app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", "Satellite Quiet"))
        guard let row = (0..<rows.count).map({ rows.element(boundBy: $0) }).first(where: { $0.isHittable && $0.frame.height < 100 }) else {
            return XCTFail("no row")
        }
        row.press(forDuration: 0.9)
        sleep(2)
        shot("duplicate-song-menu")
        tap("Remove from playlist")
        sleep(3)
        shot("playlist-duplicate-after")
    }

    func testBackupShowsSpotify() {
        openPlaylist("QA Mix")
        tap("More actions")
        tap("Cloud backup")
        sleep(4)
        shot("backup-made")
        tap("Back")
        tap("Account")
        sleep(2)
        // Settings › Cloud sync › Playlist cloud backup.
        for _ in 0..<12 where element(beginning: "Cloud sync", timeout: 1) == nil { app.swipeUp() }
        element(beginning: "Cloud sync")?.tap()
        sleep(2)
        let entry = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Playlist cloud backup,")).firstMatch
        guard entry.waitForExistence(timeout: 5) else { return XCTFail("no backups entry") }
        entry.tap()
        sleep(2)
        shot("settings-backups")
        if let row = element(beginning: "QA Mix", timeout: 3) { row.tap(); sleep(2) }
        shot("settings-backup-detail")
        if let restore = element("Restore", timeout: 3) { restore.tap(); sleep(3) }
        shot("settings-backup-restore")
        // Restore to the Spotify account itself: a new playlist made through POST me/playlists + items.
        if let dest = element("Yoin QA, Spotify", timeout: 3) { dest.tap(); sleep(3) }
        shot("settings-backup-restore-target")
        for label in ["Restore", "Create playlist", "Restore as new playlist"] {
            if let go = element(label, timeout: 2) { go.tap(); sleep(4); break }
        }
        shot("settings-backup-restore-done")
    }
}
