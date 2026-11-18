import XCTest

/// The Home avatar's two gestures (Home/AccountQuickSwitch.swift; 51, 2026-11-07: a tap stopped opening
/// the account page after the hold to switch accounts landed). A tap opens Settings as it always did; a
/// hold of 0.45 s and a slide onto the next account switches to it and opens nothing.
///
/// Needs the demo Subsonic servers on 127.0.0.1:5021 and :5022 (two accounts to switch between).
final class AvatarGestureUITests: XCTestCase {
    var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["-silentAudio", "YES", "-AppleLanguages", "(en)", "-AppleLocale", "en_US",
                               "-subsonicURL", "http://127.0.0.1:5021", "-subsonicUser", "demo", "-subsonicPass", "demo",
                               "-profilesScript", "wait:1500;add:http://127.0.0.1:5022;wait:1500"]
        app.launch()
    }

    private var avatar: XCUIElement { app.buttons["Account"].firstMatch }

    private func waitForHome() {
        XCTAssertTrue(avatar.waitForExistence(timeout: 15))
        // The account script and the Memories bubble settle first.
        sleep(4)
    }

    func test_should_openSettings_when_theAvatarIsTapped() {
        waitForHome()
        avatar.tap()
        XCTAssertTrue(app.staticTexts["Settings"].waitForExistence(timeout: 5), "a tap opens the account page")
    }

    func test_should_switchAccountsAndOpenNothing_when_theAvatarIsHeldAndSlid() {
        waitForHome()
        let before = avatar.value as? String
        let center = avatar.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        // The first account in the column sits 60 pt below the avatar's centre (AccountFanGeometry).
        center.press(forDuration: 0.9, thenDragTo: center.withOffset(CGVector(dx: 0, dy: 60)))
        sleep(3)
        XCTAssertFalse(app.staticTexts["Settings"].exists, "the hold's release opens nothing")
        XCTAssertTrue(avatar.waitForExistence(timeout: 5))
        XCTAssertNotEqual(avatar.value as? String, before, "the hold switched accounts")
    }
}
