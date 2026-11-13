import XCTest
@testable import Yoin

/// The one flag for "the iPad app is running on a Mac" (App/Platform.swift) and its two stand-ins.
final class PlatformTests: XCTestCase {
    func test_should_reportTheProcess_when_nothingStandsIn() {
        XCTAssertEqual(Platform.system.isMac, ProcessInfo.processInfo.isiOSAppOnMac)
        XCTAssertNil(Platform.standIn)
        XCTAssertEqual(Platform.current, Platform.system)
    }

    func test_should_beAMacInsideTheScopeOnly_when_stoodIn() {
        Platform.$standIn.withValue(.mac) {
            XCTAssertTrue(Platform.current.isMac)
            Platform.$standIn.withValue(.device) { XCTAssertFalse(Platform.current.isMac) }
            XCTAssertTrue(Platform.current.isMac)
        }
        XCTAssertEqual(Platform.current, Platform.system)
    }

    func test_should_reachTasksStartedInside_when_stoodIn() async {
        let inside = await Platform.$standIn.withValue(.mac) {
            await Task { Platform.current.isMac }.value
        }
        XCTAssertTrue(inside)
        let outside = await Task { Platform.current }.value
        XCTAssertEqual(outside, Platform.system)
    }

    func test_should_standInForAMac_when_launchedWithPlatformMac() throws {
        let suite = "platform-tests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }

        XCTAssertEqual(Platform.atLaunch(system: .device, defaults: defaults), .device)
        defaults.set(true, forKey: "platformMac")
        XCTAssertEqual(Platform.atLaunch(system: .device, defaults: defaults), .mac)
        // The argument only ever adds a Mac; a real one stays a Mac without it.
        defaults.set(false, forKey: "platformMac")
        XCTAssertEqual(Platform.atLaunch(system: .mac, defaults: defaults), .mac)
    }
}
