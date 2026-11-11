import XCTest

/// iPad layout QA (not an assertion test): launches with the arguments in `YOIN_SHOT_ARGS`, turns the
/// device to `YOIN_SHOT_ORIENTATION` (landscape / portrait) and saves a screenshot to `YOIN_SHOT_PATH`.
/// `YOIN_SHOT_STEPS` (optional) drives the app first, for screenshots after a tap and for screen
/// recordings: `;`-separated `tap:x,y`, `drag:x,y,x2,y2`, `wait:s` and `shot:path`, with x / y as
/// fractions of the window. Skipped unless `YOIN_SHOT_PATH` is set, so the normal run never pays for it.
final class PadLayoutShotsUITests: XCTestCase {
    func testShot() throws {
        let env = ProcessInfo.processInfo.environment
        guard let path = env["YOIN_SHOT_PATH"] else { throw XCTSkip("QA only") }
        let app = XCUIApplication()
        app.launchArguments = (env["YOIN_SHOT_ARGS"] ?? "").split(separator: " ").map(String.init) + ["-silentAudio", "YES"]
        XCUIDevice.shared.orientation = env["YOIN_SHOT_ORIENTATION"] == "portrait" ? .portrait : .landscapeLeft
        app.launch()
        sleep(UInt32(env["YOIN_SHOT_WAIT"].flatMap(UInt32.init) ?? 14))
        let window = app.windows.firstMatch
        func point(_ x: Double, _ y: Double) -> XCUICoordinate {
            window.coordinate(withNormalizedOffset: CGVector(dx: x, dy: y))
        }
        for step in (env["YOIN_SHOT_STEPS"] ?? "").split(separator: ";") {
            let parts = step.split(separator: ":", maxSplits: 1).map(String.init)
            guard parts.count == 2 else { continue }
            let n = parts[1].split(separator: ",").compactMap { Double($0) }
            switch parts[0] {
            case "tap" where n.count == 2: point(n[0], n[1]).tap()
            case "drag" where n.count == 4:
                point(n[0], n[1]).press(forDuration: 0.05, thenDragTo: point(n[2], n[3]),
                                        withVelocity: .fast, thenHoldForDuration: 0.1)
            case "wait": Thread.sleep(forTimeInterval: Double(parts[1]) ?? 1)
            case "shot":
                try XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: parts[1]))
            default: break
            }
        }
        try XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: path))
    }
}
