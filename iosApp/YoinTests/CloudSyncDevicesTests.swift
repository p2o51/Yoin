import XCTest
import YoinShared
@testable import Yoin

/// Which Cloud sync device rows offer removing their Drive copy (D-F5).
final class CloudSyncDevicesTests: XCTestCase {
    private func device(_ id: String, this: Bool = false) -> SyncDeviceRow {
        SyncDeviceRow(deviceId: id, name: id, isThisDevice: this, lastSyncAt: nil)
    }

    func test_should_neverOfferRemoval_when_theRowIsThisDevice() {
        XCTAssertFalse(CloudSyncDevices.canRemove(device("me", this: true)))
        XCTAssertTrue(CloudSyncDevices.canRemove(device("pixel")))
    }

    func test_should_listOnlyOtherDevices_when_filteringRemovable() {
        let rows = [device("me", this: true), device("pixel"), device("ipad")]
        XCTAssertEqual(CloudSyncDevices.removable(rows).map(\.deviceId), ["pixel", "ipad"])
    }
}
