import XCTest

/// iPad layout QA (not an assertion test): launches with the arguments in `YOIN_SHOT_ARGS`, turns the
/// device to `YOIN_SHOT_ORIENTATION` (landscape / portrait) and saves a screenshot to `YOIN_SHOT_PATH`.
/// Skipped unless those are set, so the normal run never pays for it.
final class PadLayoutShotsUITests: XCTestCase {
    func testShot() throws {
        let env = ProcessInfo.processInfo.environment
        guard let path = env["YOIN_SHOT_PATH"] else { throw XCTSkip("QA only") }
        let app = XCUIApplication()
        app.launchArguments = (env["YOIN_SHOT_ARGS"] ?? "").split(separator: " ").map(String.init) + ["-silentAudio", "YES"]
        XCUIDevice.shared.orientation = env["YOIN_SHOT_ORIENTATION"] == "portrait" ? .portrait : .landscapeLeft
        app.launch()
        sleep(UInt32(env["YOIN_SHOT_WAIT"].flatMap(UInt32.init) ?? 14))
        try XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: path))
    }
}
