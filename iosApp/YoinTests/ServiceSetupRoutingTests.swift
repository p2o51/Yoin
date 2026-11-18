import XCTest
@testable import Yoin

final class ServiceSetupRoutingTests: XCTestCase {
    func test_should_mapProviderKeys_when_cloudOnlyAccountHasSetup() {
        XCTAssertEqual(ServiceSetupRouting.service(forProvider: "subsonic"), .subsonic)
        XCTAssertEqual(ServiceSetupRouting.service(forProvider: "spotify"), .spotify)
        XCTAssertEqual(ServiceSetupRouting.service(forProvider: "applemusic"), .appleMusic)
    }

    func test_should_haveNoAdd_when_providerIsUnknown() {
        XCTAssertNil(ServiceSetupRouting.service(forProvider: "local"))
        XCTAssertNil(ServiceSetupRouting.service(forProvider: ""))
    }

    func test_should_showSpotifySignIn_when_addingOrManagingAnAccount() {
        XCTAssertTrue(ServiceSetupRouting.showsSpotifySignIn(adding: true, hasAccount: false))
        XCTAssertTrue(ServiceSetupRouting.showsSpotifySignIn(adding: false, hasAccount: true))
        XCTAssertFalse(ServiceSetupRouting.showsSpotifySignIn(adding: false, hasAccount: false))
    }
}
