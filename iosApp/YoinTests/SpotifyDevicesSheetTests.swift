import XCTest
@testable import Yoin

/// The Spotify devices sheet's lines (NowPlaying/SpotifyDevicesSheet, Android DevicesSheet, parity C-U10):
/// a failure shows above the list on its own, so a list with devices no longer hides it.
final class SpotifyDevicesSheetTests: XCTestCase {
    private let device = SpotifySource.Device(id: "d", name: "Desk", type: "Computer", isActive: false)

    func test_should_sayNothingWhereTheListIs_when_devicesAreThere() {
        XCTAssertNil(SpotifyDevicesSheet.statusLine(devices: [device], failed: true))
        XCTAssertNil(SpotifyDevicesSheet.statusLine(devices: [device], failed: false))
    }

    func test_should_sayItIsLooking_when_notLoadedYet() {
        XCTAssertEqual(SpotifyDevicesSheet.statusLine(devices: nil, failed: false), loc("Looking for devices…"))
    }

    func test_should_leaveTheEmptyLineToTheFailure_when_theLoadFailed() {
        XCTAssertNil(SpotifyDevicesSheet.statusLine(devices: [], failed: true))
        XCTAssertNotNil(SpotifyDevicesSheet.statusLine(devices: [], failed: false))
    }
}
