import XCTest
import YoinShared
@testable import Yoin

/// Which accounts read "Changes waiting to upload" under "Paused": a Start fresh the core put off because
/// the old account's changes are not in the Drive yet (Android CloudSyncManager.startFreshAccount).
final class CloudSyncFreshHoldTests: XCTestCase {
    private let paused: SyncAccountStatus = SyncAccountStatusAccountChanged.shared
    private let syncing: SyncAccountStatus = SyncAccountStatusSyncing.shared

    private func state(pending: Bool, _ accounts: [(id: String, status: SyncAccountStatus)]) -> CloudSyncState {
        CloudSyncState(phase: pending ? CloudSyncPhaseOffline.shared : CloudSyncPhaseUpToDate.shared, account: nil,
                       lastSyncAt: nil, pendingChanges: pending,
                       accounts: accounts.map {
                           SyncAccountRow(localProfileId: $0.id, provider: "subsonic", displayName: $0.id, status: $0.status)
                       },
                       cloudOnlyAccounts: [], devices: [], lastSummary: nil, readOnly: false, lastBackup: nil)
    }

    func test_should_holdTheAccount_when_startFreshComesBackWithChangesNotUploaded() {
        let offline = state(pending: true, [("a", paused), ("b", syncing)])
        XCTAssertEqual(CloudSyncFreshHold.after([], profile: "a", notUploaded: 3, state: offline), ["a"])
    }

    func test_should_holdNothing_when_startFreshWentThrough() {
        // The account is syncing as the new one; another paused account that was put off stays held.
        let after = state(pending: true, [("a", syncing), ("b", paused)])
        XCTAssertEqual(CloudSyncFreshHold.after(["a", "b"], profile: "a", notUploaded: 0, state: after), ["b"])
    }

    func test_should_holdNothing_when_theCycleAfterThePutOffUploadedEverything() {
        // Put off, but the cycle that follows every Start fresh got the changes up: asking again works.
        let uploaded = state(pending: false, [("a", paused)])
        XCTAssertEqual(CloudSyncFreshHold.after([], profile: "a", notUploaded: 3, state: uploaded), [])
    }

    func test_should_release_when_everythingIsUploaded() {
        XCTAssertEqual(CloudSyncFreshHold.pruned(["a"], state: state(pending: true, [("a", paused)])), ["a"])
        XCTAssertEqual(CloudSyncFreshHold.pruned(["a"], state: state(pending: false, [("a", paused)])), [])
    }

    func test_should_release_when_theAccountIsNoLongerPaused() {
        // Kept as before, signed back in as the old account, or removed from this device.
        XCTAssertEqual(CloudSyncFreshHold.pruned(["a", "b"], state: state(pending: true, [("a", syncing), ("b", paused)])), ["b"])
        XCTAssertEqual(CloudSyncFreshHold.pruned(["a"], state: state(pending: true, [])), [])
    }
}
